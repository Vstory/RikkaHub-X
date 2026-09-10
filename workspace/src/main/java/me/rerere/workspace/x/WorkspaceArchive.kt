package me.rerere.workspace.x

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

/** 工作区归档的容器格式标识 */
const val WORKSPACE_ARCHIVE_FORMAT = "rikkahub-workspace"
const val WORKSPACE_ARCHIVE_VERSION = 2
const val WORKSPACE_ARCHIVE_MANIFEST = "manifest.json"

/**
 * 工作区归档 manifest,打包在 tar.gz 首位。
 *
 * 设计:工作区 rootfs 本体是可重装的发行版(不打包);归档只含
 *  - 工具清单 tools/(tools.txt + restore-tools.sh,自动探测手动安装的工具)
 *  - rootfs 用户区 linux/{usr/local,opt,home,root,etc}(手工安装/配置,发行版升级不覆盖)
 *  - 用户工作文件 files/
 * 保留原 id/root/name,新设备恢复按原 id 覆盖补全(配合应用备份,能把 BROKEN 工作区"填活")。
 */
@Serializable
data class WorkspaceArchiveManifest(
    val format: String = WORKSPACE_ARCHIVE_FORMAT,
    val version: Int = WORKSPACE_ARCHIVE_VERSION,
    val id: String,
    val root: String,
    val name: String,
    val shellCompatibilityMode: Boolean = false,
    val exportedAtEpochMillis: Long,
    /** 归档是否含 rootfs 用户区(usr/local opt home root etc) */
    val includesUserArea: Boolean = false,
    /** 归档是否含工具清单 tools/ */
    val includesTools: Boolean = false,
)

data class WorkspaceArchiveProgress(
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val currentEntry: String = "",
)

/** 归档内虚拟文件(不落盘直接写入,如 tools/restore-tools.sh) */
data class WorkspaceArchiveFile(
    val bytes: ByteArray,
    val mode: Int,
)

/**
 * 工作区导出/导入归档器(tar.gz)。
 *
 * 用 tar 而不用 zip:rootfs 用户区含符号链接与权限位,java.util.zip 不写 unix extra fields,
 * 打包后语义会静默丢失;tar 原生保留 typeflag(符号链接)与 mode,与 RootfsInstaller 格式同构。
 */
object WorkspaceArchiver {
    /** rootfs 内视为"用户区"的子目录(相对 linux/),相对发行版本体;打包/恢复都按这份清单走 */
    val USER_AREA_RELATIVE = listOf("usr/local", "opt", "home", "root", "etc")

    /**
     * linux/etc 下不应随归档迁移的「机器级 / 运行时态」文件:
     *  - resolv.conf  网络由 RootfsPatcher 按新设备环境重建
     *  - shadow/gshadow 账号密码哈希:新设备 rootfs 有自己的账号体系,迁移后重设密码即可;
     *                带出即泄露旧系统口令哈希(可被离线爆破)
     *  - machine-id  机器标识,新设备应生成自己的
     * (etc/ssh/ 下的 SSH host 私钥另由 [shouldSkipInEtc] 单独排除;
     *  /root/.ssh 等用户自身的密钥属用户数据,保留随归档迁移)
     */
    private val ETC_EXCLUDE_FILES = setOf("resolv.conf", "shadow", "gshadow", "machine-id")

    /** linux/etc/ssh/ 下的 SSH host 私钥(机器身份;.pub 公钥保留无妨) */
    private val SSH_HOST_KEY = Regex("ssh_host_[a-z0-9]+_key")

    // encodeDefaults = true:让 format/version 等带默认值的字段也显式写进 JSON,
    // 归档自述格式与版本。旧档缺这些字段仍可 decode(缺失落默认值),向后兼容。
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeManifest(manifest: WorkspaceArchiveManifest): ByteArray =
        json.encodeToString(manifest).toByteArray(Charsets.UTF_8)

    fun decodeManifest(bytes: ByteArray): WorkspaceArchiveManifest =
        json.decodeFromString(bytes.decodeToString())

    /** 判断某归档内目录名是否属于 rootfs 用户区顶层(如 usr/local、opt…) */
    fun isUserAreaTop(name: String): Boolean = name in USER_AREA_RELATIVE

    /**
     * 打包工作区到 tar.gz 写入 [output]。
     * sections:用户区各子目录(若存在)+ files/;[virtualFiles] 写在其后(归档路径如 tools/xxx)。
     */
    fun writeArchive(
        workspaceDir: File,
        manifest: WorkspaceArchiveManifest,
        output: OutputStream,
        virtualFiles: Map<String, WorkspaceArchiveFile> = emptyMap(),
        onProgress: (WorkspaceArchiveProgress) -> Unit = {},
    ) {
        val linuxDir = File(workspaceDir, "linux")
        val filesDir = File(workspaceDir, "files")
        val sections = buildList {
            if (manifest.includesUserArea) {
                for (sub in USER_AREA_RELATIVE) {
                    val dir = File(linuxDir, sub)
                    if (dir.isDirectory) add(Section(dir, "linux/$sub"))
                }
            }
            if (filesDir.isDirectory) add(Section(filesDir, "files"))
        }
        val totalBytes = MANIFEST_ESTIMATE_BYTES +
            virtualFiles.values.sumOf { it.bytes.size.toLong() } +
            sections.sumOf { measureSection(it) }
        var done = 0L
        fun report(name: String) {
            onProgress(WorkspaceArchiveProgress(done, totalBytes, name))
        }

        GzipCompressorOutputStream(BufferedOutputStream(output, IO_BUFFER)).use { gzip ->
            TarArchiveOutputStream(gzip, "UTF-8").use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                tar.setAddPaxHeadersForNonAsciiNames(true)

                // 1. manifest 放最前
                val manifestBytes = encodeManifest(manifest)
                writeBytesEntry(tar, WORKSPACE_ARCHIVE_MANIFEST, manifestBytes, 0x1A4)
                done += manifestBytes.size
                report(WORKSPACE_ARCHIVE_MANIFEST)

                // 2. 虚拟文件(tools/)
                for ((name, file) in virtualFiles) {
                    writeBytesEntry(tar, name, file.bytes, file.mode)
                    done += file.bytes.size
                    report(name)
                }

                // 3. 用户区 + files 子树
                for (section in sections) {
                    writeSection(tar, section) { entryName, bytesWritten ->
                        done += bytesWritten
                        report(entryName)
                    }
                }
            }
        }
    }

    /**
     * 从 tar.gz 读取 manifest(扫描到 manifest.json 即返回)。
     * 不校验格式/版本:旧档可能缺 format/version 字段,decode 落到默认值,
     * 是否支持由调用方(preview/import)决定。
     */
    fun readManifest(input: InputStream): WorkspaceArchiveManifest {
        GzipCompressorInputStream(BufferedInputStream(input, IO_BUFFER)).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    if (entry.name == WORKSPACE_ARCHIVE_MANIFEST) {
                        // [X-fix] size 防线:非正或超预算的伪造头 → 拒绝,避免 size.toInt() 溢出(≥2GiB 变负)后 readNBytes 抛异常
                        if (entry.size <= 0 || entry.size > MAX_MANIFEST_BYTES) {
                            error("Workspace archive manifest is corrupt or too large (size=${entry.size})")
                        }
                        val bytes = tar.readNBytes(entry.size.toInt())
                        return decodeManifest(bytes)
                    }
                    // 非 manifest 条目:继续 nextEntry 会自动跳到下一条目头
                }
            }
        }
        error("Not a RikkaHub workspace archive: missing $WORKSPACE_ARCHIVE_MANIFEST")
    }

    /**
     * 把 tar.gz 解压到 [stagingDir](先清空再建),保留归档内完整前缀
     * (linux/usr/local/…、files/…),之后由调用方 mergeTree 合入目标。
     *
     * [maxTotalBytes] / [maxEntries] 是解压预算(tar bomb 防线,audit A5):
     * 归档头里的 size 是**声明值**,高压缩比内容可以「几 KB 输入解出几十 GB」,
     * 没有预算就只能等存储被写满才发现。超限即抛错,并清掉已写入的半份产物。
     * 默认值远高于合法用途,**调用方宜按可用空间再收紧**;测试可注入小值验证。
     */
    fun extractArchive(
        input: InputStream,
        stagingDir: File,
        onProgress: (WorkspaceArchiveProgress) -> Unit = {},
        maxTotalBytes: Long = MAX_EXTRACT_TOTAL_BYTES,
        maxEntries: Long = MAX_EXTRACT_ENTRIES,
    ) {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        try {
            extractEntries(input, stagingDir, onProgress, maxTotalBytes, maxEntries)
        } catch (e: Throwable) {
            // 失败(含预算超限)不留半份解包结果 —— 否则「中止」只是停手,
            // 已写入的部分照样占着存储,tar bomb 的目的就达到了
            stagingDir.deleteRecursively()
            throw e
        }
    }

    private fun budgetExceeded(bytes: Long, limit: Long): Nothing =
        error("Workspace archive expands beyond the size budget (bytes=$bytes, limit=$limit)")

    /** 逐条目解包。目录准备与失败清理由 [extractArchive] 负责。 */
    private fun extractEntries(
        input: InputStream,
        stagingDir: File,
        onProgress: (WorkspaceArchiveProgress) -> Unit,
        maxTotalBytes: Long,
        maxEntries: Long,
    ) {
        var entries = 0L
        var totalBytes = 0L
        GzipCompressorInputStream(BufferedInputStream(input, IO_BUFFER)).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    // 条目数预算:海量空条目同样能撑爆目录项(且比写数据更廉价)
                    if (entries >= maxEntries) {
                        error("Workspace archive has too many entries (limit=$maxEntries)")
                    }
                    val target = resolveInside(stagingDir, entry.name)
                    when {
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            target.delete()
                            val linkName = entry.linkName
                            // 链接目标是**惰性数据**,解包时不会被跟随:
                            // 「经符号链接向外写入」已由 resolveInside 的 canonicalPath 前缀校验拦下
                            // (该路径会解析链接,一旦穿透则前缀不符而抛错),故此处按原样重建。
                            //
                            // 曾经此处对「绝对路径 / 含 ..」的链接一律降级为空文件 —— 那是错的:
                            //   · rootfs 用户区(etc/alternatives、usr/local/lib、etc/rc*.d 等)
                            //     本就大量使用绝对链接与 ../../ 形式的相对链接;
                            //   · 导出端原样写出这些链接,导入端却把它们杀掉,
                            //     导致自己产出的归档往返一次即静默丢内容(A1 已由 CI 证实)。
                            // 安全性未削弱:向外的写入仍被 resolveInside 拒绝(SEC 用例守护)。
                            if (linkName.isNotEmpty()) {
                                runCatching {
                                    Files.createSymbolicLink(target.toPath(), Paths.get(linkName))
                                }.onFailure {
                                    // 个别平台建符号链接失败(权限等):退化为空文件,避免整体失败
                                    target.writeBytes(ByteArray(0))
                                }
                            } else {
                                // 空目标无意义,createSymbolicLink 会抛 —— 直接落空文件
                                target.writeBytes(ByteArray(0))
                            }
                        }

                        // 硬链接(tar LF_LINK):条目自身 size=0,内容在目标条目里。
                        // 不处理会被 else 当普通文件写出 0 字节 → 内容丢失(A2)。
                        // 链接目标同样经 resolveInside 校验,不允许指向 staging 之外;
                        // 无法建硬链接的环境(权限/跨设备)退化为复制内容。
                        entry.isLink -> {
                            target.parentFile?.mkdirs()
                            target.delete()
                            val linkTarget = resolveInside(stagingDir, entry.linkName)
                            runCatching {
                                Files.createLink(target.toPath(), linkTarget.toPath())
                            }.onFailure {
                                if (linkTarget.isFile) {
                                    // 退化为复制:同样占存储,一并计入预算
                                    totalBytes += linkTarget.length()
                                    if (totalBytes > maxTotalBytes) budgetExceeded(totalBytes, maxTotalBytes)
                                    linkTarget.copyTo(target, overwrite = true)
                                } else {
                                    target.writeBytes(ByteArray(0))
                                }
                            }
                        }

                        entry.isDirectory -> target.mkdirs()

                        else -> {
                            target.parentFile?.mkdirs()
                            // 逐块计数写入:超预算立即抛错(不把超限部分写盘)
                            target.outputStream().use { out ->
                                val buffer = ByteArray(IO_BUFFER)
                                while (true) {
                                    val read = tar.read(buffer)
                                    if (read < 0) break
                                    totalBytes += read
                                    if (totalBytes > maxTotalBytes) budgetExceeded(totalBytes, maxTotalBytes)
                                    out.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    if (!entry.isSymbolicLink && entry.mode != 0) {
                        applyUnixMode(target, entry.mode)
                    }
                    if (!entry.isDirectory && !entry.isSymbolicLink) {
                        runCatching { target.setLastModified(entry.modTime.time) }
                    }
                    entries++
                    onProgress(WorkspaceArchiveProgress(entries, 0, entry.name))
                }
            }
        }
    }

    /**
     * 递归合并 [source] 到 [target]:目录/文件/符号链接按需复制覆盖,保留可执行等权限。
     * 目标中多出的文件保留(合并语义,不做镜像同步)。
     */
    fun mergeTree(source: File, target: File) {
        if (!source.exists()) return
        target.mkdirs()
        Files.walkFileTree(source.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val dst = target.toPath().resolve(source.toPath().relativize(dir))
                dst.toFile().mkdirs()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val dst = target.toPath().resolve(source.toPath().relativize(file))
                dst.parent?.toFile()?.mkdirs()
                val src = file.toFile()
                if (Files.isSymbolicLink(file)) {
                    dst.toFile().delete()
                    runCatching {
                        Files.createSymbolicLink(dst, Files.readSymbolicLink(file))
                    }
                } else if (src.isFile) {
                    src.copyTo(dst.toFile(), overwrite = true)
                    runCatching { dst.toFile().setLastModified(src.lastModified()) }
                    if (src.canExecute()) runCatching { dst.toFile().setExecutable(true, false) }
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * 列出归档内所有条目路径(含目录前缀),导入分析用(判断含 files/linux 用户区/tools 等)。
     */
    fun listArchivePaths(input: InputStream): Set<String> {
        val paths = LinkedHashSet<String>()
        GzipCompressorInputStream(BufferedInputStream(input, IO_BUFFER)).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    // 目录条目名常带尾斜杠(files/),过滤空段即可:原写法 `segments.dropLast(1)`
                    // 的返回值未被接收,等于没执行,于是产出 "files/" 这类尾斜杠伪路径(A3)。
                    val segments = entry.name.split('/').filter { it.isNotEmpty() }
                    var acc = ""
                    for ((i, seg) in segments.withIndex()) {
                        acc = if (acc.isEmpty()) seg else "$acc/$seg"
                        paths += acc
                    }
                }
            }
        }
        return paths
    }

    /** 读取归档内单个文件内容(不存在返回 null);大文件请勿用于整个归档 */
    fun readArchiveFile(input: InputStream, targetName: String): ByteArray? {
        GzipCompressorInputStream(BufferedInputStream(input, IO_BUFFER)).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == targetName) {
                        // size 防线:越界/超限 → 视为不可读(null),避免 toInt() 溢出后 readNBytes 抛异常。
                        // 这里读的是**归档内用户文件**(如 tools/tools.txt),不能用 manifest 的 512KB 阈值:
                        // 否则稍大的清单会被静默当成读不到(实测 600KB 即返回 null,A4)。
                        if (entry.size < 0 || entry.size > MAX_ARCHIVE_FILE_BYTES) return null
                        return tar.readNBytes(entry.size.toInt())
                    }
                }
            }
        }
        return null
    }

    // ---- 内部 ----

    private const val IO_BUFFER = 64 * 1024
    private const val MANIFEST_ESTIMATE_BYTES = 4096L
    private const val MAX_MANIFEST_BYTES = 512 * 1024

    /**
     * 解压总量上限默认值(audit A5 加固)。取 8 GiB —— 远高于合法用途
     * (rootfs 用户区 + 文件区),仅作兜底;真实调用由调用方按可用空间收紧
     * (见 WorkspaceRepository 的预算计算),否则手机上一个「合法地很大」的
     * 工作区会被误拒。
     */
    const val MAX_EXTRACT_TOTAL_BYTES = 8L * 1024 * 1024 * 1024

    /** 解压条目数上限默认值:防海量空条目耗目录项(单条目 tar 头仅 512 字节,伪造成本极低) */
    const val MAX_EXTRACT_ENTRIES = 200_000L

    /** 归档内单个**用户文件**(tools/tools.txt 等)的读取上限,与 manifest 阈值分开(A4) */
    private const val MAX_ARCHIVE_FILE_BYTES = 8 * 1024 * 1024

    private data class Section(val dir: File, val archiveName: String)

    private fun measureSection(section: Section): Long {
        var total = 0L
        Files.walkFileTree(section.dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!Files.isSymbolicLink(file) && Files.isRegularFile(file)) {
                    total += runCatching { Files.size(file) }.getOrDefault(0L)
                }
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }

    private fun writeBytesEntry(tar: TarArchiveOutputStream, name: String, bytes: ByteArray, mode: Int) {
        val entry = TarArchiveEntry(name, TarConstants.LF_NORMAL)
        entry.size = bytes.size.toLong()
        entry.mode = mode
        tar.putArchiveEntry(entry)
        tar.write(bytes)
        tar.closeArchiveEntry()
    }

    private fun writeSection(
        tar: TarArchiveOutputStream,
        section: Section,
        onEntry: (entryName: String, bytesWritten: Long) -> Unit,
    ) {
        val root = section.dir.toPath()
        writeDirEntry(tar, section.archiveName)
        onEntry(section.archiveName, 0)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == root) return FileVisitResult.CONTINUE
                val name = entryName(dir)
                if (shouldSkipInEtc(name)) return FileVisitResult.SKIP_SUBTREE
                writeDirEntry(tar, name)
                onEntry(name, 0)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = entryName(file)
                if (shouldSkipInEtc(name)) return FileVisitResult.CONTINUE
                if (Files.isSymbolicLink(file)) {
                    val entry = TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply {
                        linkName = runCatching { Files.readSymbolicLink(file).toString() }.getOrDefault("")
                        mode = 0x1FF // 0777
                    }
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                    onEntry(name, 0)
                    return FileVisitResult.CONTINUE
                }
                if (Files.isRegularFile(file)) {
                    val size = runCatching { Files.size(file) }.getOrDefault(0L)
                    val entry = TarArchiveEntry(name, TarConstants.LF_NORMAL).apply {
                        this.size = size
                        mode = unixMode(file.toFile())
                    }
                    tar.putArchiveEntry(entry)
                    var written = 0L
                    Files.newInputStream(file).use { input ->
                        val buffer = ByteArray(IO_BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            tar.write(buffer, 0, read)
                            written += read
                        }
                    }
                    tar.closeArchiveEntry()
                    onEntry(name, written)
                }
                return FileVisitResult.CONTINUE
            }

            fun entryName(path: Path): String = section.archiveName + "/" +
                root.relativize(path).joinToString("/") { it.fileName.toString() }
        })
    }

    private fun shouldSkipInEtc(name: String): Boolean {
        // 仅 linux/etc 内的文件按机器级清单跳过(发行版本体不含,只在打包用户区 etc 时生效)
        if (!name.startsWith("linux/etc/")) return false
        val tail = name.removePrefix("linux/etc/")
        if (tail.isEmpty()) return false
        // 顶层文件:命中 ETC_EXCLUDE_FILES 则跳过(目录不命中,保证能进到子目录)
        if (tail.indexOf('/') < 0) return tail in ETC_EXCLUDE_FILES
        // etc/ssh/ 下的 host 私钥:机器身份,换机应重新生成
        val parent = tail.substringBeforeLast('/')
        val file = tail.substringAfterLast('/')
        return parent == "ssh" && SSH_HOST_KEY.matches(file)
    }

    private fun writeDirEntry(tar: TarArchiveOutputStream, name: String) {
        val entry = TarArchiveEntry(name, TarConstants.LF_DIR)
        entry.mode = 0x1ED // 0755
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
    }

    /** 尽量读完整 unix mode(含 suid);Android 不支持 unix 视图时退化为可执行位 */
    private fun unixMode(file: File): Int {
        val mode = runCatching {
            (Files.getAttribute(file.toPath(), "unix:mode") as? Number)?.toInt()
        }.getOrNull()
        if (mode != null && mode != 0) return mode
        return if (file.canExecute()) 0x1ED else 0x1A4 // 0755 / 0644
    }

    /** 恢复权限:优先 unix 视图全量还原,失败时用 File API 粗略还原(含可执行位) */
    private fun applyUnixMode(file: File, mode: Int) {
        val applied = runCatching {
            Files.setAttribute(file.toPath(), "unix:mode", mode)
        }.isSuccess
        if (applied) return
        runCatching {
            file.setReadable(mode and 0x124 != 0, false)
            file.setWritable(mode and 0x92 != 0, false)
            file.setExecutable(mode and 0x49 != 0, false)
        }
    }

    /** 归档内路径解析 + 穿越防护:必须落在 base 内 */
    private fun resolveInside(base: File, name: String): File {
        val target = File(base, name).normalize()
        val basePath = base.canonicalPath.trimEnd('/')
        val targetPath = target.canonicalPath
        check(targetPath == basePath || targetPath.startsWith("$basePath/")) {
            "Unsafe archive entry path: $name"
        }
        return target
    }
}
