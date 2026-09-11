package me.rerere.rikkahub.x.diag

import me.rerere.rikkahub.x.diag.XLogRing.Level
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 诊断中枢单测（诊断框架 D1）。
 *
 * 重点守**开关语义**——用户明确要求「诊断关闭期间不输出调试日志，和上游一样」：
 *
 * | 情形 | 期望 |
 * |---|---|
 * | 关 + 正常流程 | 不记录 |
 * | 关 + 异常 | 不记录（但 logcat 由 [XLog] 负责，另有机检约束） |
 * | 开 | 记录 |
 *
 * 以及**分域隔离** —— 域之间不得互相挤占（上游那种全局共享一份缓冲的病灶），
 * 和**导出只含有内容的域**（否则诊断页上是一屏空标题）。
 */
class XDiagnosticsTest {

    private val filesRoot = "/data/user/0/me.rerere.rikkahub.x/files"
    private val hash = "a3f9c2d1" + "0".repeat(56)

    @Before
    fun setUp() {
        XDiagnostics.clearAll()
        XDiagnostics.setEnabled(false)
    }

    @After
    fun tearDown() {
        XDiagnostics.clearAll()
        XDiagnostics.setEnabled(false)
    }

    // ---- 开关语义 ----

    @Test
    fun `defaults to disabled`() {
        assertFalse("诊断默认必须是关的", XDiagnostics.isEnabled())
        assertNull("没有记录则无起点", XDiagnostics.windowStartMillis())
    }

    @Test
    fun `records when enabled`() {
        XDiagnostics.setEnabled(true)
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "asset.write.new", "新建")
        assertEquals(1, XDiagnostics.countOf(XDomain.STORAGE))
    }

    @Test
    fun `buffer accumulates regardless of switch because record is the low level api`() {
        // record() 是底层入口,开关判断在上层(XLog)。这里固化该分层约定:
        // 若将来有人把开关判断挪进 record,本用例会红,提醒同步改 XLog 的文档
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "x")
        assertEquals(1, XDiagnostics.countOf(XDomain.STORAGE))
    }

    @Test
    fun `turning off keeps what was recorded`() {
        // 关掉后再导出是很自然的顺序;若关闭即清空,用户必须先导出才能关,很别扭
        XDiagnostics.setEnabled(true)
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "x")
        XDiagnostics.setEnabled(false)
        assertEquals("关闭后内容应保留", 1, XDiagnostics.countOf(XDomain.STORAGE))
    }

    // ---- 记录起点(推导值) ----

    @Test
    fun `window start equals the earliest record`() {
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "第一条")
        val first = XDiagnostics.entries(XDomain.STORAGE).first().at
        XDiagnostics.record(XDomain.COMPRESS, Level.INFO, "e", "第二条")
        assertEquals("应取全域里最早一条的时刻", first, XDiagnostics.windowStartMillis())
    }

    @Test
    fun `window start is null when there is nothing recorded`() {
        assertNull(XDiagnostics.windowStartMillis())
    }

    @Test
    fun `clearing removes the window start too`() {
        // 这是把起点做成推导值而非存储字段的直接收益:
        // 若存字段,清空后会出现「有记录却显示从未开启」或「没记录却显示起点」这类自相矛盾
        XDiagnostics.setEnabled(true)
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "x")
        assertTrue(XDiagnostics.windowStartMillis() != null)
        XDiagnostics.clearAll()
        assertNull("清空后不该再有起点", XDiagnostics.windowStartMillis())
    }

    @Test
    fun `window start survives turning the switch off`() {
        // 关闭保留内容 → 起点也必须还在,否则导出时表头说「无记录」而正文有内容
        XDiagnostics.setEnabled(true)
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "x")
        XDiagnostics.setEnabled(false)
        assertTrue(XDiagnostics.windowStartMillis() != null)
    }

    // ---- 分域隔离 ----

    @Test
    fun `domains do not crowd each other out`() {
        // 上游那种全局共享一份 100 条缓冲的病灶:某一域打点密,就把别的域挤没了
        XDiagnostics.setEnabled(true)
        repeat(XDiagnostics.MAX_ENTRIES_PER_DOMAIN) {
            XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "存储 $it")
        }
        XDiagnostics.record(XDomain.COMPRESS, Level.INFO, "e", "压缩 一条")

        assertEquals(XDiagnostics.MAX_ENTRIES_PER_DOMAIN, XDiagnostics.countOf(XDomain.STORAGE))
        assertEquals("其它域不受影响", 1, XDiagnostics.countOf(XDomain.COMPRESS))
    }

    @Test
    fun `per domain capacity is enforced`() {
        XDiagnostics.setEnabled(true)
        repeat(XDiagnostics.MAX_ENTRIES_PER_DOMAIN + 100) {
            XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "第 $it 条")
        }
        assertEquals(XDiagnostics.MAX_ENTRIES_PER_DOMAIN, XDiagnostics.countOf(XDomain.STORAGE))
        assertEquals(
            "最新一条必须在",
            "第 ${XDiagnostics.MAX_ENTRIES_PER_DOMAIN + 99} 条",
            XDiagnostics.entries(XDomain.STORAGE).last().message,
        )
    }

    @Test
    fun `total count sums all domains`() {
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "a")
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "b")
        XDiagnostics.record(XDomain.COMPRESS, Level.INFO, "e", "c")
        assertEquals(3, XDiagnostics.totalCount())
    }

    @Test
    fun `domains with content lists only non empty ones`() {
        XDiagnostics.record(XDomain.MCP, Level.INFO, "e", "x")
        assertEquals(listOf(XDomain.MCP), XDiagnostics.domainsWithContent())
    }

    // ---- 导出 ----

    @Test
    fun `dump is empty when nothing recorded`() {
        assertTrue(XDiagnostics.dump().isEmpty())
        assertEquals(XDiagnostics.EMPTY_DUMP, XDiagnostics.dumpMerged())
    }

    @Test
    fun `dump only contains domains with content`() {
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "x")
        val dump = XDiagnostics.dump()
        assertEquals(setOf(XDomain.STORAGE), dump.keys)
    }

    @Test
    fun `dump carries a header with label key count and window`() {
        XDiagnostics.setEnabled(true)
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "asset.write.new", "新建")
        val text = XDiagnostics.dump().getValue(XDomain.STORAGE)

        assertTrue("表头应含中文标签", text.contains("# 存储"))
        assertTrue("表头应含域 key(导出文件名用)", text.contains("storage"))
        assertTrue("表头应含条数", text.contains("# 条数: 1"))
        assertTrue("表头应说明脱敏状态", text.contains("# 脱敏: 是"))
        assertTrue("表头应含记录起点", text.contains("# 记录起点: "))
        assertFalse("有记录时不该说无记录", text.contains("(无记录)"))
        assertTrue("正文应含事件名", text.contains("asset.write.new"))
    }

    @Test
    fun `dump marks full mode in the header`() {
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "x")
        val text = XDiagnostics.dump(full = true).getValue(XDomain.STORAGE)
        assertTrue(text.contains("# 脱敏: 否"))
    }

    @Test
    fun `dump redacts by default`() {
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "asset.write.new", "$filesRoot/assets/ab/cd/$hash.png")
        val text = XDiagnostics.dump(filesRoot = filesRoot).getValue(XDomain.STORAGE)
        assertFalse("默认应抹掉应用私有目录前缀", text.contains(filesRoot))
        assertFalse("默认应截断哈希", text.contains(hash))
        assertTrue("但保留可定位的相对路径", text.contains("assets/ab/cd/"))
        assertTrue("并保留哈希前缀", text.contains(hash.take(8)))
    }

    @Test
    fun `dump with full mode keeps everything`() {
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "$filesRoot/assets/$hash.png")
        val text = XDiagnostics.dump(full = true, filesRoot = filesRoot).getValue(XDomain.STORAGE)
        assertTrue(text.contains(filesRoot))
        assertTrue(text.contains(hash))
    }

    @Test
    fun `dump can be limited to selected domains`() {
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "存储")
        XDiagnostics.record(XDomain.COMPRESS, Level.INFO, "e", "压缩")
        val dump = XDiagnostics.dump(domains = listOf(XDomain.STORAGE))
        assertEquals(setOf(XDomain.STORAGE), dump.keys)
    }

    @Test
    fun `merged dump separates domains by a blank line`() {
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "存储")
        XDiagnostics.record(XDomain.COMPRESS, Level.INFO, "e", "压缩")
        val merged = XDiagnostics.dumpMerged()
        assertTrue(merged.contains("存储"))
        assertTrue(merged.contains("压缩"))
        assertTrue("域之间应空行分隔,便于在聊天里阅读", merged.contains("\n\n"))
    }

    @Test
    fun `dump of a domain with no window start says so`() {
        // 起点由最早记录推导,所以「有内容却无起点」在实现上不可能出现;
        // 这里守住的是表头对「无记录」的兜底文案 —— 若哪天改成存字段,本用例会红
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "x")
        XDiagnostics.clearAll()
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "y")
        val text = XDiagnostics.dump().getValue(XDomain.STORAGE)
        assertFalse("有记录时表头不该说无记录", text.contains("(无记录)"))
        assertTrue(text.contains("# 记录起点: "))
    }

    // ---- 清空 ----

    @Test
    fun `clearAll empties every domain but keeps the switch`() {
        XDiagnostics.setEnabled(true)
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "a")
        XDiagnostics.record(XDomain.MCP, Level.INFO, "e", "b")
        XDiagnostics.clearAll()

        assertEquals(0, XDiagnostics.totalCount())
        assertTrue("清空不应改变开关状态", XDiagnostics.isEnabled())
    }

    @Test
    fun `clearAll is enough to start a fresh round`() {
        // 「重新开始一轮记录」不需要单独的 API:起点是推导的,清空即回到初始状态
        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "上一轮")
        XDiagnostics.clearAll()
        assertNull(XDiagnostics.windowStartMillis())

        XDiagnostics.record(XDomain.STORAGE, Level.INFO, "e", "新一轮")
        assertEquals(1, XDiagnostics.totalCount())
        assertTrue(XDiagnostics.windowStartMillis() != null)
    }

    // ---- 域清单 ----

    @Test
    fun `domain keys are unique and lowercase`() {
        XDomain.entries.forEach { domain ->
            assertTrue("域 key 应全小写:${domain.key}", domain.key == domain.key.lowercase())
            assertFalse("域 key 不应含空格", domain.key.contains(" "))
        }
        val keys = XDomain.entries.map { it.key }
        assertEquals("域 key 不得重复", keys.size, keys.toSet().size)
    }

    @Test
    fun `domain keys are safe as file names`() {
        // 导出时用 key 当文件名(如 storage.log),含路径分隔符或冒号会写不出文件
        val forbidden = listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|")
        XDomain.entries.forEach { domain ->
            forbidden.forEach { ch ->
                assertFalse("域 key 不得含 $ch:${domain.key}", domain.key.contains(ch))
            }
        }
    }

    @Test
    fun `storage domain exists because P1 is built on it`() {
        assertEquals("storage", XDomain.STORAGE.key)
    }

    // ---- logcat 提示 ----

    @Test
    fun `logcat hint uses the unified tag`() {
        // 命令必须与 TAG 一致:两处各写一份常量,改 tag 时必漏一处,
        // 用户照着敲就一条日志都看不到
        assertEquals("adb logcat -s XCustom:*", XDiagnostics.logcatHint())
        assertTrue(XDiagnostics.logcatHint().contains(XLogRing.TAG))
    }
}
