package me.rerere.common.android

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

private const val MAX_RECENT_LOGS = 100

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        /**
         * [X-custom] **非 2xx** 响应的一小段正文（2026-09-13 加）。
         *
         * 状态码只说「被拒了」，**原因写在正文里** —— 实测 2026-09-13：同一个
         * `/v1/user/balance` 一次 401、一次 200，而当时**没有任何途径**能看到那个 401
         * 说了什么（Firebase 也看不到：上游没接 Performance Monitoring，
         * Crashlytics 只收崩溃，而非致命异常上报全项目 0 处）。
         *
         * 只对非 2xx 取值：2xx 的 chat 请求是 SSE 流，取正文会碰流式（见
         * `RequestLoggingInterceptor` 的注释）。默认 `null` —— 2xx 与「取不到」都是它。
         */
        val responseBody: String? = null,
        /**
         * [X-custom] 上面那段正文**是否被截断**（超过上限时无法完整带走）。
         *
         * 单独一个字段而不是往正文里塞标记：正文可能是 JSON，塞标记会把它弄坏。
         * 也**不许静默截断** —— 读的人必须能判断自己看到的是不是全部。
         */
        val responseBodyTruncated: Boolean = false,
        val durationMs: Long? = null,
        val error: String? = null
    ) : LogEntry()
}

object Logging {
    private val recentLogs = arrayListOf<LogEntry>()
    @Volatile
    private var requestLoggingEnabled = false

    /**
     * [X-custom] 请求记录的**旁路回调** —— RikkaHub-X 的诊断框架接在这里,把每条请求记录
     * 同步写一份到会话目录的 `net.log`。
     *
     * ## 为什么是回调而不是让 X 去读 [getRequestLogs]
     *
     * 那个环形缓冲只有 100 条、且进程一死就没 —— 正是 X 要补的缺口。轮询它还有两个毛病:
     * 有延迟(最多一个轮询周期)、且窗口内超过 100 条就会静默漏掉。
     *
     * 挂在这里则**一条不漏**:本函数是所有请求记录的**唯一漏斗**(`RequestLoggingInterceptor`
     * 的两处调用都走它)。
     *
     * ## 与 [recentLogs] 的关系
     *
     * 不是替代 —— 那份留着(应用内「日志」页要展示)。这里只是**多送一份**出去。
     *
     * **可为 null**(没有消费者时):判断一次空引用,开销可忽略。
     */
    @Volatile
    private var requestLogSink: ((LogEntry.RequestLog) -> Unit)? = null

    /** [X-custom] 接上旁路回调;传 `null` 摘掉。 */
    fun setRequestLogSink(sink: ((LogEntry.RequestLog) -> Unit)?) {
        requestLogSink = sink
    }

    fun log(tag: String, message: String) {
        addLog(LogEntry.TextLog(tag = tag, message = message))
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!requestLoggingEnabled) return
        addLog(entry)
        // [X-custom] 旁路一份给诊断框架。放在开关判断**之后** —— 于是「关掉诊断就一条都不记」
        // 这条承诺对请求日志同样成立。回调抛异常不该影响上游取日志,故吞掉。
        runCatching { requestLogSink?.invoke(entry) }
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled = enabled
    }

    private fun addLog(entry: LogEntry) {
        synchronized(recentLogs) {
            recentLogs.add(0, entry)
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeLastOrNull()
            }
        }
    }

    fun getRecentLogs(): List<LogEntry> {
        synchronized(recentLogs) {
            return recentLogs.toList()
        }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.TextLog>()
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.RequestLog>()
        }
    }

    fun clear() {
        synchronized(recentLogs) {
            recentLogs.clear()
        }
    }
}
