// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文用量明细弹窗
// .x 独立新文件(me.rerere.rikkahub.x.ui),上游无此文件,merge 零冲突。
//
// 参照 OpenAI Codex 的上下文展示:总量与占比 + 构成分解 + 剩余可用。
// 数据来源分两类,展示时分别标注,不把估算值伪装成实测值:
//   · 上一轮请求输入 / 上一轮回复输出 —— 模型 API 上报的权威值
//   · 输入框待发送 —— 按字符估算
// 分解数值由 ContextUsageCalculator.breakdown 纯函数算出,与圆环同口径。
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.x.context.ContextUsageCalculator

/** 千分位分组(不依赖 Locale,行为稳定)。 */
private fun groupDigits(value: Long): String {
    val s = value.toString()
    if (s.length <= 3) return s
    return s.reversed().chunked(3).joinToString(",").reversed()
}

/**
 * 上下文用量明细弹窗。
 *
 * @param breakdown 占用分解(由 [ContextUsageCalculator.breakdown] 算出)
 * @param messageCount 当前会话消息条数
 * @param modelName 当前生效模型标识
 */
@Composable
fun ContextUsageDialog(
    breakdown: ContextUsageCalculator.UsageBreakdown,
    messageCount: Int,
    modelName: String?,
    onDismiss: () -> Unit,
    onCompressClick: () -> Unit,
) {
    val percent = breakdown.percent
    val percentInt = breakdown.percentInt
    val accent = usageColor(percent)

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
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 总量与占比
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$percentInt%",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.padding(bottom = 3.dp)) {
                        Text(
                            text = stringResource(
                                R.string.context_usage_used_of,
                                groupDigits(breakdown.usedTokens),
                                groupDigits(breakdown.capacityTokens.toLong()),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.context_usage_remaining_value,
                                breakdown.remainingPercent,
                                groupDigits(breakdown.remainingTokens),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 构成条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                ) {
                    segments.forEach { (_, value, color) ->
                        Box(
                            modifier = Modifier
                                .weight(value.toFloat())
                                .fillMaxWidth()
                                .height(10.dp)
                                .background(color),
                        )
                    }
                }

                // 构成明细
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    segments.forEach { (label, value, color) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
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
                                text = groupDigits(value),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 会话信息
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ContextUsageInfoRow(
                        label = stringResource(R.string.context_usage_messages),
                        value = messageCount.toString(),
                    )
                    if (!modelName.isNullOrBlank()) {
                        ContextUsageInfoRow(
                            label = stringResource(R.string.context_usage_model),
                            value = modelName,
                        )
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
