package me.rerere.rikkahub.ui.pages.diagnostic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.x.diag.XDiagnostics
import me.rerere.rikkahub.x.diag.XDomain
import me.rerere.rikkahub.x.diag.XLogRing
import me.rerere.rikkahub.x.diag.XLogcatProbe

/**
 * X 定制诊断页。
 *
 * ## 它解决什么问题
 *
 * X 的功能都在上游代码的夹缝里（存储层、压缩、容量表、同步…），出问题时**没有办法在现场取证**：
 * 用户能拿到的只有「不好使」三个字。本页把 X 自己的运行记录摊开，让用户当场看到
 * 「账本记了多少条、去重命中几次、哪一步失败了」，并能一键复制出来。
 *
 * ## 为什么和上游的日志页不合并
 *
 * 上游那个页读的是 logcat，是**全量**系统日志，几千行里翻 X 的几行不现实；
 * 而这里只装 X 自己的事件，且**关掉开关时一条不记**（见 [XDiagnostics] 的开关语义）。
 * 两者互补而非重复：本页看「X 做了什么」，上游页看「系统发生了什么」。
 *
 * ## 数据从哪来
 *
 * 直接读 [XDiagnostics] —— 它是进程内单例，不是 Flow。只有本页会改它（开关、清空），
 * 故用一个本地版本号触发重读即可，不必为它引入状态流（那会让每次埋点都走一次 Flow 派发）。
 */
@Composable
fun DiagnosticPage() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 本地版本号：开关与清空之后 +1，以下所有快照随之重算
    var revision by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    // ⚠️ 临时（logcat 自读验证 spike）—— 验完与本文件末尾那张卡片、
    // 以及 XLogcatProbe.kt 一起删除。
    val probeScope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var probeSummary by remember { mutableStateOf<String?>(null) }

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

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        toaster.show(message = context.getString(R.string.diagnostic_copied), type = ToastType.Success)
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
                                TextButton(onClick = { copy(failure.detail ?: failure.message) }) {
                                    Text(stringResource(R.string.diagnostic_sticky_copy))
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
                        headlineContent = { Text(stringResource(R.string.diagnostic_copy_redacted)) },
                        supportingContent = { Text(stringResource(R.string.diagnostic_copy_redacted_desc)) },
                        onClick = {
                            val text = XDiagnostics.dumpMerged(
                                full = false,
                                filesRoot = context.filesDir.absolutePath,
                            )
                            if (text == XDiagnostics.EMPTY_DUMP) {
                                toaster.show(message = context.getString(R.string.diagnostic_summary_empty))
                            } else {
                                copy(text)
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.diagnostic_copy_full)) },
                        supportingContent = { Text(stringResource(R.string.diagnostic_copy_full_desc)) },
                        onClick = {
                            val text = XDiagnostics.dumpMerged(
                                full = true,
                                filesRoot = context.filesDir.absolutePath,
                            )
                            if (text == XDiagnostics.EMPTY_DUMP) {
                                toaster.show(message = context.getString(R.string.diagnostic_summary_empty))
                            } else {
                                copy(text)
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.diagnostic_copy_logcat)) },
                        supportingContent = { Text(stringResource(R.string.diagnostic_copy_logcat_desc)) },
                        onClick = { copy(logcatHint) },
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

            // ⚠️ 临时：logcat 自读验证（spike）。见 XLogcatProbe 的类注释。
            // 它回答的是「诊断框架能否靠 logcat 白捡上游与框架日志」，
            // 从而决定后面是「插桩为主」还是「捕获为主」。验完整块删除。
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("⚠️ 临时探针（验完删除）") },
                ) {
                    item(
                        headlineContent = {
                            Text(if (probing) "正在读取 logcat…" else "验证 logcat 自读")
                        },
                        supportingContent = {
                            Text(
                                probeSummary
                                    ?: "读取本进程的 logcat（一次性 dump + 崩溃缓冲 + 限时流读），结果复制到剪贴板"
                            )
                        },
                        onClick = {
                            if (!probing) {
                                probing = true
                                probeScope.launch {
                                    val declared = declaresReadLogs(context)
                                    val report = withContext(Dispatchers.IO) {
                                        XLogcatProbe.run(declared).report()
                                    }
                                    probeSummary = report.lineSequence().firstOrNull {
                                        it.startsWith("✅") || it.startsWith("❌") || it.startsWith("⚠️")
                                    }
                                    copy(report)
                                    probing = false
                                }
                            }
                        },
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
 * ⚠️ 临时（随探针一起删除）：Manifest 里是否声明了 `READ_LOGS`。
 *
 * 报告里要如实体现代码的声明状态：本次验证的正是「不声明也能读自己 UID 的日志」，
 * 若哪天有人误加了该权限声明，结论会变得不可解释 —— 所以这一项必须打出来。
 */
@Suppress("DEPRECATION")
private fun declaresReadLogs(context: Context): Boolean = runCatching {
    context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        ?.contains(Manifest.permission.READ_LOGS) == true
}.getOrDefault(false)
