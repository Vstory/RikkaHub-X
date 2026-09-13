// [X-custom] RikkaHub-X 诊断框架：本应用 logcat 的持续捕获
package me.rerere.rikkahub.x.diag

import android.content.Context
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicInteger
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
 * 不会拿到别的应用)，宁可文件大，也不要「查的时候关键那行没被记」。
 *
 * ## ⚠️ 收全量,但**分片轮转**(2026-09-13 改)
 *
 * 原先是**一个** 200MB 的文件,到顶写一条警示就停 —— 两处都不对:
 *
 * ① **停掉之后丢的是最新那一段**。而日志的用法是「刚出问题,去看刚才发生了什么」,
 *    那种情形下最该看的恰恰是刚发生的行,却被丢了。方向反了。
 * ② **单文件 200MB 对「交给 AI 读」极不友好** —— 要它看一段,得先吞下 200MB。
 *
 * 现在是分片 + LRU:每片 [DEFAULT_PART_BYTES],保留最近 [DEFAULT_KEEP_PARTS] 片
 * (命名与保留策略见 [XLogcatParts])。磁盘上限是两者相乘,**比原来更小**,
 * 而**最新的那一片一定在**。旧片自然老去,不再有「停写」这件事。
 *
 * 代价(诚实记下):**久远的行会被轮转掉**。定位问题时要记得「只保留了最近这一窗」——
 * 会话结束标记与包内清单都会写明这一窗有多大、丢了几片。
 *
 * ## 落盘位置与生命周期
 *
 * 每次「开关关 → 开」= 一个新会话目录,日志落在其中的 `logcat_<n>.log` 若干片。
 *
 * ## 噪声过滤(2026-09-13 加)
 *
 * 一份真实捕获里约**三分之二**的行是 ColorOS 渲染/窗口/输入法流水(实测数据见
 * [XLogcatNoise] 的类注释)。那些行对诊断 App 行为零贡献,却会挤掉体积额度、拖慢读取、
 * 把要看的行埋掉。故写入前过一道 [XLogcatNoise.isNoise] —— **只丢认得出的 tag,
 * 认不出的一律保留**(过滤失败的代价必须是「日志变多」)。丢了多少行会在结束标记里写明,不静默丢。
 * 目录由 [XDiagSession] **唯一**创建;同目录下还有事件时间线 `events.log`
 * (语义事件与网络元数据合在一份,按发生顺序)。关闭开关 = 结束本次会话,
 * **文件保留、不随进程退出而删除** ——
 *
 * ## 与 [XLog] 的关系
 *
 * 不冲突:[XLog.info] 在开关关闭时连 logcat 都不写(「关掉诊断 = 对 logcat 零贡献」)，
 * 而本模块只在开关**打开**时运行。即两边一致 —— 关掉诊断，logcat 与文件都干净。
 */
object XLogcatCapture {

    private const val LOGCAT = "/system/bin/logcat"

    // 会话根目录与目录名前缀住在 XDiagSession —— 目录由它唯一创建(见该类注释)。

    /**
     * 单片的体积上限。
     *
     * 8MB 是有意的:它**小于**多数 AI 上下文能舒服吞下的量,故「交给它看这一段」
     * 是现实的;而一次会话有 12 片(见下条),合计 96MB —— 比原来的单文件 200MB 还小。
     */
    const val DEFAULT_PART_BYTES = 8L * 1024 * 1024

    /**
     * 保留最近几片(LRU)。
     *
     * 到顶**不是静默丢弃**:被轮转掉多少片会写进会话结束标记与包内清单,
     * 让「这份日志只覆盖最近这一窗」在导出物里**看得见**。
     */
    const val DEFAULT_KEEP_PARTS = 12

    /** 攒够这么多字节就 flush 一次;另有 [FLUSH_INTERVAL_MS] 兜住低速时段。 */
    private const val FLUSH_BYTES = 64 * 1024
    private const val FLUSH_INTERVAL_MS = 500L

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
        /** 开始时刻(毫秒)。 */
        val startedAt: Long,
        /** 单片体积上限(字节)。 */
        val partBytes: Long,
        /** 保留片数。 */
        val keepParts: Int,
        /**
         * 本次捕获是否滤掉已知的 OEM/渲染噪声。
         *
         * **在会话开始时定下**,中途改开关只影响之后的会话 —— 否则一份文件里会
         * 「前段滤了、后段没滤」而清单头说不清,读的人无从判断看到的是哪种。
         */
        val noiseFiltered: Boolean,
    ) {
        internal val stopRequested = AtomicBoolean(false)
        internal val written = AtomicLong(0L)
        internal val linesWritten = AtomicLong(0L)

        /** 被轮转掉的片数。让它可见 —— 「只保留了最近这一窗」必须说得出来。 */
        internal val partsDroppedNow = AtomicInteger(0)

        /** 是否已经轮转过(即已经有旧片被删)。诊断页据此给一句提示。 */
        internal val rotated = AtomicBoolean(false)

        /**
         * 轮转是否已经失败并放弃。
         *
         * `@Volatile` 且**只由 drain 线程写**:失败之后不再尝试轮转,免得每一行都
         * 去 close/open 一次文件(那会把 I/O 拖垮,而问题本来就已经是 I/O)。
         */
        @Volatile
        internal var rotationBlocked: Boolean = false

        /** 被噪声过滤丢掉的**行数**(不是字节)。让它可见,而不是静默丢掉。 */
        internal val linesNoiseFiltered = AtomicLong(0L)

        /**
         * 当前正在写第几片。
         *
         * `@Volatile`:写它的只有 drain 那一个线程,而读它的有诊断页与清单 ——
         * 不加的话它们可能读到旧值,于是页面显示「第 3 片」而实际在写第 5 片。
         */
        @Volatile
        internal var part: Int = 1

        /** 当前正在写的那一片。**编号最大的那一片**。 */
        val logFile: File get() = File(dir, XLogcatParts.nameOf(part))

        /** 已写入字节数(**全部片合计**)。 */
        val bytes: Long get() = written.get()

        /** 已写入行数(**全部片合计**)。 */
        val lines: Long get() = linesWritten.get()

        /** 是否已有旧片被轮转掉。 */
        val hasRotated: Boolean get() = rotated.get()

        /** 被轮转掉的片数。 */
        val droppedParts: Int get() = partsDroppedNow.get()

        /** 被噪声过滤丢掉的行数。诊断页与结束标记都会显示它。 */
        val noiseFilteredLines: Long get() = linesNoiseFiltered.get()
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

    // ────────────────────────────────────
    // 分片之后的取用口
    // ────────────────────────────────────
    //
    // ⚠️ 这里原有一个 `latestLogFile(context)`,返回「最近一次会话的最后一片」。
    //    分片之后它**只剩一半用处**:诊断页要显示的是「上一次录了多大」,而那是
    //    **全部片合计** —— 只看最后一片会少报(一次会话常有十几片)。故删掉它,
    //    改由下面两个口对外:`partsOf`(拿全部片)与 `latestSessionBytes`(拿合计)。
    //    留着那个函数的坏处是:下一个人会拿它去算体积,于是又少报一次,而不会报错。

    /**
     * 一个会话目录里**全部 logcat 分片**,按片号升序。
     *
     * 判据收窄到「恰好是 `logcat_<正整数>.log`」(见 [XLogcatParts.partOf]) ——
     * 不用「以 logcat 开头」,否则别处同名文件会被算进来。
     */
    fun partsOf(dir: File?): List<File> =
        dir?.listFiles()
            ?.filter { it.isFile && it.length() > 0L && XLogcatParts.isPartName(it.name) }
            ?.sortedBy { XLogcatParts.partOf(it.name) ?: 0 }
            .orEmpty()

    /**
     * 最近一次会话里**全部分片的体积合计**。
     *
     * 诊断页在「未在捕获」时要显示上一次录了多大 —— 而分片之后**单片体积不是那个数**了:
     * 只取最后一片会少报(实测一次会话常有十几片)。故这里合计。
     */
    fun latestSessionBytes(context: Context): Long =
        partsOf(XDiagSession.latest(context)).sumOf { it.length() }

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

        val s = Session(
            dir = dir,
            startedAt = System.currentTimeMillis(),
            partBytes = DEFAULT_PART_BYTES,
            keepParts = DEFAULT_KEEP_PARTS,
            // 会话开始时定下,中途改开关不影响本轮(见 Session.noiseFiltered)
            noiseFiltered = XLogcatNoise.isEnabled(),
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

    /**
     * 结束当前捕获,并**同步**摘掉会话引用。
     *
     * 与 [stop] 的区别只在「什么时候摘引用」:[stop] 把收尾交给读取线程,而本函数立刻
     * 摘掉 —— 供「清空后要马上重开一轮」用(见 [XDiagClear])。不这样做的话,`start()`
     * 的幂等守卫会返回那个**正要死掉的旧会话**,新一轮永远起不来。
     *
     * 摘引用是安全的:读取线程收尾时那一步本就带 `if (session === s)` 守卫,不会被它清错。
     * 旧线程此后往**已被删除**的文件里补写结束标记,落在无人可读的 inode 上 —— 无害。
     *
     * ⚠️ 别把 [stop] 也改成同步摘:那里「等读取线程收尾」是有意的(要写完结束标记)。
     */
    fun stopNow() {
        val s = synchronized(lock) { session } ?: return
        s.stopRequested.set(true)
        synchronized(lock) { if (session === s) session = null }
    }

    /** 捕获失败的事件名(三段点分隔,由检查器机检)。 */
    const val CAPTURE_FAIL_EVENT = "diag.capture.fail"

    /** 分片轮转失败的事件名。 */
    const val ROTATE_FAIL_EVENT = "diag.rotate.fail"

    /**
     * 片首标记的起头 —— 让人一眼看到「**这不是开头,是接着上一片的**」。
     *
     * ⚠️ 用意与它取代的那条(`CAP_MARKER`,「后面没了」)**正好相反**:那条说的是
     * 「到此为止」,这条说的是「上面还有」。分片之后不再有「停写」,只会有「更早的片
     * 已经被轮转掉」—— 而两件事都要在文件里说得出来,否则读者会以为手里这份就是全部。
     */
    internal const val PART_MARKER_LEAD = "!!! [x-diag] logcat part"

    // ────────────────────────────────────

    /**
     * 一片的写入目标:片号 + 该片已写字节数。
     *
     * 只有 drain 那个线程碰它,故不需要锁。**片内字节数必须单独记**(不能只看会话总量)——
     * 判断「本片满了没有」用的是它,而会话总量只用于统计与展示。
     */
    private class PartWriter(val part: Int, val writer: BufferedWriter) {
        var bytes = 0L
    }

    /**
     * 开一片。
     *
     * 第 2 片起先写一行**片首标记**:读者打开 `logcat_7.log` 时必须立刻知道
     * 「这只是中间的一段」,而不是以为这就是全部。
     *
     * ⚠️ 只在**空文件**上写标记。续写已有内容时插进去会把标记落在日志中间,
     * 反而成了噪声。
     */
    private fun openPart(s: Session, part: Int): PartWriter {
        val file = File(s.dir, XLogcatParts.nameOf(part))
        val fresh = file.length() == 0L
        val writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(file, /* append = */ true), Charsets.UTF_8),
            FLUSH_BYTES,
        )
        if (fresh && part > 1) {
            runCatching {
                writer.write(
                    "$PART_MARKER_LEAD $part — CONTINUES from the previous part; earlier lines are " +
                        "in ${XLogcatParts.nameOf(part - 1)}. Parts are consecutive slices of one capture. " +
                        XDiagEnv.MARK
                )
                writer.newLine()
                writer.flush()
            }
        }
        return PartWriter(part, writer)
    }

    /**
     * 轮转到下一片:**先删该老的,再开新的**。
     *
     * 顺序有讲究:反过来的话,若删除失败(或进程立刻被杀),磁盘上会**多留一片**——
     * 而「上限 = 单片 × 片数」这句话就不再成立,且没有任何地方会报错。
     *
     * 由 [XLogcatParts.partToDrop] 决定删哪一片(那里是差一错误的常驻点,已被单测钉住)。
     */
    private fun rotate(s: Session, cur: PartWriter): PartWriter {
        runCatching { cur.writer.flush() }
        runCatching { cur.writer.close() }

        XLogcatParts.partToDrop(latest = cur.part, keep = s.keepParts)?.let { victim ->
            val deleted = runCatching { File(s.dir, XLogcatParts.nameOf(victim)).delete() }
                .getOrDefault(false)
            if (deleted) {
                s.partsDroppedNow.incrementAndGet()
                s.rotated.set(true)
            }
        }

        val next = cur.part + 1
        val opened = runCatching { openPart(s, next) }.getOrNull()
        if (opened == null) {
            // 开不出新片(磁盘满/权限/被杀途中)。**不能让它把捕获弄死** ——
            // 本线程一旦退出,logcat 的管道就没人读,进程会被反向堵住,于是
            // 「诊断功能把小问题变成应用问题」。故退回旧片继续写,并把原因显式留痕:
            // 代价是那一句「上限 = 单片 × 片数」在此会话内不再成立,而这必须说得出来。
            s.rotationBlocked = true
            XDiagnostics.recordStickyFailure(
                domain = XDomain.CORE,
                event = ROTATE_FAIL_EVENT,
                message = "logcat 分片轮转失败,本片会一直增长(不再轮转):" + XLogcatParts.nameOf(next),
            )
            // ⚠️ 重新打开旧片时**保留它的已写字节数**:新 PartWriter 是 0,
            //    而文件其实是接着写的(append)—— 记错会让「本片多大」这个数撒谎。
            //    (轮转已被 blocked 挡住,故它只影响展示;但记错的数不该留在代码里。)
            val reopened = runCatching { openPart(s, cur.part) }.getOrNull()
                ?: return cur // 连旧片都开不回来 → 继续读、不再写(管道仍不能堵)
            reopened.bytes = cur.bytes
            return reopened
        }
        s.part = next
        return opened
    }

    private fun runSession(s: Session, proc: Process, context: Context) {
        try {
            // 当前片**会变**(轮转时换人),故一路用 var 传下去 —— 收尾标记必须落在
            // **最后**那一片上,而不是第 1 片(写错的话结束标记会出现在文件中间)。
            var current = openPart(s, s.part)
            try {
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
                writeCaptureHeader(s, current.writer, context)
                try {
                    current = drain(s, proc, current)
                } finally {
                    // 结束标记放在 finally:捕获失败(异常)时也要留下「到此为止」的痕迹,
                    // 否则读者会以为这份是正常收尾。
                    writeCaptureEnd(s, current)
                    runCatching { current.writer.flush() }
                }
            } finally {
                // 关闭**最后**那一片。中途轮转掉的片在 rotate 里已经关过了。
                runCatching { current.writer.flush() }
                runCatching { current.writer.close() }
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
     * **② 读的动作永远不停。**
     * 停止读取会让管道灌满、反向把 logcat 堵住(它写不进就会卡)。故**任何情况下都继续读**
     * —— 分片满了只是换个目的地([rotate]),不是停读。
     *
     * @return **最后**写的那一片。收尾标记要落在它上面 —— 落在第 1 片上就会出现在文件中间。
     */
    private fun drain(s: Session, proc: Process, first: PartWriter): PartWriter {
        var current = first
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

                if (s.noiseFiltered && XLogcatNoise.isNoise(line)) {
                    // 已知噪声:只计数不落盘。**计数是必须的** —— 结束时要把滤掉多少行写出来,
                    // 否则读的人无法判断这份日志是不是完整的(见 writeCaptureEnd)。
                    s.linesNoiseFiltered.incrementAndGet()
                } else {
                    val n = line.length + 1L
                    // 本片满了 → 换一片。判据用**片内**字节数(不是会话总量),
                    // 否则第 2 片一开始就被判满。
                    if (!s.rotationBlocked && current.bytes + n > s.partBytes) {
                        current = rotate(s, current)
                    }
                    runCatching {
                        current.writer.write(line)
                        current.writer.newLine()
                    }
                    current.bytes += n
                    s.written.addAndGet(n)
                    s.linesWritten.incrementAndGet()
                    sinceFlush += n
                }

                val now = System.currentTimeMillis()
                if (sinceFlush >= FLUSH_BYTES || now - lastFlush >= FLUSH_INTERVAL_MS) {
                    // 批量 flush:降 I/O 次数。失败不致命(下一次或关闭时还会再 flush)。
                    runCatching { current.writer.flush() }
                    sinceFlush = 0L
                    lastFlush = now
                }
            }
        }
        return current
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
            // 单调锚点:与上一行同时取,但**不受改时间影响**。读者若怀疑时间线乱序,
            // 拿这两行一比就能看出当时有没有被改过(检测与理由见 XClockWatch)。
            add("monotonic: ${SystemClock.elapsedRealtime()} ms since boot")
            // 措辞刻意避开「本文件…」:这一行会**跟着文件被导出**,而导出时已逐行脱敏 ——
            // 若写「本文件未脱敏」,它在导出物里就成了假话。改说「原始文件本身不脱敏」,
            // 那是关于原始文件的陈述,在两种载体里都成立。
            add(
                "redaction: " + if (XLogScrub.ENABLED) {
                    "applied line by line on export (this file and the bundle); credentials " +
                        "are masked, but ordinary content such as chat text is NOT sanitised"
                } else {
                    "DISABLED - exports are raw and may carry credentials such as API keys"
                }
            )
            add("noise   : ${XLogcatNoise.describeForHeader(s.noiseFiltered)}")
            add(
                "parts   : up to ${sizeText(s.partBytes)} each, newest ${s.keepParts} kept; " +
                    "older parts are deleted as new ones start (see the part marker at the " +
                    "top of any file other than part 1)"
            )
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
     * 与 [PART_MARKER_LEAD] 的分工:那条写在**每一片的开头**(除第 1 片),回答「上面还有」;
     * 这条写在**最后一片的末尾**,回答「这份什么时候结束的、滤掉了多少行、轮转掉了多少片」。
     */
    private fun writeCaptureEnd(s: Session, part: PartWriter) {
        // ⚠️ 收尾写的是**最后那一片** —— 轮转之后那不再是第 1 片。
        //    正因如此这里必须收 [PartWriter] 而不是 `BufferedWriter`:收后者的话调用方
        //    传 `current` 会编译不过(实测在 CI 上红过一次,而本地检查器判不了类型)。
        val out = part.writer
        val now = System.currentTimeMillis()
        val seconds = (now - s.startedAt) / 1000.0
        runCatching {
            out.newLine()
            // 被滤掉的行数写进结束标记:与「达阀丢了多少」同一个口径 —— 丢了就得说,
            // 否则读的人会以为文件里就是全部。
            val noiseText = if (s.noiseFiltered) {
                " · ${s.noiseFilteredLines} noise line(s) filtered"
            } else {
                ""
            }
            out.write(
                "${XDiagEnv.MARK} capture ended ${XDiagEnv.stamp(now)} · lasted " +
                    "${XDiagEnv.durationText(seconds)} · ${s.lines} lines / ${sizeText(s.bytes)}" +
                    " in ${s.part} part(s)" + noiseText + " ${XDiagEnv.MARK}"
            )
            out.newLine()
            // ⚠️ 先算成 val:`"…" + if (c) A else B + "…"` 会被解析成 `if (c) A else (B + C)`,
            //    于是**真**分支丢掉尾巴标记。编译不报错,只在那一支才看得出来 ——
            //    而那条路径平时不走,属于最难发现的一类。
            val rotateText = when {
                s.rotationBlocked ->
                    "ROTATION FAILED - this part grew past the ${sizeText(s.partBytes)} limit; " +
                        "older parts were NOT trimmed (see the recorded critical failure)"
                s.hasRotated ->
                    "${s.droppedParts} older part(s) were deleted to stay within " +
                        "${s.keepParts} part(s) - EARLIER LINES ARE GONE, this capture only " +
                        "covers the most recent window"
                else ->
                    "nothing rotated out (the whole capture fits in ${s.keepParts} part(s))"
            }
            out.write("${XDiagEnv.MARK} parts: $rotateText ${XDiagEnv.MARK}")
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
