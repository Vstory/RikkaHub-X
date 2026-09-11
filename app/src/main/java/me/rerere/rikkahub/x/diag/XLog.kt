// [X-custom] RikkaHub-X 诊断框架：埋点统一入口(Android 侧)
package me.rerere.rikkahub.x.diag

import android.util.Log

/**
 * X 定制所有埋点的**唯一入口**。
 *
 * ## 行为矩阵（用户 2026-09-11 明确要求，改这里前先读）
 *
 * | 诊断开关 | 正常流程（[info]） | 异常/失败（[warn]） |
 * |---|---|---|
 * | **关** | **完全不输出**（logcat 不写、缓冲不进、lambda 不执行） | **logcat 输出**，不进缓冲 |
 * | **开** | logcat 输出 **+** 进缓冲 | logcat 输出 **+** 进缓冲 |
 *
 * 三处刻意的取舍：
 *
 * ① **关闭时正常流程连 logcat 都不写** —— 这样「关掉诊断」= 对 logcat 零贡献，
 * 用户想读上游的日志时不会被 X 的噪声干扰。
 *
 * ② **关闭时失败仍写 logcat** —— 与上游一致。上游的失败路径本来就往 logcat 写
 * （全项目 `android.util.Log` 共 191 处，含 63 处 `Log.e` 与 32 处 `Log.w`，
 * 其中不少是正常流程；另有 `me.rerere.common.android.Logging.log` 5 处只进内存）。
 * 把失败也一并静默，会让真出问题时**无从查起**。
 *
 * ③ **开启时失败与正常都进缓冲** —— 用户要的是「开启后记录 X 定制的全部信息」，
 * 单个导出包里应当**既有流水也有错误**，否则还得再去 logcat 里找另一半。
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
 * 这还顺带避免一件事：**在关闭状态下把敏感内容拼进临时字符串**
 * （拼了即使不写日志，也会短暂存在于内存里）。
 *
 * ## 不得绕过本入口
 *
 * 若某处直接写 `Log.i(...)`，那条日志就绕过了开关 —— 用户关掉诊断后仍会在 logcat 里
 * 看到它，「关闭 = 零贡献」的承诺就破了。故 `x` 包（含子包）内**禁止直接调用
 * `android.util.Log`**，由 `scripts/check_x_log_gate.py` 机检拦住；
 * 被 X 改动的上游文件里，凡带 `[X-custom]` 标记的日志行同样受约束。
 */
object XLog {

    /** logcat tag。与 [XLogRing.TAG] 一致（整个 X 定制共用一个，便于一条命令看全）。 */
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
     * **无论开关如何都写 logcat**（与上游一致，理由见类注释 ②）；
     * **只有开启时才进缓冲** —— 缓冲是给用户主动导出的，
     * 用户没开诊断就不该往他的导出包里塞东西。
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun warn(domain: XDomain, event: String, error: Throwable? = null, message: () -> String) {
        val text = message()
        if (error == null) {
            Log.w(TAG, "${domain.key} $event $text")
        } else {
            // logcat 侧保留完整异常,便于需要时深挖;缓冲侧只留类型与消息
            Log.w(TAG, "${domain.key} $event $text", error)
        }
        if (XDiagnostics.isEnabled()) {
            XDiagnostics.record(domain, XLogRing.Level.WARN, event, text, error)
        }
    }
}
