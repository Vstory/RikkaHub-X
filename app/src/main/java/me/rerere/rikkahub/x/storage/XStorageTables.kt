// [X-custom] RikkaHub-X 存储管理重构(P0)：表名/列名常量（单一真源，勿与 DDL 漂移）
package me.rerere.rikkahub.x.storage

/**
 * X 存储层的表名与列名常量。
 *
 * **为什么不用 Room 实体**（决策依据见知识库 `存储重构方案.md`「P0 架构决策」）：
 * 注册实体会改 `AppDatabase.kt` 的 `entities` / `version` / `autoMigrations` ——
 * 上游每次加表都动这几行，改它等于**保证反复冲突**（本仓库是 fork）；
 * 且 Room 迁移依赖编译期生成的 `app/schemas/*.json`，而本项目本地不跑构建。
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
     * 与 Kelivo 的差异（有意简化，已记入方案文档）：Kelivo 用「独立 id + contentHash UNIQUE」两列；
     * 因 hash 已 UNIQUE，二者恒为 1:1，故本表只留一列（`id` 即哈希）。
     * 若将来换哈希算法需保留旧 id，再加列即可（表结构可演进）。
     */
    object Asset {
        const val ID = "id"
        const val PATH = "path"
        const val BYTE_SIZE = "byte_size"
        const val MIME_TYPE = "mime_type"
        const val ORIGIN = "origin"
        const val WIDTH = "width"
        const val HEIGHT = "height"
        const val THUMBNAIL_PATH = "thumbnail_path"
        const val CREATED_AT = "created_at"
        const val LAST_REFERENCED_AT = "last_referenced_at"
        const val EXTRAS_JSON = "extras_json"

        val COLUMNS: List<String> = listOf(
            ID, PATH, BYTE_SIZE, MIME_TYPE, ORIGIN, WIDTH, HEIGHT,
            THUMBNAIL_PATH, CREATED_AT, LAST_REFERENCED_AT, EXTRAS_JSON,
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
     * 回收队列：**延迟删除**。
     *
     * `not_before` = 宽限截止；`attempts` = 失败重试次数；`generation` = 代数 ——
     * 资产被重新引用时 `generation + 1`，使**旧计划失效**（`AssetGcPolicy.isPlanStale`），
     * 避免「排队删除期间又被引用」导致的误删。
     */
    object AssetGc {
        const val ASSET_ID = "asset_id"
        const val NOT_BEFORE = "not_before"
        const val ATTEMPTS = "attempts"
        const val LAST_ATTEMPT_AT = "last_attempt_at"
        const val GENERATION = "generation"
        const val REASON = "reason"

        val COLUMNS: List<String> = listOf(
            ASSET_ID, NOT_BEFORE, ATTEMPTS, LAST_ATTEMPT_AT, GENERATION, REASON,
        )
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

    /** `x_asset_ref.kind` 取值：该资产在消息里扮演什么角色。 */
    object RefKinds {
        const val ATTACHMENT = "attachment"
        const val IMAGE = "image"
        const val THUMBNAIL = "thumbnail"
        const val DOCUMENT = "document"
        const val AUDIO = "audio"
        const val TOOL_OUTPUT = "tool_output"

        val ALL: List<String> =
            listOf(ATTACHMENT, IMAGE, THUMBNAIL, DOCUMENT, AUDIO, TOOL_OUTPUT)
    }

    /** `x_gc_audit.kind` 取值：审计条目类型。 */
    object AuditKinds {
        const val ASSET_DELETED = "asset_deleted"
        const val ASSET_ABANDONED = "asset_abandoned"
        const val ORPHAN_SWEPT = "orphan_swept"
        const val BACKFILL_RUN = "backfill_run"
    }
}
