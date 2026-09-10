// [X-custom] RikkaHub-X 定制(与上游合并对照 X-CUSTOM.md 保留): 压缩超限识别与递归二分重试
// .x 独立新文件(me.rerere.rikkahub.x.compress),上游无此文件,merge 零冲突。
// 移植自 kelivo lib/core/models/compress_context_options.dart
//   isContextLengthError / summarizeWithContextRetry。
//
// 背景:上游 compressConversation 对「供应商上下文超限」没有任何兜底 —— generateText
// 抛错后整次压缩直接失败(用户只看到失败 Toast,长对话永远压不动)。
// 本文件把超限错误识别出来,二分切分后分别压缩、再把两段摘要合并,最多重试 maxSplits 次;
// 这样长对话压缩从"必然失败"变为"自动降级到能成功为止"。
package me.rerere.rikkahub.x.compress

import kotlinx.coroutines.CancellationException

/**
 * 供应商上下文窗口/提示词过长错误的保守识别短语。
 *
 * 只匹配**强指向输入超限**的短语;泛化的 max_tokens 配置错误不算(见下方组合判定),
 * 避免把"输出上限配错"误判成"输入太长"而触发无意义的重试切分。
 */
private val CONTEXT_LENGTH_PHRASES: List<String> = listOf(
    "context_length",
    "context length",
    "context window",
    "maximum context",
    "max context",
    "too many tokens",
    "prompt is too long",
    "prompt too long",
    "input is too long",
    "input too long",
    "reduce the length of the messages",
    "reduce the length of the prompt",
    "exceeds the maximum number of tokens",
)

/**
 * 判断异常是否为「上下文超限」类错误。
 *
 * RikkaHub 各供应商把 HTTP 响应体拼进异常消息(如
 * `Failed to get response: 400 {"error":...context length...}`),
 * 故对 `toString()` 做短语匹配即可覆盖。
 */
fun isContextLengthError(error: Throwable): Boolean {
    val lower = error.toString().lowercase()
    if (CONTEXT_LENGTH_PHRASES.any { it in lower }) return true
    // 泛化组合判定:提到 max_tokens 且同时含"超"语义,才认为是输入超限
    return "max_tokens" in lower &&
        listOf("exceed", "too long", "too many", "over").any { it in lower }
}

/**
 * 带「上下文超限递归二分重试」的文本摘要。
 *
 * 成功直接返回;若失败且判定为上下文超限,则把 [text] 二分(UTF-16 安全),
 * 分别对两半递归摘要,再对「两段摘要拼接」做一次摘要合并(与多块 merge 同路径)。
 *
 * 停止条件:[maxSplits] 次切分额度用尽,或某一半长度已小于 [minSplitChars]
 * (继续切只会让请求更碎且仍可能超限)——此时原样抛出最后一次异常。
 * 非超限类异常立即抛出,不做无意义重试。
 *
 * @param maxSplits 整棵重试树共享的切分次数上限
 * @param minSplitChars 切分后单段的最小长度下限(UTF-16 码元),防止无限重试
 * @param onSplitRetry 每次触发二分时的回调(用于日志/埋点)
 * @param summarize 实际执行摘要的挂起函数(由调用方注入)
 */
suspend fun summarizeWithContextRetry(
    text: String,
    maxSplits: Int = DEFAULT_MAX_SPLITS,
    minSplitChars: Int = DEFAULT_MIN_SPLIT_CHARS,
    onSplitRetry: ((error: Throwable, chunkChars: Int) -> Unit)? = null,
    summarize: suspend (String) -> String,
): String = summarizeRecursive(
    text = text,
    remaining = intArrayOf(maxSplits),
    minSplitChars = minSplitChars,
    onSplitRetry = onSplitRetry,
    summarize = summarize,
)

const val DEFAULT_MAX_SPLITS: Int = 5
const val DEFAULT_MIN_SPLIT_CHARS: Int = 512

private suspend fun summarizeRecursive(
    text: String,
    remaining: IntArray,
    minSplitChars: Int,
    onSplitRetry: ((error: Throwable, chunkChars: Int) -> Unit)?,
    summarize: suspend (String) -> String,
): String {
    try {
        return summarize(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (!isContextLengthError(e) || remaining[0] <= 0) throw e
        val halves = splitUtf16SafeHalves(text) ?: throw e
        val (left, right) = halves
        if (left.length < minSplitChars || right.length < minSplitChars) throw e
        remaining[0]--
        onSplitRetry?.invoke(e, text.length)
        val leftSummary = summarizeRecursive(left, remaining, minSplitChars, onSplitRetry, summarize)
        val rightSummary = summarizeRecursive(right, remaining, minSplitChars, onSplitRetry, summarize)
        return summarizeRecursive(
            "$leftSummary\n\n$rightSummary",
            remaining,
            minSplitChars,
            onSplitRetry,
            summarize,
        )
    }
}
