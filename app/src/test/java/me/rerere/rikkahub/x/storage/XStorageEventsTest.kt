package me.rerere.rikkahub.x.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存储域事件名清单单测。
 *
 * 事件名是**排查时的检索键** —— 用户导出一段诊断发给开发者，
 * 靠的就是在文本里定位 `asset.write.new` 这样的词。
 * 故名字一旦散落成字面量，就会出现同一件事三种写法并存，日志再也搜不干净。
 *
 * 本用例同时是「存储域有哪些可观测点」的答案：新增事件若忘了登记，这里会红。
 */
class XStorageEventsTest {

    @Test
    fun `names follow the domain-action-result convention`() {
        XStorageEvents.ALL.forEach { event ->
            assertEquals("事件名应为「域.动作.结果」三段:$event", 3, event.split(".").size)
            assertTrue("应以 asset. 开头(存储域统一前缀):$event", event.startsWith("asset."))
            assertTrue("应全小写:$event", event == event.lowercase())
            assertFalse("不应含空格:$event", event.contains(" "))
        }
    }

    @Test
    fun `names are unique`() {
        val all = XStorageEvents.ALL
        assertEquals("事件名不得重复", all.size, all.toSet().size)
    }

    @Test
    fun `names are safe to grep`() {
        // 名字里若出现正则元字符,按名字过滤日志时会失配
        val regexMeta = listOf(".", "*", "+", "?", "[", "]", "(", ")", "{", "}", "|", "^", "$", "\\")
        XStorageEvents.ALL.forEach { event ->
            // 点号是分隔符,允许;其余元字符不允许
            regexMeta.filter { it != "." }.forEach { ch ->
                assertFalse("事件名不得含正则元字符 $ch:$event", event.contains(ch))
            }
        }
    }

    @Test
    fun `covers all four observable stages`() {
        // 缺了哪个阶段,对应的问题就只能靠猜:
        // 写入(去重有没有生效)/ 引用(账本记没记对)/ 回填(老文件进没进表)/ 回收(删对没删对)
        val all = XStorageEvents.ALL.toSet()
        listOf(
            XStorageEvents.WRITE_NEW,
            XStorageEvents.WRITE_REUSE,
            XStorageEvents.WRITE_REWRITE,
            XStorageEvents.REF_SYNC,
            XStorageEvents.REF_DROP,
            XStorageEvents.REF_SKIP,
            XStorageEvents.REF_FAIL,
            XStorageEvents.BACKFILL_SCAN,
            XStorageEvents.BACKFILL_DONE,
            XStorageEvents.GC_CANDIDATE,
            XStorageEvents.GC_PURGE,
            XStorageEvents.GC_REFUSE,
        ).forEach { event ->
            assertTrue("事件清单缺少 $event", event in all)
        }
    }

    @Test
    fun `failure paths are represented`() {
        // 降级是静默的;没有专门的事件名,用户永远不知道账本曾经没记上
        val all = XStorageEvents.ALL.toSet()
        assertTrue("缺少写入降级事件", XStorageEvents.WRITE_FALLBACK in all)
        assertTrue("缺少引用登记失败事件", XStorageEvents.REF_FAIL in all)
        assertTrue("缺少落盘成功但登记失败的事件", XStorageEvents.WRITE_UNRECORDED in all)
    }

    @Test
    fun `unrecorded is distinct from fallback because they are handled differently`() {
        // 两者必须分开:写入失败要回落到旧路径(否则用户存不下文件);
        // 登记失败不该回落(内容已在内容寻址路径上,再写一份会让同一内容有两个文件)。
        // 若哪天有人把它们合并成一个事件,这条用例会红,提醒同步改处置逻辑。
        assertTrue(XStorageEvents.WRITE_UNRECORDED != XStorageEvents.WRITE_FALLBACK)
    }
}
