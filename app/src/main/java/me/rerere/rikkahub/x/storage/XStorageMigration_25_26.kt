// [X-custom] RikkaHub-X 存储管理重构：X 表接入 Room 的迁移（25 → 26）
package me.rerere.rikkahub.x.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.x.diag.XDomain
import me.rerere.rikkahub.x.diag.XLog

/**
 * X 的 6 张表由「运行时手写 DDL」改为 Room 托管 —— 本迁移负责把**已经装上过旧版本的库**
 * 带到 Room 期望的形态（25 → 26）。
 *
 * ## 为什么放在 `x/storage/` 而不是 `data/db/migrations/`
 *
 * 那个目录是**上游路径**，上游每次加迁移都在那里新增文件；把 X 的迁移放进去会让
 * 每次同步上游都多一处潜在冲突。Room 只要求迁移对象能被 `addMigrations` 引用，
 * **不要求它位于某个包**，故放进 X 自己的包 —— 上游永无此文件，merge 零冲突。
 *
 * ## 为什么不用 `AutoMigration`
 *
 * Room 自动生成的 `CREATE TABLE` **不带 `IF NOT EXISTS`**。而有些设备上早期版本的
 * `ensure()` 可能真建出过表（`onOpen` 是否处于事务中，在全新安装与升级两条路径下未必相同）。
 * 那些设备升级时会撞上 `table already exists` —— 表现是**打不开库**。
 *
 * ## 三件事按顺序做
 *
 * | # | 动作 | 为什么 |
 * |---|---|---|
 * | 1 | **修复早期形态**（v1 / v2 的表） | 早期 P0/P1 构建建出的表少列或多列，`CREATE IF NOT EXISTS` 不会修正已存在的表 |
 * | 2 | **修复 `x_gc_audit` 的主键非空性** | 旧 DDL 写的是 `INTEGER PRIMARY KEY AUTOINCREMENT`，SQLite 报 `notnull=0`；而 Room 对自增主键期望 `NOT NULL` —— 不修则结构校验失败、库打不开 |
 * | 3 | `CREATE TABLE / INDEX IF NOT EXISTS` | 全新升级（表不存在）与「表已存在且形态正确」两种情况都覆盖 |
 *
 * **第 2 项是实测发现的**：对照 Room 生成的 `26.json` 才发现自增主键的 notnull 口径不同。
 * 单看 DDL 字符串看不出问题 —— 它语法合法、也能跑，只是与 Room 的期望差一个标记。
 */
val Migration_25_26 = object : Migration(25, 26) {

    override fun migrate(db: SupportSQLiteDatabase) {
        XLog.info(XDomain.STORAGE, XStorageEvents.MIGRATION_RUN) { "25 → 26 开始(X 表纳入 Room)" }
        DatabaseMigrationTracker.onMigrationStart(25, 26)
        try {
            rebuildLegacyShapes(db)
            normalizeGcAuditPrimaryKey(db)
            CREATE_STATEMENTS.forEach { db.execSQL(it) }
            XLog.info(XDomain.STORAGE, XStorageEvents.MIGRATION_RUN) { "25 → 26 完成" }
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

// ────────────────────────────────────────────────────────────────
// 早期形态修复
// ────────────────────────────────────────────────────────────────

/** v1 资产表的标志列（v2 起移入 `extras_json`）—— 还在 = 仍是 v1 形态。 */
private const val LEGACY_ASSET_V1_MARKER = "mime_type"

/** v2 回收候选表的标志列（v3 起由 `first_unreferenced_at` 取代）—— 还在 = 仍是 v2 形态。 */
private const val LEGACY_ASSET_GC_V2_MARKER = "not_before"

/** v1 资产表在 v1→v2 重建期间的临时名。 */
private const val LEGACY_ASSET_TABLE_V1 = "x_asset_legacy_v1"

/** v2 回收候选表在 v2→v3 重建期间的临时名。 */
private const val LEGACY_ASSET_GC_TABLE_V2 = "x_asset_gc_legacy_v2"

/**
 * 按**形态**而不是版本号判断要不要重建 —— 判据是「那一版的旧标志列还在不在」。
 *
 * 标志列一律是**被后续版本移除/改名**的列。表不存在时 [columnFlags] 返回空 →
 * 跳过（`CREATE_STATEMENTS` 会按新形态建出来）。
 *
 * 这不是过度谨慎：早期构建真的建出过这两种形态，而 `CREATE IF NOT EXISTS`
 * 对已存在的表**不做任何修正**。
 */
private fun rebuildLegacyShapes(db: SupportSQLiteDatabase) {
    // 1 → 2：展示属性（mime_type / origin / width / height / thumbnail_path）从真列移入 extras_json
    if (LEGACY_ASSET_V1_MARKER in columnFlags(db, XStorageTables.ASSET).map { it.name }) {
        XLog.info(XDomain.STORAGE, XStorageEvents.MIGRATION_LEGACY_REBUILD) { "x_asset 仍是 v1 形态,重建" }
        db.execSQL("ALTER TABLE ${XStorageTables.ASSET} RENAME TO $LEGACY_ASSET_TABLE_V1")
        db.execSQL(ASSET_DDL)
        db.execSQL(
            """
            INSERT OR REPLACE INTO ${XStorageTables.ASSET} (
                ${XStorageTables.Asset.ID}, ${XStorageTables.Asset.PATH},
                ${XStorageTables.Asset.BYTE_SIZE}, ${XStorageTables.Asset.CREATED_AT},
                ${XStorageTables.Asset.LAST_REFERENCED_AT}, ${XStorageTables.Asset.EXTRAS_JSON}
            )
            SELECT ${XStorageTables.Asset.ID}, ${XStorageTables.Asset.PATH},
                   ${XStorageTables.Asset.BYTE_SIZE}, ${XStorageTables.Asset.CREATED_AT},
                   ${XStorageTables.Asset.LAST_REFERENCED_AT}, ${XStorageTables.Asset.EXTRAS_JSON}
            FROM $LEGACY_ASSET_TABLE_V1
            """.trimIndent()
        )
        db.execSQL("DROP TABLE IF EXISTS $LEGACY_ASSET_TABLE_V1")
    }

    // 2 → 3：宽限截止 / 重试次数 / 上次尝试时刻 → 单列 first_unreferenced_at
    if (LEGACY_ASSET_GC_V2_MARKER in columnFlags(db, XStorageTables.ASSET_GC).map { it.name }) {
        XLog.info(XDomain.STORAGE, XStorageEvents.MIGRATION_LEGACY_REBUILD) { "x_asset_gc 仍是 v2 形态,重建" }
        db.execSQL("ALTER TABLE ${XStorageTables.ASSET_GC} RENAME TO $LEGACY_ASSET_GC_TABLE_V2")
        db.execSQL(ASSET_GC_DDL)
        db.execSQL(
            """
            INSERT OR REPLACE INTO ${XStorageTables.ASSET_GC} (
                ${XStorageTables.AssetGc.ASSET_ID}, ${XStorageTables.AssetGc.FIRST_UNREFERENCED_AT},
                ${XStorageTables.AssetGc.GENERATION}, ${XStorageTables.AssetGc.REASON}
            )
            SELECT asset_id, not_before, generation, reason
            FROM $LEGACY_ASSET_GC_TABLE_V2
            """.trimIndent()
        )
        db.execSQL("DROP TABLE IF EXISTS $LEGACY_ASSET_GC_TABLE_V2")
    }
}

/**
 * 把 `x_gc_audit` 的自增主键修正为 `NOT NULL`（Room 对 `@PrimaryKey(autoGenerate = true)` 的期望）。
 *
 * 旧 DDL 是 `id INTEGER PRIMARY KEY AUTOINCREMENT`，SQLite 的 `PRAGMA table_info` 对它有
 * 一个反直觉之处：**它报 `notnull = 0`**（主键本身不允许 NULL，但这个标记位不会被置起）。
 * Room 生成的建表语句则是 `NOT NULL`，于是升级后结构校验不一致 → 库打不开。
 *
 * 修法是标准的「改名 → 建新 → 搬数据 → 删旧」；表不存在时 [columnFlags] 为空集，直接跳过。
 */
private fun normalizeGcAuditPrimaryKey(db: SupportSQLiteDatabase) {
    val idColumn = columnFlags(db, XStorageTables.GC_AUDIT).firstOrNull { it.name == XStorageTables.GcAudit.ID }
        ?: return
    if (idColumn.notNull) return
    XLog.info(XDomain.STORAGE, XStorageEvents.MIGRATION_PK_FIX) { "x_gc_audit 主键缺 NOT NULL,重建" }
    db.execSQL("ALTER TABLE ${XStorageTables.GC_AUDIT} RENAME TO x_gc_audit_legacy")
    db.execSQL(GC_AUDIT_DDL)
    db.execSQL(
        """
        INSERT OR REPLACE INTO ${XStorageTables.GC_AUDIT} (
            ${XStorageTables.GcAudit.ID}, ${XStorageTables.GcAudit.KIND},
            ${XStorageTables.GcAudit.ENTITY_ID}, ${XStorageTables.GcAudit.BYTE_SIZE},
            ${XStorageTables.GcAudit.DETAIL}, ${XStorageTables.GcAudit.COMPLETED_AT}
        )
        SELECT ${XStorageTables.GcAudit.ID}, ${XStorageTables.GcAudit.KIND},
               ${XStorageTables.GcAudit.ENTITY_ID}, ${XStorageTables.GcAudit.BYTE_SIZE},
               ${XStorageTables.GcAudit.DETAIL}, ${XStorageTables.GcAudit.COMPLETED_AT}
        FROM x_gc_audit_legacy
        """.trimIndent()
    )
    db.execSQL("DROP TABLE IF EXISTS x_gc_audit_legacy")
}

// ────────────────────────────────────────────────────────────────
// 结构探查
// ────────────────────────────────────────────────────────────────

/** 一列的 PRAGMA 信息里本迁移用到的两位。 */
private data class ColumnFlag(val name: String, val notNull: Boolean)

/** 读 `PRAGMA table_info(table)`。表不存在 → 空集。 */
private fun columnFlags(db: SupportSQLiteDatabase, table: String): List<ColumnFlag> {
    val out = mutableListOf<ColumnFlag>()
    db.query("PRAGMA table_info($table)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        val notNullIndex = cursor.getColumnIndex("notnull")
        if (nameIndex < 0) return emptyList()
        while (cursor.moveToNext()) {
            out.add(ColumnFlag(cursor.getString(nameIndex), cursor.getInt(notNullIndex) != 0))
        }
    }
    return out
}

// ────────────────────────────────────────────────────────────────
// DDL（由运行时建表挪到这里；只有本迁移需要它）
// ────────────────────────────────────────────────────────────────

private val ASSET_DDL: String = """
    CREATE TABLE IF NOT EXISTS ${XStorageTables.ASSET} (
        ${XStorageTables.Asset.ID} TEXT NOT NULL PRIMARY KEY,
        ${XStorageTables.Asset.PATH} TEXT NOT NULL,
        ${XStorageTables.Asset.BYTE_SIZE} INTEGER NOT NULL,
        ${XStorageTables.Asset.CREATED_AT} INTEGER NOT NULL,
        ${XStorageTables.Asset.LAST_REFERENCED_AT} INTEGER NOT NULL,
        ${XStorageTables.Asset.EXTRAS_JSON} TEXT NOT NULL DEFAULT '{}'
    )
""".trimIndent()

private val ASSET_INDEX_STATEMENTS: List<String> = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_x_asset_path " +
        "ON ${XStorageTables.ASSET} (${XStorageTables.Asset.PATH})",
    "CREATE INDEX IF NOT EXISTS idx_x_asset_last_referenced " +
        "ON ${XStorageTables.ASSET} (${XStorageTables.Asset.LAST_REFERENCED_AT})",
)

private val ASSET_GC_DDL: String = """
    CREATE TABLE IF NOT EXISTS ${XStorageTables.ASSET_GC} (
        ${XStorageTables.AssetGc.ASSET_ID} TEXT NOT NULL PRIMARY KEY,
        ${XStorageTables.AssetGc.FIRST_UNREFERENCED_AT} INTEGER NOT NULL,
        ${XStorageTables.AssetGc.GENERATION} INTEGER NOT NULL DEFAULT 0,
        ${XStorageTables.AssetGc.REASON} TEXT NOT NULL DEFAULT ''
    )
""".trimIndent()

private val ASSET_GC_INDEX_STATEMENTS: List<String> = listOf(
    "CREATE INDEX IF NOT EXISTS idx_x_asset_gc_first_unreferenced " +
        "ON ${XStorageTables.ASSET_GC} (${XStorageTables.AssetGc.FIRST_UNREFERENCED_AT})",
)

private val ASSET_REF_DDL: String = """
    CREATE TABLE IF NOT EXISTS ${XStorageTables.ASSET_REF} (
        ${XStorageTables.AssetRef.MESSAGE_ID} TEXT NOT NULL,
        ${XStorageTables.AssetRef.ASSET_ID} TEXT NOT NULL,
        ${XStorageTables.AssetRef.KIND} TEXT NOT NULL,
        ${XStorageTables.AssetRef.CONVERSATION_ID} TEXT NOT NULL,
        ${XStorageTables.AssetRef.CREATED_AT} INTEGER NOT NULL,
        PRIMARY KEY (${XStorageTables.AssetRef.MESSAGE_ID}, ${XStorageTables.AssetRef.ASSET_ID}, ${XStorageTables.AssetRef.KIND})
    )
""".trimIndent()

private val ASSET_REF_INDEX_STATEMENTS: List<String> = listOf(
    "CREATE INDEX IF NOT EXISTS idx_x_asset_ref_asset " +
        "ON ${XStorageTables.ASSET_REF} (${XStorageTables.AssetRef.ASSET_ID})",
    "CREATE INDEX IF NOT EXISTS idx_x_asset_ref_message " +
        "ON ${XStorageTables.ASSET_REF} (${XStorageTables.AssetRef.MESSAGE_ID})",
    "CREATE INDEX IF NOT EXISTS idx_x_asset_ref_conversation " +
        "ON ${XStorageTables.ASSET_REF} (${XStorageTables.AssetRef.CONVERSATION_ID})",
)

/**
 * 审计表的自增主键**必须带 `NOT NULL`** —— 与 Room 生成的建表语句口径一致。
 *
 * 这一处是实测对照 `26.json` 才发现的：SQLite 对 `INTEGER PRIMARY KEY AUTOINCREMENT`
 * 的 `PRAGMA notnull` 报 0，而 Room 写的是 `NOT NULL`。少了这三个字，升级后
 * 结构校验不通过 → 库打不开，且报错信息不会指向这里。
 */
private val GC_AUDIT_DDL: String = """
    CREATE TABLE IF NOT EXISTS ${XStorageTables.GC_AUDIT} (
        ${XStorageTables.GcAudit.ID} INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        ${XStorageTables.GcAudit.KIND} TEXT NOT NULL,
        ${XStorageTables.GcAudit.ENTITY_ID} TEXT NOT NULL,
        ${XStorageTables.GcAudit.BYTE_SIZE} INTEGER,
        ${XStorageTables.GcAudit.DETAIL} TEXT NOT NULL DEFAULT '{}',
        ${XStorageTables.GcAudit.COMPLETED_AT} INTEGER NOT NULL
    )
""".trimIndent()

private val TOMBSTONE_DDL: String = """
    CREATE TABLE IF NOT EXISTS ${XStorageTables.TOMBSTONE} (
        ${XStorageTables.Tombstone.SCOPE} TEXT NOT NULL,
        ${XStorageTables.Tombstone.ENTITY_ID} TEXT NOT NULL,
        ${XStorageTables.Tombstone.DELETED_AT} INTEGER NOT NULL,
        ${XStorageTables.Tombstone.PAYLOAD} TEXT NOT NULL DEFAULT '{}',
        PRIMARY KEY (${XStorageTables.Tombstone.SCOPE}, ${XStorageTables.Tombstone.ENTITY_ID})
    )
""".trimIndent()

private val META_DDL: String = """
    CREATE TABLE IF NOT EXISTS ${XStorageTables.META} (
        ${XStorageTables.Meta.KEY} TEXT NOT NULL PRIMARY KEY,
        ${XStorageTables.Meta.VALUE} TEXT NOT NULL
    )
""".trimIndent()

private val CREATE_STATEMENTS: List<String> = listOf(
    ASSET_DDL,
    *ASSET_INDEX_STATEMENTS.toTypedArray(),
    ASSET_REF_DDL,
    *ASSET_REF_INDEX_STATEMENTS.toTypedArray(),
    ASSET_GC_DDL,
    *ASSET_GC_INDEX_STATEMENTS.toTypedArray(),
    GC_AUDIT_DDL,
    "CREATE INDEX IF NOT EXISTS idx_x_gc_audit_completed " +
        "ON ${XStorageTables.GC_AUDIT} (${XStorageTables.GcAudit.COMPLETED_AT})",
    TOMBSTONE_DDL,
    "CREATE INDEX IF NOT EXISTS idx_x_tombstone_deleted " +
        "ON ${XStorageTables.TOMBSTONE} (${XStorageTables.Tombstone.DELETED_AT})",
    META_DDL,
)

/**
 * 建表语句的对外入口 —— **给同模块单测核对「常量 ↔ DDL」用**。
 *
 * `internal` 而非 public：只有单测需要它。生产路径只有 [Migration_25_26] 会用到这些语句。
 *
 * 为什么仍要保留这层核对：DDL 是**手写**的（Room 只负责解析实体，不会替迁移生成语句），
 * 而 `XStorageTables` 的列名常量是另一处手写 —— 两者漂移时编译不报错。
 * Room 的编译期校验能挡住「实体与 DDL 不一致」，但挡不住「常量与 DDL 不一致」。
 */
internal object XStorageV26 {
    val creates: List<String> get() = CREATE_STATEMENTS
}
