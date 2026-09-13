// [X-custom] RikkaHub-X 诊断开关的持久化（启动期同步可读）
package me.rerere.rikkahub.x.diag

import android.content.Context

/**
 * 诊断开关的**落盘**（一个布尔值）。
 *
 * ## 为什么用 SharedPreferences 而不是 DataStore
 *
 * 这个值必须在 `Application.onCreate` **最开头、同步**读到 —— 因为要靠它决定
 * 「开机时跑的存量回填」这一步要不要记日志（见 [XDiagnostics.attachPersistence]）。
 * DataStore 是异步的，等它加载完，第一波日志已经过去了。
 * SharedPreferences 可以同步读，代价只是要提前读。
 *
 * 复用全局的 `rikkahub.preferences` 文件（与 `ChatDraftStore`、`ui/hooks` 的读写同一份），
 * **不新开一个 prefs 文件** —— 多一个文件就多一处「哪些状态住在哪」的模糊地带。
 */
object DiagnosticSwitchStore {

    /** 与 `ChatDraftStore` / `ui/hooks` 共用的全局文件。 */
    private const val PREFS_NAME = "rikkahub.preferences"

    /** 带版本后缀：将来若语义变了（比如改成等级），旧值不会误读成新含义。 */
    private const val KEY_ENABLED = "x_diag_enabled_v1"

    /**
     * logcat 噪声过滤开关（2026-09-13）—— **默认开**。
     *
     * 与 [KEY_ENABLED] 一样必须**同步**读回：本值决定本轮捕获怎么滤，
     * 而捕获在 `Application.onCreate` 就起了；异步加载会让启动期那一段按默认值滤。
     */
    private const val KEY_NOISE_FILTER = "x_diag_noise_filter_v1"

    /**
     * 读回上次的开关状态，并把持久化接上。
     *
     * **在 `Application.onCreate` 里尽早调用**（越早越好：回填与建库都在那之后启动）。
     * 可重复调用（幂等）。
     */
    fun install(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        XDiagnostics.attachPersistence(
            initial = prefs.getBoolean(KEY_ENABLED, false),
            write = { enabled -> prefs.edit().putBoolean(KEY_ENABLED, enabled).apply() },
        )
        // 默认 true：噪声过滤是「让日志可读」的缺省行为，与诊断开关默认关不同 ——
        // 后者是「不要产生额外日志」的隐私/性能口径，前者只是取舍。
        XLogcatNoise.attachPersistence(
            initial = prefs.getBoolean(KEY_NOISE_FILTER, true),
            write = { on -> prefs.edit().putBoolean(KEY_NOISE_FILTER, on).apply() },
        )
    }
}
