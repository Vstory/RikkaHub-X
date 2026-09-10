// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文容量表解析与查找
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
// 数据来源:远端 model-contexts/context-windows.json(schema v1),assets 内置同构文件兜底。
package me.rerere.rikkahub.x.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 模型 → 上下文窗口容量(token)远端表结构,与仓库 model-contexts/context-windows.json 同构。
 * 查找语义:① models 精确 id(忽略大小写) → ② rules 按数组顺序、首条 keywords 全命中即用 → ③ defaultContextWindow。
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
 * 必须**先于**分隔符剥离处理:OpenAI 风格的 `gpt-4o-2024-08-06` 剥掉日期后
 * 才等价于 `gpt-4o`,否则日期里的 6/1 会被当成版本号命中 `["gpt","6"]`
 * 这类规则(实测把 128K 的 gpt-4o 报成 1.05M)。
 */
private val DATE_STAMP = Regex("""\d{4}[-_/.]?\d{2}[-_/.]?\d{2}""")

/**
 * 归一化 modelId/keyword:剔除日期戳 → 小写 → 去分隔符,便于模糊匹配。
 *
 * 已知局限:纯数字 keyword 仍可能与**版本号**里的数字撞车(如
 * `claude-3-5-sonnet` 里的 "5" 会让 `["claude","sonnet","5"]` 命中,
 * 报 1M 而实际 200K)。彻底解决需按 token 位置有序匹配或语义解析,
 * 超出本表「子串匹配」的设计范围,故暂按数据侧规避(规则从具体到泛化排序)。
 */
private fun normalize(raw: String): String =
    raw.lowercase()
        .replace(DATE_STAMP, "")
        .replace(Regex("[-_/.\\s]"), "")

/**
 * 按 modelId 查容量。命中返回容量值,未命中返回 null(调用方决定隐藏圆环)。
 * exact 命中要求 id 忽略大小写完全相等;rules 命中要求该条全部 keywords 都是 modelId 归一化后的子串。
 */
fun ContextWindowTable.lookup(modelId: String): Int? {
    if (modelId.isBlank()) return null
    models.firstOrNull { it.id.equals(modelId, ignoreCase = true) }?.let { return it.contextWindow }
    val norm = normalize(modelId)
    for (rule in rules) {
        if (rule.keywords.isNotEmpty() && rule.keywords.all { normalize(it) in norm }) {
            return rule.contextWindow
        }
    }
    return defaultContextWindow
}
