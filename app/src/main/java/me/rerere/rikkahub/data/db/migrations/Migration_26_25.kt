package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_26_25"

/**
 * [X-custom] 26 → 25 的**降级**迁移:让装过存储重构版的设备能退回本包。
 *
 * ## 为什么需要它
 *
 * 存储重构把库升到 26(X 的 6 张表改由 Room 托管),而本条线仍是 25。两者
 * `applicationId` 相同,同 key 自签名可以**互相覆盖安装** —— 于是「先装 26、
 * 再装 25」是一条真实存在的路径。缺这条路径时 Room 抛
 * `A migration from 26 to 25 was required but not found`,`onDowngrade` 崩溃、
 * 应用启动即退(2026-09-12 实测发生过一次)。
 *
 * 设计文档已记下这个前提:「版本号一旦升到 26 并由用户安装过,回退到 25 的代码
 * 无法打开库 → 回滚需要同时提供「26 → 25」的降级路径」。本文件就是落实它 ——
 * 不补这条路径,两个渠道之间就**只能单向升级**。
 *
 * ## 为什么只是删 6 张表
 *
 * 25 → 26 是**纯新增**:只加了 6 张 X 表,没改任何上游表、也没改列(已核
 * `data/db/entity` 与 `data/db/dao` 在两条线之间零差异,FTS 表结构亦未变)。
 * 所以删掉这 6 张表,库结构就与真正的 v25 完全一致 —— **会话 / 消息 / 助手 /
 * 工作区等数据一律不动**。
 *
 * 代价:这 6 张表里的**内容寻址账本**(资产登记、回填进度、墓碑)会清空。它们只
 * 服务存储重构的新功能,本线没有任何代码读它;退回 26 后重跑一次存量回填即可重建。
 *
 * ## 为什么不能"什么都不做"
 *
 * Room 校验只看「期望的表在不在」,多出来的表它不查 —— 所以留着表确实也能启动。
 * 但那样库就处于「自称 v25、却带着 v26 的表」的中间态:将来若 26 → 27 有列变更,
 * AutoMigration 会因为建表语句带 `IF NOT EXISTS` 而跳过,随后结构校验不过、库打不开。
 * 删掉,库才是一个**真正的 v25 库**。
 */
val Migration_26_25 = object : Migration(26, 25) {

    /** 26 新增、25 不认识的 6 张 X 表(定义见 `x/storage/XStorageEntities.kt`)。 */
    private val xTables = listOf(
        "x_asset",
        "x_asset_ref",
        "x_asset_gc",
        "x_gc_audit",
        "x_tombstone",
        "x_storage_meta",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: 26 → 25 降级,删除 X 存储层的 ${xTables.size} 张表")
        DatabaseMigrationTracker.onMigrationStart(26, 25)
        db.beginTransaction()
        try {
            // IF EXISTS:库可能停在半途(例如那些表本就没建出来),删一张不存在的表
            // 不该让整个降级失败 —— 降级失败就直接崩在启动路径上。
            xTables.forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: 26 → 25 降级完成,上游表未动")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
