package me.rerere.rikkahub.x.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境自述里**纯函数**部分的自检。
 *
 * [XDiagEnv.appLines] 要读 `PackageManager`,是 Android 框架查询、JVM 单测里跑不了
 * (与 `xAppLabel` 同一处境,靠真机确认)。故这里只锁能离线验的那两个:
 * 时长格式化与时间戳格式。
 *
 * ## 为什么专挑边界
 *
 * [XDiagEnv.durationText] 在 60 与 3600 处换单位,判据是 `>=`。这类「比较运算符方向 +
 * 边界相等」正是离线手算最容易看漏的地方 —— 写成 `>` 的话,恰好 60 秒会被显示成
 * 「60.0 秒」,而分钟档看起来像坏了。故把边界值本身钉进用例。
 */
class XDiagEnvTest {

    @Test
    fun `duration switches unit at 60 seconds`() {
        // 边界本身:60.0 必须进「分钟」档(`>=`,不是 `>`)
        assertEquals("恰好 60 秒应进分钟档", "1.0 分钟", XDiagEnv.durationText(60.0))
        // 边界内侧:仍是秒
        assertTrue(
            "59.9 秒应留在秒档",
            XDiagEnv.durationText(59.9).endsWith("秒"),
        )
    }

    @Test
    fun `duration switches unit at one hour`() {
        // 边界本身:3600.0 必须进「小时」档
        assertEquals("恰好 3600 秒应进小时档", "1.00 小时", XDiagEnv.durationText(3600.0))
        // 边界内侧:仍是分钟
        assertTrue(
            "3540 秒(59 分钟)应留在分钟档",
            XDiagEnv.durationText(3540.0).endsWith("分钟"),
        )
    }

    @Test
    fun `duration formats short spans with one decimal`() {
        assertEquals("0.0 秒", XDiagEnv.durationText(0.0))
        assertEquals("45.2 秒", XDiagEnv.durationText(45.2))
        // ⚠️ 这条是手算时抓到我自己写错的断言:158.9 秒 ≥ 60,进的是**分钟**档,
        //    不是「158.9 秒」。留在这里当反例 —— 实测那份日志正是 158.9 秒。
        assertEquals("158.9 秒应显示为分钟档", "2.6 分钟", XDiagEnv.durationText(158.9))
    }

    @Test
    fun `stamp is absolute and sortable`() {
        // 走真实时钟:只断言形状 —— 清单头需要绝对时间(带日期),与日志正文的
        // `HH:mm:ss.SSS` 不同,两者不能混用。
        val text = XDiagEnv.stamp(System.currentTimeMillis())
        assertTrue(
            "清单头的时间必须带日期,实际得到:$text",
            Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$""").matches(text),
        )
    }
}
