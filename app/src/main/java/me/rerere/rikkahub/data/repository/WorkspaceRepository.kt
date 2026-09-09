// [X-custom] RikkaHub-X 定制(与上游合并对照 X-CUSTOM.md 保留): 工作区导出/导入归档编排(调用 me.rerere.workspace.x.WorkspaceArchiver)
package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.x.WorkspaceArchiveFile
import me.rerere.workspace.x.WorkspaceArchiveManifest
import me.rerere.workspace.x.WorkspaceArchiveProgress
import me.rerere.workspace.x.WorkspaceArchiver
import me.rerere.workspace.x.WORKSPACE_ARCHIVE_FORMAT
import me.rerere.workspace.x.WORKSPACE_ARCHIVE_VERSION
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        dao.setShellCompatibilityMode(id, enabled, System.currentTimeMillis())
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun resolveFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.resolveFile(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileSize(workspace.root, path)
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.exportRootfsFile(workspace.root, path, outputStream)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(
                workspace.root, command, cwd, timeoutMillis, stdin,
                shellCompatibilityMode = workspace.shellCompatibilityMode,
            )
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    // ==================== 工作区归档:导出 / 导入(声明式工具还原) ====================

    /**
     * 导出工作区归档(.tar.gz)。
     * 仅含 manifest + 工具清单 tools/ + rootfs 用户区(usr/local opt home root etc)+ files/,
     * 不含可重装的 rootfs 发行版本体。
     * rootfs 不健康时降级:仍导出 files 与用户区,但无法采集工具清单。
     */
    suspend fun exportWorkspaceArchive(
        id: String,
        output: OutputStream,
        onProgress: (WorkspaceArchiveProgress) -> Unit = {},
    ): WorkspaceExportReport {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val wsDir = manager.workspaceDir(workspace.root)
        val healthy = manager.hasRootfs(workspace.root)
        val scan = if (healthy) scanWorkspaceTools(id) else WorkspaceToolScan()
        val virtualFiles = buildToolFiles(scan, workspace.name)
        val includedUserArea = WorkspaceArchiver.USER_AREA_RELATIVE.any {
            File(manager.linuxDir(workspace.root), it).isDirectory
        }
        val manifest = WorkspaceArchiveManifest(
            id = workspace.id,
            root = workspace.root,
            name = workspace.name,
            shellCompatibilityMode = workspace.shellCompatibilityMode,
            exportedAtEpochMillis = System.currentTimeMillis(),
            includesUserArea = includedUserArea,
            includesTools = virtualFiles.isNotEmpty(),
        )
        withContext(Dispatchers.IO) {
            WorkspaceArchiver.writeArchive(wsDir, manifest, output, virtualFiles, onProgress)
        }
        return WorkspaceExportReport(
            healthyRootfs = healthy,
            toolsCaptured = virtualFiles.isNotEmpty(),
            toolScan = scan,
            includedUserArea = includedUserArea,
        )
    }

    /** 在可运行 rootfs 内探测手动安装的工具(apt / pip / npm + /usr/local bin) */
    suspend fun scanWorkspaceTools(id: String): WorkspaceToolScan = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext WorkspaceToolScan()
        if (!manager.hasRootfs(workspace.root)) return@withContext WorkspaceToolScan()

        fun probe(command: String): List<String> {
            val stdout = runCatching {
                manager.executeCommand(
                    workspace.root,
                    command,
                    timeoutMillis = TOOL_SCAN_TIMEOUT_MS,
                    shellCompatibilityMode = workspace.shellCompatibilityMode,
                )
            }.getOrNull()?.stdout.orEmpty()
            return stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        }

        WorkspaceToolScan(
            aptPackages = probe(CMD_APT_MANUAL),
            pipPackages = probe(CMD_PIP_LIST),
            npmPackages = probe(CMD_NPM_GLOBAL),
            localBins = collectLocalBins(workspace.root),
        )
    }

    /**
     * 预览归档(导入前展示确认):读 manifest、扫描顶层内容、统计工具数。
     */
    suspend fun previewWorkspaceArchive(file: File): WorkspaceImportPreview =
        withContext(Dispatchers.IO) {
            val manifest = file.inputStream().use { WorkspaceArchiver.readManifest(it) }
            manifest.requireSupported()
            val paths = file.inputStream().use { WorkspaceArchiver.listArchivePaths(it) }
            val hasFiles = "files" in paths
            val hasUserArea = WorkspaceArchiver.USER_AREA_RELATIVE.any { "linux/$it" in paths }
            val hasTools = "tools" in paths
            val toolCount = if (hasTools) {
                file.inputStream().use {
                    WorkspaceArchiver.readArchiveFile(it, "tools/tools.txt")
                }?.decodeToString()?.lineSequence()
                    ?.filter { line -> line.isNotBlank() && !line.startsWith("#") && !line.startsWith("##") }
                    ?.count() ?: 0
            } else 0
            val existing = dao.getById(manifest.id)
            WorkspaceImportPreview(
                manifest = manifest,
                hasFiles = hasFiles,
                hasUserArea = hasUserArea,
                hasTools = hasTools,
                toolCount = toolCount,
                targetExists = existing != null,
                targetRootfsReady = existing != null && manager.hasRootfs(existing.root),
            )
        }

    /**
     * 导入归档到本机:
     *  - 保留 manifest 原 id(有则填活原记录;无则新建同 id 工作区)
     *  - files/ 立即合入文件区
     *  - tools/ 落盘到文件区根(restore-tools.sh / tools.txt)
     *  - rootfs 用户区:rootfs 已就绪则立即合入 linux/;否则整包暂存 .rikkahub/pending_restore.tar.gz,
     *    待用户先安装 rootfs(重装只重置 linux/,files 不受影响)后由 finishPendingUserAreaImport 合入
     */
    suspend fun importWorkspaceArchive(
        file: File,
        onProgress: (WorkspaceArchiveProgress) -> Unit = {},
    ): WorkspaceImportResult {
        val manifest = file.inputStream().use { WorkspaceArchiver.readManifest(it) }
        manifest.requireSupported()
        // [X-fix] 先取样目标是否已存在(ensureImportTarget 内部会 upsert,事后判断恒为 true)
        val targetExisted = dao.getById(manifest.id) != null
        val target = ensureImportTarget(manifest)
        var restoredFiles = false
        var restoredUserAreaNow = false
        var userAreaPending = false
        var toolsRestored = false
        val toolCount: Int

        withContext(Dispatchers.IO) {
            manager.ensureWorkspace(target.root)
            val metaDir = File(manager.workspaceDir(target.root), META_DIR).apply { mkdirs() }
            val staging = File(metaDir, IMPORT_STAGING_DIR)
            file.inputStream().use { WorkspaceArchiver.extractArchive(it, staging, onProgress) }

            // 1. files 工作区
            val stagingFiles = File(staging, "files")
            if (stagingFiles.isDirectory) {
                WorkspaceArchiver.mergeTree(stagingFiles, manager.filesDir(target.root))
                restoredFiles = true
            }

            // 2. rootfs 用户区
            val stagingLinux = File(staging, "linux")
            val rootfsReady = manager.hasRootfs(target.root)
            when {
                rootfsReady && stagingLinux.isDirectory -> {
                    mergeUserArea(stagingLinux, target.root)
                    restoredUserAreaNow = true
                }

                !rootfsReady && stagingLinux.isDirectory -> {
                    // 暂存整包:rootfs 安装完成后合入用户区(此时文件区若已有内容,重放覆盖无害)
                    file.copyTo(File(metaDir, PENDING_IMPORT_FILE), overwrite = true)
                    userAreaPending = true
                }
            }

            // 3. tools 落盘到文件区根(用户可见、终端可达)
            val stagingTools = File(staging, "tools")
            if (stagingTools.isDirectory) {
                copyToolsToFiles(stagingTools, target.root)
                toolsRestored = true
            }

            toolCount = if (File(manager.filesDir(target.root), TOOLS_TXT).isFile) {
                File(manager.filesDir(target.root), TOOLS_TXT).readLines()
                    .count { line -> line.isNotBlank() && !line.startsWith("#") && !line.startsWith("##") }
            } else 0

            staging.deleteRecursively()
        }
        return WorkspaceImportResult(
            workspaceId = target.id,
            targetCreated = !targetExisted,
            restoredFiles = restoredFiles,
            restoredUserAreaNow = restoredUserAreaNow,
            userAreaPending = userAreaPending,
            toolsRestored = toolsRestored,
            toolCount = toolCount,
        )
    }

    /**
     * rootfs 安装完成后调用:把暂存的用户区合入新装的 linux/ 并清理暂存。
     * 无暂存或 rootfs 仍未就绪时返回 null。
     */
    suspend fun finishPendingUserAreaImport(id: String): WorkspaceImportResult? {
        val workspace = dao.getById(id) ?: return null
        val metaDir = File(manager.workspaceDir(workspace.root), META_DIR)
        val pending = File(metaDir, PENDING_IMPORT_FILE)
        if (!pending.isFile) return null
        if (!manager.hasRootfs(workspace.root)) return null

        var restoredUserArea = false
        var toolsRestored = false
        withContext(Dispatchers.IO) {
            val staging = File(metaDir, "${IMPORT_STAGING_DIR}-finalize")
            pending.inputStream().use { WorkspaceArchiver.extractArchive(it, staging) }
            val stagingLinux = File(staging, "linux")
            if (stagingLinux.isDirectory) {
                mergeUserArea(stagingLinux, workspace.root)
                restoredUserArea = true
            }
            val stagingTools = File(staging, "tools")
            if (stagingTools.isDirectory) {
                copyToolsToFiles(stagingTools, workspace.root)
                toolsRestored = true
            }
            staging.deleteRecursively()
            pending.delete()
        }
        val toolCount = File(manager.filesDir(workspace.root), TOOLS_TXT)
            .takeIf { it.isFile }?.readLines()
            ?.count { line -> line.isNotBlank() && !line.startsWith("#") && !line.startsWith("##") }
            ?: 0
        return WorkspaceImportResult(
            workspaceId = workspace.id,
            targetCreated = false,
            restoredFiles = false,
            restoredUserAreaNow = restoredUserArea,
            userAreaPending = false,
            toolsRestored = toolsRestored,
            toolCount = toolCount,
        )
    }

    /** 本机无该归档工作区记录时,按 manifest 原 id 新建(名字冲突自动去重) */
    private suspend fun ensureImportTarget(manifest: WorkspaceArchiveManifest): WorkspaceEntity {
        dao.getById(manifest.id)?.let { return it }
        val taken = dao.getAll().map { it.name }.toSet()
        val baseName = manifest.name.trim().ifBlank { "Workspace" }
        var name = baseName
        var suffix = 1
        while (name in taken) {
            suffix++
            name = "$baseName ($suffix)"
        }
        val now = System.currentTimeMillis()
        val entity = WorkspaceEntity(
            id = manifest.id,
            name = name,
            root = manifest.id,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(entity)
        return entity
    }

    private fun mergeUserArea(stagingLinux: File, root: String) {
        val linuxDir = manager.linuxDir(root)
        for (sub in WorkspaceArchiver.USER_AREA_RELATIVE) {
            val src = File(stagingLinux, sub)
            if (src.isDirectory) {
                WorkspaceArchiver.mergeTree(src, File(linuxDir, sub))
            }
        }
    }

    private fun copyToolsToFiles(stagingTools: File, root: String) {
        val filesDir = manager.filesDir(root)
        val staged = File(stagingTools, TOOLS_TXT)
        if (staged.isFile) {
            staged.copyTo(File(filesDir, TOOLS_TXT), overwrite = true)
        }
        val script = File(stagingTools, RESTORE_SCRIPT)
        if (script.isFile) {
            val target = File(filesDir, RESTORE_SCRIPT)
            script.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
        }
    }

    private fun collectLocalBins(root: String): List<String> {
        val linuxDir = manager.linuxDir(root)
        val names = linkedSetOf<String>()
        for (sub in LOCAL_BIN_DIRS) {
            val dir = File(linuxDir, sub)
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile || Files.isSymbolicLink(file.toPath())) names += file.name
                }
            }
        }
        return names.toList()
    }

    /** 生成 tools.txt(可读清单)+ restore-tools.sh(一键还原) */
    private fun buildToolFiles(scan: WorkspaceToolScan, workspaceName: String): Map<String, WorkspaceArchiveFile> {
        val hasAutomated = scan.aptPackages.isNotEmpty() || scan.pipPackages.isNotEmpty() || scan.npmPackages.isNotEmpty()
        val hasAny = hasAutomated || scan.localBins.isNotEmpty()
        if (!hasAny) return emptyMap()

        val date = java.util.Date().toString()
        val txt = buildString {
            appendLine("# RikkaHub Workspace Tools")
            appendLine("# workspace: $workspaceName")
            appendLine("# exported: $date")
            appendLine()
            appendSection("apt (manual, apt-mark showmanual)", scan.aptPackages)
            appendSection("pip3 (global)", scan.pipPackages)
            appendSection("npm (global)", scan.npmPackages)
            if (scan.localBins.isNotEmpty()) {
                appendLine("## /usr/local bin (manual, not auto-restorable)")
                scan.localBins.forEach { appendLine("  $it") }
            }
        }
        val script = buildString {
            appendLine("#!/bin/sh")
            appendLine("# RikkaHub workspace tools restore script")
            appendLine("# workspace: $workspaceName")
            appendLine("# exported: $date")
            appendLine("# 在终端中执行: sh restore-tools.sh(需联网,root 用户)")
            appendLine("set -e")
            appendLine()
            if (scan.aptPackages.isNotEmpty()) {
                appendLine("echo '==> [1/3] apt-get install ...'")
                appendLine("apt-get update -qq")
                appendLine("printf '%s\\n' ${scan.aptPackages.joinToString(" ")} \\")
                appendLine("  | xargs -r DEBIAN_FRONTEND=noninteractive apt-get install -y -qq")
            }
            if (scan.pipPackages.isNotEmpty()) {
                appendLine("echo '==> [2/3] pip3 install ...'")
                appendLine("if pip3 install --help 2>/dev/null | grep -q -- --break-system-packages; then")
                appendLine("  printf '%s\\n' ${scan.pipPackages.joinToString(" ")} \\")
                appendLine("    | xargs -r pip3 install --break-system-packages -q")
                appendLine("else")
                appendLine("  printf '%s\\n' ${scan.pipPackages.joinToString(" ")} | xargs -r pip3 install -q")
                appendLine("fi")
            }
            if (scan.npmPackages.isNotEmpty()) {
                appendLine("echo '==> [3/3] npm install -g ...'")
                appendLine("npm install -g ${scan.npmPackages.joinToString(" ")}")
            }
            if (scan.localBins.isNotEmpty()) {
                appendLine("echo")
                appendLine("echo '提示: /usr/local 下还有 ${scan.localBins.size} 个手工二进制/文件无法自动还原, 见 tools.txt 的 /usr/local bin 段'")
            }
            appendLine("echo '==> Done'")
        }
        return mapOf(
            "tools/$TOOLS_TXT" to WorkspaceArchiveFile(txt.toByteArray(), 0x1A4), // 0644
            "tools/$RESTORE_SCRIPT" to WorkspaceArchiveFile(script.toByteArray(), 0x1ED), // 0755
        )
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024

        // ---- 归档导出 / 导入 ----
        private const val META_DIR = ".rikkahub"
        private const val PENDING_IMPORT_FILE = "pending_restore.tar.gz"
        private const val IMPORT_STAGING_DIR = "import-staging"
        private const val TOOLS_TXT = "tools.txt"
        private const val RESTORE_SCRIPT = "restore-tools.sh"
        private const val TOOL_SCAN_TIMEOUT_MS = 90_000L
        private val LOCAL_BIN_DIRS = listOf("usr/local/bin", "usr/local/sbin")
        private const val CMD_APT_MANUAL =
            "command -v apt-mark >/dev/null 2>&1 && apt-mark showmanual 2>/dev/null | LC_ALL=C sort -u || true"
        private const val CMD_PIP_LIST =
            "command -v pip3 >/dev/null 2>&1 && pip3 list --format=freeze 2>/dev/null | sed 's/==.*//' | LC_ALL=C sort -u || true"
        private const val CMD_NPM_GLOBAL =
            "command -v npm >/dev/null 2>&1 && npm ls -g --parseable --depth=0 2>/dev/null | sed 's#.*/node_modules/##' | LC_ALL=C sort -u || true"
    }
}

/** 导入/预览共用的归档格式 + 版本防线(version 高于当前支持 → 拒绝,防旧版 App 误读未来格式) */
private fun WorkspaceArchiveManifest.requireSupported() {
    require(format == WORKSPACE_ARCHIVE_FORMAT) {
        "不是 RikkaHub 工作区归档(format=$format)"
    }
    require(version in 1..WORKSPACE_ARCHIVE_VERSION) {
        "归档版本不支持(version=$version, 当前支持 ≤ $WORKSPACE_ARCHIVE_VERSION)"
    }
    // [X-fix] 归档 id 会被直接用作工作区文件系统目录名(root = id),必须是合法 UUID:
    // ROOT_NAME_REGEX([A-Za-z0-9._-]+) 放行 "." / "..",恶意/损坏归档可借 id 逃逸工作区基目录。
    require(runCatching { Uuid.parse(id) }.isSuccess) {
        "归档 id 非法(需为 UUID):$id"
    }
}

private fun StringBuilder.appendSection(title: String, items: List<String>) {
    if (items.isEmpty()) return
    appendLine("## $title (${items.size})")
    items.forEach { appendLine("  $it") }
    appendLine()
}

// ==================== 工作区归档 数据模型(顶层,供 UI/VM 使用) ====================

/** rootfs 内可探测的"用户手动安装的工具"扫描结果 */
data class WorkspaceToolScan(
    val aptPackages: List<String> = emptyList(),
    val pipPackages: List<String> = emptyList(),
    val npmPackages: List<String> = emptyList(),
    /** /usr/local/bin、/usr/local/sbin 下的散装二进制(无法自动还原,仅记录) */
    val localBins: List<String> = emptyList(),
) {
    val total: Int get() = aptPackages.size + pipPackages.size + npmPackages.size + localBins.size
}

data class WorkspaceExportReport(
    /** 导出时 rootfs 是否健康可运行(决定工具清单能否采集) */
    val healthyRootfs: Boolean,
    val toolsCaptured: Boolean,
    val toolScan: WorkspaceToolScan = WorkspaceToolScan(),
    val includedUserArea: Boolean,
)

data class WorkspaceImportPreview(
    val manifest: WorkspaceArchiveManifest,
    val hasFiles: Boolean,
    val hasUserArea: Boolean,
    val hasTools: Boolean,
    val toolCount: Int,
    /** 本机已存在同 id 工作区记录(配合应用备份恢复,BROKEN 填活) */
    val targetExists: Boolean,
    /** 目标工作区 rootfs 已就绪(决定用户区立即合入还是暂存待装) */
    val targetRootfsReady: Boolean,
)

data class WorkspaceImportResult(
    val workspaceId: String,
    val targetCreated: Boolean,
    val restoredFiles: Boolean,
    val restoredUserAreaNow: Boolean,
    /** rootfs 未就绪:用户区暂存,待安装完成后由 finishPendingUserAreaImport 合入 */
    val userAreaPending: Boolean,
    val toolsRestored: Boolean,
    val toolCount: Int,
)
