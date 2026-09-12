package me.rerere.rikkahub.x.diag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘行格式的自检。
 *
 * ## 守的是「一条记录恰好占一行」这条硬约束
 *
 * 破了它,「按行号定位」与「grep 一条」都失效 —— 而消息里带换行是常态(堆栈、JSON 正文)。
 * 这条不会编译报错,只在有人改格式时才悄悄破掉,所以要钉住。
 *
 * ## 为什么要把行**解析回来**验,而不是比字符串
 *
 * 比字符串只能证明「现在是这样」;解析回来能同时证明**它是合法 JSON 且字段都在**。
 */
class XDiagLineTest {

    private fun parse(line: String) = Json.parseToJsonElement(line).jsonObject

    private fun field(line: String, key: String) = parse(line).getValue(key).jsonPrimitive.content

    @Test
    fun `message containing newlines still occupies exactly one line`() {
        val message = "第一行\n第二行\r\n第三行"
        val line = XDiagLine.format(XLogRing.Level.INFO, XDomain.CHAT, "chat.x.y", message, at = 0L)

        assertFalse("换行必须被转义,否则一条记录会占多行", line.contains('\n'))
        assertFalse("回车同样要转义", line.contains('\r'))
        assertEquals("解析回来应还原成原文", message, field(line, "msg"))
    }

    @Test
    fun `line carries exactly the expected fields`() {
        val line = XDiagLine.format(XLogRing.Level.INFO, XDomain.STORAGE, "asset.write.new", "m", at = 0L)
        assertEquals(setOf("at", "lvl", "domain", "event", "msg"), parse(line).keys)
    }

    @Test
    fun `domain is carried on the line because the event name does not imply it`() {
        // 存储域的事件名一律以 `asset.` 开头,而域键是 `storage` —— 从文件里复制一行出去
        // (贴进聊天是最常见的用法)就靠这个字段自明。
        val line = XDiagLine.format(XLogRing.Level.INFO, XDomain.STORAGE, "asset.write.new", "m", at = 0L)
        assertEquals("storage", field(line, "domain"))
        assertEquals("asset.write.new", field(line, "event"))
    }

    @Test
    fun `warn level is marked with a single letter`() {
        fun lvl(level: XLogRing.Level) =
            field(XDiagLine.format(level, XDomain.CORE, "a.b.c", "m", at = 0L), "lvl")

        assertEquals("I", lvl(XLogRing.Level.INFO))
        assertEquals("W", lvl(XLogRing.Level.WARN))
        assertEquals(
            "两种级别必须能被区分,否则落盘物里看不出哪条是问题",
            false,
            lvl(XLogRing.Level.INFO) == lvl(XLogRing.Level.WARN),
        )
    }

    @Test
    fun `time is formatted as hh mm ss millis`() {
        // 时区无关的形状检查:具体值随设备时区变,不该断言字面量
        val at = XDiagLine.format(XLogRing.Level.INFO, XDomain.CORE, "a.b.c", "m", at = 1_700_000_000_000L)
        assertTrue(
            "时间应是 HH:mm:ss.SSS,实得 ${field(at, "at")}",
            Regex("""^\d{2}:\d{2}:\d{2}\.\d{3}$""").matches(field(at, "at")),
        )
    }

    @Test
    fun `special characters in the message survive a round trip`() {
        // 引号、反斜杠、制表符是 JSON 转义最容易出错的地方,而日志正文里都有可能出现
        val message = """含 "双引号" 与 \反斜杠 与	制表符 与 {"json":"片段"}"""
        val line = XDiagLine.format(XLogRing.Level.INFO, XDomain.CORE, "a.b.c", message, at = 0L)
        assertFalse("反斜杠不得破坏单行约束", line.contains('\n'))
        assertEquals(message, field(line, "msg"))
    }
}
