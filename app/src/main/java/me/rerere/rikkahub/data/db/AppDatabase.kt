package me.rerere.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.migrations.Migration_16_17
import me.rerere.rikkahub.data.db.migrations.Migration_22_23
import me.rerere.rikkahub.data.db.migrations.Migration_8_9
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.x.storage.XAssetDao
import me.rerere.rikkahub.x.storage.XAssetGcEntity
import me.rerere.rikkahub.x.storage.XAssetRefEntity
import me.rerere.rikkahub.x.storage.XGcAuditEntity
import me.rerere.rikkahub.x.storage.XAssetEntity
import me.rerere.rikkahub.x.storage.XStorageMetaDao
import me.rerere.rikkahub.x.storage.XStorageMetaEntity
import me.rerere.rikkahub.x.storage.XTombstoneEntity

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        // [X-custom] X 存储层的 6 张表(2026-09-11 由「运行时手写 DDL」改为 Room 托管)。
        // 登记进来的直接收益:**语句在编译期被解析** —— 列名/类型写错不再等到运行时
        // 报「no such column」。原手写层的两次生产事故正是这一类错。
        // 表名一律带 x_ 前缀,与上游新增表撞名的概率可忽略。
        XAssetEntity::class,
        XAssetRefEntity::class,
        XAssetGcEntity::class,
        XGcAuditEntity::class,
        XTombstoneEntity::class,
        XStorageMetaEntity::class,
    ],
    // [X-custom] 25 → 26:X 的 6 张表进 entities(见下方 autoMigrations 末尾)。无手写 SQL。
    version = 26,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        // [X-custom] 25 → 26:X 表纳入 Room。**用 AutoMigration,不手写 SQL** ——
        // Room 能自动生成「新增表」的迁移语句,且它生成的建表语句**带 IF NOT EXISTS**
        // (实测:`schemas/.../25.json` 里 8 张表、9 个索引全部带),
        // 故「表已存在」的情况由 SQLite 自己跳过,不需要额外处理。
        //
        // ⚠️ 边界:若某台设备上**已存在形态不对的旧表**(早期未发布构建的运行时建表产物),
        // `IF NOT EXISTS` 会跳过建表 → Room 结构校验不过 → 库打不开。
        // 实测那些构建里建表**从未成功过**(见计划表「X 表从未建出来」事故),
        // 且 P1 尚未发布,故受影响面 ≈ 0;真有则卸载重装即可。
        AutoMigration(from = 25, to = 26),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    /** [X-custom] X 资产侧语句(由 Room 在编译期解析列名,不再手写 SQL 字符串)。 */
    abstract fun xAssetDao(): XAssetDao

    /** [X-custom] `x_storage_meta` 读写(回填进度与续跑游标等运行状态)。 */
    abstract fun xStorageMetaDao(): XStorageMetaDao
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
