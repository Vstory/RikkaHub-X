package me.rerere.rikkahub.x.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「搜关键词」那条路的自检。
 *
 * ## 为什么这些断言值得写
 *
 * 用户对这一页的要求就是「**搜关键词就能定位**」。而筛选规则判错了**全都不会报错** ——
 * 只会表现为「明明有这条却搜不到」,而用户那时会怀疑**日志本身漏了**。
 * 故这里把每条口径都钉住:
 *
 * · 搜哪些字段(含域名与中文标签 —— 用户想「看存储那摊事」时并不记得具体事件名);
 * · 大小写(*事件名全小写,正文里不一定*);
 * · 域筛与关键字是「与」而不是二选一。
 */
class XEventFilterTest {

    private fun entry(at: Long, event: String, message: String = "m") =
        XLogRing.Entry(at = at, level = XLogRing.Level.INFO, event = event, message = message)

    private fun row(domain: XDomain, at: Long, event: String, message: String = "m") =
        XEventFilter.Row(domain, entry(at, event, message))

    private val sample = listOf(
        row(XDomain.CHAT, 30, "chat.message.sent", "发送消息"),
        row(XDomain.STORAGE, 20, "asset.write.new", "新建资产 /data/x.png"),
        row(XDomain.NET, 10, "net.request.failed", "OkHttp 连接被重置"),
    )

    // ────────────────────────────────────
    // 空条件 = 全放行
    // ────────────────────────────────────

    @Test
    fun `an empty query with no domain filter keeps everything`() {
        assertEquals(sample, XEventFilter.apply(sample, "", null))
        assertEquals(sample, XEventFilter.apply(sample, "   ", null))
        assertEquals(sample, XEventFilter.apply(sample, "\n\t ", null))
    }

    @Test
    fun `an empty input stays empty`() {
        assertTrue(XEventFilter.apply(emptyList(), "chat", null).isEmpty())
    }

    // ────────────────────────────────────
    // 搜哪些字段
    // ────────────────────────────────────

    @Test
    fun `the query matches the event name`() {
        val got = XEventFilter.apply(sample, "asset.write", null)
        assertEquals(listOf("asset.write.new"), got.map { it.entry.event })
    }

    @Test
    fun `the query matches the message body`() {
        val got = XEventFilter.apply(sample, "/data/x.png", null)
        assertEquals(listOf("asset.write.new"), got.map { it.entry.event })
    }

    @Test
    fun `the query matches the domain key`() {
        // ⚠️ 这条容易被漏:用户打 `storage` 时期待看到存储那摊事,**即便事件名里没有
        //    「storage」这个字**(存储域的事件名一律是 `asset.*`)。只搜 event/msg 的话
        //    他一条都搜不到,而那与「没记录」长得一模一样。
        val got = XEventFilter.apply(sample, "storage", null)
        assertEquals(listOf("asset.write.new"), got.map { it.entry.event })
    }

    @Test
    fun `the query matches the domain label`() {
        // 同理,打中文也该管用 —— 中文标签是页面上显示的那个名字。
        assertEquals(
            listOf("asset.write.new"),
            XEventFilter.apply(sample, "存储", null).map { it.entry.event },
        )
    }

    @Test
    fun `matching is case insensitive`() {
        // 事件名全小写,而正文里不一定(`OkHttp`);要求用户记住哪种大小写没道理。
        assertEquals(1, XEventFilter.apply(sample, "OKHTTP").size)
        assertEquals(1, XEventFilter.apply(sample, "CHAT.MESSAGE").size)
        assertEquals(1, XEventFilter.apply(sample, "Net.Request").size)
    }

    @Test
    fun `the query is trimmed before matching`() {
        assertEquals(1, XEventFilter.apply(sample, "  chat.message  ", null).size)
    }

    // ────────────────────────────────────
    // 域筛 × 关键字:「与」关系
    // ────────────────────────────────────

    @Test
    fun `the domain filter works on its own`() {
        val got = XEventFilter.apply(sample, "", XDomain.STORAGE)
        assertEquals(listOf("asset.write.new"), got.map { it.entry.event })
    }

    @Test
    fun `query and domain filter are combined with AND`() {
        // 打个 `chat` 又筛了 net 域 —— 期望**空**(两者是「与」,不是二选一)。
        // ⚠️ 若写成「或」,这里会返回 chat 的那条,而用户会以为筛选没生效。
        assertTrue(XEventFilter.apply(sample, "chat", XDomain.NET).isEmpty())
        // 反过来:关键字属于该域时要有结果。
        assertEquals(1, XEventFilter.apply(sample, "net", XDomain.NET).size)
    }

    @Test
    fun `no match returns nothing rather than everything`() {
        // ⚠️ 「筛不出来」与「不筛」必须分得开:若某个分支漏了返回空,用户打了错字却看到
        //    全部记录,会以为搜索根本没生效。
        assertTrue(XEventFilter.apply(sample, "这个字不存在", null).isEmpty())
    }

    // ────────────────────────────────────
    // 造行:合并多域、每域限量
    // ────────────────────────────────────

    @Test
    fun `rows are merged across domains into one newest first timeline`() {
        // 各域内部已经新→旧,但域与域之间必须**交错** —— 否则列表会「先把 chat 列完
        // 再列 storage」,而这一页要的正是**一条时间线**。
        val perDomain = mapOf(
            XDomain.CHAT to listOf(entry(1, "a1"), entry(3, "a3")),      // 由旧到新
            XDomain.STORAGE to listOf(entry(2, "b2")),
        )
        val rows = XEventFilter.buildRows { perDomain[it].orEmpty() }

        assertEquals(listOf("a3", "b2", "a1"), rows.map { it.entry.event })
        assertEquals(listOf(XDomain.CHAT, XDomain.STORAGE, XDomain.CHAT), rows.map { it.domain })
    }

    @Test
    fun `each domain contributes at most its newest slice`() {
        // ⚠️ 每域限量是**刻意**的(见 XEventFilter.PER_DOMAIN_TAKE 的注释):列表最多显示
        //    300 行,把两万条全过一遍是白费。但这意味着「很久以前那条」在页面上搜不到
        //    —— 故页面必须把「只搜最近一批」显示出来,否则用户会以为日志漏了。
        val total = XEventFilter.PER_DOMAIN_TAKE + 5
        val many = (1..total).map { entry(it.toLong(), "e$it") }
        val rows = XEventFilter.buildRows { if (it == XDomain.CHAT) many else emptyList() }

        assertEquals(XEventFilter.PER_DOMAIN_TAKE, rows.size)
        assertEquals("留下的应是最新的那一批", "e$total", rows.first().entry.event)
        assertEquals("而被截掉的是最老的几个", "e6", rows.last().entry.event)
    }

    @Test
    fun `an empty ring yields no rows`() {
        assertTrue(XEventFilter.buildRows { emptyList() }.isEmpty())
    }
}
