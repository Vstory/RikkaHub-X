// [X-custom] RikkaHub-X 诊断框架：把崩溃带进诊断记录
package me.rerere.rikkahub.x.diag

import android.content.Context
import me.rerere.rikkahub.utils.CrashHandler

/**
 * 把**上一次运行的崩溃**带进诊断记录。
 *
 * ## 它接的是谁的活(2026-09-13)
 *
 * X 决定整体移除 Firebase Crashlytics(用户定:既然走诊断日志这条路,Firebase 就全部去掉)。
 * 而 Crashlytics 原本负责一件事 —— **崩溃可观测**。移除它之后,崩溃还剩哪些通路:
 *
 * | 通路 | 覆盖 | 缺口 |
 * |---|---|---|
 * | 上游 `CrashHandler` | 写 SharedPreferences(`commit()` 同步,崩溃前能写完);`RouteActivity` 检测到就进**安全模式**页,那里可复制栈 | 只给**当场**看;不进诊断包,换个时间就找不回来 |
 * | `XLogcatCapture` | 开关开着时,`AndroidRuntime` 的崩溃栈会进 `logcat.log` | **开关关着就没有** —— 而用户往往是崩溃之后才想起来开开关 |
 *
 * 两处缺口合起来就是:**崩溃之后,现场留不下来。** 本类补的正是这一段。
 *
 * ## 为什么是「读上游写下的」,而不是自己再装一个 handler
 *
 * 上游那个 handler 已经用 `commit()`(同步落盘)尽力保证了「崩溃前写完」——
 * 这是崩溃路径上**唯一可靠**的写入方式,自己再装一个只会两边抢。
 * 而**崩溃那一刻不适合做任何 IO**:进程正在死,文件句柄、磁盘、锁都不可信。
 * 故本类的做法是:**下次启动时把上游存下的那份读出来,落进诊断记录。**
 *
 * ## 与安全模式页的关系:只读,不清
 *
 * 上游的 `Stacktrace` 要给安全模式页展示(`CrashHandler.clearCrashed` 由**它**调用)。
 * 本类**只读不写**,故不会把那份抢走 —— 两条路各看各的,互不影响。
 *
 * ## 为什么用「关键失败留存」而不是普通记录
 *
 * 崩溃是典型的「会让现场静默消失」的事,而 [XDiagnostics.recordStickyFailure] 正是为
 * 这一类准备的:**与开关无关**(开关关着也记)、且一直留到用户清空。
 * 若走普通记录,关着开关就什么都留不下 —— 那等于没接住 Crashlytics 的活。
 */
object XCrashReport {

    /** 事件名(`域.动作.结果`,由 `check_x_event_names.py` 机检)。 */
    const val EVENT = "diag.crash.detected"

    /**
     * 检查上次是否崩溃过,并落进诊断记录。**在 `Application.onCreate` 里调用**(幂等)。
     *
     * `CrashHandler` 的状态在**本进程之外**持久化,故这里每进程只处理一次即可
     * —— 用一个进程内标志守住,重复调用不会重复记。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val crashed = runCatching { CrashHandler.hasCrashed(app) }.getOrDefault(false)
        if (!crashed) return
        val stack = runCatching { CrashHandler.getStackTrace(app) }.getOrNull()
        record(stack)
    }

    private fun record(stack: String?) {
        val detail = stack?.takeIf { it.isNotBlank() } ?: "(上游未留下栈文本)"
        // 双落点,理由与其它关键失败一致:
        //  · sticky —— 诊断页上**一眼看得到**,且与开关无关;
        //  · 域文件 —— 打包给 AI 时能带上完整栈。
        // 两者不是重复:前者是「有没有」,后者是「细节」。
        XDiagnostics.recordStickyFailure(
            domain = XDomain.CORE,
            event = EVENT,
            message = "上次运行发生了崩溃(栈见 CORE 域记录)",
        )
        XDiagnostics.record(
            domain = XDomain.CORE,
            level = XLogRing.Level.WARN,
            event = EVENT,
            message = detail,
        )
    }

    /** [install] 的幂等标志(见其注释:上游状态在进程外,每进程处理一次)。 */
    @Volatile
    private var installed = false
}
