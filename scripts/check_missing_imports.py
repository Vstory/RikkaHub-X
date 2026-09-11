#!/usr/bin/env python3
"""项目内自定义类型的「缺失 import」自检。

背景（2026-09-11，真实事故）：在 `di/RepositoryModule.kt` 里加了
`AssetRepository(get(), get<Context>().filesDir)` 一行，却**忘了加 import**。
后果是 CI 编译失败：

    Unresolved reference 'AssetRepository'

**语法自检脚本查不到这类错** —— 它只查括号/注释/字符串是否配对，
而这一行在「表层语法」上完全合法。符号解析只有编译器能做，但本项目本地不跑构建，
于是这类错每次都要花一轮 CI 往返才发现。

本脚本把「项目内自定义类型」这一子集做掉：只要某个类型**能在本项目源码里找到声明**，
那么在使用它的文件里就必须满足三者之一：
  ① 该文件已 import 它；
  ② 它与使用处**同包**（同目录下的 .kt 即同包）；
  ③ 使用处写了全限定名。

**查不到什么**：标准库 / 第三方库 / Android 框架的类型（本脚本不知道它们的包名，
故一律不报）；重载歧义；泛型推断；扩展函数接收者 —— 这些仍只有编译器能判。
**它的价值是拦住「自己项目里新写的类忘了 import」这一类**，
而那恰好是改动上游文件、新增跨包接线时最常犯的。

用法：
    python3 scripts/check_missing_imports.py <文件或目录> [...]
    python3 scripts/check_missing_imports.py --root <源码根> <文件或目录> [...]
        （源码根用于建立「类名 → 包」索引，缺省扫 app/src/main/java 与 ai/src/main/java）

退出码：0 = 通过，1 = 发现问题。
"""

from __future__ import annotations

import glob
import os
import re
import sys

SCRIPT_VERSION = "1.0.0"

DEFAULT_SOURCE_ROOTS = [
    "app/src/main/java",
    "ai/src/main/java",
    "workspace/src/main/java",
]

# 顶层类型声明（行首锚定 → 排除嵌套类；嵌套类要用外层类型限定,不需要 import）
TOP_LEVEL_TYPE_PATTERNS = [
    re.compile(r"^(?:(?:public|internal|private|abstract|open|sealed|data|enum|annotation|value|final|external)\s+)*class\s+(\w+)", re.M),
    re.compile(r"^(?:(?:public|internal|private|sealed|fun)\s+)*interface\s+(\w+)", re.M),
    re.compile(r"^(?:(?:public|internal|private|companion)\s+)*object\s+(\w+)", re.M),
    re.compile(r"^typealias\s+(\w+)", re.M),
]

# 文件「自己已定义的名字」：含嵌套与函数 —— 本文件内用简单名访问自己定义的任何东西都合法
LOCAL_DECLARATION_PATTERNS = [
    re.compile(r"\bclass\s+(\w+)"),
    re.compile(r"\binterface\s+(\w+)"),
    re.compile(r"\bobject\s+(\w+)"),
    re.compile(r"\btypealias\s+(\w+)"),
    re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\("),
    re.compile(r"\b(?:val|var)\s+(\w+)"),
]

IMPORT_PATTERN = re.compile(r"^\s*import\s+([\w.]+)", re.M)
PACKAGE_PATTERN = re.compile(r"^\s*package\s+([\w.]+)", re.M)

# 大写开头的标识符（可能是类型名）。排除全大写的常量（如 SHA256、UTF8）
TYPE_USE_PATTERN = re.compile(r"\b([A-Z][A-Za-z0-9_]{2,})\b")


def strip_comments_and_strings(text: str) -> str:
    """剥离块注释、行注释、字符串字面量，避免把其中的名字当成类型使用。"""
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        # 行注释
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j == -1 else j
            continue
        # 块注释（Kotlin 支持嵌套）
        if text.startswith("/*", i):
            depth = 1
            i += 2
            while i < n and depth > 0:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            continue
        # 三引号原始字符串
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j == -1 else j + 3
            continue
        # 普通字符串（含 $ 模板仍整体剥离，模板里通常不是类型名）
        if ch == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        # 字符字面量
        if ch == "'":
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def read(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def collect_top_level_types(text: str) -> set[str]:
    """顶层类型声明 —— 只有这些才需要 import。"""
    names: set[str] = set()
    for pattern in TOP_LEVEL_TYPE_PATTERNS:
        names.update(pattern.findall(text))
    return names


def collect_local_names(text: str) -> set[str]:
    """本文件内定义的全部名字（含嵌套类型与函数）—— 简单名访问自己定义的东西都合法。"""
    names: set[str] = set()
    for pattern in LOCAL_DECLARATION_PATTERNS:
        names.update(pattern.findall(text))
    return names


def build_index(roots: list[str]) -> dict[str, set[str]]:
    """顶层类型名 → 声明它的包集合。

    **只收顶层**：嵌套类（如 `ServerToolStatus.Error`）用简单名访问是非法的，
    真正用到时必带外层限定，那种写法前面紧跟 `.`，本检查会跳过 —— 若把嵌套类也收进索引，
    就会对每一个 `is Error ->` 这类 when 分支产生误报。
    """
    index: dict[str, set[str]] = {}
    for root in roots:
        if not os.path.isdir(root):
            continue
        for path in glob.glob(os.path.join(root, "**", "*.kt"), recursive=True):
            text = read(path)
            package_match = PACKAGE_PATTERN.search(text)
            package = package_match.group(1) if package_match else ""
            for name in collect_top_level_types(text):
                index.setdefault(name, set()).add(package)
    return index


def imports_of(text: str) -> tuple[set[str], set[str]]:
    """返回（已 import 的简单名, 已 import 的全限定名）。"""
    simple: set[str] = set()
    full: set[str] = set()
    for match in IMPORT_PATTERN.finditer(text):
        dotted = match.group(1)
        full.add(dotted)
        # 处理 `import a.b.C` 与 `import a.b.C as D`
        simple.add(dotted.rsplit(".", 1)[-1])
    return simple, full


def check_file(path: str, index: dict[str, set[str]]) -> list[str]:
    problems: list[str] = []
    raw = read(path)
    text = strip_comments_and_strings(raw)
    own_package_match = PACKAGE_PATTERN.search(raw)
    own_package = own_package_match.group(1) if own_package_match else ""

    imported_simple, imported_full = imports_of(raw)
    own_declarations = collect_local_names(raw)

    rel = os.path.relpath(path)
    for match in TYPE_USE_PATTERN.finditer(text):
        name = match.group(1)
        # 全大写常量不算类型（SHA256 / UTF8 / MAX_VALUE 之类）
        if name.isupper():
            continue
        if name in imported_simple or name in own_declarations:
            continue
        # 使用处写了全限定名（前面紧跟 '.'）→ 视为已解析
        start = match.start()
        if start > 0 and text[start - 1] == ".":
            continue
        packages = index.get(name)
        if not packages:
            # 项目里找不到声明 → 标准库/第三方/Android，本脚本不判
            continue
        if any(full.endswith("." + name) for full in imported_full):
            continue
        # **同包不需要 import。判据是「包名相同」而非「目录相同」** ——
        # 主源码集与测试源码集目录不同却共用包名，按目录判会产生整片误报。
        if own_package and own_package in packages:
            continue
        problems.append(
            f"{rel}: 用到项目内的类型 {name}(声明于 {', '.join(sorted(packages))})"
            f" 但未 import,且不同包(本文件包:{own_package or '<无>'}) —— "
            f"编译器会报 Unresolved reference"
        )
    return problems


def expand_targets(argv: list[str]) -> tuple[list[str], list[str]]:
    roots = list(DEFAULT_SOURCE_ROOTS)
    positional: list[str] = []
    i = 0
    while i < len(argv):
        if argv[i] == "--root":
            i += 1
            if i < len(argv):
                roots = [argv[i]]
        else:
            positional.append(argv[i])
        i += 1

    files: list[str] = []
    for target in positional:
        if os.path.isdir(target):
            files.extend(sorted(glob.glob(os.path.join(target, "**", "*.kt"), recursive=True)))
        elif os.path.isfile(target):
            files.append(target)
        else:
            print(f"[警告] 路径不存在,已跳过: {target}", file=sys.stderr)
    return files, roots


def main(argv: list[str]) -> int:
    files, roots = expand_targets(argv)
    if not files:
        print("没有可检查的文件")
        return 1

    index = build_index(roots)
    if not index:
        print("未建立任何类索引 —— 检查会全部通过,形同虚设。请用 --root 指定源码根", file=sys.stderr)
        return 1

    problems: list[str] = []
    for path in files:
        problems.extend(check_file(path, index))

    if problems:
        print(f"检查 {len(files)} 个文件,发现 {len(problems)} 个问题:")
        for item in problems:
            print(f"  - {item}")
        return 1

    print(f"检查 {len(files)} 个文件,0 个问题(索引 {len(index)} 个类)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
