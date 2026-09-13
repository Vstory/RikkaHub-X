// [X-custom] RikkaHub-X 诊断框架:把系统记下的上一次退出(崩溃/ANR/被杀)带进存活层
package me.rerere.rikkahub.x.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit

/**
 * 把**系统记下的**进程退出带进诊断 —— 崩溃、**ANR**、被系统杀掉。
 *
 * ## 它补的是哪一格
 *
 * | 来源 | 覆盖 | 缺口 |
 * |---|---|---|
 * | 上游 `CrashHandler` | Java/Kotlin 未捕获异常 | ANR、原生崩溃、被系统杀 —— 它一个都收不到(ANR 时线程停在别处、原生崩溃不经过它) |
 * | `XLogcatCapture` 的 crash 缓冲 | 崩溃栈 | **只在开关开着时**;ANR 的 trace 另在别处 |
 * | 本类(`ApplicationExitInfo`) | **ANR、原生崩溃、被系统杀、被用户强杀**,外加退出原因与时间 | 只在 API 30+ 有;不含 Java 栈(那是上面那条的活) |
 *
 * 三者**互补**,不是重复 —— 故这条不替代任何一条,只补上「**ANR 完全不可见**」这一格。
 *
 * ## 为什么 ANR 值得单列
 *
 * ANR 是用户侧最常见、也最难复现的一类问题:「应用卡住然后被系统弹窗」。
 * 而它**一个字都不留**:Java 异常处理器收不到(没抛异常),我们自己的捕获要开关开着,
 * Crashlytics 又被移除。于是「刚才那次是怎么回事」变成一个只能猜的问题。
 * 系统其实记着(`ApplicationExitInfo`),这里只是去把它取出来。
 *
 * ## 为什么是「下次启动时读回」,与 [XCrashReport] 同一个道理
 *
 * 退出那一刻进程正在死,不适合做任何 IO。而系统把这件事记在了**本进程之外**,
 * 下次启动读回来即可 —— 于是它天然**与诊断开关无关**(见 [XSurvivorLog])。
 *
 * ## 水位:避免每次启动重复报同一件事
 *
 * 系统保留一段退出历史(取 [MAX_QUERY] 条)。每次启动都报一遍会把存活层刷满,
 * 于是真正要看的那条被埋掉。故记一个**时间戳水位**:只报比它新的,
 * 再把水位推到「本次查询里最新的那一条」。
 *
 * ⚠️ 水位推的是**查询结果里最大的时间戳**,不是「现在」。用「现在」会**静默吞掉**
 * 「上次查询之后、本次启动之前」发生的那次退出 —— 它在时间上晚于水位、早于现在,
 * 而那种恰恰是最该报的一次。
 */
object XExitReport {

    /** ANR(应用无响应)的事件名。 */
    const val ANR_EVENT = "diag.anr.detected"

    /** 其它退出(原生崩溃、被系统杀、被强杀)的事件名。 */
    const val EXIT_EVENT = "diag.exit.detected"

    /**
     * 一次启动最多逐条记几条。
     *
     * 系统保留的历史可能十几条,而**首次运行**时水位是 0 —— 全部都会被判为「新的」。
     * 一次全写进存活层会把真正要看的那条埋掉,故限量,并在超出时**明说还剩几条**
     * (不静默丢,与别处同一个口径)。
     */
    private const val MAX_REPORTED_PER_START = 3

    /** 向系统一次问几条历史。 */
    private const val MAX_QUERY = 10

    private const val PREFS = "x_diag_exit"
    private const val KEY_LAST_TS = "last_reported_ts"

    @Volatile
    private var installed = false

    /**
     * 读回退出历史并落进存活层。**在 `Application.onCreate` 里调用**(幂等)。
     *
     * 必须排在 [XSurvivorLog.install] 之后 —— 它要往那个文件里写。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        // ⚠️ API 30 以下拿不到这个接口,直接返回。
        //
        // **为什么不写一条关键失败留存**:那不是「失败」,是这台机器的**能力边界** ——
        // 而存活层是「事后无法补救」那一类专用的。每进程写一条会让这个文件被同一条
        // 能力说明慢慢刷满,真崩溃反而被轮转掉。宁可这里安静:
        // 机器信息(build/API 级别)本来就在包内手册与捕获头里,读者据此可知这一路不存在。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        readAndReport(app)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readAndReport(app: Context) {
        val reasons = runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return
            am.getHistoricalProcessExitReasons(app.packageName, 0, MAX_QUERY)
        }.getOrNull() ?: return
        if (reasons.isEmpty()) return

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val watermark = prefs.getLong(KEY_LAST_TS, 0L)

        val fresh = reasons.filter { it.timestamp > watermark }.sortedBy { it.timestamp }
        if (fresh.isEmpty()) return

        val shown = fresh.takeLast(MAX_REPORTED_PER_START)
        shown.forEach { record(it) }
        if (fresh.size > shown.size) {
            // 超出部分**明说条数**,而不是假装没有 —— 否则读者会以为这就是全部退出。
            XDiagnostics.recordStickyFailure(
                domain = XDomain.CORE,
                event = EXIT_EVENT,
                message = "另有 ${fresh.size - shown.size} 次更早的进程退出未逐条记录" +
                    "(每次启动最多记 $MAX_REPORTED_PER_START 条,免得把最近那一次埋掉)",
            )
        }

        // ⚠️ 水位推「查询结果里最大的时间戳」,不是 `System.currentTimeMillis()`
        // —— 见类注释里那段:用「现在」会静默吞掉两次启动之间发生的那一次。
        val highest = reasons.maxOf { it.timestamp }
        // ⚠️ `commit = true`(同步落盘),不是 `apply()`:这个水位若丢了,
        //    下次启动会把同一批退出再报一遍 —— 而存活层被重复条目刷满的代价,
        //    是**真正要看的那一条被轮转掉**。放在这里的一次同步写可以忽略不计。
        prefs.edit(commit = true) { putLong(KEY_LAST_TS, highest) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun record(info: ApplicationExitInfo) {
        val isAnr = info.reason == ApplicationExitInfo.REASON_ANR
        XDiagnostics.recordStickyFailure(
            domain = XDomain.CORE,
            event = if (isAnr) ANR_EVENT else EXIT_EVENT,
            message = "上次退出:${reasonText(info.reason)}" +
                "(发生在 ${XDiagEnv.stamp(info.timestamp)})",
            // 系统给的描述常常就是 ANR 的「输入分发超时」之类 —— 那是判断原因的关键,
            // 故整段带走,不截断(总量由 XSurvivorLog 的体积阀兜底)。
            detail = describe(info),
        )
    }

    /** 退出的细节。**只收系统给的**,不自己猜。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(info: ApplicationExitInfo): String = buildString {
        appendLine("reason      : ${reasonText(info.reason)} (${info.reason})")
        appendLine("at          : ${XDiagEnv.stamp(info.timestamp)}")
        appendLine("importance  : ${importanceText(info.importance)} (${info.importance})")
        appendLine("pss/rss     : ${info.pss} KB / ${info.rss} KB")
        appendLine("exit status : ${info.status}")
        // description 在 ANR 时经常是系统给的摘要(如 "Input dispatching timed out"),
        // 那是**唯一**一句话说明「为什么卡住」的线索 —— 有就带上,没有就不写空行。
        info.description?.takeIf { it.isNotBlank() }?.let { appendLine("description : $it") }
    }.trimEnd()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonText(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "应用无响应(ANR)"
        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统回收"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被杀"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动结束(划掉/强制停止)"
        ApplicationExitInfo.REASON_SIGNALED -> "被信号杀死"
        ApplicationExitInfo.REASON_EXIT_SELF -> "自行退出"
        ApplicationExitInfo.REASON_OTHER -> "其它原因"
        else -> "未知原因"
    }

    /** 退出时进程的重要性。**只译常见的几个**,其余照样给数字。 */
    private fun importanceText(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "前台"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "前台服务"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "可见"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "后台服务"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "缓存(后台)"
        else -> "其它"
    }
}
