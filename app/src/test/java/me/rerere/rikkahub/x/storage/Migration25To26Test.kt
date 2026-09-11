// [X-custom] RikkaHub-X 存储管理重构：X 表接入 Room 的升级验证（25 → 26）
package me.rerere.rikkahub.x.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `25 → 26` 的升级验证 —— **在 JVM 上真的建一个 v25 的库、真的跑一遍 AutoMigration、再由 Room 校验结果**。
 *
 * ## 为什么需要它（哪怕迁移是 Room 自动生成的）
 *
 * 「自动生成」不等于「一定对」。这条路径仍然有四件事只有真跑一遍才知道：
 *
 * | # | 要确认的事 | 出错的表现 |
 * |---|---|---|
 * | 1 | **AutoMigration 能被生成**（需要 `25.json` 已被编译产出） | 编译期报「无法自动迁移，请手写」 |
 * | 2 | **生成出的结构与实体一致** | 升级后 Room 校验失败 → **库打不开** |
 * | 3 | **6 张表真的建出来了** | 表缺失 → 存储层静默退化（本项目已发生过一次） |
 * | 4 | **上游的表与数据没被碰** | 会话数据受损 |
 *
 * 第 2 条由 `runMigrationsAndValidate` 完成：它跑完迁移后**由 Room 逐列校验**结果与
 * `26.json` 是否一致 —— 少列 / 多列 / 类型不符 / 索引名不同 / 主键非空性不符都会被它抓到。
 *
 * ## 这组用例替代了什么
 *
 * 曾有过一个 **291 行手写迁移**（含「按形态判断并重建旧表」「搬数据」「补主键 NOT NULL」）。
 * 那套复杂度建立在一个**未核实的假设**上 —— 以为 Room 生成的建表语句不带
 * `IF NOT EXISTS`、以为 AutoMigration 不支持新增表。
 * 实测与官方文档都表明两点都相反：Room 生成的语句**带** `IF NOT EXISTS`，
 * 且 AutoMigration **支持新增表**。故手写迁移整体删除，这里改为验证自动路径。
 *
 * ## 关于「表已存在」的情况
 *
 * AutoMigration 生成的 `CREATE TABLE IF NOT EXISTS` 对已存在的表**直接跳过**：
 * - 形态正确 → 无影响；
 * - 形态不对（早期未发布构建的运行时建表产物）→ 跳过建表 → Room 校验失败 → 库打不开。
 *
 * 后者只可能在**装过早期构建的开发机**上出现（实测那些构建里建表从未成功过，
 * 且 P1 尚未发布），真有则卸载重装。故不为它保留任何手写语句。
 */
@RunWith(RobolectricTestRunner::class)
class Migration25To26Test {

    private val dbName = "x-migration-25-26"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun `upgrades from v25 and creates every x table`() {
        helper.createDatabase(dbName, 25).close()

        val db = helper.runMigrationsAndValidate(dbName, 26, false)

        val tables = tableNames(db)
        XStorageTables.ALL.forEach { table ->
            assertTrue("升级后表 $table 不存在（存储层会静默退化）实际:$tables", table in tables)
        }
        assertTrue(
            "唯一索引必须建出（去重的前提）:${indexNames(db)}",
            "idx_x_asset_path" in indexNames(db),
        )
    }

    @Test
    fun `upgrade does not touch upstream tables or their data`() {
        // X 表是「加表」而不是「改表」—— 上游的会话数据必须原样保留。
        // 这条挡的是「迁移语句误伤上游表」这类事故:一旦发生,用户丢的是聊天记录。
        val conversationId = "c-upgrade-check"

        helper.createDatabase(dbName, 25).apply {
            execSQL(
                "INSERT INTO conversationentity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, " +
                    "custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id, model_id) " +
                    "VALUES ('$conversationId', 'a1', '升级前就有的会话', '[]', 1000, 2000, '[]', 0, " +
                    "'', '[]', '[]', '', '', '')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 26, false)

        db.query("SELECT title FROM conversationentity WHERE id = ?", arrayOf(conversationId)).use { cursor ->
            assertTrue("升级后原会话必须仍在", cursor.moveToFirst())
            assertEquals("升级前就有的会话", cursor.getString(0))
        }
    }

    private fun tableNames(db: SupportSQLiteDatabase): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun indexNames(db: SupportSQLiteDatabase): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
}
