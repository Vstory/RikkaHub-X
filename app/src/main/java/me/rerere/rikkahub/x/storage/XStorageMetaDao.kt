// [X-custom] RikkaHub-X 存储管理重构：`x_storage_meta` 的 Room DAO
package me.rerere.rikkahub.x.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * `x_storage_meta`（key/value）的读写。
 *
 * ## 为什么单独一个 DAO，而不是塞进 [XAssetDao]
 *
 * 元数据表存的是**存储层的运行状态**（回填进度、结构版本），
 * 与「资产账本」是两件事：资产 DAO 的每个方法都围绕某条资产，
 * 这里的方法围绕「某个键」。混在一起会让 `XAssetDao` 渐渐变成杂物间。
 *
 * ## 读单个键，而不是读整表
 *
 * 回填每处理一批文件就要落一次进度（`PROGRESS_FLUSH_EVERY`），
 * 若每次都 `SELECT *` 拉全表再筛，写入频率一高就白白放大 IO。
 * 故只提供「按键取值 / 按键写值」两个最小操作。
 */
@Dao
interface XStorageMetaDao {

    /** 读一个键；不存在返回 `null`（调用方给默认值）。 */
    @Query("SELECT value FROM x_storage_meta WHERE key = :key")
    fun getValue(key: String): String?

    /**
     * 写入（或覆盖）一个键。
     *
     * 用 REPLACE：元数据是**状态快照**语义 —— 同一个键的旧值没有保留价值，
     * 而且回填会反复写同一批键（进度、游标），REPLACE 正是这里想要的语义。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(meta: XStorageMetaEntity)

    /** 删除一个键（回填重头再来时清游标用）。 */
    @Query("DELETE FROM x_storage_meta WHERE key = :key")
    fun remove(key: String): Int
}
