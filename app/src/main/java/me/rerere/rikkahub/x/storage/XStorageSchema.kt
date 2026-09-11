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
 * **约束取舍**：表内保留 `CHECK`（数据自洽，越界即拒写），
 * 但**不建指向上游表的外键** —— 理由见 [XStorageTables.AssetRef] 注释。
 * 因不建外键，级联删除由 X 自己的代码在事务里显式完成（可单测、可审计）。
 */
object XStorageSchema {

    /** X 表结构版本（**独立于** Room 的 `AppDatabase.version`，二者互不影响）。 */
    const val SCHEMA_VERSION = 1

    /** 元数据键：X 表结构版本。 */
    const val META_SCHEMA_VERSION = "x.storage.schema_version"

    /** 元数据键：最近一次 GC 完成时刻（epoch millis）。 */
    const val META_LAST_GC_AT = "x.storage.last_gc_at"

    /** 元数据键：存量回填状态（P1 用；`idle` / `running` / `done`）。 */
    const val META_BACKFILL_STATE = "x.storage.backfill_state"

    /** 元数据键：存量回填游标（P1 断点续跑用）。 */
    const val META_BACKFILL_CURSOR = "x.storage.backfill_cursor"

    /** 建表语句（顺序无关，全部幂等）。单测会校验其与 [XStorageTables] 常量一致。 */
    val CREATE_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS ${XStorageTables.ASSET} (
            ${XStorageTables.Asset.ID} TEXT NOT NULL PRIMARY KEY,
            ${XStorageTables.Asset.PATH} TEXT NOT NULL,
            ${XStorageTables.Asset.BYTE_SIZE} INTEGER NOT NULL,
            ${XStorageTables.Asset.MIME_TYPE} TEXT NOT NULL,
            ${XStorageTables.Asset.ORIGIN} TEXT NOT NULL,
            ${XStorageTables.Asset.WIDTH} INTEGER,
            ${XStorageTables.Asset.HEIGHT} INTEGER,
            ${XStorageTables.Asset.THUMBNAIL_PATH} TEXT,
            ${XStorageTables.Asset.CREATED_AT} INTEGER NOT NULL,
            ${XStorageTables.Asset.LAST_REFERENCED_AT} INTEGER NOT NULL,
            ${XStorageTables.Asset.EXTRAS_JSON} TEXT NOT NULL DEFAULT '{}',
            CHECK (${XStorageTables.Asset.ID} <> ''),
            CHECK (${XStorageTables.Asset.BYTE_SIZE} >= 0),
            CHECK (${XStorageTables.Asset.WIDTH} IS NULL OR ${XStorageTables.Asset.WIDTH} > 0),
            CHECK (${XStorageTables.Asset.HEIGHT} IS NULL OR ${XStorageTables.Asset.HEIGHT} > 0)
        )
        """.trimIndent(),
        // 同一份内容只应有一个落盘文件 → path 唯一
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_x_asset_path ON ${XStorageTables.ASSET} (${XStorageTables.Asset.PATH})",
        // GC 按 last_referenced_at 找候选
        "CREATE INDEX IF NOT EXISTS idx_x_asset_last_referenced ON ${XStorageTables.ASSET} (${XStorageTables.Asset.LAST_REFERENCED_AT})",

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
     * 建立/升级 X 表。**幂等**，可在每次打开库时安全调用。
     *
     * 顺序：先建表（幂等）→ 再读元数据里的版本 → 版本落后则补升级语句 → 记版本。
     * 先建表的原因：元数据表本身也是被建的，读它之前得保证存在。
     *
     * 版本**更高**时不动作 —— 那说明用户装过更新的版本，本版本无从降级，保持原样最安全。
     */
    fun ensure(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            for (statement in CREATE_STATEMENTS) {
                db.execSQL(statement)
            }
            val existing = readInt(db, META_SCHEMA_VERSION) ?: 0
            if (existing < SCHEMA_VERSION) {
                // v1 是首版：CREATE 语句已是最新形态，无增量语句。
                // 后续版本在此按 existing+1..SCHEMA_VERSION 逐级追加 ALTER/回填。
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
    fun readInt(db: SupportSQLiteDatabase, key: String): Int? {
        val cursor = db.query(
            "SELECT ${XStorageTables.Meta.VALUE} FROM ${XStorageTables.META} " +
                "WHERE ${XStorageTables.Meta.KEY} = ?",
            arrayOf<Any?>(key),
        )
        return cursor.use {
            if (it.moveToFirst()) it.getString(0)?.toIntOrNull() else null
        }
    }

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
     * 现无历史版本 → 返回空。将来加表/加列时在此登记，例如：
     * ```
     * 2 -> listOf("ALTER TABLE x_asset ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
     * ```
     */
    private fun upgradeStatementsTo(version: Int): List<String> = emptyList()
}
