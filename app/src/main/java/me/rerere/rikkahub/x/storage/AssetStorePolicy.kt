// [X-custom] RikkaHub-X 存储管理重构(P1)：内容寻址的写入决策（纯函数,无 IO/无 Android 依赖）
package me.rerere.rikkahub.x.storage

/**
 * 库里已登记的资产（按内容哈希查到的那一条）。
 *
 * [fileExists] 由调用方查盘后传入（纯函数不做 IO）：库里有记录**不代表**盘上还有文件 ——
 * 用户清过数据目录、写入中途掉电、外部工具删过文件，都会造成这种「账实不符」。
 */
data class KnownAsset(
    val relativePath: String,
    val fileExists: Boolean,
)

/**
 * 一次写入该怎么做。
 *
 * 三种情形各自的理由见 [AssetStorePolicy.plan]。
 */
sealed class AssetStorePlan {
    /** 目标落盘路径（相对 filesDir）。 */
    abstract val relativePath: String

    /** 未见过这份内容 —— 落盘到内容寻址路径。 */
    data class WriteNew(override val relativePath: String) : AssetStorePlan()

    /** 同一份内容已在盘上 —— **复用，不落盘**（这就是去重）。 */
    data class ReuseExisting(override val relativePath: String) : AssetStorePlan()

    /** 有登记但文件不在 —— 按**原路径**重写，保住已经在引用它的消息。 */
    data class RewriteMissing(override val relativePath: String) : AssetStorePlan()
}

/**
 * 内容寻址的写入决策。
 *
 * **取代**原先「一律生成新 UUID 文件名、直接落盘」的做法
 * （`FilesManager.createChatFilesByContents` / `createChatFilesByByteArrays`）——
 * 那个做法下，同一张图贴两次就是两份字节，且无从判断谁在用。
 *
 * 全部为**纯函数**：输入（哈希 / 扩展名 / 已登记记录）→ 计划。
 * 可在 JVM 单测里穷举，无需 Android 环境或真实数据库。
 */
object AssetStorePolicy {

    /**
     * 决定这次写入该怎么走。
     *
     * | 情形 | 动作 | 为什么不能更省 |
     * |---|---|---|
     * | 库中无此哈希 | [AssetStorePlan.WriteNew] | 首次见到的内容，必须落盘 |
     * | 有记录且文件在 | [AssetStorePlan.ReuseExisting] | 直接复用即可，**不写盘** |
     * | 有记录但文件丢了 | [AssetStorePlan.RewriteMissing] | 不能改路径：`x_asset_ref` 与消息里存的是**原路径**，换路径等于把引用全部打断 |
     *
     * **扩展名以首次落盘者为准。** 同一份内容可能以不同扩展名进来
     * （同一串字节既被当 `bin` 又被当 `png`），但内容寻址要求「一份内容一个文件」，
     * 故命中已有记录时**沿用旧路径的扩展名**，不因这次带来的扩展名而改名。
     * 后果只是路径后缀可能与本次 MIME 不一致 —— 无害：真正的 MIME 记在
     * `x_asset.extras_json`，路径后缀从不参与类型判定。
     */
    fun plan(hash: String, extension: String?, known: KnownAsset?): AssetStorePlan = when {
        known == null -> AssetStorePlan.WriteNew(AssetHash.relativePath(hash, extension))
        known.fileExists -> AssetStorePlan.ReuseExisting(known.relativePath)
        else -> AssetStorePlan.RewriteMissing(known.relativePath)
    }

    /** 该计划是否需要真的写盘。 */
    fun requiresWrite(plan: AssetStorePlan): Boolean = plan !is AssetStorePlan.ReuseExisting

    /**
     * 该相对路径是否位于内容寻址根目录内。
     *
     * **任何删除动作前都必须过这一关。** 回收/清理是唯一会动用户文件的操作，
     * 一旦路径来自被污染的数据（旧版本写入、导入的备份、构造的存档），
     * 直接删就会删到 `upload/`、`skills/` 甚至更外层 —— 那时引用表再准也没用。
     *
     * 判据用「前缀 + 分隔符」而不是 `startsWith("assets")`：后者会放过
     * `assets_evil/x`（同前缀不同目录）。同时拒绝路径穿越与绝对路径。
     */
    fun isManagedPath(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false
        // 绝对路径与上跳一律拒绝(V2 之前不规范化,直接当不安全)
        if (relativePath.startsWith("/")) return false
        if (relativePath.contains("..")) return false
        if (relativePath.contains('\\')) return false
        return relativePath.startsWith("${AssetHash.ROOT}/")
    }
}
