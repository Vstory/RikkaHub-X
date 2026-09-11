// [X-custom] RikkaHub-X 诊断框架：环状日志缓冲(纯逻辑,每个诊断域各持一份)
package me.rerere.rikkahub.x.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 环状日志缓冲 —— **纯内存、纯逻辑，不碰 Android**。
 *
 * 做成可实例化的类（而非单例）：每个诊断域各持一份，互不挤占。
 * 上游那种「全局共享一份」的缓冲在多域埋点下必然互相冲掉 ——
 * 而排查时最需要的那段，恰好是被挤走的那段。
 *
 * 本类要能在 JVM 单测里直接验证容量、丢弃方向、顺序与格式，
 * 故**不引用 `android.util.Log`**（本项目单测未开 `isReturnDefaultValues`，
 * 在 JVM 里调它会抛 "not mocked"）。写 logcat 由 [XLog] 负责。
 */
class XLogRing(val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /**
         * logcat 的 tag。**整个 X 定制共用一个** —— 过滤时一条命令看全：
         * `adb logcat -s XCustom:*`。
         *
         * 为什么必须统一：X 的功能散在 `x/context`、`x/sync`、`x/storage` 等多个包，
         * 此前各文件各写各的 tag（`ContextWindowRepo` / `OfficialBackupCompat` / `XStorage`），
         * 想一次看全「X 到底干了什么」得拼接多个过滤条件 —— 实际结果是没人这么看，
         * 排查时只好逐个文件猜。**统一 tag 是「可观测」这件事的前提**。
         *
         * ⚠️ 别改回 `XStorage`：那个名字只描述存储层，而现在它承载整个 X 定制的日志。
         */
        const val TAG = "XCustom"

        /**
         * 单域默认上限。
         *
         * 2000 条 × 单条约 200 字节 ≈ 400KB/域 —— 十几个域也就几 MB，
         * 对长窗口（开着诊断跑一整天）够用，又不会把内存吃掉。
         */
        const val DEFAULT_CAPACITY = 2000

        /** 注意级在导出文本里的标记 —— 扫日志时能一眼分辨。 */
        const val WARN_MARK = "[注意]"

        /** 每线程一个格式化器：`SimpleDateFormat` **非线程安全**，共享会写出错乱的时间。 */
        private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }

        /**
         * `HH:mm:ss.SSS` 时间文本。
         *
         * 放在 companion 里供**表头与正文共用** —— 两处格式若不一致，
         * 同一份导出里会出现两种时间写法，读者会以为来自不同来源。
         */
        fun timeText(at: Long): String = TIME_FORMAT.get()!!.format(Date(at))
    }

    enum class Level { INFO, WARN }

    data class Entry(
        val at: Long,
        val level: Level,
        val event: String,
        val message: String,
    ) {
        /** `HH:mm:ss.SSS` —— 够分辨先后即可；不带日期，免得每行过长。 */
        fun timeText(): String = timeText(at)
    }

    private val entries = ArrayList<Entry>(minOf(capacity, 256))

    /**
     * 记一条。
     *
     * [error] 非空时把**类型与消息**附在末尾 —— **不写堆栈**：
     * 缓冲是给人快速扫的，一段堆栈会瞬间占满几十行并把其它事件挤出去；
     * 真需要堆栈时 logcat 里有完整版本。
     */
    fun record(level: Level, event: String, message: String, error: Throwable? = null) {
        val text = if (error == null) {
            message
        } else {
            "$message | ${error::class.java.simpleName}: ${error.message.orEmpty()}"
        }
        synchronized(entries) {
            entries.add(Entry(at = System.currentTimeMillis(), level = level, event = event, message = text))
            // 超出上限时从**最旧**一端丢弃 → 缓冲里永远是最新的 capacity 条
            while (entries.size > capacity) {
                entries.removeAt(0)
            }
        }
    }

    /** 当前内容，**由旧到新**。 */
    fun recent(): List<Entry> = synchronized(entries) { entries.toList() }

    fun size(): Int = synchronized(entries) { entries.size }

    fun isEmpty(): Boolean = size() == 0

    fun clear() = synchronized(entries) { entries.clear() }

    /**
     * 格式化为可复制的纯文本，**由旧到新**并带序号。
     *
     * 顺序不能倒：用户复制的是一段有限窗口，顺序读了才知道发生了什么；
     * 倒序会把「先新建、后复用」这类因果读反。
     */
    fun format(
        entries: List<Entry> = recent(),
        redact: (String) -> String = { it },
    ): String = buildString {
        entries.forEachIndexed { index, entry ->
            append(index + 1)
            append(". ")
            append(entry.timeText())
            append("  ")
            if (entry.level == Level.WARN) {
                append(WARN_MARK)
                append(" ")
            }
            append(entry.event)
            append("  ")
            append(redact(entry.message))
            append('\n')
        }
    }
}
