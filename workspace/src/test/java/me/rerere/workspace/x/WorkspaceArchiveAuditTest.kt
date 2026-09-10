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
// 现状记录:
//   A5 DOC:解压无累计体积上限(tar bomb 风险),加固缺口,非缺陷断言
package me.rerere.workspace.x

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
     * A5 DOC:记录「解压无累计体积上限」的现状。
     * 32MB 高压缩比内容可正常解出 → 确认无上限(tar bomb 风险),属加固缺口而非缺陷。
     * 若将来加入上限,本用例应改为断言拒绝。
     */
    @Test
    fun `A5 DOC extraction has no cumulative size cap`() {
        val size = 32L * 1024 * 1024
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                val entry = TarArchiveEntry("files/big.bin", TarConstants.LF_NORMAL).apply {
                    this.size = size
                    mode = 0x1A4
                }
                tar.putArchiveEntry(entry)
                val zeros = ByteArray(64 * 1024)
                var written = 0L
                while (written < size) {
                    val n = minOf(zeros.size.toLong(), size - written).toInt()
                    tar.write(zeros, 0, n)
                    written += n
                }
                tar.closeArchiveEntry()
            }
        }

        val staging = tempDir("bomb")
        WorkspaceArchiver.extractArchive(ByteArrayInputStream(out.toByteArray()), staging)
        assertEquals(
            "解出 $size 字节未被拒绝 → 当前无累计体积上限",
            size,
            File(staging, "files/big.bin").length(),
        )
    }
}
