// [X-custom] RikkaHub-X 存储管理重构(P1)：回收候选判定（纯函数,无 IO/无 Android 依赖）
package me.rerere.rikkahub.x.storage

/** 单个资产在「回收候选」这件事上的处境。 */
enum class GcDecision {
    /** 仍被引用 —— **绝不删**（回收安全的核心保证）。 */
    KEEP_REFERENCED,

    /** 已无引用、且过了观察门槛 —— 可进候选清单，但**仍须用户显式确认**才会删。 */
    CANDIDATE,

    /** 已无引用，但还没过观察门槛（刚失去引用的那段时间）。 */
    WAIT_OBSERVATION,
}

/**
 * 回收候选策略。
 *
 * **产品前提**：删除**不自动发生** —— 存储空间页只提示「可清理 N 字节」，
 * 由用户确认后才删。故这里不做「到点即删」的判定，只回答两个问题：
 * ① 这个资产现在还该不该留在盘上（[decide]）；
 * ② 用户手上那份候选清单是不是已经过时（[isPlanStale]）。
 *
 * 全部为**纯函数**：输入（是否有活引用 / 候选门槛时刻 / 当前时刻）→ 判定。
 * 因此可在 JVM 单测里穷举决策表，无需 Android 环境或真实数据库。
 *
 * 与 Kelivo 的差异（有意简化）：它的回收表还带宽限截止、重试次数与退避时间戳；
 * 因删除改为手动确认，这三者失去对象，故只保留**代数**与观察门槛。
 */
object AssetGcPolicy {

    /**
     * 观察门槛：资产**刚失去最后一个引用**时不立刻算「可回收」，等一段时间再看。
     *
     * 语义是「滤掉抖动」，不是「宽限期」—— 它不承诺到点会自动删，只是避免把
     * 「删掉一条消息又立刻撤销」这类瞬时状态也列进候选清单，让用户看到一份噪声列表。
     *
     * 24 小时是产品判断，可调。
     */
    val DEFAULT_OBSERVATION_MILLIS: Long = 24L * 60 * 60 * 1000

    /**
     * 候选门槛时刻 = 首次观察到无引用的时刻 + 观察期。
     */
    fun candidateAt(
        firstUnreferencedAt: Long,
        observationMillis: Long = DEFAULT_OBSERVATION_MILLIS,
    ): Long {
        require(observationMillis >= 0) { "观察期不能为负:$observationMillis" }
        return firstUnreferencedAt + observationMillis
    }

    /**
     * 资产已闲置多久（毫秒）。界面据此显示「闲置 N 天」。
     *
     * 下界夹到 0：设备改时间或时钟回拨时，「闲置 −3 天」这种说法没有意义。
     */
    fun idleMillis(firstUnreferencedAt: Long, nowMillis: Long): Long =
        (nowMillis - firstUnreferencedAt).coerceAtLeast(0)

    /**
     * 判定。
     *
     * 判定顺序**有意如此**：先看是否有活引用（安全优先，宁可留着），再看观察门槛。
     *
     * **`candidateAt` 刻意不给默认值。**
     * 曾给它一个「无门槛」的哨兵默认值 `Long.MAX_VALUE`，结果自相矛盾：
     * `nowMillis < Long.MAX_VALUE` 恒真，于是**每个资产都卡在等待**，
     * 与注释写的「立即成为候选」正好相反（CI 已抓到这个错）。
     * 而换成 `Long.MIN_VALUE` 又等于默认放行 —— 把「刚失去引用」直接当候选，
     * 方向偏危险。两种默认都反直觉，故**要求调用方显式给出门槛时刻**：
     * 反正它总能从 `x_asset_gc.first_unreferenced_at` 算出（[candidateAt]）。
     */
    fun decide(
        hasLiveReferences: Boolean,
        nowMillis: Long,
        candidateAt: Long,
    ): GcDecision = when {
        hasLiveReferences -> GcDecision.KEEP_REFERENCED
        nowMillis < candidateAt -> GcDecision.WAIT_OBSERVATION
        else -> GcDecision.CANDIDATE
    }

    /**
     * 候选清单是否已过期。
     *
     * 资产在用户查看清单期间被重新引用时会 `generation + 1`；
     * 持有旧代数的清单据此判定失效，**不得据此执行删除**。
     */
    fun isPlanStale(plannedGeneration: Long, currentGeneration: Long): Boolean =
        plannedGeneration < currentGeneration
}
