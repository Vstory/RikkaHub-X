// [X-custom] RikkaHub-X 定制(merge 上游时保留): 压缩文本分块与多层合并
// .x 独立新文件(me.rerere.rikkahub.x.compress),上游无此文件,merge 零冲突。
// 移植自 kelivo lib/core/models/compress_context_options.dart
//   conversationLineForCompression / chunkMessagesForCompression / chunkPlainTexts。
//
// 背景:上游按「消息条数」256 二分(splitMessages)分块,块大小与模型窗口无关;
// 且多块结果是 N 条独立摘要直接当 N 条 user 消息插入,**没有合并步骤** ——
// 压缩产物零散、可能互相矛盾,也没法收敛成一段可读的上下文。
// 本文件按字符预算打包(优先消息边界),并提供摘要的多层合并(merge)打包。
package me.rerere.rikkahub.x.compress

import me.rerere.ai.ui.UIMessage

/** 单条消息在压缩输入里的行文本上限(与上游 summaryAsText 取值保持一致)。 */
const val COMPRESS_LINE_MAX_CHARS: Int = 2000

/** 摘要合并的最大轮数(防止病态输入下无限合并)。 */
const val MAX_MERGE_ROUNDS: Int = 8

/**
 * 把一条消息格式化为压缩输入行。
 *
 * 复用上游 [UIMessage.summaryAsText] 的格式(`[ROLE]: text`),
 * 但截断改走 UTF-16 安全路径,避免上游 `text.take(n)` 切出孤立代理字符。
 */
fun UiMessageCompressLine(message: UIMessage, maxChars: Int = COMPRESS_LINE_MAX_CHARS): String {
    val full = message.summaryAsText(Int.MAX_VALUE)
    if (full.length <= maxChars) return full
    return truncateHeadUtf16Safe(full, maxChars) + "..."
}

/**
 * 把消息按 [maxChars] 字符预算打包成若干块。
 *
 * 优先在消息边界断开;单条超预算的行做 UTF-16 安全切分(独占若干块)。
 * 与上游「按条数 256 二分」不同,这里的块大小由模型窗口反推的预算决定。
 */
fun chunkMessagesForCompress(
    messages: List<UIMessage>,
    maxChars: Int,
    lineMaxChars: Int = COMPRESS_LINE_MAX_CHARS,
): List<String> {
    if (maxChars <= 0) return emptyList()
    val chunks = ArrayList<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotEmpty()) {
            chunks.add(current.toString())
            current.clear()
        }
    }

    for (message in messages) {
        val line = UiMessageCompressLine(message, lineMaxChars)
        if (line.isBlank()) continue
        if (line.length > maxChars) {
            flush()
            chunks.addAll(splitUtf16SafeChunks(line, maxChars))
            continue
        }
        val extra = if (current.isEmpty()) line.length else line.length + 2
        if (current.isNotEmpty() && current.length + extra > maxChars) flush()
        if (current.isNotEmpty()) current.append("\n\n")
        current.append(line)
    }
    flush()
    return chunks
}

/**
 * 把已格式化文本(如各块摘要)按 [maxChars] 打包成若干组,用于多层合并。
 */
fun chunkPlainTexts(texts: List<String>, maxChars: Int): List<String> {
    if (maxChars <= 0) return emptyList()
    val chunks = ArrayList<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotEmpty()) {
            chunks.add(current.toString())
            current.clear()
        }
    }

    for (text in texts) {
        if (text.isEmpty()) continue
        if (text.length > maxChars) {
            flush()
            chunks.addAll(splitUtf16SafeChunks(text, maxChars))
            continue
        }
        val extra = if (current.isEmpty()) text.length else text.length + 2
        if (current.isNotEmpty() && current.length + extra > maxChars) flush()
        if (current.isNotEmpty()) current.append("\n\n")
        current.append(text)
    }
    flush()
    return chunks
}
