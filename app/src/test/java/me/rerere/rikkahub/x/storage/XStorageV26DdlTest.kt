package me.rerere.rikkahub.x.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X 表建表语句自检（X 存储重构 P1 → Room 改造）。
 *
 * 这组用例守的是**「DDL 与列名常量两处手写」这一代价**：
 * Room 的编译期校验能挡住「实体 ↔ DDL」不一致，但挡不住「`XStorageTables` 常量 ↔ DDL」
 * 不一致 —— 而 DDL 仍必须是手写的（Room 只解析实体，不替迁移生成语句）。
 *
 * 故这里把两侧钉在一起，外加几条**设计纪律**的机器化守护：
 *
 * | 纪律 | 断言 |
 * |---|---|
 * | 迁移语句必须幂等 | 全部 `IF NOT EXISTS`（早期构建可能已建过表） |
 * | 迁移语句不得动数据 | 不含 `DROP` / `DELETE`（回滚应手工执行） |
 * | 免 schema 纪律 | 只用于读取/展示的字段不得占真列，必须待在 `extras_json` |
 * | 「手动确认」不得退化成「到点自动删」 | 回收候选表不得重现重试次数与宽限截止列 |
 * | **不得写 `CHECK`** | 见下 |
 *
 * ## 为什么反过来断言「不得含 CHECK」
 *
 * 手写 DDL 早期版本带 `CHECK (byte_size >= 0)` 之类的约束。改用 Room 后这条路走不通：
 * Room 的 `@Entity` **表达不出 `CHECK`**，它生成的建表语句里没有 ——
 * 若迁移里写了 CHECK，则「升级上来的库」与「全新安装的库」结构不同。
 * Room 不比对 CHECK，所以这种不一致**不会报错**，会长期潜伏。
 *
 * 故本次统一放弃 CHECK，校验移到 Kotlin 侧（写入前断言 + 单测）。
 * 这条断言就是防止它被无意间加回来。
 */
class XStorageV26DdlTest {

    private val ddl: String = XStorageV26.creates.joinToString("\n")

    private val tableNameRegex = Regex("CREATE TABLE IF NOT EXISTS (\\w+) \\(")

    // ---- 幂等与无害 ----

    @Test
    fun `every statement is idempotent`() {
        XStorageV26.creates.forEach { statement ->
            assertTrue(
                "语句必须是 IF NOT EXISTS（早期构建可能已建过这些表，重复执行不能失败）:$statement",
                statement.contains("IF NOT EXISTS"),
            )
        }
    }

    @Test
    fun `no statement is destructive`() {
        val upper = ddl.uppercase()
        assertFalse("建表语句不得含 DROP（清临时表那一步在重建流程里，不属于此处）", upper.contains("DROP "))
        assertFalse("建表阶段不得动数据", upper.contains("DELETE "))
        assertFalse("不得含 TRUNCATE", upper.contains("TRUNCATE"))
    }

    @Test
    fun `statements are unique`() {
        assertEquals(
            "重复语句说明复制粘贴出错",
            XStorageV26.creates.size,
            XStorageV26.creates.toSet().size,
        )
    }

    // ---- 表名 ↔ DDL 双向覆盖 ----

    @Test
    fun `ddl table set matches declared constants exactly`() {
        val declared = XStorageV26.creates
            .flatMap { statement -> tableNameRegex.findAll(statement).map { it.groupValues[1] } }
            .toSet()

        assertEquals(
            "XStorageTables.ALL 与实际 DDL 必须一一对应（多一个=建了没登记的影子表，少一个=常量指向不存在的表）",
            XStorageTables.ALL.toSet(),
            declared,
        )
    }

    @Test
    fun `table names are x prefixed`() {
        XStorageTables.ALL.forEach { table ->
            assertTrue("表名需加 x_ 前缀以避免与上游新增表撞名:$table", table.startsWith("x_"))
        }
    }

    // ---- 列名 ↔ 常量双向覆盖 ----

    @Test
    fun `asset columns all appear in ddl`() {
        assertColumns(XStorageTables.ASSET, XStorageTables.Asset.COLUMNS)
    }

    @Test
    fun `asset ref columns all appear in ddl`() {
        assertColumns(XStorageTables.ASSET_REF, XStorageTables.AssetRef.COLUMNS)
    }

    @Test
    fun `asset gc columns all appear in ddl`() {
        assertColumns(XStorageTables.ASSET_GC, XStorageTables.AssetGc.COLUMNS)
    }

    @Test
    fun `gc audit columns all appear in ddl`() {
        assertColumns(XStorageTables.GC_AUDIT, XStorageTables.GcAudit.COLUMNS)
    }

    @Test
    fun `tombstone columns all appear in ddl`() {
        assertColumns(XStorageTables.TOMBSTONE, XStorageTables.Tombstone.COLUMNS)
    }

    @Test
    fun `meta columns all appear in ddl`() {
        assertColumns(XStorageTables.META, XStorageTables.Meta.COLUMNS)
    }

    // ---- 关键约束 ----

    @Test
    fun `ddl carries no check constraints`() {
        // 见类注释:「升级上来的库」与「全新安装的库」必须形态一致,而 Room 表达不出 CHECK
        assertFalse(
            "建表语句不得含 CHECK —— Room 的 @Entity 表达不出它,写了会让新装与升级的库结构不一致",
            ddl.uppercase().contains("CHECK ("),
        )
    }

    @Test
    fun `content addressing invariants hold`() {
        assertTrue(
            "同一路径只应有一个资产行（去重的前提）",
            ddl.contains("CREATE UNIQUE INDEX IF NOT EXISTS idx_x_asset_path"),
        )
    }

    /**
     * **免 schema 纪律的守护**（方案文档 1.4 节）。
     *
     * 只在读取时用的字段必须待在 `extras_json` 里，不得占真列 ——
     * 真列每加一个就多一次迁移，而本仓库是 fork，迁移面即同步冲突面。
     * 这条断言把"别顺手加列"变成机器可查：加了就红。
     */
    @Test
    fun `asset table keeps only addressing and time columns`() {
        val asset = ddlOf(XStorageTables.ASSET)
        val forbidden = listOf("mime_type", "origin", "width", "height", "thumbnail_path", "display_name")
        forbidden.forEach { column ->
            assertFalse(
                "`$column` 只用于读取/展示,应放进 extras_json 而非占真列（免 schema 纪律）:$column",
                asset.contains("$column TEXT") || asset.contains("$column INTEGER"),
            )
        }
        assertEquals(
            "资产表真列集合被改动时,请同步确认是否真的需要索引/约束(免 schema 纪律)",
            listOf(
                XStorageTables.Asset.ID,
                XStorageTables.Asset.PATH,
                XStorageTables.Asset.BYTE_SIZE,
                XStorageTables.Asset.CREATED_AT,
                XStorageTables.Asset.LAST_REFERENCED_AT,
                XStorageTables.Asset.EXTRAS_JSON,
            ),
            XStorageTables.Asset.COLUMNS,
        )
    }

    @Test
    fun `extras keys are namespaced and unique`() {
        val keys = XStorageTables.AssetExtras.ALL
        assertEquals("extras 键重复会互相覆盖", keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertTrue("extras 键需带 asset. 前缀:$key", key.startsWith("asset."))
        }
        // 键名不得与真列同名,否则读起来会分不清数据在哪一侧
        keys.forEach { key ->
            assertFalse(
                "extras 键不得与真列同名:$key",
                XStorageTables.Asset.COLUMNS.contains(key),
            )
        }
    }

    @Test
    fun `reference table keys on message asset kind`() {
        val ref = ddlOf(XStorageTables.ASSET_REF)
        assertTrue(
            "引用以 (message, asset, kind) 为唯一键",
            ref.contains(
                "PRIMARY KEY (${XStorageTables.AssetRef.MESSAGE_ID}, " +
                    "${XStorageTables.AssetRef.ASSET_ID}, ${XStorageTables.AssetRef.KIND})",
            ),
        )
        // 热查询索引:按资产反查引用(回收判定) 与 按消息查资产(渲染) 与会话删除
        assertTrue(ddl.contains("idx_x_asset_ref_asset"))
        assertTrue(ddl.contains("idx_x_asset_ref_message"))
        assertTrue(ddl.contains("idx_x_asset_ref_conversation"))
    }

    @Test
    fun `gc table indexes the reuse gate`() {
        assertTrue(
            "首次无引用时刻是候选排序与「闲置 N 天」的依据,必须有索引",
            ddl.contains("idx_x_asset_gc_first_unreferenced"),
        )
    }

    @Test
    fun `gc table carries no auto retry columns`() {
        // 删除是用户显式触发的单次动作,没有后台自动重试 ——
        // 这两个列若回归,说明「手动确认」这一产品决定被误读成了「到点自动删」
        val gc = ddlOf(XStorageTables.ASSET_GC)
        assertFalse("回收候选不应再有重试次数", gc.contains("attempt"))
        assertFalse("回收候选不应再有宽限截止", gc.contains("not_before"))
    }

    @Test
    fun `tombstone keyed by scope and entity`() {
        val tombstone = ddlOf(XStorageTables.TOMBSTONE)
        assertTrue(
            tombstone.contains(
                "PRIMARY KEY (${XStorageTables.Tombstone.SCOPE}, ${XStorageTables.Tombstone.ENTITY_ID})",
            ),
        )
    }

    @Test
    fun `gc audit primary key is non null`() {
        // SQLite 对 `INTEGER PRIMARY KEY AUTOINCREMENT` 的 PRAGMA notnull 报 0,
        // 而 Room 对自增主键期望 NOT NULL —— 少了这三个字,升级后结构校验不过、库打不开,
        // 且报错信息不会指向这里。故在此钉住。
        val audit = ddlOf(XStorageTables.GC_AUDIT)
        assertTrue(
            "审计表的自增主键必须显式写 NOT NULL（SQLite 不会自动补这个标记位）:$audit",
            audit.contains("${XStorageTables.GcAudit.ID} INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"),
        )
    }

    // ---- 取值常量 ----

    @Test
    fun `origin constants are unique and non blank`() {
        assertUniqueNonBlank(XStorageTables.Origins.ALL, "origin")
    }

    @Test
    fun `ref kind constants are unique and non blank`() {
        assertUniqueNonBlank(XStorageTables.RefKinds.ALL, "kind")
    }

    // ---- helpers ----

    private fun ddlOf(table: String): String {
        val found = XStorageV26.creates.firstOrNull {
            it.contains("CREATE TABLE IF NOT EXISTS $table (")
        }
        assertNotNull("找不到 $table 的建表语句", found)
        return found!!
    }

    private fun assertColumns(table: String, columns: List<String>) {
        val tableDdl = ddlOf(table)
        columns.forEach { column ->
            assertTrue("表 $table 的 DDL 缺少列 $column（常量与 DDL 漂移）", tableDdl.contains(column))
        }
    }

    private fun assertUniqueNonBlank(values: List<String>, label: String) {
        assertTrue("$label 取值不应为空", values.isNotEmpty())
        assertEquals("$label 取值重复", values.size, values.toSet().size)
        values.forEach { assertTrue("$label 取值不应为空白:$it", it.isNotBlank()) }
    }
}
