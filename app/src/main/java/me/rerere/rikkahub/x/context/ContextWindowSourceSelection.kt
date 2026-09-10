// [X-custom] RikkaHub-X 定制(merge 上游时保留): 多源候选择新
// .x 独立新文件,上游无此文件,merge 零冲突。
//
// 为什么需要"择新"而不是"先试主源、失败再试备源":
// 各 CDN 缓存的是同一条分支,刷新时刻**不同步**。实测(2026-09-10)推完 main 后,
// raw.githubusercontent.com 仍在返回上一版(边缘缓存未过期,HTTP 仍是 200),
// 而 raw.githack.com / cdn.statically.io 已是新内容。串行回退在这种情形下拿到的
// 恰恰是**旧表** —— 因为主源"成功"了。成功返回旧内容,是最坏的一种成功。
//
// 故:全部拉取 → 各自校验 → 取最新的一版。
package me.rerere.rikkahub.x.context

/**
 * 一个已通过校验的候选:来自哪个源、原文是什么、表长什么样。
 *
 * 带上 [raw] 是因为选中后要落盘的正是它 —— 不能事后重新序列化,否则会丢掉表里的
 * 未知字段(parse 时 `ignoreUnknownKeys`,序列化回来就没了)。
 */
data class TableCandidate(
    val source: String,
    val raw: String,
    val table: ContextWindowTable,
)

/** 多源择新的结果。 */
sealed interface SelectionOutcome {
    /** 采用这一版。 */
    data class Use(val candidate: TableCandidate) : SelectionOutcome

    /** 各源可达但都比本地旧,保持本地不动。 */
    data object KeepLocal : SelectionOutcome

    /** 没有任何可用候选(全不可达,或拿到的都不是本表)。 */
    data object NoCandidate : SelectionOutcome
}

/**
 * 按表内 `updatedAt` 比较两版数据的新旧:>0 表示 [a] 更新,<0 表示 [b] 更新,0 表示无法区分。
 *
 * 用**语义时间**比较,不用文本哈希:CDN 会改写格式(压缩/重排缩进),同一份数据的字节
 * 哈希并不相同,拿哈希比会把"同一份数据"误判成"两源不一致"。
 * `updatedAt` 无法解析的一侧视为更旧。
 */
fun compareTableFreshness(a: ContextWindowTable, b: ContextWindowTable): Int {
    val fa = parseTableUpdatedAt(a.updatedAt)
    val fb = parseTableUpdatedAt(b.updatedAt)
    return when {
        fa != null && fb != null -> fa.compareTo(fb)
        fa != null -> 1
        fb != null -> -1
        else -> 0
    }
}

/**
 * 从各源候选中取最新的一版;平局保留靠前的([candidates] 顺序即优先级)。
 * 泛型是为了让调用方还能取回候选自身(原文),而不是只剩一张表。
 */
fun <T> newestByTable(candidates: List<T>, tableOf: (T) -> ContextWindowTable): T? {
    if (candidates.isEmpty()) return null
    var best = candidates.first()
    for (candidate in candidates.drop(1)) {
        // 严格更新才替换 —— 平局时保留靠前的
        if (compareTableFreshness(tableOf(candidate), tableOf(best)) > 0) best = candidate
    }
    return best
}

/** 各候选是否都指向**同一版**数据。比对 `updatedAt` 而非字节:CDN 会改写格式。 */
fun <T> allSameVersion(candidates: List<T>, tableOf: (T) -> ContextWindowTable): Boolean {
    if (candidates.isEmpty()) return true
    val first = tableOf(candidates.first())
    return candidates.all { compareTableFreshness(tableOf(it), first) == 0 }
}

/**
 * 择新并决定怎么处置 —— 本文件的核心,纯函数,便于单测。
 *
 * @param candidates 各源已通过校验的候选
 * @param local 本地现有表;为 null 表示本地无表,任何候选都可接受
 */
fun selectTableSource(
    candidates: List<TableCandidate>,
    local: ContextWindowTable? = null,
): SelectionOutcome {
    val best = newestByTable(candidates) { it.table } ?: return SelectionOutcome.NoCandidate

    val current = local ?: return SelectionOutcome.Use(best)
    if (compareTableFreshness(best.table, current) >= 0) return SelectionOutcome.Use(best)

    // 走到这里:各源都比本地旧。两种可能,处置相反 ——
    //   ① 远端确实被回退过(改错后 revert):应接受,否则会抱着已撤销的数据不放;
    //   ② 本地是从某个源取到的新版,而可达的源都还滞后:必须拒绝,否则新数据被旧数据盖掉。
    //
    // 判据:回退会让**所有**可达源一致指向同一个更旧的版本;滞后只会让**部分**源落后。
    // 因此要求至少两个源相互印证才认定为回退 —— 只有单个源可达时无从印证,一律按 ② 处理,
    // 保住手上更新的数据(这正是 raw 单独可达且滞后时会发生的情形)。
    val corroborated = candidates.size >= 2 && allSameVersion(candidates) { it.table }
    return if (corroborated) SelectionOutcome.Use(best) else SelectionOutcome.KeepLocal
}
