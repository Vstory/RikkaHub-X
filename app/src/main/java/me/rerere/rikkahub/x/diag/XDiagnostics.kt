// [X-custom] RikkaHub-X 诊断框架：总开关 + 分域缓冲 + 导出
package me.rerere.rikkahub.x.diag

import me.rerere.rikkahub.x.diag.XLogRing.Level

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
    COMPRESS("compress", "上下文压缩"),
    CONTEXT("context", "容量表与用量"),
    CHAT("chat", "会话"),
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
    }

    /** 记录一条。**调用方一般用 [XLog] 而不是直接调这里**。 */
    fun record(domain: XDomain, level: Level, event: String, message: String, error: Throwable? = null) {
        rings.getValue(domain).record(level, event, message, error)
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
    }

    /**
     * 按域导出。**只返回有内容的域**。
     *
     * @param full `true` = 带完整信息（用户显式选择）。默认脱敏。
     * @param filesRoot 用于抹去应用私有目录前缀；`null` 则只做哈希脱敏。
     * @param domains 只导出这些域；`null` = 全部。
     * @return 域 → 文本（含表头：域标签、条数、记录起点）。
     */
    fun dump(
        full: Boolean = false,
        filesRoot: String? = null,
        domains: List<XDomain>? = null,
    ): Map<XDomain, String> {
        val selected = (domains ?: XDomain.entries).filter { countOf(it) > 0 }
        // 起点取全部域的并集:它描述的是「这次记录」而非「某个域」
        val start = windowStartMillis()
        return selected.associateWith { domain ->
            val redact: (String) -> String = { XRedaction.redact(it, filesRoot, full) }
            buildString {
                append("# ").append(domain.label).append("（").append(domain.key).append("）\n")
                append("# 条数: ").append(countOf(domain)).append('\n')
                append("# 记录起点: ").append(if (start == null) "(无记录)" else XLogRing.timeText(start)).append('\n')
                append("# 脱敏: ").append(if (full) "否（含完整信息）" else "是").append("\n")
                append('\n')
                append(rings.getValue(domain).format(redact = redact))
            }
        }
    }

    /**
     * 合并成一段文本（用于「一键复制」）。
     *
     * 域之间用空行分隔，便于在聊天里阅读。
     */
    fun dumpMerged(full: Boolean = false, filesRoot: String? = null): String {
        val parts = dump(full = full, filesRoot = filesRoot)
        if (parts.isEmpty()) return EMPTY_DUMP
        return parts.entries.joinToString("\n\n") { (_, text) -> text.trimEnd() }
    }

    /** 空缓冲的导出文案 —— 不返回空串，否则界面上分不清「没记录」与「界面坏了」。 */
    const val EMPTY_DUMP = "(无 X 诊断记录)"

    /**
     * logcat 过滤命令。
     *
     * 放在这里而非写死在界面里：诊断页要显示它，而命令必须与 [XLogRing.TAG] 一致 ——
     * 两处各写一份常量，改 tag 时必漏一处，用户照着敲就会一条日志都看不到。
     */
    fun logcatHint(): String = "adb logcat -s ${XLogRing.TAG}:*"
}
