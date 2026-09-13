#!/usr/bin/env python3
"""`when` 分支体「多语句但没花括号」自检。

## 为什么要它(2026-09-13)

实测撞到一次(CI 的 Guard 上):

    DiagnosticPage.kt:779 'when' expression must be exhaustive. Add the 'is Text' branch …
    DiagnosticPage.kt:790:13 Syntax error: Expecting an expression.

真正的错是:**`is PendingExport.SessionZip ->` 是单表达式分支,而我在它下面加了一个 `val`**
—— 多语句分支必须带花括号。而报错信息**指向别处**(「must be exhaustive」「Expecting an
expression」),看起来完全不像「少了一对括号」,排查一轮就要几分钟。

⚠️ 诚实记下:**它只发生过这一次**。我最初在提交信息里写成「栽过三次」——那是我凭印象
写的,查 CI 日志的证据只有 `aa1acdf9` 这一处。写清楚是因为「三次」会夸大它、也可能让
后来人以为规则更宽。

## 判据(窄到不会误报)

只报一种形状:

1. 该行是 **`when` 的分支标签且箭头在行尾**(`is X ->` / `in X ->` / `else ->`
   —— 前缀收成这三种,lambda 的 `{ it ->` 之类不会被误判);
2. 它的**第一个子句**(紧邻的下一个非空、非注释行)缩进恰好是分支缩进 + 4;
3. 那个子句是 `val` / `var` 声明。

理由:`val` 声明**不可能**是单表达式的一部分(单表达式里的声明必然在花括号内、缩进更深),
故这三条合起来就是「必然编译不过」,没有误报空间。

⚠️ **刻意不做的两件事**:
· 不判「分支体有多条语句」—— 多行表达式(`Foo(\n  a = 1,\n)`)同样多行,按行数判**必然误报**;
· 不判 lambda / `if` 表达式 —— 那要真做语法分析。

## 索引下限

扫到的 `.kt` < 300、或识别出的 when 分支 < 200 → **报错退出**,而不是「通过」。
否则式样一变就会「扫到 0 条 → 判据自动成立 → 永远绿灯」。

## 用法

    python3 scripts/check_x_when_braces.py [--root <仓库根>]

`--root` 供反向验证(`check_x_when_braces_rev.py`)把源码拷到临时目录故意改坏。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SCAN_DIRS = ("app/src/main/java", "app/src/test/java")

MIN_FILES = 300
# 识别到的 when 分支总数下限。**按实测定**:本库三种写法合计约 526 处
# (`-> {` 141、同行表达式 383、箭头在行尾 2),故取 300 留出余量。
#
# ⚠️ 首版我把下限写成 200 却只统计「箭头在行尾」那一种,于是实得 2 → 检查器直接中止。
#    这是**下限口径与统计口径不一致**造成的:下限该守的是「扫描器还认得 when 分支吗」,
#    故必须统计**全部**分支形态,而不是只统计被判据管辖的那一种。
MIN_BRANCHES = 300

# 语句性的声明 —— 它不可能出现在单表达式里。
DECL_RE = re.compile(r"^(val|var)\s+[A-Za-z_]\w*")


class Problem(Exception):
    """会让检查直接中止的解析失败 —— 与「发现问题」区分开(见 main)。"""


def branch_label(line: str):
    """这一行是 when 的分支标签吗?返回「箭头是否在行尾」;不是标签则返回 `None`。

    前缀只认 `is ` / `in ` / `else` —— 收窄的代价是漏掉「常量列表」那种标签
    (`1, 2 ->`),而收益是**绝不把 lambda 误判成分支**(`{ it ->`)。按「宁可漏、不可误报」
    的口径,这个取舍是对的:误报会让整个检查被无视。
    """
    stripped = line.strip()
    if "->" not in stripped:
        return None
    head = stripped.split("->", 1)[0].strip()
    if not (head.startswith("is ") or head.startswith("in ") or head == "else"):
        return None
    return stripped.endswith("->")


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip())


def first_child(lines: list, start: int, base_indent: int):
    """分支体里**第一个真正的语句**的行号(跳过空行与注释)。

    返回 `None` 表示这个分支没有子句(例如 `else -> {}` 或分支体在同行)。
    """
    i = start
    while i < len(lines):
        s = lines[i].strip()
        if not s:
            i += 1
            continue
        # 注释行跳过 —— 而**它们可能就写在子句前面**(实测那种写法很常见)。
        if s.startswith("//") or s.startswith("/*") or s.startswith("*"):
            i += 1
            continue
        return i if indent_of(lines[i]) > base_indent else None
    return None


def check(root: Path) -> list:
    problems = []
    files = []
    for rel in SCAN_DIRS:
        base = root / rel
        if base.is_dir():
            files.extend(sorted(base.rglob("*.kt")))
    if len(files) < MIN_FILES:
        raise Problem(f"只扫到 {len(files)} 个 .kt(期望 ≥ {MIN_FILES})—— 扫描面写错了。")

    branches = 0
    for path in files:
        text = path.read_text(encoding="utf-8")
        lines = text.split("\n")
        rel = path.relative_to(root)
        for i, line in enumerate(lines):
            arrow_at_eol = branch_label(line)
            if arrow_at_eol is None:
                continue
            # ⚠️ **所有**分支形态都计入(branches++),不管判据管不管它 ——
            #    下限守的是「扫描器还认得 when 分支吗」(见 MIN_BRANCHES 的注释)。
            branches += 1
            if not arrow_at_eol:
                continue  # 同行就有表达式(`-> {` 或 `-> expr()`)→ 不可能少花括号
            base = indent_of(line)
            child = first_child(lines, i + 1, base)
            if child is None:
                continue
            body = lines[child]
            if indent_of(body) != base + 4:
                continue  # 更深的缩进 = 在花括号/括号里,不受这条判据管辖
            if DECL_RE.match(body.strip()):
                problems.append(
                    f"{rel}:{child + 1}: 这个 `when` 分支体里有 `{body.strip()[:32]}` 声明,"
                    f"但分支**没有花括号** —— 单表达式分支放不下它,编译不过。"
                    f"(报错信息会指向整个 `when`,看起来不像少了括号)"
                )

    if branches < MIN_BRANCHES:
        raise Problem(
            f"只识别出 {branches} 个 when 分支(期望 ≥ {MIN_BRANCHES})—— "
            f"式样可能已与实际写法不符,这时**必须报错**,否则扫到 0 条也会「通过」。"
            f"(⚠️ 注意统计的是**全部**分支形态,不只是箭头在行尾那种。)"
        )
    return problems


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--root", default=None, help="仓库根(默认取脚本所在目录的上一级)")
    args = ap.parse_args(argv[1:])

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent
    try:
        problems = check(root)
    except Problem as exc:
        print(f"[FAIL] when 花括号自检无法进行:{exc}")
        return 1

    if problems:
        print(f"[FAIL] when 花括号自检发现 {len(problems)} 处:")
        for p in problems:
            print(f"  - {p}")
        return 1

    print("[CHECK PASS] 没有「when 分支体多语句却省了花括号」的地方")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
