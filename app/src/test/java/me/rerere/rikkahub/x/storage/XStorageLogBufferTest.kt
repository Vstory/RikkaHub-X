package me.rerere.rikkahub.x.storage

import me.rerere.rikkahub.x.storage.XStorageLogBuffer.Level
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X 存储层日志缓冲单测（P1）。
 *
 * **测的是 [XStorageLogBuffer] 而不是 [XStorageLog]**：后者会调 `android.util.Log`，
 * 而本项目单测未开 `testOptions.unitTests.isReturnDefaultValues`，
 * 在 JVM 里调它**会抛 "not mocked"** —— 那正是把纯逻辑单独拆出来的原因。
 *
 * 守的是「环状缓冲」这类代码最容易写错的几件事 —— **它们都不会编译报错**：
 * ① 超出上限后**丢弃方向搞反** → 缓冲里留着最旧的、把刚发生的事挤掉，
 *    而排查时最需要的恰恰是最新的；
 * ② **导出顺序倒置** → 「先新建后复用」读成「先复用后新建」，因果读反；
 * ③ 清空不彻底 / 上限算错一位。
 *
 * 另有一组**清点事件名**的用例：它同时是「X 存储层有哪些可观测点」的答案，
 * 新增事件若忘了登记，这里会红。
 */
class XStorageLogBufferTest {

    private fun info(event: String, message: String) =
        XStorageLogBuffer.record(Level.INFO, event, message)

    private fun warn(event: String, message: String, error: Throwable? = null) =
        XStorageLogBuffer.record(Level.WARN, event, message, error)

    @After
    fun tearDown() {
        XStorageLogBuffer.clear()
    }

    // ---- 容量与丢弃方向 ----

    @Test
    fun `keeps at most the capacity`() {
        repeat(XStorageLogBuffer.MAX_ENTRIES + 50) { index ->
            info(XStorageEvents.REF_SYNC, "第 $index 条")
        }
        assertEquals(XStorageLogBuffer.MAX_ENTRIES, XStorageLogBuffer.size())
    }

    @Test
    fun `drops the oldest not the newest`() {
        // 丢弃方向反了,缓冲就永远在讲历史,而排查最需要「刚刚发生了什么」
        repeat(XStorageLogBuffer.MAX_ENTRIES) { index ->
            info(XStorageEvents.REF_SYNC, "第 $index 条")
        }
        info(XStorageEvents.WRITE_NEW, "最新一条")

        val all = XStorageLogBuffer.recent()
        assertEquals("最新一条", all.last().message)
        assertFalse(
            "最旧的一条应已被挤掉",
            all.any { it.message == "第 0 条" },
        )
        assertTrue(
            "缓冲里应保留最新的 MAX_ENTRIES 条",
            all.any { it.message == "第 ${XStorageLogBuffer.MAX_ENTRIES - 1} 条" },
        )
    }

    @Test
    fun `fill then clear leaves nothing`() {
        repeat(10) { info(XStorageEvents.REF_SYNC, "x") }
        XStorageLogBuffer.clear()
        assertEquals(0, XStorageLogBuffer.size())
        assertTrue(XStorageLogBuffer.recent().isEmpty())
    }

    // ---- 顺序 ----

    @Test
    fun `recent is ordered oldest to newest`() {
        info(XStorageEvents.WRITE_NEW, "第一次")
        info(XStorageEvents.WRITE_REUSE, "第二次")
        info(XStorageEvents.REF_SYNC, "第三次")

        assertEquals(
            listOf("第一次", "第二次", "第三次"),
            XStorageLogBuffer.recent().map { it.message },
        )
    }

    @Test
    fun `dump preserves chronological order with numbering`() {
        info(XStorageEvents.WRITE_NEW, "新建")
        info(XStorageEvents.WRITE_REUSE, "复用")

        val lines = XStorageLogBuffer.dump().trim().split("\n")
        assertEquals(2, lines.size)
        assertTrue("应带序号且从 1 起", lines[0].startsWith("1. "))
        assertTrue(lines[0].contains(XStorageEvents.WRITE_NEW))
        assertTrue(lines[0].contains("新建"))
        assertTrue("次序不能倒置", lines[1].contains("复用"))
    }

    @Test
    fun `dump of empty buffer says so instead of returning blank`() {
        // 返回空串的话,界面上一片空白,分不清「没日志」与「界面坏了」
        assertEquals(XStorageLogBuffer.EMPTY_DUMP, XStorageLogBuffer.dump())
    }

    @Test
    fun `dump can be filtered to selected events`() {
        info(XStorageEvents.WRITE_NEW, "新建")
        info(XStorageEvents.REF_SKIP, "跳过")
        warn(XStorageEvents.REF_FAIL, "失败")

        val onlyWrite = XStorageLogBuffer.dump(listOf(XStorageEvents.WRITE_NEW))
        assertTrue(onlyWrite.contains("新建"))
        assertFalse(onlyWrite.contains("跳过"))
        assertFalse(onlyWrite.contains("失败"))
    }

    @Test
    fun `dump filter matching nothing still explains itself`() {
        info(XStorageEvents.WRITE_NEW, "新建")
        assertEquals(
            XStorageLogBuffer.EMPTY_DUMP,
            XStorageLogBuffer.dump(listOf(XStorageEvents.GC_PURGE)),
        )
    }

    // ---- 级别标记 ----

    @Test
    fun `warn entries are marked in dump`() {
        warn(XStorageEvents.GC_REFUSE, "拒绝删除仍被引用的资产")
        val text = XStorageLogBuffer.dump()
        assertTrue("注意级应可一眼分辨", text.contains("[注意]"))
        assertTrue(text.contains(XStorageEvents.GC_REFUSE))
    }

    @Test
    fun `info entries carry no warn mark`() {
        info(XStorageEvents.WRITE_NEW, "正常")
        assertFalse(XStorageLogBuffer.dump().contains("[注意]"))
    }

    @Test
    fun `warn records error type and message but not the stack trace`() {
        // 缓冲是给人快速扫的;整段堆栈会占满几十行,把其它事件挤出去
        warn(XStorageEvents.REF_FAIL, "登记失败", IllegalStateException("库没打开"))
        val text = XStorageLogBuffer.dump()
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("库没打开"))
        assertFalse("不应写入堆栈帧", text.contains("\tat "))
    }

    @Test
    fun `warn handles an exception with no message`() {
        // message 为 null 时不写 null 字面量
        warn(XStorageEvents.REF_FAIL, "登记失败", RuntimeException())
        val text = XStorageLogBuffer.dump()
        assertTrue(text.contains("RuntimeException"))
        assertFalse(text.contains("null"))
    }

    @Test
    fun `warn without error omits the separator`() {
        warn(XStorageEvents.GC_REFUSE, "仅有说明")
        val text = XStorageLogBuffer.dump()
        assertTrue(text.contains("仅有说明"))
        assertFalse("无异常时不应出现分隔符后的空档", text.contains(" | "))
    }

    @Test
    fun `recent exposes the level for callers that filter`() {
        info(XStorageEvents.WRITE_NEW, "正常")
        warn(XStorageEvents.GC_REFUSE, "注意")
        val levels = XStorageLogBuffer.recent().map { it.level }
        assertEquals(listOf(Level.INFO, Level.WARN), levels)
    }

    @Test
    fun `entries carry a usable time text`() {
        info(XStorageEvents.WRITE_NEW, "x")
        val text = XStorageLogBuffer.recent().single().timeText()
        // HH:mm:ss.SSS → 12 个字符,含两个冒号与一个点
        assertEquals(12, text.length)
        assertEquals(2, text.count { it == ':' })
        assertEquals(1, text.count { it == '.' })
    }

    // ---- 事件名清单 ----

    @Test
    fun `event names follow the domain-action-result convention`() {
        XStorageEvents.ALL.forEach { event ->
            assertEquals("事件名应为「域.动作.结果」三段:$event", 3, event.split(".").size)
            assertTrue("事件名应全小写:$event", event == event.lowercase())
            assertFalse("事件名不应含空格:$event", event.contains(" "))
        }
    }

    @Test
    fun `event names are unique`() {
        val all = XStorageEvents.ALL
        assertEquals("事件名不得重复", all.size, all.toSet().size)
    }

    @Test
    fun `event list covers write ref backfill and gc stages`() {
        // 四个阶段的可观测点缺一不可:少了哪个阶段,对应的问题就只能靠猜
        val all = XStorageEvents.ALL.toSet()
        listOf(
            XStorageEvents.WRITE_NEW,
            XStorageEvents.WRITE_REUSE,
            XStorageEvents.WRITE_REWRITE,
            XStorageEvents.REF_SYNC,
            XStorageEvents.REF_DROP,
            XStorageEvents.REF_SKIP,
            XStorageEvents.REF_FAIL,
            XStorageEvents.BACKFILL_DONE,
            XStorageEvents.GC_CANDIDATE,
            XStorageEvents.GC_PURGE,
            XStorageEvents.GC_REFUSE,
        ).forEach { assertTrue("事件清单缺少 $it", it in all) }
    }

    @Test
    fun `logcat hint names the shared tag`() {
        assertEquals("adb logcat -s XStorage:*", XStorageLogBuffer.logcatHint())
        assertEquals(XStorageLogBuffer.TAG, "XStorage")
    }
}
