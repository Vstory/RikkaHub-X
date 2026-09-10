package me.rerere.rikkahub.x.compress

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 压缩超限识别与递归二分重试单测(X 定制压缩链基建)。
 *
 * 回归点:上游对供应商「上下文超限」无任何兜底 → 长对话压缩整次失败。
 * 本组用例覆盖:错误识别、二分重试成功、非超限不重试、额度/下限守卫。
 */
class CompressRetryTest {

    // ---- isContextLengthError ----

    @Test
    fun `detects context length errors from provider body`() {
        assertTrue(
            isContextLengthError(
                Exception(
                    "Failed to get response: 400 {\"error\":{\"message\":" +
                        "\"This model's maximum context length is 8192 tokens\"}}",
                ),
            ),
        )
        assertTrue(isContextLengthError(Exception("prompt is too long")))
        assertTrue(isContextLengthError(Exception("context_length_exceeded")))
        assertTrue(isContextLengthError(Exception("Please reduce the length of the messages")))
    }

    @Test
    fun `does not treat auth or generic errors as context length`() {
        assertFalse(isContextLengthError(Exception("Failed to get response: 401 Unauthorized")))
        assertFalse(isContextLengthError(Exception("Connection timed out")))
        assertFalse(isContextLengthError(IllegalStateException("Failed to generate compressed summary")))
        // 仅配置类 max_tokens 错误,不含"超"语义 → 不得误判
        assertFalse(isContextLengthError(Exception("invalid max_tokens value")))
    }

    @Test
    fun `combined max_tokens overflow wording is treated as context length`() {
        assertTrue(isContextLengthError(Exception("max_tokens exceeds the limit")))
    }

    // ---- summarizeWithContextRetry ----

    @Test
    fun `splits and retries when context length error occurs`() = runBlocking {
        var calls = 0
        val big = "x".repeat(4_000)
        val result = summarizeWithContextRetry(
            text = big,
            minSplitChars = 100,
        ) { input ->
            calls++
            if (input.length > 2_000) throw Exception("context length exceeded")
            "S(${input.length})"
        }
        // 1 次整体失败 + 2 次对半成功 + 1 次合并
        assertEquals(4, calls)
        // 两半摘要 "S(2000)"(7 字符)拼接后长度 16 → 合并调用返回 S(16),
        // 说明最终产物是「合并后的单段摘要」而非两段独立摘要
        assertEquals("S(16)", result)
    }

    @Test
    fun `succeeds without splitting when no error`() = runBlocking {
        var calls = 0
        val result = summarizeWithContextRetry(text = "hello") { input ->
            calls++
            "ok:$input"
        }
        assertEquals(1, calls)
        assertEquals("ok:hello", result)
    }

    @Test
    fun `rethrows non context length errors immediately`() = runBlocking {
        var calls = 0
        try {
            summarizeWithContextRetry(text = "x".repeat(4_000)) {
                calls++
                throw IllegalStateException("boom")
            }
            fail("expected rethrow")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals("非超限错误不得重试", 1, calls)
    }

    @Test
    fun `stops when split budget exhausted`() = runBlocking {
        var calls = 0
        try {
            summarizeWithContextRetry(
                text = "x".repeat(100_000),
                maxSplits = 1,
                minSplitChars = 100,
            ) {
                calls++
                throw Exception("context length exceeded")
            }
            fail("expected throw")
        } catch (e: Exception) {
            // 预期:额度耗尽后原样抛出
        }
        // 首次失败 + 左半段尝试一次(额度已用尽 → 直接抛)
        assertEquals(2, calls)
    }

    @Test
    fun `does not split below minimum split size`() = runBlocking {
        var calls = 0
        try {
            summarizeWithContextRetry(
                text = "x".repeat(300),
                minSplitChars = 512,
            ) {
                calls++
                throw Exception("context length exceeded")
            }
            fail("expected throw")
        } catch (e: Exception) {
            // 预期:半段 150 < 512 → 不再切分,直接抛
        }
        assertEquals(1, calls)
    }

    @Test
    fun `invokes onSplitRetry hook`() = runBlocking {
        val retried = mutableListOf<Int>()
        summarizeWithContextRetry(
            text = "x".repeat(4_000),
            minSplitChars = 100,
            onSplitRetry = { _, chars -> retried.add(chars) },
        ) { input ->
            if (input.length > 2_000) throw Exception("context length exceeded")
            "S(${input.length})"
        }
        assertEquals(listOf(4_000), retried)
    }
}
