// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文弹窗样式
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
//
// 两套外观二选一,由「偏好设置 → X 定制 → 上下文用量圆环」下的选择器决定:
//   · CLASSIC — 已用占比为主指标(百分比 + 构成条),X 原有样式
//   · CODEX   — 对齐 OpenAI Codex /status 卡片:剩余额度为主指标 + 20 段进度条
//
// ⚠️ 该枚举经 kotlinx.serialization 持久化进 DisplaySetting。
// 默认值改动会让老用户升级后外观突变,新增枚举项时也须保留既有 SerialName。
package me.rerere.rikkahub.x.context

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ContextUsageDialogStyle {
    /** 已用占比样式(X 原有,默认)。 */
    @SerialName("classic")
    CLASSIC,

    /** Codex /status 卡片样式。 */
    @SerialName("codex")
    CODEX,
}
