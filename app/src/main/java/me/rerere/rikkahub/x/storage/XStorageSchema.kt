// [X-custom] RikkaHub-X 存储管理重构(P0)：X 表结构的幂等建立与版本元数据
package me.rerere.rikkahub.x.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * X 存储层的表结构定义与建立入口。
 *
 * **建立方式**：在既有 `onOpen` 回调里调用 [ensure]（接缝仅 1 行），
 * 全部语句为 `CREATE ... IF NOT EXISTS` → **可重复执行**，回滚 = `DROP TABLE`。
 * 这样 `AppDatabase.kt`（`entities` / `version` / `autoMigrations`）**零改动**，
 * Room 版本仍为 25，也不产生新的 schema json。
 *
 * **真列纪律（对齐 Kelivo「先免 schema，需要时再提升」，见方案文档 1.4 节）**：
 * 只在读取时用的字段一律放进 `extras_json`，**不占真列**；只有真正需要
 * 索引 / 排序 / 唯一约束 / CHECK 的才提升为列。对 fork 而言，每少加一版，
 * 同步上游时的冲突面就少一分。
 *
 * **约束取舍**：表内保留 `CHECK`（数据自洽，越界即拒写），
 * 但**不建指向上游表的外键** —— 理由见 [XStorageTables.AssetRef] 注释。
 * 因不建外键，级联删除由 X 自己的代码在事务里显式完成（可单测、可审计）。
 */
object XStorageSchema {

    /**
     * X 表结构版本（**独立于** Room 的 `AppDatabase.version`，二者互不影响）。
     *
     * 1 → 初版。
     * 2 → **精简真列**：`mime_type` / `origin` / `width` / `height` / `thumbnail_path`
     * 移入 `extras_json`（它们只用于读取与展示，不参与任何 SQL 过滤或排序）。
     */
    const val SCHEMA_VERSION = 2

    /** 元数据键：X 表结构版本。 */
    const val META_SCHEMA_VERSION = "x.storage.schema_version"

    /** 元数据键：最近一次 GC 完成时刻（epoch millis）。 */
    const val META_LAST_GC_AT = "x.storage.last_gc_at"

    /** 元数据键：存量回填状态（P1 用；`idle` / `running` / `done`）。 */
    const val META_BACKFILL_STATE = "x.storage.backfill_state"

    /** 元数据键：存量回填游标（P1 断点续跑用）。 */
    const val META_BACKFILL_CURSOR = "x.storage.backfill_cursor"

    // ────────────────────────────────────────────────────────────────────
    // 资产表：真列只留「寻址 / 统计 / 排序」三类
    // ────────────────────────────────────────────────────────────────────

    /**
     * `x_asset` 建表语句。
     *
     * 单独抽出成常量，是因为 v1→v2 升级要**按同一形态重建表**（SQLite 的
     * 标准做法：建新表 → 搬数据 → 旧表改名/删除）。共用一份 DDL 才不会有
     * 「升级后的形态与新建的形态不一致」这种隐蔽偏差。
     */
    private val ASSET_TABLE_DDL: String = """
        CREATE TABLE IF NOT EXISTS ${XStorageTables.ASSET} (
            ${XStorageTables.Asset.ID} TEXT NOT NULL PRIMARY KEY,
            ${XStorageTables.Asset.PATH} TEXT NOT NULL,
            ${XStorageTables.Asset.BYTE_SIZE} INTEGER NOT NULL,
            ${XStorageTables.Asset.CREATED_AT} INTEGER NOT NULL,
            ${XStorageTables.Asset.LAST_REFERENCED_AT} INTEGER NOT NULL,
            ${XStorageTables.Asset.EXTRAS_JSON} TEXT NOT NULL DEFAULT '{}',
            CHECK (${XStorageTables.Asset.ID} <> ''),
            CHECK (${XStorageTables.Asset.BYTE_SIZE} >= 0)
        )
    """.trimIndent()

    /** 资产表索引（重建表后需要重新建立，故单独成组）。 */
    private val ASSET_INDEX_STATEMENTS: List<String> = listOf(
        // 同一份内容只应有一个落盘文件 → path 唯一
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_x_asset_path ON ${XStorageTables.ASSET} (${XStorageTables.Asset.PATH})",
        // GC 按 last_referenced_at 找候选
        "CREATE INDEX IF NOT EXISTS idx_x_asset_last_referenced ON ${XStorageTables.ASSET} (${XStorageTables.Asset.LAST_REFERENCED_AT})",
    )

    /** 建表语句（顺序无关，全部幂等）。单测会校验其与 [XStorageTables] 常量一致。 */
    val CREATE_STATEMENTS: List<String> = listOf(
        ASSET_TABLE_DDL,
        *ASSET_INDEX_STATEMENTS.toTypedArray(),

        """
        CREATE TABLE IF NOT EXISTS ${XStorageTables.ASSET_REF} (
            ${XStorageTables.AssetRef.MESSAGE_ID} TEXT NOT NULL,
            ${XStorageTables.AssetRef.ASSET_ID} TEXT NOT NULL,
            ${XStorageTables.AssetRef.KIND} TEXT NOT NULL,
            ${XStorageTables.AssetRef.CONVERSATION_ID} TEXT NOT NULL,
            ${XStorageTables.AssetRef.CREATED_AT} INTEGER NOT NULL,
            PRIMARY KEY (${XStorageTables.AssetRef.MESSAGE_ID}, ${XStorageTables.AssetRef.ASSET_ID}, ${XStorageTables.AssetRef.KIND}),
            CHECK (${XStorageTables.AssetRef.MESSAGE_ID} <> ''),
            CHECK (${XStorageTables.AssetRef.ASSET_ID} <> ''),
            CHECK (${XStorageTables.AssetRef.KIND} <> '')
        )
        """.trimIndent(),
        // 「这个资产还被引用吗」与「这组消息引用了哪些资产」是本设计的两个热查询
        "CREATE INDEX IF NOT EXISTS idx_x_asset_ref_asset ON ${XStorageTables.ASSET_REF} (${XStorageTables.AssetRef.ASSET_ID})",
        "CREATE INDEX IF NOT EXISTS idx_x_asset_ref_message ON ${XStorageTables.ASSET_REF} (${XStorageTables.AssetRef.MESSAGE_ID})",
        "CREATE INDEX IF NOT EXISTS idx_x_asset_ref_conversation ON ${XStorageTables.ASSET_REF} (${XStorageTables.AssetRef.CONVERSATION_ID})",

        """
        CREATE TABLE IF NOT EXISTS ${XStorageTables.ASSET_GC} (
            ${XStorageTables.AssetGc.ASSET_ID} TEXT NOT NULL PRIMARY KEY,
            ${XStorageTables.AssetGc.NOT_BEFORE} INTEGER NOT NULL,
            ${XStorageTables.AssetGc.ATTEMPTS} INTEGER NOT NULL DEFAULT 0,
            ${XStorageTables.AssetGc.LAST_ATTEMPT_AT} INTEGER,
            ${XStorageTables.AssetGc.GENERATION} INTEGER NOT NULL DEFAULT 0,
            ${XStorageTables.AssetGc.REASON} TEXT NOT NULL DEFAULT '',
            CHECK (${XStorageTables.AssetGc.ATTEMPTS} >= 0),
            CHECK (${XStorageTables.AssetGc.GENERATION} >= 0)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_x_asset_gc_not_before ON ${XStorageTables.ASSET_GC} (${XStorageTables.AssetGc.NOT_BEFORE})",

        """
        CREATE TABLE IF NOT EXISTS ${XStorageTables.GC_AUDIT} (
            ${XStorageTables.GcAudit.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${XStorageTables.GcAudit.KIND} TEXT NOT NULL,
            ${XStorageTables.GcAudit.ENTITY_ID} TEXT NOT NULL,
            ${XStorageTables.GcAudit.BYTE_SIZE} INTEGER,
            ${XStorageTables.GcAudit.DETAIL} TEXT NOT NULL DEFAULT '{}',
            ${XStorageTables.GcAudit.COMPLETED_AT} INTEGER NOT NULL,
            CHECK (${XStorageTables.GcAudit.KIND} <> ''),
            CHECK (${XStorageTables.GcAudit.ENTITY_ID} <> '')
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_x_gc_audit_completed ON ${XStorageTables.GC_AUDIT} (${XStorageTables.GcAudit.COMPLETED_AT})",

        """
        CREATE TABLE IF NOT EXISTS ${XStorageTables.TOMBSTONE} (
            ${XStorageTables.Tombstone.SCOPE} TEXT NOT NULL,
            ${XStorageTables.Tombstone.ENTITY_ID} TEXT NOT NULL,
            ${XStorageTables.Tombstone.DELETED_AT} INTEGER NOT NULL,
            ${XStorageTables.Tombstone.PAYLOAD} TEXT NOT NULL DEFAULT '{}',
            PRIMARY KEY (${XStorageTables.Tombstone.SCOPE}, ${XStorageTables.Tombstone.ENTITY_ID}),
            CHECK (${XStorageTables.Tombstone.SCOPE} <> ''),
            CHECK (${XStorageTables.Tombstone.ENTITY_ID} <> '')
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_x_tombstone_deleted ON ${XStorageTables.TOMBSTONE} (${XStorageTables.Tombstone.DELETED_AT})",

        """
        CREATE TABLE IF NOT EXISTS ${XStorageTables.META} (
            ${XStorageTables.Meta.KEY} TEXT NOT NULL PRIMARY KEY,
            ${XStorageTables.Meta.VALUE} TEXT NOT NULL
        )
        """.trimIndent(),
    )

    /**
     * 建表/升级 X 表。**幂等**，可在每次打开库时安全调用。
     *
     * 顺序：先建表（幂等）→ 再读元数据里的版本 → 版本落后则跑增量语句 → 记版本。
     * 先建表的原因：元数据表本身也是被建的，**读它之前得保证它存在**。
     *
     * 版本**更高**时不动作 —— 那说明用户装过更新的版本，本版本无从降级，保持原样最安全。
     *
     * 整个过程在一个事务内：任一语句失败则全部回滚，版本号也不会被写上，
     * 下次打开会重新尝试（不会留下「半迁移」状态）。
     */
    fun ensure(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            for (statement in CREATE_STATEMENTS) {
                db.execSQL(statement)
            }
            val existing = readInt(db, META_SCHEMA_VERSION) ?: 0
            if (existing < SCHEMA_VERSION) {
                for (version in (existing + 1)..SCHEMA_VERSION) {
                    for (statement in upgradeStatementsTo(version)) {
                        db.execSQL(statement)
                    }
                }
                putMeta(db, META_SCHEMA_VERSION, SCHEMA_VERSION.toString())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 读元数据整数值；键不存在或非数字返回 null。 */
    fun readInt(db: SupportSQLiteDatabase, key: String): Int? =
        readString(db, key)?.toIntOrNull()

    /** 读元数据字符串；键不存在返回 null。 */
    fun readString(db: SupportSQLiteDatabase, key: String): String? {
        val cursor = db.query(
            "SELECT ${XStorageTables.Meta.VALUE} FROM ${XStorageTables.META} " +
                "WHERE ${XStorageTables.Meta.KEY} = ?",
            arrayOf<Any?>(key),
        )
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    /** 写元数据（存在即覆盖）。 */
    fun putMeta(db: SupportSQLiteDatabase, key: String, value: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO ${XStorageTables.META} " +
                "(${XStorageTables.Meta.KEY}, ${XStorageTables.Meta.VALUE}) VALUES (?, ?)",
            arrayOf<Any?>(key, value),
        )
    }

    /** 删除元数据键。 */
    fun removeMeta(db: SupportSQLiteDatabase, key: String) {
        db.execSQL(
            "DELETE FROM ${XStorageTables.META} WHERE ${XStorageTables.Meta.KEY} = ?",
            arrayOf<Any?>(key),
        )
    }

    /**
     * 升到 [version] 所需的增量语句。
     *
     * `internal`（而非 private）是为了让同模块单测能直接校验升级链 ——
     * 升级语句写错的代价是**旧库永远停在旧形态**，而这类错误在 CI 里
     * 一跑构建就能暴露，不该等到真机。
     *
     * ── 1 → 2：精简真列 ────────────────────────────────────────────────
     * `mime_type` / `origin` / `width` / `height` / `thumbnail_path` 从真列移入
     * `extras_json`。用 SQLite 的标准重建流程（而不是 `ALTER TABLE DROP COLUMN`）：
     * 前者在任何支持的版本上都能跑，且能顺带把「旧表 NOT NULL 列」的语义一并换掉。
     *
     * 重建后**必须重跑索引语句** —— 表被改名时索引跟着走，旧表一删索引也没了，
     * 少了这步会留下「有表无索引」的隐患（唯一约束会静默消失）。
     */
    internal fun upgradeStatementsTo(version: Int): List<String> = when (version) {
        2 -> listOf(
            "ALTER TABLE ${XStorageTables.ASSET} RENAME TO ${LEGACY_ASSET_TABLE_V1}",
            ASSET_TABLE_DDL,
            """
            INSERT OR REPLACE INTO ${XStorageTables.ASSET} (
                ${XStorageTables.Asset.ID},
                ${XStorageTables.Asset.PATH},
                ${XStorageTables.Asset.BYTE_SIZE},
                ${XStorageTables.Asset.CREATED_AT},
                ${XStorageTables.Asset.LAST_REFERENCED_AT},
                ${XStorageTables.Asset.EXTRAS_JSON}
            )
            SELECT
                ${XStorageTables.Asset.ID},
                ${XStorageTables.Asset.PATH},
                ${XStorageTables.Asset.BYTE_SIZE},
                ${XStorageTables.Asset.CREATED_AT},
                ${XStorageTables.Asset.LAST_REFERENCED_AT},
                ${XStorageTables.Asset.EXTRAS_JSON}
            FROM ${LEGACY_ASSET_TABLE_V1}
            """.trimIndent(),
            "DROP TABLE IF EXISTS ${LEGACY_ASSET_TABLE_V1}",
            *ASSET_INDEX_STATEMENTS.toTypedArray(),
        )

        else -> emptyList()
    }

    /** v1 资产表在 v1→v2 重建期间的临时名。 */
    private const val LEGACY_ASSET_TABLE_V1 = "x_asset_legacy_v1"
}
