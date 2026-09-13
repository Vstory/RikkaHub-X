#!/usr/bin/env python3
"""域事件清单自检 —— 「常量 ↔ ALL 清单」必须一一对应,且名字能被 grep。

## 为什么需要它(2026-09-13)

`XChatEventsTest` 里那条 `all declared events are registered in ALL` **名不副实**:
它写的是「列出三个常量名 + 断言 `ALL.size == 3`」。往对象里加新常量时,它只因为
「数量不是 3」而红 —— **查不出新常量有没有登记进 ALL**,而那正是它名字声称要守的事。

代价实测:2026-09-13 从 Firebase 迁入 5 个用户动作计数常量,推送后 **CI 红了**
(275 条用例红 1 条)。这就是仓库纪律里说的最难排查的一种红 ——
**本地秒级检查全绿、CI 红**,因为判据当时只在编译 + 四分钟之后才跑。

故把这条规则**前移**到秒级:纯静态可判定,不必等 Gradle。

⚠️ 单测那条已同步改成反射(真去取全部声明,两个方向核对)。两者**不是重复**:
单测在 JVM 里跑、能拿到编译后的真实字段;本检查器在提交前跑、**不依赖编译**。
两边都主张同一件事,任一边失效另一边仍会报。

## 判据

1. **常量 ↔ ALL 一一对应**(两个方向):
   · 声明了却没登记 → 该事件不进任何按 ALL 生成的导出与检索,等于白记;
   · 登记了却找不到常量 → 名字拼错,按名字过滤日志时失配。
2. **名字形态**:恰好三段(`域.动作.结果`)、全小写、不含空格与正则元字符。
   ⚠️ 形态这一条要查**全部已声明常量**,而不是只查 ALL 里的 ——
   只查 ALL 会漏掉「漏登记的那一个」,而那恰恰是最需要查的那个。

## 索引下限

扫到的对象数 < 1、或常量总数 < 3 → **报错退出**,而不是「通过」。
否则写法一变就会「解析出 0 条 → 全部判据自动成立 → 永远绿灯」。

## 用法

    python3 scripts/check_x_event_lists.py [--root <仓库根>]

`--root` 供反向验证(`check_x_event_lists_rev.py`)把源码拷到临时目录、故意改坏。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SCAN_DIR = "app/src/main/java/me/rerere/rikkahub/x"

# 下限(见模块注释「索引下限」)
MIN_OBJECTS = 1
MIN_CONSTS = 3

# `const val NAME = "value"` —— 只认带字符串字面量的那种。
CONST_RE = re.compile(r'^\s*const val ([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"', re.M)

# `val ALL: List<String> = listOf( … )` —— 取括号里那一段。
ALL_RE = re.compile(r"val ALL\s*:\s*List<String>\s*=\s*listOf\((.*?)\)", re.S)

# 名字的形态:三段、小写、点分。
NAME_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2}$")

# 含这些字符的名字按正则过滤日志时会失配。
REGEX_META = ("*", "+", "?", "[", "]", "(", ")", "{", "}", "|", "^", "$", "\\")


class Problem(Exception):
    """会让检查直接中止的解析失败 —— 与「发现问题」区分开(见 main)。"""


def scan(root: Path) -> list[tuple[Path, dict, list]]:
    """→ [(文件, {常量名: 值}, [ALL 里登记的名字])],按路径排序。"""
    base = root / SCAN_DIR
    if not base.is_dir():
        raise Problem(f"找不到 {SCAN_DIR}")

    found = []
    for path in sorted(base.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        m = ALL_RE.search(text)
        if not m:
            continue
        consts = dict(CONST_RE.findall(text))
        # 清单里写的是常量名(不是字面量),故取标识符;**注释里的词也要排除** ——
        # 那正是最容易误报的地方(listOf 上方的注释常拿事件名举例)。
        body = re.sub(r"//[^\n]*", "", m.group(1))
        registered = re.findall(r"\b([A-Z][A-Z0-9_]+)\b", body)
        found.append((path, consts, registered))
    return found


def check(root: Path) -> list:
    problems = []
    objects = scan(root)

    total_consts = sum(len(c) for _, c, _ in objects)
    if len(objects) < MIN_OBJECTS or total_consts < MIN_CONSTS:
        raise Problem(
            f"只扫到 {len(objects)} 个含 ALL 清单的对象 / {total_consts} 个常量"
            f"(期望 ≥ {MIN_OBJECTS} / ≥ {MIN_CONSTS})。\n"
            f"       解析式样可能已与实际写法不符 —— 这时**必须报错**,"
            f"否则本检查器扫到 0 条也会「通过」。"
        )

    for path, consts, registered in objects:
        rel = path.relative_to(root)
        reg_set = set(registered)

        # ── 判据 1:两个方向 ──
        missing = sorted(name for name in consts if name not in reg_set)
        if missing:
            problems.append(
                f"{rel}:这些常量已声明但未登记进 ALL: {missing} —— "
                f"它们不会出现在任何按 ALL 生成的导出与检索里,等于白记。"
            )

        known = set(consts)
        stale = sorted(name for name in reg_set if name not in known)
        if stale:
            problems.append(
                f"{rel}:ALL 里这些名字在本文件找不到对应常量: {stale} —— "
                f"名字拼错了,按名字过滤日志时会失配。"
            )
        if len(registered) != len(reg_set):
            dup = sorted({n for n in registered if registered.count(n) > 1})
            problems.append(f"{rel}:ALL 里有重复登记: {dup}")

        # ── 判据 2:名字形态(查**全部已声明常量**,含漏登记的那些) ──
        for name, value in sorted(consts.items()):
            if not NAME_RE.match(value):
                problems.append(
                    f"{rel}:常量 {name} 的名字 '{value}' 不合「域.动作.结果」三段点分小写 —— "
                    f"它是排查时的检索键,形态一乱日志就搜不干净。"
                )
                continue
            for meta in REGEX_META:
                if meta in value:
                    problems.append(
                        f"{rel}:常量 {name} 的名字含正则元字符 {meta!r} —— 按名字过滤日志时会失配。"
                    )
                    break

    return problems


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--root", default=None, help="仓库根(默认取脚本所在目录的上一级)")
    args = ap.parse_args(argv[1:])

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent
    try:
        problems = check(root)
    except Problem as exc:
        print(f"[FAIL] 域事件清单自检无法进行:{exc}")
        return 1

    if problems:
        print(f"[FAIL] 域事件清单自检发现 {len(problems)} 处问题:")
        for p in problems:
            print(f"  - {p}")
        return 1

    objects = scan(root)
    consts = sum(len(c) for _, c, _ in objects)
    print(
        f"[CHECK PASS] {len(objects)} 个清单 / {consts} 个事件:常量与 ALL 一一对应,名字形态合规"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
