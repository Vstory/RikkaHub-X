#!/usr/bin/env python3
"""Kotlin 源文件「表层语法」静态自检。

背景：本项目本地不跑构建（构建交 CI），但 CI 红一次的代价是一轮往返，
故提交前需要能**秒级**跑的把关。本脚本只查「单文件内即可判定」的错误：

  1. 未闭合块注释 —— Kotlin **支持嵌套块注释**，所以 KDoc 里出现 `/*` 会
     开启新的注释层。踩坑实例：KDoc 中写路径通配符 ``app/schemas/*.json``
     会被当成注释起始，编译器在**文件末尾**报 `Unclosed comment`，
     错误位置指向 EOF，与真正出问题的行相去甚远。
  2. 未闭合字符串（含三引号原始字符串）
  3. 括号不配对（花括号 / 圆括号 / 方括号）

**查不到什么**：类型错误、符号解析、重载歧义 —— 这些只有编译器能判。
本脚本的作用是「把便宜的那半把关做掉」，不是替代编译。

用法：
    python3 scripts/check_kotlin_syntax.py <文件或目录> [...]
退出码：0 = 通过，1 = 发现问题。
"""

from __future__ import annotations

import glob
import os
import sys

CODE = "code"
LINE_COMMENT = "line_comment"
BLOCK_COMMENT = "block_comment"
STRING = "string"
RAW_STRING = "raw_string"

PAIRS = {")": "(", "]": "[", "}": "{"}
OPENERS = set(PAIRS.values())


def check_file(path: str) -> list[str]:
    """返回问题描述列表；空列表表示通过。"""
    try:
        with open(path, encoding="utf-8") as handle:
            src = handle.read()
    except OSError as error:
        return [f"读取失败: {error}"]

    problems: list[str] = []
    state = CODE
    comment_depth = 0
    stack: list[tuple[str, int]] = []
    line = 1
    index = 0
    length = len(src)
    string_start_line = 1

    while index < length:
        char = src[index]
        nxt = src[index + 1] if index + 1 < length else ""

        if char == "\n":
            line += 1

            if state == LINE_COMMENT:
                state = CODE

            index += 1
            continue

        if state == CODE:
            if char == "/" and nxt == "/":
                state = LINE_COMMENT
                index += 2
                continue
            if char == "/" and nxt == "*":
                state = BLOCK_COMMENT
                comment_depth = 1
                index += 2
                continue
            if char == '"':
                if src[index:index + 3] == '"""':
                    state = RAW_STRING
                    string_start_line = line
                    index += 3
                    continue
                state = STRING
                string_start_line = line
                index += 1
                continue
            if char in OPENERS:
                stack.append((char, line))
            elif char in PAIRS:
                if not stack:
                    problems.append(f"第 {line} 行: 多余的 '{char}'")
                elif stack[-1][0] != PAIRS[char]:
                    opener, opened_at = stack[-1]
                    problems.append(
                        f"第 {line} 行: '{char}' 与第 {opened_at} 行的 '{opener}' 不匹配"
                    )
                    stack.pop()
                else:
                    stack.pop()
            index += 1
            continue

        if state == LINE_COMMENT:
            index += 1
            continue

        if state == BLOCK_COMMENT:
            if char == "/" and nxt == "*":
                comment_depth += 1
                index += 2
                continue
            if char == "*" and nxt == "/":
                comment_depth -= 1
                index += 2
                if comment_depth == 0:
                    state = CODE
                continue
            index += 1
            continue

        if state == STRING:
            if char == "\\":
                index += 2
                continue
            if char == '"':
                state = CODE
            index += 1
            continue

        if state == RAW_STRING:
            if src[index:index + 3] == '"""':
                state = CODE
                index += 3
                continue
            index += 1
            continue

    # ---- 收尾检查 ----
    if state == BLOCK_COMMENT:
        problems.append(
            f"文件在 {comment_depth} 层块注释内结束（未闭合）。"
            "常见原因:注释里出现 '/*' 序列(如路径通配符),Kotlin 会当成嵌套注释开始"
        )
    if state in (STRING, RAW_STRING):
        problems.append(f"第 {string_start_line} 行开始的字符串未闭合")
    for opener, opened_at in stack:
        problems.append(f"第 {opened_at} 行的 '{opener}' 未闭合")

    return problems


def collect_targets(arguments: list[str]) -> list[str]:
    targets: list[str] = []
    for argument in arguments:
        if os.path.isdir(argument):
            targets.extend(
                glob.glob(os.path.join(argument, "**", "*.kt"), recursive=True)
            )
        elif argument.endswith(".kt"):
            targets.append(argument)
    return sorted(set(targets))


def main(argv: list[str]) -> int:
    targets = collect_targets(argv[1:] or ["app/src/main/java/me/rerere/rikkahub/x"])
    if not targets:
        print("没有可检查的 .kt 文件")
        return 0

    total_problems = 0
    for path in targets:
        problems = check_file(path)
        if problems:
            total_problems += len(problems)
            print(f"❌ {path}")
            for problem in problems:
                print(f"     {problem}")

    print(f"\n检查 {len(targets)} 个文件，{total_problems} 个问题")
    return 1 if total_problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
