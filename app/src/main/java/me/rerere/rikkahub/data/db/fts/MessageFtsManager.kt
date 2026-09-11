// [X-custom] RikkaHub-X 存储管理重构(P4)：索引改增量维护，查询改走 join
package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    // ⚠️ `update_at` 必须写全 `conversationentity.update_at`：
    // 该列在同名于 `message_fts.update_at`，不限定表名会是**歧义列**而查询直接失败。
    RELEVANCE("rank, conversationentity.update_at DESC"),
    NEWEST_FIRST("conversationentity.update_at DESC, rank"),
    OLDEST_FIRST("conversationentity.update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

/**
 * 消息全文索引的读写。
 *
 * ## [indexConversation] 是**增量**维护，不是全量重建（2026-09-11 改）
 *
 * 原先是「删掉本会话全部索引行 + 全量重插」—— 代价不在删，而在**插**：
 * FTS5 的每一行插入都要对全文**重新分词**（本项目用 jieba），
 * 一个 N 条消息的会话每保存一次就要重新分词 N 条。
 * 而实际上除流式那条消息之外，**其余消息的文本一字未变**。这是上游问题 ⑦。
 *
 * 现在改为：读出已索引的行 → 与期望内容比对（[FtsIndexPlanner]）→ **只动变了的那几行**。
 * 内容没变时计划为空，**一次写盘都不发生**。
 *
 * ## ⚠️ 关于 `message_fts` 里的 `title` / `update_at` 两列（重要）
 *
 * 上游把「会话的属性」**冗余存进了每一行**，用意是**让搜索不必 join** ——
 * 结果要显示的标题、要排序的时间都在表内。
 *
 * 但这两个副本与消息内容无关，只在会话层面变化时才需要同步：
 *
 * | 列 | 何时变 | 后果 |
 * |---|---|---|
 * | `title` | 用户重命名（罕见） | 若要求每行同步，重命名即全量重写 |
 * | `update_at` | **每次生成结束变一次** | 若要求每行同步，那次即全量重写 |
 *
 * ## 为什么改成增量之后，**必须**把查询改为 `JOIN conversationentity`
 *
 * **这不是为了快，是为了对。** 一旦只写「变了的那几行」，
 * 生成结束时 `update_at` 变了、而索引里的副本**不再被更新** → 副本变旧 →
 * 而「最新优先 / 最旧优先」排序用的正是它 → **排序静默出错**（不报错，只是顺序不对）。
 *
 * 上游之所以能安全地冗余，是因为它**每次全量重写**（副本跟着更新）。
 *
 * ⚠️ **任何新查询都不要读这两列** —— 它们的值停在「最后写入那一刻」。
 * 已由 `scripts/check_fts_column_usage.py` 机械看守。
 *
 * ## 这两列为什么不删掉（实测后的结论，不是"不敢动"）
 *
 * FTS5 是虚拟表，**没有 `ALTER TABLE ... DROP COLUMN`**（实测报
 * `cannot drop column from virtual table`），要删只能重建整张表。
 *
 * 2026-09-11 实测这笔账（5 万条消息的规模）：
 *
 * | | 数字 |
 * |---|---|
 * | **收益**：省下的空间 | **2.4 MB**（索引 17.6 → 15.2 MB，约 14%） |
 * | **成本**：重建一次 | 遍历全部会话 + 读消息 + jieba 重新分词（秒级到几十秒） |
 *
 * **结论：不为省这 2.4 MB 去动它。** 理由**不是**"重建很重"（实测分词本身不慢），
 * 而是**重建期间搜索结果是空的** —— 用户会以为搜索坏了。
 * 用「搜索暂时不可用」换 2.4 MB 不划算：用户的图片/附件通常比这个索引大一个量级。
 *
 * **什么时候顺手做掉**：任何**本来就要重建索引**的场合 ——
 * 搜索页已有「重建索引」按钮；或将来给这张表加列时（FTS5 加列同样要重建）。
 * 那时重建是免费的，把这两列一起收掉即可。
 */
class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    /**
     * 把会话的索引更新到与当前内容一致 —— **只动变了的那几行**。
     *
     * 内容没变时本方法不产生任何写操作（[FtsIndexPlan.isEmpty]）。
     */
    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        val indexed = readIndexedRows(conversationId)
        val desired = desiredRows(conversation)
        val plan = FtsIndexPlanner.plan(indexed, desired)

        if (plan.isEmpty) {
            // 常态（内容没变）—— 不写盘、不打印，避免刷屏
            return@withContext
        }

        applyPlan(plan, conversationId, conversation)

        // 这条日志是「增量是否生效」的**现场证据**：touched 应当远小于 indexed 的规模，
        // 而在流式保存时通常只有 1~2 行（那条正在增长的消息）。
        Log.i(
            TAG,
            "indexConversation: conversation=$conversationId " +
                "已索引=${indexed.size} 需写入=${plan.touchedCount} " +
                "(删${plan.deleteRowIds.size}/改${plan.refresh.size}/增${plan.insert.size})",
        )
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    /**
     * 检索消息。
     *
     * **标题与更新时间来自 `JOIN conversationentity`**，不读 `message_fts` 里那两列冗余副本
     * （理由见类注释）。join 还顺带保证了「会话已删但索引行残留」时不会返回孤儿结果。
     */
    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: String? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        // 助手过滤直接用 join 的列，不再需要另起一个 EXISTS 子查询
        val assistantFilter = if (assistantId != null) "AND conversationentity.assistant_id = ?" else ""
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id,
                   conversationentity.title, conversationentity.update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            JOIN conversationentity ON conversationentity.id = message_fts.conversation_id
            WHERE text MATCH jieba_query(?)
            $assistantFilter
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            if (assistantId != null) arrayOf(keyword, assistantId) else arrayOf(keyword)
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }

    // ────────────────────────────────────────────────────────────────
    // 内部
    // ────────────────────────────────────────────────────────────────

    /** 读出该会话已索引的行。`rowid` 要带上 —— 按它删除是 O(1)，按 `message_id` 是全表扫。 */
    private fun readIndexedRows(conversationId: String): List<IndexedFtsRow> {
        val out = mutableListOf<IndexedFtsRow>()
        db.query(
            "SELECT rowid, message_id, node_id, text FROM message_fts WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    IndexedFtsRow(
                        rowid = cursor.getLong(0),
                        messageId = cursor.getString(1),
                        nodeId = cursor.getString(2),
                        text = cursor.getString(3) ?: "",
                    )
                )
            }
        }
        return out
    }

    /**
     * 该会话**期望**被索引的内容。
     *
     * 文本为空的消息**不进索引**（与旧实现一致）：它的行若已存在，会因为「不在期望集里」被删掉。
     */
    private fun desiredRows(conversation: Conversation): List<DesiredFtsRow> {
        val out = mutableListOf<DesiredFtsRow>()
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    out.add(
                        DesiredFtsRow(
                            messageId = message.id.toString(),
                            nodeId = node.id.toString(),
                            text = text,
                        )
                    )
                }
            }
        }
        return out
    }

    private fun applyPlan(plan: FtsIndexPlan, conversationId: String, conversation: Conversation) {
        plan.deleteRowIds.forEach { rowid ->
            db.execSQL("DELETE FROM message_fts WHERE rowid = ?", arrayOf(rowid))
        }
        plan.refresh.forEach { refresh ->
            db.execSQL("DELETE FROM message_fts WHERE rowid = ?", arrayOf(refresh.rowid))
            insertRow(refresh.desired, conversationId, conversation)
        }
        plan.insert.forEach { insertRow(it, conversationId, conversation) }
    }

    /**
     * 插入一行索引。
     *
     * `title` / `update_at` 按「写入那一刻的会话值」填 —— 它们**不会被任何查询读取**
     * （查询走 join），填真实值只是为了不让列里出现空串这种更容易误导的内容。
     */
    private fun insertRow(row: DesiredFtsRow, conversationId: String, conversation: Conversation) {
        db.execSQL(
            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf(
                row.text,
                row.nodeId,
                row.messageId,
                conversationId,
                conversation.title,
                conversation.updateAt.toEpochMilli().toString(),
            )
        )
    }
}

/**
 * 一条消息用于全文索引的文本。
 *
 * 只取正文（`Text` 部件），并**截断到 10_000 字符**：超长内容进索引没有检索价值，
 * 却会让每次分词都变慢、索引也变大。截断是刻意的，不是遗漏。
 */
private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
