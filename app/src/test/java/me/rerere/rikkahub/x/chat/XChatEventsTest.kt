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
        // ⚠️ 这条用例**原先没有守住它名字声称的东西**（2026-09-13 修）。
        //
        // 它当时写的是「列出三个常量名 + 断言 ALL.size == 3」。于是：
        //  · 往对象里加新常量 → 它只因为「数量不是 3」而红，**查不出新常量有没有登记**；
        //  · 而名字声称的正是「所有已声明事件都登记在 ALL」—— 名不副实。
        // 这就是「检查写了 ≠ 检查的东西等于要保证的东西」：数量对了、清单仍旧可能漏项。
        // 当时的修法是「把 3 改成 8」，那等于把同样的坑留到下一次。
        //
        // 故改为**反射取全部声明**，与 ALL 两个方向逐一对上：
        //  ① 声明了却没登记 → 该事件不进任何按 ALL 生成的导出/检索，等于白记；
        //  ② 登记了却找不到对应常量 → 名字拼错，按名字检索照样失配。
        // 上面三条命名规则只遍历 ALL，所以这 ① 正是它们覆盖不到的缺口。
        val declared = XChatEvents::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { field ->
                field.isAccessible = true
                field.get(XChatEvents) as String
            }
            .sorted()
        val registered = XChatEvents.ALL.sorted()

        val missing = declared.filter { it !in registered }
        val stale = registered.filter { it !in declared }

        assertEquals("这些事件已声明但未登记进 ALL（不会出现在任何按域导出里）：$missing", emptyList<String>(), missing)
        assertEquals("ALL 里这些名字找不到对应常量（拼错了？）：$stale", emptyList<String>(), stale)
        assertEquals("已声明常量与 ALL 应一一对应", declared, registered)
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
