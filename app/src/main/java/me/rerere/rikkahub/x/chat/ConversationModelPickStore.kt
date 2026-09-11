// [X-custom] RikkaHub-X：会话级模型选择的轻量持久化（会话未落库时的兜底）
package me.rerere.rikkahub.x.chat

import android.content.Context
import kotlin.uuid.Uuid

/**
 * 会话级模型选择的持久化兜底。
 *
 * ## 为什么需要它
 *
 * X 把「会话界面选模型」的作用域定为**会话**（`Conversation.modelId`），
 * 而上游是**助手**（`Assistant.chatModelId`，存在 Settings 里）。
 * 助手是持久化的，会话却不是 —— `ChatService.saveConversation` 有一条规则：
 * **新会话且为空时不保存**，于是首条消息发出之前，会话在库里**根本没有行**。
 *
 * 结果：那次选择只活在内存里。会话空闲 5 秒被回收（`ConversationSession.IDLE_TIMEOUT_MS`）
 * 或进程退出，选择就没了 —— 表现为「选好模型 → 离开 → 回来 → 发送 → 说我没选模型」。
 *
 * ## 与数据库的分工
 *
 * - 会话**已落库** → 数据库是权威，本存储不参与；
 * - 会话**未落库** → 用本存储恢复上次选择。
 *
 * 故写入时机是「会话还不存在时」，读取时机是「初始化后发现会话仍不存在」。
 *
 * ## 为什么放在 SharedPreferences 而不是新建表
 *
 * 与 [ChatDraftStore] 同源（同一个 `rikkahub.preferences` 文件、同样按会话 id 分键）——
 * 二者语义相同：**都是「这个会话尚未提交的用户意图」**。为一两个短字符串新建数据库表
 * 不划算，何况本存储要解决的正是「会话表里还没有这行」的情形。
 */
class ConversationModelPickStore(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
    }

    /** 读该会话上次选择的模型；没有或已损坏则返回 null。 */
    fun load(conversationId: Uuid): Uuid? =
        prefs.getString(key(conversationId), null)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

    /** 记下该会话选择的模型；传 null 等于删除。 */
    fun save(conversationId: Uuid, modelId: Uuid?) {
        if (modelId == null) {
            delete(conversationId)
        } else {
            prefs.edit().putString(key(conversationId), modelId.toString()).apply()
        }
    }

    /** 丢弃该会话的兜底数据（会话已落库后即可清理）。 */
    fun delete(conversationId: Uuid) {
        prefs.edit().remove(key(conversationId)).apply()
    }

    private fun key(conversationId: Uuid) = "chat_model_pick_v1_$conversationId"
}
