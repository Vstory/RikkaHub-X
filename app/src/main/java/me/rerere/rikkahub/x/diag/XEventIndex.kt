// [X-custom] RikkaHub-X 诊断框架:从数据里生成「关键词索引」(纯逻辑)
package me.rerere.rikkahub.x.diag

/**
 * 从导出的记录里**数出**「这里面有哪些事件名」——
 * 也就是用户点着要的那个东西:「**搜关键词就能定位**」。
 *
 * ## 为什么是「从数据里数」,而不是维护一份事件名清单
 *
 * 清单会**过期**,而且过期得很安静:加了新事件忘了登记,索引里就没有它 ——
 * 而这一页最不该出现的正是「照索引搜,搜不到」。
 *
 * 从数据里数则**天然完整**:包里有哪些名字,索引里就列哪些,一个不多一个不少。
 * 代价是它只反映**本次包里实际出现过的**名字 —— 而那恰恰是读者要问的问题
 * (「这份包里我能搜什么」),不是「代码里定义了哪些」(那个问题归源码与单测)。
 *
 * ⚠️ 反过来说:某一类事件一次都没触发过,索引里就不会出现。
 * 这不是缺陷 —— 它没触发,你也搜不到东西。
 *
 * ## 为什么按域分组
 *
 * 域是「哪个功能」,而事件名的首段**并不等于**域名(存储域的事件名一律以 `asset.`
 * 开头,而域键是 `storage`)。故分组只能靠每行的 `domain` 字段,不能靠名字前缀猜。
 *
 * ## 纯逻辑
 *
 * 不碰文件、不碰 Android:输入是若干行文本,输出是索引。故它能在 JVM 里被逐项验。
 */
object XEventIndex {

    /** 索引里最多列多少条。超过就截断并**写明截断了多少** —— 不静默截断。 */
    const val MAX_ENTRIES = 100

    /**
     * 一个事件名的键:域 + 事件名。
     *
     * 用一对字段而不是拼接成可读的 `域.事件名`:后者可能与事件名本身撞车(事件名含点),
     * 而这里只需要一个**稳定的身份**。
     */
    private data class Key(val domain: String, val event: String)

    /**
     * 增量计数器。
     *
     * **为什么不是 `index(lines: Iterable)` 就够**:调用方在**边读文件边算**
     * (预扫那一遍还要同时数脱敏命中数),把整个文件读成 List 只为数索引会让内存
     * 占用与文件大小挂钩 —— 而打包这一层刻意做到「几百 MB 也压得动」。
     */
    class Counter {
        private val counts = LinkedHashMap<Key, Long>()

        /** 喂一行。非 JSON 行、缺字段行一律**跳过** —— 它们不是错,只是不是事件。 */
        fun add(line: String) {
            parse(line)?.let { key -> counts[key] = (counts[key] ?: 0L) + 1L }
        }

        /**
         * 出结果:域按**首次出现**顺序,域内事件名**按名字排序**。
         *
         * 排序让两次导出可以逐行对照(顺序稳定);域的顺序保持出现次序,
         * 便于读者按「哪个功能先动起来」扫一遍。
         */
        fun result(): Map<String, Map<String, Long>> {
            val byDomain = LinkedHashMap<String, MutableMap<String, Long>>()
            counts.forEach { (key, n) ->
                byDomain.getOrPut(key.domain) { LinkedHashMap() }[key.event] = n
            }
            return byDomain.mapValues { (_, events) -> events.toSortedMap() }
        }
    }

    /** 从若干行里数出索引。等价于 [Counter] 的便捷写法。 */
    fun index(lines: Iterable<String>): Map<String, Map<String, Long>> {
        val counter = Counter()
        lines.forEach(counter::add)
        return counter.result()
    }

    /**
     * 从一行里取 (domain, event)。取不到返回 `null`。
     *
     * ⚠️ 刻意**不用 JSON 解析器**:这一层要能处理「日志被外部工具截断/拼接」后留下的
     * 畸形行,而解析器遇到畸形行会抛异常 —— 为了一个索引把整个导出弄崩,不值得。
     * 故用最笨的引号内取值:能取到就用,取不到就跳过。
     */
    private fun parse(line: String): Key? {
        val domain = field(line, "domain") ?: return null
        val event = field(line, "event") ?: return null
        if (domain.isEmpty() || event.isEmpty()) return null
        return Key(domain, event)
    }

    /** 取 `"name":"value"` 里的 value。值里的转义不处理 —— 事件名与域名都不含转义字符。 */
    private fun field(line: String, name: String): String? {
        val marker = "\"$name\":\""
        val start = line.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = line.indexOf('"', from)
        if (end < 0) return null
        return line.substring(from, end)
    }

    /**
     * 渲染成清单里那一段。**纯函数**,便于单测(这份文本是读者按图索骥的唯一指引)。
     *
     * @param index [index] 的结果。
     * @return 空索引返回 `null` —— 调用方据此**整段不写**,而不是写一个空标题
     *   (空标题会让读者以为索引坏了)。
     */
    fun render(index: Map<String, Map<String, Long>>): String? {
        if (index.isEmpty()) return null
        val sb = StringBuilder()
        var shown = 0
        var total = index.values.sumOf { it.size }

        sb.appendLine("  These are the event names that ACTUALLY OCCURRED in this bundle.")
        sb.appendLine("  Search for one to jump straight at that behaviour -- they are the fastest")
        sb.appendLine("  way in, and the list is generated from the data, so it cannot be stale.")
        sb.appendLine("  (An event that never fired is absent on purpose: there is nothing to find.)")
        sb.appendLine()

        outer@ for ((domain, events) in index) {
            if (shown >= MAX_ENTRIES) break
            sb.appendLine("    domain=$domain")
            for ((event, n) in events) {
                if (shown >= MAX_ENTRIES) break@outer
                sb.appendLine("      ${event.padEnd(34)} $n")
                shown++
            }
        }
        if (shown < total) {
            // 截断必须**可见** —— 否则读者会以为这里就是全部,于是「搜不到」时不会怀疑索引。
            sb.appendLine("      (+${total - shown} more event name(s) not listed)")
        }
        return sb.toString().trimEnd()
    }
}
