// [X-custom] RikkaHub-X 定制(merge 上游时保留): UTF-16 安全切割
// .x 独立新文件(me.rerere.rikkahub.x.compress),上游无此文件,merge 零冲突。
// 移植自 kelivo lib/utils/utf16_safe_cut.dart。
//
// 背景:上游 UIMessage.summaryAsText 的截断是 `text.take(maxLength)`,按 **UTF-16 码元**
// 计数。当截断点恰好落在代理对(emoji / 增补平面字符)中间时,会产出孤立代理字符
// (lone surrogate),后续 JSON 序列化送供应商 API 时可能被拒或乱码。
// 本文件提供按「码点」边界对齐的切割,供压缩链使用(不改上游 ai 模块)。
package me.rerere.rikkahub.x.compress

private fun isHighSurrogate(c: Char): Boolean = c.code in 0xD800..0xDBFF

private fun isLowSurrogate(c: Char): Boolean = c.code in 0xDC00..0xDFFF

/**
 * 求「前段安全结束位置」:若 [end] 恰好切在代理对中间,则回退 1 位。
 * 返回 0..value.length 闭区间内的值。
 */
fun utf16SafeHeadEnd(value: String, end: Int): Int {
    if (end <= 0) return 0
    if (end >= value.length) return value.length
    if (isHighSurrogate(value[end - 1]) && isLowSurrogate(value[end])) return end - 1
    return end
}

/**
 * 求「后段安全起始位置」:若 [start] 恰好切在代理对中间,则前进 1 位。
 * 返回 0..value.length 闭区间内的值。
 */
fun utf16SafeTailStart(value: String, start: Int): Int {
    if (start <= 0) return 0
    if (start >= value.length) return value.length
    if (isHighSurrogate(value[start - 1]) && isLowSurrogate(value[start])) return start + 1
    return start
}

/** 从头部截断到 [maxLength] 码元以内,且不切断代理对。 */
fun truncateHeadUtf16Safe(value: String, maxLength: Int): String {
    if (maxLength <= 0) return ""
    if (value.length <= maxLength) return value
    var end = utf16SafeHeadEnd(value, maxLength)
    // 首字符就是代理对时回退到 0 会把整段内容丢成空串;宁可多留一个代理对(1 码元)
    // 也不返回空串 —— 调用点的预算(数万字符)远大于 1,这点溢出无实际影响。
    if (end == 0 && value.length >= 2 && isHighSurrogate(value[0]) && isLowSurrogate(value[1])) {
        end = 2
    }
    return value.substring(0, end)
}

/** 从尾部截断到 [maxLength] 码元以内,且不切断代理对。 */
fun truncateTailUtf16Safe(value: String, maxLength: Int): String {
    if (maxLength <= 0) return ""
    if (value.length <= maxLength) return value
    return value.substring(utf16SafeTailStart(value, value.length - maxLength))
}

/**
 * 二分:返回长度尽量均等且不切断代理对的两段;无法安全二分时返回 null。
 */
fun splitUtf16SafeHalves(value: String): Pair<String, String>? {
    if (value.length < 2) return null
    val mid = utf16SafeTailStart(value, value.length / 2)
    if (mid <= 0 || mid >= value.length) return null
    return value.substring(0, mid) to value.substring(mid)
}

/**
 * 按 [maxLength] 码元切成多段,段边界一律不切断代理对。
 * 用于超大文本送模型前分段(每段再单独走压缩,避免单请求超上下文)。
 */
fun splitUtf16SafeChunks(value: String, maxLength: Int): List<String> {
    if (maxLength <= 0) return emptyList()
    if (value.isEmpty()) return emptyList()
    if (value.length <= maxLength) return listOf(value)
    val chunks = ArrayList<String>(value.length / maxLength + 1)
    var start = 0
    while (start < value.length) {
        val end = utf16SafeHeadEnd(value, minOf(start + maxLength, value.length))
        if (end <= start) break
        chunks.add(value.substring(start, end))
        start = end
    }
    return chunks
}
