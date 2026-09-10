// `updatedAt` 闸门的测试。
//
// 该字段是排序与展示的唯一依据,故必须可信。缺了这道闸,一个把年份写成 2062 的版本会
// **永远赢过后续更新**把表钉死,而界面看不出任何异常 —— 这类错误自己发现不了,只能靠拦。
package me.rerere.rikkahub.x.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ContextWindowUpdatedAtGuardTest {

    private val now: Instant = Instant.parse("2026-09-10T16:00:00Z")

    private fun tableJson(updatedAt: String) = """
        {
          "schemaVersion": 1,
          "updatedAt": "$updatedAt",
          "interpretation": { "strategy": "ordered-keyword" },
          "defaultContextWindow": 200000
        }
    """.trimIndent()

    // ── 闸门本身 ────────────────────────────────────────────────

    @Test
    fun `accepts past timestamps`() {
        assertTrue(isUpdatedAtSane("2026-09-10T23:30:30+08:00", now))
        assertTrue(isUpdatedAtSane("2026-09-10", now))
        assertTrue(isUpdatedAtSane("2019-01-01T00:00:00Z", now))
    }

    /** 允许小的时钟偏移:设备时间与数据维护者时间不可能完全一致。 */
    @Test
    fun `tolerates small clock skew`() {
        assertTrue(isUpdatedAtSane("2026-09-10T17:00:00Z", now))  // 超前 1 小时
        assertTrue(isUpdatedAtSane("2026-09-11T15:00:00Z", now))  // 超前 23 小时
    }

    /** 年份写错(2062 / 2099)是现实中最可能发生的粗错,必须挡住。 */
    @Test
    fun `rejects far future timestamps`() {
        assertFalse(isUpdatedAtSane("2062-09-10T23:30:30+08:00", now))
        assertFalse(isUpdatedAtSane("2099-01-01T00:00:00Z", now))
        assertFalse(isUpdatedAtSane("2026-09-12T00:00:00Z", now))  // 超前 32 小时
    }

    @Test
    fun `rejects unparseable or missing timestamps`() {
        assertFalse(isUpdatedAtSane("", now))
        assertFalse(isUpdatedAtSane("昨天", now))
        assertFalse(isUpdatedAtSane("2026-13-45T99:99:99Z", now))
    }

    // ── 接入 parse 后的效果 ──────────────────────────────────────

    @Test
    fun `parse accepts a sane table`() {
        val result = ContextWindowTable.parse(tableJson("2026-09-10T23:30:30+08:00"))
        assertTrue(result is ParseResult.Ok)
    }

    /**
     * 未来时间戳**整表被拒**,而不是"当成最旧勉强用"。
     * 拒表会保留上一份好表并把失败原因显给用户,问题当场可见;勉强用则会静默钉死。
     */
    @Test
    fun `parse rejects a table dated in the future`() {
        val result = ContextWindowTable.parse(tableJson("2062-09-10T23:30:30+08:00"))
        assertTrue("未来时间戳必须拒表", result is ParseResult.Rejected)
    }

    @Test
    fun `parse rejects a table without updatedAt`() {
        val result = ContextWindowTable.parse(
            """
            {
              "schemaVersion": 1,
              "interpretation": { "strategy": "ordered-keyword" },
              "defaultContextWindow": 200000
            }
            """.trimIndent(),
        )
        assertTrue("缺 updatedAt 必须拒表", result is ParseResult.Rejected)
    }

    /** 拒表原因要写清是时间戳的问题,便于维护者定位,而不是笼统的"表被拒"。 */
    @Test
    fun `rejection reason mentions updatedAt`() {
        val result = ContextWindowTable.parse(tableJson("2062-09-10T23:30:30+08:00"))
        val reason = (result as ParseResult.Rejected).reason
        assertTrue("原因应指向 updatedAt,实际:$reason", reason.contains("updatedAt"))
    }

    /** 这类拒表是"表内容有问题",不是"该源不可用" —— 两者对调用方的处置不同。 */
    @Test
    fun `future timestamp is a content problem not a broken source`() {
        val result = ContextWindowTable.parse(tableJson("2062-09-10T23:30:30+08:00"))
        assertFalse((result as ParseResult.Rejected).unusableSource)
    }

    /** 本仓库自带的数据文件必须能过闸(否则每次刷新都会被拒)。 */
    @Test
    fun `repository table passes the guard`() {
        val candidate = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "model-contexts/context-windows.json") }
            .firstOrNull { it.isFile } ?: return  // 工作目录不含数据文件时跳过(数据不变量测试已覆盖)
        val result = ContextWindowTable.parse(candidate.readText())
        assertTrue("仓库数据文件未过 updatedAt 闸门", result is ParseResult.Ok)
    }
}
