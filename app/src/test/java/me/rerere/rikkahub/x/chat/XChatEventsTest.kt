package me.rerere.rikkahub.x.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话域事件名清单单测。
 *
 * 事件名是**排查时的检索键** —— 用户导出一段诊断发给开发者，
 * 靠的就是在文本里定位 `chat.model.none` 这样的词。
 * 故名字一旦散落成字面量，就会出现同一件事多种写法并存，日志再也搜不干净。
 *
 * 本用例同时是「会话域有哪些可观测点」的答案：新增事件若忘了登记，这里会失败。
 */
class XChatEventsTest {

    @Test
    fun `names follow the domain-action-result convention`() {
        XChatEvents.ALL.forEach { event ->
            assertEquals("事件名应为「域.动作.结果」三段：$event", 3, event.split(".").size)
            assertTrue("应以 chat. 开头（会话域统一前缀）：$event", event.startsWith("chat."))
            assertTrue("应全小写：$event", event == event.lowercase())
            assertFalse("不应含空格：$event", event.contains(" "))
        }
    }

    @Test
    fun `names are unique`() {
        val all = XChatEvents.ALL
        assertEquals("事件名不得重复", all.size, all.toSet().size)
    }

    @Test
    fun `names are safe to grep`() {
        // 名字里若出现正则元字符，按名字过滤日志时会失配
        val regexMeta = listOf("*", "+", "?", "[", "]", "(", ")", "{", "}", "|", "^", "$", "\\")
        XChatEvents.ALL.forEach { event ->
            regexMeta.forEach { meta ->
                assertFalse("事件名不得含正则元字符 $meta：$event", event.contains(meta))
            }
        }
    }

    @Test
    fun `all declared events are registered in ALL`() {
        // 常量与清单必须一一对应：只在对象里加常量、忘了放进 ALL，
        // 上面三条规则都不会覆盖它（它们只遍历 ALL）。
        val all = XChatEvents.ALL.toSet()
        listOf(
            XChatEvents.MODEL_PICK_KEPT,
            XChatEvents.MODEL_PICK_RESTORED,
            XChatEvents.MODEL_NONE,
        ).forEach { event ->
            assertTrue("事件清单缺少 $event", event in all)
        }
        assertEquals("新增事件后请同步更新本清单", 3, XChatEvents.ALL.size)
    }

    @Test
    fun `the no-model failure is observable`() {
        // 「没有可用模型」以前只变成一句 "Message send failed"，看不出原因；
        // 而消息此时已落库，用户看到的是「发出去了却没有回复」。
        // 这条事件名就是那个故障的检索键，不能丢。
        assertTrue(
            "缺少「无可用模型」事件 —— 该故障将重新变成只能靠猜",
            XChatEvents.MODEL_NONE in XChatEvents.ALL,
        )
    }
}
