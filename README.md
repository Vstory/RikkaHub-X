# RikkaHub X

> **RikkaHub X** 是 [RikkaHub](https://github.com/rikkahub/rikkahub)(上游官方项目)的个人定制 fork,仅自用,请优先支持上游。

## X 版在官方基础上增加 / 调整

- 🔀 **会话级独立模型切换**:每个会话可单独指定模型,互不影响;解析优先级 `会话模型 → 助手模型 → 全局默认`,历史会话未设置则自动回退,重启不丢
- 🔊 **语音输入设置**(常规设置 → TTS 上方):两个开关分别控制语音输入时的**提示音**(默认关 = 静音)与**振动**(默认开 = 强振,关 = 原版轻振)
- 📥 **官方备份导入**:可直接导入官方 RikkaHub 的含数据库备份(自动补齐 X 的 schema 差异列)
- 📦 **与官方同机共存**:包名 `me.rerere.rikkahub.x`、应用名 `RikkaHub X`,与官方数据隔离、互不干扰
- 🛡️ **eval_javascript 工具加固**(内存 / 日志 / 错误护栏)
- ⚙️ **CI 无 secrets 降级构建**(fork 无 secrets 也能产出 Release APK)
- 🔌 **不接入官方 Firebase 项目**(占位配置,待自有账号)
