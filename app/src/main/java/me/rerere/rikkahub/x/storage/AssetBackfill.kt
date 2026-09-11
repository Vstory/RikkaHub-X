// [X-custom] RikkaHub-X 存储管理重构(P1)：存量回填（后台静默、可暂停、可续跑）
package me.rerere.rikkahub.x.storage

import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.x.diag.XDomain
import me.rerere.rikkahub.x.diag.XLog

/**
 * **存量回填**：把已有文件纳入内容寻址账本（用户 2026-09-11 拍板：策略 A · 后台静默）。
 *
 * ## 它解决什么问题
 *
 * 内容寻址只对**新写入**生效 —— 在此之前用户已经存下的附件（`upload/<uuid>.<ext>`）
 * 不在账本里，于是：去重命中不了它们、`可清理量`算不到它们、回收与审计也看不见它们。
 * 本类把它们**只读地**扫一遍、算内容哈希、写进 `x_asset`。
 *
 * ## 三件容易做错的事，各有一条硬约束
 *
 * | 风险 | 本类的做法 |
 * |---|---|
 * | 长时间扫描卡住界面 | 跑在 **`Dispatchers.IO`**，按 [BackfillPolicy.BATCH_SIZE] 分批，**批间**检查暂停与取消 |
 * | 进程被杀后从头再来 | 游标与进度**落库**（`x_storage_meta`），按**排序后**的路径严格推进 → 可续跑 |
 * | 把「重复」当成「该覆盖」 | 已存在的哈希**只统计不覆盖**（[BackfillPolicy.FileVerdict.DUPLICATE]）—— 覆盖会把已在内容寻址路径上的那个文件变成孤儿 |
 *
 * ## 为什么不建「回收候选」
 *
 * [BackfillPolicy.FileVerdict.DUPLICATE] 之外的一切写入都只碰 `x_asset`。
 * **回填不创建 `x_asset_gc` 行**，故回填过的老文件不会自己变成「可删候选」——
 * 那些文件**确实被消息用着**，只是路径里没有内容指纹、引用登记不上。
 * 「哪些资产真能删」由后续的扫描+用户确认决定，且有 `AssetStorePolicy.isManagedPath`
 * 那道过滤兜底（老文件不在内容寻址根下，天然被排除出可清理统计）。
 *
 * ## 幂等
 *
 * 重复运行是常态（每次启动都会调一次 [resumeIfNeeded]），故每个文件都要能安全地再过一遍：
 * 已在账本里、且登记的就是它 → [BackfillPolicy.FileVerdict.ALREADY_INDEXED]，什么都不做。
 */
class AssetBackfill(
    private val database: AppDatabase,
    private val filesDir: File,
    private val ledger: AssetRepository,
) {

    /** 回填状态（落库为字符串，故新增值要慎重 —— 旧版本读到不认识的值会当未完成重跑）。 */
    enum class State(val wireName: String) {
        RUNNING("Running"),
        PAUSED("Paused"),
        DONE("Done"),
    }

    /** 一次回填的结果（给日志、也给将来的界面）。 */
    data class Summary(
        val state: State,
        val scanned: Int,
        val total: Int,
        val registered: Int,
        val alreadyIndexed: Int,
        val readFailures: Int,
        val duplicates: DuplicateStats,
    )

    @Volatile
    private var pauseRequested: Boolean = false

    /**
     * 请求暂停。
     *
     * **在文件之间生效**，不在文件中途中断 —— 中途停下会留下「算了一半哈希」这类
     * 无法表达的状态，而按文件停下没有这个问题（游标指向的是已处理完的文件）。
     */
    fun requestPause() {
        pauseRequested = true
    }

    /**
     * 需要的话继续回填（已 [State.DONE] 则立即返回）。
     *
     * 调用点：应用启动的后台任务。**可重复调用** —— 每次启动一次，直到做完为止。
     */
    suspend fun resumeIfNeeded(): Summary = withContext(Dispatchers.IO) { runScan() }

    // ────────────────────────────────────────────────────────────────
    // 内部
    // ────────────────────────────────────────────────────────────────

    private suspend fun runScan(): Summary {
        val meta = database.xStorageMetaDao()
        val auditDao = database.xAssetDao()

        fun metaString(key: String): String? = meta.getValue(key)
        fun metaInt(key: String): Int = metaString(key)?.toIntOrNull() ?: 0
        fun metaLong(key: String): Long = metaString(key)?.toLongOrNull() ?: 0L
        fun put(key: String, value: String) = meta.put(XStorageMetaEntity(key, value))

        var scanned = metaInt(XStorageTables.MetaKeys.BACKFILL_SCANNED)
        var registered = metaInt(XStorageTables.MetaKeys.BACKFILL_REGISTERED)
        var alreadyIndexed = 0
        var readFailures = 0
        var duplicates = DuplicateStats(
            duplicateFiles = metaLong(XStorageTables.MetaKeys.BACKFILL_DUPLICATE_FILES),
            duplicateBytes = metaLong(XStorageTables.MetaKeys.BACKFILL_DUPLICATE_BYTES),
        )

        if (metaString(XStorageTables.MetaKeys.BACKFILL_STATE) == State.DONE.wireName) {
            return Summary(State.DONE, scanned, metaInt(XStorageTables.MetaKeys.BACKFILL_TOTAL),
                registered, alreadyIndexed, readFailures, duplicates)
        }

        val ordered = BackfillPolicy.orderPaths(collectRelativePaths())
        val total = ordered.size
        var cursor = metaString(XStorageTables.MetaKeys.BACKFILL_CURSOR)

        put(XStorageTables.MetaKeys.BACKFILL_STATE, State.RUNNING.wireName)
        put(XStorageTables.MetaKeys.BACKFILL_TOTAL, total.toString())
        XLog.info(XDomain.STORAGE, XStorageEvents.BACKFILL_SCAN) {
            "回填开始:待扫描 $total 个文件,游标=${cursor ?: "(从头)"}"
        }

        var sinceFlush = 0
        var state = State.DONE

        fun flush() {
            put(XStorageTables.MetaKeys.BACKFILL_SCANNED, scanned.toString())
            put(XStorageTables.MetaKeys.BACKFILL_REGISTERED, registered.toString())
            put(XStorageTables.MetaKeys.BACKFILL_DUPLICATE_FILES, duplicates.duplicateFiles.toString())
            put(XStorageTables.MetaKeys.BACKFILL_DUPLICATE_BYTES, duplicates.duplicateBytes.toString())
            cursor?.let { put(XStorageTables.MetaKeys.BACKFILL_CURSOR, it) }
        }

        scan@ while (true) {
            // 批间可以被取消(应用退出/作用域撤销)
            coroutineContext.ensureActive()

            val batch = BackfillPolicy.batchAfter(ordered, cursor, BackfillPolicy.BATCH_SIZE)
            if (batch.isEmpty()) break

            for (relativePath in batch) {
                if (pauseRequested) {
                    state = State.PAUSED
                    break@scan
                }

                when (val outcome = processOne(relativePath)) {
                    is FileOutcome.Failed -> readFailures++
                    is FileOutcome.Registered -> registered++
                    is FileOutcome.AlreadyIndexed -> alreadyIndexed++
                    is FileOutcome.Duplicate -> duplicates = duplicates.plusDuplicate(outcome.byteSize)
                }

                // 游标只在**处理完之后**推进 —— 中途被杀时该文件会被重新处理一遍(幂等)
                cursor = relativePath
                scanned++
                sinceFlush++
                if (sinceFlush >= BackfillPolicy.PROGRESS_FLUSH_EVERY) {
                    flush()
                    sinceFlush = 0
                }
            }
            if (state == State.PAUSED) break
            // 批间让出 IO 线程 —— 回填是「低优先级后台任务」,不该独占调度器
            yield()
        }

        flush()
        if (state == State.PAUSED) {
            put(XStorageTables.MetaKeys.BACKFILL_STATE, State.PAUSED.wireName)
            XLog.info(XDomain.STORAGE, XStorageEvents.BACKFILL_SCAN) {
                "回填已暂停:已处理 $scanned/$total"
            }
        } else {
            put(XStorageTables.MetaKeys.BACKFILL_STATE, State.DONE.wireName)
            val detail = "{\"scanned\":$scanned,\"total\":$total,\"registered\":$registered," +
                "\"alreadyIndexed\":$alreadyIndexed,\"readFailures\":$readFailures," +
                "\"duplicateFiles\":${duplicates.duplicateFiles}," +
                "\"duplicateBytes\":${duplicates.duplicateBytes}}"
            runCatching {
                auditDao.insertAudit(
                    XGcAuditEntity(
                        kind = XStorageTables.AuditKinds.BACKFILL_RUN,
                        // 批量动作没有「单一实体」—— 用固定标识占位,并在 detail 里给出全貌
                        entityId = BACKFILL_AUDIT_ENTITY,
                        byteSize = duplicates.duplicateBytes,
                        detail = detail,
                        completedAt = System.currentTimeMillis(),
                    )
                )
            }.onFailure {
                // 审计写失败不影响回填结果(回填已完成,进度在 meta 里),但必须可查
                XLog.warn(XDomain.STORAGE, XStorageEvents.BACKFILL_DONE, it) { "回填写审计失败" }
            }
            XLog.info(XDomain.STORAGE, XStorageEvents.BACKFILL_DONE) {
                "回填完成:$scanned/$total,新登记 $registered,重复 ${duplicates.duplicateFiles} 个" +
                    "(可省 ${duplicates.duplicateBytes} 字节" +
                    if (readFailures > 0) "),读取失败 $readFailures" else ")"
            }
        }

        return Summary(state, scanned, total, registered, alreadyIndexed, readFailures, duplicates)
    }

    private sealed interface FileOutcome {
        data object AlreadyIndexed : FileOutcome
        data class Duplicate(val byteSize: Long) : FileOutcome
        data object Registered : FileOutcome
        data object Failed : FileOutcome
    }

    /** 处理单个文件。**任何异常都在这里收敛** —— 一个坏文件不该让整轮扫描停下。 */
    private suspend fun processOne(relativePath: String): FileOutcome {
        val file = File(filesDir, relativePath)
        if (!file.isFile) return FileOutcome.Failed

        val byteSize = file.length()
        // ⚠️ `AssetHash.of` **不关闭流** —— 调用方必须自己关。
        // 漏掉 `use` 在几万个文件的扫描里会耗尽文件描述符(进程级崩溃,而不是一个错文件)。
        val hash = runCatching { file.inputStream().use { AssetHash.of(it) } }.getOrElse {
            XLog.warn(XDomain.STORAGE, XStorageEvents.BACKFILL_SCAN, it) {
                "读取失败,已跳过:" + relativePath
            }
            return FileOutcome.Failed
        }

        return when (val verdict = BackfillPolicy.verdictFor(ledger.registeredPathOf(hash), relativePath)) {
            BackfillPolicy.FileVerdict.ALREADY_INDEXED -> FileOutcome.AlreadyIndexed

            // 只统计，不覆盖 —— 覆盖会把已登记在内容寻址路径上的那个文件变成孤儿
            BackfillPolicy.FileVerdict.DUPLICATE -> FileOutcome.Duplicate(byteSize)

            BackfillPolicy.FileVerdict.REGISTER -> {
                val mtime = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                val ok = runCatching {
                    ledger.registerAsset(
                        hash = hash,
                        relativePath = relativePath,
                        byteSize = byteSize,
                        // 老文件的「创建/最后引用时刻」无法还原,用**文件修改时间**当最接近的代理
                        nowMillis = mtime,
                        extrasJson = BackfillPolicy.extrasFor(relativePath),
                    )
                }.isSuccess
                if (ok) FileOutcome.Registered else FileOutcome.Failed
            }
        }
    }

    /**
     * 列出扫描范围内全部文件的相对路径。
     *
     * `maxDepth` 是**必须给**的：内容寻址目录下可能有软链形成的环，
     * 无界递归会转不出来；而回填的实际层级很浅（`assets/ab/cd/x.png` = 3 层）。
     */
    private fun collectRelativePaths(): List<String> {
        val out = mutableListOf<String>()
        for (root in BackfillPolicy.SCAN_ROOTS) {
            val rootDir = File(filesDir, root)
            if (!rootDir.isDirectory) continue
            rootDir.walkTopDown()
                .maxDepth(BackfillPolicy.MAX_DEPTH)
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(filesDir).path
                    if (BackfillPolicy.shouldScan(relative)) out.add(relative)
                }
        }
        return out
    }

    private companion object {
        /** 回填审计的占位实体名（批量动作没有单一实体）。 */
        const val BACKFILL_AUDIT_ENTITY = "backfill"
    }
}
