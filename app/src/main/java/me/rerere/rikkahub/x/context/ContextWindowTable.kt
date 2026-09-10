// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文容量表解析与查找
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
// 数据来源:远端 model-contexts/context-windows.json(schema v1),assets 内置同构文件兜底。
package me.rerere.rikkahub.x.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 模型 → 上下文窗口容量(token)远端表结构,与仓库 model-contexts/context-windows.json 同构。
 * 查找语义:① models 精确 id(忽略大小写) → ② rules 按数组顺序、首条 keywords **按顺序**全部命中即用 → ③ defaultContextWindow。
 */
@Serializable
data class ContextWindowTable(
    val schemaVersion: Int = 1,
    val updatedAt: String = "",
    val defaultContextWindow: Int? = null,
    val models: List<ContextWindowExact> = emptyList(),
    val rules: List<ContextWindowRule> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ContextWindowTable? = runCatching { json.decodeFromString<ContextWindowTable>(text) }.getOrNull()
    }
}

@Serializable
data class ContextWindowExact(val id: String = "", val contextWindow: Int = 0, val note: String = "")

@Serializable
data class ContextWindowRule(val keywords: List<String> = emptyList(), val contextWindow: Int = 0, val note: String = "")

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
 * 归一化:剔除日期戳 → 小写。
 *
 * **保留分隔符** —— 分隔符是版本组件与数字段的天然边界,不能剥掉。
 */
private fun normalize(raw: String): String = raw.lowercase().replace(DATE_STAMP, "")

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

        fun of(keywords: List<String>): List<Step> {
            val steps = mutableListOf<Step>()
            var i = 0
            while (i < keywords.size) {
                val word = normalize(keywords[i]).trim(*TRIM)
                if (word.isEmpty()) {
                    i++
                    continue
                }
                if (word.all { it.isDigit() }) {
                    // 连续的数字 keyword 视为**同一个版本**的各个组件
                    val components = mutableListOf<String>()
                    while (i < keywords.size) {
                        val next = normalize(keywords[i]).trim(*TRIM)
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
private fun matchesInOrder(haystack: String, keywords: List<String>): Boolean {
    var pos = 0
    for (step in Step.of(keywords)) {
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
 * 按 modelId 查容量。命中返回容量值,未命中返回 null(调用方决定隐藏圆环)。
 *
 * exact 命中要求 id 忽略大小写完全相等;rules 命中要求该条 keywords 与 id **结构匹配**
 * (见 [matchesInOrder])。
 */
fun ContextWindowTable.lookup(modelId: String): Int? {
    if (modelId.isBlank()) return null
    models.firstOrNull { it.id.equals(modelId, ignoreCase = true) }?.let { return it.contextWindow }
    val norm = normalize(modelId)
    for (rule in rules) {
        if (rule.keywords.isNotEmpty() && matchesInOrder(norm, rule.keywords)) {
            return rule.contextWindow
        }
    }
    return defaultContextWindow
}
