// [X-custom] RikkaHub-X 存储管理重构(P1)：资产/引用/回收候选的读写（IO 层）
package me.rerere.rikkahub.x.storage

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase

/** 一条回收候选（供界面展示「闲置 N 天 / 可清理 N 字节」）。 */
data class GcCandidate(
    val assetId: String,
    val relativePath: String,
    val byteSize: Long,
    val firstUnreferencedAt: Long,
)

/**
 * X 存储层的读写入口。
 *
 * **这一层是「纯 SQL 构造」（[AssetSql]）与「执行」之间的薄包装**：本类只负责
 * 取数据库句柄、开事务、按固定列顺序读游标，不掺业务判断 —— 判断都在
 * [AssetStorePolicy] / [AssetGcPolicy] 里，那些是纯函数、可在 JVM 单测中穷举。
 *
 * 表不注册为 Room 实体，故 DAO 不可用，走 `openHelper.writableDatabase`
 * （先例：同库的 `MessageFtsManager` 就是这样访问 `message_fts`）。
 */
class AssetRepository(
    private val database: AppDatabase,
    /** 应用私有文件根目录（`context.filesDir`）。用于把 URL 还原成相对路径、以及判断文件是否在盘上。 */
    private val filesDir: File,
) {
    private companion object {
        const val TAG = "XStorage"
    }

    private val db get() = database.openHelper.writableDatabase

    // ────────────────────────────────────────────────────────────────
    // 资产
    // ────────────────────────────────────────────────────────────────

    /**
     * 按内容哈希查已登记的资产 —— [AssetStorePolicy.plan] 的输入。
     *
     * **同时查盘**：库里有记录不代表文件还在（用户清过数据目录、写入中途掉电、
     * 外部工具删过文件）。这正是 [KnownAsset.fileExists] 存在的原因。
     */
    suspend fun knownAsset(hash: String): KnownAsset? = withContext(Dispatchers.IO) {
        val cursor = db.query(AssetSql.selectAssetByHash(hash).sql, arrayOf<Any?>(hash))
        cursor.use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                c.getString(0)?.let { relativePath ->
                    KnownAsset(relativePath = relativePath, fileExists = isFilePresent(relativePath))
                }
            }
        }
    }

    /** 登记（或覆盖）一条资产。调用方**只在内容首次落盘时**调用。 */
    suspend fun registerAsset(
        hash: String,
        relativePath: String,
        byteSize: Long,
        nowMillis: Long,
        extrasJson: String = "{}",
    ) = withContext(Dispatchers.IO) {
        exec(
            AssetSql.upsertAsset(
                hash = hash,
                relativePath = relativePath,
                byteSize = byteSize,
                createdAt = nowMillis,
                lastReferencedAt = nowMillis,
                extrasJson = extrasJson,
            )
        )
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
    ): Int = withContext(Dispatchers.IO) {
        var registered = 0
        transaction {
            for (ref in refs) {
                val relativePath =
                    AssetRefExtractor.toRelativePath(ref.url, filesDir.absolutePath) ?: continue
                val assetId = AssetRefExtractor.assetIdOf(relativePath) ?: continue
                exec(AssetSql.insertRef(ref.messageId, assetId, ref.kind, conversationId, nowMillis))
                exec(AssetSql.touchLastReferenced(assetId, nowMillis))
                exec(AssetSql.deleteGcCandidate(assetId))
                registered++
            }
        }
        registered
    }

    /** 撤销某会话的全部引用（会话被删除时调用）。 */
    suspend fun removeRefsOfConversation(conversationId: String) = withContext(Dispatchers.IO) {
        exec(AssetSql.deleteRefsOfConversation(conversationId))
    }

    /** 撤销某条消息的全部引用（消息被删除或编辑时调用）。 */
    suspend fun removeRefsOfMessage(messageId: String) = withContext(Dispatchers.IO) {
        exec(AssetSql.deleteRefsOfMessage(messageId))
    }

    /** 某资产当前还有多少条引用。 */
    suspend fun refCountOf(assetId: String): Int = withContext(Dispatchers.IO) {
        db.query(AssetSql.countRefsOfAsset(assetId).sql, arrayOf<Any?>(assetId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 回收候选（只登记,不自动删）
    // ────────────────────────────────────────────────────────────────

    /** 界面上的「可清理 N 字节」：当前**无任何引用**的资产总大小。 */
    suspend fun unreferencedBytes(): Long = withContext(Dispatchers.IO) {
        db.query(AssetSql.sumUnreferencedBytes().sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
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
        val out = mutableListOf<GcCandidate>()
        db.query(AssetSql.selectGcCandidates(cutoff).sql, arrayOf<Any?>(cutoff)).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    GcCandidate(
                        assetId = cursor.getString(0),
                        relativePath = cursor.getString(1),
                        byteSize = cursor.getLong(2),
                        firstUnreferencedAt = cursor.getLong(3),
                    )
                )
            }
        }
        out
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
        exec(AssetSql.insertGcCandidate(assetId, firstUnreferencedAt, generation = 0L, reason = reason))
    }

    // ────────────────────────────────────────────────────────────────
    // 删除（仅限用户显式确认后）
    // ────────────────────────────────────────────────────────────────

    /**
     * 删除一条资产的**登记**（审计留痕）。
     *
     * **删除前必须再查一次引用**：用户从看到候选清单到点确认之间，
     * 该资产完全可能又被某条消息引用（恢复备份、导入会话）。此时按旧清单删就会
     * 删掉在用的文件 —— 这是本设计里代价最高的一种错，故把校验放在最靠近删除的位置。
     *
     * 文件本身的删除由调用方负责（本类只管库）—— 顺序应为**先删文件、后删登记**：
     * 反过来会在中途失败时留下「有文件、无登记」的孤儿，那比「无文件、有登记」更难收拾
     * （后者下次写入会按 [AssetStorePlan.RewriteMissing] 自动补回）。
     *
     * @return true = 已删除；false = 仍有引用，**拒绝删除**。
     */
    suspend fun purgeAsset(
        assetId: String,
        byteSize: Long?,
        nowMillis: Long,
        reason: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        var purged = false
        transaction {
            val stillReferenced = db
                .query(AssetSql.countRefsOfAsset(assetId).sql, arrayOf<Any?>(assetId))
                .use { if (it.moveToFirst()) it.getInt(0) > 0 else false }
            if (!stillReferenced) {
                exec(AssetSql.deleteGcCandidate(assetId))
                exec(AssetSql.deleteAsset(assetId))
                exec(
                    AssetSql.insertAudit(
                        kind = XStorageTables.AuditKinds.ASSET_DELETED,
                        entityId = assetId,
                        byteSize = byteSize,
                        detail = reason,
                        completedAt = nowMillis,
                    )
                )
                purged = true
            }
        }
        if (!purged) {
            Log.w(TAG, "拒绝删除仍被引用的资产:$assetId")
        }
        purged
    }

    // ────────────────────────────────────────────────────────────────
    // 内部
    // ────────────────────────────────────────────────────────────────

    /** 文件是否真的在盘上。路径先过 [AssetStorePolicy.isManagedPath] 之外的存在性检查即可。 */
    private fun isFilePresent(relativePath: String): Boolean =
        runCatching { File(filesDir, relativePath).isFile }.getOrDefault(false)

    private fun exec(statement: SqlStatement) {
        db.execSQL(statement.sql, statement.args.toTypedArray())
    }

    /**
     * 事务包装。
     *
     * `beginTransaction` 是可重入的（同一线程内嵌套调用只会让最外层提交时生效），
     * 故本方法可安全地被嵌套调用。
     */
    private inline fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        return try {
            val result = block()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }
}
