// [X-custom] RikkaHub-X 存储管理重构：X 表接入 Room 的升级验证（25 → 26）
package me.rerere.rikkahub.x.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `25 → 26` 的升级验证 —— 真的建一个 v25 的库、真的跑一遍 `AutoMigration`、**再由 Room 校验结果**。
 *
 * ## 为什么放在 `androidTest` 而不是 `test`
 *
 * `MigrationTestHelper` 的官方定位是「**用于 instrumentation tests**」：它靠
 * `InstrumentationRegistry.getInstrumentation()` 拿到目标 Context 与 assets 目录。
 * 在纯 JVM 单测里 `Instrumentation` 不存在，即使用 Robolectric 顶替也会在反射初始化时失败
 * （实测：`AndroidInterceptors` → `IllegalAccessException at Reflection.java`）。
 *
 * 本项目已有先例 —— `data/db/migrations/Migration_11_12_Test.kt` 就是按这个方式写的，
 * 且 `androidTestImplementation(libs.androidx.room.testing)` 与 schemas 资产路径**早已配好**。
 * 故本测试照该先例写，**并为它引入任何新的构建配置**（曾误放进 `test`，为此加的
 * Robolectric / test-assets 配置已一并回滚）。
 *
 * ⚠️ **诚实标注**：CI 的 `X Custom Guard` **只跑 `:app:testDebugUnitTest`，不跑 `androidTest`**
 * （后者需要设备/模拟器）。故本测试**当前不在 CI 上执行**，需在真机验证时（线 B 第 10 步）
 * 用 `./gradlew :app:connectedDebugAndroidTest` 手动跑。
 *
 * ## 它守什么（哪怕 AutoMigration 是 Room 自动生成的）
 *
 * 「自动生成」不等于「一定对」。这条路径仍有四件事只有真跑一遍才知道：
 *
 * | # | 要确认的事 | 出错的表现 |
 * |---|---|---|
 * | 1 | **AutoMigration 能被生成**（需要 `25.json`） | 编译期报「无法自动迁移，请手写」 |
 * | 2 | **生成的结构与实体一致** | 升级后 Room 校验失败 → **库打不开** |
 * | 3 | **6 张表真的建出来了** | 表缺失 → 存储层静默退化（本项目已发生过一次） |
 * | 4 | **上游的表与数据没被碰** | 会话数据受损 |
 *
 * 第 2 条由 `runMigrationsAndValidate` 完成：它跑完迁移后**由 Room 逐列校验**结果与
 * `26.json` 是否一致 —— 少列 / 多列 / 类型不符 / 索引名不同 / 主键非空性不符都会被它抓到。
 */
@RunWith(AndroidJUnit4::class)
class Migration25To26Test {

    private val testDb = "x-migration-25-26"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun upgradeFromV25CreatesEveryXTable() {
        helper.createDatabase(testDb, 25).close()

        val db = helper.runMigrationsAndValidate(testDb, 26, true)

        val tables = tableNames(db)
        XStorageTables.ALL.forEach { table ->
            assertTrue("升级后表 $table 不存在（存储层会静默退化）实际:$tables", table in tables)
        }
        assertTrue(
            "唯一索引必须建出（去重的前提）:${indexNames(db)}",
            "idx_x_asset_path" in indexNames(db),
        )
        db.close()
    }

    @Test
    fun upgradeDoesNotTouchUpstreamTablesOrTheirData() {
        // X 表是「加表」而不是「改表」—— 上游的会话数据必须原样保留。
        // 这条挡的是「迁移语句误伤上游表」这类事故：一旦发生，用户丢的是聊天记录。
        val conversationId = "c-upgrade-check"

        helper.createDatabase(testDb, 25).apply {
            execSQL(
                "INSERT INTO conversationentity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, " +
                    "custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id, model_id) " +
                    "VALUES ('$conversationId', 'a1', '升级前就有的会话', '[]', 1000, 2000, '[]', 0, " +
                    "'', '[]', '[]', '', '', '')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 26, true)

        db.query("SELECT title FROM conversationentity WHERE id = ?", arrayOf(conversationId)).use { cursor ->
            assertTrue("升级后原会话必须仍在", cursor.moveToFirst())
            assertEquals("升级前就有的会话", cursor.getString(0))
        }
        db.close()
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
