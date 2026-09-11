// [X-custom] RikkaHub-X 存储管理重构(P0)：回收策略（纯函数,无 IO/无 Android 依赖）
package me.rerere.rikkahub.x.storage

/** 单个资产的回收判定结果。 */
enum class GcDecision {
    /** 仍被引用 —— **绝不删**（这是回收安全的核心保证）。 */
    KEEP_REFERENCED,

    /** 已无引用，但还在宽限期内 —— 等。 */
    WAIT_GRACE,

    /** 已无引用且过宽限 —— 可删。 */
    DELETE,

    /** 重试次数用尽 —— 放弃（留在盘上并记审计，不再空转重试）。 */
    ABANDON,
}

/**
 * 资产回收策略。
 *
 * 全部为**纯函数**：输入（是否有活引用 / 宽限截止 / 当前时刻 / 已试次数）→ 判定。
 * 因此可在 JVM 单测里穷举决策表，无需 Android 环境或真实数据库。
 *
 * 为什么需要「延迟 + 重试 + 代数」三件套（对齐 Kelivo `asset_gc_rows`）：
 * - **延迟**：删除会话后立即删文件，用户撤销/导入备份时就永久丢了；宽限给反悔窗口
 * - **重试**：删除可能因文件被占用/IO 错误失败，一次性尝试会留下永远清不掉的残留
 * - **代数**：排队期间资产**又被引用**（如从回收站恢复、同步回灌）时，
 *   旧计划必须失效，否则会删掉刚被引用的文件
 */
object AssetGcPolicy {

    /**
     * 默认宽限期：**7 天**。
     *
     * 语义：资产已无任何引用（从 UI 上已经不可达）后，仍保留 7 天才真删。
     * 选 7 天的理由：足够覆盖「删错会话后过几天想起来」与「备份导入前」的窗口，
     * 又不至于让磁盘长期挂着无用文件。**这是产品判断，不是技术约束，可调**。
     */
    val DEFAULT_GRACE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /** 单个资产最多重试次数，超过则 ABANDON（避免无限重试）。 */
    const val DEFAULT_MAX_ATTEMPTS = 5

    private const val BASE_BACKOFF_MILLIS = 5L * 60 * 1000
    private const val MAX_BACKOFF_MILLIS = 6L * 60 * 60 * 1000
    private const val MAX_BACKOFF_SHIFT = 20

    /** 宽限截止时刻。 */
    fun graceDeadline(nowMillis: Long, graceMillis: Long = DEFAULT_GRACE_MILLIS): Long {
        require(graceMillis >= 0) { "宽限期不能为负:$graceMillis" }
        return nowMillis + graceMillis
    }

    /**
     * 第 [attempts] 次重试前的等待：指数退避（5min 起，每次翻倍，上限 6h）。
     *
     * 上限存在的意义：失败往往来自环境问题（空间不足、文件被占用），
     * 无限退避会让「明显失败」的条目长期占据队列。
     */
    fun backoffMillis(attempts: Int): Long {
        require(attempts >= 0) { "attempts 不能为负:$attempts" }
        val shifted = BASE_BACKOFF_MILLIS shl attempts.coerceAtMost(MAX_BACKOFF_SHIFT)
        return shifted.coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /** 下一次尝试时刻。 */
    fun nextAttemptAt(nowMillis: Long, attempts: Int): Long = nowMillis + backoffMillis(attempts)

    /**
     * 决策。
     *
     * 判定顺序**有意如此**：先看是否有活引用（安全优先，宁可留着），
     * 再看时间闸门，最后才看重试次数。
     */
    fun decide(
        hasLiveReferences: Boolean,
        notBefore: Long,
        nowMillis: Long,
        attempts: Int,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): GcDecision = when {
        hasLiveReferences -> GcDecision.KEEP_REFERENCED
        nowMillis < notBefore -> GcDecision.WAIT_GRACE
        attempts >= maxAttempts -> GcDecision.ABANDON
        else -> GcDecision.DELETE
    }

    /**
     * 计划是否已过期。
     *
     * 资产在排队期间被重新引用时会 `generation + 1`；
     * 持有旧代数的计划据此判定失效，**不得执行删除**。
     */
    fun isPlanStale(plannedGeneration: Long, currentGeneration: Long): Boolean =
        plannedGeneration < currentGeneration
}
