package me.rerere.rikkahub.x.chat

import android.content.Context
import kotlin.uuid.Uuid

/**
 * [X-custom] issue 1715:会话输入草稿持久化 —— 切换窗口/离开会话后回来内容不丢失。
 *
 * - 仅持久化**纯文本**草稿;图片/文件/语音附件涉及本地临时文件生命周期,暂不纳入(评估文档注明);
 * - 按会话隔离:key = chat_draft_v1_<conversationId>;
 * - 复用全局 SharedPreferences "rikkahub.preferences"(与 ui/hooks 的 read/writeStringPreference 同文件)。
 */
class ChatDraftStore(private val context: Context) {
    private val prefs by lazy {
        context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
    }

    fun load(conversationId: Uuid): String? =
        prefs.getString(key(conversationId), null)

    fun save(conversationId: Uuid, text: String) {
        prefs.edit().putString(key(conversationId), text).apply()
    }

    fun delete(conversationId: Uuid) {
        prefs.edit().remove(key(conversationId)).apply()
    }

    private fun key(conversationId: Uuid) = "chat_draft_v1_$conversationId"
}
