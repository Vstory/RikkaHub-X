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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.x.diag.XDiagEnv
import me.rerere.rikkahub.x.diag.XDiagnostics
import me.rerere.rikkahub.x.diag.XDomain
import me.rerere.rikkahub.x.diag.XLogRing
import me.rerere.rikkahub.x.diag.XLogScrub
import me.rerere.rikkahub.x.diag.XLogcatCapture
import me.rerere.rikkahub.x.diag.XRedaction

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
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val payload = pending
        pending = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { writeExport(context, uri, payload) }.getOrDefault(false)
            }
            toaster.show(
                message = context.getString(
                    if (ok) R.string.diagnostic_export_done else R.string.diagnostic_export_failed
                ),
                type = if (ok) ToastType.Success else ToastType.Error,
            )
        }
    }

    /** 记下内容并弹出保存位置选择。带时间戳,多次导出不会互相覆盖。 */
    fun export(prefix: String, ext: String, payload: PendingExport) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        pending = payload
        saveLauncher.launch("rikkahub-x-" + prefix + "-" + stamp + "." + ext)
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
                                val size = capture?.bytes ?: (captureFile?.length() ?: 0L)
                                val lines = capture?.lines ?: 0L
                                Text(
                                    stringResource(
                                        R.string.diagnostic_capture_size,
                                        XLogcatCapture.sizeText(size),
                                        lines,
                                    )
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
                        XDiagnostics.clearAll()
                        confirmClear = false
                        revision += 1
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
 * 分两种形态是必要的:诊断记录是一小段文本,而**应用日志可能有几百 MB** ——
 * 后者必须流式读文件、逐行处理,不能先拼成一个字符串(那样内存直接爆掉)。
 */
private sealed interface PendingExport {
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
): Boolean = context.contentResolver.openOutputStream(uri)?.use { out ->
    when (payload) {
        is PendingExport.Text -> {
            OutputStreamWriter(out, Charsets.UTF_8).use { it.write(payload.text) }
            true
        }

        is PendingExport.LogcatFile -> {
            // 逐行:读一行 → 脱敏一行 → 写一行。内存占用与文件大小无关,
            // 故几百 MB 的日志也能导出而不会 OOM。
            //
            // 脱敏用 XLogScrub(正则换掉凭据)、不用 XRedaction:后者是为 X 的事件文本写的
            // (缩路径、掩哈希),而 logcat 是任意文本,要防的是凭证泄漏。见 XLogScrub 的类注释。
            //
            // ⚠️ 先预扫一遍再写正文:摘要要落在**文件开头**(读者第一眼就该看到「脱敏跑了没、
            //    跑了多少」),而命中数只有读完全文才知道 —— 单遍做不到这件事。
            //    代价是两遍顺序读:实测单份日志是 KB 级可忽略;即便 200MB 的极端情况,
            //    也多不过「让人对着文件猜脱敏有没有生效」的代价。
            val scan = scanForExport(payload.file)
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { writer ->
                writeExportSummary(writer, scan)
                BufferedReader(InputStreamReader(payload.file.inputStream(), Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        writer.write(XLogScrub.scrub(line))
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
private fun scanForExport(file: File): ExportScan {
    var lines = 0L
    var scrubbed = 0L
    BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            lines++
            if (XLogScrub.scrub(line) != line) scrubbed++
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
 */
private fun writeExportSummary(writer: BufferedWriter, scan: ExportScan) {
    writer.write("${XDiagEnv.MARK} 导出摘要 ${XDiagEnv.MARK}")
    writer.newLine()
    writer.write("导出时间: ${XDiagEnv.stamp(System.currentTimeMillis())}")
    writer.newLine()
    writer.write(
        "脱敏    : 已逐行应用 XLogScrub;命中 ${scan.scrubbed} 行" +
            "(这些行里含被替换成 ${XLogScrub.MASK} 的凭据)"
    )
    writer.newLine()
    writer.write("总行数  : ${scan.lines}")
    writer.newLine()
    // ⚠️ 此处**不能**写「以下为日志正文」:紧随其后的是文件自带的清单头,
    //    而清单头自己末尾才是那句「以下为日志正文」。两处都那么写会指错地方。
    writer.write("${XDiagEnv.MARK} 以下为日志文件原文(已逐行脱敏) ${XDiagEnv.MARK}")
    writer.newLine()
    writer.newLine()
}
