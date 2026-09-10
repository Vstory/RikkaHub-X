// 审计验证用例(2026-09-10,分支 audit/verify-x)
// 约定见 WorkspaceArchiveAuditTest 头注释:断言期望的正确行为,失败 = 结论成立。
//
// 本文件覆盖上下文用量圆环的分子口径。
// A6 —— **已确认为缺陷并修复**:无 usage 时返回 null,不再返回只含输入增量的误导值;
//       用例转为回归守护(失败 = 修复被回退)。
package me.rerere.rikkahub.x.context

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextUsageAuditTest {

    /** 主路径(设计如此):最近一次 usage.promptTokens + 输入框增量。 */
    @Test
    fun `DOC main path uses last assistant usage plus input estimate`() {
        val messages = listOf(
            UIMessage.user("问题"),
            UIMessage.assistant("回答").copy(usage = TokenUsage(promptTokens = 1200)),
        )
        assertEquals(1200L + "12345678".length / 4, ContextUsageCalculator.currentUsageTokens(messages, "12345678"))
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
}
