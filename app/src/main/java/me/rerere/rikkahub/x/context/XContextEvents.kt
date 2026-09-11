// [X-custom] RikkaHub-X 诊断框架：容量表/用量域的事件名
package me.rerere.rikkahub.x.context

/**
 * 容量表与上下文用量域的日志事件名。
 *
 * 与存储域同样的规则：`域.动作.结果`，全小写点分，
 * 集中成常量以便**日志可检索**（用户导出的诊断里靠这些词定位）。
 *
 * 这些事件此前是散落在 [ContextWindowRepository] 里的中文 `Log.i` / `Log.w` 字面量 ——
 * 用户无法按关键词稳定检索，且关闭诊断后照样往 logcat 输出。
 * 现统一改为事件名 + 说明的两段式：**事件名供机器检索，说明供人阅读**。
 */
object XContextEvents {

    // ── 缓存 ──

    /** 本地缓存的表解析不通过（被可信闸门拒绝），将重新拉取。 */
    const val CACHE_UNUSABLE = "context.cache.unusable"

    /** 缓存写入失败。 */
    const val CACHE_WRITE_FAIL = "context.cache.write_fail"

    /** 更新时间戳失败。 */
    const val TIMESTAMP_WRITE_FAIL = "context.cache.timestamp_fail"

    // ── 自动重试链 ──

    /** 首次重试已排定。 */
    const val RETRY_SCHEDULED = "context.retry.scheduled"

    /** 后续重试已排定（第 n 次）。 */
    const val RETRY_RESCHEDULED = "context.retry.rescheduled"

    /** 重试次数用尽仍未取到新内容，转交定时更新。 */
    const val RETRY_EXHAUSTED = "context.retry.exhausted"

    /** 重试链超出时限，被丢弃。 */
    const val RETRY_TIMEOUT = "context.retry.timeout"

    /** 重试状态落盘失败。 */
    const val RETRY_STATE_WRITE_FAIL = "context.retry.state_write_fail"

    // ── 多源拉取 ──

    /** 一轮拉取完成，含各源结果。 */
    const val FETCH_RESULT = "context.fetch.result"

    /** 各源都不比本地新，保持本地表。 */
    const val SOURCES_NOT_NEWER = "context.fetch.sources_not_newer"

    /** 各源均未提供可用的表，保留现有表。 */
    const val NO_USABLE_TABLE = "context.fetch.no_usable_table"

    /** 远端不可达，保留现有表。 */
    const val REMOTE_UNREACHABLE = "context.fetch.remote_unreachable"

    /** 全部事件名，供单测核对。 */
    val ALL: List<String> = listOf(
        CACHE_UNUSABLE, CACHE_WRITE_FAIL, TIMESTAMP_WRITE_FAIL,
        RETRY_SCHEDULED, RETRY_RESCHEDULED, RETRY_EXHAUSTED, RETRY_TIMEOUT, RETRY_STATE_WRITE_FAIL,
        FETCH_RESULT, SOURCES_NOT_NEWER, NO_USABLE_TABLE, REMOTE_UNREACHABLE,
    )
}
