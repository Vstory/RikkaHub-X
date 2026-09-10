// 多源候选择新与处置策略的测试。
//
// 覆盖的是**实际踩过的坑**:推完 main 后某个 CDN 仍在供上一版,而 HTTP 依然是 200 ——
// 若按"主源成功就不看备源"的串行回退,拿到的就是旧表。故选新必须按 updatedAt 判,而非按源优先级。
package me.rerere.rikkahub.x.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowSourceSelectionTest {

    private val local = ContextWindowTable(updatedAt = "2026-09-10T20:00:00+08:00")
    private val newer = ContextWindowTable(updatedAt = "2026-09-10T23:30:30+08:00")
    private val older = ContextWindowTable(updatedAt = "2026-09-10T09:00:00+08:00")
    private val legacy = ContextWindowTable(updatedAt = "2026-09-10")

    private fun candidate(name: String, table: ContextWindowTable) =
        TableCandidate(source = name, raw = """{"updatedAt":"${table.updatedAt}"}""", table = table)

    // ── 新鲜度比较 ──────────────────────────────────────────────

    /** 旧的"只到日"格式必须能和带秒格式比出新旧 —— 否则滞后源供的旧格式会被当成最新。 */
    @Test
    fun `new format beats same-day legacy format`() {
        assertTrue(compareTableFreshness(newer, legacy) > 0)
        assertTrue(compareTableFreshness(legacy, newer) < 0)
    }

    /** 比较的是**时刻**而非文本:+08:00 的 23:30 是 UTC 15:30,早于 UTC 16:00。 */
    @Test
    fun `comparison is by instant not by text`() {
        val shanghai = ContextWindowTable(updatedAt = "2026-09-10T23:30:30+08:00")
        val utc = ContextWindowTable(updatedAt = "2026-09-10T16:00:00+00:00")
        assertTrue(compareTableFreshness(utc, shanghai) > 0)
    }

    /** 无法解析的 updatedAt 视为最旧,不得压过可解析的。 */
    @Test
    fun `unparseable version ranks lowest`() {
        assertTrue(compareTableFreshness(newer, ContextWindowTable(updatedAt = "")) > 0)
    }

    // ── 择新 ────────────────────────────────────────────────────

    /** 核心场景:一个源滞后(旧),另一个源已更新 → 必须选新的那个,与源顺序无关。 */
    @Test
    fun `newer source wins regardless of order`() {
        val picked = newestByTable(listOf(candidate("stale", older), candidate("fresh", newer))) { it.table }
        assertEquals("fresh", picked?.source)
    }

    /** 平局取靠前的(列表顺序即优先级),保证结果确定。 */
    @Test
    fun `tie keeps the earlier candidate`() {
        val same = ContextWindowTable(updatedAt = newer.updatedAt)
        val picked = newestByTable(listOf(candidate("first", same), candidate("second", same))) { it.table }
        assertEquals("first", picked?.source)
    }

    // ── 处置策略 ────────────────────────────────────────────────

    @Test
    fun `no candidates yields NoCandidate`() {
        assertEquals(SelectionOutcome.NoCandidate, selectTableSource(emptyList(), local))
    }

    /** 本地无表 → 任何可用候选都接受(首启场景)。 */
    @Test
    fun `accepts any candidate when nothing cached`() {
        val outcome = selectTableSource(listOf(candidate("raw", older)), local = null)
        assertTrue(outcome is SelectionOutcome.Use)
    }

    /** 有更新的一版 → 采用它,且原样带回该源的原文(落盘要用原文,不能重新序列化)。 */
    @Test
    fun `picks the newest when one source lags`() {
        val stale = candidate("raw.githubusercontent.com", legacy)
        val fresh = candidate("raw.githack.com", newer)
        val outcome = selectTableSource(listOf(stale, fresh), local)
        assertTrue(outcome is SelectionOutcome.Use)
        assertEquals(fresh.raw, (outcome as SelectionOutcome.Use).candidate.raw)
    }

    /** 与本地同版 → 接受(不阻止同时间戳的数据修正被取回)。 */
    @Test
    fun `accepts same version as local`() {
        assertTrue(selectTableSource(listOf(candidate("raw", local)), local) is SelectionOutcome.Use)
    }

    /**
     * 各源都比本地旧、且相互**不一致** → 判为 CDN 滞后,保持本地。
     * 拿旧表盖掉手上的新表是最难察觉的坏结果。
     */
    @Test
    fun `keeps local when sources lag and disagree`() {
        val outcome = selectTableSource(
            listOf(candidate("a", older), candidate("b", legacy)),
            local,
        )
        assertEquals(SelectionOutcome.KeepLocal, outcome)
    }

    /**
     * 单个源比本地旧 → **保持本地**。
     *
     * 只有单源可达时无从印证它是否只是滞后,若贸然采用,就会出现"raw 恰好是可达的那个、
     * 又恰好滞后"时把新表换成旧表 —— 本功能要解决的正是这种情形。
     */
    @Test
    fun `keeps local when the only reachable source is stale`() {
        assertEquals(SelectionOutcome.KeepLocal, selectTableSource(listOf(candidate("raw", older)), local))
    }

    /**
     * 各源**一致**指向同一份更旧的表 → 判为整表回退,采用它。
     * 否则远端 revert 掉一版错误数据后,App 会永久抱着那份已撤销的数据。
     */
    @Test
    fun `accepts unanimous older version as a rollback`() {
        val outcome = selectTableSource(
            listOf(candidate("a", older), candidate("b", older), candidate("c", older)),
            local,
        )
        assertTrue(outcome is SelectionOutcome.Use)
        assertEquals(older.updatedAt, (outcome as SelectionOutcome.Use).candidate.table.updatedAt)
    }

    /** 一致性判据看**时刻**而非文本:格式不同但时刻相同仍算一致(CDN 会改写格式)。 */
    @Test
    fun `agreement is judged by instant not by text`() {
        val shanghai = ContextWindowTable(updatedAt = "2026-09-10T10:00:00+08:00")
        val utc = ContextWindowTable(updatedAt = "2026-09-10T02:00:00+00:00")
        assertTrue(allSameVersion(listOf(candidate("a", shanghai), candidate("b", utc))) { it.table })
        assertFalse(allSameVersion(listOf(candidate("a", shanghai), candidate("b", legacy))) { it.table })
    }
}
