package me.rerere.rikkahub.x.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收策略单测（X 存储重构 P0）。
 *
 * 这是整个重构里**最不能出错**的一段：判错的代价是删掉用户还在用的文件，
 * 且不可逆。故把决策做成纯函数并在此穷举 —— 尤其钉住三条：
 * ① **有活引用绝不删**（哪怕已过宽限、哪怕重试次数已用尽）；
 * ② 时间闸门用 `<`，即 `now == notBefore` 时**可以删**（避免边界上永远差一毫秒）；
 * ③ 代数落后的计划一律作废（防「排队期间又被引用」的误删）。
 */
class AssetGcPolicyTest {

    private val now = 1_800_000_000_000L

    // ---- 决策表 ----

    @Test
    fun `deletes when unreferenced and grace elapsed`() {
        assertEquals(
            GcDecision.DELETE,
            AssetGcPolicy.decide(
                hasLiveReferences = false,
                notBefore = now - 1,
                nowMillis = now,
                attempts = 0,
            ),
        )
    }

    @Test
    fun `keeps referenced asset even past grace and attempts`() {
        // 安全优先:有任何活引用就绝不删
        val decision = AssetGcPolicy.decide(
            hasLiveReferences = true,
            notBefore = now - 1_000_000,
            nowMillis = now,
            attempts = AssetGcPolicy.DEFAULT_MAX_ATTEMPTS + 10,
        )
        assertEquals(GcDecision.KEEP_REFERENCED, decision)
    }

    @Test
    fun `waits inside grace window`() {
        assertEquals(
            GcDecision.WAIT_GRACE,
            AssetGcPolicy.decide(
                hasLiveReferences = false,
                notBefore = now + 1,
                nowMillis = now,
                attempts = 0,
            ),
        )
    }

    @Test
    fun `grace boundary is inclusive of deletion`() {
        // now == notBefore → 可删(时间闸门用 < 判断)
        assertEquals(
            GcDecision.DELETE,
            AssetGcPolicy.decide(false, notBefore = now, nowMillis = now, attempts = 0),
        )
    }

    @Test
    fun `abandons after max attempts`() {
        assertEquals(
            GcDecision.ABANDON,
            AssetGcPolicy.decide(
                hasLiveReferences = false,
                notBefore = now - 1,
                nowMillis = now,
                attempts = AssetGcPolicy.DEFAULT_MAX_ATTEMPTS,
            ),
        )
        // 差一次仍可删
        assertEquals(
            GcDecision.DELETE,
            AssetGcPolicy.decide(
                hasLiveReferences = false,
                notBefore = now - 1,
                nowMillis = now,
                attempts = AssetGcPolicy.DEFAULT_MAX_ATTEMPTS - 1,
            ),
        )
    }

    @Test
    fun `referenced wins over abandon`() {
        // 引用判定在重试次数之前 —— 顺序有意如此
        assertEquals(
            GcDecision.KEEP_REFERENCED,
            AssetGcPolicy.decide(
                hasLiveReferences = true,
                notBefore = now - 1,
                nowMillis = now,
                attempts = AssetGcPolicy.DEFAULT_MAX_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `grace wins over abandon`() {
        // 时间闸门在重试次数之前
        assertEquals(
            GcDecision.WAIT_GRACE,
            AssetGcPolicy.decide(
                hasLiveReferences = false,
                notBefore = now + 1000,
                nowMillis = now,
                attempts = AssetGcPolicy.DEFAULT_MAX_ATTEMPTS,
            ),
        )
    }

    // ---- 宽限期 ----

    @Test
    fun `default grace is seven days`() {
        assertEquals(7L * 24 * 60 * 60 * 1000, AssetGcPolicy.DEFAULT_GRACE_MILLIS)
    }

    @Test
    fun `graceDeadline adds grace`() {
        assertEquals(now + 1000, AssetGcPolicy.graceDeadline(now, graceMillis = 1000))
        assertEquals(now, AssetGcPolicy.graceDeadline(now, graceMillis = 0))
        assertEquals(
            now + AssetGcPolicy.DEFAULT_GRACE_MILLIS,
            AssetGcPolicy.graceDeadline(now),
        )
    }

    @Test
    fun `graceDeadline rejects negative grace`() {
        assertThrows(IllegalArgumentException::class.java) {
            AssetGcPolicy.graceDeadline(now, graceMillis = -1)
        }
    }

    // ---- 退避 ----

    @Test
    fun `backoff grows exponentially then caps`() {
        val base = AssetGcPolicy.backoffMillis(0)
        assertEquals(5L * 60 * 1000, base)
        assertEquals(base * 2, AssetGcPolicy.backoffMillis(1))
        assertEquals(base * 4, AssetGcPolicy.backoffMillis(2))
    }

    @Test
    fun `backoff is non decreasing and capped`() {
        var previous = 0L
        for (attempts in 0..40) {
            val current = AssetGcPolicy.backoffMillis(attempts)
            assertTrue("退避应单调不减:$attempts", current >= previous)
            assertTrue("退避应有上限:$attempts", current <= 6L * 60 * 60 * 1000)
            previous = current
        }
        // 上限确实会被触及(否则"有上限"形同虚设)
        assertEquals(6L * 60 * 60 * 1000, AssetGcPolicy.backoffMillis(40))
    }

    @Test
    fun `backoff rejects negative attempts`() {
        assertThrows(IllegalArgumentException::class.java) { AssetGcPolicy.backoffMillis(-1) }
    }

    @Test
    fun `nextAttemptAt adds backoff`() {
        assertEquals(now + AssetGcPolicy.backoffMillis(0), AssetGcPolicy.nextAttemptAt(now, 0))
        assertEquals(now + AssetGcPolicy.backoffMillis(3), AssetGcPolicy.nextAttemptAt(now, 3))
    }

    // ---- 代数 ----

    @Test
    fun `stale plan detection`() {
        assertTrue("旧代数计划应作废", AssetGcPolicy.isPlanStale(plannedGeneration = 0, currentGeneration = 1))
        assertFalse("同代数仍有效", AssetGcPolicy.isPlanStale(plannedGeneration = 1, currentGeneration = 1))
        assertFalse("计划代数更新(不应发生)时不判作废", AssetGcPolicy.isPlanStale(plannedGeneration = 2, currentGeneration = 1))
    }
}
