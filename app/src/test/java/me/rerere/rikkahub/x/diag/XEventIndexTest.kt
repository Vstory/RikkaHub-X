package me.rerere.rikkahub.x.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关键词索引的自检。
 *
 * ## 它守的是用户那句「搜关键词就能定位」
 *
 * 索引是读者**按图索骥的唯一指引**:照着它搜却搜不到,比没有索引更坏 ——
 * 那会让人怀疑日志本身漏了。故三件事必须成立:
 *
 * ① **完整**:包里出现过的名字一个不漏(它从数据里数,这条天然成立 —— 但要钉住,
 *    因为它正是「维护一份清单」那种做法会失败的地方);
 * ② **不撒谎**:没出现过的名字不许出现;
 * ③ **截断可见**:超过上限时必须写明还差多少,而不是让读者以为「就这些」。
 */
class XEventIndexTest {

    private fun line(domain: String, event: String, msg: String = "x") =
        """{"at":"00:00:00.000","lvl":"I","domain":"$domain","event":"$event","msg":"$msg"}"""

    @Test
    fun `counts each event and groups by domain`() {
        val index = XEventIndex.index(
            listOf(
                line("chat", "chat.message.sent"),
                line("chat", "chat.message.sent"),
                line("storage", "asset.write.new"),
            )
        )

        assertEquals(setOf("chat", "storage"), index.keys)
        assertEquals(2L, index.getValue("chat").getValue("chat.message.sent"))
        assertEquals(1L, index.getValue("storage").getValue("asset.write.new"))
    }

    @Test
    fun `grouping follows the domain field, not the event name prefix`() {
        // ⚠️ 这条是**真实存在的坑**:存储域的事件名一律以 `asset.` 开头,而域键是
        //    `storage`。若按名字前缀猜分组,这份索引会把存储的事件报到 `asset` 域下 ——
        //    读者按 `domain=storage` 找就一条都找不到。
        val index = XEventIndex.index(listOf(line("storage", "asset.write.new")))

        assertEquals(setOf("storage"), index.keys)
        assertFalse("不该按名字前缀猜出一个 asset 域", index.containsKey("asset"))
    }

    @Test
    fun `event names inside a domain are sorted so two bundles line up`() {
        // 排序让两份导出可以逐行对照。若按出现顺序,同一份日志的两次导出行序会不同,
        // 人工 diff 时全是噪声。
        val index = XEventIndex.index(
            listOf(
                line("chat", "chat.zzz.last"),
                line("chat", "chat.aaa.first"),
            )
        )

        assertEquals(listOf("chat.aaa.first", "chat.zzz.last"), index.getValue("chat").keys.toList())
    }

    @Test
    fun `malformed lines are skipped instead of breaking the index`() {
        // 日志可能被外部工具截断/拼接(发给 AI 的那条路上最常见)。
        // 为一个索引把整个导出弄崩不值得,故畸形行一律跳过。
        val index = XEventIndex.index(
            listOf(
                "这不是 JSON",
                """{"at":"00:00:00.000","lvl":"I","domain":"chat"}""",          // 缺 event
                """{"at":"00:00:00.000","lvl":"I","event":"chat.a.b"}""",      // 缺 domain
                """{"at":"00:00:00.000","lvl":"I","domain":"","event":"a.b.c"}""", // 空值
                "logcat 的普通文本行 01-01 00:00:00.000  1234  1234 I XCustom: 随便什么",
                line("chat", "chat.message.sent"),
            )
        )

        assertEquals(1, index.size)
        assertEquals(1, index.getValue("chat").size)
    }

    @Test
    fun `a counter accumulates across files without re-reading them`() {
        // 打包时是**边读边算**(不把文件读成 List) —— 这条钉住那个用法。
        val counter = XEventIndex.Counter()
        counter.add(line("chat", "chat.message.sent"))
        counter.add(line("core", "diag.crash.detected"))
        counter.add(line("chat", "chat.message.sent"))

        val result = counter.result()
        assertEquals(2L, result.getValue("chat").getValue("chat.message.sent"))
        assertEquals(1L, result.getValue("core").size)
    }

    @Test
    fun `an empty index renders as nothing at all`() {
        // 空索引必须整段不写:写一个空标题会让读者以为索引坏了,而不是「这里没有事件」。
        assertNull(XEventIndex.render(emptyMap()))
        assertNull(XEventIndex.render(XEventIndex.index(listOf("无关内容"))))
    }

    @Test
    fun `the rendered index names every event it counted`() {
        val rendered = XEventIndex.render(
            XEventIndex.index(
                listOf(
                    line("chat", "chat.message.sent"),
                    line("storage", "asset.write.new"),
                )
            )
        )!!

        assertTrue("应给出域名", rendered.contains("domain=chat"))
        assertTrue(rendered.contains("domain=storage"))
        assertTrue("应给出事件名(那是被搜的东西)", rendered.contains("chat.message.sent"))
        assertTrue(rendered.contains("asset.write.new"))
        assertTrue("应说明这份索引不会过期", rendered.contains("cannot be stale"))
    }

    @Test
    fun `truncation is stated, never silent`() {
        // ⚠️ 超过上限**必须写明还差多少**。静默截断会让读者在「搜不到」时怀疑日志本身,
        //    而不是怀疑这份索引被裁过 —— 而「静默失败比崩溃更贵」正是本项目的既有铁律。
        val many = (1..XEventIndex.MAX_ENTRIES + 7).map { line("chat", "chat.e$it") }
        val rendered = XEventIndex.render(XEventIndex.index(many))!!

        assertTrue("必须写明还差多少条", rendered.contains("(+7 more event name(s) not listed)"))
        // ⚠️ 排序是**字典序**,故末尾那几条才是被裁掉的(不是 e101~e107 ——
        //    字典序里 "e100" 排在 "e11" 之前)。这条同时钉住「排序」这个行为。
        assertFalse("被裁掉的名字不该出现", rendered.contains("chat.e99"))
        assertTrue("排序靠前的名字应在(否则就是裁错了方向)", rendered.contains("chat.e100"))
    }
}
