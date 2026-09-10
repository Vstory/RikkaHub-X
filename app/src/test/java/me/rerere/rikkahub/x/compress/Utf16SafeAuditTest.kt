// 审计验证用例(2026-09-10,分支 audit/verify-x)
// 约定见 WorkspaceArchiveAuditTest 头注释:断言期望的正确行为,失败 = 结论成立。
//
// A10 —— **已确认为缺陷并修复**:首字符即代理对且 maxLength=1 时不再返回空串;
//        用例转为回归守护(实际调用点预算远大于 1,影响面小,但边界值不该丢内容)。
package me.rerere.rikkahub.x.compress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Utf16SafeAuditTest {

    /** A10:maxLength=1 + 代理对开头 → 期望至少保留该字符(而非返回空串)。 */
    @Test
    fun `A10 maxLength one with leading surrogate keeps some content`() {
        val result = truncateHeadUtf16Safe("😀ab", 1)
        assertTrue("返回了空串(内容全丢)", result.isNotEmpty())
    }

    /** 对照:正常预算下不切代理对,也不丢内容。 */
    @Test
    fun `DOC normal budget keeps text and never splits surrogate pairs`() {
        val text = "😀😀😀abc"
        val cut = truncateHeadUtf16Safe(text, 5)
        assertTrue("不应以孤立高位代理结尾", !cut.endsWith('\uD83D'))
        assertEquals("😀😀", cut)
    }
}
