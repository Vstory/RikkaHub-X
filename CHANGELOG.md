# 变更日志

> **RikkaHub X** 是 [RikkaHub](https://github.com/rikkahub/rikkahub)(上游官方项目)的个人定制 fork。
> 本文件记录 **X 相对上游的定制增量**(用户可见的功能 / 修复 / 共存改造),按日期倒序。
> **上游本身的改动不在此列** —— 请见上游仓库的 release 说明。
>
> **版本基线**:跟随上游 `versionName`(当前 **2.5.1** / `versionCode` **186**);构建产物见
> [nightly release](https://github.com/Vstory/RikkaHub-X/releases/tag/nightly)。
>
> **文档分工**(避免重复维护):
> | 文件 | 定位 |
> |---|---|
> | **本文件** | 面向使用的变更流水:回答"这版跟官方有什么不一样" |
> | `X-CUSTOM.md` | 定制点对账表:merge 上游时逐项核对,防止定制被覆盖 |
> | 知识库 `RikkaHub-X/CHANGELOG.md` | 开发过程沉淀:决策背景 / 踩坑根因 / DB 迁移影响 |

---

## 2026-09-10

### 🔧 压缩执行健壮性 —— 手动压缩更可靠

**触发方式不变**(「压缩历史」菜单 / 点击上下文用量圆环),重做的是"按下确认之后发生什么"。
策略移植自 [kelivo](https://github.com/Chevey339/kelivo) 的压缩实现,保留段沿用 RikkaHub 原逻辑。

| 改进 | 上游原状 | 现在 |
|---|---|---|
| 分块依据 | 按**消息条数** 256,与模型窗口无关(单块理论可达 51.2 万字符) | 按**模型窗口**反推字符预算(窗口 ×70%×1.6,硬上限 10 万字符) |
| 超限处理 | **无兜底**,超限即整次压缩失败 | 自动识别超限错误 → 二分重试(共享 5 次切分,单段下限 512 字符) |
| 压缩产物 | N 块 → **N 条**独立摘要直接插入,零散且可能矛盾 | 多层合并(≤8 轮)→ 收敛为**单段摘要** |
| 文本截断 | 按 UTF-16 码元截断,可能切出孤立代理字符(emoji 场景被 API 拒/乱码) | UTF-16 安全切割,不切断代理对 |

- **保留段未改动**:仍是最近 32 条**原始消息**(与上游一致)
- 窗口容量复用上下文圆环的同一份数据源,无新增依赖
- 新增 29 个 JVM 单测(含"上游切出孤立代理 vs X 版安全"对照用例)
- commit: `e6e4c56b`(.x 基建 + 单测)、`7a2f90ed`(ChatService 接入)

### 🤖 CI 构建签名策略调整

- **默认(nightly)= 每次随机临时签名**:构建时用 `keytool` 现场生成随机口令的开发签名,
  DN 带时间戳(如 `CN=RikkaHubX Nightly <ts>`),不再依赖仓库 secrets
- **手动选 `release` = 正式固定签名**:需在仓库 secrets 配 `KEY_BASE64` / `SIGNING_CONFIG`
- `workflow_dispatch` 新增 `build_type` 输入(nightly / release)
- 修复:随机签名 key 密码不匹配、DN 字段未拆分、旧 asset 清理步 `xargs` 占位符报错
- ⚠️ **nightly 安装须知**:签名每次不同 → **升级须先卸载旧版**(不能覆盖安装)
- commit: `4ebabfd5`、`7443650b`、`14505ede`、`be87b26b`

### 📄 文档精简

- 根目录**仅保留 `README.md`**(fork 自述 + X 定制清单),删除上游多语言 `README_ZH_CN.md` / `README_ZH_TW.md`
  —— 那两份是上游 README 的整篇翻译,与 fork 实际差异不符且易误导
- commit: `c4d785e6`

---

## 2026-09-09

### ✨ 会话级独立模型切换

- 每个会话可单独指定模型,互不影响;解析优先级 `会话模型 → 助手模型 → 全局默认`
- 历史会话未设置则自动回退,**重启不丢**(持久化于 Room DB `conversationentity.model_id` 列)
- 修复:切换后 UI 不即时刷新 + 落库覆盖丢失(改为先同步内存态再落库)
- commit: `9924573d`、`ebcda576`、`ba7189f3`、`08d669ce`

### ✨ 官方 v25 备份库导入适配

- 官方与 X 的 DB version 同为 25 但 schema 分叉(X 多 `model_id` 列)→ Room 校验失败,
  官方含数据库备份无法直接导入
- 方案:识别官方 v25 库 → 补齐 `model_id` 列 + 改写 identity_hash → 走「恢复备份」入口**透明导入**
- 官方 ≤v24 旧备份无需适配(迁移链同源);settings.json / files 与官方同源,零处理
- commit: `f80b29e4`

### 💬 会话输入草稿自动保存

- 输入内容按会话持久化:切窗口 / 切走再回来 / 杀进程重进都不丢
- 发送或手动清空后自动清除,不会"幽灵回填";仅纯文本(附件不纳入)
- commit: `af50d89e`

### 🔘 上下文用量圆环

- 输入区实时显示当前会话上下文用量,分档变色预警,**点击可快速压缩**
- 开关收口在「设置 → 偏好设置 → X Custom」,**默认关**
- 配套:远端上下文容量表(内置资源兜底 + 远端静默刷新)
- commit: `0a3bc4d6`、`54c2593d`、`50d9dfd6`、`cb671a09`、`f97fbe0d`

### 🏷️ 消息来源显示

- 每条 AI 回复末尾灰字标注实际生成的「路由 · 模型」(如 `my-claude-relay · claude-sonnet-4-5`)
- 会话内切换模型后,新回复上方出现"以下回复由 xx 生成"分隔线,便于核对来源与成本归因
- commit: `71b0beba`

### 🖼️ 图片识别只针对新图

- 历史消息含图后继续纯文字对话,不再强制进入图片识别流程,也不对历史图片反复 OCR
- 避免打断对话、保住上下文前缀缓存
- commit: `c8e6d383`

### 🔊 语音输入提示音 / 振动开关

- 两个开关分别控制语音输入时的**提示音**(默认关 = 静音)与**振动**(默认开 = 强振)
- commit: `9cfe7111`、`530bd82f`、`189e4c6f`

### 🎯 共存改造:独立包名 + 应用名

- applicationId `me.rerere.rikkahub` → **`me.rerere.rikkahub.x`** → 与官方**同机共存**(异签名)
- 应用名 6 语言 → **`RikkaHub X`**;About 页标题同步加 X 标识
- Firebase **不用官方项目**(占位配置),待自有账号接入
- namespace / 源码包路径保持 `me.rerere.rikkahub`(减少上游 merge 冲突)
- commit: `0fe29f9c`、`811698eb`、`c1ed792c`、`94c94a7b`

### 🗂️ 工作区归档安全加固

- 归档解包:`symlink` 目标校验 + `tar` 条目 size 防线
- 导入:确认按钮先 `dismiss` 再执行导致导入被静默跳过 → 修正执行顺序
- commit: `42afb2c9`、`7e819c4c`、`87008c3c`

### 🤖 CI

- 停用 `daily-build` 定时自动构建(历史凌晨构建积累大量重复 APK),改为**仅手动触发**
- 无 secrets 降级构建(临时开发签名 + 占位 google-services.json)
- commit: `1208aedb`、`433f918d`、`55f3acc7`、`833cd152`

### 🔧 其它

- `eval_javascript` 工具加固(资源安全与错误处理)
- 上游字段改名适配 `conversationRepository` → `conversationRepo`
- 新增上游 issue 1114 的 markdown 表格解析回归测试
- commit: `7312f39d`、`01986ab7`、`2bf3797e`
