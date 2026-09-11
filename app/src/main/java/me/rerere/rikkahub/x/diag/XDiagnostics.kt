// [X-custom] RikkaHub-X 诊断框架：总开关 + 分域缓冲 + 导出(纯逻辑)
package me.rerere.rikkahub.x.diag

import me.rerere.rikkahub.x.diag.XLogRing.Level

/**
 * X 定制的诊断域。
 *
 * 每个域对应一组 X 定制功能，日志按域分开存放 —— 导出时**一域一个文件**，
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
 * ## 开关语义（**这是本类的核心约定**）
 *
 * | 情形 | logcat | 诊断缓冲 |
 * |---|---|---|
 * | 诊断**关** + 正常流程 | **完全不输出** | 不记录 |
 * | 诊断**关** + 异常/失败 | 输出（与上游一致） | 不记录 |
 * | 诊断**开** | 输出 | 记录 |
 *
 * 第二行是刻意的：上游自己在失败路径上就有日志（`Logging.log` 全项目 5 处调用
 * 全在 catch/onFailure 里），若把失败日志也一并静默，反而偏离了「和上游一样」。
 * 而**正常流程**在诊断关闭时静默到 logcat 都不写 —— 这样「关掉诊断」
 * 就等于回到与上游完全一致的可观测行为。
 *
 * ## 为什么用 lambda 传消息（见 [XLog.info]）
 *
 * 字符串拼接发生在调用点。若消息作为普通参数传入，**即使开关关着也已经拼好了** ——
 * 白付拼接与分配的开销。故入口用内联函数 + lambda，关闭时**连 lambda 都不会执行**。
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

    /** 当前窗口起点（本次开启的时刻）；未开启为 `null`。 */
    @Volatile
    private var windowStartAt: Long? = null

    /** 累计开启次数 —— 用于摘要里说明「这是第几轮记录」。 */
    @Volatile
    private var sessionCount = 0

    private val rings: Map<XDomain, XLogRing> =
        XDomain.entries.associateWith { XLogRing(MAX_ENTRIES_PER_DOMAIN) }

    fun isEnabled(): Boolean = enabledFlag

    /**
     * 翻转开关。
     *
     * **开启时记录窗口起点**；关闭时**保留已记录内容**（便于关掉后再导出，
     * 否则用户得先导出才能关，顺序上很别扭）。要清空请显式调用 [clearAll]。
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled == enabledFlag) return
        enabledFlag = enabled
        if (enabled) {
            windowStartAt = System.currentTimeMillis()
            sessionCount++
        }
    }

    /** 本次窗口的起点时刻；从未开启过为 `null`。 */
    fun windowStartAt(): Long? = windowStartAt

    fun sessionCount(): Int = sessionCount

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

    fun clearAll() {
        rings.values.forEach { it.clear() }
    }

    /**
     * 清空并重置窗口 —— 「重新开始一轮记录」。
     *
     * 与 [clearAll] 的区别：本方法把窗口起点也刷新掉，
     * 便于「清空 → 复现操作 → 导出」这种最常用的排查流程。
     */
    fun restart() {
        clearAll()
        if (enabledFlag) {
            windowStartAt = System.currentTimeMillis()
            sessionCount++
        }
    }

    /**
     * 按域导出。**只返回有内容的域**。
     *
     * @param full `true` = 带完整信息（用户显式选择）。默认脱敏。
     * @param filesRoot 用于抹去应用私有目录前缀；`null` 则只做哈希脱敏。
     * @param domains 只导出这些域；`null` = 全部。
     * @return 域 → 文本（含表头：域标签、条数、窗口起点）。
     */
    fun dump(
        full: Boolean = false,
        filesRoot: String? = null,
        domains: List<XDomain>? = null,
    ): Map<XDomain, String> {
        val selected = (domains ?: XDomain.entries).filter { countOf(it) > 0 }
        return selected.associateWith { domain ->
            val redact: (String) -> String = { XRedaction.redact(it, filesRoot, full) }
            buildString {
                append("# ").append(domain.label).append("（").append(domain.key).append("）\n")
                append("# 条数: ").append(countOf(domain)).append('\n')
                append("# 窗口起点: ").append(timeTextOf(windowStartAt)).append('\n')
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

    private fun timeTextOf(atMillis: Long?): String =
        if (atMillis == null) "(未开启过)" else XLogRing.timeText(atMillis)
}
