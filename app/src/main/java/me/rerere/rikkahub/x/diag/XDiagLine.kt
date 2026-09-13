// [X-custom] RikkaHub-X 诊断框架：语义事件的落盘行格式(纯逻辑)
package me.rerere.rikkahub.x.diag

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 语义事件落盘时的**一行格式** —— 紧凑 JSON,**一行即一条**。
 *
 * ## 为什么是一行紧凑 JSON,而不是多行可读
 *
 * 这份日志的主要读者是 AI(用户 2026-09-12 定的)。两条判据:
 *
 * | 判据 | 一行 JSON | 多行可读 |
 * |---|---|---|
 * | 边界 | 一行即一条,永远 | 消息自身含换行与 `{}`,得靠标记猜哪行是结尾 |
 * | 行的语义 | 与 logcat 行同构,**按行号即可定位** | 一条占几十行,行号失去意义 |
 * | 字段自明 | 字段名在,不必从排版倒推 | 靠约定(哪个标题对应哪个字段) |
 * | 体积 | 无缩进冗余 | 缩进 + 标题重复 |
 *
 * 对人读的代价不高:筛出那一行再格式化即可。**"一条记录恰好占一行"是硬约束** ——
 * 破了它,「按行号定位」与「grep 一条」都失效,而消息里带换行是常态(堆栈、JSON 正文)。
 *
 * ## 为什么带 `domain`
 *
 * ⚠️ 这条的理由在 2026-09-13 **变了**:原先所有域各占一个文件,域名从文件名就能看出来,
 * 那时带上它只是「复制一行出去(贴进聊天)时自明」的便利。合并成一条时间线之后,
 * **它是区分这条记录属于谁的唯一手段** —— 按域挑记录只剩读取端过滤这条路
 * (`grep '"domain":"storage"'`),某一行漏了 `domain` 就永远分不出属于谁,
 * 而它不会有任何编译或运行时报错。故 `check_x_diag_layout.py` 现在专门守着这条。
 *
 * 另:事件名的首段**并不等于**域名 —— 存储域的事件名一律以 `asset.` 开头
 * (`XStorageEventsTest` 钉住了这条),而域键是 `storage`。这条仍然成立。
 *
 * ## 为什么时间只到 `HH:mm:ss.SSS`
 *
 * 会话目录名里已有日期,而这一列只用来**排先后、与 logcat 对时**。带上日期会让每行
 * 多 11 字节,在「按域分文件、单域可能很多行」的场景里不划算。
 */
object XDiagLine {

    /**
     * 组一行。
     *
     * @param at 事件时刻;**显式传入**而不是在这里取当前时间 —— 否则这个纯函数没法测。
     */
    fun format(
        level: XLogRing.Level,
        domain: XDomain,
        event: String,
        message: String,
        at: Long = System.currentTimeMillis(),
        detail: String? = null,
    ): String = buildJsonObject {
        put("at", XLogRing.timeText(at))
        put("lvl", if (level == XLogRing.Level.WARN) "W" else "I")
        put("domain", domain.key)
        put("event", event)
        put("msg", message)
        // 可选的长文本(崩溃栈、异常栈)。**不截断**:栈被裁掉一半就失去了定位价值,
        // 而总量由调用方的体积阀兜底(见 XSurvivorLog.MAX_BYTES)。
        // 换行由 JSON 转义,故「一条记录恰好占一行」这条硬约束不受影响 —— 那正是
        // 当初选「一行紧凑 JSON」的理由(见类注释的对照表)。
        detail?.let { put("detail", it) }
    }.toString()
}
