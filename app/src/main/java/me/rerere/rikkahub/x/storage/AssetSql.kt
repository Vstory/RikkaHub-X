// [X-custom] RikkaHub-X 存储管理重构(P1)：X 存储层的 SQL 构造（纯函数,无 IO/无 Android 依赖）
package me.rerere.rikkahub.x.storage

/**
 * 一条可执行的语句：SQL 文本 + 位置参数。
 *
 * 把「构造」与「执行」分开，是为了让占位符与参数**能在单测里对上账** ——
 * 二者错位是这类手写 SQL 代码最典型的错误，而它**编译不报错、运行到那条路径才炸**。
 */
data class SqlStatement(val sql: String, val args: List<Any?>)

/**
 * X 存储层的 SQL 构造。
 *
 * **为什么手写 SQL 而不用 Room DAO**：X 表不注册为 Room 实体（理由见 [XStorageTables] 的
 * 类注释），故拿不到 DAO。本对象只产出语句、不执行，可在 JVM 单测里逐条核对。
 *
 * 全部语句的**占位符数量与参数数量**由单测统一断言（[placeholders]），
 * 新增语句若写错会立刻变红。
 */
object AssetSql {

    /** 位置参数占位符。 */
    const val PLACEHOLDER = "?"

    /** 统计语句里的占位符个数（本层语句不含字符串字面量 `?`，故可简单计数）。 */
    fun placeholders(sql: String): Int = sql.count { it == PLACEHOLDER[0] }

    // ────────────────────────────────────────────────────────────────
    // 资产
    // ────────────────────────────────────────────────────────────────

    /**
     * 按内容哈希查资产。
     *
     * **只查 `path`**：调用方（[AssetStorePolicy.plan]）只需要路径来决定「复用还是补写」，
     * 多查的列既没用、又会让「列顺序 ↔ 读取顺序」多一处可能对不上的地方。
     * 真需要字节数时另加语句，别顺手扩这条。
     */
    fun selectAssetByHash(hash: String): SqlStatement = SqlStatement(
        "SELECT ${XStorageTables.Asset.PATH} " +
            "FROM ${XStorageTables.ASSET} WHERE ${XStorageTables.Asset.ID} = ?",
        listOf(hash),
    )

    /**
     * 登记（或覆盖）一条资产。
     *
     * 用 `INSERT OR REPLACE`：调用方**只在内容首次落盘时**登记，冲突仅出现在
     * 「文件丢失后按原路径补写」这一种情形 —— 那时刷新时间戳无害（内容未变）。
     */
    fun upsertAsset(
        hash: String,
        relativePath: String,
        byteSize: Long,
        createdAt: Long,
        lastReferencedAt: Long,
        extrasJson: String,
    ): SqlStatement = SqlStatement(
        "INSERT OR REPLACE INTO ${XStorageTables.ASSET} (" +
            "${XStorageTables.Asset.ID}, ${XStorageTables.Asset.PATH}, " +
            "${XStorageTables.Asset.BYTE_SIZE}, ${XStorageTables.Asset.CREATED_AT}, " +
            "${XStorageTables.Asset.LAST_REFERENCED_AT}, ${XStorageTables.Asset.EXTRAS_JSON}" +
            ") VALUES (?, ?, ?, ?, ?, ?)",
        listOf(hash, relativePath, byteSize, createdAt, lastReferencedAt, extrasJson),
    )

    /**
     * 刷新「最后被引用时刻」。
     *
     * **只在时间前进时写**（`WHERE last_referenced_at < ?`）：引用登记可能因
     * 备份恢复、消息重放而**乱序**到达，若直接赋值就会让时间倒流 ——
     * 而「闲置多久」正是回收候选的排序依据。
     */
    fun touchLastReferenced(hash: String, at: Long): SqlStatement = SqlStatement(
        "UPDATE ${XStorageTables.ASSET} SET ${XStorageTables.Asset.LAST_REFERENCED_AT} = ? " +
            "WHERE ${XStorageTables.Asset.ID} = ? AND ${XStorageTables.Asset.LAST_REFERENCED_AT} < ?",
        listOf(at, hash, at),
    )

    /** 删除一条资产（**仅限用户显式确认后**调用）。 */
    fun deleteAsset(hash: String): SqlStatement = SqlStatement(
        "DELETE FROM ${XStorageTables.ASSET} WHERE ${XStorageTables.Asset.ID} = ?",
        listOf(hash),
    )

    // ────────────────────────────────────────────────────────────────
    // 引用
    // ────────────────────────────────────────────────────────────────

    /**
     * 登记一条引用。
     *
     * `OR IGNORE` 而非 `OR REPLACE`：重复登记同一引用**不应刷新任何东西** ——
     * 引用表没有时间语义，主键 `(message_id, asset_id, kind)` 相同就是同一条。
     * 用 REPLACE 会先删后插，白白制造写放大。
     */
    fun insertRef(
        messageId: String,
        assetId: String,
        kind: String,
        conversationId: String,
        createdAt: Long,
    ): SqlStatement = SqlStatement(
        "INSERT OR IGNORE INTO ${XStorageTables.ASSET_REF} (" +
            "${XStorageTables.AssetRef.MESSAGE_ID}, ${XStorageTables.AssetRef.ASSET_ID}, " +
            "${XStorageTables.AssetRef.KIND}, ${XStorageTables.AssetRef.CONVERSATION_ID}, " +
            "${XStorageTables.AssetRef.CREATED_AT}" +
            ") VALUES (?, ?, ?, ?, ?)",
        listOf(messageId, assetId, kind, conversationId, createdAt),
    )

    /** 删除某会话的全部引用（会话被删除时调用）。 */
    fun deleteRefsOfConversation(conversationId: String): SqlStatement = SqlStatement(
        "DELETE FROM ${XStorageTables.ASSET_REF} " +
            "WHERE ${XStorageTables.AssetRef.CONVERSATION_ID} = ?",
        listOf(conversationId),
    )

    /** 删除某条消息的全部引用（消息被删除或编辑时调用）。 */
    fun deleteRefsOfMessage(messageId: String): SqlStatement = SqlStatement(
        "DELETE FROM ${XStorageTables.ASSET_REF} WHERE ${XStorageTables.AssetRef.MESSAGE_ID} = ?",
        listOf(messageId),
    )

    /** 某资产当前还有多少条引用。0 = 无引用。 */
    fun countRefsOfAsset(assetId: String): SqlStatement = SqlStatement(
        "SELECT COUNT(*) FROM ${XStorageTables.ASSET_REF} " +
            "WHERE ${XStorageTables.AssetRef.ASSET_ID} = ?",
        listOf(assetId),
    )

    /**
     * 无引用资产的总字节数（界面上的「可清理 N 字节」）。
     *
     * `COALESCE` 兜住空表：`SUM` 在无行时返回 NULL，不兜会得到 0 而不是崩溃 ——
     * 但显式写出来，读者不必回忆 SQL 的这条规则。
     */
    fun sumUnreferencedBytes(): SqlStatement = SqlStatement(
        "SELECT COALESCE(SUM(a.${XStorageTables.Asset.BYTE_SIZE}), 0) " +
            "FROM ${XStorageTables.ASSET} a " +
            "WHERE NOT EXISTS (" +
            "SELECT 1 FROM ${XStorageTables.ASSET_REF} r " +
            "WHERE r.${XStorageTables.AssetRef.ASSET_ID} = a.${XStorageTables.Asset.ID})",
        emptyList(),
    )

    /**
     * 回收候选：无引用、且首次无引用时刻早于 `cutoff`（即已过观察门槛）。
     *
     * 列顺序 `id, path, byte_size, first_unreferenced_at`，按闲置最久优先、同闲置时长按大的优先 ——
     * 先清大件对腾空间的感知最明显。
     */
    fun selectGcCandidates(cutoff: Long): SqlStatement = SqlStatement(
        "SELECT a.${XStorageTables.Asset.ID}, a.${XStorageTables.Asset.PATH}, " +
            "a.${XStorageTables.Asset.BYTE_SIZE}, g.${XStorageTables.AssetGc.FIRST_UNREFERENCED_AT} " +
            "FROM ${XStorageTables.ASSET} a " +
            "JOIN ${XStorageTables.ASSET_GC} g ON g.${XStorageTables.AssetGc.ASSET_ID} = a.${XStorageTables.Asset.ID} " +
            "WHERE NOT EXISTS (" +
            "SELECT 1 FROM ${XStorageTables.ASSET_REF} r " +
            "WHERE r.${XStorageTables.AssetRef.ASSET_ID} = a.${XStorageTables.Asset.ID}) " +
            "AND g.${XStorageTables.AssetGc.FIRST_UNREFERENCED_AT} <= ? " +
            "ORDER BY g.${XStorageTables.AssetGc.FIRST_UNREFERENCED_AT} ASC, " +
            "a.${XStorageTables.Asset.BYTE_SIZE} DESC",
        listOf(cutoff),
    )

    // ────────────────────────────────────────────────────────────────
    // 回收候选登记（只登记,不自动删）
    // ────────────────────────────────────────────────────────────────

    /**
     * 登记一条回收候选。**已存在则完全不改**（`INSERT OR IGNORE`）。
     *
     * 两个字段都不能被后来的扫描覆盖：
     * - `first_unreferenced_at` 是「闲置多久」的**起算点**，每次扫描都刷新的话，
     *   闲置时长永远从今天算起、候选永远等不到门槛；
     * - `reason` 同理保留首次值（它记录的是**因何失去引用**，事后再看才有意义）。
     *
     * **刻意不用 `ON CONFLICT ... DO UPDATE`**（SQLite 3.24+ 语法）：本项目用的是
     * fork 版 sqlite-android，其内嵌 SQLite 版本**本地无法验证**，而 CI 不跑真库 ——
     * 语法不支持的后果只在真机上才暴露。`INSERT OR IGNORE` 是长期稳定语法，且语义恰好相同。
     */
    fun insertGcCandidate(
        assetId: String,
        firstUnreferencedAt: Long,
        generation: Long,
        reason: String,
    ): SqlStatement = SqlStatement(
        "INSERT OR IGNORE INTO ${XStorageTables.ASSET_GC} (" +
            "${XStorageTables.AssetGc.ASSET_ID}, ${XStorageTables.AssetGc.FIRST_UNREFERENCED_AT}, " +
            "${XStorageTables.AssetGc.GENERATION}, ${XStorageTables.AssetGc.REASON}" +
            ") VALUES (?, ?, ?, ?)",
        listOf(assetId, firstUnreferencedAt, generation, reason),
    )

    /** 资产重新被引用 → 撤销候选。 */
    fun deleteGcCandidate(assetId: String): SqlStatement = SqlStatement(
        "DELETE FROM ${XStorageTables.ASSET_GC} WHERE ${XStorageTables.AssetGc.ASSET_ID} = ?",
        listOf(assetId),
    )

    /** 清除全部候选（回填/对账后重建时用）。 */
    fun deleteAllGcCandidates(): SqlStatement = SqlStatement(
        "DELETE FROM ${XStorageTables.ASSET_GC}",
        emptyList(),
    )

    /** 某资产当前的代数；无候选行时无结果（调用方按 0 处理）。 */
    fun selectGeneration(assetId: String): SqlStatement = SqlStatement(
        "SELECT ${XStorageTables.AssetGc.GENERATION} FROM ${XStorageTables.ASSET_GC} " +
            "WHERE ${XStorageTables.AssetGc.ASSET_ID} = ?",
        listOf(assetId),
    )

    // ────────────────────────────────────────────────────────────────
    // 审计
    // ────────────────────────────────────────────────────────────────

    fun insertAudit(
        kind: String,
        entityId: String,
        byteSize: Long?,
        detail: String,
        completedAt: Long,
    ): SqlStatement = SqlStatement(
        "INSERT INTO ${XStorageTables.GC_AUDIT} (" +
            "${XStorageTables.GcAudit.KIND}, ${XStorageTables.GcAudit.ENTITY_ID}, " +
            "${XStorageTables.GcAudit.BYTE_SIZE}, ${XStorageTables.GcAudit.DETAIL}, " +
            "${XStorageTables.GcAudit.COMPLETED_AT}" +
            ") VALUES (?, ?, ?, ?, ?)",
        listOf(kind, entityId, byteSize, detail, completedAt),
    )
}
