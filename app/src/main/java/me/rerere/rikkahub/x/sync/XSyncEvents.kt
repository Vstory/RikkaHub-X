// [X-custom] RikkaHub-X 诊断框架：备份同步域的事件名
package me.rerere.rikkahub.x.sync

/**
 * 备份同步域（含官方备份兼容）的日志事件名。
 *
 * 规则同其他域：`域.动作.结果`，全小写点分，集中成常量以便检索。
 */
object XSyncEvents {

    /** 官方 RikkaHub 备份库已被适配为 X 结构（补 model_id 列）。 */
    const val OFFICIAL_ADAPTED = "sync.official.adapted"

    /** 适配被跳过：不是可识别的官方库，按原样导入。 */
    const val OFFICIAL_SKIP = "sync.official.skip"

    /** 适配过程中出错。 */
    const val OFFICIAL_ADAPT_FAIL = "sync.official.adapt_fail"

    val ALL: List<String> = listOf(OFFICIAL_ADAPTED, OFFICIAL_SKIP, OFFICIAL_ADAPT_FAIL)
}
