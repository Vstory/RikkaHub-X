// [X-custom] RikkaHub-X 诊断框架：把上游的请求记录接进事件时间线
package me.rerere.rikkahub.x.diag

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging

/**
 * 把上游 `RequestLoggingInterceptor` 产出的请求记录**落一份到事件时间线**。
 *
 * ## 补的是哪个缺口
 *
 * 上游那套(`RequestLoggingInterceptor` + `Logging`)其实**已经很全**:它读请求体、响应码与
 * 耗时,连请求头一起交出去。问题只在于**它只进内存环形缓冲(100 条,进程一死全没)** ——
 * 于是实测日志里 `Content-Length: 5249` 摆着而**正文一个字没有**:只看得到模型说了什么,
 * 看不到它看到了什么。开关关掉之后也回看不到任何历史请求。
 *
 * 这里不动上游那套,只挂一个旁路回调([Logging.setRequestLogSink]),来一条就落一行。
 * 内存缓冲照旧(应用内「日志」页要展示)。
 *
 * ## 两条与开关相关的约定
 *
 * **① 打开时把上游开关一并打开。** 否则上游那个 `if (!isRequestLoggingEnabled()) return`
 * 根本不会产生记录,旁路也就无事可做。
 *
 * **② 关闭时放回接管前的值**,而不是一律置 `false`(用户 2026-09-12 定的口径)。理由:用户
 * 可能自己在应用内「日志」页开过请求记录,那时我们一关诊断就把他的开关也关掉,属于越界 ——
 * 「关掉诊断 = 恢复原样」应当字面成立。
 *
 * ## 一处已知取舍
 *
 * 挂在那个 client 上的**所有**出网都会记(聊天 / MCP / 搜索 / WebDAV 同步),不只是 AI 对话。
 * 诊断期这是想要的,代价是体积与噪音都更大。
 */
object XRequestLog {

    /** [install] 的幂等标志。 */
    @Volatile
    private var installed = false

    /**
     * 我方接管**之前**上游开关的状态;`null` = 尚未接管。
     *
     * 只在 [install] 那一刻取样一次。若用户之后在「日志」页又手动改了它,关闭诊断时会放回
     * **最初那个值**而不是他最新的选择 —— 这是刻意的:那一页的原话是「诊断关闭时保持原样」,
     * 而「原样」指我们插手之前的样子。要改成跟随最新值就得监听那一页,不值当。
     */
    @Volatile
    private var beforeTakeover: Boolean? = null

    /**
     * 接上。**必须在 [XDiagFileStore.install] 之后调用** —— 落盘要用它接好的写口。
     * 可重复调用(幂等)。
     */
    fun install() {
        if (installed) return
        installed = true

        beforeTakeover = Logging.isRequestLoggingEnabled()
        Logging.setRequestLogSink { entry -> onEntry(entry) }

        XDiagnostics.addEnabledListener { enabled -> apply(enabled) }
        // 开关在启动前就是「开」的(落盘读回来的)→ 立刻接管,否则启动期第一波请求会漏掉。
        if (XDiagnostics.isEnabled()) apply(true)
    }

    private fun apply(enabled: Boolean) {
        Logging.setRequestLoggingEnabled(if (enabled) true else (beforeTakeover ?: false))
    }

    private fun onEntry(entry: LogEntry.RequestLog) {
        // 双保险:上游开关可能被用户在「日志」页手动打开,那时诊断开关若关着就什么都不该写。
        if (!XDiagnostics.isEnabled()) return
        XDiagFileStore.writeLine(XDomain.NET, XNetLine.format(entry))
    }
}
