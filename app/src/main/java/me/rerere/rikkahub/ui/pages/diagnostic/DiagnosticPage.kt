package me.rerere.rikkahub.ui.pages.diagnostic

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.x.diag.NoExportProgress
import me.rerere.rikkahub.x.diag.XDiagClear
import me.rerere.rikkahub.x.diag.XDiagExportNotifier
import me.rerere.rikkahub.x.diag.XDiagEnv
import me.rerere.rikkahub.x.diag.XDiagSession
import me.rerere.rikkahub.x.diag.XDiagZip
import me.rerere.rikkahub.x.diag.XDiagnostics
import me.rerere.rikkahub.x.diag.XDomain
import me.rerere.rikkahub.x.diag.XExportPhase
import me.rerere.rikkahub.x.diag.XExportProgress
import me.rerere.rikkahub.x.diag.XLogRing
import me.rerere.rikkahub.x.diag.XLogScrub
import me.rerere.rikkahub.x.diag.XLogcatCapture
import me.rerere.rikkahub.x.diag.XRedaction
import me.rerere.rikkahub.x.diag.countingStream
import org.koin.compose.koinInject

/**
 * 诊断页 —— **本应用的**运行记录与完整日志。
 *
 * ## 它解决什么问题
 *
 * X 的功能都长在上游代码的夹缝里（存储层、压缩、容量表、同步…），出问题时**没有办法在现场取证**：
 * 用户能拿到的往往只有「不好使」三个字。本页把现场摊开，让用户当场看到发生了什么，
 * 并**导出一个文件**交给开发者。
 *
 * ## 覆盖面:整个应用,而不是只有 X（2026-09-12 纠偏）
 *
 * 早先这页只记 X 定制自己的语义事件，注释里甚至写着「本页看『X 做了什么』，
 * 上游页看『系统发生了什么』」。**那个划分是错的** —— 真机实测（2026-09-12）证明
 * 零权限就能读到本 UID 的全部日志，也就是**整个应用做过什么本来就能拿到**。
 * 于是现在有两层:
 *
 * | 层 | 来源 | 覆盖 |
 * |---|---|---|
 * | 广度 | [XLogcatCapture] | 上游 206 处 `Log.*` + 框架日志（OkHttp/Room/Coil/Compose）+ 崩溃栈 + 本进程内的 OEM 框架日志 |
 * | 深度 | [XDiagnostics] | logcat 表达不出来的:去重命中数、字节数、阶段耗时、关键失败留存 |
 *
 * 只看 X 那几十处埋点，等于把「应用实际做了什么」丢掉了一大半。
 *
 * ## 导出为什么是文件而不是剪贴板
 *
 * 完整日志有几十到几百 MB，**剪贴板装不下**，而且剪贴板留不下东西 —— 用户还得再找地方粘贴。
 * 现在点一下走系统的「创建文档」，用户自己选存到哪（下载目录、文件管理器任意位置）。
 * 应用日志在写出前**逐行脱敏**（文件本身不脱敏，交给用户时才过一遍）。
 *
 * ## 数据从哪来
 *
 * 直接读 [XDiagnostics] —— 它是进程内单例，不是 Flow。只有本页会改它（开关、清空），
 * 故用一个本地版本号触发重读即可，不必为它引入状态流（那会让每次埋点都走一次 Flow 派发）。
 * 捕获体积是唯一会持续变化的量，另用一个每秒 +1 的 tick 驱动刷新。
 */
@Composable
fun DiagnosticPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    // 导出跑在**应用级**作用域上,不是本页的:本页的作用域会随页面一起取消 ——
    // 用户在导出途中退出去看别的,写了一半的文件就会被丢在那里。
    val appScope: AppScope = koinInject()

    // 本地版本号：开关与清空之后 +1，以下所有快照随之重算。
    // tick 是另一回事 —— 日志体积会持续增长，靠它每秒 +1 让页面上的数字跟着动，
    // 否则「看得见体积」只是句空话。
    var revision by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    val enabled = remember(revision) { XDiagnostics.isEnabled() }
    val total = remember(revision) { XDiagnostics.totalCount() }
    val windowStart = remember(revision) { XDiagnostics.windowStartMillis() }
    val perDomain = remember(revision) {
        XDomain.entries
            .map { it to XDiagnostics.countOf(it) }
            .filter { (_, count) -> count > 0 }
    }
    // 命令与 tag 都来自同一处常量，不会出现「照着敲却一条看不到」
    val logcatHint = remember { XDiagnostics.logcatHint() }
    val sticky = remember(revision) { XDiagnostics.stickyFailure() }
    val capture = remember(revision, tick) { XLogcatCapture.current() }
    val captureFile = remember(revision, tick) { XLogcatCapture.latestLogFile(context) }

    val filesRoot = remember(context) { context.filesDir.absolutePath }

    // 待导出的内容。先记下内容、再让用户挑保存位置 —— 反过来会先去算一遍内容（可能很大）。
    var pending by remember { mutableStateOf<PendingExport?>(null) }

    // 走系统的「创建文档」让用户自己选存到哪。不再用剪贴板:它装不下完整日志,也留不下文件。
    //
    // ⚠️ 位置选完之后真正落盘的那段**两个 launcher 共用**:MIME 只能在**构造时**定死,
    //    而我们要两种(`text/plain` 给文本、`application/zip` 给压缩包)——
    //    若只用一个通用 MIME,选文件时系统就不给对应的默认后缀,用户很容易存出一个
    //    打不开的名字。故注册两个,落盘逻辑只写一份。
    fun onPicked(uri: Uri?) {
        val payload = pending
        pending = null
        if (uri == null || payload == null) return
        val fileName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        XDiagExportNotifier.begin(context)
        appScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    writeExport(context, uri, payload, XDiagExportNotifier.progress(context))
                }.getOrDefault(false)
            }
            // 结果同时走通知与吐司:通知保证「离开了这一页也看得到」,
            // 吐司保证「通知权限被关掉时仍有反馈」。
            XDiagExportNotifier.finish(context, fileName, ok)
            toaster.show(
                message = context.getString(
                    if (ok) R.string.diagnostic_export_done else R.string.diagnostic_export_failed
                ),
                type = if (ok) ToastType.Success else ToastType.Error,
            )
        }
    }

    val saveText = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> onPicked(uri) }

    val saveZip = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> onPicked(uri) }

    /** 记下内容并弹出保存位置选择。带时间戳,多次导出不会互相覆盖。 */
    fun exportZip(prefix: String, payload: PendingExport) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        pending = payload
        saveZip.launch("rikkahub-x-" + prefix + "-" + stamp + ".zip")
    }

    /** 记下内容并弹出保存位置选择。带时间戳,多次导出不会互相覆盖。 */
    fun export(prefix: String, ext: String, payload: PendingExport) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        pending = payload
        saveText.launch("rikkahub-x-" + prefix + "-" + stamp + "." + ext)
    }

    // 捕获中时每秒刷新一次体积（见 tick 的注释）。enabled 变化时重启这个循环。
    LaunchedEffect(enabled) {
        while (true) {
            delay(1000)
            if (XLogcatCapture.isRunning()) tick += 1
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.diagnostic_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 开关 ──
            // ── 关键失败留存（与开关无关）──
            //
            // 放在最前：它意味着「某个功能已经不可用」，比任何计数都重要。
            // 实测 2026-09-11：建表失败发生在启动时，用户启动后才打开开关 ——
            // 缓冲里空无一物，现场丢失。留存区就是为了不再发生这件事。
            sticky?.let { failure ->
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.diagnostic_sticky_title)) },
                    ) {
                        item(
                            headlineContent = { Text(failure.message) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.diagnostic_sticky_event,
                                        failure.event,
                                        XLogRing.timeText(failure.at),
                                    )
                                )
                            },
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        val detail = failure.detail ?: failure.message
                                        export(
                                            "failure", "txt",
                                            PendingExport.Text(XRedaction.redact(detail, filesRoot)),
                                        )
                                    }
                                ) {
                                    Text(stringResource(R.string.diagnostic_sticky_export))
                                }
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.diagnostic_toggle_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.diagnostic_toggle_switch)) },
                        supportingContent = { Text(stringResource(R.string.diagnostic_toggle_desc)) },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    XDiagnostics.setEnabled(it)
                                    revision += 1
                                },
                            )
                        },
                    )
                }
            }

            // ── 应用日志捕获 ──
            //
            // 放在开关之后、概况之前:它回答的是「除了上面那些语义事件,应用本身还发生了什么」。
            // 这一层是 2026-09-12 之后才有的 —— 此前本页只看得到 X 自己的埋点。
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.diagnostic_capture_title)) },
                ) {
                    item(
                        headlineContent = {
                            Text(
                                stringResource(
                                    if (capture != null) R.string.diagnostic_capture_running
                                    else R.string.diagnostic_capture_stopped
                                )
                            )
                        },
                        supportingContent = {
                            Column {
                                // 体积与行数**同源**:两者都取当前会话。此前体积会退化到
                                // 读磁盘文件、而行数硬编码 0,于是出现「75.6 KB · 0 行」这种
                                // 自相矛盾 —— 未在记录时改为显示上一次留下的文件体积,并说清它
                                // 是「上次记录」(见下面的分支)。
                                val size = capture?.bytes ?: (captureFile?.length() ?: 0L)
                                val lines = capture?.lines
                                Text(
                                    when {
                                        // 在记录:两者都来自当前会话,必然自洽。
                                        lines != null -> stringResource(
                                            R.string.diagnostic_capture_size,
                                            XLogcatCapture.sizeText(size),
                                            lines,
                                        )
                                        // 未在记录但上次留下了文件:只说体积,并点明是「上次记录」。
                                        // 此前这里会显示硬编码的 0 行,与体积自相矛盾。
                                        size > 0L -> stringResource(
                                            R.string.diagnostic_capture_size_last,
                                            XLogcatCapture.sizeText(size),
                                        )
                                        // 未在记录且没有文件 —— 明说,而不是显示「0 B · 0 行」。
                                        else -> stringResource(R.string.diagnostic_capture_size_none)
                                    }
                                )
                                if (capture?.isCapped == true) {
                                    Text(
                                        text = stringResource(R.string.diagnostic_capture_capped),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
            }

            // ── 概况 ──
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.diagnostic_summary_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.diagnostic_summary_total)) },
                        supportingContent = {
                            Text(
                                if (total > 0) {
                                    stringResource(R.string.diagnostic_summary_total_value, total)
                                } else {
                                    stringResource(R.string.diagnostic_summary_empty)
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.diagnostic_summary_window)) },
                        supportingContent = {
                            Text(
                                // 起点由缓冲推导，故「有记录必有起点」；无记录时明说，不留空。
                                // 用与导出文本同一个格式化函数，两处写法不会漂移
                                if (windowStart != null) XLogRing.timeText(windowStart)
                                else stringResource(R.string.diagnostic_summary_window_none)
                            )
                        },
                    )
                }
            }

            // ── 各域条数 ──
            if (perDomain.isNotEmpty()) {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.diagnostic_domains_title)) },
                    ) {
                        perDomain.forEach { (domain, count) ->
                            item(
                                headlineContent = { Text(domain.label) },
                                supportingContent = { Text(domain.key) },
                                trailingContent = {
                                    Text(
                                        text = stringResource(R.string.diagnostic_domain_entries, count),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // ── 导出 ──
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.diagnostic_export_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.diagnostic_export_bundle)) },
                        supportingContent = { Text(stringResource(R.string.diagnostic_export_bundle_desc)) },
                        onClick = {
                            // 停止之后也允许导出 —— 「先关掉开关,再把刚录的那段导出来」是很自然的顺序。
                            val dir = XDiagSession.latest(context)
                            // 写成 if/else 而不是 `hasContent(dir)` + `dir!!`:后者那个 `!!`
                            // 是在向类型系统撒谎(内容非空不等于目录非 null),这里让它自然收窄。
                            if (dir == null || !XDiagZip.hasContent(dir)) {
                                toaster.show(message = context.getString(R.string.diagnostic_export_bundle_none))
                            } else {
                                exportZip("diag", PendingExport.SessionZip(dir))
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.diagnostic_export_redacted)) },
                        supportingContent = { Text(stringResource(R.string.diagnostic_export_redacted_desc)) },
                        onClick = {
                            val text = XDiagnostics.dumpMerged(full = false, filesRoot = filesRoot)
                            if (text == XDiagnostics.EMPTY_DUMP) {
                                toaster.show(message = context.getString(R.string.diagnostic_summary_empty))
                            } else {
                                export("diag", "txt", PendingExport.Text(text))
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.diagnostic_export_full)) },
                        supportingContent = { Text(stringResource(R.string.diagnostic_export_full_desc)) },
                        onClick = {
                            val text = XDiagnostics.dumpMerged(full = true, filesRoot = filesRoot)
                            if (text == XDiagnostics.EMPTY_DUMP) {
                                toaster.show(message = context.getString(R.string.diagnostic_summary_empty))
                            } else {
                                export("diag-full", "txt", PendingExport.Text(text))
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.diagnostic_export_logcat)) },
                        supportingContent = { Text(stringResource(R.string.diagnostic_export_logcat_desc)) },
                        onClick = {
                            // 停止之后也允许导出 —— 用户很自然会「先关掉开关,再把刚录的那段导出来」。
                            val file = XLogcatCapture.latestLogFile(context)
                            if (file == null) {
                                toaster.show(message = context.getString(R.string.diagnostic_export_logcat_none))
                            } else {
                                export("logcat", "log", PendingExport.LogcatFile(file))
                            }
                        },
                    )
                }
            }

            // ── 清空 ──
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = { Text(stringResource(R.string.diagnostic_clear)) },
                        supportingContent = { Text(stringResource(R.string.diagnostic_clear_desc)) },
                        onClick = { confirmClear = true },
                    )
                }
            }

            // ── 提示：命令行等价物（不装本页也能看） ──
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.diagnostic_cli_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = logcatHint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.diagnostic_clear_confirm_title)) },
            text = { Text(stringResource(R.string.diagnostic_clear_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        // 先刷一次:内存环已清,概况该立刻归零,不必等磁盘那步。
                        revision += 1
                        // 清空要删文件、并可能重起一轮记录 —— 都是阻塞操作,放 IO 线程。
                        // 只调 XDiagnostics.clearAll() 会漏掉磁盘上的文件(用户实测撞到过)。
                        scope.launch {
                            withContext(Dispatchers.IO) { XDiagClear.perform(context) }
                            // 再刷一次:此时磁盘已清、新一轮(若有)已开,状态卡才是最终态。
                            revision += 1
                        }
                        toaster.show(message = context.getString(R.string.diagnostic_cleared))
                    }
                ) {
                    Text(stringResource(R.string.diagnostic_clear_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.diagnostic_cancel))
                }
            },
        )
    }
}

/**
 * 待导出的内容。
 *
 * 分三种形态是必要的:诊断记录是一小段文本、会话目录是一堆文件、而**日志可能有几百 MB** ——
 * 后两者必须流式处理,不能先拼成一个字符串(那样内存直接爆掉)。
 */
private sealed interface PendingExport {
    /** 整次会话:全部文件打进一个压缩包(见 [XDiagZip])。 */
    data class SessionZip(val dir: File) : PendingExport

    /** 小段文本(诊断记录、失败详情)。 */
    data class Text(val text: String) : PendingExport

    /**
     * 应用日志文件。
     *
     * ⚠️ 原文件是**未脱敏**的(捕获时不做脱敏:那是热路径,而且文件本身在应用私有目录)。
     * 交出去之前必须逐行过一遍 [XLogScrub] —— 导出物的去向是聊天/AI,带出一个凭证就是泄漏。
     */
    data class LogcatFile(val file: File) : PendingExport
}

/**
 * 把待导出内容写进用户选定的位置。
 *
 * @return 是否真的写出了内容。`false` 意味着没能打开目标(极少见:权限或存储问题)。
 */
private fun writeExport(
    context: Context,
    uri: Uri,
    payload: PendingExport,
    progress: XExportProgress = NoExportProgress,
): Boolean = context.contentResolver.openOutputStream(uri)?.use { out ->
    when (payload) {
        // 打包与脱敏都在 XDiagZip 里,进度由它上报(读两遍:先扫、后写)。
        is PendingExport.SessionZip ->
            XDiagZip.write(XDiagEnv.appLines(context), out, payload.dir, progress)

        is PendingExport.Text -> {
            // ⚠️ 文本同样要过 [XLogScrub] —— 2026-09-12 复核导出路径时发现**三条文本导出
            //    一条都没过**(诊断记录 / 诊断记录完整版 / 失败详情),而它们的去向与压缩包
            //    一样是聊天/AI。文本虽小,里面带的 URL 查询串(`?key=`)与请求头同样是凭据形态。
            //
            // 放在这里而不是各个调用点:这是所有文本导出物的**唯一漏斗**;
            // 只在调用点加,就会出现「新加一条导出忘了加」。
            val body = XLogScrub.scrubBlock(payload.text)
            // 一小段文本,没有可分段的进度 —— 报一次头、一次尾即可(否则通知会一直停在 0%)。
            val total = body.length.toLong()
            progress.report(XExportPhase.WRITING, 0L, total)
            OutputStreamWriter(out, Charsets.UTF_8).use { it.write(body) }
            progress.report(XExportPhase.WRITING, total, total)
            true
        }

        is PendingExport.LogcatFile -> {
            // 逐行:读一行 → (脱敏) → 写一行。内存占用与文件大小无关,
            // 故几百 MB 的日志也能导出而不会 OOM。
            //
            // 逐行过 [XLogScrub] 把凭据形态的值掩掉。用 XLogScrub(而不是 XRedaction)是因为
            // logcat 是**任意文本**:后者面向 X 的事件文本(缩路径、掩哈希),防不了凭据泄漏。
            // ⚠️ 它只认凭据形态,抓不到聊天内容 —— 摘要里把这件事写清楚,别让人以为已安全。
            //
            // ⚠️ 先预扫一遍再写正文:摘要要落在**文件开头**(读者第一眼就该看到行数、
            //    以及脱敏到底开没开),而这两个数只有读完才知道 —— 单遍做不到。
            //    代价是两遍顺序读:实测单份日志是 KB 级可忽略。
            val scan = scanForExport(payload.file, progress)
            // 写入阶段重新从 0 计:通知上标着阶段名,两个阶段各走一遍 0→100% 才读得懂。
            val total = payload.file.length()
            var written = 0L
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { writer ->
                writeExportSummary(writer, scan)
                val source = countingStream(payload.file.inputStream()) { n ->
                    written += n
                    progress.report(XExportPhase.WRITING, written, total)
                }
                BufferedReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        writer.write(if (XLogScrub.ENABLED) XLogScrub.scrub(line) else line)
                        writer.newLine()
                    }
                }
            }
            true
        }
    }
} ?: false

/** 预扫结果:总行数、被脱敏器**实际改过**的行数。 */
private class ExportScan(val lines: Long, val scrubbed: Long)

/**
 * 预扫一遍日志,取「导出摘要」要的两个数。
 *
 * 判「这一行被脱敏过」用 `scrub(line) != line` —— 直接问脱敏器「你动它了吗」,
 * 而不是另写一套规则去猜哪些行"应该"被掩。两套判据迟早会漂移。
 */
private fun scanForExport(file: File, progress: XExportProgress = NoExportProgress): ExportScan {
    var lines = 0L
    var scrubbed = 0L
    var read = 0L
    val total = file.length()
    val source = countingStream(file.inputStream()) { n ->
        read += n
        progress.report(XExportPhase.ANALYSING, read, total)
    }
    BufferedReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            lines++
            // 关掉时不去调脱敏器:既省一遍正则,也让「命中 0」这个数**诚实**
            // (否则会算出"如果不关会命中多少",与文件实际情况不符)。
            if (XLogScrub.ENABLED && XLogScrub.scrub(line) != line) scrubbed++
        }
    }
    return ExportScan(lines, scrubbed)
}

/**
 * 导出摘要 —— 让「脱敏到底跑了没」**可被证伪**。
 *
 * 这是实测分析里点出的缺口之一:原先掩了凭据,但文件里一个字不说,读者无从判断。
 * 现在若这一行写着「命中 0 行」而正文里明显挂着 `Authorization:`,那就是脱敏没生效 ——
 * 一眼看得出来。**可被证伪比「静默地掩掉」有用得多。**
 *
 * 写「命中 N 行」还有一个副作用:能看出脱敏器是不是**过掩**了(命中数高得离谱)。
 *
 * 与文件里其它自产内容一致用英文:它们是日志元数据,读者是分析工具与 AI。
 */
private fun writeExportSummary(writer: BufferedWriter, scan: ExportScan) {
    writer.write("${XDiagEnv.MARK} export summary ${XDiagEnv.MARK}")
    writer.newLine()
    writer.write("exported  : ${XDiagEnv.stamp(System.currentTimeMillis())}")
    writer.newLine()
    writer.write(
        if (XLogScrub.ENABLED) {
            "redaction : XLogScrub applied to every line; ${scan.scrubbed} line(s) hit " +
                "(credentials in them were replaced with ${XLogScrub.MASK})"
        } else {
            // 如实写在文件开头:读者一眼就知道「这份是原样日志,可能带密钥」,不会误以为已脱敏。
            "redaction : DISABLED - this file is the raw log, it may contain credentials " +
                "such as API keys. Review it before sharing."
        }
    )
    writer.newLine()
    writer.write("lines     : ${scan.lines}")
    writer.newLine()
    // ⚠️ 此处**不能**写「以下为日志正文」:紧随其后的是文件自带的清单头,
    //    而清单头自己末尾才是那句「以下为日志正文」。两处都那么写会指错地方。
    // ⚠️ 先算成 val,不要写成 `"…" + if (c) A else B + "…"` —— Kotlin 把那个表达式解析为
    //    `if (c) A else (B + C)`,于是**真**分支会丢掉后面的尾巴。编译不报错,只在开关
    //    打开时才看得出来(实测踩到过一次)。
    val tail = if (XLogScrub.ENABLED) "redacted line by line" else "as-is"
    writer.write("${XDiagEnv.MARK} raw log file follows ($tail) ${XDiagEnv.MARK}")
    writer.newLine()
    writer.newLine()
}
