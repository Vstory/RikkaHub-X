// [X-custom] RikkaHub-X 存储管理重构(P4)：FTS 索引的增量维护 —— 纯逻辑层
package me.rerere.rikkahub.data.db.fts

/**
 * `message_fts` 里**已存在**的一行（从表里读出）。
 *
 * `rowid` 必须读出来：FTS5 改一行等价于「删旧插新」，
 * 而按 `rowid` 定位是 O(1)；按 `message_id` 定位要走全表扫（那列是 `UNINDEXED`）。
 */
data class IndexedFtsRow(
    val rowid: Long,
    val messageId: String,
    val nodeId: String,
    val text: String,
)

/** **期望**被索引的一行（由会话内容算出）。 */
data class DesiredFtsRow(
    val messageId: String,
    val nodeId: String,
    val text: String,
)

/** 「删掉这一行、按新内容重新插入」（FTS5 没有原地更新，改一行就是这个动作）。 */
data class FtsRefresh(val rowid: Long, val desired: DesiredFtsRow)

/**
 * 一次索引维护要做的事。
 *
 * **空计划是常态**：内容没变的会话走一遍计划，得到的就是全空 —— 那才是增量维护的收益所在。
 */
data class FtsIndexPlan(
    val deleteRowIds: List<Long> = emptyList(),
    val refresh: List<FtsRefresh> = emptyList(),
    val insert: List<DesiredFtsRow> = emptyList(),
) {
    /** 没有任何行需要动。 */
    val isEmpty: Boolean get() = deleteRowIds.isEmpty() && refresh.isEmpty() && insert.isEmpty()

    /** 需要写的行数（日志与断言用；**不写盘的行数**才是「省下多少」的度量）。 */
    val touchedCount: Int get() = deleteRowIds.size + refresh.size + insert.size
}

/**
 * FTS 索引的**增量维护**计划器 —— 纯函数，可在 JVM 单测里穷举。
 *
 * ## 它替掉的旧做法及其代价
 *
 * 原先每次保存都是「`DELETE FROM message_fts WHERE conversation_id = ?` + 全量 INSERT」。
 * 关键代价不在删，而在 **INSERT 必须对每条消息的全文重新分词**（本项目用的是 jieba）——
 * 一个 N 条消息的会话，每保存一次就要重新分词 N 条，长会话下这是秒级开销。
 *
 * 而实际上，除流式那条消息外，**其余消息的文本一字未变**。
 *
 * ## 为什么需要「纯逻辑」这一层
 *
 * 「哪几行要动」是本阶段最容易判错的地方，而判错的后果是**搜不到**或**搜出错** ——
 * 两者都不会报错。抽成纯函数后可以把每种组合列全，不需要数据库或分词器。
 *
 * ## 一条必须守住的性质：幂等
 *
 * 用同一份期望内容连跑两次，第二次必须得到**空计划**。
 * 若做不到，说明判据里混进了会自己变化的东西（如时间戳、随机值），
 * 索引就会每次保存都被无谓重写。单测里有一条直接钉住它。
 */
object FtsIndexPlanner {

    /**
     * 比对「已索引」与「期望索引」，算出最小改动集。
     *
     * 判据只看**内容指纹**（`messageId` + `nodeId` + `text`）：
     * 其余字段（会话标题、更新时间）不进这个判断 —— 它们不是消息的属性，
     * 放在这里会让每次保存都判成「变了」（详见 `MessageFtsManager` 关于冗余列的说明）。
     */
    fun plan(indexed: List<IndexedFtsRow>, desired: List<DesiredFtsRow>): FtsIndexPlan {
        // 同一 messageId 有多行是异常状态（一条消息只该有一行索引）。
        // 保留第一行、其余计入删除 —— 否则重复行会随每次保存累积，索引只增不减。
        val existingByMessage = LinkedHashMap<String, MutableList<IndexedFtsRow>>()
        indexed.forEach { existingByMessage.getOrPut(it.messageId) { mutableListOf() }.add(it) }

        val deleteRowIds = mutableListOf<Long>()
        val refresh = mutableListOf<FtsRefresh>()
        val insert = mutableListOf<DesiredFtsRow>()

        val desiredIds = HashSet<String>(desired.size)
        desired.forEach { want ->
            desiredIds.add(want.messageId)
            val have = existingByMessage[want.messageId]
            if (have == null) {
                insert.add(want)
            } else {
                val keep = have.first()
                have.drop(1).forEach { deleteRowIds.add(it.rowid) }
                // 文本或所属节点变了才动。**只比这两个** —— 见方法注释。
                if (keep.text != want.text || keep.nodeId != want.nodeId) {
                    refresh.add(FtsRefresh(keep.rowid, want))
                }
            }
        }

        // 已索引、但不再需要（消息被删 / 文本变空 / 会话变了）
        existingByMessage.forEach { (messageId, rows) ->
            if (messageId !in desiredIds) {
                rows.forEach { deleteRowIds.add(it.rowid) }
            }
        }

        return FtsIndexPlan(deleteRowIds = deleteRowIds, refresh = refresh, insert = insert)
    }
}
