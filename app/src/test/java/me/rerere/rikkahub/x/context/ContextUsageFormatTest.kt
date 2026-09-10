// 上下文用量展示格式的单测。
//
// 期望值取自 OpenAI Codex 的真实渲染快照
// (codex-rs/tui/src/status/snapshots/codex_tui__status__tests__status_snapshot_includes_credits_and_limits.snap):
//   Token usage:      2K total  (1.4K input + 600 output)
//   Context window:   100% left (2.2K used / 272K)
//   5h limit:         [███████████░░░░░░░░░] 55% left
// 上面的 "2K"(2000)、"1.4K"(1400)、"600"、"2.2K"(2200)、"272K"(272000)
// 与 "55% → 11 格 / 88% → 18 格" 即下面用例的期望来源。
package me.rerere.rikkahub.x.context

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageFormatTest {

    // ─────────────── 紧凑数字(format_tokens_compact) ───────────────

    /** 小于 1000 原样输出,不做任何缩放。 */
    @Test
    fun `compact keeps values under one thousand as-is`() {
        assertEquals("0", ContextUsageFormat.compactTokens(0))
        assertEquals("1", ContextUsageFormat.compactTokens(1))
        assertEquals("600", ContextUsageFormat.compactTokens(600))
        assertEquals("999", ContextUsageFormat.compactTokens(999))
    }

    /** 千级:缩放后 <10 取 2 位小数并去尾零,≥10 取 1 位,≥100 取整数。 */
    @Test
    fun `compact scales thousands with magnitude-based decimals`() {
        assertEquals("1K", ContextUsageFormat.compactTokens(1_000))
        assertEquals("1.2K", ContextUsageFormat.compactTokens(1_200))
        assertEquals("1.4K", ContextUsageFormat.compactTokens(1_400))
        assertEquals("1.5K", ContextUsageFormat.compactTokens(1_500))
        assertEquals("2K", ContextUsageFormat.compactTokens(2_000))
        assertEquals("2.2K", ContextUsageFormat.compactTokens(2_200))
        assertEquals("12.3K", ContextUsageFormat.compactTokens(12_345))
        assertEquals("123K", ContextUsageFormat.compactTokens(123_456))
        assertEquals("272K", ContextUsageFormat.compactTokens(272_000))
    }

    /** 百万级及以上依次取 M / B / T。 */
    @Test
    fun `compact uses M B and T suffixes by magnitude`() {
        assertEquals("1M", ContextUsageFormat.compactTokens(1_000_000))
        assertEquals("1.23M", ContextUsageFormat.compactTokens(1_234_567))
        assertEquals("1.5M", ContextUsageFormat.compactTokens(1_500_000))
        assertEquals("272M", ContextUsageFormat.compactTokens(272_000_000))
        assertEquals("1B", ContextUsageFormat.compactTokens(1_000_000_000))
        assertEquals("1T", ContextUsageFormat.compactTokens(1_000_000_000_000))
    }

    /** 负值按 0 处理,不出现 "-1K" 之类的输出。 */
    @Test
    fun `compact clamps negative values to zero`() {
        assertEquals("0", ContextUsageFormat.compactTokens(-1))
        assertEquals("0", ContextUsageFormat.compactTokens(-999_999))
    }

    /**
     * 小数分隔符必须恒为句点。
     * Rust 的 `format!` 与 Locale 无关;若此处改用默认 Locale,
     * 在逗号作小数分隔符的语言环境下会输出 "1,5K",与 Codex 不一致。
     */
    @Test
    fun `compact always uses dot as decimal separator`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.5K", ContextUsageFormat.compactTokens(1_500))
        } finally {
            java.util.Locale.setDefault(default)
        }
    }

    // ─────────────── 进度条(render_limit_progress_bar) ───────────────

    /** 边界:满剩余 = 全实心,零剩余 = 全空,越界被钳制。 */
    @Test
    fun `bar fills all segments at full remaining and none at zero`() {
        assertEquals(20, ContextUsageFormat.filledSegments(100.0))
        assertEquals(0, ContextUsageFormat.filledSegments(0.0))
        assertEquals(20, ContextUsageFormat.filledSegments(150.0))
        assertEquals(0, ContextUsageFormat.filledSegments(-10.0))
    }

    /** 中段按比例四舍五入(与 Codex 快照的 55%→11 格、70%→14 格、88%→18 格一致)。 */
    @Test
    fun `bar rounds ratio to nearest segment`() {
        assertEquals(11, ContextUsageFormat.filledSegments(55.0))
        assertEquals(14, ContextUsageFormat.filledSegments(70.0))
        assertEquals(18, ContextUsageFormat.filledSegments(88.0))
        assertEquals(18, ContextUsageFormat.filledSegments(89.0))
        assertEquals(10, ContextUsageFormat.filledSegments(50.0))
    }

    /** 总段数固定为 20(Codex STATUS_LIMIT_BAR_SEGMENTS)。 */
    @Test
    fun `bar segment count matches codex constant`() {
        assertEquals(20, ContextUsageFormat.BAR_SEGMENTS)
    }

    // ─────────────── 会话累计用量 ───────────────

    /** 逐条累加各轮 promptTokens / completionTokens;total = 入 + 出。 */
    @Test
    fun `cumulative sums input and output across turns`() {
        val messages = listOf(
            UIMessage.user("q1"),
            UIMessage.assistant("a1").copy(usage = TokenUsage(promptTokens = 800, completionTokens = 400)),
            UIMessage.user("q2"),
            UIMessage.assistant("a2").copy(usage = TokenUsage(promptTokens = 1_400, completionTokens = 600)),
        )
        val cumulative = ContextUsageCalculator.cumulativeUsage(messages)
        assertEquals(2_200L, cumulative.inputTokens)
        assertEquals(1_000L, cumulative.outputTokens)
        assertEquals(3_200L, cumulative.totalTokens)
    }

    /** 无 usage 的消息(用户消息、失败回复)不贡献累计值,不抛异常。 */
    @Test
    fun `cumulative ignores messages without usage`() {
        val messages = listOf(
            UIMessage.user("q"),
            UIMessage.assistant("no usage"),
        )
        val cumulative = ContextUsageCalculator.cumulativeUsage(messages)
        assertEquals(0L, cumulative.inputTokens)
        assertEquals(0L, cumulative.outputTokens)
        assertEquals(0L, cumulative.totalTokens)
    }
}
