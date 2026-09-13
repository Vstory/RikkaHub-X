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
 * ## 守的第一条:**请求体绝不落盘,只记字节数**(2026-09-13)
 *
 * 这条是**反向**的断言 —— 从「还原成原文」变成「原文一个字都不许出现」。
 * 写法上要刻意防住最容易犯的那类退化:有人为了「顺手」把正文加回来,
 * 编译过、其余用例照绿。故这里既断言键不存在,**也断言正文内容不出现在整行里**。
 *
 * ## 守的第二条:「一条记录恰好占一行」
 *
 * 这条最要紧,因为**它一定会被破坏**:响应正文是 JSON、内含换行。
 * 一旦没转义,一条记录就散成几十行,「按行号定位」与「单行筛取」同时失效 ——
 * 而且不会编译报错,只在使用时才看得出来。故这里钉住。
 *
 * ## 守的第三条:形状与语义事件**对齐**
 *
 * 合并进同一条时间线之后,`domain`/`event`/`lvl` 三个核心字段必须都在 ——
 * 少一个,读的人就得逐行猜「这行是哪一类的」。
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

    // ────────────────────────────────────
    // 请求体:只记字节数(2026-09-13 用户决定)
    // ────────────────────────────────────

    @Test
    fun `request body is never written, only its byte size`() {
        // 真实形态:聊天请求的正文是 system prompt + 聊天历史,可达数百 KB
        val body = """{"messages":[{"role":"user","content":"这段话不该出现在包里"}]}"""
        val line = XNetLine.format(entry(requestBody = body), at = 0L)
        val json = parse(line)

        assertFalse("请求体键不得存在", json.containsKey("reqBody"))
        assertTrue("应记字节数", json.containsKey("reqBytes"))
        // 只断言「键不存在」还不够 —— 有人可能把它塞进别的字段名里。故直接查正文。
        assertFalse(
            "正文内容一个字都不许出现在行里",
            line.contains("这段话不该出现在包里"),
        )
    }

    @Test
    fun `byte size counts utf8 bytes, not characters`() {
        // ⚠️ 这条防的是最容易写错的那处:`String.length` 数的是**字符**,而传输量以字节计。
        //    中文一字三字节 → 写成 length 会低报三分之二,而肉眼看不出来。
        val line = XNetLine.format(entry(requestBody = "中文"), at = 0L)
        assertEquals("两个汉字 = 6 字节", "6", text(line, "reqBytes"))
    }

    @Test
    fun `absent request body omits the key`() {
        // GET 类请求没有正文;写成 `"reqBytes":0` 会让「有没有正文」这个判断落空
        val json = parse(XNetLine.format(entry(method = "GET"), at = 0L))
        assertFalse(json.containsKey("reqBytes"))
        assertFalse("没有头时不该出现空对象", json.containsKey("reqHeaders"))
    }

    // ────────────────────────────────────
    // 单行约束(改由响应正文承载 —— 它是唯一还带正文的字段)
    // ────────────────────────────────────

    @Test
    fun `response body with newlines still occupies exactly one line`() {
        val body = """
            {
              "error": {
                "message": "第一行\n第二行"
              }
            }
        """.trimIndent()
        val line = XNetLine.format(entry(responseCode = 401, responseBody = body), at = 0L)

        assertFalse("换行必须被转义,否则一条记录会占多行", line.contains('\n'))
        assertFalse("回车同样要转义", line.contains('\r'))
        assertEquals("解析回来应还原成原文", body, text(line, "respBody"))
    }

    @Test
    fun `response body survives quotes and backslashes`() {
        // 正文本身含 JSON,故双引号与反斜杠遍地都是 —— 转义出错在这里最先暴露
        val body = """{"a":"含 \"引号\"","b":"含 \\反斜杠","c":{"d":"\n真换行"}}"""
        val line = XNetLine.format(entry(responseCode = 500, responseBody = body), at = 0L)
        assertFalse("反斜杠不得破坏单行约束", line.contains('\n'))
        assertEquals(body, text(line, "respBody"))
    }

    // ────────────────────────────────────
    // 形状与语义事件对齐(2026-09-13:合并进同一条时间线)
    // ────────────────────────────────────

    @Test
    fun `core fields match the semantic event shape`() {
        val ok = parse(XNetLine.format(entry(responseCode = 200), at = 0L))
        assertEquals("net", ok.getValue("domain").jsonPrimitive.content)
        assertEquals("net.request.sent", ok.getValue("event").jsonPrimitive.content)
        assertEquals("I", ok.getValue("lvl").jsonPrimitive.content)
        assertTrue("行里必须有 at", ok.containsKey("at"))
    }

    @Test
    fun `failed request is a different event and level`() {
        // 出错那次没有响应码与耗时 —— 若写成 "code":null,读者会分不清「没记录」与「值为空」
        val line = XNetLine.format(entry(error = "Connection reset"), at = 0L)
        val json = parse(line)

        assertEquals("Connection reset", text(line, "error"))
        assertFalse("没有响应码时不该出现 code 键", json.containsKey("code"))
        assertFalse("没有耗时时不该出现 durationMs 键", json.containsKey("durationMs"))
        // 单列事件名而不是只靠 error 字段:一条 grep 就能捞全部失败,
        // 比 grep '"error"' 稳(后者会被正文里恰好含 error 字样的行污染)
        assertEquals("net.request.failed", json.getValue("event").jsonPrimitive.content)
        assertEquals("W", json.getValue("lvl").jsonPrimitive.content)
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
        // 请求头里的凭据**落盘保留原文**(现场不被破坏),导出时由 XLogScrub 掩掉 ——
        // 与其它内容同一条口径,故这里断言它确实在。
        assertEquals("Bearer x", req.getValue("Authorization").jsonPrimitive.content)
        assertEquals("nginx", parse(line).getValue("respHeaders").jsonObject.getValue("Server").jsonPrimitive.content)
    }

    // ────────────────────────────────────
    // 非 2xx 的响应正文
    //
    // 它存在的唯一理由:状态码只说「被拒了」,**原因写在正文里**。
    // ────────────────────────────────────

    @Test
    fun `error response body is carried and stays on one line`() {
        val body = """
            {
              "error": {
                "message": "Authentication Fails",
                "type": "authentication_error"
              }
            }
        """.trimIndent()
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
