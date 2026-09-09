# RikkaHub-X 定制点清单(X 与上游 rikkahub 差异对账表)

> **用途**:下次合并上游(`git fetch upstream && git merge upstream/master`)前,用本清单逐项对账:
> 凡冲突文件落在下表 → 按「定制内容」保留 X 逻辑;凡上游改动与 X 定制叠加 → 手工合。
> **维护规则**:每次 X 改了上游已有文件或新增文件,必须同步更新本清单(加/改一行)。
> **标签约定**:侵入上游文件的定制,文件头已加 `// [X-custom] ...` 注释,`grep -rl "X-custom"` 可随时列出;
> 独立新文件已进 `me.rerere.*.x` 包(上游永无此文件,merge 零冲突)。
> 基准 commit: 2026-09-09 `29cd9aa1`(此后 X 侧每次改动需回填本表)。

## A. 独立新文件(上游没有 → 已 .x 化 / 无需包)

| 文件 | 说明 | 隔离方式 |
|---|---|---|
| `workspace/.../x/WorkspaceArchive.kt` | 工作区归档核心(447行) | ✅ `me.rerere.workspace.x` |
| `workspace/.../x/WorkspaceArchiverTest.kt` | 归档单测 | ✅ `me.rerere.workspace.x` |
| `app/.../x/sync/OfficialBackupCompat.kt` | 官方备份库→X 结构适配(方案A导入器) | ✅ `me.rerere.rikkahub.x.sync` |
| `.github/google-services.placeholder.json` | CI 占位配置 | 非代码,仓库文件+cp |
| `scripts/pangu_format_resources.py` | 盘古之白资源格式化脚本 | 工程脚本 |

## B. 侵入上游文件(文件头已加 [X-custom] 标签, merge 保留)

| 上游文件 | X 定制内容 |
|---|---|
| `RikkaHubApp.kt` | 压缩结果升级系统通知 + 压缩前请求通知权限 |
| `data/ai/tools/local/JavascriptTool.kt` | eval_javascript 工具资源安全与错误处理加固 |
| `data/datastore/PreferencesStore.kt` | 压缩会话反馈设置 + 语音输入提示音/振动增强开关 |
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
| `service/ChatService.kt` | 压缩通知 + 会话级模型覆盖优先读取 + 会话切换消息保持修复(上游 issue #1314/#1663/#1820: 停止/异常落库,initializeConversation 防覆盖流式态) |
| `ui/components/ai/ChatInput.kt` | 语音输入提示音/振动 + 会话模型切换 UI |
| `ui/pages/chat/ChatPage.kt` | 压缩通知权限请求 + 会话模型切换 UI |
| `ui/pages/chat/ChatVM.kt` | 会话级模型切换逻辑与 UI 即时刷新 |
| `ui/pages/backup/tabs/ImportExportTab.kt` | 备份导出 SAF 文件名前缀改 `RikkaHub-X_backup_`(与上游区分;导入不卡名兼容上游格式) |
| `ui/pages/extensions/workspace/WorkspaceDetailPage.kt` | 工作区导出/导入归档 UI;导出文件名前缀 `RikkaHub-X_workspace_` |
| `ui/pages/extensions/workspace/WorkspaceDetailVM.kt` | 工作区导出/导入归档 VM |
| `ui/pages/setting/SettingPreferencesGeneralPage.kt` | 压缩反馈设置项 + 语音输入设置板块 |

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
| `ExampleInstrumentedTest.kt` | 测试断言包名 .x |

## merge 上游操作手册

1. `git fetch upstream && git merge upstream/master`
2. 冲突文件逐个查上表:
   - A 类:上游不可能有同名 → 基本零冲突,冲突反查是否上游意外建同名
   - B 类:按「定制内容」列保留 X 逻辑 + 叠加上游新改动;改完看文件头标签确认仍在
   - C 类:字符串/依赖冲突取并集;schema 冲突优先 X(见 data/db migrations 规则)
3. 若上游把 B 类某文件大重构 → 该文件 X 定制可能需重做,按标签+本表评估是否把定制抽成 .x 扩展
4. 合完 `grep -rl "X-custom"` 复核所有标签文件都在,更新本表基准 commit
