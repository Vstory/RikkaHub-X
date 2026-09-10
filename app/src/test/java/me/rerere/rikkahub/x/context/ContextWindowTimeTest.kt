// 容量表时间显示的格式化测试。
//
// 两个时间都按 `yyyy-MM-dd HH:mm:ss` 显示(精确到秒),且必须**转换到显示时区** ——
// 表内 `updatedAt` 带的是数据维护者的时区偏移,直接截字符串会让不同时区的人看到错的时刻。
// 故这里显式传入时区,断言不受运行环境默认时区影响。
package me.rerere.rikkahub.x.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.time.ZoneId
import org.junit.Test

class ContextWindowTimeTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `table timestamp is rendered in the display zone`() {
        // 同一时刻,在 +08:00 与 UTC 下分别是 23:30:30 与 15:30:30
        assertEquals(
            "2026-09-10 23:30:30",
            formatTableUpdatedAt("2026-09-10T23:30:30+08:00", shanghai),
        )
        assertEquals(
            "2026-09-10 15:30:30",
            formatTableUpdatedAt("2026-09-10T23:30:30+08:00", utc),
        )
    }

    @Test
    fun `Z suffixed timestamps are supported`() {
        assertEquals(
            "2026-09-10 23:30:30",
            formatTableUpdatedAt("2026-09-10T15:30:30Z", shanghai),
        )
    }

    /** 秒以下的精度不该让解析失败(表里出现毫秒也要能显示)。 */
    @Test
    fun `sub second precision is accepted`() {
        assertEquals(
            "2026-09-10 23:30:30",
            formatTableUpdatedAt("2026-09-10T23:30:30.123+08:00", shanghai),
        )
    }

    /** 解析失败必须返回 null,由调用方回退显示原始串 —— 不能抛异常,也不能假装成功。 */
    @Test
    fun `unparseable timestamp yields null instead of throwing`() {
        assertNull(formatTableUpdatedAt("", shanghai))
        assertNull(formatTableUpdatedAt("2026-09-10", shanghai))
        assertNull(formatTableUpdatedAt("not a time", shanghai))
    }

    @Test
    fun `local refresh time is rendered with seconds`() {
        // epochMillis 取自 formatRefreshedAt 往返,确保断言与本地时区无关
        val millis = java.time.OffsetDateTime.parse("2026-09-10T23:30:30+08:00")
            .toInstant().toEpochMilli()
        assertEquals("2026-09-10 23:30:30", formatRefreshedAt(millis, shanghai))
        assertEquals("2026-09-10 15:30:30", formatRefreshedAt(millis, utc))
    }
}
