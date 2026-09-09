package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceExportReport
import me.rerere.rikkahub.data.repository.WorkspaceImportPreview
import me.rerere.rikkahub.data.repository.WorkspaceImportResult
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.x.WorkspaceArchiveProgress
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError = _settingsError.asStateFlow()

    /** null = 空闲;非 null = 导出/导入进行中 */
    private val _transfer = MutableStateFlow<WorkspaceTransferUi?>(null)
    val transfer = _transfer.asStateFlow()

    /** 导入预览结果(确认对话框用),由 UI 触发 dismiss */
    private val _importCandidate = MutableStateFlow<WorkspaceImportPreview?>(null)
    val importCandidate = _importCandidate.asStateFlow()

    /** 一次性事件(导出完成/导入完成/错误等),UI 展示后 dismiss */
    private val _transferEvent = MutableStateFlow<WorkspaceTransferEvent?>(null)
    val transferEvent = _transferEvent.asStateFlow()

    fun dismissSettingsError() {
        _settingsError.value = null
    }

    /** UI 侧的即时错误提示(如读文件失败) */
    fun showTransferError(message: String) {
        _transferEvent.value = WorkspaceTransferEvent.Error(message)
    }

    fun dismissImportCandidate() {
        _importCandidate.value = null
    }

    fun dismissTransferEvent() {
        _transferEvent.value = null
    }

    init {
        loadWorkspace()
        refresh()
        // 若存在暂存的用户区(之前导入时 rootfs 未装)且环境已就绪 → 自动合入
        finishPendingImportIfNeeded()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    suspend fun resolveImageFile(
        entry: WorkspaceFileEntry,
        area: WorkspaceStorageArea,
    ): File = repository.resolveFile(id, area, entry.path)

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun setShellCompatibilityMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setShellCompatibilityMode(id, enabled)
                val workspace = repository.getById(id)
                _state.update { it.copy(workspace = workspace) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _settingsError.value = error.message.orEmpty()
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
                // rootfs 装好后,若存在暂存的用户区导入 → 合入并提示还原工具
                finishPendingImportIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    // ===== 工作区归档 导出 / 导入 =====

    fun exportWorkspaceArchive(output: OutputStream) {
        val workspace = state.value.workspace ?: return
        viewModelScope.launch {
            _transferEvent.value = null
            _transfer.value = WorkspaceTransferUi(isExport = true)
            try {
                val report = repository.exportWorkspaceArchive(workspace.id, output) { progress ->
                    _transfer.value = WorkspaceTransferUi(isExport = true, progress = progress)
                }
                _transferEvent.value = WorkspaceTransferEvent.ExportFinished(report)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _transferEvent.value = WorkspaceTransferEvent.Error(error.message ?: "导出失败")
            } finally {
                _transfer.value = null
            }
        }
    }

    fun previewImport(file: File) {
        viewModelScope.launch {
            runCatching { repository.previewWorkspaceArchive(file) }
                .onSuccess { _importCandidate.value = it }
                .onFailure { error ->
                    _transferEvent.value =
                        WorkspaceTransferEvent.Error(error.message ?: "解析归档失败")
                }
        }
    }

    fun runImport(file: File) {
        if (_importCandidate.value == null) return
        viewModelScope.launch {
            _importCandidate.value = null
            _transferEvent.value = null
            _transfer.value = WorkspaceTransferUi(isExport = false)
            try {
                val result = repository.importWorkspaceArchive(file) { progress ->
                    _transfer.value = WorkspaceTransferUi(isExport = false, progress = progress)
                }
                _transferEvent.value = WorkspaceTransferEvent.ImportFinished(result)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _transferEvent.value = WorkspaceTransferEvent.Error(error.message ?: "导入失败")
            } finally {
                _transfer.value = null
                refresh()
            }
        }
    }

    /** 环境就绪后调用:若存在暂存的用户区导入则合入;无暂存时无操作 */
    fun finishPendingImportIfNeeded() {
        viewModelScope.launch {
            runCatching { repository.finishPendingUserAreaImport(id) }
                .getOrNull()
                ?.let { result ->
                    _transferEvent.value = WorkspaceTransferEvent.PendingMerged(result)
                    loadWorkspace()
                }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** 导出/导入进行中状态;null = 空闲 */
data class WorkspaceTransferUi(
    val isExport: Boolean = true,
    val progress: WorkspaceArchiveProgress? = null,
)

/** 导出/导入的一次性事件(UI 展示后 dismiss) */
sealed interface WorkspaceTransferEvent {
    data class ExportFinished(val report: WorkspaceExportReport) : WorkspaceTransferEvent
    data class ImportFinished(val result: WorkspaceImportResult) : WorkspaceTransferEvent
    data class PendingMerged(val result: WorkspaceImportResult) : WorkspaceTransferEvent
    data class Error(val message: String) : WorkspaceTransferEvent
}

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}
