# RikkaHub-X 项目须知(本 fork 专属)

> **本文件不在上游仓库中**,是 RikkaHub-X 专属的 AI 代理速查卡。
> `AGENTS.md` 是上游通用说明(模块结构/概念/i18n);**本文件记录本 fork 的特有约定** ——
> 分支模型、CI、提交铁律、推送方式、构建环境、文档分工。
>
> 权威细节在知识库(见 §7),本文件是**仓库内速查**,不重复维护同一份内容。

## 1. 仓库身份

- 本仓库 `Vstory/RikkaHub-X` 是 **`rikkahub/rikkahub` 的 fork**(个人自用定制),**不是上游**。
- 与上游的差异全部是刻意的定制;定制点有登记表(见 §7),**改动前先查表**,避免覆盖已有定制。
- 应用包名 `me.rerere.rikkahub.x`(与官方 `me.rerere.rikkahub` 不同,可同机共存);
  仓库内上游原有与定制有关的路径与标识为 `RikkaHub-X`(应用名资源 `app_name`)。

## 2. 分支模型

| 分支 | 用途 |
|---|---|
| `main` | **定制主线**。X 全部定制提交都在此 |
| `master` | **上游纯镜像**,随 `git fetch upstream` 更新;**不要在此提交** |

**开发流程(约定,2026-09-10 定)**:

```
开特性分支(feat/xxx)
  → 推送并等 CI(编译 ~4 分 / 守护 ~4~5 分)
  → git switch main && git merge --ff-only feat/xxx    # 快进,不产生 merge commit
  → bash dev-project/push-x.sh                         # 推 main
  → 删分支(本地 + 远程)
```

- **新功能一律在特性分支开发**,不在 `main` 直接改功能。
- **合并用 `--ff-only`**:快进不产生新 tree,分支上验证过的提交原样进 main
  —— 这也是为何 `compile-check` 在 main 上不重复跑(见 §3)。
- 分支使命结束即删,仓库只留 `main` + `master`。

## 3. CI 三个 workflow(都在 `.github/workflows/`)

| workflow | 触发 | 跑什么 | 实测耗时 |
|---|---|---|---|
| `compile-check.yml` | push 到**特性分支**(`branches-ignore: [main, master]` + `paths`) | 只 `:app:compileDebugKotlin` —— 不 lint / 不 R8 / 不打包 / 不签名 / 不发布 | **~4 分** |
| `x-custom-guard.yml` | push 命中守护相关 `paths`(见文件内清单)+ dispatch | `:workspace:testDebugUnitTest` + `:app:testDebugUnitTest`(X 定制回归用例) | **~4~5 分** |
| `daily-build.yml` | **仅手动 dispatch**(`build_type`: `nightly`/`release`) | `assembleRelease` + 签名 + 发布/清理 nightly release | **~9~11 分** |

**为什么这样分**:`daily-build` 的 8.9 分钟里有 **243 秒**花在只与发布相关的任务上
(`lintVitalRelease` 154.6s + `minifyReleaseWithR8` 61.4s + `expandReleaseArtProfileWildcards` 27.1s),
而真正编译 app 的 `compileReleaseKotlin` 只 26.8 秒 —— 故拆出 `compile-check` 作快通道。

**已知取舍**:

- `compile-check` 不管 main:快进合并后 main 的 tip 与分支上已验证的提交**逐字节相同**,再编一次是
  可证明的重复。若要单独验 main 的编译,手动 dispatch 它(支持 `workflow_dispatch`)。
  覆盖缺口:合并上游(master→main)会产生 merge commit,tree 与任何已验证提交都不同
  —— 此时 main 由 `daily-build` 的 `assembleRelease` 兜底。
- `x-custom-guard` **在 main 上照常跑**:合并上游会改到它守护的上游文件,那时 main 的守护是真需要。
- 三者 Gradle 缓存 key **统一为** `{os}-gradle-{hash}`(勿加各自前缀,否则各持一份冷缓存)。
- 三者均声明 `run-name`;push 触发的 run 默认标题取 commit message 首行,不声明的话同一提交
  在多分支重跑会显示成完全相同的标题。

## 4. 提交与推送铁律

### 🚫 commit / merge message 禁一切 `#数字`

本仓库是 fork,提交里的 `#N` 会解析到**上游编号空间**,在**上游 issue 时间线创建 `referenced` 事件**,
且**永久不可移除**(实测:已 amend 成不可达的提交,事件仍在 → 改写历史救不回)。

- 不区分本意:引用上游 issue 禁、Actions 运行号禁、本地任务号禁、跨仓 `owner/repo#N` 同样禁。
- 唯一允许写法 = **纯文本不带 `#`**:写「上游 issue 1805」「Actions run 20」,或干脆不写。
- 仓库已装本地 `.git/hooks/commit-msg` 机械拦截,命中即 abort(`-m` / `-F` / merge 全覆盖)。
- 提交前自查:`git log -1 --format=%B | grep -E '#[0-9]+'` 须为空。

### 提交信息写法

- **不要 AI 汇报体**:禁小标题(`需求:/背景:/改动:/验证:`)、禁指认对话方(`用户指出/要求`)、
  禁自我检讨与辩护语。直接陈述技术事实:为什么改 / 改了什么 / 影响。
- **多行或含反引号 → 必须用 `-F`**:
  ```bash
  cat > /tmp/msg <<'EOF'
  fix(x): 一句话说清
  <空行>
  为什么改、影响。
  EOF
  git commit -F /tmp/msg
  ```
  双引号 `-m "… `code` …"` 会触发 shell 命令替换,把内容吞成空档(实测踩过)。
- 粒度:每逻辑步一次 commit,不攒批。

### 推送

```bash
bash dev-project/push-x.sh                            # 推当前分支
bash dev-project/push-x.sh --branch feat/xxx          # 推指定分支
bash dev-project/push-x.sh --branch feat/xxx --dry-run # 只看将推什么
```

- 脚本在 `dev-project/`(**已 gitignore,不进仓库**);token 从 `/workspace/tokens/Github/pat/token.txt`
  或 `GH_TOKEN` 读取,**仅存变量、一次性带凭据 URL、输出脱敏、不写 remote 配置**。
- **分支推送一律走它,不要手搓 token-URL**(手搓会绕过脱敏与跟踪引用同步)。
- 推送后用 `git status` 复核 remote 无凭据残留。

## 5. 构建与验证

- **本项目构建统一走 CI**,不在本地跑完整构建(用户定,2026-09-10)。
  日常改动推特性分支,`compile-check` 约 4 分钟给编译反馈。
- ⚠️ 但**静态自检必须本地先做**:新增代码用到的符号是否都已 import。曾因漏 `fillMaxWidth` 导入
  而 CI 失败(`Unresolved reference`),本可零成本发现。
- 若确实需本地构建(本机 arm64):Android SDK 的 `aapt` 是 x86_64 **不能执行**,
  核对产物一律用 `aapt2`(`gradle.properties` 的 `android.aapt2FromMavenOverride` 指向的 arm64 版)。
- `:web:buildWebUi` 是 app 编译的前置(`web:preBuild` 依赖它),故编译 app 必须备好 Node + pnpm。

## 6. 代码布局约定

- **X 定制新文件**放独立包:`me.rerere.rikkahub.x.*` / `me.rerere.workspace.x.*`
  —— 上游永无同名文件,merge 零冲突。
- **侵入上游文件**的定制,在文件头加 `// [X-custom] ...` 注释,便于随时
  `grep -rl "X-custom"` 列出并核对。
- 改动 X 定制 → 须同步知识库三件套(见 §7 的「改动落地铁律」)。

## 7. 文档分工(权威细节在知识库)

**位置**:`/workspace/Personal-Knowledge-Base/dev-guide/项目开发记录/RikkaHub-X/`
(用 `kb_search.py` 检索;本文件只是仓库内速查,不重复其内容)

| 文件 | 定位 |
|---|---|
| `X-CUSTOM.md` | **定制点对账表**(A 独立新文件 / B 侵入上游文件 / C 构建基础设施)+ **改动落地铁律**(含开发流程、提交铁律、落地顺序) |
| `使用向CHANGELOG.md` | **面向使用**:回答「这版跟官方有什么不一样」 |
| `CHANGELOG.md` | **开发过程沉淀**:决策背景 / 踩坑根因 / 实测数据 |
| `定制差异与上游合并指南.md` | merge 上游操作手册(流程 + 冲突决策表) |
| `仓库关系.md` / `共存改造方案.md` | 仓库关系 / 共存改造方案 |
| `待开发清单.md` / `上游Issue评估与开发计划-*.md` | 待办与上游 issue 评估 |

**改动落地铁律(简版)**:一次 X 定制改动 = ① 代码改动 + ② `X-CUSTOM.md` 登记 +
③ `使用向CHANGELOG.md` 追加条目。纯内部重构 / CI 细节 / 文档微调可只做 ①(或并入当日条目)。
详见 `X-CUSTOM.md` 顶部该节。

## 8. 本期踩坑速查(AI 易再犯)

| 坑 | 现象 | 正解 |
|---|---|---|
| workflow 的 dispatch 传 inputs | `422` | 只有**声明了 `inputs`** 的 workflow 才能传;未声明的传 `{"inputs":…}` 会 422 |
| 解析 workflow 的 `on:` | 取到 `None` | YAML 1.1 把 `on` 解析成布尔 `True`,须回退 `d.get(True)` |
| `run-name` 用错上下文 | workflow 直接失败 | 只能用 `github` 与 `inputs`;**禁 `head_commit.message`**(多行全文会截断标题) |
| `git log origin/<分支>..<分支> \|\| true` | 远端无该分支时**静默不推** | 错误被 `\|\| true` 吞成空 → 误判「无未推送 commit」;应回退与 `origin/main` 比对 |
| 双引号 `-m` 包反引号 | 提交信息出现空档 | 用 `-F /tmp/msg`(定界符加单引号)或单引号 `-m '…'` |
| commit message 里的 `#N` | 上游 issue 被永久 `referenced` | 一个 `#数字` 都不写;仓库 hook 会拦 |

## 9. 安全纪律

- **凭证零回显**:token / 密钥原文绝不进工具输出、聊天、记忆、git。只用
  `KEY=$(cat 路径)` 变量赋值,确认只显掩码 `前4****后4`。
- 命令文本**禁写死凭证值**,一律变量 / 文件路径引用。
- 轮换密钥后 `git status` 复核无凭证被跟踪。
- `dev-project/` 已 gitignore(含本地工具与 tokens),**永不推送**。
