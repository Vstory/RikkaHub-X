// [X-custom] RikkaHub-X 存储管理重构(P1)：X 存储层的日志缓冲(纯逻辑,不依赖 Android)
package me.rerere.rikkahub.x.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * X 存储层的日志缓冲 —— **纯内存、纯逻辑，不碰 Android**。
 *
 * **为什么与 [XStorageLog] 分成两个文件**：本类要能在 JVM 单测里直接验证
 * （容量、丢弃方向、顺序、导出格式），而**写 logcat 必须调 `android.util.Log`** ——
 * 本项目的单测没有开 `testOptions.unitTests.isReturnDefaultValues`，
 * 在 JVM 里调它**会抛 "not mocked"**。混在一个类里，缓冲逻辑就跟着变得不可测。
 * 分开之后：纯的部分随便测，Android 的部分只做转发。
 *
 * **为什么不复用上游的 `me.rerere.common.android.Logging`**：那份缓冲**全局共享且只留 100 条**，
 * 上游自己在 5 处失败路径里也用它。X 存储的埋点密集得多（每次保存会话都记引用同步）——
 * 混着用会互相挤掉，**最需要的那段日志恰好被另一边的噪声冲走**。故自带独立缓冲。
 */
object XStorageLogBuffer {

    /** logcat 的 tag。与 [AssetRepository] / `ConversationRepository` 的 TAG 保持一致。 */
    const val TAG = "XStorage"

    /** 缓冲上限。500 条足够覆盖「清空 → 复现一轮操作 → 导出」的窗口。 */
    const val MAX_ENTRIES = 500

    /** 空缓冲的导出文案 —— 不返回空串，否则界面上分不清「没日志」和「界面坏了」。 */
    const val EMPTY_DUMP = "(无 X 存储日志)"

    enum class Level { INFO, WARN }

    /** 日志级别在导出文本里的标记。 */
    private const val WARN_MARK = "[注意]"

    data class Entry(
        val at: Long,
        val level: Level,
        val event: String,
        val message: String,
    ) {
        /** `HH:mm:ss.SSS` —— 够分辨先后即可；不带日期，免得每行过长。 */
        fun timeText(): String = TIME_FORMAT.get()!!.format(Date(at))
    }

    /** 每线程一个格式化器：`SimpleDateFormat` **非线程安全**，共享会写出错乱的时间。 */
    private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private val entries = ArrayList<Entry>(MAX_ENTRIES)

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
            // 超出上限时从**最旧**一端丢弃 → 缓冲里永远是最新的 MAX_ENTRIES 条
            if (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
        }
    }

    /** 当前内容，**由旧到新**。 */
    fun recent(): List<Entry> = synchronized(entries) { entries.toList() }

    fun size(): Int = synchronized(entries) { entries.size }

    fun clear() = synchronized(entries) { entries.clear() }

    /**
     * 导出为可复制的纯文本，**由旧到新**并带序号。
     *
     * 顺序不能倒：用户复制的是一段有限窗口，顺序读了才知道发生了什么；
     * 倒序会把「先新建、后复用」这类因果读反。
     *
     * @param events 只导出这些事件名；传 `null` 表示全部。
     */
    fun dump(events: List<String>? = null): String = buildString {
        val snapshot = recent().filter { events == null || it.event in events }
        if (snapshot.isEmpty()) {
            append(EMPTY_DUMP)
            return@buildString
        }
        snapshot.forEachIndexed { index, entry ->
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
            append(entry.message)
            append('\n')
        }
    }

    /** logcat 过滤命令 —— 显示在诊断页上，省得用户去查。 */
    fun logcatHint(): String = "adb logcat -s $TAG:*"
}
