// [X-custom] RikkaHub-X 诊断框架：导出进度上报
package me.rerere.rikkahub.x.diag

import java.io.InputStream

/**
 * 导出进行到哪个阶段。
 *
 * 分两段是必要的:**读两遍** —— 压缩包要先通读一遍才知道「掩了几处凭据」,而那个数要写在
 * 包内清单的最前面(见 [XDiagZip.write])。不分开的话,进度条会在「其实还在读」的时候
 * 显示满格 —— 那比没有进度更糟:用户会以为卡住了。
 */
enum class XExportPhase { ANALYSING, WRITING }

/**
 * 导出进度。
 *
 * [processed] 与 [total] 都是**输入侧的字节数**。用字节而不是百分比:百分比由显示方算,
 * 于是「怎么算」只该有一处(见 [XDiagExportNotifier.percentOf])。
 *
 * ⚠️ [total] 允许为 0(内容为空,或总量还没算出来)。**显示方必须能处理** —— 除零会崩。
 * ⚠️ [processed] 允许**超过** [total]:日志文件正被捕获线程追加时,读到的字节会比开始时
 * 量到的多。显示方要夹到 100%,不能让进度条跑出界。
 */
fun interface XExportProgress {
    fun report(phase: XExportPhase, processed: Long, total: Long)
}

/** 不关心进度时用它 —— 比让各处传 `null` 再判空好读。 */
val NoExportProgress: XExportProgress = XExportProgress { _, _, _ -> }

/**
 * 边读边报「读了多少字节」的包装流。
 *
 * 用**真实字节**而不是「行数 × 每行字符数」:后者对中文会低估(UTF-8 里一个汉字占 3 字节),
 * 于是进度条永远差一截到不了头。也不能按行数估算 —— 那要先知道总行数,又是一遍全读。
 *
 * 放在这里而不是某个具体导出实现里:压缩包与单文件两条导出路径都要用它,
 * 各写一份迟早漂移(这类重复在本项目已经吃过亏)。
 */
internal fun countingStream(source: InputStream, onRead: (Long) -> Unit): InputStream =
    object : InputStream() {
        override fun read(): Int = source.read().also { if (it >= 0) onRead(1L) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            source.read(b, off, len).also { if (it > 0) onRead(it.toLong()) }

        override fun available(): Int = source.available()

        override fun close() = source.close()
    }

/**
 * 字节数 → 0~100;算不出时返回 `null`。
 *
 * ## 为什么总量为 0 返回 `null` 而不是 0
 *
 * 显示方据此选择「转圈」而不是「0% 的条」—— 后者看着像卡死,而这条进度存在的意义
 * 正是**别让用户以为卡死**。
 *
 * ## 为什么夹到 100
 *
 * `processed` 允许超过 `total`:日志文件正被捕获线程追加时,读到的字节会比开始时量到的多。
 * 不夹的话进度会跑出界(负数宽度或 >100)。
 *
 * 纯函数,放在这里而不是通知器里:通知器碰 Android,而这条边界逻辑值得被 JVM 单测直接钉住。
 */
internal fun percentOf(processed: Long, total: Long): Int? {
    if (total <= 0L) return null
    if (processed <= 0L) return 0
    return ((processed * 100) / total).coerceIn(0L, 100L).toInt()
}
