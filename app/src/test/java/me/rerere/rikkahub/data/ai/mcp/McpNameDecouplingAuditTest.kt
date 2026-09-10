// 审计验证用例(2026-09-10,分支 audit/verify-x)
// 约定见 WorkspaceArchiveAuditTest 头注释:断言期望的正确行为,失败 = 结论成立。
//
// A8:displayName(UI 显示名)与 name(协议内部名)解耦后,
//     只填显示名时 UI 看起来已配置,但内部名为空 —— 而 McpSessionRegistry 的
//     连接条件含 `commonOptions.name.isNotBlank()`(上游既有代码,未改动),
//     结果是「界面显示已启用,实际静默不连接」。
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
            "内部名为空:UI 显示已配置,但 name.isBlank() 会让会话注册表跳过连接",
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
