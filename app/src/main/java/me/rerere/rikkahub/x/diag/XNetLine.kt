// [X-custom] RikkaHub-X 诊断框架：网络请求记录的落盘行格式(纯逻辑)
package me.rerere.rikkahub.x.diag

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.common.android.LogEntry

/**
 * 一条 HTTP 请求记录 → **一行紧凑 JSON**,与语义事件**同一形状**。
 *
 * ## 为什么形状与语义事件统一了(2026-09-13 改)
 *
 * 原先这里是**另一套字段**(只有 `at`/`method`/`url`/…,没有 `domain`/`event`/`lvl`),
 * 因为它当时**独占一个文件**(网络记录单独一份)。**合并进同一条时间线之后,
 * 两种形状是读不下去的** —— 读的人得逐行猜「这行是哪一类的」。故补齐核心字段:
 * `at` / `lvl` / `domain` / `event`,与 [XDiagLine] 完全对齐;专有字段(`method`/`code`/…)
 * 平铺在同一层,靠 `domain` 区分。
 *
 * 不变的是那条硬约束:**一条记录恰好占一行**(正文里的换行由 JSON 转义),
 * 理由见 [XDiagLine] 的类注释。
 *
 * ## ⚠️ 请求体**不落盘**,只记字节数(2026-09-13 用户决定)
 *
 * 这里此前记的是**完整请求正文**(system prompt + 聊天历史,可达数百 KB)。改为只记
 * `reqBytes`。两条理由,后一条是决定性的:
 *
 * ① **时间线会被冲垮**。合并成一条时间线之后,一条几百 KB 的记录会让「什么时候做了什么」
 *    完全读不出来 —— 而时间线可读正是合并的全部收益。
 * ② **脱敏器认不出聊天正文**。[XLogScrub] 只认**凭据的形态**(密钥、token、Authorization),
 *    它对「用户打的字」无能为力。请求体不落盘,等于**从源头掐掉包里最大的一块**,
 *    而不是指望导出时那一层过滤。
 *
 * **代价(诚实记下)**:包里从此看不到「模型到底看到了什么」。要看它需要在应用内
 * 「日志」页临时打开请求记录(上游内存缓冲,100 条,不为取证保留)。这是刻意的取舍。
 *
 * ## 响应正文仍然保留,但只在**非 2xx** 时
 *
 * 字段名 `respBody`,上限 256 KB(超过则置 `respBodyTruncated` 为真,不静默)。
 * 非 2xx 的正文是**几十字节到几 KB 的错误说明**(如 deepseek 的 401 是 65 字节)——
 * 它是「服务端为什么拒了」的唯一留存处,且体积不构成威胁。
 * 为什么不能记 2xx:见 `RequestLoggingInterceptor.peekErrorBody` —— 一句话是
 * 「2xx 的对话响应是 SSE 流,取它会把流式打断」。
 *
 * ## 为什么不进 logcat
 *
 * 这些字段里 `url` 与 `respBody` 都可能很长,而 logd 单条上限约 4 KB ——
 * 打进去会被**静默截断**成一段看着像完整记录的残片,比没有更糟。故只落文件。
 */
object XNetLine {

    /** 正常完成的一次请求。 */
    internal const val EVENT_SENT = "net.request.sent"

    /**
     * 出错的那次。
     *
     * 单列一个事件名而不是只靠 `error` 字段:出错的行**没有响应码与耗时**,
     * 一条 grep 就能把「所有失败」捞出来,比 `grep '"error"'` 稳
     * (后者会被正文里恰好出现 error 字样的行污染)。
     */
    internal const val EVENT_FAILED = "net.request.failed"

    /**
     * 组一行。
     *
     * @param at 由调用方决定(便于单测);生产路径传 [LogEntry.RequestLog.timestamp]。
     */
    internal fun format(entry: LogEntry.RequestLog, at: Long = entry.timestamp): String =
        buildJsonObject {
            // ── 与 XDiagLine 对齐的四个核心字段 ──
            put("at", XLogRing.timeText(at))
            put("lvl", if (entry.error != null) "W" else "I")
            put("domain", XDomain.NET.key)
            put("event", if (entry.error != null) EVENT_FAILED else EVENT_SENT)
            // ── net 专有字段(平铺,靠 domain 区分) ──
            put("method", entry.method)
            put("url", entry.url)
            entry.responseCode?.let { put("code", it) }
            entry.durationMs?.let { put("durationMs", it) }
            entry.error?.let { put("error", it) }
            // ⚠️ 只记**字节数**,不记正文(理由见类注释)。写字节数而不是字符数:
            //    传输量以字节计,而 UTF-8 下中文一字三字节,字符数会低报三分之一。
            //    只在有正文时输出 —— GET 类请求没有正文,写成 `"reqBytes":0` 会让
            //    「有没有正文」这个判断落空(与 test 里那条「不写 null 字段」同一条理由)。
            entry.requestBody?.let { put("reqBytes", it.toByteArray(Charsets.UTF_8).size) }
            // 非 2xx 的响应正文:错误说明,是「服务端为什么拒了」的唯一留存处。
            entry.responseBody?.let { put("respBody", it) }
            // 被上限截断时**必须标出来** —— 否则读的人会以为自己看到了完整的错误。
            if (entry.responseBodyTruncated) put("respBodyTruncated", true)
            // 请求头里就是凭据所在处(Authorization / x-api-key),落盘保留原文
            // (现场不被破坏),**导出时由 XLogScrub 掩掉** —— 与其它内容同一条口径。
            if (entry.requestHeaders.isNotEmpty()) {
                putJsonObject("reqHeaders") { entry.requestHeaders.forEach { (k, v) -> put(k, v) } }
            }
            if (entry.responseHeaders.isNotEmpty()) {
                putJsonObject("respHeaders") { entry.responseHeaders.forEach { (k, v) -> put(k, v) } }
            }
        }.toString()
}
