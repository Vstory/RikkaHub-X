// [X-custom] RikkaHub-X 诊断框架:诊断页「事件」区的筛选(纯逻辑)
package me.rerere.rikkahub.x.diag

/**
 * 诊断页上「搜关键词」那条路的筛选规则 —— **纯逻辑**,不碰 Compose。
 *
 * ## 为什么它值得单独一个对象
 *
 * 用户对这一页的原话是「**搜关键词就能定位**」。而那件事的成败全在这几行规则上:
 * 搜什么字段、大小写算不算、筛了域之后搜索框还起不起作用 —— 每一处判错了都**不会报错**,
 * 只会表现为「明明有这条却搜不到」,而用户那时会怀疑**日志本身漏了**。
 * 故抽出来逐条钉住(见 `XEventFilterTest`)。
 *
 * ## 三条口径(都是刻意的)
 *
 * ① **搜索范围含域名与中文标签**。用户想「看存储那摊事」时,会直接打「存储」或 `storage`
 *    —— 那时他并不记得具体事件名。只搜 `event`/`msg` 会让他搜不到,而这与「没记录」长得一样。
 * ② **大小写不敏感**。事件名全是小写,而正文里不一定(`OkHttp`、`ANR`)。要求用户记住
 *    哪种大小写是没道理的。
 * ③ **域筛与关键字是「与」关系**,不是二选一 —— 先按域收窄、再在里面搜,是排查时的自然动作。
 *
 * ## 上限:只搜「最近这一批」
 *
 * 页面上的列表**不可能**把几万条全渲染出来,故调用方只喂最近的一批(见 [MAX_ROWS] 的注释)。
 * 这不是缺陷,但**必须让用户知道** —— 否则他搜不到早先那条时会以为日志漏了。
 * 故页面那边会显示一句「在最近 N 条里搜」。
 */
object XEventFilter {

    /**
     * 列表最多渲染这么多行。
     *
     * 300:一屏十几行、二十屏的量。再多的话,滚动本身就成了负担,而**要看全量时该用导出的包**
     * (那里一条不漏,还能交给 AI 读)。
     */
    const val MAX_ROWS = 300

    /**
     * 喂给筛选的**每域上限**。
     *
     * 内存环每域最多 2000 条,12 个域合起来两万多条 —— 每次刷新都把它们全过一遍是白费的
     * (列表最多显示 [MAX_ROWS] 条)。故只取每域最近这么多条,合起来 ≤ 4800 行,刷新一次可忽略。
     *
     * ⚠️ **这是一处取舍,不是疏漏**:它意味着「找很久以前的那条」在页面上可能搜不到。
     * 故页面必须把那句话显示出来(见类注释末段)。
     */
    const val PER_DOMAIN_TAKE = 400

    /** 一行:域 + 该域里的一条。**因为 Entry 本身不带域**,而列表要按域显示与筛选。 */
    data class Row(val domain: XDomain, val entry: XLogRing.Entry)

    /**
     * 筛出要显示的行,**保持输入顺序**。
     *
     * @param rows 调用方按**最新在前**排好 —— 这个函数只管筛,不管序(排序是呈现的事,
     *   放在这里会让「筛」与「排」两件事纠缠,测试也不好读)。
     * @param query 关键字;空白视为「不筛」。
     * @param only 只看这个域。**默认 `null` = 全部** —— 「不筛域」本来就是最常见的用法,
     *   故给它默认值而不是要求每个调用点都写一遍 `null`。
     *   (⚠️ 这个默认值是补上的:首版没有,于是三个「只给关键字」的调用点编译不过 ——
     *    而那三个调用点写的正是最自然的读法。参数该不该有默认值,看的是**最常见的用法**,
     *    不是「能不能省」。)
     */
    fun apply(rows: List<Row>, query: String, only: XDomain? = null): List<Row> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty() && only == null) return rows
        return rows.filter { row ->
            (only == null || row.domain == only) &&
                (needle.isEmpty() || matches(row, needle))
        }
    }

    private fun matches(row: Row, needle: String): Boolean =
        row.entry.event.lowercase().contains(needle) ||
            row.entry.message.lowercase().contains(needle) ||
            row.domain.key.lowercase().contains(needle) ||
            row.domain.label.lowercase().contains(needle)

    /**
     * 造出最新在前的行列表,每域只取最近 [PER_DOMAIN_TAKE] 条。
     *
     * 放在这里(而不是页面里)是为了**能被单测**:「每域取多少、怎么合并、顺序对不对」
     * 这三件事都可能写错,而错了只表现为「少了几条」或「顺序看着别扭」。
     *
     * @param entriesOf 取某域的全部记录(**由旧到新**,即 `XLogRing.recent()` 的口径)。
     */
    fun buildRows(entriesOf: (XDomain) -> List<XLogRing.Entry>): List<Row> {
        val rows = ArrayList<Row>()
        XDomain.entries.forEach { domain ->
            val recent = entriesOf(domain)
            // 由旧到新 → 取**末尾**那一段(最近的),再整体反转成最新在前。
            recent.takeLast(PER_DOMAIN_TAKE).asReversed().forEach { rows += Row(domain, it) }
        }
        // 各域内部已经是新→旧,但域与域之间还没交错。整体按时间再排一遍,
        // 否则列表会「先把 chat 的全列完再列 storage 的」—— 而这一页要的正是**一条时间线**。
        return rows.sortedByDescending { it.entry.at }
    }
}
