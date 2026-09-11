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
 * ## 与数据库的分工（**互不重叠**）
 *
 * | 会话状态 | 模型由谁记 |
 * |---|---|
 * | **已落库** | 数据库 `conversationentity.model_id`（每会话一行，**各自独立**） |
 * | **未落库** | 本存储 |
 *
 * 因此本存储**不参与「每个会话用不同模型」这件事** —— 那个语义完全由数据库承担。
 * 这里只需要服务「还没落库的那一个会话」。
 *
 * ## 为什么只存**一个**键
 *
 * 曾按会话 id 分键（`..._<conversationId>`），但那会**只增不减**：
 * 会话一旦被丢弃（默认「启动时新建对话」下每次重启都换 id），它的键就永远留着。
 *
 * 而「需要兜底的会话」**任何时刻最多一个** —— 能进入未落库状态的只有全新空会话
 * （分叉会立刻 `saveConversation`，故一建就是已落库），而用户一次只能待在一个里，
 * 离开后它也不在会话列表里（列表只列库中的行），回不去。
 *
 * 故单键足够，且不会累积。
 *
 * ## 单键的安全前提（**必须**）
 *
 * 键值里**同时记下它属于哪个会话**，读取时核对 —— 否则会把 A 的模型塞给 B。
 * [load] 与 [delete] 都只对「同一个会话」生效；核对逻辑抽成纯函数 [resolve]，
 * 以便离线验算（见 `ConversationModelPickStoreTest`）。
 */
class ConversationModelPickStore(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
    }

    /** 读该会话上次选择的模型。**只在该会话正是记录里那个会话时才返回**。 */
    fun load(conversationId: Uuid): Uuid? =
        resolve(prefs.getString(KEY, null), conversationId)

    /** 记下该会话选择的模型；传 null 等于删除。 */
    fun save(conversationId: Uuid, modelId: Uuid?) {
        if (modelId == null) {
            delete(conversationId)
        } else {
            prefs.edit().putString(KEY, encode(conversationId, modelId)).apply()
        }
    }

    /** 丢弃记录 —— 但**只在它属于该会话时**（避免删掉别的会话的记录）。 */
    fun delete(conversationId: Uuid) {
        val (storedId, _) = decode(prefs.getString(KEY, null)) ?: return
        if (storedId == conversationId) {
            prefs.edit().remove(KEY).apply()
        }
    }

    companion object {
        /** 与 `ChatDraftStore` 同源（同一个 `rikkahub.preferences` 文件）。 */
        internal const val KEY = "chat_model_pick_v1"

        /** 单键的编码：`<会话 id>:<模型 id>`。 */
        internal fun encode(conversationId: Uuid, modelId: Uuid): String =
            "$conversationId:$modelId"

        /** 解析；任何不完整或损坏的内容都返回 null（当作**没有记录**）。 */
        internal fun decode(raw: String?): Pair<Uuid, Uuid>? {
            val parts = raw?.split(":", limit = 2) ?: return null
            if (parts.size != 2) return null
            val conversationId = runCatching { Uuid.parse(parts[0]) }.getOrNull() ?: return null
            val modelId = runCatching { Uuid.parse(parts[1]) }.getOrNull() ?: return null
            return conversationId to modelId
        }

        /**
         * 取出属于 [conversationId] 的模型。
         *
         * 记录属于**别的**会话时返回 null —— 这一条正是单键方案不串会话的保证。
         */
        internal fun resolve(raw: String?, conversationId: Uuid): Uuid? {
            val (storedId, modelId) = decode(raw) ?: return null
            return if (storedId == conversationId) modelId else null
        }
    }
}
