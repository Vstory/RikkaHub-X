// [X-custom] RikkaHub-X 存储管理重构(P1)：X 存储层的事件名(用于日志检索与断言)
package me.rerere.rikkahub.x.storage

/**
 * X 存储层的日志事件名。
 *
 * **为什么用常量而不是随手写字符串**：这些名字是**排查时的检索键** ——
 * 用户从真机导出一段日志发给开发者时，靠的就是在文本里定位 `asset.write.new`
 * 这样的词。名字一散落成字面量，就会出现 `asset.write.new` / `write_new` /
 * `assetWriteNew` 三种写法并存，日志再也搜不干净。
 *
 * **命名规则**：`域.动作.结果`，全小写点分。加新事件时照此续写，
 * 并在单测 [XStorageEventsTest] 的清单里登记 —— 那份清单同时是「有哪些可观测点」的答案。
 */
object XStorageEvents {

    // ── 写入：内容首次落盘 / 复用已有 / 补写丢失 ──

    /** 内容首次落盘，并在资产表登记。 */
    const val WRITE_NEW = "asset.write.new"

    /** 命中已有内容 → 复用现有文件，**不产生第二份**（去重生效）。 */
    const val WRITE_REUSE = "asset.write.reuse"

    /** 资产表有记录但文件已不在盘上 → 按原路径补写。 */
    const val WRITE_REWRITE = "asset.write.rewrite"

    /** 写入路径接线失败（降级为旧行为：照常存文件，只是不进资产表）。 */
    const val WRITE_FALLBACK = "asset.write.fallback"

    /**
     * 内容**已落盘**但登记失败 —— 文件能用，只是不去重、也不进引用表。
     *
     * 与 [WRITE_FALLBACK] 分开成两个事件，因为它们对应的处置不同：
     * 写入失败要**回落到旧路径**（否则用户存不下文件）；登记失败**不该回落** ——
     * 内容已经在内容寻址路径上了，再往旧路径写一份，同一份内容就有两个文件，
     * 既浪费又让「一份内容一个文件」的约定出现例外。
     */
    const val WRITE_UNRECORDED = "asset.write.unrecorded"

    // ── 引用：登记 / 跳过 / 撤销 / 失败 ──

    /** 会话保存时重建引用完成。 */
    const val REF_SYNC = "asset.ref.sync"

    /** 会话删除时撤销引用完成。 */
    const val REF_DROP = "asset.ref.drop"

    /** 某条引用被跳过（无内容指纹 / 不在应用文件目录内）。 */
    const val REF_SKIP = "asset.ref.skip"

    /** 引用登记失败但已降级（**不阻塞会话保存**）。 */
    const val REF_FAIL = "asset.ref.fail"

    // ── 存量回填 ──

    /** 回填扫描完成一轮。 */
    const val BACKFILL_SCAN = "asset.backfill.scan"

    /** 回填整体完成。 */
    const val BACKFILL_DONE = "asset.backfill.done"

    // ── 回收（只登记，不自动删） ──

    /** 某资产进入回收候选。 */
    const val GC_CANDIDATE = "asset.gc.candidate"

    /** 用户确认后真的删除了某个资产。 */
    const val GC_PURGE = "asset.gc.purge"

    /** **拒绝删除**：删前复查发现该资产又有引用了。 */
    const val GC_REFUSE = "asset.gc.refuse"

    /** X 存储层建表失败（`onOpen` 回调里 `ensure` 抛错）—— 存储层不可用，功能降级。 */
    const val SCHEMA_ENSURE_FAIL = "asset.schema.ensure_fail"

    /** X 表纳入 Room 的迁移开始 / 结束。 */
    const val MIGRATION_RUN = "asset.migration.run"

    /**
     * 早期形态的表被重建（v1 资产表 / v2 回收候选表）。
     *
     * 单列一条事件的理由：重建是**改结构**的动作，出问题时需要能一眼看出
     * 「这台设备走了重建分支」—— 与「走的是全新建表」是两条完全不同的路径。
     */
    const val MIGRATION_LEGACY_REBUILD = "asset.migration.legacy_rebuild"

    /** 审计表主键补 `NOT NULL`（Room 对自增主键的要求，SQLite 不会自动补这个标记位）。 */
    const val MIGRATION_PK_FIX = "asset.migration.pk_fix"

    /**
     * 拒绝删除:手上的候选清单已过期。
     *
     * 单列一条事件(而不是复用 [GC_REFUSE])的理由:两种拒绝的**原因与处置不同** ——
     * 「仍被引用」是资产还在用;[本事件]是**用户看到清单之后**该资产被重新引用过,
     * 故清单上的「闲置 N 天」已不准。前者等引用撤销,后者**刷新清单**即可。
     */
    const val GC_PLAN_STALE = "asset.gc.plan_stale"

    /** 撤销会话引用失败 —— 会话已删但引用残留，附件将暂时清不掉。 */
    const val REF_DROP_FAIL = "asset.ref.drop_fail"

    /** 全部事件名，供单测核对（新增事件须登记到这里）。 */
    val ALL: List<String> = listOf(
        WRITE_NEW, WRITE_REUSE, WRITE_REWRITE, WRITE_FALLBACK, WRITE_UNRECORDED,
        REF_SYNC, REF_DROP, REF_DROP_FAIL, REF_SKIP, REF_FAIL,
        BACKFILL_SCAN, BACKFILL_DONE,
        GC_CANDIDATE, GC_PURGE, GC_REFUSE, GC_PLAN_STALE,
        SCHEMA_ENSURE_FAIL,
    )
}
