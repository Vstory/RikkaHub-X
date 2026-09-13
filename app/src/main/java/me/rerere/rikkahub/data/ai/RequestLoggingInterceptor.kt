package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {

    companion object {
        /**
         * [X-custom] 非 2xx 响应正文的**取用上限**。
         *
         * 256 KB:错误正文通常是几十字节到几 KB(deepseek 的 401 是 65 字节),
         * 留这么大是为了不误伤「服务端返回一大段校验错误」那种情况。
         * 超过则截断,并由 `responseBodyTruncated` 标出来 —— 不静默。
         */
        private const val ERROR_BODY_LIMIT = 256L * 1024
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
        val requestBody = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error,
                responseBody = if (response.isSuccessful) null else peekErrorBody(response),
                responseBodyTruncated = isErrorBodyTruncated(response),
            )
        )

        return response
    }

    /**
     * [X-custom] 取**非 2xx** 响应的一小段正文（2026-09-13 加）。
     *
     * ## 为什么只有非 2xx
     *
     * | 理由 | 说明 |
     * |---|---|
     * | 2xx 的 chat 是 SSE 流 | `peekBody` 要**缓冲**才能拿到字节 —— 对流式响应等于把对话推迟/打断 |
     * | 错误响应不是流 | 实测 deepseek 的 401 是 65 字节的完整 JSON，取它不碰任何流 |
     * | 诊断上要的正是它 | 状态码只说「被拒了」，**原因写在正文里** |
     *
     * ## 为什么用 `peekBody` 而不是读 body
     *
     * `peekBody` 把读到的字节**另存一份**，原响应流仍可被完整消费 —— 调用方拿到的
     * response 一字不少。换成 `body.string()` / `readUtf8()` 会把响应体读干，
     * 调用方拿到空 body，等于**上游功能被诊断改坏**。
     *
     * ⚠️ 取不到就返回 `null`（不抛）：诊断的失败绝不能影响正常请求。这时那一行只是
     * 少一个字段，其它字段照旧。
     */
    private fun peekErrorBody(response: Response): String? =
        runCatching { response.peekBody(ERROR_BODY_LIMIT).string() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * [X-custom] 上面那段正文是否**被上限截断**。
     *
     * 判据是服务端声明的长度，不是「读到的长度等于上限」—— 后者在正文恰好等于上限时
     * 会误报。声明长度拿不到（chunked 等）时返回 `false`：宁可漏报，不可凭空说「被截了」。
     */
    private fun isErrorBodyTruncated(response: Response): Boolean {
        if (response.isSuccessful) return false
        val declared = runCatching { response.body?.contentLength() ?: -1L }.getOrDefault(-1L)
        return declared > ERROR_BODY_LIMIT
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { name ->
            if (name.equals("Proxy-Authorization", ignoreCase = true)) {
                "██"
            } else {
                get(name) ?: ""
            }
        }
    }
}
