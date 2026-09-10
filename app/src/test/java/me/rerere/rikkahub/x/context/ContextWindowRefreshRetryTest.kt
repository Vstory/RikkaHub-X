// 手动刷新未取到新内容后的重试链测试。
//
// 规则要点:三次重试分别落在点击后 5 / 10 / 15 分钟,用完即止并回落到 TTL 定时刷新;
// 整链有时限,防止陈旧链条在进程重启后突然开跑。
package me.rerere.rikkahub.x.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowRefreshRetryTest {

    private val now = 1_789_056_000_000L
    private val minute = 60_000L

    @Test
    fun `first retry is one interval after the click`() {
        val plan = RefreshRetryPlan.newRequest(now)
        assertEquals(now + RETRY_INTERVAL_MINUTES * minute, plan.nextAttemptAt)
        assertEquals(1, plan.attemptNumber)
    }

    /** 三次重试依次落在点击后 5 / 10 / 15 分钟 —— 间隔逐档拉长,而不是固定节奏猛敲。 */
    @Test
    fun `retries back off on the click timeline`() {
        var plan = RefreshRetryPlan.newRequest(now)
        val expected = listOf(5L, 10L, 15L)
        expected.forEachIndexed { index, minutes ->
            assertEquals("第 ${index + 1} 次", now + minutes * minute, plan.nextAttemptAt)
            assertEquals(index + 1, plan.attemptNumber)
            plan = plan.afterFailure()
        }
    }

    /** 额度恰好是 [MAX_RETRY_ATTEMPTS] 次,不多不少。 */
    @Test
    fun `allows exactly the configured number of attempts`() {
        var plan = RefreshRetryPlan.newRequest(now)
        repeat(MAX_RETRY_ATTEMPTS) {
            assertTrue("第 ${plan.attemptNumber} 次应还有额度", plan.hasAttemptsLeft)
            plan = plan.afterFailure()
        }
        assertFalse("额度用尽", plan.hasAttemptsLeft)
        assertFalse("用尽后不再继续", plan.shouldContinue(plan.nextAttemptAt))
    }

    /** 时限内继续,超时限停止 —— 防的是"几小时前那次点击的链子被重启后接着跑完"。 */
    @Test
    fun `stops once outside the window`() {
        val plan = RefreshRetryPlan.newRequest(now)
        assertTrue(plan.withinWindow(now))
        assertTrue(plan.withinWindow(now + RETRY_WINDOW_MINUTES * minute))
        assertFalse(plan.withinWindow(now + RETRY_WINDOW_MINUTES * minute + 1))
        assertFalse(plan.shouldContinue(now + RETRY_WINDOW_MINUTES * minute + 1))
    }

    /** 时限窗口必须宽于三次重试的跨度(15 分钟),否则末次重试永远等不到。 */
    @Test
    fun `window is wider than the whole retry span`() {
        val span = RETRY_INTERVAL_MINUTES * MAX_RETRY_ATTEMPTS
        assertTrue(
            "时限 $RETRY_WINDOW_MINUTES 分钟应宽于重试跨度 $span 分钟",
            RETRY_WINDOW_MINUTES > span,
        )
    }

    @Test
    fun `afterFailure advances the plan`() {
        val plan = RefreshRetryPlan.newRequest(now)
        val next = plan.afterFailure()
        assertEquals(1, next.attemptsDone)
        assertEquals(plan.requestedAt, next.requestedAt)  // 基准时刻不动
        assertNotEquals(plan.nextAttemptAt, next.nextAttemptAt)
    }

    // ── 落盘(状态要跨进程存活,否则用户点完切走就白点了)──────────

    @Test
    fun `round trips through storage`() {
        val plan = RefreshRetryPlan(requestedAt = now, attemptsDone = 2)
        val restored = RefreshRetryPlan.decode(RefreshRetryPlan.encode(plan))
        assertEquals(plan, restored)
        assertEquals(plan.nextAttemptAt, restored?.nextAttemptAt)
    }

    /** 内容损坏一律当作没有链子 —— 重试是尽力而为,不该因一个坏文件而崩。 */
    @Test
    fun `corrupt storage decodes to null`() {
        assertNull(RefreshRetryPlan.decode(""))
        assertNull(RefreshRetryPlan.decode("不是 JSON"))
        assertNull(RefreshRetryPlan.decode("""{"attemptsDone": 1}"""))  // 缺 requestedAt
    }

    /** 未知字段要能忽略:升级/降级时格式可能多出字段,不该因此丢掉整条链。 */
    @Test
    fun `unknown fields are tolerated`() {
        val decoded = RefreshRetryPlan.decode("""{"requestedAt": $now, "attemptsDone": 1, "futureField": "x"}""")
        assertEquals(1, decoded?.attemptsDone)
    }
}
