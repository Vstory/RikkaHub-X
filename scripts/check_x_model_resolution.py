#!/usr/bin/env python3
"""守卫：UI 层解析「当前模型」必须与生成侧同源（会话感知），不得用全局口径。

## 它在防什么（一个**只在真机、只在特定配置下**才暴露的 bug）

X 把模型作用域定为**会话**：`conversation.modelId → 会话绑定助手 → 全局默认`。
生成侧（`ChatService`）与模型选择器的显示口径都是这条链。

但 `Settings.getCurrentChatModel()` 是**上游**的口径，只看 `助手 → 全局`：

    findModelById(getCurrentAssistant().chatModelId ?: chatModelId)

两者只在「会话级模型 == 助手级 == 全局」时一致。一旦用户**只**选了会话级模型
（全新安装、刚配好供应商、还没给助手配模型 —— 这是最常见的新用户路径），
`getCurrentChatModel()` 返回 null，而会话其实已经有模型了。

2026-09-11 实测：发送按钮的闸门正是读它，于是

    在会话里选好模型 → 输入文字 → 点发送
    → 弹「请先选择模型」（明明选过了）

**同一个缺陷类此前已在 `ConversationAssistantScope.kt` 收敛过大半**（那份文件就是干这个的），
但漏了 4 处：发送闸门（`ChatVM.currentChatModel`）、附件类型判断、搜索开关、
语音模式闸门。四处都只在「只配了会话级模型」时发作，故一路没被发现。

## 判据

`app/src/main/java/**/ui/**` 下**不得出现** `getCurrentChatModel`（调用或 import 都算）。

豁免（**有明确理由**，不是"暂时不想改"）：

| 文件 | 理由 |
|---|---|
| `ui/components/ai/FilesPicker.kt` | 该组件拿不到 `conversation`（模型由父组件持有）；改它需要新增参数，留待后续 |

其余图层不受约束：`ChatService` 的压缩回退是**刻意**的「随便哪个可用模型」，
`PreferencesStore` 是它的定义处。

## 用法

    python3 scripts/check_x_model_resolution.py
"""
import re
import sys
from pathlib import Path

UI_ROOT = Path("app/src/main/java/me/rerere/rikkahub/ui")
TOKEN = "getCurrentChatModel"
# 正确写法：getConversationChatModel(conversation)
CORRECT = "getConversationChatModel"

# 豁免：相对 UI_ROOT 的路径 → 理由
ALLOWED = {
    "components/ai/FilesPicker.kt": "组件拿不到 conversation，留待后续补参数",
}

# 防「假检查」：扫到的文件数低于这个量说明路径或判据失效（索引为空 ⇒ 恒通过）
SANITY_MIN_FILES = 100

# 注释行不算（说明性文字里提到旧口径是好事，不该被拦）
RE_COMMENT = re.compile(r"^\s*(//|\*|/\*)")


def main() -> int:
    if not UI_ROOT.is_dir():
        print(f"❌ 找不到 UI 目录：{UI_ROOT}（请在仓库根目录运行）")
        return 1

    files = sorted(UI_ROOT.rglob("*.kt"))
    if len(files) < SANITY_MIN_FILES:
        print(f"❌ 只扫到 {len(files)} 个 UI 文件（少于 {SANITY_MIN_FILES}）"
              f"—— 判据或路径失效，检查器已失去意义")
        return 1

    errors = []
    for path in files:
        rel = path.relative_to(UI_ROOT).as_posix()
        if rel in ALLOWED:
            continue
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if TOKEN not in line:
                continue
            if RE_COMMENT.match(line):
                continue
            errors.append(f"{path}:{lineno} {line.strip()}")

    print(f"模型解析口径自检：扫过 {len(files)} 个 UI 文件"
          f"（豁免 {len(ALLOWED)} 个），违规 {len(errors)} 处")

    if not errors:
        print("✅ 无问题（UI 侧一律走会话感知口径，与生成侧同源）")
        return 0

    print()
    for err in errors:
        print(f"❌ {err}")
    print()
    print("X 的模型作用域是**会话**，解析链是「会话级 → 会话绑定助手 → 全局默认」，")
    print("生成侧（ChatService）就是这么取的。UI 层若改用 getCurrentChatModel()（只看助手/全局），")
    print("在「只配了会话级模型」时会误判为没有模型 —— 表现为明明选过、却提示请先选择模型。")
    print(f"请改用 {CORRECT}(conversation)。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
