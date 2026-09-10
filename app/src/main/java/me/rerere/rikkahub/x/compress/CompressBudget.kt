// [X-custom] RikkaHub-X 定制(与上游合并对照 X-CUSTOM.md 保留): 压缩请求字符预算
// .x 独立新文件(me.rerere.rikkahub.x.compress),上游无此文件,merge 零冲突。
// 移植自 kelivo lib/core/models/compress_context_options.dart::compressRequestCharBudget。
//
// 背景:上游 compressConversation 按「消息条数」分块(maxMessagesPerChunk = 256),
// 完全不看模型上下文窗口 —— 单块理论可达 256 × 2000 = 51.2 万字符,极易触发
// 供应商上下文超限错误,而上游对超限没有任何兜底(直接整次压缩失败)。
// 本文件按模型真实窗口反推「单请求字符预算」,把超限从"必然失败"变成"事先避免"。
package me.rerere.rikkahub.x.compress

object CompressBudget {
    /**
     * 单次压缩请求的硬上限(UTF-16 码元)。
     * 即便模型窗口 1M,也压在 10 万字符以内,避免 regex / JSON 编码 / AOT 解码爆量。
     */
    const val SAFE_REQUEST_CHARS: Int = 100_000

    /** 模型元数据缺失时假定的上下文窗口(保守下限,当前小模型普遍 ≥32k)。 */
    const val DEFAULT_CONTEXT_WINDOW_TOKENS: Int = 32_000

    /** 预留比例:给压缩 prompt 模板外壳 + 模型输出留出的空间。 */
    const val RESERVE_FRACTION: Double = 0.30

    /** 保守的 chars/token(CJK 偏重场景;与 kelivo 取值一致)。 */
    const val CHARS_PER_TOKEN: Double = 1.6

    /**
     * 计算单次压缩请求的字符预算。
     *
     * 公式:`window × (1 - reserve) × charsPerToken`,再与 [SAFE_REQUEST_CHARS] 取小。
     * 32k 窗口 → 22400 可用 token → 35840 字符;128k+ 窗口更大但仍受硬上限约束。
     *
     * @param contextWindowTokens 模型上下文窗口(token);null 或 ≤0 时按 [DEFAULT_CONTEXT_WINDOW_TOKENS] 处理
     */
    fun requestCharBudget(contextWindowTokens: Int?): Int {
        val window = contextWindowTokens?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
        val chars = (window * (1.0 - RESERVE_FRACTION) * CHARS_PER_TOKEN).toInt()
        if (chars < 1) return 1
        return minOf(chars, SAFE_REQUEST_CHARS)
    }

    /** 压缩文本的粗估 token 数(CJK 1.6 字符/token,其余 4 字符/token)。用于压缩前提示。 */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (rune in text.codePoints().toArray()) {
            if (rune in 0x2E80..0x9FFF || rune in 0xF900..0xFAFF || rune in 0xFF00..0xFFEF) cjk++ else other++
        }
        return ((cjk / CHARS_PER_TOKEN) + (other / 4.0)).toInt()
    }
}
