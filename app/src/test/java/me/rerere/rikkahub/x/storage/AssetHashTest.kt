package me.rerere.rikkahub.x.storage

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内容哈希单测（X 存储重构 P0 地基）。
 *
 * 守的是两件事：
 * ① **哈希正确性** —— 向量取自 SHA-256 标准测试值，写错算法/编码立刻红；
 * ② **流式读取完整性** —— 大文件分块读取必须与整体一致。这里用「每次只吐 7 字节」
 *    的流覆盖「read 返回小值」路径 —— 历史教训是把 `read == 0` 误当 EOF 会静默截断，
 *    而截断的哈希会**看起来完全正常**（64 位十六进制），只是对不上任何一份真内容。
 */
class AssetHashTest {

    // 标准测试向量（已用 sha256sum 独立核对）
    private val sha256OfEmpty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val sha256OfAbc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    // ---- 哈希值正确性 ----

    @Test
    fun `matches known vectors`() {
        assertEquals(sha256OfEmpty, AssetHash.of(ByteArray(0)))
        assertEquals(sha256OfAbc, AssetHash.of("abc".toByteArray()))
    }

    @Test
    fun `produces lowercase hex of fixed length`() {
        val hash = AssetHash.of("任意内容".toByteArray())
        assertEquals(AssetHash.HEX_LENGTH, hash.length)
        assertEquals("应全为小写十六进制", hash, hash.lowercase())
        assertTrue(AssetHash.isValid(hash))
    }

    // ---- 流式读取完整性 ----

    @Test
    fun `stream result equals byte array result for payload larger than read buffer`() {
        // 200_000 > 64KB 读缓冲,必然走多轮循环
        val payload = ByteArray(200_000) { (it % 128).toByte() }
        assertEquals(AssetHash.of(payload), AssetHash.of(ByteArrayInputStream(payload)))
    }

    @Test
    fun `stream result is identical when chunks are tiny`() {
        val payload = ByteArray(200_000) { (it % 128).toByte() }
        val expected = AssetHash.of(payload)
        assertEquals(expected, AssetHash.of(DripInputStream(payload, chunkBytes = 7)))
        assertEquals(expected, AssetHash.of(DripInputStream(payload, chunkBytes = 1)))
    }

    @Test
    fun `stream of empty input equals empty vector`() {
        assertEquals(sha256OfEmpty, AssetHash.of(ByteArrayInputStream(ByteArray(0))))
        assertEquals(sha256OfEmpty, AssetHash.of(DripInputStream(ByteArray(0), chunkBytes = 3)))
    }

    @Test
    fun `same content hashes identically regardless of extension`() {
        // 去重的根据是内容而非文件名 —— 这条是本设计的前提
        val a = AssetHash.of("same bytes".toByteArray())
        val b = AssetHash.of("same bytes".toByteArray())
        assertEquals(a, b)
    }

    // ---- isValid ----

    @Test
    fun `isValid rejects malformed hashes`() {
        assertTrue(AssetHash.isValid(sha256OfAbc))
        assertFalse("空串", AssetHash.isValid(""))
        assertFalse("长度不足", AssetHash.isValid(sha256OfAbc.dropLast(1)))
        assertFalse("长度超出", AssetHash.isValid(sha256OfAbc + "0"))
        assertFalse("含大写", AssetHash.isValid(sha256OfAbc.uppercase()))
        assertFalse("含非十六进制字符", AssetHash.isValid(sha256OfAbc.dropLast(1) + "z"))
    }

    // ---- 扩展名归一 ----

    @Test
    fun `normalizes extension`() {
        assertEquals("png", AssetHash.normalizeExtension("png"))
        assertEquals("png", AssetHash.normalizeExtension(".PNG"))
        assertEquals("jpeg", AssetHash.normalizeExtension("  JPEG  "))
        assertEquals("bin", AssetHash.normalizeExtension(null))
        assertEquals("bin", AssetHash.normalizeExtension(""))
        assertEquals("bin", AssetHash.normalizeExtension("."))
        // 复合扩展名超出白名单长度/字符集 → 回退,避免拼出可疑文件名
        assertEquals("bin", AssetHash.normalizeExtension("tar.gz"))
        assertEquals("bin", AssetHash.normalizeExtension("abcdefghi"))
        assertEquals("bin", AssetHash.normalizeExtension("../etc/passwd"))
    }

    // ---- 落盘路径 ----

    @Test
    fun `relative path shards by hash prefix`() {
        assertEquals(
            "assets/ba/78/$sha256OfAbc.png",
            AssetHash.relativePath(sha256OfAbc, "png"),
        )
        assertEquals(
            "assets/ba/78/$sha256OfAbc.bin",
            AssetHash.relativePath(sha256OfAbc, null),
        )
    }

    @Test
    fun `relative path rejects invalid hash`() {
        assertThrows(IllegalArgumentException::class.java) {
            AssetHash.relativePath("not-a-hash", "png")
        }
    }

    /** 每次只返回 [chunkBytes] 字节的流，用于覆盖分块读取路径。 */
    private class DripInputStream(payload: ByteArray, private val chunkBytes: Int) : InputStream() {
        private val delegate = ByteArrayInputStream(payload)

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len.coerceAtMost(chunkBytes))
    }
}
