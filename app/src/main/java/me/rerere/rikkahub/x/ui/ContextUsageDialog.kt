// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文弹窗分发器
// .x 独立新文件(me.rerere.rikkahub.x.ui),上游无此文件,merge 零冲突。
//
// 两套外观各自独立成文件,此处只按用户选择分发 —— 调用方无需知道有几种样式:
//   · [ContextUsageDialogClassic] — 已用占比样式(默认)
//   · [ContextUsageDialogCodex]   — Codex /status 卡片样式
// 两者共用同一份数值(UsageBreakdown),切换样式不会出现数字口径差异。
package me.rerere.rikkahub.x.ui

import androidx.compose.runtime.Composable
import me.rerere.rikkahub.x.context.ContextUsageCalculator
import me.rerere.rikkahub.x.context.ContextUsageDialogStyle

/**
 * 按用户选择的样式展示上下文弹窗。
 *
 * @param style 用户在「X 定制」中选择的样式
 * @param cumulative 会话累计用量(仅 Codex 样式展示)
 */
@Composable
fun ContextUsageDialog(
    style: ContextUsageDialogStyle,
    breakdown: ContextUsageCalculator.UsageBreakdown,
    cumulative: ContextUsageCalculator.CumulativeUsage,
    messageCount: Int,
    modelName: String?,
    onDismiss: () -> Unit,
    onCompressClick: () -> Unit,
) {
    when (style) {
        ContextUsageDialogStyle.CLASSIC -> ContextUsageDialogClassic(
            breakdown = breakdown,
            messageCount = messageCount,
            modelName = modelName,
            onDismiss = onDismiss,
            onCompressClick = onCompressClick,
        )

        ContextUsageDialogStyle.CODEX -> ContextUsageDialogCodex(
            breakdown = breakdown,
            cumulative = cumulative,
            messageCount = messageCount,
            modelName = modelName,
            onDismiss = onDismiss,
            onCompressClick = onCompressClick,
        )
    }
}
