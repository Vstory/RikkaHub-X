package me.rerere.rikkahub.x.storage

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 内容寻址写入路径单测（X 存储重构 P1）。
 *
 * 守的是**去重的实际效果**——这是本阶段唯一真正给用户省钱、也最容易「看着对其实没生效」
 * 的地方：
 *
 * ① **同内容只落一份**。判据不能只看返回值，必须**数盘上的文件个数**：如果实现是
 *    「先写一份再删掉」或「写到第二个路径」，返回值看上去一样，磁盘却翻倍。
 * ② **跨入口去重**。贴图走流式、字节走内存 —— 两条入口对同一份内容必须算出同一个路径，
 *    否则「同一个文件贴两次」会存成两份，而单测若只测单入口则完全发现不了。
 * ③ **复用不重写**。命中去重时必须一个字节都不写（连 mtime 都不该动），
 *    否则「去重」只省了空间没省 IO。
 * ④ **补写认原路径**。账本有登记但文件没了，必须写回**原来的路径** ——
 *    否则已经在引用它的消息会指向一个不存在的文件（表现为「历史图片全坏」）。
 *
 * 另有一条闭环用例把写入路径与引用层对上：写出来的路径必须能被
 * [AssetRefExtractor.assetIdOf] 反解回同一个哈希。反解不出来 = 这份文件永远不会被
 * 登记为引用，也就永远进不了回收判据。
 */
class AssetWritePathTest {

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = File(System.getProperty("java.io.tmpdir"), "x-writepath-${System.nanoTime()}")
        filesDir.mkdirs()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    // ────────────────────────────────────────────────────────────────
    // 首次写入
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `first write lands on the content addressed path`() {
        val ledger = FakeLedger(filesDir)
        val path = AssetWritePath(filesDir, ledger)
        val bytes = "hello".toByteArray()

        val written = path.writeBytes("txt", bytes)

        assertEquals(AssetWriteKind.NEW, written.kind)
        assertEquals(AssetHash.relativePath(AssetHash.of(bytes), "txt"), written.relativePath)
        assertTrue("文件应真的在盘上", written.file.isFile)
        assertEquals("内容应原样落盘", "hello", written.file.readText())
        assertEquals("应登记一次", 1, ledger.recordCalls)
        assertEquals("登记的大小应为实际字节数", bytes.size.toLong(), ledger.lastSize)
    }

    @Test
    fun `written path can be reversed back to the hash by the reference layer`() {
        // 闭环:写入 → 引用提取器反解 → 同一个哈希。
        // 反解不出来时,这份文件永远不会进引用表,回收判据也就永远看不到它
        val path = AssetWritePath(filesDir, FakeLedger(filesDir))
        val written = path.writeBytes("png", byteArrayOf(1, 2, 3))

        assertEquals(written.hash, AssetRefExtractor.assetIdOf(written.relativePath))
    }

    @Test
    fun `empty content is still a valid asset`() {
        val path = AssetWritePath(filesDir, FakeLedger(filesDir))
        val written = path.writeBytes("bin", ByteArray(0))

        assertEquals(AssetWriteKind.NEW, written.kind)
        assertTrue(written.file.isFile)
        assertEquals(0L, written.file.length())
    }

    // ────────────────────────────────────────────────────────────────
    // 去重：本阶段的核心
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `same content twice is stored only once`() {
        val ledger = FakeLedger(filesDir)
        val path = AssetWritePath(filesDir, ledger)
        val bytes = "same".toByteArray()

        val first = path.writeBytes("txt", bytes)
        val second = path.writeBytes("txt", bytes)

        assertEquals(AssetWriteKind.NEW, first.kind)
        assertEquals(AssetWriteKind.REUSED, second.kind)
        assertEquals("应指向同一个文件", first.relativePath, second.relativePath)
        assertEquals("盘上只应有一份", 1, countFiles(File(filesDir, AssetHash.ROOT)))
        assertEquals("复用不该再登记", 1, ledger.recordCalls)
    }

    @Test
    fun `reuse does not touch the file at all`() {
        // 「去重」如果实现成「写一份新的再删旧的」,空间省了但 IO 没省,且 mtime 会变。
        // 这条用例用 mtime 与内容双重确认:复用时一个字节都没写过
        val path = AssetWritePath(filesDir, FakeLedger(filesDir))
        val bytes = "stable".toByteArray()

        val first = path.writeBytes("txt", bytes)
        val stampBefore = first.file.lastModified()
        Thread.sleep(20) // 足以让文件系统的时间戳往前走
        val second = path.writeBytes("txt", bytes)

        assertEquals(stampBefore, second.file.lastModified())
        assertEquals("stable", second.file.readText())
        assertEquals(1, countFiles(File(filesDir, AssetHash.ROOT)))
    }

    @Test
    fun `stream and bytes agree so the same file pasted twice is stored once`() {
        // 贴图走流式(content:// 只能读一遍),字节走内存 —— 两条入口必须算出同一个路径
        val ledger = FakeLedger(filesDir)
        val path = AssetWritePath(filesDir, ledger)
        val bytes = "cross entry".toByteArray()

        val fromStream = path.writeStream("txt", ByteArrayInputStream(bytes))
        val fromBytes = path.writeBytes("txt", bytes)

        assertEquals(AssetWriteKind.NEW, fromStream.kind)
        assertEquals(AssetWriteKind.REUSED, fromBytes.kind)
        assertEquals(fromStream.relativePath, fromBytes.relativePath)
        assertEquals(1, countFiles(File(filesDir, AssetHash.ROOT)))
    }

    @Test
    fun `different content gets different files`() {
        val path = AssetWritePath(filesDir, FakeLedger(filesDir))

        val a = path.writeBytes("txt", "a".toByteArray())
        val b = path.writeBytes("txt", "b".toByteArray())

        assertEquals(AssetWriteKind.NEW, a.kind)
        assertEquals(AssetWriteKind.NEW, b.kind)
        assertFalse(a.relativePath == b.relativePath)
        assertEquals(2, countFiles(File(filesDir, AssetHash.ROOT)))
    }

    @Test
    fun `same content with different extensions still reuses the first path`() {
        // 扩展名以首次落盘者为准:内容相同就是同一个文件,不因这次带来的后缀而改名。
        // 否则同一份内容会有两个路径,去重形同虚设
        val path = AssetWritePath(filesDir, FakeLedger(filesDir))
        val bytes = byteArrayOf(9, 9, 9)

        val first = path.writeBytes("png", bytes)
        val second = path.writeBytes("jpg", bytes)

        assertEquals(AssetWriteKind.REUSED, second.kind)
        assertEquals(first.relativePath, second.relativePath)
        assertTrue(first.relativePath.endsWith(".png"))
    }

    // ────────────────────────────────────────────────────────────────
    // 补写：账本有、文件没了
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `entry whose file is gone is rewritten at the original path`() {
        val ledger = FakeLedger(filesDir)
        val path = AssetWritePath(filesDir, ledger)
        val bytes = "restore me".toByteArray()

        val first = path.writeBytes("txt", bytes)
        assertTrue(first.file.delete())
        assertNull("删掉后查盘应发现文件不在", ledger.findKnown(first.hash)?.fileExists?.takeIf { it })

        val again = path.writeBytes("txt", bytes)

        assertEquals("应识别为补写而非新建", AssetWriteKind.REWRITTEN, again.kind)
        assertEquals("必须写回原路径,否则已在引用它的消息会指向空文件", first.relativePath, again.relativePath)
        assertTrue("文件应重新在盘上", again.file.isFile)
        assertEquals("补写不该重复登记", 1, ledger.recordCalls)
    }

    @Test
    fun `rewrite keeps the original extension when this call brings another`() {
        val ledger = FakeLedger(filesDir)
        val path = AssetWritePath(filesDir, ledger)
        val bytes = byteArrayOf(7, 7)

        val first = path.writeBytes("png", bytes)
        assertTrue(first.file.delete())
        val again = path.writeBytes("gif", bytes)

        assertEquals(AssetWriteKind.REWRITTEN, again.kind)
        assertEquals("补写要认原路径,包括原后缀", first.relativePath, again.relativePath)
        assertTrue(again.relativePath.endsWith(".png"))
    }

    // ────────────────────────────────────────────────────────────────
    // 落盘成功但登记失败
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `record failure keeps the file and reports it instead of throwing`() {
        // 登记那一步失败时**不能让异常冒出去**:调用方会把异常当成「这次写入失败」而回落到
        // 旧路径,于是同一份内容在盘上出现两个文件(内容寻址一份 + 旧式一份)。
        // 正确处置是照常返回,并如实告知「没登记上」,由调用方记一条日志。
        val ledger = FakeLedger(filesDir).apply { failRecording = true }
        val path = AssetWritePath(filesDir, ledger)

        val written = path.writeBytes("txt", "ledger down".toByteArray())

        assertEquals(AssetWriteKind.NEW, written.kind)
        assertFalse("登记失败要如实上报", written.recorded)
        assertTrue("内容仍应可用", written.file.isFile)
        assertEquals("盘上只应有一份", 1, countFiles(File(filesDir, AssetHash.ROOT)))
    }

    @Test
    fun `successful record reports recorded true`() {
        // 与上一条成对:默认路径必须报告「已登记」,否则调用方会一直误报降级
        val path = AssetWritePath(filesDir, FakeLedger(filesDir))
        assertTrue(path.writeBytes("txt", "ok".toByteArray()).recorded)
    }

    // ────────────────────────────────────────────────────────────────
    // 流式路径
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `stream hashing matches byte hashing even when reads return tiny chunks`() {
        val path = AssetWritePath(filesDir, FakeLedger(filesDir))
        val bytes = ByteArray(5_000) { (it % 251).toByte() }

        val fromStream = path.writeStream("bin", ChunkedStream(bytes, chunk = 7))

        assertEquals("分块读取必须与整体一致", AssetHash.relativePath(AssetHash.of(bytes), "bin"), fromStream.relativePath)
        assertTrue(fromStream.file.readBytes().contentEquals(bytes))
    }

    @Test
    fun `reuse via stream leaves no temporary file behind`() {
        val path = AssetWritePath(filesDir, FakeLedger(filesDir))
        val bytes = "no temp".toByteArray()

        path.writeStream("txt", ByteArrayInputStream(bytes))
        val second = path.writeStream("txt", ByteArrayInputStream(bytes))

        assertEquals(AssetWriteKind.REUSED, second.kind)
        assertEquals("命中去重时临时文件必须清掉", 0, countFiles(File(filesDir, ".x-tmp")))
        assertEquals(1, countFiles(File(filesDir, AssetHash.ROOT)))
    }

    @Test
    fun `stream failure does not leave a temporary file behind`() {
        val path = AssetWritePath(filesDir, FakeLedger(filesDir))

        runCatching { path.writeStream("bin", FailingStream()) }

        assertEquals("中途失败也不能留下临时文件", 0, countFiles(File(filesDir, ".x-tmp")))
    }

    // ────────────────────────────────────────────────────────────────
    // 扩展名归一
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `extension is normalized before it reaches the path`() {
        val path = AssetWritePath(filesDir, FakeLedger(filesDir))

        assertTrue(path.writeBytes("PNG", byteArrayOf(1)).relativePath.endsWith(".png"))
        assertTrue(path.writeBytes(".png", byteArrayOf(2)).relativePath.endsWith(".png"))
        assertTrue("非法后缀应回落 bin", path.writeBytes("tar.gz", byteArrayOf(3)).relativePath.endsWith(".bin"))
        assertTrue("缺少后缀应回落 bin", path.writeBytes(null, byteArrayOf(4)).relativePath.endsWith(".bin"))
    }

    // ────────────────────────────────────────────────────────────────
    // 测试替身
    // ────────────────────────────────────────────────────────────────

    private fun countFiles(dir: File): Int =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.count()

    /**
     * 假账本：只记「哈希 → 相对路径」，并把**文件是否在盘上照实算**。
     *
     * 这一点必须照实：真实仓储的 `knownAsset` 也是同时查盘的，[KnownAsset.fileExists]
     * 就是给「复用还是补写」用的判据。若假账本偷懒恒返回 true，
     * 补写路径（历史图片坏掉的那个场景）就永远测不到。
     */
    private class FakeLedger(private val filesDir: File) : AssetLedger {
        private val paths = mutableMapOf<String, String>()
        var recordCalls = 0
            private set
        var lastSize = -1L
            private set

        /** 打开后 [recordAsset] 抛错 —— 用来覆盖「内容已落盘但登记失败」的分支。 */
        var failRecording = false

        override fun findKnown(hash: String): KnownAsset? {
            val relativePath = paths[hash] ?: return null
            return KnownAsset(relativePath = relativePath, fileExists = File(filesDir, relativePath).isFile)
        }

        override fun recordAsset(
            hash: String,
            relativePath: String,
            byteSize: Long,
            nowMillis: Long,
            extrasJson: String,
        ) {
            recordCalls += 1
            lastSize = byteSize
            if (failRecording) throw IllegalStateException("ledger unavailable")
            paths[hash] = relativePath
        }

        override fun referenceCountOf(assetId: String): Int = 0
    }

    /** 每次只吐固定字节数的流 —— 覆盖「read 返回小值」的分块路径。 */
    private class ChunkedStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var position = 0

        override fun read(): Int =
            if (position >= data.size) -1 else data[position++].toInt() and 0xFF

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(chunk, length, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    /** 读到一半就抛。用来确认失败路径也会清掉临时文件。 */
    private class FailingStream : InputStream() {
        private var emitted = 0

        override fun read(): Int {
            if (emitted++ > 4) throw java.io.IOException("boom")
            return 1
        }

        // 按流契约把读到的字节放进 buffer,而不是只回一个长度 ——
        // 否则写入方拿到的是未写入的缓冲区内容,用例就测不出「读到一半失败」的真实形态
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val value = read()
            if (value == -1) return -1
            buffer[offset] = value.toByte()
            return 1
        }
    }
}
