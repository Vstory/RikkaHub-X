// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文窗口弹窗
// .x 独立新文件(me.rerere.rikkahub.x.ui),上游无此文件,merge 零冲突。
//
// 版式对齐 OpenAI Codex 的 /status 卡片(codex-rs/tui/src/status/card.rs):
//   主行   「89% left (123K used / 272K)」 → 剩余百分比为主指标 + 已用/窗口
//   累计行 「1.5M total (1.2M input + 300K output)」
//   进度条  固定 20 段、按「剩余」比例填充(render_limit_progress_bar)
// 数字与条形几何由 ContextUsageFormat / ContextUsageCalculator 纯函数产出,与圆环同源。
package me.rerere.rikkahub.x.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.x.context.ContextUsageCalculator
import me.rerere.rikkahub.x.context.ContextUsageFormat

/**
 * 上下文窗口弹窗(Codex /status 卡片版式)。
 *
 * @param breakdown 窗口占用分解(与圆环同源)
 * @param cumulative 会话累计用量
 * @param messageCount 当前会话消息条数
 * @param modelName 当前生效模型标识
 */
@Composable
fun ContextUsageDialogCodex(
    breakdown: ContextUsageCalculator.UsageBreakdown,
    cumulative: ContextUsageCalculator.CumulativeUsage,
    messageCount: Int,
    modelName: String?,
    onDismiss: () -> Unit,
    onCompressClick: () -> Unit,
) {
    val percentRemaining = breakdown.remainingPercent
    val accent = usageColor(breakdown.percent)
    val compact = ContextUsageFormat::compactTokens

    // 构成分段:上一轮输入 / 上一轮输出 / 输入框待发送 / 剩余
    val segments = listOf(
        Triple(
            stringResource(R.string.context_usage_segment_prompt),
            breakdown.promptTokens,
            MaterialTheme.colorScheme.primary,
        ),
        Triple(
            stringResource(R.string.context_usage_segment_completion),
            breakdown.completionTokens,
            MaterialTheme.colorScheme.tertiary,
        ),
        Triple(
            stringResource(R.string.context_usage_segment_input),
            breakdown.inputTokens,
            MaterialTheme.colorScheme.secondary,
        ),
        Triple(
            stringResource(R.string.context_usage_segment_free),
            breakdown.remainingTokens,
            MaterialTheme.colorScheme.surfaceVariant,
        ),
    ).filter { it.second > 0L }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.context_usage_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // ── 主指标:剩余百分比(Codex 以「剩余」为准)──
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$percentRemaining%",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.context_usage_remaining_suffix),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                // ── 进度条:20 段,按剩余比例填充 ──
                SegmentedBar(
                    filled = ContextUsageFormat.filledSegments(percentRemaining.toDouble()),
                    total = ContextUsageFormat.BAR_SEGMENTS,
                    filledColor = accent,
                )

                // ── 「123K 已用 / 272K」(Codex 的 used / window)──
                Text(
                    text = stringResource(
                        R.string.context_usage_used_of_compact,
                        compact(breakdown.usedTokens),
                        compact(breakdown.capacityTokens.toLong()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // ── 累计用量(Codex 的 Token usage 行)──
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ContextUsageInfoRow(
                        label = stringResource(R.string.context_usage_cumulative),
                        value = stringResource(
                            R.string.context_usage_cumulative_value,
                            compact(cumulative.totalTokens),
                            compact(cumulative.inputTokens),
                            compact(cumulative.outputTokens),
                        ),
                    )
                    if (!modelName.isNullOrBlank()) {
                        ContextUsageInfoRow(
                            label = stringResource(R.string.context_usage_model),
                            value = modelName,
                        )
                    }
                    ContextUsageInfoRow(
                        label = stringResource(R.string.context_usage_messages),
                        value = messageCount.toString(),
                    )
                }

                HorizontalDivider()

                // ── 窗口构成 ──
                Text(
                    text = stringResource(R.string.context_usage_breakdown),
                    style = MaterialTheme.typography.titleSmall,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    segments.forEach { (label, value, color) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(color),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = compact(value),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.context_usage_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCompressClick) {
                Text(stringResource(R.string.context_usage_compress))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.context_usage_close))
            }
        },
    )
}

/**
 * 分段进度条:等宽格子,前 [filled] 格实心。
 *
 * 几何沿用 Codex 的 20 段制;此处画成格子而非拼接 █/░ 字符,
 * 避免依赖设备字体字形,也让颜色能随占用档位变化。
 */
@Composable
private fun SegmentedBar(
    filled: Int,
    total: Int,
    filledColor: Color,
    modifier: Modifier = Modifier,
) {
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < filled) filledColor else emptyColor),
            )
        }
    }
}

@Composable
private fun ContextUsageInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
