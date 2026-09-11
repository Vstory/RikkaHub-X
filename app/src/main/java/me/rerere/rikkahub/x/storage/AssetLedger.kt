// [X-custom] RikkaHub-X 资产层：写入路径所需的账本能力（窄接口）
package me.rerere.rikkahub.x.storage

/**
 * 写入路径需要的**全部**账本能力。
 *
 * ## 为什么要单独一个接口，而不是直接用 [AssetRepository]
 *
 * 两个理由：
 *
 * ① **可测**。落盘与去重的机械过程（算哈希 → 查账本 → 复用还是写盘 → 登记）是本阶段
 *    最该被用例守住的东西，而 [AssetRepository] 是具体类、要数据库才能构造。抽出这个
 *    窄接口后，[AssetWritePath] 配一个假的账本 + 临时目录就能在 JVM 单测里跑完整流程。
 *
 * ② **划清职责**。[AssetWritePath] 只需要「查一条、写一条、数引用」这三件事；
 *    把整个仓储暴露给它，等于让写入路径有机会去做回收、回填那些与它无关的事。
 *
 * ## 为什么是同步方法
 *
 * 调用点在 Compose 回调里（裁剪回调、`ReceiveContentListener`、`ActivityResult` 回调），
 * 改成挂起会波及 8 处界面代码；而这条路径**本来就在做阻塞式文件 IO**（整份内容写进文件），
 * 一次带索引的查询不改变它的性质。仓储的挂起版本就是这些同步方法的薄包装，只有一份实现。
 */
interface AssetLedger {

    /**
     * 按内容哈希查已登记的资产；**同时查盘**。
     *
     * 查盘这一步不能省：库里有记录不代表文件还在（用户清过数据目录、写入中途掉电、
     * 外部工具删过文件）。[KnownAsset.fileExists] 就是给这个判据用的 ——
     * 它决定「复用」还是「按原路径补写」。
     */
    fun findKnown(hash: String): KnownAsset?

    /** 登记（或覆盖）一条资产。调用方**只在内容首次落盘时**调用。 */
    fun recordAsset(
        hash: String,
        relativePath: String,
        byteSize: Long,
        nowMillis: Long,
        extrasJson: String = "{}",
    )

    /**
     * 该资产当前被多少条引用指向。
     *
     * 给「能不能删这个文件」当判据用 —— 内容寻址之后**一份内容只有一个文件**，
     * 多个会话可能同时引用它，删之前必须先数引用。
     */
    fun referenceCountOf(assetId: String): Int
}
