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
            "同一路径只应有一个资产行",
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
        // 热查询索引:按资产反查引用(回收判定) 与 按消息查资产(渲染)
        assertTrue(ddl.contains("idx_x_asset_ref_asset"))
        assertTrue(ddl.contains("idx_x_asset_ref_message"))
        assertTrue(ddl.contains("idx_x_asset_ref_conversation"))
    }

    @Test
    fun `gc table enforces non negative generation and indexes the reuse gate`() {
        val gc = ddlOf(XStorageTables.ASSET_GC)
        assertTrue(gc.contains("CHECK (${XStorageTables.AssetGc.GENERATION} >= 0)"))
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

    /**
     * 升级链的守护：v1→v2 的重建流程必须完整。
     *
     * 缺任何一步的后果都是**静默的**：
     * - 缺重建语句 → 旧库永远停在旧形态（mime_type 等 NOT NULL 列还在，后续写入会失败）
     * - 缺索引重建 → 唯一约束静默消失，去重不再成立
     * - 缺临时表清理 → 库里留下幽灵表
     */
    @Test
    fun `upgrade to v2 rebuilds asset table and restores indexes`() {
        val upgrades = XStorageSchema.upgradeStatementsTo(2)
        assertTrue("v1→v2 应有升级语句", upgrades.isNotEmpty())
        assertTrue(
            "重建表后必须重跑索引语句（表改名时索引会跟着走,删旧表即丢索引）",
            upgrades.any { it.contains("CREATE UNIQUE INDEX IF NOT EXISTS idx_x_asset_path") },
        )
        assertTrue(
            "重建流程需搬移旧数据",
            upgrades.any { it.contains("INSERT OR REPLACE INTO ${XStorageTables.ASSET}") },
        )
        assertTrue(
            "重建后需清理临时表",
            upgrades.any { it.contains("DROP TABLE IF EXISTS") },
        )
    }

    /**
     * 升级链的守护：v2→v3 同样必须完整。
     *
     * 少了重建的后果尤其隐蔽 —— 跑过 P0 版本构建的机器上 `x_asset_gc` 仍是旧形态，
     * 而 `CREATE TABLE IF NOT EXISTS` **不会改已存在的表**，新代码写入
     * `first_unreferenced_at` 会直接报「无此列」。
     */
    @Test
    fun `upgrade to v3 rebuilds gc table and restores indexes`() {
        val upgrades = XStorageSchema.upgradeStatementsTo(3)
        assertTrue("v2→v3 应有升级语句", upgrades.isNotEmpty())
        assertTrue(
            "重建表后必须重跑索引语句（表改名时索引会跟着走,删旧表即丢索引）",
            upgrades.any { it.contains("idx_x_asset_gc_first_unreferenced") },
        )
        assertTrue(
            "重建流程需搬移旧数据",
            upgrades.any { it.contains("INSERT OR REPLACE INTO ${XStorageTables.ASSET_GC}") },
        )
        assertTrue(
            "重建后需清理临时表",
            upgrades.any { it.contains("DROP TABLE IF EXISTS") },
        )
    }

    /**
     * 每一版都要有升级语句。
     *
     * 这条挡的是「加了版本号却忘了写语句」—— 后果是旧库**静默**停在旧形态，
     * 直到某条读路径撞上不存在的列才炸，而那时已离改动很远。
     */
    @Test
    fun `every version up to current has upgrade statements`() {
        for (version in 2..XStorageSchema.SCHEMA_VERSION) {
            assertTrue(
                "缺少到 v$version 的升级语句",
                XStorageSchema.upgradeStatementsTo(version).isNotEmpty(),
            )
        }
    }

    @Test
    fun `upgrade statements are not needed for versions below current`() {
        // 无历史版本的档位应为空 —— 否则会在每次打开库时重复执行无用语句
        assertTrue("v1 是首版,不该有增量语句", XStorageSchema.upgradeStatementsTo(1).isEmpty())
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
