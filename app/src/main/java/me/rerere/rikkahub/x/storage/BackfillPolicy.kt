// [X-custom] RikkaHub-X 存储管理重构(P1)：存量回填的**纯逻辑**（可在 JVM 单测里穷举）
package me.rerere.rikkahub.x.storage

import me.rerere.rikkahub.data.files.FileFolders

/**
 * 存量回填的策略与判据 —— **全部是纯函数**：输入（路径、账本查询结果）→ 判定。
 *
 * ## 回填做什么、不做什么（用户 2026-09-11 拍板）
 *
 * | | 做什么 | 是否改数据 |
 * |---|---|---|
 * | ✅ **回填索引**（本类服务的事） | 扫描已有文件 → 算哈希 → 写 `x_asset` | **只读文件、只写新表** |
 * | ❌ 存量合并 | 把重复文件合并成一份（要改写消息 URI） | **不做** —— 决策 8 定为「存量只统计」 |
 *
 * ## 为什么判据要单独抽出来
 *
 * 「这个文件该登记、还是算重复、还是已经索引过」是本阶段**最容易判错**的一步：
 * 判错只是数字不对、不报错，且要等用户看到「可清理量」时才察觉。
 * 抽成纯函数后可以在单测里把每种组合列全，不需要数据库或文件系统。
 */
object BackfillPolicy {

    /**
     * 扫描的**根目录**（相对 `filesDir`）。
     *
     * 只扫这两个，是有意的范围限制：
     *
     * | 目录 | 为什么扫 |
     * |---|---|
     * | `upload/` | **老式附件**（`upload/<uuid>.<ext>`）—— 内容寻址之前写入的文件都在这里，是回填的主要对象 |
     * | `assets/` | **内容寻址根** —— 正常情况下文件已在账本里；扫它是为了**修复**早期「表没建出来」期间写入的文件（实测发生过：写入静默回落旧路径） |
     *
     * **不扫**：`images/`（生成图，由 GenMedia 自己的生命周期管，属 P2 备份问题的范围）、
     * `tool_outputs/`（启动即清）、缓存与临时目录。
     */
    val SCAN_ROOTS: List<String> = listOf(FileFolders.UPLOAD, AssetHash.ROOT)

    /** 每处理这么多文件就落一次进度（让「可暂停 + 进度可见」在长扫描中真的可见）。 */
    const val PROGRESS_FLUSH_EVERY = 32

    /** 从排序后的清单里一次取多少条（分批是为了在批间检查暂停与取消）。 */
    const val BATCH_SIZE = 64

    /** 目录最多往下走几层 —— `assets/ab/cd/x.png` 是 3 层，留一层余量。 */
    const val MAX_DEPTH = 4

    /**
     * 该文件登记时的 `asset.origin` 取值。
     *
     * 只区分「老式附件目录」与「其余」两档：回填**无法知道**一个老文件当初是从哪来的
     * （上传 / 生成 / 工具输出）—— 那个信息在写入时就丢了。
     * 与其编一个看起来更具体的值，不如老实写 `unknown`。
     */
    fun originFor(relativePath: String): String =
        if (relativePath.startsWith(FileFolders.UPLOAD + "/")) {
            XStorageTables.Origins.UPLOAD
        } else {
            XStorageTables.Origins.UNKNOWN
        }

    /**
     * 登记老文件时写进 `extras_json` 的内容。
     *
     * 手写 JSON 而不是用序列化器：这里只有一个键值对，
     * 为一个键引入 `JsonObject` 构造反而让「它到底写了什么」变得不直观。
     */
    fun extrasFor(relativePath: String): String =
        "{\"" + XStorageTables.AssetExtras.ORIGIN + "\":\"" + originFor(relativePath) + "\"}"

    /** 一个文件在回填视角下的处置。 */
    enum class FileVerdict {
        /** 该内容已在账本里、且登记的就是**这个文件** → 无需动作（重复运行回填时的常态）。 */
        ALREADY_INDEXED,

        /**
         * 该内容已在账本里、但登记的是**另一个文件** → 存量重复。
         *
         * **只统计，不合并**：合并要改写消息 URI（决策 8 明确不做）。
         * 这里也不去覆盖账本已有行 —— 覆盖会把「那个已在内容寻址路径上的文件」变成孤儿。
         */
        DUPLICATE,

        /** 账本里没有这个内容 → 登记。 */
        REGISTER,
    }

    /**
     * 判定一个文件该怎么处置。
     *
     * @param knownPath `x_asset` 里该内容登记的路径；`null` = 账本里没有这个内容。
     * @param relativePath 本次扫描到的文件路径。
     */
    fun verdictFor(knownPath: String?, relativePath: String): FileVerdict = when {
        knownPath == null -> FileVerdict.REGISTER
        knownPath == relativePath -> FileVerdict.ALREADY_INDEXED
        else -> FileVerdict.DUPLICATE
    }

    /**
     * 该路径是否属于回填范围。
     *
     * 排除两类：
     * - **隐藏项**（任一路径段以 `.` 开头）—— 内容寻址的临时目录就叫 `.x-tmp`，
     *   其中的文件是**写入中途**的产物，登记它们会凭空造出账本里的假条目；
     * - **空路径**。
     */
    fun shouldScan(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false
        return relativePath.split('/').none { it.startsWith(".") }
    }

    /**
     * 扫描顺序 —— **稳定排序**是「可续跑」的前提。
     *
     * 文件系统的列举顺序不保证稳定；若按列举顺序推进游标，中断再续时
     * 游标指向的位置在下次枚举里可能已换，于是**漏扫或多扫**。
     * 排序后游标（上一个已处理的路径）在两次运行间是可靠的。
     */
    fun orderPaths(paths: List<String>): List<String> = paths.sorted()

    /**
     * 从排序后的列表里取出游标之后的一批。
     *
     * @param cursor 上一个**已处理完**的路径；`null` = 从头开始。
     *   用「严格大于」而不是「不等于」：即使游标指向的路径本次已不存在（文件被删），
     *   也能从正确位置继续。
     */
    fun batchAfter(ordered: List<String>, cursor: String?, batchSize: Int): List<String> {
        require(batchSize > 0) { "批大小必须为正:$batchSize" }
        if (cursor == null) return ordered.take(batchSize)
        val from = ordered.indexOfFirst { it > cursor }
        if (from < 0) return emptyList()
        return ordered.subList(from, minOf(from + batchSize, ordered.size))
    }

    /**
     * 进度百分比（0~100）。
     *
     * `total == 0` 时返回 **100** 而不是 0 —— 「没有要处理的文件」是**已完成**，
     * 报 0 会让界面永远停在「0%」而用户以为卡住了。
     */
    fun progressPercent(done: Int, total: Int): Int {
        if (total <= 0) return 100
        if (done <= 0) return 0
        if (done >= total) return 100
        return (done.toLong() * 100 / total).toInt()
    }
}

/**
 * 存量重复的累计结果（决策 8：**只统计，不合并**）。
 *
 * 单位是「**可省下的字节**」而不是「涉及的总字节」——
 * 两个 1MB 的相同文件，可省下的是 1MB（合并成一份后能删掉的那份），
 * 而报 2MB 会让用户以为能腾出两倍空间。
 */
data class DuplicateStats(
    /** 重复文件的**个数**（不含被保留的那份）。 */
    val duplicateFiles: Long = 0L,
    /** 可省下的字节数 = Σ(每组重复文件的份数 − 1) × 单份大小。 */
    val duplicateBytes: Long = 0L,
) {
    /** 又发现一个「内容已存在」的文件 → 计入可省下的字节。 */
    fun plusDuplicate(byteSize: Long): DuplicateStats =
        copy(duplicateFiles = duplicateFiles + 1, duplicateBytes = duplicateBytes + byteSize)
}
