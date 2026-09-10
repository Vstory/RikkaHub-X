// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文容量表解析与查找
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
// 数据来源:**仅**远端 model-contexts/context-windows.json(App 侧不内置任何表,只带解释器)。
//
// 表为**自描述**:interpretation 块声明「该怎么被读」(strategy 枚举 / lookupOrder / 开关 / TTL),
// 故换策略、换顺序、改开关**无需发版**;App 只实现枚举对应的策略。
package me.rerere.rikkahub.x.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 容量值的合法区间。越界条目会被**丢弃**(而非钳制):钳制会把明显的错值变成一个"看起来正常"的错值。 */
const val MIN_CONTEXT_WINDOW: Int = 1_024
const val MAX_CONTEXT_WINDOW: Int = 100_000_000

/**
 * 表自描述块 —— 「这份表该怎么被读」。
 *
 * 放在**数据文件内部**(而非另起一个规则文件)是关键:配置与数据同一份文件 → **永不版本错配**,
 * 且只需一次请求。
 */
@Serializable
data class ContextWindowInterpretation(
    /** 匹配策略。App 只认 [ContextWindowInterpretation.STRATEGY_ORDERED_KEYWORD],其余一律拒表。 */
    val strategy: String = STRATEGY_ORDERED_KEYWORD,
    /** 查找阶段顺序。全部阶段见 [STAGES]。 */
    val lookupOrder: List<String> = listOf(STAGE_EXACT, STAGE_RULES, STAGE_DEFAULT),
    /** 匹配前是否剔除日期戳(`gpt-4o-2024-08-06` → `gpt-4o`)。 */
    val stripDateStamp: Boolean = true,
    /** 是否忽略大小写。 */
    val ignoreCase: Boolean = true,
    /** 客户端刷新 TTL(分钟)。 */
    val refreshIntervalMinutes: Int = DEFAULT_REFRESH_MINUTES,
) {
    companion object {
        const val STRATEGY_ORDERED_KEYWORD = "ordered-keyword"
        const val STAGE_EXACT = "exact"
        const val STAGE_RULES = "rules"
        const val STAGE_DEFAULT = "default"
        const val DEFAULT_REFRESH_MINUTES = 1440

        /** App 已实现的策略集合。 */
        val KNOWN_STRATEGIES = setOf(STRATEGY_ORDERED_KEYWORD)

        /** App 已知的查找阶段。 */
        val STAGES = setOf(STAGE_EXACT, STAGE_RULES, STAGE_DEFAULT)
    }
}

/**
 * 模型 → 上下文窗口容量(token)远端表结构,与仓库 model-contexts/context-windows.json 同构。
 *
 * 查找语义由 [interpretation] 声明,默认:models 精确 id → rules 按数组顺序首条结构匹配 →
 * defaultContextWindow(见 [lookup])。
 */
@Serializable
data class ContextWindowTable(
    val schemaVersion: Int = SUPPORTED_SCHEMA_VERSION,
    val updatedAt: String = "",
    val interpretation: ContextWindowInterpretation = ContextWindowInterpretation(),
    val defaultContextWindow: Int? = null,
    val models: List<ContextWindowExact> = emptyList(),
    val rules: List<ContextWindowRule> = emptyList(),
) {
    companion object {
        /** App 支持的 schema 版本。远端推来更高版本 → 拒表(不猜测其语义)。 */
        const val SUPPORTED_SCHEMA_VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 解析并校验远端文本。
         *
         * 分两级处理,原则是「宁可少用,不可用错」:
         * - **拒整表**([ParseResult.Rejected]):JSON 不合法、schemaVersion 过高、strategy 未实现、
         *   lookupOrder 含未知阶段、表内容为空。调用方据此**保留上一份好表**。
         * - **丢条目**([ParseResult.Ok] 但已裁剪):单条 keywords 为空、数值越界、id 为空。
         *   这类错误是局部的,不该牵连整张表。
         */
        fun parse(text: String): ParseResult {
            val table = runCatching { json.decodeFromString<ContextWindowTable>(text) }.getOrNull()
                ?: return ParseResult.Rejected("JSON 解析失败")

            if (table.schemaVersion !in 1..SUPPORTED_SCHEMA_VERSION) {
                return ParseResult.Rejected("schemaVersion=${table.schemaVersion} 不受支持")
            }
            val interpretation = table.interpretation
            if (interpretation.strategy !in ContextWindowInterpretation.KNOWN_STRATEGIES) {
                return ParseResult.Rejected("strategy=${interpretation.strategy} 未实现")
            }
            if (interpretation.lookupOrder.isEmpty() ||
                interpretation.lookupOrder.any { it !in ContextWindowInterpretation.STAGES }
            ) {
                return ParseResult.Rejected("lookupOrder 含未知阶段:${interpretation.lookupOrder}")
            }

            val models = table.models.filter { it.id.isNotBlank() && it.contextWindow.isValidCapacity() }
            val rules = table.rules.filter { r ->
                r.keywords.isNotEmpty() && r.keywords.none { it.isBlank() } && r.contextWindow.isValidCapacity()
            }
            val default = table.defaultContextWindow?.takeIf { it.isValidCapacity() }

            if (models.isEmpty() && rules.isEmpty() && default == null) {
                return ParseResult.Rejected("表内容为空或全部条目越界")
            }
            return ParseResult.Ok(
                table.copy(models = models, rules = rules, defaultContextWindow = default)
            )
        }
    }
}

/** [ContextWindowTable.parse] 的结果。 */
sealed interface ParseResult {
    /** 校验通过。[table] 可能已裁掉非法条目,但整体可信。 */
    data class Ok(val table: ContextWindowTable) : ParseResult

    /** 整表不可用 —— 调用方应**保留上一份好表**,而不是退回默认策略去解释它。 */
    data class Rejected(val reason: String) : ParseResult
}

private fun Int.isValidCapacity(): Boolean = this in MIN_CONTEXT_WINDOW..MAX_CONTEXT_WINDOW

@Serializable
data class ContextWindowExact(val id: String = "", val contextWindow: Int = 0, val note: String = "")

@Serializable
data class ContextWindowRule(val keywords: List<String> = emptyList(), val contextWindow: Int = 0, val note: String = "")

/**
 * 匹配时的开关,由 `interpretation` 决定。
 *
 * 默认值即现行语义;改动它们会改变命中结果,故与策略一同写在数据文件里、随数据一起演进。
 */
private class MatchParams(val stripDateStamp: Boolean, val ignoreCase: Boolean) {
    companion object {
        fun of(interpretation: ContextWindowInterpretation) =
            MatchParams(interpretation.stripDateStamp, interpretation.ignoreCase)
    }
}

/**
 * 训练语料里的「日期戳」:2024-08-06 / 20241022 / 2024_08_06 等。
 *
 * 先剔除再匹配:OpenAI 风格的 `gpt-4o-2024-08-06` 剥掉日期后才等价于 `gpt-4o`,
 * 否则日期里的数字可能被当成版本号命中 `["gpt","6"]` 这类规则。
 */
private val DATE_STAMP = Regex("""\d{4}[-_/.]?\d{2}[-_/.]?\d{2}""")

/** 版本号里连接各组件的分隔符 —— `4.6`(点)与 `4-6`(连字符)都是真实存在的写法。 */
private fun isVersionSeparator(ch: Char): Boolean = ch == '.' || ch == '-' || ch == '_'

/**
 * 归一化:按开关剔除日期戳 → 按开关小写。
 *
 * **保留分隔符** —— 分隔符是版本组件与数字段的天然边界,不能剥掉。
 */
private fun normalize(raw: String, params: MatchParams): String {
    var text = raw
    if (params.stripDateStamp) text = text.replace(DATE_STAMP, "")
    return if (params.ignoreCase) text.lowercase() else text
}

/**
 * 规则的匹配单元。
 *
 * - [literal] 型号名文字段(如 `gpt`/`sonnet`),在 id 中按顺序寻找即可;
 * - [version] 版本组:连续的纯数字 keyword 合并而成(如 `["4","6"]` → `4.6`),
 *   必须整体对齐 id 里的一个版本组。
 */
private class Step private constructor(val literal: String?, val version: List<String>?) {
    companion object {
        private val TRIM = charArrayOf('-', '_', '.', '/', ' ')

        fun of(keywords: List<String>, params: MatchParams): List<Step> {
            val steps = mutableListOf<Step>()
            var i = 0
            while (i < keywords.size) {
                val word = normalize(keywords[i], params).trim(*TRIM)
                if (word.isEmpty()) {
                    i++
                    continue
                }
                if (word.all { it.isDigit() }) {
                    // 连续的数字 keyword 视为**同一个版本**的各个组件
                    val components = mutableListOf<String>()
                    while (i < keywords.size) {
                        val next = normalize(keywords[i], params).trim(*TRIM)
                        if (next.isEmpty() || !next.all { it.isDigit() }) break
                        components += next
                        i++
                    }
                    steps += Step(null, components)
                } else {
                    steps += Step(word, null)
                    i++
                }
            }
            return steps
        }
    }
}

/**
 * 从 [start] 读取一个版本组,返回结束下标(不含)。
 *
 * 组件间分隔符仅在后一个字符也是数字时才并入,避免把 `qwen3.7-max` 的 `-max` 吃掉。
 * 不是数字时返回 -1。
 */
private fun readVersionEnd(text: String, start: Int): Int {
    if (start >= text.length || !text[start].isDigit()) return -1
    var i = start
    while (i < text.length) {
        if (text[i].isDigit()) {
            i++
            continue
        }
        if (isVersionSeparator(text[i]) && i + 1 < text.length && text[i + 1].isDigit()) {
            i++
            continue
        }
        break
    }
    return i
}

/**
 * 从 [from] 起寻找第一个「组件以 [spec] 开头」的版本组,返回其结束下标(不含)。
 *
 * 版本可以隔若干文字出现(`doubao-seed-2.1` 的 `2.1` 在 `seed` 之后),故向前扫描;
 * id 带 `v` 前缀(`mimo-v2.5`)时跳过该字母。
 */
private fun findVersionEnd(text: String, spec: List<String>, from: Int): Int? {
    var i = from
    while (i < text.length) {
        var at = i
        if (text[at] == 'v' && at + 1 < text.length && text[at + 1].isDigit()) at++
        val end = readVersionEnd(text, at)
        if (end > at) {
            val components = text.substring(at, end).split('.', '-', '_')
            if (spec.size <= components.size && spec.indices.none { components[it] != spec[it] }) return end
            i = end
        } else {
            i++
        }
    }
    return null
}

/**
 * 判断 [keywords] 是否与 [haystack] 结构匹配。
 *
 * 相比旧的「每个 keyword 各自是子串」,这里收紧了两点:
 * 1. **按顺序**匹配、互不重叠 —— `claude-3-5-sonnet` 里 "sonnet" 之后没有 "5",
 *    故不命中 `["claude","sonnet","5"]`;`claude-sonnet-5` 命中。
 * 2. **数字 keyword 合并为版本组并整体对齐** —— `["claude","sonnet","5"]` 要求
 *    「版本 = 5」,而 `claude-sonnet-4.5` 的版本是 `4.5`、首个组件是 4,不命中。
 *    这消除了「3.5 被当成 Sonnet 5」「4.5 被当成 Sonnet 5」这类版本错位。
 *
 * 版本规格取版本组组件的**前缀**即可:`["claude","opus","4"]` 命中 `claude-opus-4.6`。
 */
private fun matchesInOrder(haystack: String, keywords: List<String>, params: MatchParams): Boolean {
    var pos = 0
    for (step in Step.of(keywords, params)) {
        val literal = step.literal
        if (literal != null) {
            val at = haystack.indexOf(literal, pos)
            if (at < 0) return false
            pos = at + literal.length
            continue
        }
        val spec = step.version ?: continue
        pos = findVersionEnd(haystack, spec, pos) ?: return false
    }
    return true
}

/**
 * 按 modelId 查容量。命中返回容量值,未命中返回 null(调用方据此隐藏圆环)。
 *
 * 阶段顺序取自 `interpretation.lookupOrder`(默认 exact → rules → default),
 * 各阶段的语义不变:exact 要求 id 按开关比较相等,rules 要求 keywords 与 id **结构匹配**
 * (见 [matchesInOrder])。
 */
fun ContextWindowTable.lookup(modelId: String): Int? {
    if (modelId.isBlank()) return null
    val interpretation = this.interpretation
    val params = MatchParams.of(interpretation)
    val norm = normalize(modelId, params)
    for (stage in interpretation.lookupOrder) {
        when (stage) {
            ContextWindowInterpretation.STAGE_EXACT ->
                models.firstOrNull { it.id.equals(modelId, ignoreCase = params.ignoreCase) }
                    ?.let { return it.contextWindow }

            ContextWindowInterpretation.STAGE_RULES ->
                // keywords 为空时必须跳过:Step.of 对空列表返回空步骤序列,matchesInOrder 会**恒真**,
                // 一条畸形规则就能把整张表吞成同一个值。parse 阶段已过滤,这里再兜一层(直接构造的表也走这里)。
                rules.firstOrNull { it.keywords.isNotEmpty() && matchesInOrder(norm, it.keywords, params) }
                    ?.let { return it.contextWindow }

            ContextWindowInterpretation.STAGE_DEFAULT ->
                defaultContextWindow?.let { return it }
        }
    }
    return null
}
