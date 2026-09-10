package me.rerere.rikkahub.x.compress

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩分块与合并打包单测(X 定制压缩链基建)。
 *
 * 回归点:上游按「消息条数 256」二分且多块不合并;X 版按字符预算打包,
 * 并对摘要做多层合并打包。另验证单条超长行切分不产生孤立代理字符。
 */
class CompressChunkerTest {

    private val emoji = "\uD83D\uDE00"

    @Test
    fun `chunks messages within budget`() {
        val messages = (1..5).map { UIMessage.user("m$it") }
        val chunks = chunkMessagesForCompress(messages, maxChars = 25)
        assertTrue("应产生多块", chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue("块长度不得超预算: ${chunk.length}", chunk.length <= 25)
        }
        // 内容不丢失(逐条消息文本都应出现)
        val joined = chunks.joinToString("\n\n")
        (1..5).forEach { assertTrue(joined.contains("m$it")) }
    }

    @Test
    fun `single message fits in one chunk`() {
        val chunks = chunkMessagesForCompress(listOf(UIMessage.user("hi")), maxChars = 100)
        assertEquals(1, chunks.size)
        assertEquals("[USER]: hi", chunks.single())
    }

    @Test
    fun `over long line is split without lone surrogates`() {
        val text = "a".repeat(100) + emoji + "b".repeat(100)
        val chunks = chunkMessagesForCompress(listOf(UIMessage.user(text)), maxChars = 50)
        assertTrue("超长行应被切分", chunks.size > 1)
        chunks.forEach { chunk ->
            assertFalse("块不应以孤立高代理结尾", chunk.last().code in 0xD800..0xDBFF)
            assertFalse("块不应以孤立低代理开头", chunk.first().code in 0xDC00..0xDFFF)
        }
    }

    @Test
    fun `blank messages are skipped`() {
        val messages = listOf(UIMessage.user(""), UIMessage.user("real"))
        val chunks = chunkMessagesForCompress(messages, maxChars = 100)
        assertEquals(1, chunks.size)
        assertTrue(chunks.single().contains("real"))
    }

    @Test
    fun `line longer than line budget is safely truncated`() {
        val message = UIMessage.user("x".repeat(COMPRESS_LINE_MAX_CHARS + 500))
        val line = UiMessageCompressLine(message)
        assertTrue(line.length <= COMPRESS_LINE_MAX_CHARS + 3) // + "..."
        assertTrue(line.endsWith("..."))
    }

    @Test
    fun `non positive budget yields no chunks`() {
        assertTrue(chunkMessagesForCompress(listOf(UIMessage.user("a")), 0).isEmpty())
        assertTrue(chunkMessagesForCompress(listOf(UIMessage.user("a")), -1).isEmpty())
    }

    @Test
    fun `chunkPlainTexts packs by budget`() {
        val packed = chunkPlainTexts(listOf("aaa", "bbb", "ccc"), maxChars = 8)
        assertEquals(2, packed.size)
        assertEquals("aaa\n\nbbb", packed[0])
        assertEquals("ccc", packed[1])
    }

    @Test
    fun `chunkPlainTexts splits oversized entry safely`() {
        val packed = chunkPlainTexts(listOf("z".repeat(25)), maxChars = 10)
        assertEquals(3, packed.size)
        packed.forEach { assertTrue(it.length <= 10) }
        assertEquals("z".repeat(25), packed.joinToString(""))
    }

    @Test
    fun `chunkPlainTexts skips empty entries`() {
        assertEquals(listOf("a"), chunkPlainTexts(listOf("", "a", ""), maxChars = 10))
        assertTrue(chunkPlainTexts(emptyList(), maxChars = 10).isEmpty())
    }
}
