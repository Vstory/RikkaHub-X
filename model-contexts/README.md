# RikkaHub X 上下文窗口容量表(远端)

本目录维护一份 **模型 → 上下文窗口容量(token)** 的远端表,供 App 动态拉取,用于渲染"上下文用量圆环"(上游 issue 1669 的 100% 分母)。

## 为什么放远端

- App 无需发版即可修正/补充模型容量;
- 用户自定义 modelId 也能随时补规则;
- **App 侧不内置任何表**(只带解释器):首次拉取成功后落盘缓存,之后离线可用;因此本文件是**唯一真源**,
  不存在"改了远端忘了改内置"的漂移。

## 文件

| 文件 | 说明 |
|---|---|
| `context-windows.json` | 容量表本体(App 拉取的就是它) |
| `README.md` | 本说明 |

## 表结构

```jsonc
{
  "schemaVersion": 1,          // 结构版本,App 据此校验兼容性
  "updatedAt": "2026-09-10",   // 数据更新时间(YYYY-MM-DD)

  // ★ 自描述块:本表声明「该怎么被读」。App 只实现 strategy 枚举对应的策略,
  //   按 lookupOrder 依次走阶段 —— 因此换策略/换顺序/改开关**无需发版**。
  "interpretation": {
    "strategy": "ordered-keyword",                    // 枚举,App 内实现
    "lookupOrder": ["exact", "rules", "default"],     // 查找阶段顺序
    "stripDateStamp": true,                           // 匹配前剔除日期戳
    "ignoreCase": true,                               // 忽略大小写
    "refreshIntervalMinutes": 1440                    // 客户端刷新 TTL(分钟)
  },

  "defaultContextWindow": 200000, // 全部未命中的兜底值
  "models": [                   // ① 精确 id(忽略大小写),优先级最高
    { "id": "o1", "contextWindow": 200000, "note": "..." }
  ],
  "rules": [                    // ② 关键字规则,按数组顺序首条命中
    { "keywords": ["claude"], "contextWindow": 200000, "note": "..." }
  ]
}
```

### 自描述块与「免发版」的边界(重要)

| 想改什么 | 是否需要发版 | 说明 |
|---|---|---|
| 加/改模型数值、补新家族 | **不需要** | 直接改本文件 |
| **加字段**(如 `maxOutputTokens`) | **不需要** | App 侧 `ignoreUnknownKeys = true`,旧版忽略未知字段 |
| 换匹配策略 / 换查找顺序 / 开关 | **不需要** | 改 `interpretation` —— App 已实现枚举对应的策略 |
| 新增一种 App 未实现的 strategy | **需要发版** | 见下:App 会**整表拒用**而非混用 |
| 整体换结构(如 v2 布局) | **需要发版** | 同样先拒用、保留上一份好表 |

**未识别 strategy 时的行为**:App **拒绝整张表并保留上一份好表**(缓存),而不是退回默认策略去解释一份
为别的策略设计的数据 —— 混用会静默给出错值,拒用只会「数值停在上一版」,后者可接受得多。

**明确的代价**:全新安装且**从未联网**时,App 没有任何表 → 容量未知 → **圆环不显示**(调用方对 `null`
的处理就是隐藏),而不是显示一个编造的数值。首次联网成功后自动恢复。

**代码永远测不了的部分**:「数值本身对不对」(某模型究竟是不是 1M)不是代码能断言的事实 ——
只能靠**联网核对官方文档 + 人工确认**。自动化只能守住「结构与排序自洽」这一层。

### 查找语义(App 端)

1. `models[]` 中 `id` 忽略大小写精确等于 modelId → 命中;
2. 否则按 `rules[]` **数组顺序**逐条检查:该条所有 `keywords` 都命中才采用(越靠前越具体,如 `claude sonnet 5` 放在 `claude` 前);
3. 都没有 → `defaultContextWindow`。

### keywords 命中规则(strategy = `ordered-keyword`)

**不是子串匹配** —— 旧的「每个 keyword 各自是子串」语义会让版本号错位命中
(`claude-3-5-sonnet` 里来自 3.5 的 "5" 会命中 `["claude","sonnet","5"]` → 错报 1M,实际 200K)。现行语义:

- 忽略大小写;匹配前先剔除日期戳(`2024-08-06` / `20241022` 等),除非 `stripDateStamp` 为 false;
- keyword 按**顺序**匹配、**互不重叠**;
- 连续的数字 keyword 合并为一个**版本组**,整体对齐 id 里的一个版本组,**允许取前缀**
  (故 `["claude","opus","4"]` 覆盖 4.6 / 4.7 / 4.8);
- 例:`claude-sonnet-5` → `["claude","sonnet","5"]` 命中 1M;
  `claude-3-5-sonnet` → **不命中**该条,落到 `["claude"]` = 200K。

> 语义细节与边界用例见代码 `app/.../x/context/ContextWindowTable.kt` 与测试
> `ContextWindowMatchingTest.kt`(语义层,用合成表,不含任何真实数值)。

## 怎么加 / 改

- **新增家族**:在 `rules` **末尾**(或按具体度插入合适位置)加一条,note 注明依据;
- **修正某型号**:优先加更靠前、更具体的规则,或直接进 `models` 精确段;
- **删模型**:对应规则/条目直接移除;
- 改完顺手把 `updatedAt` 更新为当天日期。

### 排序铁律(会被测试强制)

**更具体的规则必须排在更一般的规则之前**。若某条规则的 keywords 是**前面**某条的**前缀**
(如 `["claude"]` 排在 `["claude","sonnet","5"]` 之前),则后者**永远不可能命中** —— 是死规则。
测试 `ContextWindowsDataInvariantTest` 会直接把它判为失败。

### 自动化能守与不能守的边界

| 层 | 测试 | 守什么 |
|---|---|---|
| 语义层 | `ContextWindowMatchingTest` | 匹配**逻辑**(版本错位/日期戳/数字段/前缀/隔词),用**合成表** → 与真源无关、**永不漂移** |
| 数据层 | `ContextWindowsDataInvariantTest` | 真源**结构不变量**:可解析、schemaVersion 与 strategy 受支持、keywords 非空、数值在界内、**无死规则**、无重复规则 |
| —— | **（无自动化）** | **数值本身对不对** —— 只能联网核对官方文档 + 人工确认 |

## 数值口径(重要)

**铁律**:一律以**官方文档 / 模型卡 / 发布页**为准;与第三方说法不一致时取官方,并**在该条 `note` 里记下分歧**。

> 本 README **不再枚举各模型的数值** —— 那会成为真源之外的第二份数据,改了真源忘了改这里,文档就会撒谎,
> 而且没有任何 CI 能发现。**当前数值一律以 `context-windows.json` 为准。**

**已记录的口径分歧(顶住第三方说法)**:

| 模型 | 取值 | 第三方说法 | 依据 |
|---|---|---|---|
| Gemini 3 系 | **1M** | 有 2M / 10M 之说 | Gemini API 文档 + DeepMind 模型卡均为 1M |
| GLM-5.1 | **200K** | 一处称 1M | 官方发布稿 + OpenRouter/Kilo 等四处均为 200K |
| GPT-5.7 | **不登记** | 有 1.5M 传闻 | 截至 2026-09-10 尚未发布,不预登记 |

**更正历史(2026-09-10 联网复核)**:

- Doubao-Seed-2.1:`512K → 256K`(火山方舟模型表与 AI Hub 页均为 256k,此前采信「50 万 token」宣传口径)
- LongCat:`200K → 1M`(此前标「推断待核」)
- Muse:`128K → 1M`(此前标「推断待核」)
- 补录:Claude Fable 5 / Mythos 5、Qwen3.8-Max / Flash、Kimi K3、**Llama 4 系(此前完全无规则,
  Scout 被算成 200K,偏差 50 倍)**、LongCat-2.0、Meta Muse Spark 1.3

**仍标「推断待核」的**:`hy`(128K)、InternLM 的 `intern-s1`、Muse Glimmer 同族 —— 请以实测/官方为准修正。
