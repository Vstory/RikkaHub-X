package me.rerere.rikkahub.x.diag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.android.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求记录行格式的自检。
 *
 * ## 守的是「一条记录恰好占一行」
 *
 * 这条最要紧,因为**它一定会被破坏**:请求正文是 JSON、内含换行,长对话的正文还有几百 KB。
 * 一旦没转义,一条记录就散成几十行,「按行号定位」与「单行筛取」同时失效 ——
 * 而且不会编译报错,只在使用时才看得出来。故这里钉住。
 *
 * ## 也守「字段名是稳定的」
 *
 * 这份日志的用法就是 `grep '"reqBody"'` 之类,字段名漂了等于工具全废。
 */
class XNetLineTest {

    private fun parse(line: String) = Json.parseToJsonElement(line).jsonObject

    private fun text(line: String, key: String) = parse(line).getValue(key).jsonPrimitive.content

    private fun entry(
        method: String = "POST",
        url: String = "https://api.example.com/v1/chat/completions",
        requestHeaders: Map<String, String> = emptyMap(),
        requestBody: String? = null,
        responseCode: Int? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        durationMs: Long? = null,
        error: String? = null,
        responseBody: String? = null,
        responseBodyTruncated: Boolean = false,
    ) = LogEntry.RequestLog(
        tag = "HTTP",
        url = url,
        method = method,
        requestHeaders = requestHeaders,
        requestBody = requestBody,
        responseCode = responseCode,
        responseHeaders = responseHeaders,
        durationMs = durationMs,
        error = error,
        responseBody = responseBody,
        responseBodyTruncated = responseBodyTruncated,
    )

    @Test
    fun `request body with newlines still occupies exactly one line`() {
        // 真实形态:聊天请求的正文就是一段带缩进的 JSON
        val body = """
            {
              "messages": [
                {"role": "user", "content": "第一行\n第二行"}
              ]
            }
        """.trimIndent()
        val line = XNetLine.format(entry(requestBody = body), at = 0L)

        assertFalse("换行必须被转义,否则一条记录会占多行", line.contains('\n'))
        assertFalse("回车同样要转义", line.contains('\r'))
        assertEquals("解析回来应还原成原文", body, text(line, "reqBody"))
    }

    @Test
    fun `request body survives quotes and backslashes`() {
        // 正文本身含 JSON,故双引号与反斜杠遍地都是 —— 转义出错在这里最先暴露
        val body = """{"a":"含 \"引号\"","b":"含 \\反斜杠","c":{"d":"\n真换行"}}"""
        val line = XNetLine.format(entry(requestBody = body), at = 0L)
        assertFalse("反斜杠不得破坏单行约束", line.contains('\n'))
        assertEquals(body, text(line, "reqBody"))
    }

    @Test
    fun `identifying fields are present and stable`() {
        val line = XNetLine.format(
            entry(requestBody = "x", responseCode = 200, durationMs = 1234),
            at = 0L,
        )
        assertEquals("POST", text(line, "method"))
        assertEquals("https://api.example.com/v1/chat/completions", text(line, "url"))
        assertEquals("200", text(line, "code"))
        assertEquals("1234", text(line, "durationMs"))
        // 字段名变了等于依赖它们的 grep 全废,故逐个钉住
        assertTrue("行里必须有 at", parse(line).containsKey("at"))
    }

    @Test
    fun `headers are carried as objects`() {
        val line = XNetLine.format(
            entry(
                requestHeaders = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer x"),
                responseHeaders = mapOf("Server" to "nginx"),
            ),
            at = 0L,
        )
        val req = parse(line).getValue("reqHeaders").jsonObject
        assertEquals("application/json", req.getValue("Content-Type").jsonPrimitive.content)
        assertEquals("Bearer x", req.getValue("Authorization").jsonPrimitive.content)
        assertEquals("nginx", parse(line).getValue("respHeaders").jsonObject.getValue("Server").jsonPrimitive.content)
    }

    @Test
    fun `failed request carries error and omits absent fields`() {
        // 出错那次没有响应码与耗时 —— 若写成 "code":null,读者会分不清「没记录」与「值为空」
        val line = XNetLine.format(entry(error = "Connection reset"), at = 0L)
        val json = parse(line)
        assertEquals("Connection reset", text(line, "error"))
        assertFalse("没有响应码时不该出现 code 键", json.containsKey("code"))
        assertFalse("没有耗时时不该出现 durationMs 键", json.containsKey("durationMs"))
    }

    @Test
    fun `absent request body omits the key`() {
        // GET 类请求没有正文;写成 "reqBody":null 会让 grep 命中一堆空值
        val json = parse(XNetLine.format(entry(method = "GET"), at = 0L))
        assertFalse(json.containsKey("reqBody"))
        assertFalse("没有头时不该出现空对象", json.containsKey("reqHeaders"))
    }

    // ────────────────────────────────────
    // 非 2xx 的响应正文(2026-09-13 加)
    //
    // 它存在的唯一理由:状态码只说「被拒了」,**原因写在正文里** ——
    // 而实测那次 401 的原因在哪都查不到(Firebase 没接 Perf、Crashlytics 只收崩溃、
    // 非致命上报 0 处),只有这里能留下。
    // ────────────────────────────────────

    @Test
    fun `error response body is carried and stays on one line`() {
        // 错误正文通常是 JSON,且**带换行**。单行约束在这里同样必须成立 ——
        // 否则最先崩的恰好是出错那几行,而那是最需要看清的地方。
        val body = """{
  "error": {
    "message": "Authentication Fails",
    "type": "authentication_error"
  }
}"""
        val line = XNetLine.format(entry(responseCode = 401, responseBody = body), at = 0L)
        assertFalse("响应正文里的换行必须被转义", line.contains('\n'))
        assertEquals("解析回来应还原成原文", body, text(line, "respBody"))
        assertEquals("401", text(line, "code"))
    }

    @Test
    fun `successful response omits the response body key`() {
        // 2xx 一律不带正文:chat 的 2xx 是 SSE 流,取它会打断流式(见拦截器注释)。
        // 写成 "respBody":null 会让 grep 命中一堆空值,故**整键不出现**。
        val json = parse(XNetLine.format(entry(responseCode = 200), at = 0L))
        assertFalse("2xx 不该出现 respBody 键", json.containsKey("respBody"))
        assertFalse("没截断时不该出现 respBodyTruncated 键", json.containsKey("respBodyTruncated"))
    }

    @Test
    fun `truncation flag is carried only when true`() {
        // 被截断必须**可见** —— 否则读的人会以为自己看到了完整的错误原因,
        // 于是在一个残缺的正文上做判断。
        val truncated = parse(
            XNetLine.format(
                entry(responseCode = 500, responseBody = "x", responseBodyTruncated = true),
                at = 0L,
            )
        )
        assertEquals("true", truncated.getValue("respBodyTruncated").jsonPrimitive.content)

        // 反向:为假时不写键(与上一条用例配对,「写」与「不写」两边都钉住)
        val intact = parse(
            XNetLine.format(entry(responseCode = 500, responseBody = "x"), at = 0L)
        )
        assertFalse(intact.containsKey("respBodyTruncated"))
    }
}
