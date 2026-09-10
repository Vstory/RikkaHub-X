package me.rerere.rikkahub.x.compress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩请求字符预算单测(X 定制压缩链基建)。
 *
 * 回归点:预算必须随模型窗口缩放,且受硬上限约束 —— 上游按「消息条数 256」
 * 分块与窗口无关,是超限失败的直接成因。
 */
class CompressBudgetTest {

    @Test
    fun `null or non positive window falls back to conservative default`() {
        val expected = (32_000 * 0.7 * 1.6).toInt() // 35840
        assertEquals(expected, CompressBudget.requestCharBudget(null))
        assertEquals(expected, CompressBudget.requestCharBudget(0))
        assertEquals(expected, CompressBudget.requestCharBudget(-1))
    }

    @Test
    fun `budget scales with window`() {
        assertEquals((8_000 * 0.7 * 1.6).toInt(), CompressBudget.requestCharBudget(8_000))
        assertEquals((32_000 * 0.7 * 1.6).toInt(), CompressBudget.requestCharBudget(32_000))
        // 128k 窗口换算 143360 字符,已越过硬上限 → 必须被截到 SAFE_REQUEST_CHARS
        assertEquals(CompressBudget.SAFE_REQUEST_CHARS, CompressBudget.requestCharBudget(128_000))
    }

    @Test
    fun `huge window is capped by hard safe limit`() {
        assertEquals(
            CompressBudget.SAFE_REQUEST_CHARS,
            CompressBudget.requestCharBudget(1_000_000),
        )
        assertEquals(
            CompressBudget.SAFE_REQUEST_CHARS,
            CompressBudget.requestCharBudget(Int.MAX_VALUE),
        )
    }

    @Test
    fun `budget is always positive for tiny windows`() {
        assertTrue(CompressBudget.requestCharBudget(1) >= 1)
    }

    @Test
    fun `estimateTokens counts cjk denser than latin`() {
        val cjk = CompressBudget.estimateTokens("上下文压缩")
        val latin = CompressBudget.estimateTokens("context")
        assertTrue("同等字符数下中文 token 估算应不低于英文", cjk >= latin)
        assertEquals(0, CompressBudget.estimateTokens(""))
    }
}
