// [X-custom] RikkaHub-X 定制(与上游合并对照 X-CUSTOM.md 保留): 上下文容量表解析与查找
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

/** 归一化 modelId/keyword:小写 + 去分隔符,便于模糊匹配 */
private fun normalize(raw: String): String =
    raw.lowercase().replace(Regex("[-_/.\\s]"), "")

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
