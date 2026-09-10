// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文已用量计算
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
// 分子口径:优先取「最近一次助手回复携带的 usage.promptTokens」= 上一轮真实送入模型的
// 完整历史 token(API 实测);其上再叠当前输入框新增文本的粗估增量(约 4 字符 ≈ 1 token)。
// 无任何 usage 时返回 null(由调用方决定隐藏或估算)。
package me.rerere.rikkahub.x.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

object ContextUsageCalculator {
    private const val CHARS_PER_TOKEN = 4

    /** 最近一次含 usage 的助手回复所报告的输入上下文 token(整段历史)。 */
    fun lastReportedPromptTokens(messages: List<UIMessage>): Long? =
        messages.asReversed()
            .firstOrNull { it.role == MessageRole.ASSISTANT && (it.usage?.promptTokens ?: 0) > 0 }
            ?.usage?.promptTokens?.toLong()

    /** 输入框未发送文本的粗估 token 增量。 */
    fun estimateExtraTokens(inputText: String): Int = inputText.length / CHARS_PER_TOKEN

    /**
     * 计算当前上下文占用 token。
     *
     * 只认「有据可依」的占用值:必须存在最近一次助手回复上报的 usage.promptTokens,
     * 在其上叠加输入框未发送文本的粗估增量。
     *
     * **无任何 usage 时一律返回 null**(即便已有历史消息、输入框也有文字):
     * 那种情况下唯一能算出的只有输入框增量,不含整段历史,返回它会让长会话显示
     * 接近 0% 的占用(圆环门控只判 null 与否),属于误导性数值。宁可不显示。
     *
     * @return 有据可依的占用值;从未有过 usage 时返回 null
     */
    fun currentUsageTokens(messages: List<UIMessage>, inputText: String): Long? {
        val reported = lastReportedPromptTokens(messages) ?: return null
        return reported + estimateExtraTokens(inputText)
    }
}
