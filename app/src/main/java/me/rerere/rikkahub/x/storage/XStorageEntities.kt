// [X-custom] RikkaHub-X 存储管理重构：X 表的 Room 实体（改造方案第 2 步）
package me.rerere.rikkahub.x.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * X 的 6 张表 → Room `@Entity`。
 *
 * **为什么要有这一层**：原先这 6 张表是手写 DDL + 手写映射，列名写错编译不报错、
 * 只在运行到那条语句时抛「no such column」。代价是两次生产事故与一天排查。
 * 改成 Room 后，列名/类型错误在**编译期**就报出来。
 *
 * ## 与既有库逐项对齐（否则迁移后 Room 校验不过）
 *
 * 三条约束把「实体声明」与「已经建好的表」钉在一起：
 *
 * | 项 | 要求 |
 * |---|---|
 * | **表名 / 列名** | 与 `XStorageTables` 的常量逐字一致（本文件用字面量书写，见下方说明） |
 * | **索引名** | 必须显式写成既有名（`idx_x_*`）——Room 默认生成 `index_x_*`，名字不同即校验失败 |
 * | **原生 SQL 默认值** | 带上 `defaultValue`，否则 Room 认为「有默认值的列」不一致 |
 *
 * ## 为什么列名这里写**字面量**而不引用 `XStorageTables` 的常量
 *
 * Room 的注解参数必须是**编译期常量**，而 `XStorageTables.Asset.ID` 这类常量虽也是
 * `const val`，在字符串模板里拼进注解会让报错信息变得难以阅读（错在实体还是错在常量表？）。
 * 故此处按字面量写，并由 `XStorageDdlExecutionTest` 一侧核对两侧一致 —— 手写的两边
 * 必须有一个机械校验，否则「对不齐」这件事没有任何东西会发现。
 *
 * ## 刻意不表达的约束
 *
 * 既有 DDL 里的 `CHECK (byte_size >= 0)` / `CHECK (kind <> '')` 等**没有对应注解**
 * （Room 的 `@Entity` 表达不出来）。方案已确认放弃 CHECK，把校验移到 Kotlin 侧
 * （写入前 `require`）。理由：迁移里手写 CHECK 而 Room 建表没有 → 新装与升级的库
 * 结构不一致，而 Room **不比对 CHECK**，这种偏差不会报错、只会长期潜伏。
 */

/** `x_asset` —— 资产账本。`id` 是内容哈希（SHA-256 十六进制）。 */
@Entity(
    tableName = "x_asset",
    indices = [
        // 同一份内容只应有一个落盘文件
        Index(value = ["path"], unique = true, name = "idx_x_asset_path"),
        // 回收候选按闲置时长筛选
        Index(value = ["last_referenced_at"], name = "idx_x_asset_last_referenced"),
    ],
)
data class XAssetEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_referenced_at") val lastReferencedAt: Long,
    @ColumnInfo(name = "extras_json", defaultValue = "{}") val extrasJson: String = "{}",
)

/**
 * `x_asset_ref` —— 引用登记（消息 ↔ 资产）。
 *
 * 主键是三元组：同一条消息引用同一份资产多次（如正文 + 缩略图）算**不同**引用，
 * 靠 `kind` 区分。故这层不能用「外键级联」替代 —— 它记录的是「谁在用」，
 * 而不是「这个资产存在」。
 */
@Entity(
    tableName = "x_asset_ref",
    primaryKeys = ["message_id", "asset_id", "kind"],
    indices = [
        // 「这个资产还被引用吗」——回收判定最热的查询
        Index(value = ["asset_id"], name = "idx_x_asset_ref_asset"),
        // 「这组消息引用了哪些资产」——删除消息、重建引用时用
        Index(value = ["message_id"], name = "idx_x_asset_ref_message"),
        // 会话删除时按会话批量撤销引用
        Index(value = ["conversation_id"], name = "idx_x_asset_ref_conversation"),
    ],
)
data class XAssetRefEntity(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** `x_asset_gc` —— 回收候选。**只登记，不自动删**；删除由用户显式确认。 */
@Entity(
    tableName = "x_asset_gc",
    indices = [
        // 候选清单按「首次无引用时刻」过观察门槛并排序
        Index(value = ["first_unreferenced_at"], name = "idx_x_asset_gc_first_unreferenced"),
    ],
)
data class XAssetGcEntity(
    @PrimaryKey @ColumnInfo(name = "asset_id") val assetId: String,
    /** 首次观察到无引用的时刻 —— 「闲置多久」的起算点，只写一次，不被后续扫描刷新。 */
    @ColumnInfo(name = "first_unreferenced_at") val firstUnreferencedAt: Long,
    /** 代数：资产重新被引用时递增，使持有旧代数的候选清单失效。 */
    @ColumnInfo(name = "generation", defaultValue = "0") val generation: Long = 0,
    /** 因何失去引用。保留首次值（事后再看才有意义）。 */
    @ColumnInfo(name = "reason", defaultValue = "") val reason: String = "",
)

/** `x_gc_audit` —— 回收审计留痕（自增主键）。 */
@Entity(
    tableName = "x_gc_audit",
    indices = [
        Index(value = ["completed_at"], name = "idx_x_gc_audit_completed"),
    ],
)
data class XGcAuditEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    /** 可空：审计条目未必与字节数有关（如「扫描了一轮」）。 */
    @ColumnInfo(name = "byte_size") val byteSize: Long?,
    @ColumnInfo(name = "detail", defaultValue = "{}") val detail: String = "{}",
    @ColumnInfo(name = "completed_at") val completedAt: Long,
)

/** `x_tombstone` —— 删除墓碑，供多端同步判断「已删除」而非「未同步」。 */
@Entity(
    tableName = "x_tombstone",
    primaryKeys = ["scope", "entity_id"],
    indices = [
        Index(value = ["deleted_at"], name = "idx_x_tombstone_deleted"),
    ],
)
data class XTombstoneEntity(
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    @ColumnInfo(name = "payload", defaultValue = "{}") val payload: String = "{}",
)

/**
 * `x_storage_meta` —— key/value 元数据（结构版本、回填游标等）。
 *
 * `key` / `value` 在 SQLite 里都不是保留字，故不加 `@ColumnInfo` 改名 ——
 * 少一层映射就少一处对不齐的地方。
 */
@Entity(tableName = "x_storage_meta")
data class XStorageMetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)
