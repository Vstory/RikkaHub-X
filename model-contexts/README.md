# RikkaHubX 上下文窗口容量表(远端)

本目录维护一份 **模型 → 上下文窗口容量(token)** 的远端表,供 App 动态拉取,用于渲染"上下文用量圆环"(上游 issue 1669 的 100% 分母)。

## 为什么放远端

- App 无需发版即可修正/补充模型容量;
- 用户自定义 modelId 也能随时补规则;
- 离线/首启由 App 内置兜底表接管,本表只是"更新源"。

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
  "defaultContextWindow": 128000, // 全部未命中的兜底值
  "models": [                   // ① 精确 id(忽略大小写),优先级最高
    { "id": "o1", "contextWindow": 200000, "note": "..." }
  ],
  "rules": [                    // ② 关键字规则,按数组顺序首条命中
    { "keywords": ["claude"], "contextWindow": 200000, "note": "..." }
  ]
}
```

### 查找语义(App 端)

1. `models[]` 中 `id` 忽略大小写精确等于 modelId → 命中;
2. 否则按 `rules[]` **数组顺序**逐条检查:该条所有 `keywords` 都命中才采用(越靠前越具体,如 `claude sonnet 5` 放在 `claude` 前);
3. 都没有 → `defaultContextWindow`。

### keywords 命中规则

- 忽略大小写;
- 把 modelId 与 keyword 中的分隔符(`-` `_` `.` 空格)去掉后,keyword 是 modelId 的**子串**即命中。
  例:`claude-sonnet-5-20260901` → keyword `claude`/`sonnet`/`5` 均命中。

## 怎么加 / 改

- **新增家族**:在 `rules` **末尾**(或按具体度插入合适位置)加一条,note 注明依据;
- **修正某型号**:优先加更靠前、更具体的规则,或直接进 `models` 精确段;
- **删模型**:对应规则/条目直接移除;
- 改完顺手把 `updatedAt` 更新为当天日期。

## 数值来源与口径(重要)

| 来源 | 值 | 说明 |
|---|---|---|
| **锚点(可信)** | `claude sonnet/opus 5`、`deepseek v4 flash/pro/v4.1` = **1M** | 与代码 `ModelRegistry.kt` 中 `contextLength(1.m)` 六处一致 |
| **家族默认(推断)** | gpt-4o 128k / gpt-5 系 400k / gemini 1M / claude 200k / deepseek 64k / qwen 256k / glm 128k / kimi 128k / doubao 256k / grok 256k / minimax 200k / mimo 256k / step 128k / intern 128k / hy 128k / longcat 200k / muse 128k | 按各官方生态公开值推测,新版本可能变动,**欢迎 PR 修正** |

> 该表只决定"100% 是多少";"已用多少(分子)"由各 provider 响应 `usage` 提供,与本文无关。
