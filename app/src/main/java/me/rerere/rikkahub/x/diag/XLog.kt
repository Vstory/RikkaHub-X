// [X-custom] RikkaHub-X 诊断框架：埋点统一入口(Android 侧)
package me.rerere.rikkahub.x.diag

import android.util.Log

/**
 * X 定制所有埋点的**唯一入口**。
 *
 * ## 为什么必须只走这里
 *
 * 诊断开关的行为（关掉就静默、异常仍上报）靠本类实现。若某处直接写
 * `Log.i(...)`，那条日志就**绕过了开关** —— 用户关掉诊断后仍会在 logcat 里看到它，
 * 而「关掉诊断 = 回到与上游一致的可观测行为」这个承诺就破了。
 * 故有一道机检：`x` 包（含子包）下**不得出现直接调用 `Log.i` / `Log.d` / `Log.v`**
 * （见 `scripts/check_x_log_gate.py`）。
 *
 * > ⚠️ 本行注释刻意不写成「x 包的通配符形式」—— 那样会在 KDoc 里出现
 * > 「斜杠紧跟星号」的字符序列，而 **Kotlin 会把「斜杠 + 单个星号」当成嵌套块注释的开始**，
 * > 报错位置却指向**文件末尾**。本项目已两次踩这个坑（第二次就发生在这里，
 * > 写这条说明时又写了那两个字符），提交前的表层语法自检脚本正是为此而写。
 *
 * ## 为什么消息用 lambda 传
 *
 * 字符串拼接发生在调用点。若把消息当普通参数传，
 * **即使开关关着也已经拼好了** —— 白付拼接与分配开销。
 * 内联 + lambda 后，关闭时**连 lambda 都不会执行**：
 *
 * ```kotlin
 * XLog.info(XDomain.STORAGE, XStorageEvents.WRITE_NEW) {
 *     "hash=${asset.id} size=${bytes}"     // 开关关着时,这段根本不执行
 * }
 * ```
 *
 * 仅为「零开销」还不够 —— 它同时避免了**在关闭状态下把敏感内容拼进临时字符串**
 * （拼接结果即使不写日志，也会短暂存在于内存里）。
 *
 * ## 与 logcat 的关系
 *
 * [info] 在开启时同时写 logcat（tag = [XLogRing.TAG]），便于 `adb logcat -s XStorage:*`
 * 实时观察。上游的 `me.rerere.common.android.Logging` **只进内存、不输出 logcat**，
 * 只靠它会导致 logcat 里什么都搜不到。
 */
object XLog {

    /** logcat tag。与 [XLogRing.TAG] 一致。 */
    const val TAG = XLogRing.TAG

    /**
     * 记一条正常流程的事件。
     *
     * **诊断关闭时彻底静默**：不写 logcat、不进缓冲、不执行 [message]。
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun info(domain: XDomain, event: String, message: () -> String) {
        if (!XDiagnostics.isEnabled()) return
        val text = message()
        Log.i(TAG, "${domain.key} $event $text")
        XDiagnostics.record(domain, XLogRing.Level.INFO, event, text)
    }

    /**
     * 记一条需要注意的事件。
     *
     * **与 [info] 不同：无论诊断开关如何，都会写 logcat。**
     * 理由是与上游保持一致 —— 上游自己的失败路径就有日志
     * （`Logging.log` 全项目 5 处调用全在 catch/onFailure 里）。
     * 把失败日志也静默掉，反而会让真出问题时无从查起。
     *
     * 但**只有诊断开启时才进缓冲**：缓冲是给用户主动导出的，
     * 用户没开诊断就不该往他的导出包里塞东西。
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun warn(domain: XDomain, event: String, error: Throwable? = null, message: () -> String) {
        val text = message()
        if (error == null) {
            Log.w(TAG, "${domain.key} $event $text")
        } else {
            // logcat 侧保留完整异常,便于需要时深挖
            Log.w(TAG, "${domain.key} $event $text", error)
        }
        if (XDiagnostics.isEnabled()) {
            XDiagnostics.record(domain, XLogRing.Level.WARN, event, text, error)
        }
    }
}
