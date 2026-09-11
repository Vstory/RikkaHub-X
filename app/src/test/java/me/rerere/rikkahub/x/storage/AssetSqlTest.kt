package me.rerere.rikkahub.x.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 构造层单测（X 存储重构 P1）。
 *
 * 这组用例守的是**手写 SQL 的代价**：表不是 Room 实体，拿不到 Room 的编译期校验，
 * 语句里的表名/列名/占位符全靠手写。写错的后果都是**编译不报错、运行到那条路径才炸**：
 *
 * ① **占位符与参数个数不等** —— `execSQL` 当场抛异常（少参数）或行为诡异（多参数）；
 * ② 表名拼错或漂移到别的表 —— 运行时 "no such column / table"；
 * ③ 顺手用了破坏性语句 —— 数据没了才发现。
 *
 * 故对**每一条**语句统一断言，而非只挑几条举例。
 */
class AssetSqlTest {

    /** 全部语句 + 人类可读的用途名，供逐条断言。 */
    private fun allStatements(): List<Pair<String, SqlStatement>> = listOf(
        "selectAssetByHash" to AssetSql.selectAssetByHash("h"),
        "upsertAsset" to AssetSql.upsertAsset("h", "assets/ab/cd/h.png", 1L, 2L, 3L, "{}"),
        "touchLastReferenced" to AssetSql.touchLastReferenced("h", 1L),
        "deleteAsset" to AssetSql.deleteAsset("h"),
        "insertRef" to AssetSql.insertRef("m", "h", "image", "c", 1L),
        "deleteRefsOfConversation" to AssetSql.deleteRefsOfConversation("c"),
        "deleteRefsOfMessage" to AssetSql.deleteRefsOfMessage("m"),
        "countRefsOfAsset" to AssetSql.countRefsOfAsset("h"),
        "sumUnreferencedBytes" to AssetSql.sumUnreferencedBytes(),
        "selectGcCandidates" to AssetSql.selectGcCandidates(1L),
        "insertGcCandidate" to AssetSql.insertGcCandidate("h", 1L, 0L, ""),
        "deleteGcCandidate" to AssetSql.deleteGcCandidate("h"),
        "deleteAllGcCandidates" to AssetSql.deleteAllGcCandidates(),
        "selectGeneration" to AssetSql.selectGeneration("h"),
        "insertAudit" to AssetSql.insertAudit("asset_deleted", "h", 1L, "{}", 1L),
    )

    // ---- ① 占位符与参数对账 ----

    @Test
    fun `every statement has as many args as placeholders`() {
        allStatements().forEach { (name, statement) ->
            assertEquals(
                "$name:占位符与参数个数不一致 —— execSQL 会在运行时抛异常",
                AssetSql.placeholders(statement.sql),
                statement.args.size,
            )
        }
    }

    @Test
    fun `no statement body contains a string literal`() {
        // placeholders() 用简单计数。本层语句**不应含任何字符串字面量** ——
        // 既避免误算问号个数,也保证「该传参的一律传参」而非拼进 SQL
        allStatements().forEach { (name, statement) ->
            assertFalse("语句里不应含字符串字面量:$name", statement.sql.contains("'"))
        }
    }

    // ---- ② 表名不漂移 ----

    @Test
    fun `every referenced table is declared in XStorageTables`() {
        val tableRegex = Regex("(?:FROM|INTO|UPDATE|JOIN)\\s+(x_[a-z_]+)")
        val declared = XStorageTables.ALL.toSet()

        allStatements().forEach { (name, statement) ->
            tableRegex.findAll(statement.sql).forEach { match ->
                val table = match.groupValues[1]
                assertTrue(
                    "$name 引用了未声明的表 $table —— 常量与 DDL 已漂移",
                    declared.contains(table),
                )
            }
        }
    }

    @Test
    fun `every statement touches at least one table`() {
        val tableRegex = Regex("(?:FROM|INTO|UPDATE|JOIN)\\s+(x_[a-z_]+)")
        allStatements().forEach { (name, statement) ->
            assertTrue("$name 没引用任何 X 表,疑似写错", tableRegex.containsMatchIn(statement.sql))
        }
    }

    // ---- ③ 不破坏 ----

    @Test
    fun `no statement is structurally destructive`() {
        allStatements().forEach { (name, statement) ->
            val upper = statement.sql.uppercase()
            assertFalse("$name 不得含 DROP", upper.contains("DROP "))
            assertFalse("$name 不得含 TRUNCATE", upper.contains("TRUNCATE"))
        }
    }

    @Test
    fun `unconditional deletes are whitelisted`() {
        // 无 WHERE 的 DELETE = 清空整表。deleteAllGcCandidates 是唯一有意的例外,
        // 故用白名单显式列出,避免「新增语句忘了带 WHERE」被漏掉
        val allowed = setOf("deleteAllGcCandidates")
        allStatements().forEach { (name, statement) ->
            val upper = statement.sql.uppercase()
            if (upper.contains("DELETE FROM") && !upper.contains("WHERE")) {
                assertTrue("$name 是无条件 DELETE,若确属有意请加入白名单", name in allowed)
            }
        }
    }

    // ---- 关键语义 ----

    @Test
    fun `ref insert is ignore-on-conflict`() {
        // 重复登记同一引用不该先删后插(写放大),也不该报错
        val sql = AssetSql.insertRef("m", "h", "image", "c", 1L).sql.uppercase()
        assertTrue("引用登记应为 INSERT OR IGNORE", sql.startsWith("INSERT OR IGNORE"))
        assertFalse("不该用 REPLACE —— 会白白制造写放大", sql.contains("OR REPLACE"))
    }

    @Test
    fun `gc candidate insert never overwrites the first unreferenced time`() {
        // 首次无引用时刻是「闲置多久」的起算点;每次扫描都刷新,候选就永远等不到门槛
        val sql = AssetSql.insertGcCandidate("h", 1L, 0L, "").sql.uppercase()
        assertTrue("候选登记应为 INSERT OR IGNORE", sql.startsWith("INSERT OR IGNORE"))
        assertFalse(
            "不得用 DO UPDATE / REPLACE —— 会覆盖起算点",
            sql.contains("DO UPDATE") || sql.contains("OR REPLACE"),
        )
    }

    @Test
    fun `last referenced only moves forward`() {
        // 引用登记可能因备份恢复/消息重放而乱序到达,直接赋值会让时间倒流,
        // 而「闲置多久」正是候选排序依据
        val upper = AssetSql.touchLastReferenced("h", 100L).sql.uppercase()
        assertTrue(
            "刷新最后引用时刻必须带「小于才写」的条件",
            upper.contains("WHERE") && upper.contains("<"),
        )
    }

    @Test
    fun `unreferenced query uses not-exists rather than a join`() {
        // NOT EXISTS 不必因「一行资产对应多行引用」而额外去重
        val upper = AssetSql.sumUnreferencedBytes().sql.uppercase()
        assertTrue("应用 NOT EXISTS 判定无引用", upper.contains("NOT EXISTS"))
    }

    @Test
    fun `gc candidates exclude referenced assets and apply cutoff`() {
        val statement = AssetSql.selectGcCandidates(12345L)
        val upper = statement.sql.uppercase()
        assertTrue("必须排除仍有引用的资产", upper.contains("NOT EXISTS"))
        assertTrue("必须应用观察门槛", upper.contains("<="))
        assertEquals("门槛值应作为参数传入,不拼进 SQL", listOf<Any?>(12345L), statement.args)
        assertTrue("候选应按闲置最久优先", upper.contains("ORDER BY"))
    }

    @Test
    fun `asset lookup is by content hash`() {
        val statement = AssetSql.selectAssetByHash("abc")
        assertTrue(
            "按内容哈希查 = 主键等值查询",
            statement.sql.contains("${XStorageTables.Asset.ID} = ?"),
        )
        assertEquals(listOf<Any?>("abc"), statement.args)
    }

    // ---- 审计 ----

    @Test
    fun `audit records carry kind entity and time in column order`() {
        val statement = AssetSql.insertAudit("asset_deleted", "abc", 123L, "reason", 999L)
        assertEquals(5, statement.args.size)
        assertEquals("审计参数顺序应与列顺序一致", "asset_deleted", statement.args[0])
        assertEquals("abc", statement.args[1])
        assertEquals(123L, statement.args[2])
        assertEquals("reason", statement.args[3])
        assertEquals(999L, statement.args[4])
    }

    @Test
    fun `audit byte size may be absent`() {
        // 有些审计条目没有字节数(如回填运行记录),参数允许为 null
        val statement = AssetSql.insertAudit("backfill_run", "run-1", null, "{}", 1L)
        assertEquals(5, statement.args.size)
        assertNull(statement.args[2])
    }
}
