// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文用量圆环
// .x 独立新文件(me.rerere.rikkahub.x.ui),上游无此文件,merge 零冲突。
// Codex 风格上下文占用圆环:外圈弧 = 占用比例,中心百分比文本;按占用分段变色。
// 纯展示组件,数据由调用方算好传入。
package me.rerere.rikkahub.x.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val LOW_THRESHOLD = 0.6f
private const val HIGH_THRESHOLD = 0.85f

private fun usageColor(percent: Float): Color = when {
    percent >= HIGH_THRESHOLD -> Color(0xFFE5484D)   // 红:接近上限
    percent >= LOW_THRESHOLD -> Color(0xFFFFB224)    // 橙:建议压缩
    else -> Color(0xFF30A46C)                        // 绿:健康
}

/**
 * 上下文用量圆环。
 * @param usedTokens 已用 token;@param capacityTokens 容量 token(>0 才绘制)
 * @param onClick 点击动作(默认打开更多菜单/压缩入口)
 */
@Composable
fun ContextUsageRing(
    usedTokens: Long,
    capacityTokens: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    if (capacityTokens <= 0) return
    val percent = (usedTokens.toFloat() / capacityTokens).coerceIn(0f, 1f)
    val color = usageColor(percent)
    val textColor = when {
        percent >= HIGH_THRESHOLD -> Color(0xFFE5484D)
        percent >= LOW_THRESHOLD -> Color(0xFFFFB224)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val desc = "上下文已用 ${(percent * 100).roundToInt()}%"
    val stroke = 3.dp
    val ringBgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .size(28.dp)
            .semantics { contentDescription = desc }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val arcSize = Size(size.width, size.height)
            // 背景环
            drawArc(
                color = ringBgColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
            )
            // 进度弧(从 12 点方向顺时针)
            if (percent > 0f) {
                rotate(degrees = -90f, pivot = Offset(size.width / 2, size.height / 2)) {
                    drawArc(
                        color = color,
                        startAngle = 0f,
                        sweepAngle = 360f * percent,
                        useCenter = false,
                        style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
                        size = arcSize,
                    )
                }
            }
        }
        Text(
            text = "${(percent * 100).roundToInt()}",
            color = textColor,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
