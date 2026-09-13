// [X-custom] RikkaHub-X 诊断框架:系统时间跳变的检测(纯逻辑)
package me.rerere.rikkahub.x.diag

import java.util.Locale
import kotlin.math.abs

/**
 * 检测**墙上时钟被改动** —— 时间线靠它才不会「悄悄乱序」。
 *
 * ## 为什么需要它
 *
 * 事件行上的 `at` 是**墙上时钟**(`System.currentTimeMillis`),而那是可以被改的:
 * 用户手动改时间、系统按 NTP 步进、时区切换。一旦它往回跳,时间线里后来的记录会
 * 出现在更早的位置 —— 而**读的人是按顺序读的**,于是会得出错误的因果。
 * (仓库里那条既有判断正是如此:「**乱序比缺时间更坏**」。)
 *
 * 单调时钟(`elapsedRealtime`)不会跳。故同时读两个:两者的**增量差**就是墙上时钟
 * 相对真实时间的漂移。超过阈值就说明被改过。
 *
 * ## 为什么阈值是 2 秒
 *
 * 低于它的那些是**正常**的:调度抖动、NTP 的微调(每秒几十毫秒)、以及
 * `record` 本身两条之间隔了几毫秒。把那些也报出来会让这个事件变成噪声 ——
 * 而噪声的下场是没人再看它。
 *
 * ## 为什么要有冷却期
 *
 * 若系统在做**逐步**校时(一次跳几百毫秒、连着跳几十次),严格的阈值会连着报很多条,
 * 而它们说的是同一件事。故报告之后**冷却一段时间**(按单调时钟计,不受墙上时钟影响)。
 * 冷却期内**仍然更新基准**(否则冷却一过会把整段漂移一次性报成一次大跳)。
 */
object XClockWatch {

    /** 判为「跳变」的阈值(毫秒)。见类注释:低于它的都是正常抖动。 */
    const val JUMP_THRESHOLD_MS = 2_000L

    /** 两次报告之间的冷却(按单调时钟计)。 */
    const val COOLDOWN_MS = 60_000L

    /** 跳变事件名(三段点分隔)。 */
    const val JUMP_EVENT = "diag.clock.jump"

    /**
     * 一次观察的结果。
     *
     * @param driftMs 墙上时钟相对单调时钟的增量差。**正 = 时间往前跳了**,负 = 往回跳。
     *   往回跳最危险(时间线乱序),故调用方在措辞上要能区分。
     */
    data class Jump(val driftMs: Long, val reported: Int)

    /**
     * 观察器。**有状态**,但状态只有「上一次的两个读数」与「上次报告的时刻」——
     * 不碰文件、不碰 Android,故能在 JVM 里逐条验(见 `XClockWatchTest`)。
     */
    class Watch(
        private val thresholdMs: Long = JUMP_THRESHOLD_MS,
        private val cooldownMs: Long = COOLDOWN_MS,
    ) {
        private var lastWall: Long? = null
        private var lastMono: Long? = null
        private var lastReportedMono: Long? = null

        /** 累计发现的跳变次数(**含冷却期内没报告的**)。 */
        var jumps: Int = 0
            private set

        /**
         * 记一次读数。返回**本次是否该报**(跳变 + 过了冷却),是则给出漂移量。
         *
         * @param wall 墙上时钟(`System.currentTimeMillis`)
         * @param mono 单调时钟(`elapsedRealtime`)—— **调用方传入**,故这个类不碰 Android
         */
        fun observe(wall: Long, mono: Long): Jump? {
            val prevWall = lastWall
            val prevMono = lastMono
            lastWall = wall
            lastMono = mono
            if (prevWall == null || prevMono == null) return null

            val drift = (wall - prevWall) - (mono - prevMono)
            if (abs(drift) < thresholdMs) return null

            jumps++
            val last = lastReportedMono
            if (last != null && mono - last < cooldownMs) return null
            lastReportedMono = mono
            return Jump(drift, jumps)
        }

        /** 仅供测试/诊断:当前是否处于冷却中。 */
        fun inCooldown(mono: Long): Boolean =
            lastReportedMono?.let { mono - it < cooldownMs } ?: false
    }

    /** 把漂移量说成一句人话。**正负要能区分** —— 往回跳才会让时间线乱序。 */
    fun describe(jump: Jump): String {
        val seconds = jump.driftMs / 1000.0
        val direction = if (jump.driftMs > 0) {
            "向前跳(时间被改到了未来)"
        } else {
            "向后跳(时间被改到了过去,本次记录的时间线可能乱序)"
        }
        // 显式 Locale.US:用默认 locale 的话,某些语言下小数点会变成逗号
        // ("-60,0 秒")—— 与 XLogcatCapture.sizeText 同一处取舍。
        val drift = String.format(Locale.US, "%.1f", seconds)
        // ⚠️ 把「第 N 次」先算成 val,不写成 `… + if (c) A else ""`:**那正是**
        //    本仓库记录过的陷阱 —— `"…" + if (c) A else B + "…"` 会被解析成
        //    `if (c) A else (B + "…")`,于是真分支丢掉尾巴。这里 if 在末尾、
        //    本次恰好安全,但按既有纪律一律拆开,免得后来人往里加一个后缀。
        val repeat = if (jump.reported > 1) "(第 ${jump.reported} 次)" else ""
        return "系统时间" + direction + ":漂移 " + drift + " 秒" + repeat
    }
}
