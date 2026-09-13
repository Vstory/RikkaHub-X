// [X-custom] RikkaHub-X 定制(merge 上游时保留): 会话级模型切换逻辑与 UI 即时刷新
package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.x.chat.ChatDraftStore
import me.rerere.rikkahub.x.chat.ConversationModelPickStore
import me.rerere.rikkahub.x.chat.XChatEvents
import me.rerere.rikkahub.x.chat.getConversationChatModel
import me.rerere.rikkahub.x.diag.XDomain
import me.rerere.rikkahub.x.diag.XLog
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // [X-custom] issue 1715: 会话输入草稿持久化(切换窗口/离开后回来不丢失)
    private val chatDraftStore = ChatDraftStore(context)
    private var draftDebounceJob: Job? = null

    // [X-custom] 会话级模型选择的兜底持久化：新会话在首条消息之前不落库,
    // 那次选择只活在内存里(空闲回收/进程退出即丢),故另存一份(见该类注释)。
    private val modelPickStore = ConversationModelPickStore(context)

    val voiceSession = VoiceSessionController(viewModelScope, context::getString) {
        chatService.enqueueVoiceMessage(_conversationId, it)
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
            // [X-custom] 初始化之后补一次:新建会话尚未落库,库里没有会话级模型,
            // 用持久化的上次选择补上(否则选好模型离开再回来,选择就丢了)。
            // 必须排在本调用**之后** —— 否则会被上面的新建分支覆盖掉。
            restoreModelPickIfNeeded()
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())

        // [X-custom] issue 1715: 进入会话时恢复该会话上次未发送的草稿(仅当输入框为空且非编辑态)
        val draft = chatDraftStore.load(_conversationId)
        if (!draft.isNullOrBlank() && inputState.isEmpty() && !inputState.isEditing()) {
            inputState.setMessageText(draft)
        }
    }

    override fun onCleared() {
        voiceSession.stop()
        // [X-custom] issue 1715: 离开会话兜底保存草稿(VM 销毁时最新输入落盘)
        persistDraftNow()
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    /**
     * [X-custom] issue 1715: 输入文本变化上报(由 ChatPage snapshotFlow 每帧文本变化调用)。
     * 防抖 600ms 落盘;编辑历史消息时不落草稿(避免恢复成普通新消息)。
     */
    fun onDraftInputChanged() {
        draftDebounceJob?.cancel()
        if (inputState.isEditing()) return
        draftDebounceJob = viewModelScope.launch {
            delay(600)
            persistDraftNow()
        }
    }

    private fun persistDraftNow() {
        if (inputState.isEditing()) return
        val text = inputState.textContent.text.toString().trim()
        if (text.isBlank()) {
            chatDraftStore.delete(_conversationId)
        } else {
            chatDraftStore.save(_conversationId, text)
        }
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    // [X-custom] 与生成侧同源:会话级覆盖 → 会话绑定助手 → 全局默认。
    // 原先读 getCurrentChatModel()(只看助手级/全局),只选了会话级模型时此处判为 null,
    // 而 ChatService 与模型选择器都已认得它 —— 发送按钮的闸门正基于此处,
    // 表现为「明明选了模型,点发送却说请先选择模型」。口径见 ConversationAssistantScope.kt。
    val currentChatModel = combine(settings, conversation) { current, conv ->
        current.getConversationChatModel(conv)
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    val messageQueue = chatService.getMessageQueueFlow(_conversationId)

    fun removeQueuedMessage(id: Uuid) = chatService.removeQueuedMessage(_conversationId, id)

    fun beginEditQueuedMessage(id: Uuid) = chatService.beginEditQueuedMessage(_conversationId, id)

    fun finishEditQueuedMessage(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishEditQueuedMessage(_conversationId, id, parts)

    fun resumeMessageQueue() = chatService.resumeMessageQueue(_conversationId)

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置当前会话的聊天模型(会话级覆盖,不影响其他会话)
    fun setChatModel(conversationId: Uuid, model: Model) {
        viewModelScope.launch {
            // 先同步内存态(活跃 session),触发 UI 即时刷新当前会话的模型显示;
            // 否则仅落库时,内存 session.state.modelId 仍是旧值,
            // 需切走再切回(initializeConversation 重新读库)才显示,且后续整对象保存会把旧值覆盖回去。
            // 模式对齐 moveConversationToFolder:先内存后落库。
            chatService.updateConversationState(conversationId) { it.copy(modelId = model.id) }
            conversationRepo.updateConversationModelId(
                conversationId = conversationId,
                modelId = model.id
            )
            // [X-custom] 会话尚未落库时,上面那条 UPDATE 是**静默 0 行**
            // (saveConversation 的规则:新会话且为空时不保存) —— 只改内存,
            // 空闲回收或进程退出即丢。故此处另存一份兜底。
            if (!conversationRepo.existsConversationById(conversationId)) {
                modelPickStore.save(conversationId, model.id)
            }
        }
    }

    /**
     * [X-custom] 会话未落库时,用持久化的上次选择恢复会话级模型。
     *
     * 只在「会话不在库里」时参与 —— 一旦会话已落库,数据库就是权威,
     * 同时把兜底数据清掉(避免无限累积)。模型若已被删除(provider 变更),
     * `findModelById` 返回 null,**静默跳过** —— 与「不认识就忽略」的口径一致。
     */
    private suspend fun restoreModelPickIfNeeded() {
        val picked = modelPickStore.load(_conversationId) ?: return
        if (conversationRepo.existsConversationById(_conversationId)) {
            modelPickStore.delete(_conversationId)
            return
        }
        // 本次已经选好了(用户手速快,或上面保留了内存态) → 无需恢复
        if (chatService.getConversationFlow(_conversationId).value.modelId != null) return
        val model = settingsStore.settingsFlowRaw.first().findModelById(picked) ?: return
        setChatModel(_conversationId, model)
        XLog.info(XDomain.CHAT, XChatEvents.MODEL_PICK_RESTORED) {
            "已恢复该会话上次选择的模型"
        }
    }

    // Update checker
    val updateState = settingsStore.settingsFlow
        .map { settings ->
            !settings.init &&
                settings.displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
        }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (enabled) updateChecker.updateState else flowOf(UiState.Loading)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            UiState.Loading,
        )

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        XLog.info(XDomain.CHAT, XChatEvents.MESSAGE_SENT) { "发送消息" }

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        XLog.info(XDomain.CHAT, XChatEvents.MESSAGE_EDITED) { "编辑消息" }

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        XLog.info(XDomain.CHAT, XChatEvents.MESSAGE_REGENERATED) { "重新生成" }
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        // 审批是同意还是拒绝,是这两个动作里唯一有信息量的差别 —— 故记进 msg。
        // 工具名与调用 id 不记:它们属于内容侧,而本域的约定是不记自由文本。
        XLog.info(XDomain.CHAT, XChatEvents.TOOL_APPROVED) {
            if (approved) "工具调用已同意" else "工具调用已拒绝"
        }
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        XLog.info(XDomain.CHAT, XChatEvents.TOOL_ANSWERED) { "工具调用已由用户作答" }
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // 文件夹是助手内分组，切换助手后原文件夹在新助手下不可见，需清空归属避免会话丢失
            val updatedConversation = conversationFull.copy(
                assistantId = targetAssistantId,
                folderId = null,
            )
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
