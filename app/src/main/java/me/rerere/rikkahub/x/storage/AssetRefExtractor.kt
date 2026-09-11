// [X-custom] RikkaHub-X 存储管理重构(P1)：从消息里提取本地文件引用（纯函数,无 IO/无 Android 依赖）
package me.rerere.rikkahub.x.storage

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.net.URLDecoder

/**
 * 从消息里提取出的一条本地文件引用。
 *
 * [url] 保留 `file://` 原样：[AssetRefExtractor] 不做落盘路径推断，
 * 那是 [AssetRefExtractor.toRelativePath] 的事 —— 后者需要知道 `filesDir`，而本类型不应携带它。
 */
data class ExtractedAssetRef(
    val messageId: String,
    val url: String,
    val kind: String,
)

/**
 * 消息 → 本地文件引用。
 *
 * **取代**原先「在消息 JSON 里搜子串」的引用判定
 * （`MessageNodeDAO.hasFileReference` 的 `instr(messages, :encodedFileUrl) > 0`）。
 * 那个做法有两处硬伤：① 搜的是序列化后的整段 JSON，用户正文里恰好出现同一串就误判为引用；
 * ② 只能回答「某文件是否被引用」，答不了「这条消息引用了哪些文件」——而后者才是回收与
 * 批量对账需要的方向。
 *
 * 全部为**纯函数**，可在 JVM 单测里穷举。
 *
 * 与既有 `Conversation.kt` 的 `List<UIMessagePart>.localFileUrls()` 是同一套语义
 * （含 `Tool.output` 嵌套），差别只在本处**保留了 messageId 与类型**，
 * 供引用登记入表。两者不得漂移，故单测里有一条一致性守护对照二者。
 */
object AssetRefExtractor {

    /** 本地文件 URL 的协议前缀。 */
    private const val FILE_SCHEME = "file://"

    /**
     * 该 part 若引用本地文件，返回它在消息里扮演的角色（[XStorageTables.RefKinds] 之一）。
     *
     * 非文件 part 返回 `null`。远程 URL（`http(s)://`、`data:`）一律不算 ——
     * 它们不占本地磁盘，纳入回收范围只会带来无意义的数据。
     */
    fun kindOf(part: UIMessagePart): String? = when (part) {
        is UIMessagePart.Image -> XStorageTables.RefKinds.IMAGE
        is UIMessagePart.Video -> XStorageTables.RefKinds.VIDEO
        is UIMessagePart.Audio -> XStorageTables.RefKinds.AUDIO
        is UIMessagePart.Document -> XStorageTables.RefKinds.DOCUMENT
        else -> null
    }

    /**
     * 提取一组消息的全部本地文件引用。
     *
     * `Tool.output` 里嵌套的附件同样提取（工具产出的图/文件确实占着磁盘）。
     *
     * 去重是**必要**的：同一条消息里贴两次同一张图会产生两行完全相同的引用，
     * 而 `x_asset_ref` 的主键是 `(message_id, asset_id, kind)` —— 不去重会在插入时
     * 撞主键。这里先按 `url` 收敛，保证同一消息内每个 URL 每种角色只登记一条。
     */
    fun extract(messages: List<UIMessage>): List<ExtractedAssetRef> {
        val out = mutableListOf<ExtractedAssetRef>()
        for (message in messages) {
            val seen = mutableSetOf<String>()
            collect(message.id.toString(), message.parts, out, seen)
        }
        return out
    }

    private fun collect(
        messageId: String,
        parts: List<UIMessagePart>,
        out: MutableList<ExtractedAssetRef>,
        seen: MutableSet<String>,
    ) {
        for (part in parts) {
            if (part is UIMessagePart.Tool) {
                collect(messageId, part.output, out, seen)
            }
            val kind = kindOf(part) ?: continue
            val url = urlOf(part) ?: continue
            if (!url.startsWith(FILE_SCHEME)) continue
            if (seen.add("$kind\u0000$url")) {
                out.add(ExtractedAssetRef(messageId = messageId, url = url, kind = kind))
            }
        }
    }

    private fun urlOf(part: UIMessagePart): String? = when (part) {
        is UIMessagePart.Image -> part.url
        is UIMessagePart.Video -> part.url
        is UIMessagePart.Audio -> part.url
        is UIMessagePart.Document -> part.url
        else -> null
    }

    /**
     * 相对路径 → 内容哈希（即 `x_asset.id`）。
     *
     * 只对**内容寻址路径**有效：`assets/ab/cd/<hash>.png` 里的哈希由路径本身携带，
     * 故能反解。非内容寻址路径（如 `upload/<uuid>.png`）返回 `null` ——
     * 那类存量文件没有内容指纹，需靠回填阶段（P1 任务 1.7）另行处理；
     * 在此之前它们**不进引用表、也进不了回收候选**，方向安全（宁可留着）。
     */
    fun assetIdOf(relativePath: String): String? {
        if (!AssetStorePolicy.isManagedPath(relativePath)) return null
        val name = relativePath.substringAfterLast('/')
        // 哈希全为十六进制、不含 '.'，故取最后一个 '.' 之前的部分即为哈希
        val hash = name.substringBeforeLast('.', missingDelimiterValue = name)
        return if (AssetHash.isValid(hash)) hash else null
    }

    /**
     * 本地文件 URL → 相对 `filesDir` 的路径（即 `x_asset.path` 的形态）。
     *
     * 返回 `null` 表示**不属于本应用的文件目录**（外部存储、缓存目录、其它 App 的路径），
     * 调用方应跳过登记 —— 登记了就会在回收时把范围扩到我们无权管理的文件上。
     *
     * 百分号转义需要还原：`file.toUri()` 会把空格等字符编成 `%20`，
     * 而盘上的名字是原文。`URLDecoder` 会把 `+` 解释成空格，这在**表单**里正确、
     * 在**路径**里错误，故先把字面量 `+` 保护起来。
     */
    fun toRelativePath(url: String, filesRootPath: String): String? {
        if (!url.startsWith(FILE_SCHEME)) return null
        val encodedPath = url.removePrefix(FILE_SCHEME)
        val decoded = runCatching {
            URLDecoder.decode(encodedPath.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrNull() ?: return null

        val prefix = filesRootPath.trimEnd('/') + "/"
        if (!decoded.startsWith(prefix)) return null
        val relative = decoded.removePrefix(prefix)
        if (relative.isEmpty()) return null
        // 相对路径不得上跳:被污染的数据不该有机会指向 filesDir 之外
        if (relative.contains("..")) return null
        return relative
    }
}
