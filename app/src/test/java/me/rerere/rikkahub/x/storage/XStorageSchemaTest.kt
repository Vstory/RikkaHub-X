package me.rerere.rikkahub.x.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X 表结构自检（X 存储重构 P0）。
 *
 * 这组用例守的是**「无编译期类型安全」这一代价**：
 * 表不是 Room 实体，列名靠手写 SQL 与手写映射对齐 ——
 * 一旦有人改了 DDL 却忘了改映射常量（或反之），
 * 编译不会报错、运行时只在特定路径上静默取错列。
 * 故用单测把两侧钉在一起。
 *
 * 另外钉住几条**幂等性与无害性**（这些是 P0 敢直接跑在既有库上的前提）：
 * 语句必须全部 `IF NOT EXISTS`、不得含破坏性操作。
 */
class XStorageSchemaTest {

    private val ddl: String = XStorageSchema.CREATE_STATEMENTS.joinToString("\n")

    private val tableNameRegex = Regex("CREATE TABLE IF NOT EXISTS (\\w+) \\(")

    // ---- 幂等与无害 ----

    @Test
    fun `every statement is idempotent`() {
        XStorageSchema.CREATE_STATEMENTS.forEach { statement ->
            assertTrue(
                "语句必须是 IF NOT EXISTS（否则重复打开库会失败）:$statement",
                statement.contains("IF NOT EXISTS"),
            )
        }
    }

    @Test
    fun `no statement is destructive`() {
        val upper = ddl.uppercase()
        assertFalse("不得含 DROP（回滚应手工执行）", upper.contains("DROP "))
        assertFalse("不得含 DELETE（建表阶段不该动数据）", upper.contains("DELETE "))
        assertFalse("不得含 TRUNCATE", upper.contains("TRUNCATE"))
    }

    @Test
    fun `statements are unique`() {
        assertEquals(
            "重复语句说明复制粘贴出错",
            XStorageSchema.CREATE_STATEMENTS.size,
            XStorageSchema.CREATE_STATEMENTS.toSet().size,
        )
    }

    // ---- 表名 ↔ DDL 双向覆盖 ----

    @Test
    fun `ddl table set matches declared constants exactly`() {
        val declared = XStorageSchema.CREATE_STATEMENTS
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
    fun `content addressing invariants hold`() {
        val asset = ddlOf(XStorageTables.ASSET)
        assertTrue("资产 id 非空", asset.contains("CHECK (${XStorageTables.Asset.ID} <> '')"))
        assertTrue("字节数非负", asset.contains("CHECK (${XStorageTables.Asset.BYTE_SIZE} >= 0)"))
        assertTrue(
            "宽高若存在必须为正（0 是无效值而非'未知'）",
            asset.contains("CHECK (${XStorageTables.Asset.WIDTH} IS NULL OR ${XStorageTables.Asset.WIDTH} > 0)"),
        )
        assertTrue(
            "同一路径只应有一个资产行",
            ddl.contains("CREATE UNIQUE INDEX IF NOT EXISTS idx_x_asset_path"),
        )
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
        // 热查询索引:按资产反查引用(回收判定) 与 按消息查资产(渲染)
        assertTrue(ddl.contains("idx_x_asset_ref_asset"))
        assertTrue(ddl.contains("idx_x_asset_ref_message"))
        assertTrue(ddl.contains("idx_x_asset_ref_conversation"))
    }

    @Test
    fun `gc table enforces non negative counters`() {
        val gc = ddlOf(XStorageTables.ASSET_GC)
        assertTrue(gc.contains("CHECK (${XStorageTables.AssetGc.ATTEMPTS} >= 0)"))
        assertTrue(gc.contains("CHECK (${XStorageTables.AssetGc.GENERATION} >= 0)"))
        assertTrue(
            "宽限截止是回收的时间闸门,必须有索引",
            ddl.contains("idx_x_asset_gc_not_before"),
        )
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

    // ---- 元数据键 ----

    @Test
    fun `meta keys are namespaced and unique`() {
        val keys = listOf(
            XStorageSchema.META_SCHEMA_VERSION,
            XStorageSchema.META_LAST_GC_AT,
            XStorageSchema.META_BACKFILL_STATE,
            XStorageSchema.META_BACKFILL_CURSOR,
        )
        assertEquals("元数据键重复会互相覆盖", keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertTrue("元数据键需带命名空间,避免与将来其它用途撞键:$key", key.startsWith("x.storage."))
        }
    }

    @Test
    fun `schema version starts at one`() {
        assertTrue("结构版本应从 1 起", XStorageSchema.SCHEMA_VERSION >= 1)
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
        val found = XStorageSchema.CREATE_STATEMENTS.firstOrNull {
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
