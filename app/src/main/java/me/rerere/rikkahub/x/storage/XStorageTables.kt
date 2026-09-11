// [X-custom] RikkaHub-X 存储管理重构(P0)：表名/列名常量（单一真源，勿与 DDL 漂移）
package me.rerere.rikkahub.x.storage

/**
 * X 存储层的表名与列名常量。
 *
 * **为什么不用 Room 实体**（决策依据见知识库 `存储重构方案.md`「P0 架构决策」）：
 * 注册实体会改 `AppDatabase.kt` 的 `entities` / `version` / `autoMigrations` ——
 * 上游每次加表都动这几行，改它等于**保证反复冲突**（本仓库是 fork）；
 * 且 Room 迁移依赖编译期生成的 schema json（位于 `app/schemas` 目录），而本项目本地不跑构建。
 * 故表由**幂等 DDL** 建立（先例：同库的 `message_fts` 虚拟表就是这样建的，
 * 见 `AppDatabaseFactory` 的 `onOpen` 回调），列名以常量集中维护，
 * 并由 `XStorageSchemaTest` 断言「常量 ↔ DDL」一致，防迁移期漂移。
 *
 * **为什么不另建 DB 文件**：DB 是单文件 `rikka_hub`，备份/同步/恢复
 * （`BackupManager` / `DatabaseBackup` / `S3Sync` / `WebDavSync` / `OfficialBackupCompat`）
 * 全围绕它；另建文件会牵动全部备份路径。故 X 表建在同一库内 —— 备份侧零改动。
 *
 * 表名一律 `x_` 前缀，避免与上游未来新增表撞名。
 */
object XStorageTables {
    const val ASSET = "x_asset"
    const val ASSET_REF = "x_asset_ref"
    const val ASSET_GC = "x_asset_gc"
    const val GC_AUDIT = "x_gc_audit"
    const val TOMBSTONE = "x_tombstone"
    const val META = "x_storage_meta"

    /** 全部 X 表：单测据此断言「常量 ↔ DDL」双向覆盖。 */
    val ALL: List<String> = listOf(ASSET, ASSET_REF, ASSET_GC, GC_AUDIT, TOMBSTONE, META)

    /**
     * 资产表：内容哈希寻址（`id` 即 SHA-256 十六进制）。
     *
     * **真列只留「寻址 / 统计 / 排序」三类**（对齐 Kelivo 的「先免 schema，需要时再提升」）：
     * - 寻址：`id`（哈希）、`path`（唯一）
     * - 统计：`byte_size`
     * - 排序 / 时间闸门：`created_at`、`last_referenced_at`
     * - 扩展：`extras_json`（承载其余全部「只在读取时用」的字段）
     *
     * 其余字段（mime / origin / 宽高 / 缩略图路径 / 显示名…）**刻意不占列** ——
     * 它们不参与任何 SQL 过滤或排序，占列只会白增一次迁移；
     * 对 fork 而言每少加一版，同步上游时的冲突面就少一分。见 [AssetExtras]。
     *
     * 与 Kelivo 的另一处差异（有意简化，已记入方案文档）：Kelivo 用「独立 id +
     * contentHash UNIQUE」两列；因 hash 已唯一，二者恒为 1:1，故只留一列。
     */
    object Asset {
        const val ID = "id"
        const val PATH = "path"
        const val BYTE_SIZE = "byte_size"
        const val CREATED_AT = "created_at"
        const val LAST_REFERENCED_AT = "last_referenced_at"
        const val EXTRAS_JSON = "extras_json"

        val COLUMNS: List<String> = listOf(
            ID, PATH, BYTE_SIZE, CREATED_AT, LAST_REFERENCED_AT, EXTRAS_JSON,
        )
    }

    /**
     * `x_asset.extras_json` 的键。
     *
     * 键名带 `asset.` 前缀（Kelivo 的约定：Keys must be feature-prefixed），
     * 避免不同用途的键在同一 JSON 里撞名。
     *
     * **提升为真列的判据**：一旦某个键需要索引 / 唯一约束 / CHECK / 排序，
     * 就把它提升成真列（届时 `XStorageSchema` 加一版并写重建语句），
     * 而不是让它"永久住在 JSON 里"。
     */
    object AssetExtras {
        const val MIME_TYPE = "asset.mimeType"
        const val ORIGIN = "asset.origin"
        const val WIDTH = "asset.width"
        const val HEIGHT = "asset.height"
        const val THUMBNAIL_PATH = "asset.thumbnailPath"
        const val DISPLAY_NAME = "asset.displayName"

        val ALL: List<String> = listOf(
            MIME_TYPE, ORIGIN, WIDTH, HEIGHT, THUMBNAIL_PATH, DISPLAY_NAME,
        )
    }

    /**
     * 引用表：**取代原先「在消息 JSON 里搜子串」的引用判定**
     * （`MessageNodeDAO.hasFileReference` 的 `instr(messages, url) > 0`）。
     *
     * 刻意**不建外键**指向上游表：`message_id` / `conversation_id` 按值保存。
     * 理由 —— ① 恢复/导入时表间顺序无法保证，外键会挡住合法数据；
     * ② 上游改表结构不应牵连 X 表；③ 级联删除由 X 自己的代码显式完成（可测、可审计）。
     */
    object AssetRef {
        const val MESSAGE_ID = "message_id"
        const val ASSET_ID = "asset_id"
        const val KIND = "kind"
        const val CONVERSATION_ID = "conversation_id"
        const val CREATED_AT = "created_at"

        val COLUMNS: List<String> = listOf(MESSAGE_ID, ASSET_ID, KIND, CONVERSATION_ID, CREATED_AT)
    }

    /**
     * 回收候选队列：**只登记「已无任何引用」的资产，不自动删除**。
     *
     * 删除由用户在存储空间页显式确认（产品决定），故这里没有「宽限截止」——
     * 只记 `first_unreferenced_at`（首次观察到无引用的时刻），供界面显示
     * 「闲置 N 天」并据此排序。
     *
     * `generation` = 代数：资产被重新引用时 `generation + 1`，使**旧候选清单失效**
     * （[AssetGcPolicy.isPlanStale]）。手动流程同样有这个竞态：用户看到清单 →
     * 期间该资产又被某条消息引用 → 此时若仍按看到的那份清单删，就会删掉在用的文件。
     *
     * **刻意没有重试次数与退避时间戳**：删除是用户显式触发的单次动作，失败当场反馈，
     * 不存在后台自动重试 —— 留着这两个字段只会让读者以为存在自动重试。
     */
    object AssetGc {
        const val ASSET_ID = "asset_id"
        const val FIRST_UNREFERENCED_AT = "first_unreferenced_at"
        const val GENERATION = "generation"
        const val REASON = "reason"

        val COLUMNS: List<String> = listOf(ASSET_ID, FIRST_UNREFERENCED_AT, GENERATION, REASON)
    }

    /** 回收审计：每次回收/放弃都留痕（Kelivo `gc_audit_rows` 的对应物）。 */
    object GcAudit {
        const val ID = "id"
        const val KIND = "kind"
        const val ENTITY_ID = "entity_id"
        const val BYTE_SIZE = "byte_size"
        const val DETAIL = "detail"
        const val COMPLETED_AT = "completed_at"

        val COLUMNS: List<String> = listOf(ID, KIND, ENTITY_ID, BYTE_SIZE, DETAIL, COMPLETED_AT)
    }

    /** 墓碑：删除留痕，供同步/多端合并判断「这是删除，不是缺失」。 */
    object Tombstone {
        const val SCOPE = "scope"
        const val ENTITY_ID = "entity_id"
        const val DELETED_AT = "deleted_at"
        const val PAYLOAD = "payload"

        val COLUMNS: List<String> = listOf(SCOPE, ENTITY_ID, DELETED_AT, PAYLOAD)
    }

    /** 存储元数据（key/value）：记 schema 版本、回填进度、GC 世代等。 */
    object Meta {
        const val KEY = "key"
        const val VALUE = "value"

        val COLUMNS: List<String> = listOf(KEY, VALUE)
    }

    /** `x_asset.origin` 取值：资产来自哪条路径（便于统计与按来源回收）。 */
    object Origins {
        const val UPLOAD = "upload"
        const val CHAT_FILE = "chat_file"
        const val GEN_MEDIA = "gen_media"
        const val TOOL_OUTPUT = "tool_output"
        const val SKILL = "skill"
        const val FONT = "font"
        const val UNKNOWN = "unknown"

        val ALL: List<String> =
            listOf(UPLOAD, CHAT_FILE, GEN_MEDIA, TOOL_OUTPUT, SKILL, FONT, UNKNOWN)
    }

    /**
     * `x_asset_ref.kind` 取值：该资产在消息里扮演什么角色。
     *
     * 前四个与 [UIMessagePart][me.rerere.ai.ui.UIMessagePart] 里带 `url` 的类型一一对应，
     * 由 [AssetRefExtractor] 产出（嵌套在 `Tool.output` 里的附件同样按其自身类型记，
     * 因为「这个文件长什么样」与「它由谁产生」是两件事 —— 后者记在 `x_asset.extras_json`
     * 的 `asset.origin`）。
     *
     * [THUMBNAIL] 是预留给缩略图引用（`asset.thumbnailPath` 已存在）的角色名，
     * 当前尚无产出方。
     */
    object RefKinds {
        const val IMAGE = "image"
        const val VIDEO = "video"
        const val AUDIO = "audio"
        const val DOCUMENT = "document"
        const val THUMBNAIL = "thumbnail"

        val ALL: List<String> = listOf(IMAGE, VIDEO, AUDIO, DOCUMENT, THUMBNAIL)
    }

    /** `x_gc_audit.kind` 取值：审计条目类型。 */
    object AuditKinds {
        const val ASSET_DELETED = "asset_deleted"
        const val ORPHAN_SWEPT = "orphan_swept"
        const val BACKFILL_RUN = "backfill_run"
    }
}
