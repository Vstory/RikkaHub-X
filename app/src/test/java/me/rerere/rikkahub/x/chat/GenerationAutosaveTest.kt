// 生成过程自动保存:间隔钳制契约的守护测试。
//
// 为什么要有这层:间隔是**用户可配数值**,来源不可信(手输、备份导入、历史默认值)。
// 而落库是**全量行写**(整个 messageNodes 序列化),会话越大越贵 ——
// 一旦钳制失效(比如被改成 0 或负数),ticker 会退化成**无间隔死循环写库**。
// 因此本测试把「消费端必须把任意输入收敛到安全区间」钉死为契约;
// 常量本身的值也一并断言,避免有人悄悄改宽区间而不触发任何告警。
package me.rerere.rikkahub.x.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationAutosaveTest {

    // ─────────────── 常量契约 ───────────────

    /** 区间与默认值即对外契约:改动它们会让 UI 提示文案与用户预期一起变化。 */
    @Test
    fun `interval bounds and default are pinned`() {
        assertEquals(5, GenerationAutosave.MIN_INTERVAL_SECONDS)
        assertEquals(600, GenerationAutosave.MAX_INTERVAL_SECONDS)
        assertEquals(10, GenerationAutosave.DEFAULT_INTERVAL_SECONDS)
    }

    /** 默认值必须落在合法区间内,否则开箱即被钳到另一个值,与设置页显示不符。 */
    @Test
    fun `default interval lies inside bounds`() {
        val d = GenerationAutosave.DEFAULT_INTERVAL_SECONDS
        assertTrue(d >= GenerationAutosave.MIN_INTERVAL_SECONDS)
        assertTrue(d <= GenerationAutosave.MAX_INTERVAL_SECONDS)
    }

    /** 下限必须为正:0 或负数会让 delay(0) 退化成死循环写库。 */
    @Test
    fun `lower bound is strictly positive`() {
        assertTrue(GenerationAutosave.MIN_INTERVAL_SECONDS > 0)
    }

    // ─────────────── 钳制:下界 ───────────────

    /** 0 / 负数 / 小于下限 → 一律抬到下限(这是防死循环写库的关键一条)。 */
    @Test
    fun `values below lower bound are raised to it`() {
        val min = GenerationAutosave.MIN_INTERVAL_SECONDS
        assertEquals(min, GenerationAutosave.clampGenerationAutosaveInterval(0))
        assertEquals(min, GenerationAutosave.clampGenerationAutosaveInterval(-1))
        assertEquals(min, GenerationAutosave.clampGenerationAutosaveInterval(-100))
        assertEquals(min, GenerationAutosave.clampGenerationAutosaveInterval(Int.MIN_VALUE))
        assertEquals(min, GenerationAutosave.clampGenerationAutosaveInterval(min - 1))
    }

    // ─────────────── 钳制:上界 ───────────────

    /** 超过上限 → 收到上限(再大则单次丢失量过多,失去保护意义)。 */
    @Test
    fun `values above upper bound are lowered to it`() {
        val max = GenerationAutosave.MAX_INTERVAL_SECONDS
        assertEquals(max, GenerationAutosave.clampGenerationAutosaveInterval(max + 1))
        assertEquals(max, GenerationAutosave.clampGenerationAutosaveInterval(max + 100))
        assertEquals(max, GenerationAutosave.clampGenerationAutosaveInterval(66_666))
        assertEquals(max, GenerationAutosave.clampGenerationAutosaveInterval(Int.MAX_VALUE))
    }

    // ─────────────── 钳制:区间内原样通过 ───────────────

    /** 区间内的值不被打扰 —— 用户调大间隔的诉求必须照做。 */
    @Test
    fun `values inside bounds pass through unchanged`() {
        val min = GenerationAutosave.MIN_INTERVAL_SECONDS
        val max = GenerationAutosave.MAX_INTERVAL_SECONDS
        assertEquals(min, GenerationAutosave.clampGenerationAutosaveInterval(min))
        assertEquals(min + 1, GenerationAutosave.clampGenerationAutosaveInterval(min + 1))
        assertEquals(10, GenerationAutosave.clampGenerationAutosaveInterval(10))
        assertEquals(30, GenerationAutosave.clampGenerationAutosaveInterval(30))
        assertEquals(60, GenerationAutosave.clampGenerationAutosaveInterval(60))
        assertEquals(max - 1, GenerationAutosave.clampGenerationAutosaveInterval(max - 1))
        assertEquals(max, GenerationAutosave.clampGenerationAutosaveInterval(max))
    }

    /** 全区间扫描:输出恒在区间内,且不改变已合法值(幂等)。 */
    @Test
    fun `clamping is idempotent across the range`() {
        val min = GenerationAutosave.MIN_INTERVAL_SECONDS
        val max = GenerationAutosave.MAX_INTERVAL_SECONDS
        // 覆盖区间两侧与区间内的代表性取值
        val samples = listOf(Int.MIN_VALUE, -1, 0) +
            (min..max).toList() +
            listOf(max + 1, Int.MAX_VALUE)
        samples.forEach { raw ->
            val once = GenerationAutosave.clampGenerationAutosaveInterval(raw)
            assertTrue("钳制结果越界: raw=$raw -> $once", once in min..max)
            assertEquals("钳制不幂等: raw=$raw", once, GenerationAutosave.clampGenerationAutosaveInterval(once))
        }
    }

    // ─────────────── UI 文案同源 ───────────────

    /** 区间提示由常量生成,避免设置页文案与实现脱节。 */
    @Test
    fun `range description is derived from bounds`() {
        assertEquals(
            "${GenerationAutosave.MIN_INTERVAL_SECONDS} ~ ${GenerationAutosave.MAX_INTERVAL_SECONDS}",
            GenerationAutosave.intervalRangeDescription(),
        )
    }
}
