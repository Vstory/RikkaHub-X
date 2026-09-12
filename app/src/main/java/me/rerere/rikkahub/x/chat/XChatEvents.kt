// [X-custom] RikkaHub-X 会话域：事件名常量（用于日志检索与断言）
package me.rerere.rikkahub.x.chat

/**
 * 会话域的日志事件名。
 *
 * **为什么用常量而不是随手写字符串**：这些名字是**排查时的检索键**。用户从真机
 * 导出一段诊断发给开发者时，靠的就是在文本里定位 `chat.model.none` 这样的词；
 * 名字一散落成字面量，就会出现同一件事多种写法并存，日志再也搜不干净。
 *
 * **命名规则**：`域.动作.结果`，全小写点分。加新事件时照此续写，
 * 并在单测 [XChatEventsTest] 的清单里登记 —— 那份清单同时是「会话域有哪些可观测点」的答案。
 *
 * 与 [me.rerere.rikkahub.x.storage.XStorageEvents] / `XContextEvents` / `XSyncEvents`
 * 同构：**一个域一个对象**，便于按域导出与检索。
 */
object XChatEvents {

    // ── 会话级模型选择 ──

    /**
     * 新建会话时**保留**了内存态里已选的模型。
     *
     * 出现这个词说明：用户在会话打开过程中就选好了模型（通常因为首次开库较慢），
     * 而初始化差点把它抹掉。**这条日志是那个竞态的活证据** ——
     * 频繁出现即说明开库耗时可观。
     */
    const val MODEL_PICK_KEPT = "chat.model.pick_kept"

    /** 从持久化的上次选择里恢复了该会话的模型（会话尚未落库时的兜底）。 */
    const val MODEL_PICK_RESTORED = "chat.model.pick_restored"

    /**
     * 会话 / 助手 / 全局**都没有可用的聊天模型**，生成被拒。
     *
     * 用户侧表现为「消息发出去了但永远没有回复」，而错误文案只有一句
     * `Message send failed` —— 看不出原因。故单独留一条可检索的记录。
     */
    const val MODEL_NONE = "chat.model.none"

    /** 全部事件名：单测据此校验命名规则与唯一性。 */
    val ALL: List<String> = listOf(
        MODEL_PICK_KEPT,
        MODEL_PICK_RESTORED,
        MODEL_NONE,
    )
}
