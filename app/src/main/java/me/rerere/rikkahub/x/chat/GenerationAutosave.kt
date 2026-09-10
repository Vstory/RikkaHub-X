package me.rerere.rikkahub.x.chat

/**
 * [X-custom] 生成过程自动保存 —— 间隔常量与钳制。
 *
 * **要解决的问题**:一次发送期间内容只更新内存,直到结束才落库(流式与工具执行窗口期
 * 长度可达数分钟到数小时)。此前 X 已补了「停止 / 异常中断」的落库,但**进程被系统杀掉**
 * 时那些收尾路径根本不会执行 → 已生成部分全部丢失。本项按固定间隔落库,把损失上限
 * 收敛到「一个间隔」。
 *
 * **为什么单独钳制**:间隔是用户可配数值,若被改成 0 或负数,ticker 会退化成
 * 无间隔死循环写库(而写库是**全量行写**,会话越大越贵)。故消费端不信任配置,
 * 一律经 [clampGenerationAutosaveInterval] 收敛到安全区间。
 */
object GenerationAutosave {
    /** 间隔下限(秒)。低于此值写库开销可能压过收益。 */
    const val MIN_INTERVAL_SECONDS: Int = 5

    /** 间隔上限(秒)。再大则单次丢失量过多,失去保护意义。 */
    const val MAX_INTERVAL_SECONDS: Int = 600

    /** 默认间隔(秒),与 `DisplaySetting.generationAutosaveIntervalSeconds` 保持一致。 */
    const val DEFAULT_INTERVAL_SECONDS: Int = 10

    /**
     * 把任意配置值收敛到 [MIN_INTERVAL_SECONDS] ~ [MAX_INTERVAL_SECONDS]。
     *
     * 配置来源不可信(用户手输、备份导入、历史版本默认值) → **消费端必须过这一层**,
     * 不能只依赖 UI 侧校验。
     */
    fun clampGenerationAutosaveInterval(seconds: Int): Int =
        seconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)

    /** 供 UI 提示文案使用:区间说明。 */
    fun intervalRangeDescription(): String = "$MIN_INTERVAL_SECONDS ~ $MAX_INTERVAL_SECONDS"
}
