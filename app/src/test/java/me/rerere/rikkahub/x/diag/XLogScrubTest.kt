package me.rerere.rikkahub.x.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏的契约自检。
 *
 * ## 为什么两类样本都要有
 *
 * **只测「该掩的掩了」会漏掉一半问题** —— 脱敏器最大的风险不是放过凭证(那看得见),
 * 而是**把不该掩的也掩了**:`max_tokens=1000`、`token_count=5` 在 LLM 应用里遍地都是,
 * 掩掉它们等于把最需要看的诊断信息抹了,而且**没人会立刻发现**。
 * 故「必须不掩」那一组是本用例的主体。
 *
 * ## 三个在离线验算时才发现的坑(各对应下面的样本)
 *
 * ① 值用 `\S+` 会把 JSON 的收尾 `"}`、URL 的 `&page=2` 一并吃掉;
 * ② `Authorization: Bearer <长令牌>` 里令牌只被当「一个词」处理,主体留在后面;
 * ③ `secret_access_key` 的凭据词在中间、末尾是 `key`,不单列则整串都不匹配 ——
 *    而 AWS 密钥值**没有可识别前缀**,漏了就是真漏。
 * ④ **「掩到行尾」在 JSON 载体上会把正文一起吃掉**(见下面那条用例)。这条是加了网络请求
 *    记录之后才暴露的:同一个 `Authorization` 在 logcat 里独占一行,在网络日志里却与
 *    请求体同处一行 —— 规则必须两种载体都对。
 */
class XLogScrubTest {

    private companion object {
        /** 三段 base64url 的令牌样例(内容随意,形状要真)。 */
        const val JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0." +
                "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
    }

    private fun assertScrub(source: String, expected: String, why: String) {
        assertEquals(why, expected, XLogScrub.scrub(source))
    }

    // ────────────────────────────────────
    // 该掩的:凭据
    // ────────────────────────────────────

    @Test
    fun `authorization header stops at quote, not end of line`() {
        // 载体一:纯 logcat 行。没有引号 → 值一直掩到行尾(与改造前一致)。
        assertScrub(
            "Authorization: Bearer $JWT",
            "Authorization: ***",
            "logcat 行没有引号,整段都是凭据",
        )
        // 载体二:网络日志。同一行是**一整条 JSON** —— 掩到行尾会把请求正文一并吞掉,
        // 而正文恰恰是最该留下的。值必须在引号处停。
        assertScrub(
            """{"headers":{"Authorization":"Bearer $JWT"},"body":"{\"prompt\":\"hi\"}"}""",
            """{"headers":{"Authorization":"***"},"body":"{\"prompt\":\"hi\"}"}""",
            "JSON 载体里正文必须活下来",
        )
    }

    @Test
    fun `cookies are masked in both directions`() {
        // 会话令牌常放在 cookie 里,而 Authorization 规则管不到它。
        assertScrub("Cookie: a=1; sessionid=deadbeef", "Cookie: ***", "请求带出去的 cookie")
        assertScrub("Set-Cookie: sid=x; HttpOnly", "Set-Cookie: ***", "服务端下发的 cookie")
        assertScrub(
            """{"headers":{"Cookie":"sid=x"},"body":"keep me"}""",
            """{"headers":{"Cookie":"***"},"body":"keep me"}""",
            "JSON 载体里的 cookie 同样只掩值",
        )
        // 反向:不能被误伤 —— `charset` 里含 `set`,但不是 `Set-Cookie`。
        assertScrub(
            "Content-Type: application/json; charset=utf-8",
            "Content-Type: application/json; charset=utf-8",
            "charset 不该被 cookie 规则误伤",
        )
    }

    @Test
    fun `key value forms are masked`() {
        assertScrub(
            "api_key=sk-abcdefghijklmnopqrstuvwxyz",
            "api_key=***",
            "api_key 的键值形式",
        )
        assertScrub(
            "apiKey: 'sk-abcdefghijklmnopqrstuvwxyz'",
            "apiKey: '***'",
            "camelCase + 引号:值被掩,引号保留",
        )
        assertScrub(
            "access_token=abcdef123456",
            "access_token=***",
            "access_token",
        )
        assertScrub(
            "accessToken=abcdef123456",
            "accessToken=***",
            "camelCase accessToken",
        )
        assertScrub(
            "aws_secret_access_key=wJalrXUtnFEMI",
            "aws_secret_access_key=***",
            "AWS 密钥:凭据词在中间,末尾是 key —— 必须命中",
        )
        assertScrub(
            "private_key=LS0tLS1CRUdJTiBSU0E",
            "private_key=***",
            "private_key",
        )
        assertScrub(
            "password=hunter2xyz",
            "password=***",
            "password",
        )
        assertScrub(
            "client_secret: 9f8e7d6c5b4a",
            "client_secret: ***",
            "client_secret",
        )
        assertScrub(
            "token=$JWT",
            "token=***",
            "token=",
        )
    }

    @Test
    fun `json form keeps its closing characters`() {
        assertScrub(
            """{"api_key": "sk-abcdefghijklmnopqrstuvwxyz"}""",
            """{"api_key": "***"}""",
            "值不能吃掉收尾的 \"} —— 否则日志结构被破坏",
        )
    }

    @Test
    fun `vendor prefixes are masked, longest first`() {
        assertScrub("sk-abcdefghijklmnopqrstuvwxyz", "sk-***", "OpenAI 前缀")
        assertScrub(
            "sk-ant-api03-abcdefghijklmnopqr",
            "sk-ant-***",
            "Anthropic:不能被 sk- 先吃掉、留下 api03-… 的尾巴",
        )
        assertScrub(
            "sk-or-v1-abcdefghijklmnopqrst",
            "sk-or-v1-***",
            "OpenRouter",
        )
        assertScrub(
            "ghp_abcdefghijklmnopqrstuvwxyz01",
            "ghp_***",
            "GitHub PAT",
        )
        assertScrub(
            "github_pat_11ABCDEFG0abcdefghij_klmnopqrstuvwxyz",
            "github_pat_***",
            "新式 GitHub PAT",
        )
        assertScrub(
            "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7",
            "AIza***",
            "Google API key",
        )
        assertScrub("xai-abcdefghijklmnopqrstuvwx", "xai-***", "xAI")
        assertScrub("AKIAIOSFODNN7EXAMPLE", "AKIA***", "AWS access key id")
    }

    @Test
    fun `bearer and bare jwt are masked`() {
        assertScrub("Bearer $JWT", "Bearer ***", "裸 Bearer")
        assertScrub("got jwt $JWT end", "got jwt eyJ*** end", "裸 JWT")
    }

    @Test
    fun `url query credentials are masked without eating other params`() {
        assertScrub(
            "https://x.test/v1?api_key=abc123&page=2",
            "https://x.test/v1?api_key=***&page=2",
            "查询串里的凭据掩掉,但 &page=2 必须保留",
        )
        assertScrub(
            "log: url=/v1?key=abc123 & retry",
            "log: url=/v1?key=*** & retry",
            "查询串里的裸 key",
        )
    }

    // ────────────────────────────────────
    // 不该掩的:用量数字与普通日志
    //
    // 这组才是本用例的主体 —— 误掩不会被发现,只会让人觉得「日志怎么少了一半」。
    // ────────────────────────────────────

    @Test
    fun `usage counters are not masked`() {
        assertScrub("max_tokens=1000", "max_tokens=1000", "max_tokens 是请求参数,不是凭据")
        assertScrub("tokens=1000", "tokens=1000", "复数 tokens")
        assertScrub("token_count=5", "token_count=5", "token_count 是计数")
        assertScrub(
            "prompt_tokens: 1234  completion_tokens: 567",
            "prompt_tokens: 1234  completion_tokens: 567",
            "用量统计 —— 掩掉它等于把最该看的信息抹了",
        )
    }

    @Test
    fun `plain log lines are left alone`() {
        assertScrub(
            "cache_key=abc",
            "cache_key=abc",
            "裸 key= 不在键值规则里(只在 URL 查询串里)",
        )
        assertScrub("secretSanta=true", "secretSanta=true", "secret 后紧跟 s,不是凭据键")
        assertScrub(
            "Choreographer: Skipped 42 frames",
            "Choreographer: Skipped 42 frames",
            "普通框架日志",
        )
        assertScrub(
            "okhttp: --> GET https://api.example.com/v1/models",
            "okhttp: --> GET https://api.example.com/v1/models",
            "普通请求日志(URL 里没有凭据参数)",
        )
        assertScrub("Room: query took 12ms", "Room: query took 12ms", "普通框架日志")
    }

    // ────────────────────────────────────
    // 自检:规则表非空
    // ────────────────────────────────────

    @Test
    fun `rule table is not empty`() {
        // 防「检查器永远通过」那类问题:规则表若被误删成空,上面所有样本都会「通过」
        // (因为输入等于输出,而"不该掩"那组恰好也期望不变)。
        assertTrue("规则表为空 → 脱敏实际没生效,上面的样本会假通过", XLogScrub.ruleCount >= 6)
    }
}
