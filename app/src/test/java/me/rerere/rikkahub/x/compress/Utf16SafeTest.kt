package me.rerere.rikkahub.x.compress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UTF-16 安全切割单测(X 定制压缩链基建)。
 *
 * 核心回归点:上游 `String.take(n)` 按码元截断会切出孤立代理字符,
 * 本组用例证明 X 版切割不会(并显式对照上游行为)。
 */
class Utf16SafeTest {

    /** "😀" = 高代理 U+D83D + 低代理 U+DE00,占 2 个 UTF-16 码元。 */
    private val emoji = "\uD83D\uDE00"

    @Test
    fun `upstream take produces lone high surrogate while X stays safe`() {
        val text = "ab" + emoji + "cd" // 码元长度 6

        // 上游行为:take(3) 恰好切在代理对中间 → 产出孤立高代理
        val upstream = text.take(3)
        assertEquals(3, upstream.length)
        assertTrue("上游应切出孤立高代理", upstream.last().code in 0xD800..0xDBFF)

        // X 行为:回退到安全边界,长度 2
        val safe = truncateHeadUtf16Safe(text, 3)
        assertEquals("ab", safe)
        assertFalse("X 不应以孤立高代理结尾", safe.lastOrNull()?.code in 0xD800..0xDBFF)
    }

    @Test
    fun `truncateHead keeps intact code points`() {
        val text = "ab" + emoji + "cd"
        assertEquals("ab", truncateHeadUtf16Safe(text, 3))
        assertEquals("ab" + emoji, truncateHeadUtf16Safe(text, 4))
        assertEquals(text, truncateHeadUtf16Safe(text, 99))
        assertEquals("", truncateHeadUtf16Safe(text, 0))
        assertEquals("", truncateHeadUtf16Safe(text, -5))
    }

    @Test
    fun `truncateTail keeps intact code points`() {
        val text = "ab" + emoji + "cd"
        // 取尾 3 码元会切在代理对中间 → 前进 1 位,得到 "cd"
        assertEquals("cd", truncateTailUtf16Safe(text, 3))
        assertEquals(emoji + "cd", truncateTailUtf16Safe(text, 4))
        assertEquals(text, truncateTailUtf16Safe(text, 99))
        assertEquals("", truncateTailUtf16Safe(text, 0))
    }

    @Test
    fun `splitChunks never breaks surrogate pair`() {
        val text = "0123456789" + emoji + "0123456789"
        val chunks = splitUtf16SafeChunks(text, 11)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
        chunks.forEach { chunk ->
            assertFalse(
                "块不应以孤立高代理结尾",
                chunk.last().code in 0xD800..0xDBFF,
            )
            assertFalse(
                "块不应以孤立低代理开头",
                chunk.first().code in 0xDC00..0xDFFF,
            )
        }
    }

    @Test
    fun `splitHalves returns null when unsplittable`() {
        assertEquals(null, splitUtf16SafeHalves(""))
        assertEquals(null, splitUtf16SafeHalves("a"))
        val (left, right) = splitUtf16SafeHalves("abcd")!!
        assertEquals("ab", left)
        assertEquals("cd", right)
    }

    @Test
    fun `splitChunks handles empty and non positive budget`() {
        assertTrue(splitUtf16SafeChunks("", 10).isEmpty())
        assertTrue(splitUtf16SafeChunks("abc", 0).isEmpty())
        assertTrue(splitUtf16SafeChunks("abc", -1).isEmpty())
        assertEquals(listOf("abc"), splitUtf16SafeChunks("abc", 10))
    }
}
