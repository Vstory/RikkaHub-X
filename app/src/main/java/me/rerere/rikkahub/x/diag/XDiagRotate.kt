// [X-custom] RikkaHub-X 诊断框架:旧会话目录的清理策略(纯逻辑)
package me.rerere.rikkahub.x.diag

/**
 * 旧会话目录该删哪些 —— **纯逻辑**,不碰文件系统。
 *
 * ## 为什么需要它
 *
 * 每次「开关关 → 开」都会新建一个 `session-<时间戳>` 目录,而**旧的一个都不删**。
 * 单片 logcat 8MB × 12 片,加上事件时间线,单次会话最坏能到 ~112MB —— 用户来回拨几次
 * 开关,磁盘就这么被吃掉几百 MB。而那个开关在诊断页上,拨它是很自然的动作。
 *
 * ## 为什么可以**收得比较紧**(两个上限)
 *
 * 一个容易被忽略的事实:**导出只取最新的那一个会话**(`XDiagSession.latest`)。
 * 更早的目录**根本不会被读**。故留着它们只有「万一以后做『选哪次会话』」这一点价值,
 * 而不是「现在能看到历史」。所以:
 *
 * | 上限 | 值 | 防的是 |
 * |---|---|---|
 * | 个数 | [KEEP_SESSIONS] | 拨开关的次数多(每次都会新建一个) |
 * | 总字节 | [MAX_SESSION_BYTES] | 单次会话录很久(撞到 logcat 分片上限) |
 *
 * 两个都要:只限个数的话,3 个满员会话仍是 ~336MB;只限字节的话,一堆几百 KB 的小会话
 * 也能堆成上千个目录(而每个目录的创建/列举都有成本)。
 *
 * ## 三条硬规则(判错了都不会报错,故值得单独钉住)
 *
 * ① **最新那个永不动** —— 它正在被写,而且导出只取它。删了就是当场把现场扔掉;
 * ② **超限时从最老的一头连着删** —— 不是「挑最大的删」:会话是按时间排的序列,
 *    留着「中间某个大会话」而删掉「它前面那个小会话」会让时间线出现空洞,而那种空洞
 *    在读者眼里看不出是删除造成的;
 * ③ **一个都不删时返回空**,而不是返回「除了最新以外的全部」—— 让调用方按「有没有要删的」
 *    决定是否记一条日志;返回空集就不吵。
 */
object XDiagRotate {

    /** 保留最近几个会话目录。 */
    const val KEEP_SESSIONS = 3

    /**
     * 全部会话目录的**总**体积上限。
     *
     * 256MB:按单次最坏 ~112MB 算,大约能留住两次满员会话 —— 而「上一次」正是唯一有
     * 实用价值的那一个(见类注释:导出只取最新)。再往上加,收益只是多留几个不会被读的目录。
     */
    const val MAX_SESSION_BYTES = 256L * 1024 * 1024

    /** 清理发生时记的事件名(三段点分隔)。 */
    const val ROTATE_EVENT = "diag.session.rotate"

    /** 一个会话目录的摘要。 */
    data class Entry(val name: String, val bytes: Long)

    /**
     * 该删哪些(返回**目录名**)。
     *
     * @param sessions 按名字**降序**(最新在前)。目录名带 `yyyyMMdd-HHmmss` 时间戳,
     *   故「按名字排」就是「按时间排」—— 这是 [XDiagSession] 当初选这个格式的附带好处。
     */
    fun selectToDelete(
        sessions: List<Entry>,
        keepCount: Int = KEEP_SESSIONS,
        maxBytes: Long = MAX_SESSION_BYTES,
    ): List<String> {
        // 一个都没有、或只剩一个:不动。见类注释规则 ①。
        if (sessions.size <= 1) return emptyList()
        // keepCount < 1 会让「最新那个也要删」——那是最坏的一种错,故直接不删。
        if (keepCount < 1) return emptyList()

        val kept = mutableListOf<Entry>()
        var accumulated = 0L
        var cutFrom = sessions.size

        for ((index, session) in sessions.withIndex()) {
            val overCount = kept.size >= keepCount
            // ⚠️ `kept.isNotEmpty()` 这个条件不能少:它保证**最新那个无论如何都被留下**,
            //    哪怕它自己就已经超过 maxBytes(那说明上限设小了,而不是该删现场)。
            val overBytes = kept.isNotEmpty() && accumulated + session.bytes > maxBytes
            if (overCount || overBytes) {
                cutFrom = index
                break
            }
            kept += session
            accumulated += session.bytes
        }

        // 从 cutFrom 起**连着**删到末尾(即最老的一批) —— 见类注释规则 ②。
        return sessions.drop(cutFrom).map { it.name }
    }
}
