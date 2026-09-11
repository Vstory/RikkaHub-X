// [X-custom] RikkaHub-X 存储管理重构：X 资产侧的 Room DAO（改造方案第 3 步）
package me.rerere.rikkahub.x.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * X 资产侧的语句 —— `AssetSql` 那 15 条手写语句的 Room 版本。
 *
 * ## 这一层替掉的是什么
 *
 * `AssetSql` 把 SQL 拼成字符串，再由 `AssetSqlTest` 断言「表名在不在、占位符个数对不对」。
 * 那组断言守的不是**正确性**，而是「拼写没错」—— 它无法知道 `x_asset` 有没有 `byte_size`
 * 这一列。改到这里之后，Room 在**编译期**解析每条语句：列名写错、类型不匹配、
 * 返回类型与列数不符，都在编译时失败。
 *
 * 故本 DAO 落地后，`AssetSqlTest` 的 15 条存在理由消失（它守的事 Room 守得更严）。
 *
 * ## 三条从手写版本继承下来的语义（改写时不能丢）
 *
 * | 语义 | 在哪 | 为什么 |
 * |---|---|---|
 * | `touchLastReferenced` **只在时间前进时写** | `AND last_referenced_at < :at` | 引用登记会因恢复备份、消息重放而乱序到达；直接赋值会让时间倒流，而闲置时长正是回收排序依据 |
 * | `insertRef` 用 **IGNORE 而非 REPLACE** | `OnConflictStrategy.IGNORE` | 重复登记同一引用不该刷新任何东西。REPLACE 会先删后插，白白制造写放大 |
 * | 返回**受影响行数** | `touchLastReferenced` / 各 DELETE 返回 `Int` | 现有调用方用行数做判据（如「是否真的更新了」），不能改成 `Unit` |
 *
 * ## 尚未覆盖的表
 *
 * `x_tombstone` 与 `x_storage_meta` 的读写目前仍在 `XStorageSchema`（`readInt` /
 * `putMeta` 等）。本步只搬资产侧 15 条 —— 与方案的任务分解一致，一次只动一层。
 */
@Dao
interface XAssetDao {

    // ──────────────────────────────────
    // 资产
    // ──────────────────────────────────

    /**
     * 按内容哈希查资产的落盘路径。
     *
     * **只查 `path`**：调用方（`AssetStorePolicy.plan`）只需要路径来决定「复用还是补写」。
     * 多查的列既没用，又会让「列顺序 ↔ 读取顺序」多一处可能对不上的地方。
     */
    @Query("SELECT path FROM x_asset WHERE id = :id")
    fun selectPathByHash(id: String): String?

    /**
     * 登记（或覆盖）一条资产。
     *
     * 用 REPLACE：调用方**只在内容首次落盘时**登记，冲突仅出现在「文件丢失后按原路径
     * 补写」这一种情形 —— 那时刷新时间戳无害（内容未变）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAsset(asset: XAssetEntity): Long

    /**
     * 刷新「最后被引用时刻」，**只在时间前进时写**。
     *
     * @return 受影响行数。0 表示该资产不存在、或已有更新的时间戳（两种都不会有问题）。
     */
    @Query(
        "UPDATE x_asset SET last_referenced_at = :at " +
            "WHERE id = :id AND last_referenced_at < :at"
    )
    fun touchLastReferenced(id: String, at: Long): Int

    /** 删除一条资产。**仅限用户显式确认后**调用（见 `AssetRepository.purgeAsset`）。 */
    @Query("DELETE FROM x_asset WHERE id = :id")
    fun deleteAsset(id: String): Int

    // ──────────────────────────────────
    // 引用
    // ──────────────────────────────────

    /** 登记一条引用。重复登记被忽略 —— 引用表没有时间语义，主键相同就是同一条。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertRef(ref: XAssetRefEntity): Long

    /** 撤销某会话的全部引用（会话被删除时调用）。 */
    @Query("DELETE FROM x_asset_ref WHERE conversation_id = :conversationId")
    fun deleteRefsOfConversation(conversationId: String): Int

    /** 撤销某条消息的全部引用（消息被删除或编辑时调用）。 */
    @Query("DELETE FROM x_asset_ref WHERE message_id = :messageId")
    fun deleteRefsOfMessage(messageId: String): Int

    /** 某资产当前还有多少条引用。0 = 无引用。 */
    @Query("SELECT COUNT(*) FROM x_asset_ref WHERE asset_id = :assetId")
    fun countRefsOfAsset(assetId: String): Int

    // ──────────────────────────────────
    // 回收候选（只读统计 + 只登记）
    // ──────────────────────────────────

    /**
     * 无引用资产的总字节数 —— 界面上的「可清理 N 字节」。
     *
     * `COALESCE` 兜住空表：`SUM` 在无行时返回 NULL，不兜会得到 0 而不是崩溃 ——
     * 但显式写出来，读者不必回忆 SQL 的这条规则。
     */
    @Query(
        "SELECT COALESCE(SUM(a.byte_size), 0) FROM x_asset a " +
            "WHERE NOT EXISTS (SELECT 1 FROM x_asset_ref r WHERE r.asset_id = a.id)"
    )
    fun sumUnreferencedBytes(): Long

    /**
     * 回收候选：无引用、且首次无引用时刻已早于 `cutoff`（即已过观察门槛）。
     *
     * 排序按**闲置最久优先，同闲置时长按大的优先** —— 先清大件对腾空间的感知最明显。
     *
     * 别名（`asset_id` / `relative_path` 等）是为了让 [XGcCandidateRow] 的字段名自然可读；
     * Room 按**别名**做映射，别名写错同样在编译期报错。
     */
    @Query(
        "SELECT a.id AS asset_id, a.path AS relative_path, a.byte_size AS byte_size, " +
            "g.first_unreferenced_at AS first_unreferenced_at " +
            "FROM x_asset a JOIN x_asset_gc g ON g.asset_id = a.id " +
            "WHERE NOT EXISTS (SELECT 1 FROM x_asset_ref r WHERE r.asset_id = a.id) " +
            "AND g.first_unreferenced_at <= :cutoff " +
            "ORDER BY g.first_unreferenced_at ASC, a.byte_size DESC"
    )
    fun selectGcCandidates(cutoff: Long): List<XGcCandidateRow>

    /**
     * 登记一条回收候选。**已存在则完全不改**（IGNORE）。
     *
     * 两个字段都不能被后来的扫描覆盖：
     * - `first_unreferenced_at` 是「闲置多久」的起算点，每次扫描都刷新的话，
     *   闲置时长永远从今天算起、候选永远等不到门槛；
     * - `reason` 记录的是**因何失去引用**，保留首次值事后再看才有意义。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertGcCandidate(candidate: XAssetGcEntity): Long

    /** 资产重新被引用 → 撤销候选。 */
    @Query("DELETE FROM x_asset_gc WHERE asset_id = :assetId")
    fun deleteGcCandidate(assetId: String): Int

    /** 清除全部候选（回填 / 对账后重建时用）。 */
    @Query("DELETE FROM x_asset_gc")
    fun deleteAllGcCandidates(): Int

    /** 某资产当前的代数。无候选行时返回 null（调用方按 0 处理）。 */
    @Query("SELECT generation FROM x_asset_gc WHERE asset_id = :assetId")
    fun selectGeneration(assetId: String): Long?

    // ──────────────────────────────────
    // 审计
    // ──────────────────────────────────

    /**
     * 写一条审计留痕。
     *
     * `id` 交给 SQLite 自增（实体里默认 0 = 不指定）—— 审计只追加，不需要调用方关心编号。
     */
    @Insert
    fun insertAudit(audit: XGcAuditEntity): Long
}

/**
 * 回收候选的查询结果行。
 *
 * 与 `AssetRepository.GcCandidate`（领域模型）分开：这一层只负责「把列读出来」，
 * 领域模型负责「界面怎么展示」。两者混用会让 SQL 的列名变动波及 UI。
 */
data class XGcCandidateRow(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "first_unreferenced_at") val firstUnreferencedAt: Long,
)
