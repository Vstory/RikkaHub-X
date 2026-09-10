// 审计验证用例(2026-09-10,分支 audit/verify-x)
//
// 目的:用真实 CI 执行来证实/证伪静态审计结论。
// 约定:**断言期望的正确行为** —— 用例失败 = 该结论被证实(问题真实存在);
// 用例通过 = 结论不成立(或属设计取舍,见 DOC 前缀用例)。
//
// 本文件覆盖工作区归档器(WorkspaceArchive)相关结论。
// 以下各条**已确认为缺陷并修复**,用例转为回归守护(失败 = 修复被回退):
//   A1  绝对符号链接往返后被降级为空文件
//   A1b 含 .. 的相对链接(usr/local/lib 常见形式)同样须保留
//   A2  硬链接条目解出为空文件(内容丢失)
//   A3  listArchivePaths 产出尾斜杠伪条目
//   A4  tools.txt 超 512KB 时 readArchiveFile 返回 null(阈值用错对象)
// 安全守护:
//   SEC 经符号链接向外写入必须仍被拒绝(确保上述放宽未削弱防护)
// 加固(A5,2026-09-10 落地):
//   A5  解压加入总量预算 —— tar bomb(几 KB 输入解出几十 GB)被拒,且不留半份产物
//   A5b 预算内的归档仍正常解出(防加固误伤合法导入)
//   A5c 条目数预算 —— 海量空条目同样被拒
//   A5d 预算策略 —— 40% 可用空间(合并复制阶段峰值 2 份)、上限与兜底
//   A5e sparse 归档的「孔」须计入预算(16 MiB 全零 → 归档仅 133 字节)
//   A5f sparse 归档按逻辑大小解出(内容不因孔丢失)
// 端到端(不含 UI):
//   A6  完整往返 —— 目录层级/内容/空文件/可执行位逐项保留
//   A7  真实文件系统可用空间可读,预算留出合并余量
//   A7b 按真实可用空间预算跑通「解压 → 合并」全链路
//
// 残留边界(未加固,风险较低):listArchivePaths / 归档预览只遍历条目不落盘,
//   故未加条目上限;其资源占用受输入体积约束,不构成「写满存储」类风险。
package me.rerere.workspace.x

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceArchiveAuditTest {

    private fun tempDir(name: String): File =
        Files.createTempDirectory("ws-audit-$name").toFile().apply { deleteOnExit() }

    private fun manifest(includesUserArea: Boolean = true) = WorkspaceArchiveManifest(
        id = "00000000-0000-0000-0000-000000000001",
        root = "00000000-0000-0000-0000-000000000001",
        name = "audit",
        exportedAtEpochMillis = 0L,
        includesUserArea = includesUserArea,
    )

    private fun write(root: File, virtualFiles: Map<String, WorkspaceArchiveFile> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        WorkspaceArchiver.writeArchive(
            workspaceDir = root,
            manifest = manifest(),
            output = out,
            virtualFiles = virtualFiles,
        )
        return out.toByteArray()
    }

    /** A1:绝对符号链接(Debian 系 etc/alternatives 的常态)往返后应仍是链接,而非空文件。 */
    @Test
    fun `A1 absolute symlink survives archive roundtrip`() {
        val root = tempDir("abslink")
        val alternatives = File(root, "linux/etc/alternatives").apply { mkdirs() }
        val link = File(alternatives, "mytool")
        Files.createSymbolicLink(link.toPath(), Paths.get("/usr/bin/mytool"))
        assertTrue("前置:应已建出绝对符号链接", Files.isSymbolicLink(link.toPath()))

        val restored = tempDir("abslink-restored")
        WorkspaceArchiver.extractArchive(ByteArrayInputStream(write(root)), restored)

        val restoredLink = File(restored, "linux/etc/alternatives/mytool")
        assertTrue(
            "绝对符号链接往返后被降级为普通文件(" +
                "isLink=${Files.isSymbolicLink(restoredLink.toPath())}, size=${restoredLink.length()})",
            Files.isSymbolicLink(restoredLink.toPath()),
        )
    }

    /** A1b:含 ".." 的相对链接(usr/local/lib/x -> ../../lib/x 这类常见形式)也应保留。 */
    @Test
    fun `A1b relative parent symlink survives archive roundtrip`() {
        val root = tempDir("rellink")
        val lib = File(root, "linux/usr/local/lib").apply { mkdirs() }
        val link = File(lib, "mylib.so")
        Files.createSymbolicLink(link.toPath(), Paths.get("../../lib/mylib.so"))
        assertTrue("前置:应已建出含 .. 的符号链接", Files.isSymbolicLink(link.toPath()))

        val restored = tempDir("rellink-restored")
        WorkspaceArchiver.extractArchive(ByteArrayInputStream(write(root)), restored)

        val restoredLink = File(restored, "linux/usr/local/lib/mylib.so")
        assertTrue(
            "含 .. 的相对链接往返后被降级为普通文件(size=${restoredLink.length()})",
            Files.isSymbolicLink(restoredLink.toPath()),
        )
    }

    /**
     * SEC:放宽链接目标后,**经符号链接向外写入**必须仍被拒绝。
     * 守护点:resolveInside 用 canonicalPath 解析链接,穿透后前缀不符 → 抛错中止解包。
     */
    @Test
    fun `SEC crafted archive must not write through a symlink`() {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                // 1) 先放一个指向 staging 之外的符号链接
                val link = TarArchiveEntry("files/esc", TarConstants.LF_SYMLINK).apply {
                    linkName = "/tmp"
                    mode = 0x1FF
                }
                tar.putArchiveEntry(link)
                tar.closeArchiveEntry()

                // 2) 再借该链接向外写文件
                val payload = TarArchiveEntry("files/esc/pwned.txt", TarConstants.LF_NORMAL).apply {
                    size = 4L
                    mode = 0x1A4
                }
                tar.putArchiveEntry(payload)
                tar.write("pwnx".toByteArray())
                tar.closeArchiveEntry()
            }
        }

        val staging = tempDir("sec")
        val result = runCatching {
            WorkspaceArchiver.extractArchive(ByteArrayInputStream(out.toByteArray()), staging)
        }
        assertTrue(
            "经符号链接向外的条目竟被接受(安全防护失效)",
            result.isFailure,
        )
        assertTrue(
            "写入穿透到了 staging 之外",
            !File("/tmp/pwned.txt").exists(),
        )
    }

    /** A2:tar 硬链接条目应保留内容(建硬链接或至少复制内容),当前解成 0 字节文件。 */
    @Test
    fun `A2 hardlink entry keeps content`() {
        // 手工构造带硬链接的 tar.gz(GNU tar -c 会产出这种条目):files/real.txt + 硬链接 files/hard.txt
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                val real = TarArchiveEntry("files/real.txt", TarConstants.LF_NORMAL).apply {
                    size = 5L
                    mode = 0x1A4
                }
                tar.putArchiveEntry(real)
                tar.write("hello".toByteArray())
                tar.closeArchiveEntry()

                val hard = TarArchiveEntry("files/hard.txt", TarConstants.LF_LINK).apply {
                    linkName = "files/real.txt"
                    mode = 0x1A4
                }
                tar.putArchiveEntry(hard)
                tar.closeArchiveEntry()
            }
        }

        val staging = tempDir("hardlink")
        WorkspaceArchiver.extractArchive(ByteArrayInputStream(out.toByteArray()), staging)

        val restoredReal = File(staging, "files/real.txt")
        val restoredHard = File(staging, "files/hard.txt")
        assertEquals("前置:原文件内容应正确", "hello", restoredReal.readText())
        assertEquals(
            "硬链接条目内容丢失(解成 ${restoredHard.length()} 字节)",
            "hello",
            restoredHard.readText(),
        )
    }

    /** A3:listArchivePaths 不应产出以 "/" 结尾的伪条目(GNU tar 的目录条目名带尾斜杠)。 */
    @Test
    fun `A3 listArchivePaths emits no trailing-slash artifacts`() {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                val dir = TarArchiveEntry("files/", TarConstants.LF_DIR).apply { mode = 0x1ED }
                tar.putArchiveEntry(dir)
                tar.closeArchiveEntry()

                val file = TarArchiveEntry("files/a.txt", TarConstants.LF_NORMAL).apply {
                    size = 1L
                    mode = 0x1A4
                }
                tar.putArchiveEntry(file)
                tar.write("x".toByteArray())
                tar.closeArchiveEntry()
            }
        }

        val paths = WorkspaceArchiver.listArchivePaths(ByteArrayInputStream(out.toByteArray()))
        val bogus = paths.filter { it.endsWith("/") || it.isEmpty() }
        assertTrue("产出尾斜杠伪条目: $bogus", bogus.isEmpty())
    }

    /** A4:600KB 的 tools/tools.txt 应能被读回(当前 512KB 阈值把它当 manifest 拒绝)。 */
    @Test
    fun `A4 tools txt larger than 512KB is readable`() {
        val root = tempDir("toolsbig")
        File(root, "files").mkdirs()
        val big = "x".repeat(600 * 1024)

        val archived = write(
            root,
            virtualFiles = mapOf(
                "tools/tools.txt" to WorkspaceArchiveFile(big.toByteArray(), 0x1A4),
            ),
        )

        val read = WorkspaceArchiver.readArchiveFile(ByteArrayInputStream(archived), "tools/tools.txt")
        assertNotNull("600KB 的 tools.txt 读回为 null(阈值 512KB 误用于用户文件)", read)
        assertEquals(600 * 1024, read!!.size)
    }

    /**
     * A5 加固(2026-09-10):解压加入总量预算 —— 高压缩比归档不再能「几 KB 输入撑爆存储」。
     * 预算可注入,故用小值验证;真实调用由 WorkspaceRepository 按可用空间收紧。
     */
    @Test
    fun `A5 archive expanding beyond the size budget is rejected`() {
        val size = 32L * 1024 * 1024
        val budget = 1L * 1024 * 1024
        val staging = tempDir("bomb")

        val error = assertThrows(IllegalStateException::class.java) {
            WorkspaceArchiver.extractArchive(
                ByteArrayInputStream(bombArchive("files/big.bin", size)),
                staging,
                maxTotalBytes = budget,
            )
        }
        assertTrue("错误信息应指明体积超限: ${error.message}", error.message!!.contains("size budget"))
        assertTrue(
            "中止后应清掉半份产物(实际仍留 ${File(staging, "files/big.bin").length()} 字节)",
            !File(staging, "files/big.bin").exists(),
        )
    }

    /** A5b:预算内的归档照常解出(确保加固没伤到合法导入)。 */
    @Test
    fun `A5b archive within the size budget extracts normally`() {
        val size = 32L * 1024 * 1024
        val staging = tempDir("bomb-ok")
        WorkspaceArchiver.extractArchive(
            ByteArrayInputStream(bombArchive("files/big.bin", size)),
            staging,
            maxTotalBytes = 64L * 1024 * 1024,
        )
        assertEquals(size, File(staging, "files/big.bin").length())
    }

    /** A5c:条目数预算 —— 海量空条目(每个 tar 头仅 512 字节)同样能撑爆目录项。 */
    @Test
    fun `A5c excessive entry count is rejected`() {
        val staging = tempDir("entries")
        val error = assertThrows(IllegalStateException::class.java) {
            WorkspaceArchiver.extractArchive(
                ByteArrayInputStream(manyEntriesArchive(50)),
                staging,
                maxEntries = 10,
            )
        }
        assertTrue("错误信息应指明条目数超限: ${error.message}", error.message!!.contains("too many entries"))
    }

    /** A5d:解压预算策略 —— 40% 可用空间,受默认上限约束,查不到空间时退回上限。 */
    @Test
    fun `A5d extract budget policy keeps room for the merge copy`() {
        val gb = 1024L * 1024 * 1024
        assertEquals(
            "可用 20 GiB 时应取 40%(复制阶段还要一份),而非 80%",
            8L * gb,
            WorkspaceArchiver.extractBudgetFor(20L * gb),
        )
        assertEquals(4L * gb, WorkspaceArchiver.extractBudgetFor(10L * gb))
        assertEquals(
            "查不到可用空间时退回默认上限,不因此拒绝导入",
            WorkspaceArchiver.MAX_EXTRACT_TOTAL_BYTES,
            WorkspaceArchiver.extractBudgetFor(0L),
        )
        assertEquals(
            "极大可用空间仍受默认上限约束",
            WorkspaceArchiver.MAX_EXTRACT_TOTAL_BYTES,
            WorkspaceArchiver.extractBudgetFor(10_000L * gb),
        )
    }

    /**
     * A5e:sparse 归档的「孔」必须计入预算。
     *
     * 素材为真实 GNU tar 1.35 `--sparse` 产出的归档(头部 typeflag='S',GNU sparse 0.0):
     * 16 MiB 全零文件 → 归档仅 **133 字节**,是 tar bomb 的极致形态。
     * 若计数只算「存储的数据段」(此处为 0),这类归档将完全绕过上限。
     */
    @Test
    fun `A5e sparse archive counts toward the size budget`() {
        val staging = tempDir("sparse-bomb")
        val error = assertThrows(IllegalStateException::class.java) {
            WorkspaceArchiver.extractArchive(
                ByteArrayInputStream(sparseArchive()),
                staging,
                maxTotalBytes = 1L * 1024 * 1024,
            )
        }
        assertTrue("错误信息应指明体积超限: ${error.message}", error.message!!.contains("size budget"))
        assertTrue("中止后不应留下产物", !File(staging, "files/sparse.bin").exists())
    }

    /** A5f:预算足够时 sparse 归档按**逻辑大小**解出(零填充),内容不因孔而丢失。 */
    @Test
    fun `A5f sparse archive extracts to its logical size`() {
        val staging = tempDir("sparse-ok")
        WorkspaceArchiver.extractArchive(
            ByteArrayInputStream(sparseArchive()),
            staging,
            maxTotalBytes = 64L * 1024 * 1024,
        )
        val restored = File(staging, "files/sparse.bin")
        assertEquals("sparse 文件应按逻辑大小解出", 16L * 1024 * 1024, restored.length())
        restored.inputStream().use { input ->
            val probe = ByteArray(4096)
            var read = 0
            while (read < probe.size) {
                val n = input.read(probe, read, probe.size - read)
                if (n < 0) break
                read += n
            }
            assertTrue("解出的内容应为零填充", probe.all { it == 0.toByte() })
        }
    }

    /**
     * A6:完整往返 —— 目录层级、文件内容、空文件、可执行位逐项保留。
     * 这是**不含 UI** 的端到端:导出 → 解压,验证两侧一致。
     */
    @Test
    fun `A6 roundtrip preserves layout content and exec bit`() {
        val root = tempDir("roundtrip")
        File(root, "files/docs/notes").mkdirs()
        File(root, "files/docs/notes/a.txt").writeText("hello 你好")
        File(root, "files/empty.txt").writeText("")
        val script = File(root, "linux/usr/local/bin/tool").apply {
            parentFile!!.mkdirs()
            writeText("#!/bin/sh\necho hi\n")
            setExecutable(true, false)
        }
        assertTrue("前置:脚本应可执行", script.canExecute())

        val restored = tempDir("roundtrip-restored")
        WorkspaceArchiver.extractArchive(ByteArrayInputStream(write(root)), restored)

        assertEquals("utf-8 文件内容应一致", "hello 你好", File(restored, "files/docs/notes/a.txt").readText())
        assertTrue("空文件应保留", File(restored, "files/empty.txt").isFile)
        assertTrue("目录层级应保留", File(restored, "files/docs/notes").isDirectory)
        val restoredScript = File(restored, "linux/usr/local/bin/tool")
        assertEquals("脚本内容应一致", "#!/bin/sh\necho hi\n", restoredScript.readText())
        assertTrue("可执行位应保留", restoredScript.canExecute())
    }

    /** A7:真实文件系统上可取到可用空间,且预算留出合并复制(峰值 2 份)的余量。 */
    @Test
    fun `A7 budget derives from real usable space leaving room for merge copy`() {
        val dir = tempDir("usable")
        val usable = dir.usableSpace
        assertTrue("真实文件系统应能取到可用空间(实测 $usable)", usable > 0L)
        val budget = WorkspaceArchiver.extractBudgetFor(usable)
        assertTrue("预算($budget)应为正", budget > 0L)
        assertTrue("预算($budget)应小于可用空间($usable)", budget < usable)
    }

    /**
     * A7b:按真实可用空间推导的预算跑通完整导入链路(解压 → 合并),
     * 覆盖「UI 之外」的全部失败面:预算计算、解包、mergeTree 落盘。
     */
    @Test
    fun `A7b import flow with real usable-space budget merges into target`() {
        val source = tempDir("import-src")
        File(source, "files/docs").mkdirs()
        File(source, "files/docs/readme.md").writeText("imported body")

        val archive = write(source)
        val staging = tempDir("import-staging")
        val target = tempDir("import-target")

        val budget = WorkspaceArchiver.extractBudgetFor(staging.usableSpace)
        WorkspaceArchiver.extractArchive(ByteArrayInputStream(archive), staging, maxTotalBytes = budget)
        WorkspaceArchiver.mergeTree(File(staging, "files"), target)

        assertEquals("imported body", File(target, "docs/readme.md").readText())
    }

    /**
     * 真实 GNU tar 1.35 `--sparse` 产出的归档(133 字节):
     * files/sparse.bin = 16 MiB 全零,头部 typeflag='S'
     */
    private fun sparseArchive(): ByteArray = Base64.getDecoder().decode(
        "H4sIAAAAAAAAA+3QUQrCMAyA4R6lJ5jp2upBdoIKFQpipa3339ZHFQWhL/J/LwlJICGXdI31UO+h1Did000NIJujSI/yGntuvJm9tc7tdeNPziu9jDjm2aO2ULRWJef2ae5b/2ciRt59ZMgCAAAAAAAAAAAAAAAAAMCfWAF6s7pkACgAAA==",
    )

    /** 构造含单个指定大小文件的 tar.gz(内容为零字节,压缩比极高,等同 bomb)。 */
    private fun bombArchive(name: String, size: Long): ByteArray {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                val entry = TarArchiveEntry(name, TarConstants.LF_NORMAL).apply {
                    this.size = size
                    mode = 0x1A4
                }
                tar.putArchiveEntry(entry)
                val chunk = ByteArray(64 * 1024)
                var written = 0L
                while (written < size) {
                    val n = minOf(chunk.size.toLong(), size - written).toInt()
                    tar.write(chunk, 0, n)
                    written += n
                }
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    /** 构造含 [count] 个空文件的 tar.gz,用于条目数预算验证。 */
    private fun manyEntriesArchive(count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                repeat(count) { index ->
                    val entry = TarArchiveEntry("files/e$index", TarConstants.LF_NORMAL).apply {
                        size = 0L
                        mode = 0x1A4
                    }
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
