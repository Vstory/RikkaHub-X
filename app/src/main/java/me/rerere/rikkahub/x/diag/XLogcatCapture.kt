// [X-custom] RikkaHub-X 诊断框架：本应用 logcat 的持续捕获
package me.rerere.rikkahub.x.diag

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 本应用 logcat 的**持续捕获** → 落盘。
 *
 * ## 它改变的是诊断框架的方向
 *
 * 此前这套框架只记 **X 定制自己的语义事件**(那 11 个域)—— 页面上甚至写着
 * 「本页看『X 做了什么』，上游页看『系统发生了什么』」。**那个划分是错的**：
 * 真机实测(2026-09-12，构建 `4db822b7`)证明**零权限就能读到本 UID 的全部日志**，
 * 也就是说**整个应用做过什么，本来就能拿到**，不必只盯着 X 改动的那几十处。
 *
 * 于是职责变成:
 *
 * | | 来源 | 覆盖 |
 * |---|---|---|
 * | **广度** | 本模块(logcat) | 上游 206 处 `Log.*` + 框架日志(OkHttp/Room/Coil/Compose) + 本进程内的 OEM 框架日志 + 崩溃栈 |
 * | **深度** | [XDiagnostics] 的语义事件 | logcat 表达不出来的:去重命中数、字节数、阶段耗时、关键失败留存 |
 *
 * 两者合并才是「这个应用刚才发生了什么」，而不是只有「X 做了什么」。
 *
 * ## 为什么不按 tag 过滤
 *
 * 只看 `XLogRing.TAG` 就等于没做这件事。本模块**收全量**(logcat 侧已由系统按 UID 过滤，
 * 不会拿到别的应用)，宁可文件大，也不要「查的时候发现关键那行没被记」。
 * 体积由 [DEFAULT_MAX_BYTES] 安全阀兜底，且**到顶有标记**。
 *
 * ## 落盘位置与生命周期
 *
 * 每次「开关关 → 开」= 一个新会话目录,日志落在其中的 `logcat.log`。
 * 目录由 [XDiagSession] **唯一**创建;同目录下还有请求记录与各域的语义事件文件
 * (文件名即域名)。关闭开关 = 结束本次会话,**文件保留、不随进程退出而删除** ——
 *
 * ## 与 [XLog] 的关系
 *
 * 不冲突:[XLog.info] 在开关关闭时连 logcat 都不写(「关掉诊断 = 对 logcat 零贡献」)，
 * 而本模块只在开关**打开**时运行。即两边一致 —— 关掉诊断，logcat 与文件都干净。
 */
object XLogcatCapture {

    private const val LOGCAT = "/system/bin/logcat"

    // 会话根目录与目录名前缀住在 XDiagSession —— 目录由它唯一创建(见该类注释)。
    private const val LOG_NAME = "logcat.log"

    /** 攒够这么多字节就 flush 一次;另有 [FLUSH_INTERVAL_MS] 兜住低速时段。 */
    private const val FLUSH_BYTES = 64 * 1024
    private const val FLUSH_INTERVAL_MS = 500L

    /**
     * 体积安全阀(计划 §4.2 的建议值)。
     *
     * 到顶**不是静默丢弃**:写一条醒目的警示行、把 `capped` 置位、并继续统计后续行数，
     * 让「这次日志不完整」在导出物里**看得见**。宁可有标记地停，也不静默地丢。
     */
    const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024

    private val lock = Any()

    /** [install] 的幂等标志。 */
    @Volatile
    private var installed = false

    /** 当前会话;`null` = 未在捕获。 */
    @Volatile
    private var session: Session? = null

    /** 一次捕获会话。计数用原子量，诊断页可随时读取而不必加锁。 */
    class Session internal constructor(
        /** 本次会话目录。 */
        val dir: File,
        /** 日志文件。 */
        val logFile: File,
        /** 开始时刻(毫秒)。 */
        val startedAt: Long,
        /** 体积安全阀(字节)。 */
        val maxBytes: Long,
    ) {
        internal val stopRequested = AtomicBoolean(false)
        internal val written = AtomicLong(0L)
        internal val linesWritten = AtomicLong(0L)
        internal val linesAfterCap = AtomicLong(0L)
        internal val capped = AtomicBoolean(false)


        /** 已写入字节数。 */
        val bytes: Long get() = written.get()

        /** 已写入行数。 */
        val lines: Long get() = linesWritten.get()

        /** 是否已达安全阀。 */
        val isCapped: Boolean get() = capped.get()

        /** 达阀之后**未被记录**的行数(仍然读走并按丢弃计数 —— 见 [drain] 的注释)。 */
        val droppedAfterCap: Long get() = linesAfterCap.get()
    }

    /**
     * 接进诊断开关。**在 `Application.onCreate` 尽早调用**(越早越好:
     * 启动期正是最需要取证的一段)。可重复调用(幂等)。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        // 幂等:重复调用不会重复注册(否则开关翻一次会起两个会话)。
        if (installed) return
        installed = true
        XDiagnostics.addEnabledListener { enabled ->
            if (enabled) start(app) else stop()
        }
        // 开关在启动前就是「开」的(落盘读回来的)→ 立即开始。
        // 这一段就是「开关必须活过启动期」那件事的兑现点:XDiagnostics 的
        // attachPersistence 给出初值,这里据此接管 —— 否则启动期的日志永远拿不到。
        if (XDiagnostics.isEnabled()) start(app)
    }

    /** 当前会话;`null` = 未在捕获。 */
    fun current(): Session? = session

    /** 是否正在捕获。 */
    fun isRunning(): Boolean = session != null

    /**
     * 最近一次会话的日志文件 —— **即使当前未在捕获**。
     *
     * 停止之后仍然要能导出:用户很自然会「先关掉开关，再把刚录的那段导出来」,
     * 若只在运行中可取,那段现场等于白录。会话目录名带时间戳,故按名字取最大即最近一次。
     */
    fun latestLogFile(context: Context): File? {
        current()?.let { return it.logFile }
        return XDiagSession.latest(context)
            ?.let { File(it, LOG_NAME) }
            ?.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * 开始捕获。已在捕获则原样返回当前会话(幂等 —— 重复开不会产生两个 logcat 进程)。
     */
    fun start(context: Context): Session? = synchronized(lock) {
        session?.let { return it }

        // 目录由 XDiagSession **唯一**创建:三个写入者(logcat / 请求记录 / 域文件)必须
        // 落进同一个目录,各自造会因「跨没跨过一秒」分裂成两个 —— 那种分裂很隐蔽。
        // 故这里**只取不造**;取不到就明确失败并留存,而不是自己另造一个目录。
        val dir = XDiagSession.current()
        if (dir == null) {
            XDiagnostics.recordStickyFailure(
                domain = XDomain.CORE,
                event = CAPTURE_FAIL_EVENT,
                message = "诊断会话目录未建立,本次不记录应用日志(接线顺序有误)",
            )
            return null
        }

        val logFile = File(dir, LOG_NAME)
        val s = Session(
            dir = dir,
            logFile = logFile,
            startedAt = System.currentTimeMillis(),
            maxBytes = DEFAULT_MAX_BYTES,
        )

        val proc = try {
            ProcessBuilder(listOf(LOGCAT, "-v", "threadtime")).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            XDiagnostics.recordStickyFailure(
                domain = XDomain.CORE,
                event = CAPTURE_FAIL_EVENT,
                message = "无法启动 logcat 进程，本次不记录应用日志:" + e,
                error = e,
            )
            return null
        }

        session = s
        // applicationContext 交给捕获线程:清单头要取应用名,而那是 PackageManager 查询,
        // 不该压在 Application.onCreate 的主线程上(这条路径的目标是「启动尽量别变慢」)。
        val app = context.applicationContext
        Thread({ runSession(s, proc, app) }, "x-logcat-capture").apply { isDaemon = true }.start()
        return s
    }

    /** 结束当前捕获(文件保留)。未在捕获则无操作。 */
    fun stop() {
        val s = synchronized(lock) { session } ?: return
        s.stopRequested.set(true)
        // 由读取线程负责收尾(它拿到 IOException 后 flush/close/清空 session);
        // 这里不 kill 进程 —— 读取端会通过 destroyForcibly 唤醒，见 runSession。
    }

    /** 清空全部会话目录(用户点「清空」时一并调用)。 */
    fun clearSessions(context: Context) {
        stop()
        XDiagSession.clearAll(context)
    }

    /** 捕获失败的事件名(三段点分隔,由 check_x_event_names.py 机检)。 */
    const val CAPTURE_FAIL_EVENT = "diag.capture.fail"

    /** 达到安全阀时写进日志文件的那一行 —— 让人一眼看到「后面没了」。 */
    internal const val CAP_MARKER =
        "!!! [x-diag] size cap reached, later log lines were not recorded (file ends here)."

    // ────────────────────────────────────

    private fun runSession(s: Session, proc: Process, context: Context) {
        try {
            FileOutputStream(s.logFile, /* append = */ true).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8), FLUSH_BYTES).use { out ->

                    // 看门狗:到点或收到停止请求就结束进程。
                    // ⚠️ 用 waitFor 而不是 sleep:进程自己退出时立即返回,不留空等线程。
                    Thread({
                        runCatching {
                            val deadline = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(6)
                            while (System.currentTimeMillis() < deadline) {
                                if (proc.waitFor(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)) return@runCatching
                                if (s.stopRequested.get()) break
                            }
                        }
                        runCatching { proc.destroyForcibly() }
                    }, "x-logcat-capture-watchdog").apply { isDaemon = true }.start()

                    // 头**先写**:它要在任何一行日志之前落盘 —— 这样即使进程随后被强杀,
                    // 「这是什么、从哪开始」也还在。反过来(导出时补)就丢掉了当时的设备/版本。
                    writeCaptureHeader(s, out, context)
                    try {
                        drain(s, proc, out)
                    } finally {
                        // 结束标记放在 finally:捕获失败(异常)时也要留下「到此为止」的痕迹,
                        // 否则读者会以为这份是正常收尾。
                        writeCaptureEnd(s, out)
                        runCatching { out.flush() }
                    }
                }
            }
        } catch (e: Throwable) {
            XDiagnostics.recordStickyFailure(
                domain = XDomain.CORE,
                event = CAPTURE_FAIL_EVENT,
                message = "日志捕获中断，文件可能不完整:" + e,
                error = e,
            )
        } finally {
            runCatching { proc.destroyForcibly() }
            synchronized(lock) { if (session === s) session = null }
        }
    }

    /**
     * 读尽 logcat 的 stdout 并落盘。
     *
     * ⚠️ 两个**真机上踩到过**的坑写在这里:
     *
     * **① 停止时抛的是 IO 异常，不是 EOF。**
     * `Process.destroyForcibly()` 会一并关掉该进程的 stdout 流，阻塞在 `readLine()` 上的线程
     * 由此**抛 IOException**。若照「会拿到 EOF」的直觉去写，异常会穿透出去 → 整个会话被判失败
     * → **已落盘的数据被当成没发生**(2026-09-12 流读探针就是这么丢掉全部数据的)。
     * 故:已请求停止时，把 IO 异常当作**正常的结束信号**。
     *
     * **② 达阀之后仍然必须继续读。**
     * 停止读取会让管道灌满、反向把 logcat 堵住(它写不进就会卡)。故达阀后**继续读走并计数**，
     * 只是不再写入文件 —— 于是「丢了多少行」是可见的。
     */
    private fun drain(s: Session, proc: Process, out: BufferedWriter) {
        BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
            var sinceFlush = 0L
            var lastFlush = System.currentTimeMillis()

            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: IOException) {
                    // 见注释 ①:是我们自己叫停 → 当作读完；否则是真故障，外抛。
                    if (!s.stopRequested.get()) throw e
                    null
                }
                if (line == null) break

                val n = line.length + 1L

                if (s.capped.get()) {
                    s.linesAfterCap.incrementAndGet()
                } else if (s.written.get() + n > s.maxBytes) {
                    // 见注释 ②:先落标记，再转为「只读不写」。
                    s.capped.set(true)
                    runCatching {
                        out.write(CAP_MARKER)
                        out.newLine()
                        out.flush()
                    }
                } else {
                    out.write(line)
                    out.newLine()
                    s.written.addAndGet(n)
                    s.linesWritten.incrementAndGet()
                    sinceFlush += n
                }

                val now = System.currentTimeMillis()
                if (sinceFlush >= FLUSH_BYTES || now - lastFlush >= FLUSH_INTERVAL_MS) {
                    // 批量 flush:降 I/O 次数。失败不致命(下一次或关闭时还会再 flush)。
                    runCatching { out.flush() }
                    sinceFlush = 0L
                    lastFlush = now
                }
            }
        }
    }

    /**
     * 清单头:这次捕获的**自述**。
     *
     * ## 它解决什么
     *
     * 真机实测(2026-09-12)里,一份 1232 行的日志**没有任何自我描述** —— 读者只能猜版本、设备,
     * 更关键的是**猜不出「这份是不是完整的」**。而那份日志里还出现了**两个 PID**,不知情者会
     * 以为串了别的应用,实际是「本应用重启前后」。
     *
     * ## 为什么写在文件头而不是导出时补
     *
     * 导出可能发生在另一次运行里(甚至另一个进程) —— 那时再取设备/版本,拿到的是**当时的**,
     * 不是产生这份日志时的。写在头里则与内容同源同时。
     */
    private fun writeCaptureHeader(s: Session, out: BufferedWriter, context: Context) {
        val lines = buildList {
            add("${XDiagEnv.MARK} capture info ${XDiagEnv.MARK}")
            addAll(XDiagEnv.appLines(context))
            add("started : ${XDiagEnv.stamp(s.startedAt)}")
            // 措辞刻意避开「本文件…」:这一行会**跟着文件被导出**,而导出时已逐行脱敏 ——
            // 若写「本文件未脱敏」,它在导出物里就成了假话。改说「原始文件本身不脱敏」,
            // 那是关于原始文件的陈述,在两种载体里都成立。
            add(
                "redaction: " + if (XLogScrub.ENABLED) {
                    "applied line by line on in-app export"
                } else {
                    "DISABLED - exports are raw and may carry credentials such as API keys"
                }
            )
            add("size cap: ${sizeText(s.maxBytes)}")
            add("note    : written by the app itself from its own logcat, so it holds only this")
            add("          app (plus its in-process framework logs), never other apps. Lines")
            add("          before 'started' may come from the logd ring buffer, including the")
            add("          previous run - read in time order.")
            add("${XDiagEnv.MARK} log lines follow ${XDiagEnv.MARK}")
        }
        lines.forEach { out.write(it); out.newLine() }
        // 立刻落盘:头若留在缓冲里而进程马上被杀,就等于没写。
        runCatching { out.flush() }
    }

    /**
     * 结束标记:让「这份日志到哪为止」**自证**。
     *
     * 与 [CAP_MARKER] 的分工:那条是**达上限那一刻**写在正文里的,这条是**收尾时**写在末尾的。
     * 前者回答「后面为什么没了」,后者回答「这份什么时候结束的、有没有丢行」。
     */
    private fun writeCaptureEnd(s: Session, out: BufferedWriter) {
        val now = System.currentTimeMillis()
        val seconds = (now - s.startedAt) / 1000.0
        runCatching {
            out.newLine()
            out.write(
                "${XDiagEnv.MARK} capture ended ${XDiagEnv.stamp(now)} · lasted " +
                    "${XDiagEnv.durationText(seconds)} · ${s.lines} lines / ${sizeText(s.bytes)} " +
                    "${XDiagEnv.MARK}"
            )
            out.newLine()
            // ⚠️ 先算成 val:`"…" + if (c) A else B + "…"` 会被解析成 `if (c) A else (B + C)`,
            //    于是**真**分支丢掉尾巴标记。编译不报错,只在「已达上限」时才看得出来 ——
            //    而那条路径平时根本不走,属于最难发现的一类。
            val capText = if (s.isCapped) {
                "reached, ${s.droppedAfterCap} line(s) beyond it were not recorded " +
                    "(so the line count is incomplete)"
            } else {
                "not reached (nothing dropped)"
            }
            out.write("${XDiagEnv.MARK} size cap: $capText ${XDiagEnv.MARK}")
            out.newLine()
        }
    }

    /** 供诊断页显示:人类可读的体积。 */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
