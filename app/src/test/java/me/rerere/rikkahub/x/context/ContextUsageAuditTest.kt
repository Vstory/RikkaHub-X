// 审计验证用例(2026-09-10,分支 audit/verify-x)
// 约定见 WorkspaceArchiveAuditTest 头注释:断言期望的正确行为,失败 = 结论成立。
//
// 本文件覆盖上下文用量圆环的分子口径。
// A6 —— **已确认为缺陷并修复**:无 usage 时返回 null,不再返回只含输入增量的误导值;
//       用例转为回归守护(失败 = 修复被回退)。
// A9 —— **已确认为缺陷并修复**(2026-09-10):分子漏算上一轮回复的 completionTokens,
//       导致占用被持续低估;用例转为回归守护(失败 = 修复被回退)。
// A10 —— 用量分解(圆环点击后的明细弹窗):各项之和 == 合计、与圆环口径一致、
//       剩余钳零不出现负数、无 usage 时同样隐藏。
package me.rerere.rikkahub.x.context

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextUsageAuditTest {

    /**
     * 主路径(设计如此):最近一次 usage 的 promptTokens + completionTokens + 输入框增量。
     * 计入 completionTokens 是因为上一轮回复发出后也留在上下文里,只算 promptTokens 会低估。
     */
    @Test
    fun `DOC main path uses last assistant usage plus input estimate`() {
        val messages = listOf(
            UIMessage.user("问题"),
            UIMessage.assistant("回答").copy(usage = TokenUsage(promptTokens = 1200)),
        )
        assertEquals(1200L + "12345678".length / 4, ContextUsageCalculator.currentUsageTokens(messages, "12345678"))
    }

    /** A9(2026-09-10 修复):上一轮回复的输出 token 必须计入,否则占用被持续低估。 */
    @Test
    fun `A9 counts previous reply completion tokens`() {
        val messages = listOf(
            UIMessage.user("问题"),
            UIMessage.assistant("回答").copy(usage = TokenUsage(promptTokens = 1200, completionTokens = 300)),
        )
        val expected = 1200L + 300L + "1234567890123456".length / 4
        assertEquals(
            "上一轮回复的 300 输出 token 未计入,当前占用被低估",
            expected,
            ContextUsageCalculator.currentUsageTokens(messages, "1234567890123456"),
        )
    }

    /** A6:有历史、无 usage、有输入 → 期望返回 null(宁可不显示,也不显示误导性的近 0%)。 */
    @Test
    fun `A6 returns null when history exists but no usage yet`() {
        val messages = listOf(
            UIMessage.user("历史问题"),
            UIMessage.assistant("历史回答"),
        )
        val value = ContextUsageCalculator.currentUsageTokens(messages, "正在输入的一段文字")
        assertNull(
            "历史消息(${messages.size} 条)无 usage 时返回了 $value(= 仅输入估算),圆环会显示近 0%",
            value,
        )
    }

    /** 无历史也无输入 → null(现状正确,作为对照)。 */
    @Test
    fun `DOC empty conversation returns null`() {
        assertNull(ContextUsageCalculator.currentUsageTokens(emptyList(), ""))
    }

    // ─────────────────── A10:用量分解(圆环点击后的明细弹窗) ───────────────────

    /** A10:分解各项之和 == 合计,且与圆环口径一致(两处数字不得漂移)。 */
    @Test
    fun `A10 breakdown parts sum to used and match ring formula`() {
        val messages = listOf(
            UIMessage.user("问题"),
            UIMessage.assistant("回答").copy(usage = TokenUsage(promptTokens = 1200, completionTokens = 300)),
        )
        val input = "1234567890123456"
        val breakdown = ContextUsageCalculator.breakdown(messages, input, 200_000)!!
        assertEquals(
            "分解各项之和与合计不符",
            breakdown.usedTokens,
            breakdown.promptTokens + breakdown.completionTokens + breakdown.inputTokens,
        )
        assertEquals(
            "明细弹窗与圆环口径不一致",
            ContextUsageCalculator.currentUsageTokens(messages, input),
            breakdown.usedTokens,
        )
    }

    /** A10:剩余 = 容量 − 合计;百分比按四舍五入取整。 */
    @Test
    fun `A10 remaining equals capacity minus used`() {
        val messages = listOf(
            UIMessage.assistant("答").copy(usage = TokenUsage(promptTokens = 1_000)),
        )
        val breakdown = ContextUsageCalculator.breakdown(messages, "", 10_000)!!
        assertEquals(9_000L, breakdown.remainingTokens)
        assertEquals(10, breakdown.percentInt)
        assertEquals(90, breakdown.remainingPercent)
    }

    /** A10:占用超出容量时,剩余与剩余百分比钳为 0,不出现负数。 */
    @Test
    fun `A10 remaining clamps at zero when over capacity`() {
        val messages = listOf(
            UIMessage.assistant("答").copy(usage = TokenUsage(promptTokens = 5_000, completionTokens = 1_000)),
        )
        val breakdown = ContextUsageCalculator.breakdown(messages, "", 4_000)!!
        assertEquals(0L, breakdown.remainingTokens)
        assertEquals(0, breakdown.remainingPercent)
        assertEquals(100, breakdown.percentInt)
        assertEquals(1f, breakdown.percent, 1e-6f)
    }

    /** A10:无 usage 时分解同样返回 null(与圆环门控一致,不显示无据可依的数值)。 */
    @Test
    fun `A10 breakdown returns null without usage`() {
        assertNull(ContextUsageCalculator.breakdown(emptyList(), "x", 200_000))
        assertNull(
            ContextUsageCalculator.breakdown(
                listOf(UIMessage.user("问题"), UIMessage.assistant("回答")),
                "x",
                200_000,
            ),
        )
    }
}
