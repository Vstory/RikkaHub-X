// [X-custom] RikkaHub-X 诊断框架：总开关 + 分域缓冲 + 导出
package me.rerere.rikkahub.x.diag

import me.rerere.rikkahub.x.diag.XLogRing.Level
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * X 定制的诊断域。
 *
 * 每个域对应一组 X 定制功能，日志按域分开存放 —— 导出时**一域一段**（后续接打包时一域一个文件），
 * 排查时不必在几千行混杂日志里翻找。
 *
 * **顺序即导出顺序**，也决定诊断页上的排列，故按「日常使用频率」而非字母序。
 */
enum class XDomain(val key: String, val label: String) {
    STORAGE("storage", "存储"),

    /**
     * 全文检索索引的维护（2026-09-11 加）。
     *
     * 单列一个域而不是并进「存储」：存储域的事件名一律以 `asset.` 开头
     * （`XStorageEventsTest` 钉住了这条），而索引维护与资产账本是两件事 ——
     * 硬塞进去会让「这个前缀代表什么」变得含糊。
     */
    SEARCH("search", "检索"),
    COMPRESS("compress", "上下文压缩"),
    CONTEXT("context", "容量表与用量"),
    CHAT("chat", "会话"),

    /**
     * 网络请求记录（方法 + URL + 状态码 + 耗时 + 请求头 + **错误响应正文**，2026-09-12 加）。
     *
     * 单列一个域而不是并进「会话」：它记的不是「X 做了什么」，而是**应用往外发了什么**，
     * 来源也不同（上游 `RequestLoggingInterceptor`）。混进会话域会让「chat.* 是 X 的会话埋点」
     * 这条约定变得含糊。
     *
     * ⚠️ **请求正文不记，只记 `reqBytes` 字节数**（2026-09-13 用户决定）。两条理由：
     * 一条几百 KB 的记录会把时间线冲垮（合并成 `events.log` 之后这一点变成硬约束），
     * 而 [XLogScrub] 只认凭据形态、**认不出聊天正文** —— 不落盘才是真正的源头掐断。
     * 详见 [XNetLine] 的类注释。
     */
    NET("net", "网络请求"),
    WORKSPACE("workspace", "工作区"),
    SYNC("sync", "备份同步"),
    MCP("mcp", "MCP"),
    UI("ui", "界面与语音"),
    VERSION("version", "版本号"),
    CORE("core", "通用"),
}

/**
 * X 定制的诊断中枢。
 *
 * ## 开关语义（**本类的核心约定**，用户 2026-09-11 明确要求）
 *
 * | 情形 | logcat | 诊断缓冲 |
 * |---|---|---|
 * | 诊断**关** + 正常流程 | **完全不输出** | 不记录 |
 * | 诊断**关** + 异常/失败 | 输出（与上游一致） | 不记录 |
 * | 诊断**开** | 输出 | 记录 |
 *
 * 第二行是刻意的：上游自己在失败路径上就有日志（`android.util.Log` 全项目 191 处，
 * 含 63 处 `Log.e`、32 处 `Log.w`），若把失败日志也一并静默，反而偏离了「和上游一样」。
 * 而**正常流程**在诊断关闭时静默到 logcat 都不写 —— 这样「关掉诊断」就回到与上游一致的
 * 可观测行为。开关只作用于 **X 改动的代码**，上游代码一律保持原样。
 *
 * ## 为什么消息用 lambda 传（见 [XLog.info]）
 *
 * 字符串拼接发生在调用点。若消息作为普通参数传入，**即使开关关着也已经拼好了** ——
 * 白付拼接与分配的开销。故入口用内联函数 + lambda，关闭时**连 lambda 都不会执行**。
 *
 * ## 为什么「记录起点」是推导的，而不是存下来的（2026-09-11 修正）
 *
 * 初版用一个 `windowStartAt` 字段在**开启开关时**记下时刻。两个毛病：
 *
 * ① **表头会撒谎**：清空缓冲后字段仍在，于是「有记录、但起点显示为开开关那一刻」；
 *    反过来若清空时顺手把字段置空，又会变成「有记录、却显示从未开启」。
 * ② **多出一个可进入却无法离开的状态**：想回到「从未开启过」只能重启进程 ——
 *    上游测试写得出来、跑不过去。
 *
 * 改为**从缓冲里最早一条记录推导**：表头永远与实际内容一致，清空后自然无起点，
 * 也不再有需要重置的隐藏状态。语义上它也更准 —— 用户要的是「这段记录覆盖了哪段时间」，
 * 那正是最早一条记录的时刻，而不是「他几点点的开关」。
 */
object XDiagnostics {

    /** 单域上限。与 [XLogRing.DEFAULT_CAPACITY] 保持一致。 */
    const val MAX_ENTRIES_PER_DOMAIN = XLogRing.DEFAULT_CAPACITY

    /**
     * 总开关。
     *
     * `@Volatile`：可在任意线程翻转（设置页在 UI 线程，埋点在 IO 线程）。
     * 读多写极少，无需加锁。
     */
    @Volatile
    private var enabledFlag = false

    /**
     * 把开关状态写出去的回调 —— 由 Android 侧在启动时接上（见 [attachPersistence]）。
     *
     * **可为 null**（JVM 单测里就是 null）：那时开关只在内存里翻转，与改造前行为一致。
     * 做成可注入而不是直接依赖 `SharedPreferences`，是为了让这一层**保持纯 Kotlin、可单测** ——
     * 否则「开关会不会被持久化」这件事只能靠装机去试。
     */
    private var persist: ((Boolean) -> Unit)? = null

    /**
     * 把每条记录**落盘**的回调 —— 由 Android 侧接上（见 [XDiagFileStore]）。
     *
     * 做成可注入而不是直接依赖文件，理由与 [persist] 相同：这一层保持纯 Kotlin，
     * 于是「开关会不会持久化」「落盘行长什么样」这类事能在 JVM 单测里验，
     * 而不必装机去看。
     *
     * **可为 null**（JVM 单测里就是 null）：那时只有内存环，与落盘之前的行为一致。
     * 传的是「域 + 已组好的一行」—— 组行的逻辑在 [XDiagLine]（也是纯函数）。
     */
    @Volatile
    private var lineSink: ((XDomain, String) -> Unit)? = null

    /** 接上落盘；传 `null` 摘掉。 */
    fun setLineSink(value: ((XDomain, String) -> Unit)?) {
        lineSink = value
    }

    /**
     * 开关翻转时要通知的一方（实现方自己接上，见 [addEnabledListener]）。
     *
     * ## 为什么是一个列表而不是单个回调
     *
     * 目前只有 logcat 捕获（[XLogcatCapture]）一个消费者;域文件落盘与上游请求日志
     * 接入时也走这里。起初只有一个,故写成单个可空字段;眼下正要加第二个,若再添一个
     * 字段,以后每加一个消费者就多一处「别忘了在这里也调一下」—— 那是最容易漏的地方。
     *
     * ## 为什么不让消费者自己来读开关
     *
     * 捕获会起子进程、请求记录会改 OkHttp 日志级别 —— 都是**副作用**,依赖「谁在什么时候读」
     * 不可靠;开关翻转的**那一刻**才是准确的起停时机。
     *
     * 用 `CopyOnWriteArrayList`:注册发生在启动期,翻转发生在任意线程,而读多写极少。
     */
    private val enabledListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /**
     * 启动期接上持久化，并把**上次的开关状态**读回来。
     *
     * ## 为什么必须持久化（2026-09-11 用户指出，实测确认）
     *
     * 原实现只是个内存变量：**进程一重启，开关就回到「关」**。这条看着像小不便，实际是硬缺陷 ——
     * **本项目最需要取证的恰恰是「启动期」的事**：
     *
     * | 场景 | 开关必须活着的原因 |
     * |---|---|
     * | 存量回填（P1） | 它在**开机时**跑；开关若在重启后归零，那批日志永远不会被记录 |
     * | 启动健壮性（P3） | 它要查的就是「打不开 App」这类**启动期**故障 —— 开关自己都活不过启动，就永远查不到 |
     *
     * 即：**要取证的事发生在启动期，而开关却活不过启动期** —— 逻辑上自相矛盾。
     *
     * ## 为什么要「同步读」
     *
     * 回填与建库都在 `Application.onCreate` 里启动，**早于任何异步设置加载**。
     * 故这里由调用方传**已经读好的初值**，而不是让它自己去异步取 ——
     * 否则第一波日志会跑在开关生效之前。
     *
     * @param initial 上次的开关状态（同步读到的）。
     * @param write 之后每次翻转时调用，用于落盘。
     */
    fun attachPersistence(initial: Boolean, write: (Boolean) -> Unit) {
        enabledFlag = initial
        persist = write
    }

    /**
     * 注册一个「开关翻转」的消费者。
     *
     * 开关**打开**时它该开始、**关闭**时该结束 —— 故传的是翻转事件本身，
     * 而不是让它轮询开关状态。
     *
     * 某个消费者起停失败**不能**影响开关本身（用户要的是「开着」，它只是其中一个消费者），
     * 但也不能静默:记一条警告,诊断页上能看到。
     *
     * 幂等:同一个实例重复注册只会生效一次（`addIfAbsent` 按实例判等）。
     * 这让各模块的 `install()` 可以放心地重复调用。
     */
    fun addEnabledListener(listener: (Boolean) -> Unit) {
        enabledListeners.addIfAbsent(listener)
    }

    private val rings: Map<XDomain, XLogRing> =
        XDomain.entries.associateWith { XLogRing(MAX_ENTRIES_PER_DOMAIN) }

    fun isEnabled(): Boolean = enabledFlag

    /**
     * 翻转开关。
     *
     * **关闭时保留已记录内容** —— 用户很自然会「关掉诊断，再导出刚刚那段」；
     * 若关闭即清空，他就必须先把导出做完才能关，顺序上很别扭。要清空请显式调用 [clearAll]。
     */
    fun setEnabled(enabled: Boolean) {
        enabledFlag = enabled
        // 落盘失败不该影响本次会话的开关（内存里的值已经是新的），但也不能静默 ——
        // 写不进去意味着「下次启动开关又归零」，那是需要知道的现象。
        //
        // ⚠️ 记进**内存缓冲**而不是写系统日志，两个理由：
        // ① 用户本来就在诊断页看记录，记在这里他才看得到；
        // ② `android.util.Log` 在 JVM 单测里未 mock，会在**这条异常处理路径上**再抛一次。
        persist?.let { write ->
            runCatching { write(enabled) }.onFailure { error ->
                record(
                    domain = XDomain.CORE,
                    level = XLogRing.Level.WARN,
                    event = PERSIST_FAIL_EVENT,
                    message = "诊断开关落盘失败，下次启动将回到默认：" + error,
                    error = error,
                )
            }
        }
        // 各消费者随开关起停。放在落盘之后:起停失败要记的警告本身也得能落盘。
        enabledListeners.forEach { notify ->
            runCatching { notify(enabled) }.onFailure { error ->
                record(
                    domain = XDomain.CORE,
                    level = XLogRing.Level.WARN,
                    event = "diag.capture.toggle_fail",
                    message = "诊断消费者起停失败：" + error,
                    error = error,
                )
            }
        }
    }

    /**
     * 开关落盘失败的事件名。
     *
     * 单列一条的理由：它的**后果与其它失败不同** —— 其它失败是「这次少记了一条」，
     * 而它是「**下次启动开关会归零**」。用户看到它就知道：要么手动再开一次，
     * 要么去查存储权限/空间。
     */
    const val PERSIST_FAIL_EVENT = "diag.persist.fail"

    /** 记录一条。**调用方一般用 [XLog] 而不是直接调这里**。 */
    fun record(domain: XDomain, level: Level, event: String, message: String, error: Throwable? = null) {
        rings.getValue(domain).record(level, event, message, error)
        // 再落一份盘。放在这个**底层入口**而不是 XLog：框架自身的失败路径也走这里，
        // 而那些正是「需要在文件里看到」的。
        // 开关的闸门在 XLog（info / warn 各自判断），故关掉开关时这里不会被调用。
        lineSink?.let { sink ->
            sink(domain, XDiagLine.format(level, domain, event, XLogRing.textWithError(message, error)))
        }
    }

    fun entries(domain: XDomain): List<XLogRing.Entry> = rings.getValue(domain).recent()

    fun countOf(domain: XDomain): Int = rings.getValue(domain).size()

    /** 全部域的条数合计。 */
    fun totalCount(): Int = rings.values.sumOf { it.size() }

    /** 有内容的域（诊断页只展示这些，避免一屏空标题）。 */
    fun domainsWithContent(): List<XDomain> = XDomain.entries.filter { countOf(it) > 0 }

    /**
     * 本次记录覆盖的起点 —— **由缓冲里最早一条记录推导**，没有记录则为 `null`。
     *
     * 推导而非存储，故不可能与实际内容不一致（理由见类注释）。
     */
    fun windowStartMillis(): Long? =
        XDomain.entries
            .flatMap { entries(it) }
            .minOfOrNull { it.at }

    /**
     * 清空全部域，回到「什么都没记录过」的状态。
     *
     * 因为「记录起点」是推导的，清空后它自然变为 `null`，无需额外重置任何字段。
     */
    fun clearAll() {
        rings.values.forEach { it.clear() }
        clearStickyFailure()
    }

    // ────────────────────────────────────
    // 关键失败留存（与开关无关）
    // ────────────────────────────────────

    /** 一条「会让功能静默失效」的失败。 */
    data class StickyFailure(
        val domain: XDomain,
        val event: String,
        val message: String,
        val detail: String?,
        val at: Long,
    )

    private val sticky = AtomicReference<StickyFailure?>(null)

    /** 最近一次关键失败；没有则为 `null`。 */
    fun stickyFailure(): StickyFailure? = sticky.get()

    /**
     * 记一条**关键失败**：会让某个 X 功能静默失效的那种（如建表失败）。
     *
     * **为什么要独立于开关**：开关管的是「正常流水」。而关键失败通常发生在**启动时**，
     * 用户发现异常、打开开关时，失败早已过去 —— 记录被开关挡在门外，现场就此丢失。
     * 实测 2026-09-11 的建表失败正是如此：诊断页里只剩后续的连锁失败，
     * 真正原因只能靠猜。
     *
     * 故：**开关关着也记**，且**一直留着**直到用户清空。
     */
    fun recordStickyFailure(
        domain: XDomain,
        event: String,
        message: String,
        error: Throwable? = null,
        detail: String? = null,
    ) {
        val stackText = detail ?: error?.stackTraceToString()
        sticky.set(
            StickyFailure(
                domain = domain,
                event = event,
                message = message,
                detail = stackText,
                at = System.currentTimeMillis(),
            )
        )

        // ── 落点 ①:存活层(常开、跨会话) ──
        // 这是**不能少的那一处**:另外两处都可能留不住 —— 内存槽进程一死就没,
        // 而 events.log 要会话目录(只在开关开着时才有)。见 XSurvivorLog 的类注释。
        XSurvivorLog.append(domain = domain, event = event, message = message, detail = stackText)

        // ── 落点 ②:会话事件时间线(开关开着时才有) ──
        // 与上面**刻意重复**,但服务不同读法:这里是「什么时候发生了什么」(顺序即信息),
        // 存活层是「哪些事不能丢」(清单)。目录未建立时 sink 自己会跳过 —— 那时已有 ① 兜住。
        lineSink?.let { sink ->
            sink(domain, XDiagLine.format(Level.WARN, domain, event, XLogRing.textWithError(message, error)))
        }
    }

    /** 清掉关键失败留存。 */
    fun clearStickyFailure() {
        sticky.set(null)
    }

    // ────────────────────────────────────
    // 导出:已收敛到「一个压缩包」
    // ────────────────────────────────────
    //
    // 这里原先还有两个文本导出函数(`dump` / `dumpMerged`),把内存环渲成一段按域分块的
    // 文本。2026-09-13 移除,连同诊断页上那两张「导出诊断记录(已脱敏/完整)」卡片 ——
    // 它们与压缩包**是同一份内容的两种包装**,而压缩包还多带原始 logcat 与清单。
    //
    // ⚠️ 一处**已知取舍**要记下来:文本导出读的是**内存环**,而压缩包读的是**磁盘文件**。
    // 正常情形下文件是超集(环每域只留 2000 条,文件从会话开始累积),故移除无损;
    // 唯一的例外是**会话目录建不出来**时(见 [XDiagSession.open]),那时环里有内容而
    // 文件一个都没有 —— 压缩包会是空的。该情形已由 [recordStickyFailure] 显式留痕
    // (「诊断目录创建失败,本次不落盘任何记录」),不会静默消失。


    /**
     * logcat 过滤命令。
     *
     * 放在这里而非写死在界面里：诊断页要显示它，而命令必须与 [XLogRing.TAG] 一致 ——
     * 两处各写一份常量，改 tag 时必漏一处，用户照着敲就会一条日志都看不到。
     */
    fun logcatHint(): String = "adb logcat -s ${XLogRing.TAG}:*"
}
