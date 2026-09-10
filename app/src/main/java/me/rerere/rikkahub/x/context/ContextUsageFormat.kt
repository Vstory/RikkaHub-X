// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文用量的展示格式
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
//
// 逐条对齐 OpenAI Codex 的算法,保证同一组数字在两端呈现一致:
//   · 紧凑数字 — codex-rs/tui/src/status/helpers.rs::format_tokens_compact
//                1234567 → "1.23M"、272000 → "272K"、1500 → "1.5K"
//   · 进度条几何 — codex-rs/tui/src/status/rate_limits.rs::render_limit_progress_bar
//                固定 20 段,按「剩余比例」四舍五入决定实心段数
// 纯函数,无 Android 依赖,可直接单测。
package me.rerere.rikkahub.x.context

import java.util.Locale
import kotlin.math.roundToInt

object ContextUsageFormat {
    /** 进度条总段数(Codex STATUS_LIMIT_BAR_SEGMENTS = 20)。 */
    const val BAR_SEGMENTS: Int = 20

    private const val TRILLION = 1_000_000_000_000L
    private const val BILLION = 1_000_000_000L
    private const val MILLION = 1_000_000L
    private const val THOUSAND = 1_000L

    /**
     * 紧凑 token 数(Codex `format_tokens_compact` 的同构实现)。
     *
     * 规则:负值按 0;小于 1000 原样;否则按量级取 K/M/B/T 后缀,
     * 并按缩放后的大小决定小数位(小于 10 → 2 位,小于 100 → 1 位,否则 0 位),
     * 最后去掉多余的尾零与小数点:1500 → "1.5K"、1000 → "1K"、272000 → "272K"。
     *
     * 注意:定死 [Locale.US] 拼接小数,避免在逗号作小数分隔符的语言环境下
     * 输出 "1,5K"。Codex 的 Rust `format!` 恒用句点,此处须与之一致。
     */
    fun compactTokens(value: Long): String {
        val v = value.coerceAtLeast(0L)
        if (v == 0L) return "0"
        if (v < THOUSAND) return v.toString()

        val scaled: Double
        val suffix: String
        when {
            v >= TRILLION -> {
                scaled = v.toDouble() / TRILLION
                suffix = "T"
            }

            v >= BILLION -> {
                scaled = v.toDouble() / BILLION
                suffix = "B"
            }

            v >= MILLION -> {
                scaled = v.toDouble() / MILLION
                suffix = "M"
            }

            else -> {
                scaled = v.toDouble() / THOUSAND
                suffix = "K"
            }
        }

        val decimals = when {
            scaled < 10.0 -> 2
            scaled < 100.0 -> 1
            else -> 0
        }
        var formatted = String.format(Locale.US, "%.${decimals}f", scaled)
        if (formatted.contains('.')) {
            while (formatted.endsWith("0")) formatted = formatted.dropLast(1)
            if (formatted.endsWith(".")) formatted = formatted.dropLast(1)
        }
        return formatted + suffix
    }

    /**
     * 进度条实心段数(Codex `render_limit_progress_bar` 的几何同构)。
     *
     * **入参是「剩余」百分比,不是已用** —— Codex 源码注释明确警告传成已用会让
     * 条形图反向、误导用户。此处沿用同一语义:剩余越多,实心段越多。
     *
     * UI 侧按 20 个格子渲染,而非拼接 █/░ 字符:既保留 Codex 的分段几何,
     * 又不依赖设备字体是否含 U+2588 / U+2591 字形。
     *
     * @param percentRemaining 剩余百分比(0..100,越界会被钳制)
     * @return 实心段数,范围 0..[segments]
     */
    fun filledSegments(percentRemaining: Double, segments: Int = BAR_SEGMENTS): Int {
        val count = segments.coerceAtLeast(1)
        val ratio = (percentRemaining / 100.0).coerceIn(0.0, 1.0)
        return (ratio * count).roundToInt().coerceIn(0, count)
    }
}
