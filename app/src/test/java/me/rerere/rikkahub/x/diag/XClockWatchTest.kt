package me.rerere.rikkahub.x.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间跳变检测的自检。
 *
 * ## 它守的是「时间线能不能按顺序读」
 *
 * 事件行上的 `at` 是墙上时钟,而它可以被改。一旦往回跳,后来的记录会出现在更早的位置
 * —— 而读的人正是**按顺序**推因果的,于是会得出错误结论(仓库里那条既有判断:
 * 「**乱序比缺时间更坏**」)。故这里逐条钉住:
 *
 * · 什么算跳变(阈值),什么只是正常抖动;
 * · **一次跳变只报一次**(否则连发几十条说的是同一件事,变成噪声 → 没人再看);
 * · 以及冷却期内**仍然更新基准**(否则冷却一过会把整段漂移一次性报成一次大跳)。
 */
class XClockWatchTest {

    private val threshold = XClockWatch.JUMP_THRESHOLD_MS
    private val cooldown = XClockWatch.COOLDOWN_MS

    // ────────────────────────────────────
    // 什么算跳变
    // ────────────────────────────────────

    @Test
    fun `the first reading never reports`() {
        // 没有上一次读数就没有「增量」可言。首条记录不该报跳变。
        assertNull(XClockWatch.Watch().observe(wall = 1_000_000, mono = 5_000))
    }

    @Test
    fun `two clocks advancing together is normal`() {
        val w = XClockWatch.Watch()
        w.observe(1_000_000, 5_000)
        // 墙上走 100ms,单调也走 100ms → 无漂移
        assertNull(w.observe(1_000_100, 5_100))
        assertEquals(0, w.jumps)
    }

    @Test
    fun `ordinary jitter below the threshold is ignored`() {
        val w = XClockWatch.Watch()
        w.observe(0, 0)
        // 差 1.5 秒 —— 低于阈值(调度抖动、NTP 微调都在这量级),不该报。
        assertNull(w.observe(1_500, 0))
        assertEquals(0, w.jumps)
    }

    @Test
    fun `a backward jump is reported and its sign is negative`() {
        // ⚠️ 往回跳才是危险的那种(时间线乱序),故正负号必须能区分出来。
        val w = XClockWatch.Watch()
        w.observe(1_000_000, 5_000)
        val jump = w.observe(1_000_000 - 60_000, 5_000 + 10)

        assertTrue("往回跳 60 秒应被判为跳变", jump != null)
        assertEquals("漂移应为负", -60_010L, jump!!.driftMs)
        assertEquals(1, w.jumps)
    }

    @Test
    fun `a forward jump is reported and its sign is positive`() {
        val w = XClockWatch.Watch()
        w.observe(1_000_000, 5_000)
        val jump = w.observe(1_000_000 + 3_600_000, 5_000 + 10)

        assertTrue(jump != null)
        assertEquals(3_599_990L, jump!!.driftMs)
    }

    // ────────────────────────────────────
    // 一次跳变只报一次(否则就是噪声)
    // ────────────────────────────────────

    @Test
    fun `a jump is a one-shot, not a permanent state`() {
        // 跳变体现在**相邻两次读数之差**里,故它只出现在那一次比较里。
        // ⚠️ 若把基准更新写错(比如超阈值时不更新),它会每读一次报一次 ——
        //    连发几十条说的是同一件事,而噪声的下场是没人再看这个事件。
        val w = XClockWatch.Watch()
        w.observe(0, 0)
        assertTrue(w.observe(1_000_000, 10) != null)   // 跳(墙上跑飞了)
        assertEquals(1, w.jumps)

        assertNull("跳完之后恢复同步,不该继续报", w.observe(1_000_100, 110))
        assertNull(w.observe(1_000_200, 210))
        assertEquals(1, w.jumps)
    }

    @Test
    fun `a jump during the cooldown is counted but not reported`() {
        // 系统在做**逐步**校时(一次跳几百毫秒、连着跳几十次)时,它们说的是同一件事。
        val w = XClockWatch.Watch()
        w.observe(0, 0)
        assertTrue("第一次要报", w.observe(100_000, 10) != null)

        // 冷却期内再跳:计数 +1,但**不再报**
        val second = w.observe(200_000, 20)
        assertNull("冷却期内不该再报", second)
        assertEquals("但计数要涨(调用方据此知道发生了多次)", 2, w.jumps)
    }

    @Test
    fun `the baseline keeps updating during the cooldown`() {
        // ⚠️ 这条是**最容易写错**的一处:若冷却期内不更新基准,那么冷却一过会把
        //    「整段累积的漂移」一次性报成一次大跳 —— 而那个数是没有意义的
        //    (真实的几次小跳早就在冷却期里过去了)。
        val w = XClockWatch.Watch()
        w.observe(0, 0)
        w.observe(100_000, 10)      // 报#1,进入冷却
        w.observe(200_000, 20)      // 冷却中:跳过,但基准**必须**更新到 (200_000, 20)

        // 冷却一过,两个时钟**同步**前进(增量相等 → 漂移为 0)—— 不该报任何东西。
        // 若基准没在冷却期里更新(仍停在 100_000/10),这里算出的漂移会是 ~99990ms 而**误报**,
        // 于是这条断言正好把那一处错挡下来。
        val after = w.observe(200_000 + cooldown + 100, 20 + cooldown + 100)
        assertNull("冷却结束后同步前进不该报(说明基准一直在更新)", after)
    }

    @Test
    fun `in cooldown reflects the reported time`() {
        val w = XClockWatch.Watch()
        w.observe(0, 0)
        w.observe(100_000, 10)
        assertTrue("刚报过,应在冷却中", w.inCooldown(10 + cooldown - 1))
        assertFalse("冷却已过", w.inCooldown(10 + cooldown))
    }

    // ────────────────────────────────────
    // 阈值是可配的(测试用)
    // ────────────────────────────────────

    @Test
    fun `the threshold is configurable`() {
        val w = XClockWatch.Watch(thresholdMs = 10_000)
        w.observe(0, 0)
        assertNull("5 秒在 10 秒阈值内", w.observe(5_000, 0))
        assertTrue("15 秒超了", w.observe(20_000, 0) != null)
        assertEquals("默认阈值仍是 2000ms(见类注释的理由)", 2_000L, threshold)
    }

    // ────────────────────────────────────
    // 措辞:方向必须能分辨(往回跳才危险)
    // ────────────────────────────────────

    @Test
    fun `the description distinguishes backward from forward`() {
        val backward = XClockWatch.describe(XClockWatch.Jump(-60_000, 1))
        val forward = XClockWatch.describe(XClockWatch.Jump(60_000, 1))

        assertTrue("往回跳必须点明会让时间线乱序:实得 $backward", backward.contains("乱序"))
        assertFalse("往前跳不该说乱序:实得 $forward", forward.contains("乱序"))
        assertTrue("两者措辞应可分辨", backward != forward)
    }

    @Test
    fun `the description mentions the repeat count only when repeated`() {
        val first = XClockWatch.describe(XClockWatch.Jump(3_000, 1))
        val third = XClockWatch.describe(XClockWatch.Jump(3_000, 3))

        assertFalse("第一次不该提「第 N 次」:实得 $first", first.contains("第 1 次"))
        assertTrue("反复发生时要说出来:实得 $third", third.contains("第 3 次"))
    }
}
