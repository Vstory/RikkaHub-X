// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文已用量计算
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
// 分子口径:优先取「最近一次助手回复携带的 usage」—— promptTokens 为上一轮真实送入
// 模型的完整历史 token,completionTokens 为该轮回复自身(回完话后同样留在上下文里);
// 其上再叠当前输入框新增文本的粗估增量(约 4 字符 ≈ 1 token)。
// 无任何 usage 时返回 null(由调用方决定隐藏或估算)。
package me.rerere.rikkahub.x.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.math.roundToInt

object ContextUsageCalculator {
    private const val CHARS_PER_TOKEN = 4

    /** 最近一次助手回复上报的 token 用量。 */
    data class ReportedUsage(
        /** 该次请求的输入上下文 token(整段历史)。 */
        val promptTokens: Long,
        /** 该次回复的输出 token —— 回复发出后同样进入上下文。 */
        val completionTokens: Long,
    )

    /**
     * 上下文占用的完整分解,供用量明细展示。
     *
     * `usedTokens` 与 [currentUsageTokens] 同口径,两者共用同一下层计算,不会漂移。
     */
    data class UsageBreakdown(
        /** 上一轮请求的输入上下文(系统提示词 + 工具定义 + 历史消息)。 */
        val promptTokens: Long,
        /** 上一轮回复自身的输出。 */
        val completionTokens: Long,
        /** 输入框待发送文本的估算增量。 */
        val inputTokens: Long,
        /** 当前占用合计。 */
        val usedTokens: Long,
        /** 剩余可用(`usedTokens` 超出容量时为 0,不出现负数)。 */
        val remainingTokens: Long,
        /** 模型上下文窗口。 */
        val capacityTokens: Int,
    ) {
        /** 占用比例,恒在 0..1。 */
        val percent: Float
            get() = if (capacityTokens <= 0) {
                0f
            } else {
                (usedTokens.toFloat() / capacityTokens).coerceIn(0f, 1f)
            }

        /** 占用百分比(四舍五入整数)。 */
        val percentInt: Int get() = (percent * 100).roundToInt()

        /** 剩余百分比;已超容量时为 0。 */
        val remainingPercent: Int get() = (100 - percentInt).coerceAtLeast(0)
    }

    /** 最近一次含 usage 的助手回复所报告的 token(输入 + 输出)。 */
    fun lastReportedUsage(messages: List<UIMessage>): ReportedUsage? =
        messages.asReversed()
            .firstOrNull { it.role == MessageRole.ASSISTANT && (it.usage?.promptTokens ?: 0) > 0 }
            ?.usage
            ?.let { ReportedUsage(it.promptTokens.toLong(), it.completionTokens.toLong()) }

    /** 输入框未发送文本的粗估 token 增量。 */
    fun estimateExtraTokens(inputText: String): Int = inputText.length / CHARS_PER_TOKEN

    /**
     * 计算当前上下文占用 token。
     *
     * 只认「有据可依」的占用值:必须存在最近一次助手回复上报的 usage,在其
     * `promptTokens + completionTokens` 之上叠加输入框未发送文本的粗估增量。
     *
     * 计入 `completionTokens`:promptTokens 只覆盖「上一轮请求的输入」,而上一轮的
     * 回复发出后同样留在上下文里。只算 promptTokens 会持续低估当前占用,
     * 且会话越长、回复越多,低估越明显。
     *
     * **无任何 usage 时一律返回 null**(即便已有历史消息、输入框也有文字):
     * 那种情况下唯一能算出的只有输入框增量,不含整段历史,返回它会让长会话显示
     * 接近 0% 的占用(圆环门控只判 null 与否),属于误导性数值。宁可不显示。
     *
     * @return 有据可依的占用值;从未有过 usage 时返回 null
     */
    fun currentUsageTokens(messages: List<UIMessage>, inputText: String): Long? {
        val reported = lastReportedUsage(messages) ?: return null
        return reported.promptTokens + reported.completionTokens + estimateExtraTokens(inputText)
    }

    /**
     * 计算上下文占用的完整分解(圆环点击后的用量明细用)。
     *
     * 各分量之和恒等于 [UsageBreakdown.usedTokens];`remaining` 在超容量时钳为 0,
     * 保证明细各项不会出现负数。
     *
     * @return 有据可依的分解;从未有过 usage 时返回 null
     */
    fun breakdown(
        messages: List<UIMessage>,
        inputText: String,
        capacityTokens: Int,
    ): UsageBreakdown? {
        val reported = lastReportedUsage(messages) ?: return null
        val inputTokens = estimateExtraTokens(inputText).toLong()
        val usedTokens = reported.promptTokens + reported.completionTokens + inputTokens
        return UsageBreakdown(
            promptTokens = reported.promptTokens,
            completionTokens = reported.completionTokens,
            inputTokens = inputTokens,
            usedTokens = usedTokens,
            remainingTokens = (capacityTokens - usedTokens).coerceAtLeast(0L),
            capacityTokens = capacityTokens,
        )
    }
}
