// 审计验证用例(2026-09-10,分支 audit/verify-x)
// 约定见 WorkspaceArchiveAuditTest 头注释:断言期望的正确行为,失败 = 结论成立。
//
// A8 复核结论(2026-09-10):该状态**不可经 UI 落库**,不构成缺陷,本文件仅记录
//     数据类语义(displayName 与 name 可各自独立为空,消费侧以 name 为连接条件)。
//     依据:落库唯一入口 UseEditState.confirm() 只有一个调用点(保存按钮),
//     其 onClick 守卫 `name.isNotBlank() && isValidMcpName(...)`;
//     新增/导入亦 filter { name.isNotBlank() } —— 均为上游既有逻辑,X 未改动。
package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpNameDecouplingAuditTest {

    /** A8:只填显示名时,内部名仍为空 → UI 有名字但协议链路不认。 */
    @Test
    fun `A8 displayName alone leaves internal name blank`() {
        val options = McpCommonOptions(name = "", displayName = "我的服务器")
        assertEquals("我的服务器", options.uiName)
        assertTrue(
            "数据类语义:内部名可与显示名独立为空(UI 落库有守卫,不会产生该状态)",
            options.name.isBlank(),
        )
    }

    /** A8 反向:显示名缺失时回退到内部名(这层行为符合预期)。 */
    @Test
    fun `DOC uiName falls back to internal name`() {
        assertEquals("demo", McpCommonOptions(name = "demo").uiName)
    }

    /** 对照:内部名确实参与连接复用 key(clientName),并非纯装饰字段。 */
    @Test
    fun `DOC internal name participates in connection key`() {
        val base = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "a"),
            url = "https://example.com/mcp",
        )
        val renamed = base.copy(commonOptions = base.commonOptions.copy(name = "b"))
        assertNotEquals(base.connectionKey(), renamed.connectionKey())
    }
}
