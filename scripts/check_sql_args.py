#!/usr/bin/env python3
"""SQL 构造层的「占位符 ↔ 参数」结构自检。

背景：X 存储层的表不注册为 Room 实体（理由见 `x/storage/XStorageTables.kt`），
拿不到 Room 的编译期校验，语句全靠手写。手写 SQL 最典型的错误是
**占位符个数与参数个数不等** —— 它**编译不报错**，只在运行到那条语句时才抛异常，
而那时早已离开改动现场。CI 虽能跑到，但一轮往返几分钟；本脚本在提交前秒级拦住。

本脚本**读真源码**（而非照抄实现），从每处 `SqlStatement(...)` 构造里解析出
「SQL 文本」与「参数列表」两个参数再对账，故不依赖人对实现的转述是否准确。

检查项：
  1. 占位符 `?` 个数 == 参数个数
  2. SQL 文本内不含字符串字面量（`'...'`）—— 该传参的一律传参，不拼进 SQL
  3. 语句引用的表名都在 `XStorageTables` 声明的常量内（防常量与 DDL 漂移）

**查不到什么**：语句在目标 SQLite 版本上是否语法合法、列名是否真的存在、
语义是否符合预期 —— 那些只有真库或单测能判。本脚本只把「结构对账」这半做掉。

用法：
    python3 scripts/check_sql_args.py [文件或目录 ...]
    （缺省检查 app/src/main/java/me/rerere/rikkahub/x/storage）

退出码：0 = 通过，1 = 发现问题。
"""

from __future__ import annotations

import glob
import os
import re
import sys

SCRIPT_VERSION = "1.0.0"

DEFAULT_TARGET = "app/src/main/java/me/rerere/rikkahub/x/storage"

# `= SqlStatement(` 之后的内容即参数列表（构造只在此处出现）
CTOR_PATTERN = re.compile(r"=\s*SqlStatement\(")
TABLE_REF_PATTERN = re.compile(r"(?:FROM|INTO|UPDATE|JOIN)\s+(x_[a-z_]+)")

# 函数名（用于报错定位）：`fun name(` 出现在构造之前最近的位置
FUN_PATTERN = re.compile(r"fun\s+(\w+)\s*\(")


def read(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def match_paren(text: str, open_index: int) -> int:
    """返回与 `open_index` 处左括号配对的右括号下标。"""
    depth = 0
    in_string: str | None = None
    i = open_index
    while i < len(text):
        ch = text[i]
        if in_string:
            if ch == "\\":
                i += 2
                continue
            if ch == in_string:
                in_string = None
        elif ch in "\"'":
            in_string = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def split_top_level(text: str) -> list[str]:
    """按顶层逗号切分（引号内与括号内的逗号不算）。"""
    parts: list[str] = []
    depth = 0
    in_string: str | None = None
    start = 0
    i = 0
    while i < len(text):
        ch = text[i]
        if in_string:
            if ch == "\\":
                i += 2
                continue
            if ch == in_string:
                in_string = None
        elif ch in "\"'":
            in_string = ch
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(text[start:i])
            start = i + 1
        i += 1
    parts.append(text[start:])
    return parts


def sql_text_of(arg: str) -> str:
    """取参数里的字符串字面量拼成的 SQL 文本。"""
    pieces = re.findall(r'"(?:[^"\\]|\\.)*"', arg)
    return "".join(pieces)


def expand_templates(sql: str, const_map: dict[str, str]) -> str:
    """把 `${XStorageTables.NAME}` 展开成真实表名，否则表名检查形同虚设。"""

    def replace(match: re.Match[str]) -> str:
        expr = match.group(1).strip()
        tail = expr.split(".")[-1]
        return const_map.get(tail, expr)

    return re.sub(r"\$\{([^}]*)\}", replace, sql)


def count_args(arg: str) -> int | None:
    """参数个数。`emptyList()` → 0；无法识别 → None。"""
    stripped = arg.strip()
    if stripped.startswith("emptyList("):
        return 0
    match = re.search(r"listOf\(", stripped)
    if not match:
        return None
    close = match_paren(stripped, match.end() - 1)
    if close == -1:
        return None
    inner = stripped[match.end() : close]
    if not inner.strip():
        return 0
    return len(split_top_level(inner))


def enclosing_fun_name(text: str, index: int) -> str:
    names = FUN_PATTERN.findall(text[:index])
    return names[-1] if names else "<unknown>"


def check_file(path: str, declared_tables: set[str], const_map: dict[str, str]) -> list[str]:
    problems: list[str] = []
    text = read(path)
    rel = os.path.relpath(path)

    for match in CTOR_PATTERN.finditer(text):
        open_paren = match.end() - 1
        close_paren = match_paren(text, open_paren)
        if close_paren == -1:
            problems.append(f"{rel}: 有一处 SqlStatement( 括号未闭合")
            continue

        body = text[open_paren + 1 : close_paren]
        args = split_top_level(body)
        name = enclosing_fun_name(text, match.start())

        if len(args) < 2:
            problems.append(f"{rel} [{name}]: SqlStatement 应有 sql 与 args 两个参数")
            continue

        sql = sql_text_of(args[0])
        if not sql:
            problems.append(f"{rel} [{name}]: 未能解析出 SQL 文本（写法超出本脚本识别范围）")
            continue

        placeholders = sql.count("?")
        arg_count = count_args(args[1])

        if arg_count is None:
            problems.append(f"{rel} [{name}]: 未能解析出参数个数（写法超出本脚本识别范围）")
            continue

        if placeholders != arg_count:
            problems.append(
                f"{rel} [{name}]: 占位符 {placeholders} 个,参数 {arg_count} 个 —— "
                f"execSQL 会在运行时抛异常"
            )

        # 去掉模板表达式后再找字面量：模板里是常量引用,不含引号
        literal_source = re.sub(r"\$\{[^}]*\}", "", args[0])
        if re.search(r"'", literal_source):
            problems.append(f"{rel} [{name}]: SQL 里出现字符串字面量,应改为参数传递")

        for table in TABLE_REF_PATTERN.findall(expand_templates(sql, const_map)):
            if table not in declared_tables:
                problems.append(f"{rel} [{name}]: 引用了未声明的表 {table}(常量与 DDL 已漂移)")

    return problems


def resolve_const_source(files: list[str]) -> str:
    """常量映射的来源目录：优先专用目标目录，回落第一个文件所在目录。"""
    if os.path.isdir(DEFAULT_TARGET):
        return DEFAULT_TARGET
    return os.path.dirname(files[0]) if files else "."


def collect_const_map(root: str) -> dict[str, str]:
    """从 XStorageTables.kt 读出「常量名 → 表名」映射。"""
    path = os.path.join(root, "XStorageTables.kt")
    if not os.path.isfile(path):
        return {}
    return {
        name: table
        for name, table in re.findall(r'const val (\w+) = "(x_\w+)"', read(path))
    }


def expand_targets(argv: list[str]) -> list[str]:
    targets = argv or [DEFAULT_TARGET]
    files: list[str] = []
    for target in targets:
        if os.path.isdir(target):
            files.extend(sorted(glob.glob(os.path.join(target, "**", "*.kt"), recursive=True)))
        elif os.path.isfile(target):
            files.append(target)
        else:
            print(f"[警告] 路径不存在,已跳过: {target}", file=sys.stderr)
    return files


def main(argv: list[str]) -> int:
    files = expand_targets(argv)
    if not files:
        print("没有可检查的文件")
        return 1

    const_map = collect_const_map(resolve_const_source(files))
    declared = set(const_map.values())

    problems: list[str] = []
    for path in files:
        problems.extend(check_file(path, declared, const_map))

    if problems:
        print(f"检查 {len(files)} 个文件,发现 {len(problems)} 个问题:")
        for item in problems:
            print(f"  - {item}")
        return 1

    print(f"检查 {len(files)} 个文件,0 个问题")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
