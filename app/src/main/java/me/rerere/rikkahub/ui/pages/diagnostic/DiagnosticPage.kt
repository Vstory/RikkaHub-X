package me.rerere.rikkahub.ui.pages.diagnostic

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import java.io.File
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
import me.rerere.rikkahub.x.diag.XEventFilter
import me.rerere.rikkahub.x.diag.XExportPhase
import me.rerere.rikkahub.x.diag.XExportProgress
import me.rerere.rikkahub.x.diag.XLogRing
import me.rerere.rikkahub.x.diag.XLogScrub
import me.rerere.rikkahub.x.diag.XLogcatCapture
import me.rerere.rikkahub.x.diag.XLogcatDump
import me.rerere.rikkahub.x.diag.XLogcatNoise
import me.rerere.rikkahub.x.diag.XSurvivorLog
import me.rerere.rikkahub.x.diag.XRedaction
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
 *
 * ## 为什么导出只有**一个**动作（2026-09-13 收敛）
 *
 * 此前这一区摆着四张卡：压缩包、诊断记录(已脱敏)、诊断记录(完整)、应用日志。它们其实是
 * **同一份内容的四种包装** —— 「应用日志」是压缩包里的一个文件，「诊断记录」两兄弟是同一段
 * 文本的两种路径处理。而其中两张的说明**还写着「不做脱敏」**，与实现正好相反（压缩包逐行
 * 过 [XLogScrub]）。卡片少一张不会让人少拿到东西，说明写反了却会让人误判风险。
 *
 * 现在只有「导出诊断包」：全部内容、已脱敏、包内清单说明每一处。要哪一份就在包里取。
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
    // 噪声过滤开关的状态也随 revision 重读:改了它之后那一行要立刻反映新值。
    val noiseFiltered = remember(revision) { XLogcatNoise.isEnabled() }
    val capture = remember(revision, tick) { XLogcatCapture.current() }
    // ⚠️ 未在捕获时要显示**全部片**的合计 —— 分片之后单片体积不是那个数(一次会话常有
    //    十几片),只取最后一片会少报。故用 latestSessionBytes 而不是 latestLogFile。
    val captureBytes = remember(revision, tick) { XLogcatCapture.latestSessionBytes(context) }
    // 存活层:只取体积(便宜),**不读内容** —— 那要走 IO,而这一页的刷新是每秒一次。
    // 它要回答的问题只有一个:「磁盘上到底有没有留存」。
    val survivorBytes = remember(revision, tick) { XSurvivorLog.file()?.takeIf { it.isFile }?.length() ?: 0L }

    val filesRoot = remember(context) { context.filesDir.absolutePath }

    // 待导出的内容。先记下内容、再让用户挑保存位置 —— 反过来会先去算一遍内容（可能很大）。
    var pending by remember { mutableStateOf<PendingExport?>(null) }

    // 导出入口「正在抓缓冲快照」的标志。抓快照要起一次 logcat 进程(几十到几百毫秒),
    // 期间重复点会抓两份、并弹两次保存位置。故挡住。
    var preparing by remember { mutableStateOf(false) }

    // 「事件」区的搜索与筛选。默认全空 = 看全部。
    var query by remember { mutableStateOf("") }
    var onlyDomain by remember { mutableStateOf<XDomain?>(null) }
    // 展开中的那一条(键见下面 items 的注释)。**只允许展开一条** —— 长文本同时展开几段
    // 就又要滚半天,而那与「一眼定位」的初衷相反。
    var expandedKey by remember { mutableStateOf<String?>(null) }

    // 事件列表的数据。与其它快照同一口径(随 revision/tick 重算)。
    // ⚠️ 筛选规则全在 [XEventFilter] 里(纯逻辑 + 单测):搜哪些字段、大小写、域与关键字
    //    的关系 —— 每一处判错了都只表现为「明明有却搜不到」,而那与「没记录」长得一样。
    val allRows = remember(revision, tick) { XEventFilter.buildRows { XDiagnostics.entries(it) } }
    val matchedRows = remember(allRows, query, onlyDomain) { XEventFilter.apply(allRows, query, onlyDomain) }
    val shownRows = remember(matchedRows) { matchedRows.take(XEventFilter.MAX_ROWS) }

    // 走系统的「创建文档」让用户自己选存到哪。不再用剪贴板:它装不下完整日志,也留不下文件。
    //
    // ⚠️ 位置选完之后真正落盘的那段**两个 launcher 共用**:MIME 只能在**构造时**定死,
    //    而我们要两种(`text/plain` 给文本、`application/zip` 给压缩包)——
    //    若只用一个通用 MIME,选文件时系统就不给对应的默认后缀,用户很容易存出一个
    //    打不开的名字。故注册两个,落盘逻辑只写一份。
    fun onPicked(uri: Uri?) {
        val payload = pending
        pending = null
        if (payload == null) return
        // ⚠️ **临时文件在每一条路径上都要删**:写成功、写失败、以及**用户在系统选位置时
        //    点了取消**(`uri == null`)。它们是这次导出动作的产物,不是诊断现场 ——
        //    现场在会话目录与存活层里,那些一个都不动。漏删的后果是一份几十 MB 的
        //    快照留在缓存目录里,而它对应的那次导出可能已经被取消了。
        val temps = (payload as? PendingExport.SessionZip)?.temps.orEmpty()
        fun cleanTemps() = temps.forEach { runCatching { it.delete() } }
        if (uri == null) {
            cleanTemps()
            return
        }
        val fileName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        XDiagExportNotifier.begin(context)
        appScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    writeExport(context, uri, payload, XDiagExportNotifier.progress(context))
                }.getOrDefault(false)
            }
            cleanTemps()
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
                                val size = capture?.bytes ?: captureBytes
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
                                if (capture?.hasRotated == true) {
                                    Text(
                                        text = stringResource(R.string.diagnostic_capture_capped),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                // 滤掉的行数**显示出来** —— 与「达阀丢了多少」同一个口径:
                                // 丢过东西就得说,否则用户会以为文件里就是全部。
                                val filtered = capture?.noiseFilteredLines ?: 0L
                                if (filtered > 0L) {
                                    Text(
                                        text = stringResource(
                                            R.string.diagnostic_capture_noise_dropped,
                                            filtered,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.diagnostic_survivors))
                        },
                        supportingContent = {
                            // ⚠️ 这一项**不是装饰**:存活层跨会话保留,而上面的「关键失败留存」
                            //    卡读的是**内存** —— 重启之后那张卡什么都不显示,文件里却可能
                            //    躺着一次崩溃。没有这一项,页面就会在那种时候撒谎。
                            Text(
                                if (survivorBytes > 0L) {
                                    stringResource(
                                        R.string.diagnostic_survivors_kept,
                                        XLogcatCapture.sizeText(survivorBytes),
                                    )
                                } else {
                                    stringResource(R.string.diagnostic_survivors_none)
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.diagnostic_capture_noise))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.diagnostic_capture_noise_desc))
                        },
                        trailingContent = {
                            // 与总开关分开一层:总开关管「记不记」,它管「记下来的要不要滤」。
                            // 互不依赖,故不复用 enabled。
                            Switch(
                                checked = noiseFiltered,
                                onCheckedChange = {
                                    XLogcatNoise.setEnabled(it)
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

            // ── 事件(可搜可筛)──
            //
            // 用户对这一页的原话是「**搜关键词就能定位**」—— 这一区就是那条路。
            // 在此之前,这一页只显示「各域多少条」,而**看不到任何一条内容**:
            // 想确认某个埋点到底有没有触发,只能先导出、再解压、再翻文件。
            //
            // ⚠️ 两处刻意的取舍(写在这里,免得被当成疏漏):
            //  · 只搜**最近一批**(每域 XEventFilter.PER_DOMAIN_TAKE 条、最多显示 MAX_ROWS 行)
            //    —— 列表不可能把几万条全渲染出来;
            //  · 而「要看全量」的正当去处是**导出的包**(一条不漏、还能交给 AI)。
            //    故下面那行计数**必须把「只在最近一批里搜」说出来**,否则用户搜不到早先
            //    那条时会以为日志漏了 —— 那正是这一类页面最不该造成的误解。
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.diagnostic_events_search)) },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = onlyDomain == null,
                            onClick = { onlyDomain = null },
                            label = { Text(stringResource(R.string.diagnostic_events_all)) },
                        )
                        perDomain.forEach { (domain, _) ->
                            FilterChip(
                                selected = onlyDomain == domain,
                                // 再点一下同一个 = 取消筛选(比再去找「全部」顺手)
                                onClick = { onlyDomain = if (onlyDomain == domain) null else domain },
                                label = { Text(domain.label) },
                            )
                        }
                    }
                    Text(
                        text = if (allRows.isEmpty()) {
                            stringResource(R.string.diagnostic_events_empty)
                        } else {
                            stringResource(
                                R.string.diagnostic_events_scope,
                                XEventFilter.PER_DOMAIN_TAKE,
                            ) + " · " + stringResource(
                                R.string.diagnostic_events_count,
                                shownRows.size,
                                matchedRows.size,
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            // ⚠️ 不用 `key = {...}`:两条事件完全可能**同一毫秒、同域、同事件名**
            //    (循环里连发就是这么回事),而 LazyColumn 的 key 必须唯一 —— 撞了会直接崩,
            //    且只在真出现重复那一刻才崩。用位置作键即可(列表本来就会整体重建)。
            items(shownRows) { row ->
                val key = row.domain.key + "|" + row.entry.at + "|" + row.entry.event
                val expanded = expandedKey == key
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        onClick = { expandedKey = if (expanded) null else key },
                        overlineContent = {
                            Text(row.entry.timeText() + " · " + row.domain.label)
                        },
                        headlineContent = {
                            Text(
                                text = row.entry.event,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = row.entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                // 长行折叠:默认两行,点一下展开整段。
                                // 「一条到底」在诊断页上很常见(路径、JSON 片段),
                                // 全展开会把列表拉得没法扫。
                                maxLines = if (expanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Text(
                                text = if (row.entry.level == XLogRing.Level.WARN) "W" else "I",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (row.entry.level == XLogRing.Level.WARN) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                    )
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
                            // ⚠️ **顺序是「先抓快照、再判定」**(2026-09-13 修,此前是个缺口)。
                            //
                            // 判据若看不到快照,「开关从未开过、也没崩溃过」时会报
                            // 「还没有记录」—— 而那一刻 logd 缓冲里其实躺着东西(含开关
                            // 打开之前的行),那恰恰是快照**唯一**能提供、别处都拿不到的部分。
                            // 换句话说:**这个按钮会在最需要它的那台设备上撒谎。**
                            //
                            // 代价是点一下到弹出保存位置之间多几十~几百毫秒(一次
                            // `logcat -b all -d`),期间用 `preparing` 挡住重复点击。
                            //
                            // ⚠️ 另一处**必须留意**的:判据走 `hasContent`(它自己接 `File?`),
                            //    **不要**改写成「dir == null 就先报没有记录」—— 那会把
                            //    「开关从未开过、但崩溃过」的那种包判成空,而它恰恰最该导出来。
                            if (!preparing) {
                                preparing = true
                                appScope.launch {
                                    // ⚠️ `preparing` 用 finally 复位:它若因为任何异常留在 true,
                                    //    这个按钮就**永久失灵**了,而界面上看不出任何异常(点了没
                                    //    反应)。这正是「静默失败比崩溃更贵」那一类 ——
                                    //    XLogcatDump.capture 自己吞掉了异常,但 `withContext`
                                    //    仍可能因取消而抛。
                                    try {
                                        // 停止之后也允许导出 —— 「先关掉开关,再把刚录的那段
                                        // 导出来」是很自然的顺序。
                                        //
                                        // 抓取走 IO:它要起进程、读管道。
                                        val dump = withContext(Dispatchers.IO) {
                                            XLogcatDump.capture(context.cacheDir)
                                        }
                                        val dir = XDiagSession.latest(context)
                                        // 存活层在**根目录**、独立于开关,同样要进判据与包。
                                        val extras = listOfNotNull(XSurvivorLog.file(), dump)
                                        if (XDiagZip.hasContent(dir, extras)) {
                                            // 快照作为**临时文件**随载荷传下去,由 onPicked
                                            // 在任何路径下删掉(见那里的注释)。
                                            exportZip(
                                                "diag",
                                                PendingExport.SessionZip(dir, extras, listOfNotNull(dump)),
                                            )
                                        } else {
                                            // 空包:刚才那份快照也要删 —— 留着就是一份没人要的文件。
                                            runCatching { dump?.delete() }
                                            toaster.show(
                                                message = context.getString(R.string.diagnostic_export_bundle_none),
                                            )
                                        }
                                    } finally {
                                        preparing = false
                                    }
                                }
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
    /**
     * 整次会话:全部文件打进一个压缩包(见 [XDiagZip])。
     *
     * @param dir 会话目录。**可以为 `null`** —— 「开关从未开过」时没有会话目录,
     *   而存活层仍可能有内容(崩溃过),那种情况必须照样能导出。
     * @param extra 会话目录之外的额外文件(存活层 `survivors.log`,在根目录)。
     */
    data class SessionZip(
        val dir: File?,
        val extra: List<File> = emptyList(),
        /**
         * 导出完成后要删掉的**临时文件**(导出那刻抓的缓冲快照)。
         *
         * ⚠️ 与 [extra] 分开是**有意的**:`extra` 是「要打进包的内容」,这里是
         * 「打完要清理什么」。此刻两者恰好指向同一个文件,但语义不同 —— 合成一个字段,
         * 将来给 `extra` 加一项(比如把某个现场文件也递进来)就会**连带把它删掉**。
         */
        val temps: List<File> = emptyList(),
    ) : PendingExport

    /**
     * 小段文本。**现在唯一的调用点是「关键失败留存」卡片里的「导出详情」** ——
     * 那是这一页上除了压缩包之外仅剩的导出动作,而且它不在「导出」区里(它是那一条
     * 失败记录的上下文动作,不是一份可选择的导出物)。
     *
     * 2026-09-13 之前它还被「导出诊断记录(已脱敏/完整)」两张卡用着,那两张已随
     * 「导出收敛成一个压缩包」移除。
     */
    data class Text(val text: String) : PendingExport
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
        // dir 为 null 不是错误(没有会话、只有存活层),故不在这里报失败 ——
        // XDiagZip 以「到底打进了什么」为准返回,空包才 false。
        // ⚠️ **分支体必须带花括号**:它不是单表达式(里面有一个 `val`)。省掉花括号是
        //    编译错,而报错信息会指向 `when` 整体(「must be exhaustive」「Add the 'is Text'
        //    branch」)—— 看起来完全不像是「少了一对括号」。
        //    (2026-09-13 实测踩到过一次;现在由 `check_x_when_braces.py` 机检 ——
        //     故这里不必靠人记住,写这句话只是给读到这一段的人一个提醒。)
        is PendingExport.SessionZip -> {
            // 快照**已经在入口处抓好并挂在这个载荷上了**(见导出卡片那段注释),
            // 这里只管写、不再自己抓一次:再抓一次会得到**第二份**、而且时机晚于
            // 用户选保存位置 —— 「导出那一刻」就不再是同一个时刻了。
            // 时间跳变那句话挂在**头部行**里(而不是新加一个参数):头部行本来就是
            // 「关于这次记录的元信息」,而跳变正是这样一种信息 —— 且这样 XDiagZip 仍是
            // 纯逻辑(它不碰 XDiagnostics)。
            val header = XDiagEnv.appLines(context) + listOfNotNull(XDiagnostics.clockNote())
            XDiagZip.write(header, out, payload.dir, progress, payload.extra)
        }

        is PendingExport.Text -> {
            // ⚠️ 文本同样要过 [XLogScrub] —— 2026-09-12 复核导出路径时发现**三条文本导出
            //    一条都没过**(诊断记录 / 诊断记录完整版 / 失败详情),而它们的去向与压缩包
            //    一样是聊天/AI。文本虽小,里面带的 URL 查询串(`?key=`)与请求头同样是凭据形态。
            //    (那三条里前两条 2026-09-13 已随导出收敛移除,现在只剩失败详情走这条路 ——
            //     但**这个漏斗不能撤**:撤了就回到「新加一条导出忘了加脱敏」。)
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
    }
} ?: false
