// [X-custom] RikkaHub-X 诊断框架：环境自述(清单头与导出摘要共用)
package me.rerere.rikkahub.x.diag

import android.content.Context
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.x.app.xAppLabel

/**
 * 诊断产物的**环境自述**。
 *
 * ## 为什么要有它
 *
 * 真机实测(2026-09-12)暴露的头号短板:导出的日志**没有自我描述** —— 读的人(与我)只能猜
 * 「这是什么版本、什么设备、这份是不是完整的」。猜不出来的后果很实际:
 * 一份日志里出现了两个 PID,读者第一反应是「串了别的应用」,而不是「这是本应用重启前的自己」。
 *
 * 故这里把「读日志前必须先知道的东西」集中成几行,由 [XLogcatCapture] 写在**捕获开始时**、
 * 导出时再补一段摘要。放在同一个对象里是为了让两处**同源** —— 分开写迟早会漂移。
 *
 * ## 为什么全用英文
 *
 * 这些字段是**日志的元数据**,不是界面文案:读者主要是日志分析工具与 AI,而它们处理英文键
 * 更稳(避免编码/断词差异);混排中英还会让「冒号对齐」在不同宽度下散开。
 * 界面文案走 `strings.xml`,与这里无关。
 */
object XDiagEnv {

    /** 清单头 / 摘要的行首标记。刻意不用 logcat 的时间戳前缀,一眼可辨「这是自产的,不是日志」。 */
    const val MARK = "===== [x-diag]"

    /**
     * 应用与设备自述。**不含任何用户数据**(没有账号、路径、设置内容),故可安全进导出物。
     *
     * 用 `applicationContext` 取应用名:`xAppLabel()` 要读 PackageManager,
     * 而这一行会在捕获线程里被调用,故调用方须保证传进来的是 application context。
     */
    fun appLines(context: Context): List<String> {
        val app = context.applicationContext
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val model = Build.MODEL ?: "unknown"
        return listOf(
            "app     : ${runCatching { app.xAppLabel() }.getOrDefault("RikkaHub X")} (${app.packageName})",
            // versionName 形如 `2.5.1+260912.598ce6d7` —— 已含构建日期与提交短号,
            // 故不必再单独取提交号(单一真源:build.gradle.kts 那边的 xBuildStamp)。
            "version : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "device  : $manufacturer $model · Android ${Build.VERSION.SDK_INT} · $abi",
            // ⚠️ 这一项是刻意加的:实测日志里出现过两个 PID(本应用重启前后),
            //    若不点明自身 PID,读者会误读成「串了别的应用」。
            "pid     : ${Process.myPid()}",
        )
    }

    /** `yyyy-MM-dd HH:mm:ss.SSS`。带日期 —— 清单头与日志正文不同,它需要绝对时间。 */
    fun stamp(at: Long): String = FORMAT.get()!!.format(Date(at))

    /** `SimpleDateFormat` 非线程安全,故每线程一个(与 `XLogRing` 同一处理)。 */
    private val FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /** 秒 → 人读的时长。清单头与结束标记共用,免得两处写法不一致。 */
    fun durationText(seconds: Double): String = when {
        seconds >= 3600 -> String.format(Locale.US, "%.2f h", seconds / 3600)
        seconds >= 60 -> String.format(Locale.US, "%.1f min", seconds / 60)
        else -> String.format(Locale.US, "%.1f s", seconds)
    }
}
