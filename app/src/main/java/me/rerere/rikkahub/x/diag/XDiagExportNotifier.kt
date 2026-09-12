// [X-custom] RikkaHub-X 诊断框架：导出进度通知
package me.rerere.rikkahub.x.diag

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.NotificationUtil

/**
 * 导出过程的进度通知。
 *
 * ## 为什么需要它
 *
 * 导出是「点一下 → 选位置 → 然后就什么都不动了」。小文件是几毫秒,大文件十几秒 ——
 * 后者期间界面毫无反馈,用户只能盯着一个没有变化的页面猜「是不是卡死了/要不要退出去」。
 * 有了这条通知:进度看得见,**而且可以离开这一页**。
 *
 * ## 为什么不用前台服务
 *
 * 用了更稳(进程更不容易被收走),但对**通常只有几十毫秒**的导出来说代价不划算:
 * 每次导出都会挂出一条**不可划掉的**常驻通知,一闪而过。真正的耗时场景(几十 MB)
 * 也只在用户主动点导出时出现,那时应用通常就在前台。
 *
 * 不变量:**导出跑在应用级作用域上**(见 DiagnosticPage),所以「离开这一页」不会中断它
 * —— 这一条才是「不必卡在导出状态」的关键,与是否前台服务无关。
 * 若以后实测发现大文件导出被系统收走,再升级成前台服务。
 *
 * ## 渠道为什么在这里建,而不是 RikkaHubApp
 *
 * 上游的渠道统一在 `RikkaHubApp.createNotificationChannel()` 里建。这里**另起一处**是
 * 刻意的:`RikkaHubApp.kt` 是上游文件,少改一处就少一份合并冲突;而建渠道是**幂等**的
 * (重复调用不会重置用户改过的通知设置),放在使用它的地方自足。
 *
 * ## 没有通知权限时
 *
 * 静静跳过 —— 不能因为发不出通知就让导出的**结果**没处可报。调用方仍会弹吐司。
 */
object XDiagExportNotifier {

    /**
     * 通知 ID。
     *
     * 挑的是上游已用之外的:1(聊天完成)、20(WebServer)、42(压缩结果)、
     * 2002(生成中前台服务)。用同一个 ID 让「进行中 → 完成」是**替换**而不是新增一条。
     */
    private const val NOTIFICATION_ID = 2007

    private const val CHANNEL_ID = "diag_export"

    /**
     * 更新节流。
     *
     * 进度是每读一块报一次(可能每 8KB 一次),而通知更新要走 Binder 且被系统限流 ——
     * 按毫秒节流对人眼完全够(0.3 秒一次)。
     */
    private const val MIN_UPDATE_INTERVAL_MS = 300L

    /** 上次更新的时刻(单调时钟,不受用户改系统时间影响)。 */
    private var lastUpdateAt = 0L

    /** 导出开始:挂出进行中的通知。 */
    fun begin(context: Context) {
        ensureChannel(context)
        // 复位节流,否则紧跟着的第一次进度上报会被丢掉,通知要等 0.3 秒才有动静。
        lastUpdateAt = 0L
        post(
            context = context,
            content = context.getString(R.string.diagnostic_export_notif_analysing),
            indeterminate = true,
            percent = 0,
            ongoing = true,
        )
    }

    /**
     * 拿到一个可以交给导出逻辑的进度回调。
     *
     * 做成回调而不是让导出逻辑直接碰通知:导出逻辑(含 XDiagZip)因此**不知道有通知这回事**,
     * 于是它能在 JVM 单测里跑完 —— 不必起 Android。
     */
    fun progress(context: Context): XExportProgress =
        XExportProgress { phase, processed, total -> update(context, phase, processed, total) }

    /** 导出结束:换成可划掉的结果通知。 */
    fun finish(context: Context, fileName: String, ok: Boolean) {
        if (!NotificationUtil.hasNotificationPermission(context)) return
        val content = if (ok) {
            context.getString(R.string.diagnostic_export_notif_done, fileName)
        } else {
            context.getString(R.string.diagnostic_export_notif_failed)
        }
        post(context = context, content = content, indeterminate = false, percent = 0, ongoing = false)
    }

    private fun update(context: Context, phase: XExportPhase, processed: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        if (lastUpdateAt != 0L && now - lastUpdateAt < MIN_UPDATE_INTERVAL_MS) return
        lastUpdateAt = now

        val percent = percentOf(processed, total)
        val label = if (phase == XExportPhase.ANALYSING) {
            R.string.diagnostic_export_notif_analysing
        } else {
            R.string.diagnostic_export_notif_writing
        }
        val content = if (percent == null) {
            context.getString(label)
        } else {
            context.getString(R.string.diagnostic_export_notif_percent, context.getString(label), percent)
        }
        post(
            context = context,
            content = content,
            // 算不出百分比时给个转圈,而不是一个卡在 0% 的条 —— 后者看着像死了。
            indeterminate = percent == null,
            percent = percent ?: 0,
            ongoing = true,
        )
    }

    private fun post(
        context: Context,
        content: String,
        indeterminate: Boolean,
        percent: Int,
        ongoing: Boolean,
    ) {
        // 没权限就静静跳过:导出的结果由调用方的吐司兜底,不该因为发不出通知就把事情办砸。
        if (!NotificationUtil.hasNotificationPermission(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID).apply {
            setContentTitle(context.getString(R.string.diagnostic_export_notif_title))
            setContentText(content)
            setSmallIcon(R.drawable.ic_stat_rikkahub)
            setOngoing(ongoing)
            setAutoCancel(!ongoing)
            // 进度通知必须不响不震:它在导出期间会更新几十次,每次都提示就是灾难。
            setOnlyAlertOnce(true)
            setSilent(true)
            setCategory(NotificationCompat.CATEGORY_PROGRESS)
            setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            setContentIntent(openApp(context))
            // ⚠️ 这里只写一次 setProgress。多写一次(哪怕逻辑上"应该一样")会让转圈模式
            //    被后面那次覆盖成 0% 的条 —— 看着像卡死,正是这条通知要避免的事。
            if (indeterminate) {
                setProgress(0, 0, true)
            } else {
                setProgress(100, percent.coerceIn(0, 100), false)
            }
        }.build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** 点通知回到应用。没有目标会话,故只是一个「打开」意图。 */
    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // 重要级取 LOW:它在导出期间会持续更新,不该响、不该弹横幅,但**要看得见**。
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.diagnostic_export_notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.diagnostic_export_notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
