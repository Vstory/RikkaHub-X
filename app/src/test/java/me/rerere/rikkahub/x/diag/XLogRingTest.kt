package me.rerere.rikkahub.x.diag

import me.rerere.rikkahub.x.diag.XLogRing.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环状缓冲单测（诊断框架 D1）。
 *
 * 守的是这类代码最容易写错、且**都不会编译报错**的几件事：
 * ① 超出上限后**丢弃方向搞反** → 缓冲里留着最旧的、把刚发生的事挤掉，
 *    而排查时最需要的恰恰是最新的；
 * ② **导出顺序倒置** → 「先新建后复用」读成「先复用后新建」，因果读反；
 * ③ 容量算错一位 / 清空不彻底。
 */
class XLogRingTest {

    private fun ring(capacity: Int = 10) = XLogRing(capacity)

    // ---- 容量与丢弃方向 ----

    @Test
    fun `keeps at most the capacity`() {
        val r = ring(10)
        repeat(50) { r.record(Level.INFO, "e", "第 $it 条") }
        assertEquals(10, r.size())
    }

    @Test
    fun `drops the oldest not the newest`() {
        val r = ring(5)
        repeat(5) { r.record(Level.INFO, "e", "第 $it 条") }
        r.record(Level.INFO, "e", "最新一条")

        val all = r.recent()
        assertEquals("最新一条", all.last().message)
        assertFalse("最旧的一条应已被挤掉", all.any { it.message == "第 0 条" })
        assertTrue("应保留第 4 条", all.any { it.message == "第 4 条" })
    }

    @Test
    fun `exactly at capacity loses nothing`() {
        val r = ring(3)
        repeat(3) { r.record(Level.INFO, "e", "第 $it 条") }
        assertEquals(3, r.size())
        assertEquals(
            listOf("第 0 条", "第 1 条", "第 2 条"),
            r.recent().map { it.message },
        )
    }

    @Test
    fun `clear empties the ring`() {
        val r = ring(5)
        repeat(5) { r.record(Level.INFO, "e", "x") }
        r.clear()
        assertEquals(0, r.size())
        assertTrue(r.isEmpty())
        assertTrue(r.recent().isEmpty())
    }

    @Test
    fun `empty ring is reported empty`() {
        assertTrue(ring().isEmpty())
        assertFalse(ring(5).also { it.record(Level.INFO, "e", "x") }.isEmpty())
    }

    // ---- 顺序 ----

    @Test
    fun `recent is ordered oldest to newest`() {
        val r = ring()
        r.record(Level.INFO, "a", "第一次")
        r.record(Level.INFO, "b", "第二次")
        r.record(Level.INFO, "c", "第三次")
        assertEquals(listOf("第一次", "第二次", "第三次"), r.recent().map { it.message })
    }

    @Test
    fun `format numbers from one in chronological order`() {
        val r = ring()
        r.record(Level.INFO, "asset.write.new", "新建")
        r.record(Level.INFO, "asset.write.reuse", "复用")

        val lines = r.format().trim().split("\n")
        assertEquals(2, lines.size)
        assertTrue("应带序号且从 1 起", lines[0].startsWith("1. "))
        assertTrue(lines[0].contains("asset.write.new"))
        assertTrue(lines[0].contains("新建"))
        assertTrue("次序不能倒置", lines[1].contains("复用"))
    }

    @Test
    fun `format of an empty ring yields empty text`() {
        // 空缓冲的文案由调用方(XDiagnostics)决定;ring 自身只负责逐行输出
        assertEquals("", ring().format())
    }

    // ---- 级别 ----

    @Test
    fun `warn entries are marked`() {
        val r = ring()
        r.record(Level.WARN, "asset.gc.refuse", "拒绝删除")
        assertTrue(r.format().contains("[注意]"))
        assertTrue(r.format().contains("asset.gc.refuse"))
    }

    @Test
    fun `info entries carry no warn mark`() {
        val r = ring()
        r.record(Level.INFO, "asset.write.new", "正常")
        assertFalse(r.format().contains("[注意]"))
    }

    @Test
    fun `level is exposed on entries`() {
        val r = ring()
        r.record(Level.INFO, "a", "x")
        r.record(Level.WARN, "b", "y")
        assertEquals(listOf(Level.INFO, Level.WARN), r.recent().map { it.level })
    }

    // ---- 异常摘要 ----

    @Test
    fun `error type and message are appended without a stack trace`() {
        // 缓冲是给人快速扫的;整段堆栈会占满几十行并把其它事件挤出去
        val r = ring()
        r.record(Level.WARN, "asset.ref.fail", "登记失败", IllegalStateException("库没打开"))
        val text = r.format()
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("库没打开"))
        assertFalse("不应写入堆栈帧", text.contains("\tat "))
    }

    @Test
    fun `error without message does not print null`() {
        val r = ring()
        r.record(Level.WARN, "asset.ref.fail", "登记失败", RuntimeException())
        val text = r.format()
        assertTrue(text.contains("RuntimeException"))
        assertFalse(text.contains("null"))
    }

    @Test
    fun `no error means no separator`() {
        val r = ring()
        r.record(Level.WARN, "asset.gc.refuse", "仅有说明")
        assertFalse(r.format().contains(" | "))
    }

    // ---- 时间 ----

    @Test
    fun `time text is a usable clock reading`() {
        val r = ring()
        r.record(Level.INFO, "e", "x")
        val text = r.recent().single().timeText()
        assertEquals("HH:mm:ss.SSS 共 12 个字符", 12, text.length)
        assertEquals(2, text.count { it == ':' })
        assertEquals(1, text.count { it == '.' })
    }

    @Test
    fun `companion exposes the same time format for headers`() {
        // 表头与正文的时间格式必须一致,否则同一份导出里出现两种写法
        val r = ring()
        r.record(Level.INFO, "e", "x")
        val at = r.recent().single().at
        assertEquals(XLogRing.timeText(at), r.recent().single().timeText())
        assertEquals(12, XLogRing.timeText(at).length)
    }

    // ---- 脱敏钩子 ----

    @Test
    fun `format applies the supplied redaction to messages only`() {
        val r = ring()
        r.record(Level.INFO, "asset.write.new", "/root/secret/path.png")
        val out = r.format(redact = { it.replace("/root/", "<files>/") })
        assertTrue(out.contains("<files>/secret/path.png"))
        assertFalse(out.contains("/root/secret"))
    }

    @Test
    fun `format leaves the event name untouched by redaction`() {
        // 事件名是检索键,不能被脱敏规则改掉,否则按事件名搜不到
        val r = ring()
        r.record(Level.INFO, "asset.write.new", "x")
        val out = r.format(redact = { "SHOULD_NOT_APPEAR" })
        assertTrue(out.contains("asset.write.new"))
        assertFalse(out.contains("SHOULD_NOT_APPEAR"))
    }

    @Test
    fun `tag is shared across the whole X custom`() {
        assertEquals("XStorage", XLogRing.TAG)
    }
}
