package me.rerere.rikkahub.x.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 进度换算的边界。
 *
 * 这几条看着琐碎,但每条都对着一个**会让用户误判**的显示结果:
 * 显示 0% 的死条、超出去的满条、以及永远差一截到不了头的条。
 */
class XExportProgressTest {

    @Test
    fun `unknown total yields null so the ui can show a spinner`() {
        // 算不出总量时必须返回 null 而不是 0:调用方据此选「转圈」。
        // 返回 0 的话会显示一个永远 0% 的条 —— 那条进度存在的意义正是别让人以为卡死。
        assertNull(percentOf(0, 0))
        assertNull(percentOf(123, 0))
        assertNull(percentOf(0, -5))
    }

    @Test
    fun `nothing read yet is zero`() {
        assertEquals(0, percentOf(0, 100))
    }

    @Test
    fun `exact fractions`() {
        assertEquals(50, percentOf(50, 100))
        assertEquals(100, percentOf(100, 100))
        assertEquals(25, percentOf(1, 4))
    }

    @Test
    fun `tiny fraction floors to zero instead of rounding up`() {
        // 75KB 的日志刚读到 1 字节 → 0%,而不是四舍五入成 1%(会显得进度凭空跳)。
        assertEquals(0, percentOf(1, 75_000))
    }

    @Test
    fun `reading past the total is clamped to full`() {
        // 真实情形:日志文件正被捕获线程追加,读到的字节会比开始时量到的多。
        // 不夹的话进度会跑出界。
        assertEquals(100, percentOf(200, 100))
        assertEquals(100, percentOf(Long.MAX_VALUE / 100, 1))
    }

    @Test
    fun `a 200 MB file does not overflow`() {
        // 乘法先做(processed * 100),故大文件必须仍落在 Long 里。
        val total = 200L * 1024 * 1024
        assertEquals(50, percentOf(total / 2, total))
        assertEquals(100, percentOf(total, total))
    }
}
