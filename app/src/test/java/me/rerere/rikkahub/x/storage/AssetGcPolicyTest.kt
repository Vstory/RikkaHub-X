package me.rerere.rikkahub.x.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收候选策略单测（X 存储重构 P1）。
 *
 * 这是整个重构里**最不能出错**的一段：判错的代价是删掉用户还在用的文件，且不可逆。
 * 故把判定做成纯函数并在此穷举 —— 尤其钉住三条：
 * ① **有活引用绝不进候选**（哪怕早已过了观察门槛）；
 * ② 门槛用 `<`，即 `now == candidateAt` 时**可进候选**（避免边界上永远差一毫秒）；
 * ③ 代数落后的清单一律作废（防「用户看清单期间资产又被引用」的误删）。
 */
class AssetGcPolicyTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    // ---- 判定表 ----

    @Test
    fun `becomes candidate when unreferenced and past observation`() {
        assertEquals(
            GcDecision.CANDIDATE,
            AssetGcPolicy.decide(hasLiveReferences = false, nowMillis = now, candidateAt = now - 1),
        )
    }

    @Test
    fun `keeps referenced asset even far past observation`() {
        // 安全优先:有任何活引用就绝不进候选
        assertEquals(
            GcDecision.KEEP_REFERENCED,
            AssetGcPolicy.decide(
                hasLiveReferences = true,
                nowMillis = now,
                candidateAt = now - 1_000_000,
            ),
        )
    }

    @Test
    fun `waits inside observation window`() {
        assertEquals(
            GcDecision.WAIT_OBSERVATION,
            AssetGcPolicy.decide(hasLiveReferences = false, nowMillis = now, candidateAt = now + 1),
        )
    }

    @Test
    fun `observation boundary is inclusive of candidacy`() {
        // now == candidateAt → 可进候选(门槛用 < 判断)
        assertEquals(
            GcDecision.CANDIDATE,
            AssetGcPolicy.decide(hasLiveReferences = false, nowMillis = now, candidateAt = now),
        )
    }

    @Test
    fun `referenced wins over observation window`() {
        // 引用判定在门槛之前 —— 顺序有意如此
        assertEquals(
            GcDecision.KEEP_REFERENCED,
            AssetGcPolicy.decide(hasLiveReferences = true, nowMillis = now, candidateAt = now + 1000),
        )
    }

    @Test
    fun `omitting threshold means immediately eligible`() {
        // 缺省门槛是 Long.MAX_VALUE,即「不设门槛」
        assertEquals(
            GcDecision.CANDIDATE,
            AssetGcPolicy.decide(hasLiveReferences = false, nowMillis = now),
        )
    }

    // ---- 观察门槛 ----

    @Test
    fun `default observation is one day`() {
        assertEquals(day, AssetGcPolicy.DEFAULT_OBSERVATION_MILLIS)
    }

    @Test
    fun `candidateAt adds observation`() {
        assertEquals(now + 1000, AssetGcPolicy.candidateAt(now, observationMillis = 1000))
        assertEquals(now, AssetGcPolicy.candidateAt(now, observationMillis = 0))
        assertEquals(now + day, AssetGcPolicy.candidateAt(now))
    }

    @Test
    fun `candidateAt rejects negative observation`() {
        assertThrows(IllegalArgumentException::class.java) {
            AssetGcPolicy.candidateAt(now, observationMillis = -1)
        }
    }

    // ---- 闲置时长 ----

    @Test
    fun `idleMillis measures time since last reference`() {
        assertEquals(2 * day, AssetGcPolicy.idleMillis(now - 2 * day, now))
        assertEquals(0, AssetGcPolicy.idleMillis(now, now))
    }

    @Test
    fun `idleMillis clamps clock skew to zero`() {
        // 设备改时间导致「未来」的首次无引用时刻时,不显示负数
        assertEquals(0, AssetGcPolicy.idleMillis(now + day, now))
    }

    // ---- 代数 ----

    @Test
    fun `stale plan detection`() {
        assertTrue("旧代数清单应作废", AssetGcPolicy.isPlanStale(plannedGeneration = 0, currentGeneration = 1))
        assertFalse("同代数仍有效", AssetGcPolicy.isPlanStale(plannedGeneration = 1, currentGeneration = 1))
        assertFalse(
            "清单代数更新(不应发生)时不判作废",
            AssetGcPolicy.isPlanStale(plannedGeneration = 2, currentGeneration = 1),
        )
    }
}
