// [X-custom] RikkaHub-X 诊断框架：logcat 里的 OEM/渲染噪声过滤
package me.rerere.rikkahub.x.diag

/**
 * logcat 里的**已知噪声**过滤。
 *
 * ## 为什么需要它(实测数据,不是推测)
 *
 * 2026-09-13 一份真实捕获(26.5 秒 / 686 行)里,与 App 真正相关的只有约 **220 行** ——
 * 其余三分之二全是 OEM 与渲染噪声:
 *
 * | tag | 行数 |
 * |---|---|
 * | `ResponseAPI`(模型 SSE 流) | 115 |
 * | `OkHttp`(请求头) | 93 |
 * | `DynamicFramerate [AnimationSpeedAware]` | 84 |
 * | `ViewRootImplExtImpl` | 55 |
 * | `VRI[RouteActivity]` | 55 |
 * | `ImeTracker` | 32 |
 * | `WindowOnBackDispatcher` | 30 |
 * | `OplusScrollToTopManager` / `OplusPredictiveBackController` / `InsetsController` | 各 ~25 |
 *
 * 这份包是**交给 AI 读**的。噪声不只是「占地方」:它会挤掉 200MB 安全阀的额度、
 * 拖慢读取、并且把真正要看的那几行埋掉。而 ColorOS 的渲染日志对诊断 App 行为零贡献。
 *
 * ## 安全性质:**认不出来的一律保留**
 *
 * 过滤只在「确认这是一条格式良好的 logcat 行、且它的 tag 在白名单里」时才丢弃。
 * 于是下列内容**永远不会**被丢:
 *
 * - 清单头 / 结束标记 / 达阀标记(它们不是 logcat 行);
 * - `--------- beginning of system` 之类的 logcat 自身分隔行;
 * - 任何 tag 不在名单里的行;
 * - **任何解析不出 tag 的行**(格式变了就自动退回到「全都保留」,而不是静默丢一半)。
 *
 * 最后一条是刻意的:过滤失败的代价必须是「日志变多」,不能是「日志少了」。
 *
 * ## 为什么过滤发生在**写入时**而不是导出时
 *
 * 会话目录里的文件按承诺就是「现场原样」。那里不做取舍 —— 否则同一份文件在不同机器上
 * 导出会得到不同内容,复核也无从谈起。故过滤在落盘那一步做,并且**把丢了多少行写进
 * 清单头与结束标记**(不静默丢,这是本项目一贯的口径)。
 *
 * ## 名单从哪来
 *
 * 全部取自上面那份实测捕获里出现过的 tag,而非凭空列举。加新条目时**也应当有实测依据** ——
 * 这条限制是为了防止「把看不懂的都算噪声」,那会把诊断本身弄瞎。
 */
object XLogcatNoise {

    /**
     * tag 前缀名单。用**前缀**而不是全等:`VRI[RouteActivity]`、`VRI[Pop-Up Window]`
     * 这类带后缀的 tag 很常见。
     *
     * 只放「跨机型都存在、且对 App 行为无信息量」的:OEM 渲染/动画/输入法/窗口管理、
     * 以及 AOSP 的渲染与缓冲区流水。**不放**能反映性能问题的
     * (如 `Choreographer: Skipped N frames` —— 那正是流式卡顿的证据)。
     */
    private val NOISE_TAG_PREFIXES: List<String> = listOf(
        // ColorOS / OPPO 专有
        "DynamicFramerate",
        "OplusScrollToTopManager",
        "OplusPredictiveBackController",
        "OplusBracketLog",
        "OplusInputMethodUtil",
        "OplusViewMirrorManager",
        "ViewRootImplExtImpl",
        // AOSP 窗口与输入法流水
        "VRI[",
        "ImeTracker",
        "InsetsController",
        "WindowOnBackDispatcher",
        "RemoteInputConnectionImpl",
        "InteractionJankMonitor",
        "WindowManager",
        // 渲染与图形缓冲
        "BLASTBufferQueue",
        "BufferQueueConsumer",
        "BufferQueueProducer",
        "SurfaceControl",
        "OpenGLRenderer",
        "HWUI",
        "skia",
        "ResourcesManagerExtImpl",
        "VelocityTracker",
        // 冻结进程的 GC 提示(对诊断 App 行为无信息量)
        "FinalizerDaemon",
    )

    /**
     * 从一条 `logcat -v threadtime` 行里取 tag;取不到返回 `null`。
     *
     * 格式:`MM-DD HH:MM:SS.mmm  PID  TID L TAG: message`
     * (level 是单个字母;tag 到第一个 `:` 为止)
     *
     * ⚠️ 必须**严格匹配**:宽宽松松地「找一个冒号」会把正文里的内容当成 tag,
     * 于是丢错东西。认不出的行一律返回 `null` → 调用方保留。
     */
    private val TAG_RE = Regex(
        "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+[VDIWEFAS]\\s+([^:]+):"
    )

    internal fun tagOf(line: String): String? =
        TAG_RE.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** 这条行是不是已知噪声。**认不出 tag 时返回 false**(保留)。 */
    fun isNoise(line: String): Boolean {
        val tag = tagOf(line) ?: return false
        return NOISE_TAG_PREFIXES.any { tag.startsWith(it) }
    }

    /** 名单条数。公开出来是为了让「名单被误删成空表」这件事可被断言发现。 */
    val patternCount: Int get() = NOISE_TAG_PREFIXES.size

    /**
     * 出问题时的逃生口:关掉它则**一条都不丢**。
     *
     * ## 为什么要给开关,而不是「聪明地默认过滤」
     *
     * 过滤是**有损**的 —— 猜错的代价是「现场少了一段,而你不知道少了什么」。
     * 虽然名单里全是渲染/窗口流水,但只要有人需要看那些(排查掉帧、排查输入法问题),
     * 就必须能一条不漏地拿到。默认开、可关,是「默认好用 + 需要时不骗人」的折中。
     *
     * ## 为什么状态住在这里而不是 XDiagnostics
     *
     * 它只影响 logcat 捕获这一条路,与「总开关」的语义无关(总开关管记不记,
     * 它管记下来的东西里要不要滤)。放在一起会让「开关语义」那张表多出一行无意义的组合。
     */
    @Volatile
    private var enabledFlag = true

    /** 把开关状态写出去的回调 —— 由 Android 侧接上(见 [DiagnosticSwitchStore])。 */
    private var persist: ((Boolean) -> Unit)? = null

    fun isEnabled(): Boolean = enabledFlag

    /**
     * 翻转。
     *
     * **立即生效于后续行**,不重写已写下的内容(落盘是流式的,回头改文件得不偿失)。
     * 故一次捕获里可能前段有噪声、后段没有 —— 清单头会说明本次到底滤没滤。
     */
    fun setEnabled(enabled: Boolean) {
        enabledFlag = enabled
        persist?.let { write ->
            runCatching { write(enabled) }.onFailure { error ->
                XDiagnostics.record(
                    domain = XDomain.CORE,
                    level = XLogRing.Level.WARN,
                    event = PERSIST_FAIL_EVENT,
                    message = "噪声过滤开关落盘失败，下次启动将回到默认：" + error,
                    error = error,
                )
            }
        }
    }

    /**
     * 接上持久化并把上次的值读回来。与 [XDiagnostics.attachPersistence] 同一个理由:
     * 读回来的初值要**同步**拿到,否则第一波日志会跑在开关生效之前。
     */
    fun attachPersistence(initial: Boolean, write: (Boolean) -> Unit) {
        enabledFlag = initial
        persist = write
    }

    /** 开关落盘失败的事件名(三段点分隔)。 */
    const val PERSIST_FAIL_EVENT = "diag.noise.persist_fail"

    /**
     * 写进清单头的那句自述 —— 让读包的人知道**本次**到底滤没滤。
     *
     * ⚠️ 取的是**传进来的那次会话的值**,不是「当前开关值」:开关可以在捕获中途被翻转,
     * 那时当前值已经不描述这份文件了。清单头的职责是描述**文件**,故由调用方把
     * 会话开始时定下的值传进来。
     */
    fun describeForHeader(sessionFiltered: Boolean): String =
        if (sessionFiltered) {
            "on (${NOISE_TAG_PREFIXES.size} known OEM/render tag prefixes dropped; " +
                "filtered lines are counted)"
        } else {
            "OFF (every line is kept)"
        }
}
