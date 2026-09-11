// [X-custom] RikkaHub-X 存储管理重构：X 表纳入 Room 的迁移验证（25 → 26）
package me.rerere.rikkahub.x.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `Migration_25_26` 的验证 —— **在 JVM 上真的建一个 v25 的库、真的跑一遍迁移、再由 Room 校验结果**。
 *
 * ## 为什么这组用例必须存在
 *
 * 迁移是「只在别人的设备上跑一次」的代码：写错了，本机永远不会遇到，
 * 而遇到的人**开不了 App**。而它恰好又是**手写 DDL**（Room 不替迁移生成语句），
 * 编译期没有任何东西能校验它。
 *
 * 这组用例把它钉在三处：
 *
 * | 处 | 作用 |
 * |---|---|
 * | `createDatabase(name, 25)` | 用 **`25.json` 建出真实的 v25 库**（不是手搓的表结构） |
 * | `runMigrationsAndValidate(name, 26, …)` | 跑迁移后**由 Room 逐列校验**结果与 `26.json` 是否一致 |
 * | 逐条断言 | 断「数据有没有搬丢」「旧列有没有消失」这类 Room 校验**看不到**的事 |
 *
 * 中间那一条最关键：Room 的校验能发现「少列 / 多列 / 类型不符 / 索引名不同 /
 * 主键非空性不符」——本次实施中正是它（的对照）暴露了 `x_gc_audit` 自增主键
 * 缺 `NOT NULL` 的问题：少那三个字，升级后结构校验不过、**库打不开**。
 *
 * ## 三个场景对应三种真实设备状态
 *
 * | 场景 | 设备状态 | 为什么会有这种状态 |
 * |---|---|---|
 * | A 表不存在 | **绝大多数用户** | v25 时 X 表由运行时的库打开回调创建，而它可能从未成功 |
 * | B 早期形态的表已存在 | 装过早期 X 版本的用户 | 那时建表语句是 v1 / v2 形态 |
 * | C 已是最新形态的表存在 | 装过修好后的 v25 | 建表已成功且形态正确 |
 *
 * ## 引擎边界（如实说明）
 *
 * 跑在 Robolectric 的宿主 SQLite（framework SQLite）上；生产用 requery SQLite
 * （含 jieba 分词扩展，JVM 加载不了）。X 自己的 6 张表不涉及分词扩展，故有效。
 */
@RunWith(RobolectricTestRunner::class)
class Migration25To26Test {

    private val dbName = "x-migration-25-26"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    // ────────────────────────────────────────────────
    // 场景 A：X 表不存在（绝大多数用户）
    // ────────────────────────────────────────────────

    @Test
    fun `migrates from empty v25 and creates every x table`() {
        helper.createDatabase(dbName, 25).close()

        val db = helper.runMigrationsAndValidate(dbName, 26, true, Migration_25_26)

        assertTrue("表应该都被建出来", tableNames(db).containsAll(XStorageTables.ALL))
        assertEquals(
            "表集合应恰好等于登记的 6 张（多一张=建了没登记的影子表）",
            XStorageTables.ALL.toSet(),
            tableNames(db).filter { it.startsWith("x_") }.toSet(),
        )
        assertTrue("唯一索引必须建出（去重的前提）", indexNames(db).contains("idx_x_asset_path"))
    }

    // ────────────────────────────────────────────────
    // 场景 B：早期形态的表已存在（装过早期 X 版本的用户）
    // ────────────────────────────────────────────────

    @Test
    fun `repairs v1 shaped asset table and keeps its data`() {
        val db = helper.createDatabase(dbName, 25)
        // v1 形态：展示属性还是真列，标志列 mime_type 在 → 判定为仍是 v1
        db.execSQL(
            "CREATE TABLE x_asset (" +
                "id TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, byte_size INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, last_referenced_at INTEGER NOT NULL, " +
                "extras_json TEXT NOT NULL DEFAULT '{}', mime_type TEXT NOT NULL DEFAULT '', " +
                "origin TEXT NOT NULL DEFAULT '', width INTEGER, height INTEGER, thumbnail_path TEXT)"
        )
        db.execSQL(
            "INSERT INTO x_asset (id, path, byte_size, created_at, last_referenced_at, extras_json, mime_type) " +
                "VALUES ('h1', 'assets/ab/h1.png', 123, 1000, 2000, '{}', 'image/png')"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(dbName, 26, true, Migration_25_26)

        val columns = columnNames(migrated, XStorageTables.ASSET)
        assertFalse("旧标志列应已移除（否则库停在 v1 形态）:$columns", "mime_type" in columns)
        XStorageTables.Asset.COLUMNS.forEach { column ->
            assertTrue("重建后缺列 $column:$columns", column in columns)
        }
        assertFalse("临时表应已清理:${tableNames(migrated)}", "x_asset_legacy_v1" in tableNames(migrated))

        // 重建是「改名 → 建新 → 搬数据 → 删旧」，搬漏了这里会抓到
        migrated.query(
            "SELECT path, byte_size, created_at, last_referenced_at FROM x_asset WHERE id = 'h1'"
        ).use { cursor ->
            assertTrue("升级后原行必须仍在", cursor.moveToFirst())
            assertEquals("assets/ab/h1.png", cursor.getString(0))
            assertEquals(123L, cursor.getLong(1))
            assertEquals(1000L, cursor.getLong(2))
            assertEquals(2000L, cursor.getLong(3))
        }
    }

    @Test
    fun `repairs v2 shaped gc table and keeps its data`() {
        val db = helper.createDatabase(dbName, 25)
        // v2 形态：宽限截止 / 重试次数 / 上次尝试时刻三列，标志列 not_before 在
        db.execSQL(
            "CREATE TABLE x_asset_gc (" +
                "asset_id TEXT NOT NULL PRIMARY KEY, not_before INTEGER NOT NULL, " +
                "attempts INTEGER NOT NULL DEFAULT 0, last_attempt_at INTEGER, " +
                "generation INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "INSERT INTO x_asset_gc (asset_id, not_before, attempts, generation, reason) " +
                "VALUES ('h2', 5000, 3, 7, 'unreferenced')"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(dbName, 26, true, Migration_25_26)

        val columns = columnNames(migrated, XStorageTables.ASSET_GC)
        assertFalse("旧标志列应已移除:$columns", "not_before" in columns)
        assertFalse("重试次数列应已移除（不再自动重试）:$columns", "attempts" in columns)
        XStorageTables.AssetGc.COLUMNS.forEach { column ->
            assertTrue("重建后缺列 $column:$columns", column in columns)
        }

        migrated.query(
            "SELECT first_unreferenced_at, generation, reason FROM x_asset_gc WHERE asset_id = 'h2'"
        ).use { cursor ->
            assertTrue("升级后原行必须仍在", cursor.moveToFirst())
            assertEquals("原「宽限截止」应平移为新的「首次无引用时刻」", 5000L, cursor.getLong(0))
            assertEquals(7L, cursor.getLong(1))
            assertEquals("unreferenced", cursor.getString(2))
        }
    }

    @Test
    fun `repairs gc audit primary key missing not null`() {
        // 这是**实测踩到的那个坑**：SQLite 对 `INTEGER PRIMARY KEY AUTOINCREMENT`
        // 的 PRAGMA notnull 报 0，而 Room 对自增主键期望 NOT NULL。
        // 少了这三个字，Room 的结构校验不过 → **库打不开**，且报错不指向这里。
        val db = helper.createDatabase(dbName, 25)
        db.execSQL(
            "CREATE TABLE x_gc_audit (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, entity_id TEXT NOT NULL, " +
                "byte_size INTEGER, detail TEXT NOT NULL DEFAULT '{}', completed_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO x_gc_audit (kind, entity_id, byte_size, detail, completed_at) " +
                "VALUES ('asset_deleted', 'h3', 42, '', 999)"
        )
        db.close()

        // runMigrationsAndValidate 这一步本身就断言了「Room 认为结构一致」——
        // 若迁移没修 NOT NULL，它会在此失败
        val migrated = helper.runMigrationsAndValidate(dbName, 26, true, Migration_25_26)

        val pk = notNullFlags(migrated, XStorageTables.GC_AUDIT)[XStorageTables.GcAudit.ID]
        assertEquals("自增主键必须是非空（Room 的期望）", true, pk)

        migrated.query("SELECT kind, entity_id, byte_size, completed_at FROM x_gc_audit").use { cursor ->
            assertTrue("升级后原行必须仍在", cursor.moveToFirst())
            assertEquals("asset_deleted", cursor.getString(0))
            assertEquals("h3", cursor.getString(1))
            assertEquals(42L, cursor.getLong(2))
            assertEquals(999L, cursor.getLong(3))
        }
    }

    // ────────────────────────────────────────────────
    // 场景 C：已是最新形态的表存在（装过修好后的 v25）
    // ────────────────────────────────────────────────

    @Test
    fun `leaves already correct tables untouched`() {
        val db = helper.createDatabase(dbName, 25)
        // 用迁移自己的建表语句建一遍 = 「已是最新形态」
        XStorageV26.creates.forEach { db.execSQL(it) }
        db.execSQL(
            "INSERT INTO x_asset (id, path, byte_size, created_at, last_referenced_at, extras_json) " +
                "VALUES ('h9', 'assets/ab/h9.png', 7, 1, 2, '{}')"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(dbName, 26, true, Migration_25_26)

        assertFalse("不该重建（没有旧标志列）:${tableNames(migrated)}", "x_asset_legacy_v1" in tableNames(migrated))
        migrated.query("SELECT byte_size FROM x_asset WHERE id = 'h9'").use { cursor ->
            assertTrue("原数据必须原样保留", cursor.moveToFirst())
            assertEquals(7L, cursor.getLong(0))
        }
    }

    // ────────────────────────────────────────────────
    // 夹具
    // ────────────────────────────────────────────────

    private fun tableNames(db: SupportSQLiteDatabase): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun indexNames(db: SupportSQLiteDatabase): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }

    private fun notNullFlags(db: SupportSQLiteDatabase, table: String): Map<String, Boolean> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val notNullIndex = cursor.getColumnIndex("notnull")
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(nameIndex), cursor.getInt(notNullIndex) != 0)
                }
            }
        }
}
