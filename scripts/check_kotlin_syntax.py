#!/usr/bin/env python3
"""Kotlin 源文件「表层语法」静态自检。

背景：本项目本地不跑构建（构建交 CI），但 CI 红一次的代价是一轮往返，
故提交前需要能**秒级**跑的把关。本脚本只查「单文件内即可判定」的错误：

  1. 未闭合块注释 —— Kotlin **支持嵌套块注释**，所以 KDoc 里出现「斜杠 + 星号」
     会开启新的注释层。踩坑实例：KDoc 中写路径通配符会被当成注释起始，
     编译器在**文件末尾**报 `Unclosed comment`，错误位置指向 EOF，
     与真正出问题的行相去甚远。**本脚本已两次抓到这类错。**
  2. 未闭合字符串 / 字符字面量 / 原始字符串
  3. 括号不配对（花括号 / 圆括号 / 方括号）

## 词法处理范围（2026-09-11 扩充）

原先只认 `//`、块注释、普通字符串、三引号原始字符串，于是对三类**常见且合法**的写法
误报 —— 实测在 `S3Client` / `SettingsJsonMigrator` / `MessageNodeStatsTest` /
`Markdown` / `StringUtils` 上共误报 9~19 处，且 `HEAD` 原版同样报（即误报，非真错）：

| 写法 | 原缺陷 |
|---|---|
| `trim('"')` 等**字符字面量** | 单引号被当成字符串开始，其后花括号全被吞掉 |
| **原始字符串以引号结尾**（结束处连写四个引号，前一个是内容、后三个是结束符） | 结束符判定错位 |
| **模板内嵌字符串**（普通字符串里用 `${...}`，而表达式内又有引号字面量） | 内层引号被当成本层字符串的结束 |

误报的代价是「没人再信这个检查」，故按 Kotlin 词法如实处理这三类。
**`${` 在普通字符串与原始字符串里都做模板处理**，模板内的引号/注释/括号同样被正确跳过。

## 查不到什么

类型错误、符号解析、重载歧义、泛型推断 —— 这些只有编译器能判。
本脚本的作用是「把便宜的那半把关做掉」，不是替代编译。
**也不能当作「文件一定合法」的证明**：词法之外的一切它都不看。

反过来说，**本脚本报出的问题也不必然是真错** —— 若遇到这里没覆盖的词法写法，
需要人工判断。发现误报时**应当修本脚本**，而不是习惯性忽略输出。

用法：
    python3 scripts/check_kotlin_syntax.py <文件或目录> [...]
    （缺省检查 app/src/main/java/me/rerere/rikkahub/x）
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
CHAR_LITERAL = "char_literal"
TEMPLATE = "template"

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
    # 字符串/字符字面量结束后回到哪个状态（模板内嵌字符串要回到 TEMPLATE）
    return_states: list[str] = []
    # 每个活跃模板：`{` 嵌套深度 + 承载它的字符串状态（普通/原始字符串不同）
    # 用 (depth, container_state) 而非单纯深度 —— 否则模板结束时无法知道该回到哪种字符串
    templates: list[tuple[int, str]] = []
    line = 1
    index = 0
    length = len(src)
    literal_start_line = 1

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
                    literal_start_line = line
                    return_states.append(CODE)
                    index += 3
                    continue
                state = STRING
                literal_start_line = line
                return_states.append(CODE)
                index += 1
                continue
            if char == "'":
                state = CHAR_LITERAL
                literal_start_line = line
                return_states.append(CODE)
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
            # 模板起始：`${`。进入 TEMPLATE 后按代码处理，
            # 由此**内层字符串字面量不再被误认为本层字符串的结束**。
            if char == "$" and nxt == "{":
                templates.append((1, STRING))
                state = TEMPLATE
                index += 2
                continue
            if char == '"':
                state = return_states.pop() if return_states else CODE
            index += 1
            continue

        if state == RAW_STRING:
            # 原始字符串里的 `${` 同样是模板
            if char == "$" and nxt == "{":
                templates.append((1, RAW_STRING))
                state = TEMPLATE
                index += 2
                continue
            if char == '"':
                # 数连续引号：`""""` 表示「内容以引号结尾」，最后 3 个才是结束符。
                run = 0
                while index + run < length and src[index + run] == '"':
                    run += 1
                if run >= 3:
                    # 前 run-3 个属于内容，最后 3 个是结束符
                    index += (run - 3) + 3
                    state = return_states.pop() if return_states else CODE
                    continue
            index += 1
            continue

        if state == CHAR_LITERAL:
            if char == "\\":
                # 转义整体跳过：`'\''` 里的单引号是内容而非结束符
                index += 2
                continue
            if char == "'":
                state = return_states.pop() if return_states else CODE
            index += 1
            continue

        if state == TEMPLATE:
            # 模板内按代码处理，另需跟踪花括号以判断模板何时结束
            if char == "/" and nxt == "/":
                state = LINE_COMMENT
                index += 2
                continue
            if char == '"':
                if src[index:index + 3] == '"""':
                    state = RAW_STRING
                    literal_start_line = line
                    return_states.append(TEMPLATE)
                    index += 3
                    continue
                state = STRING
                literal_start_line = line
                return_states.append(TEMPLATE)
                index += 1
                continue
            if char == "'":
                state = CHAR_LITERAL
                literal_start_line = line
                return_states.append(TEMPLATE)
                index += 1
                continue
            if char == "{":
                if templates:
                    depth, container = templates[-1]
                    templates[-1] = (depth + 1, container)
                index += 1
                continue
            if char == "}":
                if templates:
                    depth, container = templates[-1]
                    if depth <= 1:
                        # 模板结束 → 回到承载它的那个字符串状态
                        templates.pop()
                        state = container
                    else:
                        templates[-1] = (depth - 1, container)
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

        index += 1  # 兜底，理论上不可达

    # ---- 收尾检查 ----
    if state == BLOCK_COMMENT:
        problems.append(
            f"文件在 {comment_depth} 层块注释内结束（未闭合）。"
            "常见原因：注释里出现「斜杠 + 星号」序列，Kotlin 会当成嵌套注释开始"
        )
    if state in (STRING, RAW_STRING, CHAR_LITERAL):
        problems.append(f"第 {literal_start_line} 行开始的字面量未闭合")
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
