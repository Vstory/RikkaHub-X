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

    // ── 用户动作计数（2026-09-13 从 Firebase Analytics 迁入）──
    //
    // ## 为什么要迁
    //
    // 上游原本用 `FirebaseAnalytics.logEvent` 记这 5 个动作（各 5 处，全在 ChatVM）。
    // 那是**唯一**在统计「用户实际怎么用这个应用」的地方。X 决定整体移除 Firebase
    // （2026-09-13，用户定：既然走诊断日志这条路，Firebase 就全部去掉），
    // 于是这些计数**必须有人接** —— 否则「工具审批用了多少次」这类问题从此无从回答。
    //
    // ## 与上游那套的两处差别（刻意，非疏漏）
    //
    // ① 落点是 `chat.log`（诊断域文件），**受诊断开关管辖**：开关关着就是零记录。
    //    上游那套是无条件上报的 —— 而 X 的口径一直是「关掉诊断 = 与上游一致的静默」。
    // ② 事件名跟着本域的命名规则走（`域.动作.结果`），不再是 `ai_send_message` 这种
    //    下划线式。检索时按 `chat.message.` 前缀即可一网打尽。
    //
    // ## 这 5 条只记「动作发生」，不带任何参数
    //
    // 与上游一致：它们是**计数**，不是内容。聊天正文一律不进日志
    // （见 `X-CUSTOM.md` §日志与诊断约定的「不记自由文本」）。

    /** 用户发出了一条消息。 */
    const val MESSAGE_SENT = "chat.message.sent"

    /** 用户编辑并提交了某条消息。 */
    const val MESSAGE_EDITED = "chat.message.edited"

    /** 用户在某条消息处触发了重新生成。 */
    const val MESSAGE_REGENERATED = "chat.message.regenerated"

    /** 用户对工具调用做了审批（同意或拒绝）。 */
    const val TOOL_APPROVED = "chat.tool.approved"

    /** 用户回答了工具调用（把结果填回去）。 */
    const val TOOL_ANSWERED = "chat.tool.answered"

    /** 全部事件名：单测据此校验命名规则与唯一性。 */
    val ALL: List<String> = listOf(
        MODEL_PICK_KEPT,
        MODEL_PICK_RESTORED,
        MODEL_NONE,
        MESSAGE_SENT,
        MESSAGE_EDITED,
        MESSAGE_REGENERATED,
        TOOL_APPROVED,
        TOOL_ANSWERED,
    )
}
