// [X-custom] RikkaHub-X 资产层：内容寻址的落盘执行体
package me.rerere.rikkahub.x.storage

import java.io.BufferedOutputStream
import java.io.DigestOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 一次写入的结果。
 *
 * [kind] 让调用方知道实际发生了什么 —— 有多个写入点，但**只有一处**该决定怎么记日志，
 * 所以这里不自带日志，把「发生了什么」如实交出去。
 */
data class WrittenAsset(
    /** 落盘路径（相对 `filesDir`）。复用与补写时是**既有路径**，不一定是本次算出的路径。 */
    val relativePath: String,
    val file: File,
    val hash: String,
    val kind: AssetWriteKind,
) {
    /** 是否没有真的写盘（内容已在盘上）。 */
    val reused: Boolean get() = kind == AssetWriteKind.REUSED
}

/** 一次写入实际走的分支。与 [XStorageEvents] 的三个写入事件一一对应。 */
enum class AssetWriteKind {
    /** 未见过这份内容 —— 落到内容寻址路径。 */
    NEW,

    /** 同一份内容已在盘上 —— **没有落第二份**（这就是去重）。 */
    REUSED,

    /** 账本有登记但文件不在 —— 按**原路径**补写，保住已经在引用它的消息。 */
    REWRITTEN,
}

/**
 * 内容寻址的落盘执行体：**算哈希 → 查账本 → 按计划落盘或复用 → 登记**。
 *
 * ## 为什么单独一层
 *
 * 这段流程要服务 6 个写入点（贴图 / 选文件 / 字节 / 文本 / 流），它们分处不同调用场景，
 * 有的在挂起函数里、有的在 Compose 回调里。收在一处才能保证判据只写一遍 ——
 * 否则将来改规则必须改六遍，必漏。
 *
 * ## 不记日志是刻意的
 *
 * 记日志要用 `XLog`，而 `XLog` 走 `android.util.Log`，在 JVM 单测里会抛
 * 「not mocked」（本项目单测未开 `isReturnDefaultValues`，`XLogRing` 当初就是因此
 * 不引 Android API）。把日志留给调用方，本类就只剩下纯文件操作 + 纯判据，**可以在单测里
 * 跑完整流程** —— 而去重行为正是本阶段最该被用例守住的东西。
 */
class AssetWritePath(
    /** 应用私有文件根目录（`context.filesDir`）。 */
    private val filesDir: File,
    private val ledger: AssetLedger,
) {

    private companion object {
        /** 临时目录名。放在 `filesDir` 内是为了与目标同文件系统，搬移退化为改名。 */
        const val TEMP_DIR = ".x-tmp"

        const val READ_BUFFER_BYTES = 64 * 1024

        /**
         * 临时文件序号。
         *
         * 光靠 `nanoTime` 不够：多个附件可以并发写入（一次选多张图就会），
         * 而同一纳秒拿到同一个名字会让两条写入互相覆盖 —— 表现是「其中一张图内容错乱」，
         * 且只在并发时偶发。
         */
        val tempSequence = java.util.concurrent.atomic.AtomicLong()
    }

    /**
     * 写入一份**已在内存里**的内容（字节）。
     *
     * 已知内容就直接算哈希，**复用时不产生任何临时文件** —— 这是字节/文本路径相对
     * 流式路径的优势，也是贴图这类场景的常态。
     */
    fun writeBytes(extension: String?, bytes: ByteArray): WrittenAsset =
        place(hash = AssetHash.of(bytes), extension = extension) { target ->
            target.writeBytes(bytes)
        }

    /**
     * 写入一份文本。
     *
     * 与 [writeBytes] 的差别只在编码：显式用 UTF-8 并把内容转成字节后按同一套流程走，
     * 避免「文本路径」与「字节路径」各写一份判据。
     */
    fun writeText(extension: String?, text: String): WrittenAsset =
        writeBytes(extension, text.toByteArray(Charsets.UTF_8))

    /**
     * 写入一个**只能读一遍**的流（`content://` 就是这种）。
     *
     * 流不可重复读，故先在临时文件里落一份**同时算哈希**，再按计划搬走或丢弃。
     * 代价是：命中去重时也付一次临时写入（换来最终不落第二份永久文件）。
     * 这是单遍读的必然成本 —— 想省掉它只能要求调用方给可重读的来源。
     */
    fun writeStream(extension: String?, source: InputStream): WrittenAsset {
        val temp = newTempFile()
        try {
            val digest = AssetHash.newDigest()
            copyInto(temp, source, digest)
            return place(hash = AssetHash.hexOf(digest.digest()), extension = extension) { target ->
                move(temp, target)
            }
        } finally {
            // 搬移成功后这是一次无副作用的删除；命中复用或中途失败时，它清掉临时文件
            temp.delete()
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 内部
    // ────────────────────────────────────────────────────────────────

    /**
     * 落盘的核心：查账本 → 规划 → 执行。
     *
     * [writeContent] **只在需要真的写盘时被调用**；命中去重时不会被调用，
     * 所以不会出现「先写一份再删掉」的浪费（流式路径的临时文件在 [writeStream] 里处理）。
     */
    private fun place(
        hash: String,
        extension: String?,
        writeContent: (File) -> Unit,
    ): WrittenAsset {
        val known = ledger.findKnown(hash)
        return when (val plan = AssetStorePolicy.plan(hash, extension, known)) {
            is AssetStorePlan.ReuseExisting -> {
                // 同一份内容已在盘上 —— 到这里就结束，不写第二个文件
                WrittenAsset(
                    relativePath = plan.relativePath,
                    file = resolve(plan.relativePath),
                    hash = hash,
                    kind = AssetWriteKind.REUSED,
                )
            }

            is AssetStorePlan.WriteNew -> {
                val target = writeAt(plan.relativePath, writeContent)
                ledger.recordAsset(
                    hash = hash,
                    relativePath = plan.relativePath,
                    byteSize = target.length(),
                    nowMillis = System.currentTimeMillis(),
                )
                WrittenAsset(plan.relativePath, target, hash, AssetWriteKind.NEW)
            }

            is AssetStorePlan.RewriteMissing -> {
                // 账本有登记但盘上没了 —— 用**原路径**重写，已经在引用它的消息不必改
                val target = writeAt(plan.relativePath, writeContent)
                WrittenAsset(plan.relativePath, target, hash, AssetWriteKind.REWRITTEN)
            }
        }
    }

    private fun writeAt(relativePath: String, writeContent: (File) -> Unit): File {
        val target = resolve(relativePath)
        writeContent(target)
        return target
    }

    /** 相对路径 → 绝对文件，并确保父目录存在（分片目录可能是第一次创建）。 */
    private fun resolve(relativePath: String): File =
        File(filesDir, relativePath).also { it.parentFile?.mkdirs() }

    private fun newTempFile(): File {
        val dir = File(filesDir, TEMP_DIR)
        dir.mkdirs()
        return File(dir, "w-${System.nanoTime()}-${tempSequence.incrementAndGet()}.tmp")
    }

    /** 拷贝进临时文件并**同时**喂摘要器，返回字节数。 */
    private fun copyInto(target: File, source: InputStream, digest: MessageDigest): Long {
        var total = 0L
        target.outputStream().use { fileOut ->
            DigestOutputStream(BufferedOutputStream(fileOut), digest).use { out ->
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    // 只有 -1 是结束；read 返回 0 是合法的「暂无进展」，不能当作 EOF
                    val read = source.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        total += read
                    }
                }
            }
        }
        return total
    }

    /**
     * 搬移临时文件到目标位置。
     *
     * 先试改名（同一文件系统内不复制数据），不成立再退化为拷贝 + 删源。
     * 临时目录与目标同在 `filesDir` 下，正常总能改名成功；退化分支只为兜底。
     */
    private fun move(from: File, to: File) {
        if (from.renameTo(to)) return
        from.inputStream().use { input ->
            to.outputStream().use { output -> input.copyTo(output) }
        }
        from.delete()
    }
}
