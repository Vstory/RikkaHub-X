package me.rerere.rikkahub.x.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * logcat 噪声过滤的契约自检。
 *
 * ## 主体是「不该丢的不能丢」
 *
 * 过滤是**有损**的:猜错一次就是「现场少了一段,而你不知道少了什么」。
 * 故本用例的重点不在「噪声被滤掉了」(那一眼看得见),而在:
 *
 * - 认不出格式的行 → 保留(格式变了就自动退回「全都留」,而不是静默丢一半);
 * - 不在名单里的 tag → 保留;
 * - **能反映性能问题的 tag 一律不在名单里**(`Choreographer` 的丢帧提示 ——
 *   那正是「流式卡顿」的证据,滤掉它等于把要查的东西弄瞎)。
 *
 * ## 名单不许被删空
 *
 * 与脱敏规则表同一个道理:名单若被误删成空,上面「该丢的丢了」那些断言会全部
 * 假通过(输入等于输出)。故最后一条用例钉住条数下限。
 */
class XLogcatNoiseTest {

    /** 造一条 threadtime 格式的 logcat 行。 */
    private fun line(tag: String, message: String = "x") =
        "09-13 08:46:33.424 10259 13538 I $tag: $message"

    // ────────────────────────────────────
    // 该丢的
    // ────────────────────────────────────

    @Test
    fun `known OEM tags are noise`() {
        // 全部取自 2026-09-13 那份真实捕获里实际出现过的 tag
        assertTrue(XLogcatNoise.isNoise(line("DynamicFramerate [AnimationSpeedAware]")))
        assertTrue(XLogcatNoise.isNoise(line("ViewRootImplExtImpl")))
        assertTrue(XLogcatNoise.isNoise(line("ImeTracker")))
        assertTrue(XLogcatNoise.isNoise(line("WindowOnBackDispatcher")))
        assertTrue(XLogcatNoise.isNoise(line("OplusScrollToTopManager")))
    }

    @Test
    fun `bracketed tag variants are matched by prefix`() {
        // VRI[RouteActivity] / VRI[Pop-Up Window] —— 带后缀的 tag 很常见,故用前缀匹配
        assertTrue(XLogcatNoise.isNoise(line("VRI[RouteActivity]")))
        assertTrue(XLogcatNoise.isNoise(line("VRI[Pop-Up Window]")))
    }

    @Test
    fun `every severity letter is recognised`() {
        // level 是一个字母;别只按 I/W 写正则 —— 真实日志里有 V/D/E
        for (level in listOf("V", "D", "I", "W", "E", "F")) {
            val raw = "09-13 08:46:33.424 10259 10259 $level ImeTracker: x"
            assertTrue("level=$level 应当被认出并被判为噪声", XLogcatNoise.isNoise(raw))
        }
    }

    // ────────────────────────────────────
    // 不该丢的(本用例的主体)
    // ────────────────────────────────────

    @Test
    fun `lines without a parseable tag are kept`() {
        // 过滤失败的代价必须是「日志变多」,不能是「日志少了」。
        // 故任何解析不出 tag 的行一律保留 —— 包括 logcat 自身的分隔行与清单标记。
        val kept = listOf(
            "--------- beginning of system",
            "===== [x-diag] capture info ===== [x-diag]",
            "!!! [x-diag] size cap reached, later log lines were not recorded (file ends here).",
            "This line is not a logcat record at all",
            "",
        )
        kept.forEach { raw ->
            assertFalse("不该丢:`$raw`", XLogcatNoise.isNoise(raw))
            assertNull("解析不出 tag 就该返回 null:`$raw`", XLogcatNoise.tagOf(raw))
        }
    }

    @Test
    fun `app and framework tags that carry signal are kept`() {
        val kept = listOf(
            "OkHttp",            // 请求头(含被掩的 Authorization)
            "ResponseAPI",       // 模型 SSE 流
            "Choreographer",     // ★ 丢帧提示 —— 流式卡顿的证据,绝不能滤
            "FirebaseCrashlytics",
            "AndroidRuntime",    // 崩溃栈
            "Room",
            "ResourcesManager",  // 注意:与名单里的 ResourcesManagerExtImpl 不同
        )
        kept.forEach { tag ->
            assertFalse("`$tag` 必须保留", XLogcatNoise.isNoise(line(tag)))
        }
    }

    @Test
    fun `a tag that merely contains a noise word is kept`() {
        // 前缀匹配 ≠ 包含匹配。这条钉住「不许宽宽松松地 contains」
        assertFalse(
            "MyDynamicFramerateProbe 不该被判为噪声",
            XLogcatNoise.isNoise(line("MyDynamicFramerateProbe")),
        )
        assertFalse(
            "正文里出现噪声词不算",
            XLogcatNoise.isNoise(line("MyTag", "DynamicFramerate is slow")),
        )
    }

    @Test
    fun `tag extraction stops at the first colon`() {
        // 正文里还有冒号 —— 若贪心地找最后一个冒号,tag 就变成正文的一部分了
        assertEquals("OkHttp", XLogcatNoise.tagOf(line("OkHttp", "--> GET https://a.b/c")))
        assertEquals("MyTag", XLogcatNoise.tagOf(line("MyTag", "a: b: c")))
    }

    // ────────────────────────────────────
    // 自检
    // ────────────────────────────────────

    @Test
    fun `pattern list is not empty`() {
        // 防「名单被误删成空表 → 上面『该丢的』全部假通过」
        assertTrue(
            "噪声名单为空 → 过滤实际没生效,用例会假通过",
            XLogcatNoise.patternCount >= 15,
        )
    }

    @Test
    fun `header text reflects the session value, not the current switch`() {
        // 清单头描述的是**文件**。开关中途翻转时,当前值已经不描述这份文件了 ——
        // 故 describeForHeader 收的是一个显式传入的布尔,而不是自己去读开关。
        assertTrue(XLogcatNoise.describeForHeader(true).startsWith("on"))
        assertTrue(XLogcatNoise.describeForHeader(false).startsWith("OFF"))
    }
}
