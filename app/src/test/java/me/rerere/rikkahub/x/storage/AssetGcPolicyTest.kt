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
    fun `zero threshold expresses no observation window`() {
        // 回归守护:`decide` 曾给 candidateAt 一个「无门槛」的哨兵默认值
        // (Long.MAX_VALUE),结果恒真地卡在等待,与注释说的「立即成为候选」相反。
        // 现要求显式传值;「不设门槛」的正当写法是传 0 —— 这条用例把它钉住。
        assertEquals(
            GcDecision.CANDIDATE,
            AssetGcPolicy.decide(hasLiveReferences = false, nowMillis = now, candidateAt = 0),
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

    @Test
    fun `plan goes stale after a single re reference`() {
        // 界面在 T0 读出清单(代数 0);期间资产被重新引用一次 → 代数 1。
        // 此时用户仍拿 T0 的清单来删 —— 必须判作废,否则会按「闲置 30 天」删掉刚被用过的文件。
        val plannedFromUi = 0L
        val afterOneReReference = 1L
        assertTrue(
            "被重新引用一次后,旧清单必须失效",
            AssetGcPolicy.isPlanStale(plannedFromUi, afterOneReReference),
        )
    }

    // ---- 非活跃哨兵（代数的前提）----

    @Test
    fun `inactive sentinel is negative`() {
        // 哨兵必须是负值:它要能与**任何真实时间戳**区分开,
        // 而真实时间戳从 0 起算(epoch)。若哨兵取 0,「刚失去引用」与「被重新引用」就撞在一起。
        assertTrue(
            "哨兵必须为负,否则无法与真实时刻区分:${AssetGcPolicy.INACTIVE_FIRST_UNREFERENCED_AT}",
            AssetGcPolicy.INACTIVE_FIRST_UNREFERENCED_AT < 0,
        )
    }

    @Test
    fun `inactive sentinel is not considered active`() {
        // 这是「被重新引用过的资产不得进候选清单」的 Kotlin 侧判据。
        // 与 SQL 里的 `first_unreferenced_at > :inactiveAt` 等价 —— 两侧必须一致。
        assertFalse(
            "哨兵本身不是活跃状态",
            AssetGcPolicy.isActive(AssetGcPolicy.INACTIVE_FIRST_UNREFERENCED_AT),
        )
    }

    @Test
    fun `zero and later timestamps are active`() {
        assertTrue("epoch 是最早的合法时刻,应算活跃", AssetGcPolicy.isActive(0L))
        assertTrue("正常时刻应算活跃", AssetGcPolicy.isActive(1_700_000_000_000L))
    }

    @Test
    fun `real timestamps are never mistaken for inactive`() {
        // 反向确认:哨兵与真实时刻之间没有重叠区 —— 时钟回拨、时区问题都不会把
        // 一个真实时刻误判成「非活跃」。
        val plausibleEarliest = 0L
        assertTrue(
            "最早的可能时刻必须判为活跃",
            AssetGcPolicy.isActive(plausibleEarliest),
        )
        assertTrue(
            "哨兵必须小于最早的可能时刻",
            AssetGcPolicy.INACTIVE_FIRST_UNREFERENCED_AT < plausibleEarliest,
        )
    }
}
