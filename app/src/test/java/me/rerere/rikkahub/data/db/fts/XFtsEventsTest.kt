package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索域事件名的规范自检（与 `XStorageEventsTest` 同一套理由）。
 *
 * 事件名是**排查时的检索键**：用户复制一段诊断发给开发者，靠的就是在文本里定位
 * `fts.index.update` 这样的词。故它必须：
 *
 * - **三段式**（域.动作.结果）—— 便于按前缀/动作过滤；
 * - **全小写、无空格、无正则元字符** —— 否则按名字过滤日志会失配；
 * - **不重复** —— 重复会让两条不同的记录看起来是一件事。
 *
 * 同时这条用例是「检索域有哪些可观测点」的答案：新增事件若忘了登记，这里会红。
 */
class XFtsEventsTest {

    @Test
    fun `names follow the domain-action-result convention`() {
        XFtsEvents.ALL.forEach { event ->
            assertEquals("事件名应为「域.动作.结果」三段:$event", 3, event.split(".").size)
            assertTrue("应以 fts. 开头(检索域统一前缀):$event", event.startsWith("fts."))
            assertTrue("应全小写:$event", event == event.lowercase())
            assertFalse("不应含空格:$event", event.contains(" "))
        }
    }

    @Test
    fun `names are unique`() {
        val all = XFtsEvents.ALL
        assertEquals("事件名不得重复", all.size, all.toSet().size)
    }

    @Test
    fun `names are safe to grep`() {
        // 名字里若出现正则元字符,按名字过滤日志时会失配；点号是分隔符,允许
        val regexMeta = listOf("*", "+", "?", "[", "]", "(", ")", "{", "}", "|", "^", "$", "\\")
        XFtsEvents.ALL.forEach { event ->
            regexMeta.forEach { ch ->
                assertFalse("事件名不得含正则元字符 $ch:$event", event.contains(ch))
            }
        }
    }

    @Test
    fun `index update is registered because it is the increment evidence`() {
        // 这条是「只写了 1 行」在设备上的**唯一取证点** ——
        // 单测只证明了计划算得对,没证明接线连上了。删掉它等于失去增量的现场证据。
        assertTrue(
            "索引维护事件必须登记",
            XFtsEvents.INDEX_UPDATE in XFtsEvents.ALL,
        )
    }
}
