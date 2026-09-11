package me.rerere.rikkahub.data.db

import me.rerere.rikkahub.x.storage.XStorageEvents
import me.rerere.rikkahub.x.diag.XLog
import me.rerere.rikkahub.x.diag.XDomain
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.x.storage.XStorageSchema

/** Shared schema, migrations and extensions for the app and staged backup validation. */
internal object AppDatabaseFactory {
    fun create(context: Context, name: String = SQLiteConfiguration.DATABASE_NAME): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            text,
                            node_id UNINDEXED,
                            message_id UNINDEXED,
                            conversation_id UNINDEXED,
                            title UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )

                    // [X-custom] RikkaHub-X 存储层表(内容寻址的资产/引用/回收/墓碑/元数据)。
                    // 建在这里而非 Room 迁移里,是为了不动本文件的 entities/version/autoMigrations
                    // —— 上游每次加表都会改那几行,改它必然反复冲突(本仓库是 fork)。
                    // 语句全部 IF NOT EXISTS,可重复执行;结构定义见 x/storage/XStorageSchema.kt。
                    // 失败时只记录不抛出:存储层不可用应当降级,而不是让 App 打不开数据库。
                    runCatching { XStorageSchema.ensure(db) }
                        .onFailure {
                            XLog.warn(XDomain.STORAGE, XStorageEvents.SCHEMA_ENSURE_FAIL, it) {
                                "X 存储层表建立失败,内容寻址与回收功能将不可用"
                            }
                        }
                }
            })
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
}
