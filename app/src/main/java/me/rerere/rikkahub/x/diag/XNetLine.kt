// [X-custom] RikkaHub-X 诊断框架：网络请求记录的落盘行格式(纯逻辑)
package me.rerere.rikkahub.x.diag

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.common.android.LogEntry

/**
 * 一条 HTTP 请求记录 → **一行紧凑 JSON**。
 *
 * ## 字段为什么与语义事件那套不同
 *
 * `<域>.log` 里是「X 做了什么」的语义事件(`{at,lvl,domain,event,msg}`,统一格式)。
 * `net.log` 里是「应用往外发了什么」—— 字段天然不同,且**关键字段要能一眼 grep 到**:
 * 想看某次请求的正文,`grep '"reqBody"' net.log` 就该命中,而不是先打开 `msg` 再解开一层。
 *
 * 故这里用**专用字段名**,而不是把它塞进 `msg`。不变的是那条硬约束:**一条记录恰好占一行**
 * (请求正文里的换行由 JSON 转义),见 `XDiagLine` 的类注释。
 *
 * ## 请求正文是完整的,不做截断
 *
 * 诊断的整个价值就在「模型到底看到了什么」,截断等于把要看的东西裁掉;而截在哪儿都错 ——
 * 聊天请求的系统提示在前、最新消息在后,砍头砍尾都会丢掉关键那一段。
 * 总量由 [XDiagFileStore.MAX_BYTES_PER_DOMAIN] 兜底,到顶会留下一条可见的记录。
 *
 * ⚠️ 由此带来一个**已知特性**:长对话的请求正文可达数百 KB,于是**单行会很长**。
 * 这是刻意的取舍(不丢数据),不是疏漏。
 *
 * ## 响应正文只在**非 2xx** 时才有(2026-09-13 加)
 *
 * 字段名 `respBody`,同样不截断(上限 256 KB,超过则置 `respBodyTruncated` 为真)。
 * 为什么只记非 2xx、为什么不能记 2xx:见 `RequestLoggingInterceptor.peekErrorBody` ——
 * 一句话是「2xx 的对话响应是 SSE 流,取它会把流式打断」。
 *
 * ## 为什么不进 logcat
 *
 * logd 单条上限约 4 KB,而请求正文动辄几十上百 KB —— 打进去会被**静默截断**成一段看着像
 * 完整 JSON 的残片,比没有更糟。故只落文件。
 */
object XNetLine {

    /**
     * 组一行。
     *
     * @param at 由调用方决定(便于单测);生产路径传 [LogEntry.RequestLog.timestamp]。
     */
    internal fun format(entry: LogEntry.RequestLog, at: Long = entry.timestamp): String =
        buildJsonObject {
            put("at", XLogRing.timeText(at))
            put("method", entry.method)
            put("url", entry.url)
            entry.responseCode?.let { put("code", it) }
            entry.durationMs?.let { put("durationMs", it) }
            // 出错的那次没有响应码与耗时,只有这条 —— 否则那行会像「一条没写完的记录」。
            entry.error?.let { put("error", it) }
            if (entry.requestHeaders.isNotEmpty()) {
                putJsonObject("reqHeaders") { entry.requestHeaders.forEach { (k, v) -> put(k, v) } }
            }
            entry.requestBody?.let { put("reqBody", it) }
            // 非 2xx 的响应正文(2026-09-13 加)。放在请求体**之后**:读的人先看「我们发了什么」,
            // 再看「服务端回了什么」,与真实请求的时间顺序一致。
            entry.responseBody?.let { put("respBody", it) }
            // 被上限截断时**必须标出来** —— 否则读的人会以为自己看到了完整的错误。
            // 只在为真时输出:2xx 与「没截断」都是不写。
            if (entry.responseBodyTruncated) put("respBodyTruncated", true)
            if (entry.responseHeaders.isNotEmpty()) {
                putJsonObject("respHeaders") { entry.responseHeaders.forEach { (k, v) -> put(k, v) } }
            }
        }.toString()
}
