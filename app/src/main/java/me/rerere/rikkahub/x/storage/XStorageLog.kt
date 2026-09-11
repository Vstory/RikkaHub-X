// [X-custom] RikkaHub-X 存储管理重构(P1)：X 存储层日志的 Android 侧入口
package me.rerere.rikkahub.x.storage

import android.util.Log
import me.rerere.rikkahub.x.storage.XStorageLogBuffer.Level

/**
 * X 存储层日志的**对外入口**：写缓冲 + 镜像到 logcat。
 *
 * **为什么要镜像 logcat**：真机排查时 `adb logcat -s XStorage:*` 能实时看到，
 * 不必先在界面上复现一遍再看缓冲。上游的 `me.rerere.common.android.Logging`
 * **只进内存、不输出 logcat**，只靠它会导致 `adb logcat` 里什么都搜不到。
 *
 * 缓冲区与格式化逻辑在 [XStorageLogBuffer]（纯逻辑、可单测）；
 * 本对象只负责「也往 logcat 写一份」这一件 Android 相关的事。
 */
object XStorageLog {

    /** 与 [XStorageLogBuffer.TAG] 一致，便于调用方直接 `XStorageLog.TAG`。 */
    const val TAG = XStorageLogBuffer.TAG

    /** 记一条正常流程的事件。 */
    fun info(event: String, message: String) {
        Log.i(TAG, "$event $message")
        XStorageLogBuffer.record(Level.INFO, event, message)
    }

    /** 记一条需要注意的事件。[error] 只取类型与消息，堆栈请从 logcat 看。 */
    fun warn(event: String, message: String, error: Throwable? = null) {
        // logcat 侧保留完整异常,便于需要时深挖
        if (error == null) {
            Log.w(TAG, "$event $message")
        } else {
            Log.w(TAG, "$event $message", error)
        }
        XStorageLogBuffer.record(Level.WARN, event, message, error)
    }

    // ── 转发给缓冲（界面与导出用） ──

    fun recent() = XStorageLogBuffer.recent()

    fun size() = XStorageLogBuffer.size()

    fun clear() = XStorageLogBuffer.clear()

    fun dump(events: List<String>? = null) = XStorageLogBuffer.dump(events)

    fun logcatHint() = XStorageLogBuffer.logcatHint()
}
