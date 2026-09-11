#!/usr/bin/env python3
"""守卫：X 的事件名必须是「点分隔恰好三段」。

## 它在防什么（一个**只在 CI 才能发现**的错）

事件名是排查时的**检索键** —— 用户复制一段诊断发给开发者，靠的就是在文本里
定位 `asset.write.new` 这样的词。名字写错不会报错、不会崩，只是**搜不到**。

2026-09-11 实测：`diag.persist_fail`（**两段**）配了一条断言「应为三段」的单测 ——
单测能抓到，但它只在 `X Custom Guard` 里跑（要编译 + 3 分钟）。
于是出现最难解释的一种红：**本地秒级检查全绿，CI 红**。

本检查把这条规则从「单测」前移到「秒级静态检查」：提交前就能发现。

## 判据（刻意收窄，避免误报）

本项目里字符串常量极多 —— `XStorageTables` 就有几十个（表名 `x_asset`、
列名 `message_id`、状态值 `upload` …）。**它们不是事件名，不在本检查范围内**。

本检查只认两类常量：

| # | 范围 | 例 |
|:--:|---|---|
| 1 | `object X*Events` 块内的 `const val` | `XStorageEvents` / `XChatEvents` / `XFtsEvents` |
| 2 | 常量名以 `_EVENT` 结尾的 `const val` | `XDiagnostics.PERSIST_FAIL_EVENT` |

规则（与各 events 对象的单测同规矩）：

    ① 点分隔段数 == 3       asset.write.new / context.cache.write_fail / diag.persist.fail
    ② 全小写                不得出现 assetWriteNew
    ③ 无空格
    ④ 除分隔点外无正则元字符  按名字过滤日志时才不会失配

> 第三段**允许下划线**（`write_fail` / `plan_stale`）—— 段数由「点」界定，不是由下划线。

## 用法

    python3 scripts/check_x_event_names.py
"""
import re
import sys
from pathlib import Path

MAIN_ROOT = Path("app/src/main/java")
SEGMENTS = 3
# 除分隔点外的正则元字符：名字里带它们，按名字过滤日志时会失配
REGEX_META = ["*", "+", "?", "[", "]", "(", ")", "{", "}", "|", "^", "$", "\\"]

# object XxxEvents {  —— 约定：一个域一个 events 对象
RE_EVENTS_OBJECT = re.compile(r"^\s*(?:public\s+|internal\s+)?object\s+(X\w*Events)\s*[:{]")
# const val NAME = "value" （可带类型标注）
RE_CONST_VAL = re.compile(r'^\s*(?:private\s+)?const\s+val\s+(\w+)\s*(?::\s*String\s*)?=\s*"([^"]*)"')
RE_EVENT_SUFFIX = re.compile(r"_EVENT$")


def scan(root: Path):
    """产出 (文件, 行号, 常量名, 值, 来源)。"""
    found = []
    for path in sorted(root.rglob("*.kt")):
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (UnicodeDecodeError, OSError):
            continue

        in_events_object = False
        depth = 0
        object_depth = 0

        for idx, line in enumerate(lines, start=1):
            if not in_events_object:
                if RE_EVENTS_OBJECT.match(line):
                    in_events_object = True
                    object_depth = depth + line.count("{") - line.count("}")
                    depth = object_depth
                    # object 声明行本身不含常量
                    continue
                m = RE_CONST_VAL.match(line)
                if m and RE_EVENT_SUFFIX.search(m.group(1)):
                    found.append((path, idx, m.group(1), m.group(2), "_EVENT 后缀"))
                continue

            # 块内
            m = RE_CONST_VAL.match(line)
            if m:
                found.append((path, idx, m.group(1), m.group(2), "X*Events 块内"))
            depth += line.count("{") - line.count("}")
            if depth <= 0:
                in_events_object = False
    return found


def check(name: str, value: str) -> list[str]:
    problems = []
    segments = value.split(".")
    if len(segments) != SEGMENTS:
        problems.append(
            f"应为「域.动作.结果」三段，实为 {len(segments)} 段"
        )
    if value != value.lower():
        problems.append("应全小写")
    if " " in value:
        problems.append("不应含空格")
    for ch in REGEX_META:
        if ch in value:
            problems.append(f"不应含正则元字符 {ch}")
    if any(seg == "" for seg in segments):
        problems.append("存在空段（点号重复或结尾有点）")
    return problems


def main() -> int:
    if not MAIN_ROOT.is_dir():
        print(f"❌ 找不到源码目录：{MAIN_ROOT}（请在仓库根目录运行）")
        return 1

    events = scan(MAIN_ROOT)
    if not events:
        # 「索引为空 ⇒ 恒通过」是假检查的经典形态，故这里必须报错退出
        print(f"❌ 未扫到任何事件名常量（{MAIN_ROOT}）—— 判据或路径失效，检查器已失去意义")
        return 1

    errors = []
    for path, lineno, name, value, source in events:
        for problem in check(name, value):
            errors.append(f"{path}:{lineno} {name} = \"{value}\" —— {problem}（{source}）")

    names = [name for _, _, name, _, _ in events]
    print(
        f"事件名自检：扫到 {len(events)} 个事件名常量"
        f"（{len(set(names))} 个不同名），违规 {len(errors)} 处"
    )

    if not errors:
        print("✅ 无问题（全部为三段式、小写、可 grep）")
        return 0

    print()
    for err in errors:
        print(f"❌ {err}")
    print()
    print("事件名是**排查时的检索键** —— 写错不会报错，只是搜不到那个词。")
    print("统一为「域.动作.结果」：域（asset / context / sync / chat / fts / diag）")
    print("+ 动作（write / fetch / retry …）+ 结果（new / fail / skip …）。")
    print("第三段允许下划线（write_fail），段数由点界定。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
