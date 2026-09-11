// [X-custom] RikkaHub-X 存储管理重构(P1)：资产/引用/回收候选的读写（IO 层）
package me.rerere.rikkahub.x.storage

import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.x.diag.XDomain
import me.rerere.rikkahub.x.diag.XLog

/** 一条回收候选（供界面展示「闲置 N 天 / 可清理 N 字节」）。 */
data class GcCandidate(
    val assetId: String,
    val relativePath: String,
    val byteSize: Long,
    val firstUnreferencedAt: Long,
    /**
     * 读出该行时的**代数**。界面必须原样保存,用户确认删除时交回 [AssetRepository.purgeAsset]
     * —— 期间该资产若被重新引用过,代数会变,删除会被拒（见 [AssetGcPolicy.isPlanStale]）。
     */
    val generation: Long,
)

/**
 * [AssetRepository.purgeAsset] 的结果。
 *
 * **两种拒绝分开**,因为原因与处置不同:
 * - [STILL_REFERENCED]:资产还在用 → 等它真的没引用;
 * - [PLAN_STALE]:用户看到清单之后该资产被重新引用过 → 清单上的「闲置 N 天」已不准 → **刷新清单**。
 *
 * 合成一个布尔会让界面只能提示「删不掉」,而说不出为什么。
 */
enum class PurgeOutcome {
    /** 已删除登记（文件由调用方先删）。 */
    DELETED,

    /** 期间被重新引用 —— **拒绝删除**。 */
    STILL_REFERENCED,

    /** 手上的候选清单已过期 —— **拒绝删除**。 */
    PLAN_STALE,
}

/**
 * X 存储层的读写入口。
 *
 * **语句全部经 [XAssetDao]**（Room 在编译期解析列名与类型）。本类只负责
 * 取 DAO、开事务、把查询结果翻译成领域模型，不掺业务判断 —— 判断都在
 * [AssetStorePolicy] / [AssetGcPolicy] 里，那些是纯函数、可在 JVM 单测中穷举。
 *
 * ## 为什么有一个「同步桥」（[awaitDb]）
 *
 * [AssetLedger] 的契约是**同步**的：调用点在 Compose 回调（`ReceiveContentListener`、
 * `ActivityResult`、AttachmentChips 的删除回调），改成挂起会波及 27 处调用点，
 * 且会把「附件添加」变成异步（UI 时序变化）。那是一次独立的 UI/线程改造，不该混在
 * 「存储层搬家」里做。
 *
 * 但 Room 的生成代码会断言「非主线程才可访问数据库」（除非开
 * `allowMainThreadQueries` —— 那会让这个检查对**所有** DAO 失效，代价太大）。
 * 于是这里把 Room 调用放到 IO 线程执行、调用方同步等待：
 *
 * | | 主线程是否阻塞 | Room 断言 | 全局安全网 |
 * |---|---|---|---|
 * | 改造前（裸 SQLite） | 是（裸 SQLite 无此断言） | — | — |
 * | 改造后（本类） | **是（与改造前一致）** | 不触发（调用在 IO） | **保留** |
 *
 * 即：**行为与改造前逐字一致，而去掉的是「手写 SQL」这个根因**。
 * 主线程阻塞要根治，得把那 27 处调用改成协程 —— 已单独登记为后续任务。
 *
 * ⚠️ **事务内变体不走同步桥**：`replaceRefsOfConversationWithinTransaction` /
 * `removeRefsOfConversationWithinTransaction` 由调用方在 Room 的 `withTransaction`
 * 里调用，那时当前线程已是事务线程（非主线程），直接发 DAO 语句即可 ——
 * 若走 [awaitDb] 会切到另一个线程，语句就落到了事务之外。
 */
class AssetRepository(
    private val database: AppDatabase,
    /** 应用私有文件根目录（`context.filesDir`）。用于把 URL 还原成相对路径、以及判断文件是否在盘上。 */
    private val filesDir: File,
) : AssetLedger {

    private val dao get() = database.xAssetDao()

    /**
     * 在 IO 线程执行一次 Room 调用，调用方同步等待结果。
     *
     * 用法限于**同步契约**的方法（见类注释）。不要在已有 Room 事务的线程上调用。
     */
    private fun <T> awaitDb(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }

    // ────────────────────────────────────────────────────────────────
    // 资产
    // ────────────────────────────────────────────────────────────────

    /**
     * 按内容哈希查已登记的资产 —— [AssetStorePolicy.plan] 的输入。
     *
     * **同时查盘**：库里有记录不代表文件还在（用户清过数据目录、写入中途掉电、
     * 外部工具删过文件）。这正是 [KnownAsset.fileExists] 存在的原因。
     */
    suspend fun knownAsset(hash: String): KnownAsset? = withContext(Dispatchers.IO) { findKnown(hash) }

    /** @see AssetLedger.findKnown */
    override fun findKnown(hash: String): KnownAsset? {
        val relativePath = awaitDb { dao.selectPathByHash(hash) } ?: return null
        return KnownAsset(relativePath = relativePath, fileExists = isFilePresent(relativePath))
    }

    /**
     * 登记（或覆盖）一条资产。调用方**只在内容首次落盘时**调用。
     *
     * 直接走 DAO（不再转调同步版 [recordAsset]）—— 后者内部用 `runBlocking` 桥接，
     * 在已经在 IO 线程上的挂起调用里再套一层会让「阻塞等待」变得没有意义。
     * 本方法是**挂起**的，调用方本来就在协程里。
     */
    suspend fun registerAsset(
        hash: String,
        relativePath: String,
        byteSize: Long,
        nowMillis: Long,
        extrasJson: String = "{}",
    ) = withContext(Dispatchers.IO) {
        dao.upsertAsset(assetEntity(hash, relativePath, byteSize, nowMillis, extrasJson))
    }

    /**
     * 按内容哈希查**已登记的落盘路径**（不查盘）。
     *
     * 与 [findKnown] 的区别：那个要顺带做一次 `File.isFile`（决定「复用」还是「补写」），
     * 而回填只需要知道「这个内容在账本里登记的路径是哪个」——
     * 每文件一次多余的磁盘 stat，在几万个文件的扫描里不是小数目。
     */
    suspend fun registeredPathOf(hash: String): String? = withContext(Dispatchers.IO) {
        dao.selectPathByHash(hash)
    }

    /** 资产行构造 —— 同步版与挂起版共用一份，避免两处漂移。 */
    private fun assetEntity(
        hash: String,
        relativePath: String,
        byteSize: Long,
        nowMillis: Long,
        extrasJson: String,
    ) = XAssetEntity(
        id = hash,
        path = relativePath,
        byteSize = byteSize,
        createdAt = nowMillis,
        lastReferencedAt = nowMillis,
        extrasJson = extrasJson,
    )

    /** @see AssetLedger.recordAsset */
    override fun recordAsset(
        hash: String,
        relativePath: String,
        byteSize: Long,
        nowMillis: Long,
        extrasJson: String,
    ) {
        awaitDb { dao.upsertAsset(assetEntity(hash, relativePath, byteSize, nowMillis, extrasJson)) }
    }

    // ────────────────────────────────────────────────────────────────
    // 引用（登记 / 撤销）
    // ────────────────────────────────────────────────────────────────

    /**
     * 登记一组消息引用。
     *
     * 整批在**一个事务**内完成：引用登记伴随「刷新最后引用时刻」与「撤销回收候选」，
     * 三者必须同生同死 —— 若只登记了引用而没撤销候选，回收清单就会漏掉这个刚被引用的资产，
     * 而那正是最不该删的。
     *
     * 只登记**能反解出内容哈希**的引用（即内容寻址路径）。存量老文件（`upload/` 下）
     * 没有内容指纹，由回填阶段处理；在此之前它们不进引用表、也进不了候选 —— 方向安全。
     *
     * @return 实际登记成功的引用条数（供日志与断言）。
     */
    suspend fun registerRefs(
        refs: List<ExtractedAssetRef>,
        conversationId: String,
        nowMillis: Long,
    ): Int = database.withTransaction { insertRefs(refs, conversationId, nowMillis) }

    /** 撤销某会话的全部引用（会话被删除时调用）。 */
    suspend fun removeRefsOfConversation(conversationId: String) = withContext(Dispatchers.IO) {
        dao.deleteRefsOfConversation(conversationId)
    }

    // ────────────────────────────────────────────────────────────────
    // 事务内变体：供**调用方已有事务**时使用
    // ────────────────────────────────────────────────────────────────
    //
    // ⚠️ **为什么不直接复用上面的挂起版本**：那些方法内部会 `withContext(Dispatchers.IO)`，
    // 而 SQLite 的事务是**绑定线程**的 —— 在 Room 的 `withTransaction { }` 块里再切线程，
    // 新线程上没有活动事务，语句会落到事务之外（或者直接报错）。
    // 本项目的既有代码里没有「事务内再 withContext」的先例，说明这是尚未被踩过的组合。
    //
    // 下面这组方法**不切换调度器、不开启嵌套事务**：原子性已由调用方的事务保证，
    // 它们只负责在当前线程上把语句发出去（直接调 DAO —— Room 认同一连接上的活动事务）。

    /**
     * 在调用方已有的事务内，**重建**某会话的全部引用。
     *
     * 「先全删、再全插」与上游 `updateConversation` 的做法一致（那里也是删光节点再插新节点，
     * 见 `messageNodeDAO.deleteByConversation`）—— 因为消息节点的 id 可能保留而内容已变
     * （编辑、重新生成），逐条比对并不比整删整插更可靠。
     *
     * @return 登记成功的引用条数。
     */
    fun replaceRefsOfConversationWithinTransaction(
        conversationId: String,
        refs: List<ExtractedAssetRef>,
        nowMillis: Long,
    ): Int {
        dao.deleteRefsOfConversation(conversationId)
        return insertRefs(refs, conversationId, nowMillis)
    }

    /** 在调用方已有的事务内撤销某会话的全部引用。 */
    fun removeRefsOfConversationWithinTransaction(conversationId: String) {
        dao.deleteRefsOfConversation(conversationId)
    }

    /** 引用插入的公共实现 —— 挂起版与事务内版共用，避免两处逻辑漂移。 */
    private fun insertRefs(
        refs: List<ExtractedAssetRef>,
        conversationId: String,
        nowMillis: Long,
    ): Int {
        var registered = 0
        for (ref in refs) {
            // 路径还原与哈希反解任一失败即跳过：非内容寻址路径（存量老文件）、
            // 或 filesDir 之外的文件（缓存/外部存储）都不该进引用表
            val relativePath =
                AssetRefExtractor.toRelativePath(ref.url, filesDir.absolutePath) ?: continue
            val assetId = AssetRefExtractor.assetIdOf(relativePath) ?: continue

            dao.insertRef(
                XAssetRefEntity(
                    messageId = ref.messageId,
                    assetId = assetId,
                    kind = ref.kind,
                    conversationId = conversationId,
                    createdAt = nowMillis,
                )
            )
            dao.touchLastReferenced(assetId, nowMillis)
            // 资产又活了 → 退出回收状态（与登记同生同死，见方法注释）。
            // ⚠️ 这里是 **标记非活跃** 而不是删行 —— 删行会让代数归零，
            // 于是「查看清单期间被重新引用」这件事再也追不出来（详见 XAssetDao 的注释）。
            dao.markGcCandidateInactive(assetId, AssetGcPolicy.INACTIVE_FIRST_UNREFERENCED_AT)
            registered++
        }
        return registered
    }

    /** 撤销某条消息的全部引用（消息被删除或编辑时调用）。 */
    suspend fun removeRefsOfMessage(messageId: String) = withContext(Dispatchers.IO) {
        dao.deleteRefsOfMessage(messageId)
    }

    /** 某资产当前还有多少条引用。 */
    suspend fun refCountOf(assetId: String): Int = withContext(Dispatchers.IO) { referenceCountOf(assetId) }

    /** @see AssetLedger.referenceCountOf */
    override fun referenceCountOf(assetId: String): Int = awaitDb { dao.countRefsOfAsset(assetId) }

    // ────────────────────────────────────────────────────────────────
    // 回收候选（只登记,不自动删）
    // ────────────────────────────────────────────────────────────────

    /** 界面上的「可清理 N 字节」：当前**无任何引用**的资产总大小。 */
    suspend fun unreferencedBytes(): Long = withContext(Dispatchers.IO) {
        dao.sumUnreferencedBytes(AssetStorePolicy.MANAGED_PATH_SQL_PREFIX)
    }

    /**
     * 回收候选清单：无引用、且首次无引用时刻已早于观察门槛。
     *
     * 门槛用 `now - observation` 与「首次无引用时刻」比较，等价于判 `now >= first + observation`，
     * 即 [AssetGcPolicy.candidateAt] 的语义 —— 单测里有一条断言把这两条路径钉在一起。
     */
    suspend fun gcCandidates(
        nowMillis: Long,
        observationMillis: Long = AssetGcPolicy.DEFAULT_OBSERVATION_MILLIS,
    ): List<GcCandidate> = withContext(Dispatchers.IO) {
        val cutoff = nowMillis - observationMillis
        dao.selectGcCandidates(
            cutoff = cutoff,
            inactiveAt = AssetGcPolicy.INACTIVE_FIRST_UNREFERENCED_AT,
            managedPrefix = AssetStorePolicy.MANAGED_PATH_SQL_PREFIX,
        ).map { row ->
            GcCandidate(
                assetId = row.assetId,
                relativePath = row.relativePath,
                byteSize = row.byteSize,
                firstUnreferencedAt = row.firstUnreferencedAt,
                generation = row.generation,
            )
        }
    }

    /**
     * 收起重扫时登记候选 —— **首次无引用时刻只在还没有记录时写入**。
     *
     * 每次扫描都刷新「首次无引用时刻」的话，闲置时长永远从今天算起、候选永远等不到门槛。
     */
    suspend fun markGcCandidate(
        assetId: String,
        firstUnreferencedAt: Long,
        reason: String = "",
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            // 先确保有行（`INSERT OR IGNORE`：已存在则**代数与原因都不动**）
            dao.insertGcCandidate(
                XAssetGcEntity(
                    assetId = assetId,
                    firstUnreferencedAt = firstUnreferencedAt,
                    generation = 0L,
                    reason = reason,
                )
            )
            // 再「重启观察期」—— 只在当前处于非活跃（曾被重新引用过）时才改写起算点。
            // 反复扫描不会刷新起算点，否则闲置时长永远从今天算起、候选永远等不到门槛。
            dao.restartGcObservation(
                assetId = assetId,
                at = firstUnreferencedAt,
                inactiveAt = AssetGcPolicy.INACTIVE_FIRST_UNREFERENCED_AT,
            )
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 删除（仅限用户显式确认后）
    // ────────────────────────────────────────────────────────────────

    /**
     * 删除一条资产的**登记**（审计留痕）。
     *
     * **两道校验,缺一不可**:
     *
     * | # | 校验 | 挡住什么 |
     * |:--:|---|---|
     * | ① | **代数**(`plannedGeneration < 当前代数`) | 用户看到清单之后该资产被**重新引用过** → 清单上的「闲置 N 天」已不准 → 刷新清单 |
     * | ② | **引用计数** | 删除前再数一次 —— 最靠近删除的位置,挡住「仍被引用」 |
     *
     * **为什么①单独存在**:用户看到清单(T0)→ 资产被重新引用(T1)→ 又失去引用(T2)。
     * 此时引用计数为 0,②放行;而观察期本应从 T2 重新起算(远未到 24 小时),
     * 清单却按 T0 之前的旧时刻把它算成「闲置 30 天」。**只有代数能追出这个跳跃。**
     *
     * 校验顺序**有意如此**:先判代数(便宜且语义更准),再数引用。
     *
     * 文件本身的删除由调用方负责（本类只管库）—— 顺序应为**先删文件、后删登记**：
     * 反过来会在中途失败时留下「有文件、无登记」的孤儿，那比「无文件、有登记」更难收拾
     * （后者下次写入会按 [AssetStorePlan.RewriteMissing] 自动补回）。
     *
     * @param plannedGeneration 界面上那份清单里带的代数（[GcCandidate.generation]）。
     * @return [PurgeOutcome] —— 两种拒绝分开,界面才能说清为什么删不掉。
     */
    suspend fun purgeAsset(
        assetId: String,
        byteSize: Long?,
        nowMillis: Long,
        plannedGeneration: Long,
        reason: String = "",
    ): PurgeOutcome {
        var outcome = PurgeOutcome.DELETED
        database.withTransaction {
            // ① 代数:该资产在用户查看清单期间是否被重新引用过
            val currentGeneration = dao.selectGeneration(assetId) ?: 0L
            when {
                AssetGcPolicy.isPlanStale(plannedGeneration, currentGeneration) ->
                    outcome = PurgeOutcome.PLAN_STALE

                // ② 引用:删除前再数一次
                dao.countRefsOfAsset(assetId) > 0 ->
                    outcome = PurgeOutcome.STILL_REFERENCED

                else -> {
                    dao.deleteGcCandidate(assetId)
                    dao.deleteAsset(assetId)
                    dao.insertAudit(
                        XGcAuditEntity(
                            kind = XStorageTables.AuditKinds.ASSET_DELETED,
                            entityId = assetId,
                            byteSize = byteSize,
                            detail = reason,
                            completedAt = nowMillis,
                        )
                    )
                }
            }
        }
        when (outcome) {
            PurgeOutcome.DELETED ->
                XLog.info(XDomain.STORAGE, XStorageEvents.GC_PURGE) { "已删除资产登记:" + assetId }

            PurgeOutcome.PLAN_STALE ->
                XLog.warn(XDomain.STORAGE, XStorageEvents.GC_PLAN_STALE) {
                    "拒绝删除:候选清单已过期(查看期间被重新引用过):" + assetId
                }

            PurgeOutcome.STILL_REFERENCED ->
                XLog.warn(XDomain.STORAGE, XStorageEvents.GC_REFUSE) { "拒绝删除仍被引用的资产:" + assetId }
        }
        return outcome
    }

    // ────────────────────────────────────────────────────────────────
    // 内部
    // ────────────────────────────────────────────────────────────────

    /** 文件是否真的在盘上 —— 路径来自库，判存在与否只能问文件系统。 */
    private fun isFilePresent(relativePath: String): Boolean =
        runCatching { File(filesDir, relativePath).isFile }.getOrDefault(false)
}
