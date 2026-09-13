package me.rerere.rikkahub.x.diag

import androidx.compose.ui.text.AnnotatedString
import com.dokar.sonner.Toast
import com.dokar.sonner.ToastType

/**
 * **给用户看过的错误,必须留痕。**
 *
 * ## 为什么要有它(2026-09-13,一个真实的漏记)
 *
 * 扫码失败时应用弹了一条错误提示(`QRError(... NullPointerException ...)`),而诊断里
 * **一个字都没有** —— 整份导出包(含 `logcat_dump.log` 那份全缓冲快照)搜 `QRError` /
 * `NullPointerException` 都是 0 处。用户看到的和我能看到的是**两回事**。
 *
 * 根因是分工上的一个缺口:
 * · [XCrashReport] 收的是**未捕获**异常 —— 只在进程真的倒下时才有;
 * · logcat 捕获收的是**系统日志** —— 而这类错误在应用内被 `runCatching` 接住,
 *   顺手弹个提示就结束了,**根本不进 logcat**。
 *
 * 于是「被接住、然后展示给用户」这一类**恰恰最容易被漏掉** —— 它们不崩、不写日志、
 * 用户却看见了。而这类错误往往正是「功能坏了但应用还活着」的那种,排查时最需要现场。
 *
 * ## 怎么保证以后不再漏
 *
 * 不在每个调用点上加一行(那样下次新写一个提示就又会漏),而是挂在**唯一的漏斗**上:
 * `ToasterState` 的 `onToastDismissed` 回调(见 `RouteActivity` 里 `rememberToasterState`
 * 的调用)。所有 `toaster.show(..., type = ToastType.Error)` —— 现有 36 处、以及将来
 * 任何一处 —— 都会经过它。
 *
 * ⚠️ 两点如实说明:
 * · 记录发生在**提示消失**那一刻(自动消失或用户关掉),不是弹出的瞬间 —— 对「留下现场」
 *   来说没有差别,但要知道时间戳对应的是前者;
 * · 这条漏斗**只能拿到提示文本**。提示里若没带异常细节,那细节就无从得知 ——
 *   故**抛出点附近**若拿得到异常,应该用 [recordShown] 显式带上(扫码那条就是这么做的)。
 */
object XUserVisibleErrors {

    /** 事件名(`域.动作.结果`)。 */
    const val EVENT = "diag.error.shown"

    /**
     * 记一条**已经展示给用户**的错误。
     *
     * @param message 展示给用户的那句话(原样记,便于与用户描述对上)。
     * @param error 若拿得到异常就传进来 —— 栈会一并落盘;这是排查时最值钱的部分。
     * @param detail 直接给文本(用于只拿得到字符串的情形)。
     */
    fun recordShown(message: String, error: Throwable? = null, detail: String? = null) {
        // 落 CORE 域:与崩溃(`XCrashReport`)同一处 —— 两者都是「应用健康」类的事实,
        // 而不是某个业务域的内部事件。
        XDiagnostics.recordStickyFailure(
            domain = XDomain.CORE,
            event = EVENT,
            message = message,
            error = error,
            detail = detail,
        )
    }

    /**
     * 提示消失时的回调入口(挂在 `rememberToasterState(onToastDismissed = ...)`)。
     *
     * **只记 `Error` 类型** —— 正常/成功提示是流水,不该进「关键失败」那一档
     * (那里是留给「值得事后翻账」的事的)。
     */
    fun recordDismissedToast(toast: Toast) {
        if (toast.type != ToastType.Error) return
        val text = textOf(toast.message)
        if (text.isBlank()) return
        recordShown(message = "给用户看过一条错误提示", detail = text)
    }

    /**
     * 把提示文本取成 `String`。
     *
     * `Toast.message` 是 `Any`(库同时支持 `String` 与 `AnnotatedString`),故三种情形都要认;
     * **`AnnotatedString` 必须走 `.text`** —— 直接 `toString()` 会带上样式标记,读起来是噪声。
     */
    internal fun textOf(message: Any?): String = when (message) {
        null -> ""
        is String -> message
        is AnnotatedString -> message.text
        else -> message.toString()
    }
}
