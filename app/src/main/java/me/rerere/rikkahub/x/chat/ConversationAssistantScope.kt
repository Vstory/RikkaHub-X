package me.rerere.rikkahub.x.chat

import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation

/**
 * [X-custom] 会话级助手/模型解析 —— 让 App 的 **UI 侧口径**与**实际发送口径**同源。
 *
 * **问题**:UI 侧(模型选择器 / 上下文圆环 / 标题栏 / 推理档位)原先读全局
 * `settings.getCurrentAssistant()`,而真正生成走会话绑定的助手 ——
 * `ChatService`:`getAssistantById(initialConversation.assistantId) ?: getCurrentAssistant()`。
 * 两者只在「全局当前助手 == 会话绑定助手」时一致,一旦被外部途径写偏全局助手
 * (如 Web UI 切助手 / Web 侧发消息调 `initializeConversation`),即分叉:
 * 界面显示 A 的模型、实际用 B 生成,圆环按 A 的窗口去量 B 的内容。
 *
 * **做法**:UI 侧改为按 `conversation.assistantId` 解析,回退链与生成侧逐字对齐。
 * 不改动生成侧逻辑,仅收敛 UI 侧的取值来源。
 */

/** 会话绑定的助手;助手已被删除时回退全局当前助手(与生成侧一致)。 */
fun Settings.getConversationAssistant(conversation: Conversation): Assistant =
    getAssistantById(conversation.assistantId) ?: getCurrentAssistant()

/**
 * 会话当前生效模型:会话级覆盖 → 助手级 → 全局默认。
 *
 * 回退链与生成侧 `ChatService` 逐字一致 —— 注意含「会话级 modelId 已失效(对应模型不在
 * 任何 provider 中)时**不回退**到助手级」这一语义,避免 UI 显示一个本次不会生效的模型。
 */
fun Settings.getConversationChatModel(conversation: Conversation): Model? {
    val assistant = getConversationAssistant(conversation)
    return findModelById(conversation.modelId ?: assistant.chatModelId ?: chatModelId)
}
