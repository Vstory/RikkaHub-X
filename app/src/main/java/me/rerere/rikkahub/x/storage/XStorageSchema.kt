// [X-custom] RikkaHub-X 存储管理重构(P0)：X 表结构的幂等建立与版本元数据
package me.rerere.rikkahub.x.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.atomic.AtomicInteger
import me.rerere.rikkahub.x.diag.XDiagnostics
import me.rerere.rikkahub.x.diag.XDomain
import me.rerere.rikkahub.x.diag.XLog

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
     * 3 → **回收候选改为「只登记、不自动删」**：`not_before` / `attempts` /
     * `last_attempt_at` 三列由单个 `first_unreferenced_at` 取代。删除已改为用户显式确认，
     * 没有自动重试，故不再需要重试次数与退避时间戳。
     *
     * 为何 v2→v3 仍写升级语句（P0 未发布、理论上可只改 v2 定义）：任何跑过 P0 版本
     * 构建的机器上，`x_asset_gc` 已按旧形态建好 —— `CREATE TABLE IF NOT EXISTS`
     * 不会改已存在的表，新代码写入 `first_unreferenced_at` 会直接报「无此列」。
     * 升级语句约十行，换掉一整类「取决于对方是否装过旧构建」的不确定性。
     */
    const val SCHEMA_VERSION = 3

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

    /**
     * `x_asset_gc` 建表语句。
     *
     * 与资产表同理抽出成常量：v2→v3 升级要**按同一形态重建表**，共用一份 DDL
     * 才不会有「升级后的形态与新建的形态不一致」这种隐蔽偏差。
     */
    private val ASSET_GC_TABLE_DDL: String = """
        CREATE TABLE IF NOT EXISTS ${XStorageTables.ASSET_GC} (
            ${XStorageTables.AssetGc.ASSET_ID} TEXT NOT NULL PRIMARY KEY,
            ${XStorageTables.AssetGc.FIRST_UNREFERENCED_AT} INTEGER NOT NULL,
            ${XStorageTables.AssetGc.GENERATION} INTEGER NOT NULL DEFAULT 0,
            ${XStorageTables.AssetGc.REASON} TEXT NOT NULL DEFAULT '',
            CHECK (${XStorageTables.AssetGc.GENERATION} >= 0)
        )
        """.trimIndent()

    /** 回收候选表索引（重建表后需要重新建立）。 */
    private val ASSET_GC_INDEX_STATEMENTS: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS idx_x_asset_gc_first_unreferenced " +
            "ON ${XStorageTables.ASSET_GC} (${XStorageTables.AssetGc.FIRST_UNREFERENCED_AT})",
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

        ASSET_GC_TABLE_DDL,
        *ASSET_GC_INDEX_STATEMENTS.toTypedArray(),

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
     * **事务容错**：本方法可能被从「已经在事务里」的上下文调用（Room 的 `onOpen`
     * 在部分实现/路径下就处于事务中）。此时再 `beginTransaction()` 会抛
     * 「cannot start a transaction within a transaction」—— 而调用方是 `runCatching`，
     * **失败会被吞掉，功能静默失效**。
     * 因建表语句全是 `IF NOT EXISTS`（幂等），已有事务时直接借用外层事务：不自开、不提交，
     * 由外层决定成败；自开事务时仍保持「任一语句失败即整体回滚」，不留半迁移状态。
     *
     * **失败必须能定位**：语句失败时把**语句本身**带进异常 ——
     * 否则只看到一句 `SQLiteException`，不知道是哪条建表语句出的问题。
     */
    fun ensure(db: SupportSQLiteDatabase) {
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            for (statement in CREATE_STATEMENTS) {
                execOrThrow(db, statement)
            }
            val existing = readInt(db, META_SCHEMA_VERSION) ?: 0
            if (existing < SCHEMA_VERSION) {
                for (version in (existing + 1)..SCHEMA_VERSION) {
                    for (statement in upgradeStatementsTo(version)) {
                        execOrThrow(db, statement)
                    }
                }
                putMeta(db, META_SCHEMA_VERSION, SCHEMA_VERSION.toString())
            }
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    /** 执行一条 DDL；失败时把语句带进异常，便于一眼定位。 */
    private fun execOrThrow(db: SupportSQLiteDatabase, statement: String) {
        try {
            db.execSQL(statement)
        } catch (e: Exception) {
            val head = statement.lineSequence().firstOrNull()?.trim().orEmpty()
            throw IllegalStateException("X 表语句执行失败: $head", e)
        }
    }

    // ────────────────────────────────────
    // 首次访问兜底：建表失败不该是静默的
    // ────────────────────────────────────

    private const val ENSURE_PENDING = 0
    private const val ENSURE_RUNNING = 1
    private const val ENSURE_OK = 2
    private const val ENSURE_FAILED = 3

    private val ensureState = AtomicInteger(ENSURE_PENDING)

    /**
     * 在**第一次真正访问 X 表之前**兜底建表；返回 X 表是否可用。
     *
     * **为什么不能只靠 `onOpen`**：实测（2026-09-11）装机后 `x_asset` 始终不存在，
     * 所有写入静默回落到旧路径 —— 失败被 `runCatching` 吞掉，只留一条会滚掉的 logcat。
     * 而建表失败发生在启动时，用户往往是启动**之后**才打开诊断开关，现场就此丢失。
     *
     * 这里做三件事：
     * ① **换一个上下文重试** —— 首次取库时不在 Room 的 `onOpen` 里，那里能成的事这里更能成；
     * ② **失败进留存区**（[XDiagnostics.recordStickyFailure]），与诊断开关无关；
     * ③ **记状态不做无谓重试** —— 失败过就不再每次访问都试，避免刷屏。
     *
     * 代价：每次取库多一次原子读（可忽略）。
     */
    fun ensureOnce(db: SupportSQLiteDatabase): Boolean {
        when (ensureState.get()) {
            ENSURE_OK -> return true
            ENSURE_FAILED -> return false
            ENSURE_RUNNING -> return false // 别的线程在建；本线程先照常走，最坏是这一条查询撞上缺表
        }
        // 只有一个线程去建：并发调用会同时开事务，那种失败信息纯粹是噪声
        if (!ensureState.compareAndSet(ENSURE_PENDING, ENSURE_RUNNING)) return false
        return try {
            ensure(db)
            ensureState.set(ENSURE_OK)
            true
        } catch (e: Exception) {
            ensureState.set(ENSURE_FAILED)
            recordEnsureFailure(e, where = "首次访问兜底")
            false
        }
    }

    /**
     * 记录一次建表失败：一条 logcat 警告 + 一份与诊断开关无关的留存。
     *
     * **留存是关键**：`XLog.warn` 只保证写 logcat，而 logcat 会滚动 ——
     * 用户来反馈时那条早没了（本次就是这样，只能靠猜）。留存让「上次建表失败了」
     * 在诊断页**一直看得见**，直到用户自己清空。
     */
    fun recordEnsureFailure(error: Throwable, where: String) {
        XLog.warn(XDomain.STORAGE, XStorageEvents.SCHEMA_ENSURE_FAIL, error) {
            "X 存储层表建立失败($where),内容寻址与回收功能将不可用"
        }
        XDiagnostics.recordStickyFailure(
            domain = XDomain.STORAGE,
            event = XStorageEvents.SCHEMA_ENSURE_FAIL,
            message = "X 存储层建表失败($where)：内容寻址已停用,新附件仍走旧路径落盘",
            error = error,
        )
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
     * ── 2 → 3：回收候选表改形态 ─────────────────────────────────────────
     * 三列（宽限截止 / 重试次数 / 上次尝试时刻）由 `first_unreferenced_at` 一列取代。
     * 旧值语义可平移：原「宽限截止」记的就是**首次失去引用的时刻 + 宽限**，
     * 故直接搬过来当作首次无引用时刻（略偏保守：实际闲置时间会被算得更久一点）。
     * 同样走重建流程，**重建后必须重跑索引语句**。
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

        /*
         * v2 的列名在此按字面量书写：对应常量已随本次改动删除，而这段语句要读的是
         * **旧表** —— 引用新常量会指向不存在的列。
         */
        3 -> listOf(
            "ALTER TABLE ${XStorageTables.ASSET_GC} RENAME TO $LEGACY_ASSET_GC_TABLE_V2",
            ASSET_GC_TABLE_DDL,
            """
            INSERT OR REPLACE INTO ${XStorageTables.ASSET_GC} (
                ${XStorageTables.AssetGc.ASSET_ID},
                ${XStorageTables.AssetGc.FIRST_UNREFERENCED_AT},
                ${XStorageTables.AssetGc.GENERATION},
                ${XStorageTables.AssetGc.REASON}
            )
            SELECT asset_id, not_before, generation, reason
            FROM $LEGACY_ASSET_GC_TABLE_V2
            """.trimIndent(),
            "DROP TABLE IF EXISTS $LEGACY_ASSET_GC_TABLE_V2",
            *ASSET_GC_INDEX_STATEMENTS.toTypedArray(),
        )

        else -> emptyList()
    }

    /** v1 资产表在 v1→v2 重建期间的临时名。 */
    private const val LEGACY_ASSET_TABLE_V1 = "x_asset_legacy_v1"

    /** v2 回收候选表在 v2→v3 重建期间的临时名。 */
    private const val LEGACY_ASSET_GC_TABLE_V2 = "x_asset_gc_legacy_v2"
}
