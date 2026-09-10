# RikkaHub-X 定制点清单(X 与上游 rikkahub 差异对账表)

> **用途**:下次合并上游(`git fetch upstream && git merge upstream/master`)前,用本清单逐项对账:
> 凡冲突文件落在下表 → 按「定制内容」保留 X 逻辑;凡上游改动与 X 定制叠加 → 手工合。
> **维护规则**:见下「🔁 改动落地铁律」——**每次 X 定制改动,提交前必须同步本清单 + `CHANGELOG.md`**。
> **标签约定**:侵入上游文件的定制,文件头已加 `// [X-custom] ...` 注释,`grep -rl "X-custom"` 可随时列出;
> 独立新文件已进 `me.rerere.*.x` 包(上游永无此文件,merge 零冲突)。
> 基准 commit: 2026-09-09 `29cd9aa1`(此后 X 侧每次改动需回填本表)。

## 🔁 改动落地铁律(2026-09-10 立)

> 一次 X 定制改动 + 提交 = **三件事一起做**,缺一即视为未完成:
>
> | # | 动作 | 落到哪 | 目的 |
> |---|---|---|---|
> | 1 | 代码/资源改动 | — | 功能本身 |
> | 2 | **定制点登记**(加/改一行) | 本文件 A/B/C 表 | **merge 上游时对账,防止定制被覆盖** |
> | 3 | **追加变更条目** | `CHANGELOG.md` | **面向使用:回答"这版跟官方有什么不一样"** |
>
> **②③ 的取舍边界**:
> - ✅ **必写 CHANGELOG**:用户可见的功能、修复、行为变化、开关、UI、命名、包名、签名策略
> - ⚪ **可省/并入当日条目**:纯内部重构(无外部可见变化)、文档微调、CI 内部细节、注释
> - ❌ **两处都不写**:上游镜像提交(随 merge 进来的上游 commit,非 X 定制)
>
> **CHANGELOG 条目格式约定**(照抄现有风格):
> ```markdown
> ### ✨ 一句话说清改了什么
> - 要点式说明(用户视角:改变了什么行为 / 解决什么问题)
> - 注意事项 / 安装提示(如涉及)
> - commit: `abc1234`、`def5678`
> ```
> - 同日多次改动 → **追加进同一日期段落**,不新开日期
> - commit hash 用反引号包,且必须是**真实存在**的(写完可 `git cat-file -e <hash>` 复核)
> - 引用上游 issue:`#1736` 允许(正常引用);**非 issue 编号**(Actions 运行号/本地任务号)不要裸写成 `#N` → 改写为「Actions run 20」(见知识库 git 铁律 10)
>
> **提交前自查**(两条命令即可):
> ```bash
> # ① 改了代码却没带上 X-CUSTOM.md / CHANGELOG.md → 补上
> git diff --cached --name-only
> # ② 复核本次 commit hash 是否真实存在(写进 CHANGELOG 的必须可验证)
> git cat-file -e <hash>^{commit} && echo OK
> ```


## A. 独立新文件(上游没有 → 已 .x 化 / 无需包)

| 文件 | 说明 | 隔离方式 |
|---|---|---|
| `workspace/.../x/WorkspaceArchive.kt` | 工作区归档核心(447行) | ✅ `me.rerere.workspace.x` |
| `workspace/.../x/WorkspaceArchiverTest.kt` | 归档单测 | ✅ `me.rerere.workspace.x` |
| `app/.../x/sync/OfficialBackupCompat.kt` | 官方备份库→X 结构适配(方案A导入器) | ✅ `me.rerere.rikkahub.x.sync` |
| `app/.../x/context/ContextWindowTable.kt` | 上下文容量表解析/查找(exact→rules→default) | ✅ `me.rerere.rikkahub.x.context` |
| `app/.../x/context/ContextWindowRepository.kt` | 容量表仓库:assets 内置兜底 + 远端静默刷新(raw/jsDelivr) | ✅ `me.rerere.rikkahub.x.context` |
| `app/.../x/context/ContextUsageCalculator.kt` | 上下文已用量:最近 usage.promptTokens + 输入估算 | ✅ `me.rerere.rikkahub.x.context` |
| `app/.../x/ui/ContextUsageRing.kt` | 上下文用量圆环组件(Codex 风格,分档变色) | ✅ `me.rerere.rikkahub.x.ui` |
| `app/.../x/chat/ChatDraftStore.kt` | 会话输入草稿持久化(issue 1715):按会话 key 存 SharedPreferences,纯文本,进入恢复/防抖保存/发送后自动清 | ✅ `me.rerere.rikkahub.x.chat` |
| `app/.../x/compress/Utf16Safe.kt` | UTF-16 安全切割(head/tail/chunks/halves),避免切出孤立代理字符(移植 kelivo) | ✅ `me.rerere.rikkahub.x.compress` |
| `app/.../x/compress/CompressBudget.kt` | 压缩请求字符预算:模型窗口 →(1-30%)×1.6,硬上限 10 万字符(移植 kelivo) | ✅ 同上 |
| `app/.../x/compress/CompressRetry.kt` | 上下文超限识别 + 递归二分重试(树共享 5 次切分/单段下限 512)(移植 kelivo) | ✅ 同上 |
| `app/.../x/compress/CompressChunker.kt` | 压缩按字符预算分块 + 摘要多层合并打包(移植 kelivo) | ✅ 同上 |
| `app/src/test/.../x/compress/*Test.kt` | X 压缩链单测 29 用例(UTF-16/预算/重试/分块) | ✅ 同上 |
| `app/.../assets/context-windows/context-windows.json` | 内置容量表(与远端 model-contexts/ 同构,离线兜底) | 资源文件 |
| `.github/google-services.placeholder.json` | CI 占位配置 | 非代码,仓库文件+cp |
| `scripts/pangu_format_resources.py` | 盘古之白资源格式化脚本 | 工程脚本 |
| `CHANGELOG.md` | 面向使用的变更日志(定制增量流水):上游无此文件;若上游日后新增同名文件 → merge 时保留 X 版并按上游格式续写 | 仓库文档 |

## B. 侵入上游文件(文件头已加 [X-custom] 标签, merge 保留)

| 上游文件 | X 定制内容 |
|---|---|
| `RikkaHubApp.kt` | 压缩结果升级系统通知 + 压缩前请求通知权限 |
| `data/ai/tools/local/JavascriptTool.kt` | eval_javascript 工具资源安全与错误处理加固 |
| `data/datastore/PreferencesStore.kt` | 压缩会话反馈设置 + 语音输入提示音/振动增强开关 + 上下文用量圆环开关(enableContextUsageRing,默认关) |
| `data/db/dao/ConversationDAO.kt` | 会话级模型覆盖字段 model_id |
| `data/db/entity/ConversationEntity.kt` | 会话级模型覆盖字段 model_id |
| `data/model/Conversation.kt` | 会话级模型覆盖字段 model_id |
| `data/repository/ConversationRepository.kt` | 会话级模型覆盖持久化/读取 |
| `data/repository/WorkspaceRepository.kt` | 工作区导出/导入归档编排(调 me.rerere.workspace.x) |
| `data/sync/BackupManager.kt` | 官方备份库导入适配(x.sync.OfficialBackupCompat) |
| `data/sync/S3Sync.kt` | 云备份(S3)上传对象名 `RikkaHub-X_backup_<ts>.zip` 与上游区分;列表双前缀兼容历史 `backup_` |
| `data/sync/webdav/WebDavSync.kt` | 云备份(WebDAV)上传文件名同左;列表双前缀兼容历史 `backup_` |
| `data/ai/mcp/McpConfig.kt` | MCP 服务器名解耦:`displayName`(本地显示名,可中文)+ `name`(内部标识,ASCII);协议/工具链路仍用 `name`=上游逻辑 |
| `ui/components/ai/McpPicker.kt` | MCP 服务器显示改 `uiName`(displayName 优先,可中文) |
| `ui/pages/setting/SettingMcpPage.kt` | MCP 设置双名称输入(显示名+内部标识,ASCII 违规=上游同警告);导入非 ASCII key 自动生成内部名;展示用 uiName |
| `service/ChatService.kt` | 压缩通知 + 会话级模型覆盖优先读取 + 会话切换消息保持修复(上游 issue #1314/#1663/#1820: 停止/异常落库,initializeConversation 防覆盖流式态) + **压缩执行健壮性**(`compressConversation`:窗口感知字符预算 / 超限递归二分重试 / 多层合并收敛为单段摘要,调 `x.compress.*`;保留段仍用上游 `keepRecentMessages=32` 原始消息逻辑,未做 user-message 计数改造) |
| `data/ai/transformers/OcrTransformer.kt` | OCR 仅识别本轮新增图片,历史图片只读缓存/占位,纯文字续聊不再强制识别(上游 issue #1736);缓存窗口 3天/64→30天/256 |
| `ui/pages/chat/ChatList.kt` | 消息来源显示增强(上游 issue #1805):providerNameById 反查所属路由 + 模型切换分隔线(相邻助手回复 modelId 变化时居中提示) |
| `ui/components/message/ChatMessage.kt` | 消息来源显示增强(上游 issue #1805):新增 providerName 参数,助手消息末尾灰字落款"路由名 · 模型名" |
| `ui/components/ai/ChatInput.kt` | 语音输入提示音/振动 + 会话模型切换 UI + 上下文用量圆环挂点(容量/用量计算 + onCompressContext 参数 + 点击弹压缩) |
| `ui/pages/chat/ChatPage.kt` | 压缩通知权限请求 + 会话模型切换 UI + 圆环压缩回调透传(vm.handleCompressContext) + 输入文本变化 snapshotFlow 监听上报草稿防抖保存(issue 1715) |
| `ui/pages/chat/ChatVM.kt` | 会话级模型切换逻辑与 UI 即时刷新 + 输入草稿(issue 1715):进入恢复、防抖保存(onDraftInputChanged 600ms)、onCleared 兜底落盘、编辑态不落草稿 |
| `RouteActivity.kt` | 偏好设置新增 X 定制子路由(Screen.SettingPreferencesXCustom) |
| `ui/pages/setting/SettingPreferencesPage.kt` | 偏好设置板块新增「X Custom」入口行 |
| `ui/pages/setting/SettingPreferencesXCustomPage.kt` | [新增] X 定制聚合页:收纳全部带开关的 X 定制项(语音输入提示音/振动增强/压缩会话反馈/上下文用量圆环);2026-09-10 由 GeneralPage 迁入 |
| `ui/pages/backup/tabs/ImportExportTab.kt` | 备份导出 SAF 文件名前缀改 `RikkaHub-X_backup_`(与上游区分;导入不卡名兼容上游格式) |
| `ui/pages/extensions/workspace/WorkspaceDetailPage.kt` | 工作区导出/导入归档 UI;导出文件名前缀 `RikkaHub-X_workspace_` |
| `ui/pages/extensions/workspace/WorkspaceDetailVM.kt` | 工作区导出/导入归档 VM |

## C. 构建/资源/基础设施类改动(无代码标签, merge 时人工留意)

| 文件 | 说明 |
|---|---|
| `app/build.gradle.kts` | applicationId=`me.rerere.rikkahub.x`(namespace 仍上游), debug suffix |
| `gradle/libs.versions.toml` | +commons-compress 等归档依赖 |
| `workspace/build.gradle.kts` | +归档依赖 |
| `app/schemas/.../25.json` | Room schema(会话级 model_id 列 → X identity_hash 分叉) |
| `app/src/main/res/values*/strings.xml` ×7 | X 功能文案(归档 UI/语音/模型切换) |
| `.github/workflows/*.yml` ×2 | CI 适配 |
| `.gitignore` / `README.md` | 项目级 |
| `CHANGELOG.md` | X 定制变更日志(新增文件);`README.md` 已精简为仅 fork 自述(删上游多语言翻译) |
| `ExampleInstrumentedTest.kt` | 测试断言包名 .x |

## merge 上游操作手册

1. `git fetch upstream && git merge upstream/master`
2. 冲突文件逐个查上表:
   - A 类:上游不可能有同名 → 基本零冲突,冲突反查是否上游意外建同名
   - B 类:按「定制内容」列保留 X 逻辑 + 叠加上游新改动;改完看文件头标签确认仍在
   - C 类:字符串/依赖冲突取并集;schema 冲突优先 X(见 data/db migrations 规则)
3. 若上游把 B 类某文件大重构 → 该文件 X 定制可能需重做,按标签+本表评估是否把定制抽成 .x 扩展
4. 合完 `grep -rl "X-custom"` 复核所有标签文件都在,更新本表基准 commit
