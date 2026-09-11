package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FTS 索引增量维护的纯逻辑自检（X 存储重构 P4 · 上游问题 ⑦）。
 *
 * 这组用例守的是**「哪几行要动」这个判断**,而它判错的方式很隐蔽:
 *
 * | 判错方向 | 后果 | 会报错吗 |
 * |---|---|---|
 * | 少改了该改的行 | **搜到旧内容**,用户以为改了的东西搜不到 | ❌ 不报 |
 * | 多改了不该改的行 | 白做功(等于退回全量重建),性能优势消失 | ❌ 不报 |
 * | 该删的没删 | 搜到**已删除的消息** | ❌ 不报 |
 *
 * 三种都不会抛异常,所以必须在这里把组合列全 —— 真跑一遍要数据库 + jieba 分词器。
 *
 * **性能收益本身也有用例**:`touchedCount` 必须只等于变化行数 ——
 * 若哪天有人把它改回「全量重插」,那条断言会红。
 */
class FtsIndexPlannerTest {

    // ────────────────────────────────────────────────
    // 初次索引（空表）
    // ────────────────────────────────────────────────

    @Test
    fun `empty index inserts everything`() {
        val plan = FtsIndexPlanner.plan(
            indexed = emptyList(),
            desired = listOf(
                DesiredFtsRow("m1", "n1", "第一段"),
                DesiredFtsRow("m2", "n1", "第二段"),
            ),
        )
        assertEquals("空索引时应当全部插入", 2, plan.insert.size)
        assertTrue("不该有删除", plan.deleteRowIds.isEmpty())
        assertTrue("不该有刷新", plan.refresh.isEmpty())
    }

    @Test
    fun `empty desired with empty index is a no op`() {
        assertTrue(FtsIndexPlanner.plan(emptyList(), emptyList()).isEmpty)
    }

    // ────────────────────────────────────────────────
    // 内容没变（增量维护的核心收益）
    // ────────────────────────────────────────────────

    @Test
    fun `unchanged content produces an empty plan`() {
        val plan = FtsIndexPlanner.plan(
            indexed = listOf(
                IndexedFtsRow(10L, "m1", "n1", "第一段"),
                IndexedFtsRow(11L, "m2", "n1", "第二段"),
            ),
            desired = listOf(
                DesiredFtsRow("m1", "n1", "第一段"),
                DesiredFtsRow("m2", "n1", "第二段"),
            ),
        )
        assertTrue("内容没变时一次写盘都不该发生:$plan", plan.isEmpty)
        assertEquals("写盘行数必须为 0", 0, plan.touchedCount)
    }

    // ────────────────────────────────────────────────
    // 流式增长：只动最后那一条
    // ────────────────────────────────────────────────

    @Test
    fun `only the changed message is refreshed`() {
        // 这正是流式输出的形态:前面 99 条一字未变,最后一条在增长。
        // 旧做法会把 100 条全部重新分词;新做法只碰 1 条 —— 收益就在这个差值上。
        val indexed = (1..100).map { i -> IndexedFtsRow(i.toLong(), "m$i", "n1", "内容 $i") }
        val desired = (1..100).map { i ->
            DesiredFtsRow("m$i", "n1", if (i == 100) "内容 100 又长了几个字" else "内容 $i")
        }

        val plan = FtsIndexPlanner.plan(indexed, desired)

        assertEquals("只有那一条需要重写", 1, plan.touchedCount)
        assertEquals("应当是刷新(按 rowid 就地替换),不是先删后插", 1, plan.refresh.size)
        assertEquals("被刷新的应当是最后一条的 rowid", 100L, plan.refresh.first().rowid)
        assertTrue("不该有新插入", plan.insert.isEmpty())
        assertTrue("不该有删除", plan.deleteRowIds.isEmpty())
    }

    @Test
    fun `touched count never exceeds changed rows`() {
        val indexed = (1..50).map { i -> IndexedFtsRow(i.toLong(), "m$i", "n1", "t$i") }
        val desired = (1..50).map { i ->
            DesiredFtsRow("m$i", "n1", if (i in 7..9) "t$i 改了" else "t$i")
        }
        assertEquals("改动 3 条就只该写 3 行", 3, FtsIndexPlanner.plan(indexed, desired).touchedCount)
    }

    // ────────────────────────────────────────────────
    // 删除
    // ────────────────────────────────────────────────

    @Test
    fun `messages no longer present are deleted by rowid`() {
        val plan = FtsIndexPlanner.plan(
            indexed = listOf(
                IndexedFtsRow(10L, "m1", "n1", "留下"),
                IndexedFtsRow(11L, "m2", "n1", "被删"),
            ),
            desired = listOf(DesiredFtsRow("m1", "n1", "留下")),
        )
        assertEquals("该删的只有一行", listOf(11L), plan.deleteRowIds)
        assertTrue("其余不动", plan.refresh.isEmpty() && plan.insert.isEmpty())
    }

    @Test
    fun `text becoming blank removes the row`() {
        // 消息文本变空时**调用方不会把它放进期望集**(空文本不进索引),
        // 故在计划器看来就是「不再需要」→ 删除。这条钉住两侧的约定。
        val plan = FtsIndexPlanner.plan(
            indexed = listOf(IndexedFtsRow(5L, "m1", "n1", "原来有字")),
            desired = emptyList(),
        )
        assertEquals(listOf(5L), plan.deleteRowIds)
    }

    @Test
    fun `message moving to another node is refreshed`() {
        // 文本没变但所属节点变了:node_id 是结果里要返回的字段,必须跟着更新
        val plan = FtsIndexPlanner.plan(
            indexed = listOf(IndexedFtsRow(7L, "m1", "n1", "同样的字")),
            desired = listOf(DesiredFtsRow("m1", "n2", "同样的字")),
        )
        assertEquals("节点变了也要重写", 1, plan.refresh.size)
        assertEquals(7L, plan.refresh.first().rowid)
    }

    // ────────────────────────────────────────────────
    // 异常状态：同一消息多行
    // ────────────────────────────────────────────────

    @Test
    fun `duplicate rows for one message collapse to one`() {
        // 一条消息只该有一行索引。多行若不处理,会随每次保存**持续累积**(索引只增不减)。
        val plan = FtsIndexPlanner.plan(
            indexed = listOf(
                IndexedFtsRow(1L, "m1", "n1", "内容"),
                IndexedFtsRow(2L, "m1", "n1", "内容"),
                IndexedFtsRow(3L, "m1", "n1", "内容"),
            ),
            desired = listOf(DesiredFtsRow("m1", "n1", "内容")),
        )
        assertEquals("保留第一行,其余删掉", listOf(2L, 3L), plan.deleteRowIds)
        assertTrue("内容没变、无需刷新", plan.refresh.isEmpty())
        assertTrue("也不该新插", plan.insert.isEmpty())
    }

    @Test
    fun `duplicate rows with changed text are deleted and refreshed`() {
        val plan = FtsIndexPlanner.plan(
            indexed = listOf(
                IndexedFtsRow(1L, "m1", "n1", "旧"),
                IndexedFtsRow(2L, "m1", "n1", "旧"),
            ),
            desired = listOf(DesiredFtsRow("m1", "n1", "新")),
        )
        assertEquals(listOf(2L), plan.deleteRowIds)
        assertEquals(1, plan.refresh.size)
        assertEquals("刷新的应当是保留下来的那一行", 1L, plan.refresh.first().rowid)
    }

    // ────────────────────────────────────────────────
    // 混合场景与幂等
    // ────────────────────────────────────────────────

    @Test
    fun `mixed changes are planned together`() {
        val plan = FtsIndexPlanner.plan(
            indexed = listOf(
                IndexedFtsRow(1L, "m1", "n1", "不变"),
                IndexedFtsRow(2L, "m2", "n1", "要改"),
                IndexedFtsRow(3L, "m3", "n1", "要删"),
            ),
            desired = listOf(
                DesiredFtsRow("m1", "n1", "不变"),
                DesiredFtsRow("m2", "n1", "改完了"),
                DesiredFtsRow("m4", "n1", "新增的"),
            ),
        )
        assertEquals("删 m3", listOf(3L), plan.deleteRowIds)
        assertEquals("改 m2", listOf(2L), plan.refresh.map { it.rowid })
        assertEquals("增 m4", listOf("m4"), plan.insert.map { it.messageId })
        assertEquals("共 3 处改动", 3, plan.touchedCount)
    }

    @Test
    fun `planning is idempotent`() {
        // 把计划执行一遍之后再算一次,必须是空计划。
        // 做不到就说明判据里混进了会自己变化的东西(时间戳、随机值),
        // 那会让每次保存都无谓重写索引 —— 正是本次要消灭的行为。
        val indexed = listOf(
            IndexedFtsRow(1L, "m1", "n1", "不变"),
            IndexedFtsRow(2L, "m2", "n1", "要改"),
            IndexedFtsRow(3L, "m3", "n1", "要删"),
        )
        val desired = listOf(
            DesiredFtsRow("m1", "n1", "不变"),
            DesiredFtsRow("m2", "n1", "改完了"),
            DesiredFtsRow("m4", "n1", "新增的"),
        )

        val after = applyPlan(indexed, desired, FtsIndexPlanner.plan(indexed, desired))
        val second = FtsIndexPlanner.plan(after, desired)

        assertTrue("执行过一遍之后不该再有任何改动:$second", second.isEmpty)
    }

    @Test
    fun `plan does not depend on input order`() {
        val a = listOf(
            IndexedFtsRow(1L, "m1", "n1", "甲"),
            IndexedFtsRow(2L, "m2", "n1", "乙"),
        )
        val desiredA = listOf(DesiredFtsRow("m1", "n1", "甲"), DesiredFtsRow("m2", "n1", "乙"))
        val desiredB = desiredA.reversed()
        assertTrue(FtsIndexPlanner.plan(a, desiredA).isEmpty)
        assertTrue(FtsIndexPlanner.plan(a, desiredB).isEmpty)
    }

    @Test
    fun `is empty reflects all three action lists`() {
        assertFalse(FtsIndexPlan(deleteRowIds = listOf(1L)).isEmpty)
        assertFalse(FtsIndexPlan(refresh = listOf(FtsRefresh(1L, DesiredFtsRow("m", "n", "t")))).isEmpty)
        assertFalse(FtsIndexPlan(insert = listOf(DesiredFtsRow("m", "n", "t"))).isEmpty)
        assertTrue(FtsIndexPlan().isEmpty)
    }

    // ────────────────────────────────────────────────
    // 夹具：把计划作用到「已索引」上，得到执行后的状态
    // ────────────────────────────────────────────────

    private fun applyPlan(
        indexed: List<IndexedFtsRow>,
        desired: List<DesiredFtsRow>,
        plan: FtsIndexPlan,
    ): List<IndexedFtsRow> {
        val byRowId = indexed.associateBy { it.rowid }.toMutableMap()
        plan.deleteRowIds.forEach { byRowId.remove(it) }
        plan.refresh.forEach {
            byRowId[it.rowid] = IndexedFtsRow(it.rowid, it.desired.messageId, it.desired.nodeId, it.desired.text)
        }
        // 新插入的行由 SQLite 分配 rowid,这里取一个当前未用的值模拟
        var nextRowId = (indexed.maxOfOrNull { it.rowid } ?: 0L) + 1
        plan.insert.forEach {
            byRowId[nextRowId] = IndexedFtsRow(nextRowId, it.messageId, it.nodeId, it.text)
            nextRowId++
        }
        return byRowId.values.toList()
    }
}
