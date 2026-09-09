package me.rerere.rikkahub.x.sync

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

/**
 * 官方 RikkaHub 备份库 → RikkaHub-X 结构适配(2026-09-09,方案 A 导入器)。
 *
 * 背景:官方 rikkahub/rikkahub 与 RikkaHub-X 的 Room DB version 目前都是 25,
 * 但 schema 分叉 —— X 的会话级模型切换给 `ConversationEntity` 加了 `model_id` 列:
 * - 官方 v25 identity_hash `049f05fd92fc292b92c652743f056693`(无 model_id)
 * - X    v25 identity_hash `d6f984c2c76986ff51c3b4952180256d`(有 model_id)
 * Room 打开时发现 version 相同但 identity_hash 不同 → 不跑迁移、直接抛
 * "cannot verify data integrity" → 官方含 DB 的备份无法直接导入 X。
 *
 * 适配策略(只针对官方 v25,version 撞车这一种):
 * 1. 读 room_master_table.identity_hash 判定来源;
 * 2. 官方 v25 → 就地 `ALTER TABLE ConversationEntity ADD COLUMN model_id`
 *    + 把 identity_hash 改写为 X v25,使 Room 打开校验通过;
 * 3. 官方 ≤v24 的旧备份**无需本适配**:两版同源迁移链一致,
 *    Room 会按 AutoMigration(…→24→25)正常升级(补列+重算 hash)。
 *
 * 注意:X 的 DB version 升到 26 时,本文件的 X_V25_HASH 需同步更新为
 * app/schemas/.../<新版本>.json 的 identityHash(否则官方 v25 库再导入会校验失败)。
 */
internal object OfficialBackupCompat {

    private const val TAG = "OfficialBackupCompat"

    /** 官方 v25(上游 master 6e0aa7d4,无 model_id)room_master identity_hash */
    private const val OFFICIAL_V25_HASH = "049f05fd92fc292b92c652743f056693"

    /** X v25(含 model_id)identity_hash —— 与 app/schemas/.../25.json 保持一致 */
    private const val X_V25_HASH = "d6f984c2c76986ff51c3b4952180256d"

    private const val ROOM_MASTER_ID = 42

    /**
     * 若 [databaseFile] 是官方 RikkaHub v25 库,就地补齐 X 的 schema 差异。
     * 返回 true = 完成官方 → X 适配;false = 无需处理(已是 X 结构 / 更旧官方版 / 非 Room 库)。
     * 必须只在数据库文件已 checkpoint 成干净单文件后调用(对齐 DatabaseBackup.normalize 之后)。
     */
    fun adaptIfOfficial(context: Context, databaseFile: File): Boolean {
        if (!databaseFile.isFile) return false
        return try {
            val configuration = SQLiteConfiguration.configure(
                context,
                SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READWRITE),
            )
            SQLiteDatabase.openDatabase(configuration, null) {
                error("Official backup database is corrupt")
            }.use { db ->
                val hash = queryIdentityHash(db) ?: return@use false
                when (hash) {
                    OFFICIAL_V25_HASH -> {
                        addModelIdColumnIfMissing(db)
                        db.execSQL(
                            "UPDATE room_master_table SET identity_hash = '$X_V25_HASH' WHERE id = $ROOM_MASTER_ID"
                        )
                        Log.i(TAG, "官方 RikkaHub v25 备份库已适配为 RikkaHub-X v25 结构(补 model_id 列)")
                        true
                    }

                    X_V25_HASH -> false // 已是 X 结构
                    else -> false // 更旧官方版本 / 未知来源:交给 Room 迁移链或由上层报错
                }
            }
        } catch (e: Throwable) {
            // 打不开(非 SQLite / 损坏 / 缺 room_master)→ 不在此处理,交由后续步骤给出准确错误
            Log.w(TAG, "adaptIfOfficial 跳过(非可识别官方 v25 库): ${e.message}")
            false
        }
    }

    private fun queryIdentityHash(db: SQLiteDatabase): String? =
        db.query("SELECT identity_hash FROM room_master_table WHERE id = $ROOM_MASTER_ID").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun addModelIdColumnIfMissing(db: SQLiteDatabase) {
        val hasColumn = db.query("PRAGMA table_info(`ConversationEntity`)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "model_id") {
                    found = true
                    break
                }
            }
            found
        }
        if (!hasColumn) {
            db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `model_id` TEXT NOT NULL DEFAULT ''")
        }
    }
}
