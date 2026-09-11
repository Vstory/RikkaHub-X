#!/usr/bin/env python3
"""符号引用自检：抓「引用了不存在的成员」与「已删符号的残留引用」。

## 为什么需要它（2026-09-11 血泪）

删掉旧实现（`XStorageLog` / `XStorageLogBuffer`）时，`logcatHint()` 一并删了，
但测试里还留着一行调用：

    e: XLogRingTest.kt:208 Unresolved reference 'logcatHint'.
    > Task :app:compileDebugUnitTestKotlin FAILED

**单元测试根本没跑起来**，CI 整条红。而我在本地跑的五项检查**全部通过**：

| 检查 | 为什么没抓到 |
|---|---|
| `check_kotlin_syntax.py` | 只做词法（括号/字符串/注释），**不解析符号** |
| `check_missing_imports.py` | 只查「项目内类型未 import」，不查**成员是否存在** |
| `check_sql_args.py` / `check_x_log_gate.py` | 与符号无关 |
| 手工扫「已删符号残留」 | **我 grep 的符号名不对** —— 漏了 `logcatHint` |

即：**「删了 API 却漏改调用点」是本地检查的盲区**。这类错 100% 让 CI 红，
而本仓库**本地不跑构建**（构建交 CI），一次往返很贵。故机械化。

## 两条规则

### 规则 A：成员存在性（**本次真正抓到问题的规则**）

建立「项目内 `object` / `enum class` / 无父类的 `class`」的**成员索引**，
然后检查所有 `Owner.member` 形式的使用：`member` 不在索引里就报错。

它能抓到本次的错 —— 因为调用点写的是 `XLogRing.logcatHint()`：
`XLogRing` 是已知对象，而它的成员里没有 `logcatHint`。

**为什么不用「已删符号」判定**：那样抓不到 —— `logcatHint` 被删**又被重新加回**
另一个对象（`XDiagnostics`）时，符号名仍然存在，只有「挂在谁身上」变了。
成员存在性检查才看得见这个差别。

### 规则 B：已删符号的残留引用（互补）

从 `git diff <基准>` 的被删行里抽声明名，报告**全仓库已无声明、却仍被引用**的名字。
规则 A 只管 `Owner.member` 形式；规则 B 覆盖「顶层函数/类被删、调用点写成裸名」的情形。

## 已知边界（诚实记录）

- **只做词法近似**，不做真正的类型解析。带父类的类型**整体跳过**（成员来自父类或接口，
  词法上推不出来）—— 只为避免误报。
- **看不懂 `this` / 局部变量上的成员访问**（如 `entry.timeText()`）：接收者不是已知对象名，
  一律不管。
- 因此**它不能替代编译**，只能把「我们实际踩过的那类错」提前拦下。
- **误报是刻意压到零的**：误报会让人不再信任检查。故规则 A 对带父类的类型直接跳过。

## 用法

    python3 scripts/check_symbol_usage.py                 # 规则 A + 规则 B(对 HEAD)
    python3 scripts/check_symbol_usage.py --base HEAD~1   # 规则 B 换个基准
    python3 scripts/check_symbol_usage.py --rule a        # 只跑成员存在性

退出码：0 = 通过，1 = 发现问题。
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import subprocess
import sys

SOURCE_ROOTS = ("app", "ai", "workspace", "common", "document")

# 顶层声明（只认行首无缩进的，避免把成员当顶层）
TOP_LEVEL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public\s+|internal\s+|private\s+|open\s+|sealed\s+|abstract\s+|data\s+|"
    r"enum\s+|annotation\s+|value\s+|inner\s+|external\s+)*"
    r"(object|enum\s+class|class|interface)\s+([A-Za-z_]\w*)"
    r"(?P<header>[^{]*)",
    re.M,
)

# 成员声明
MEMBER = re.compile(
    r"\b(?:fun|val|var|const\s+val|class|object|interface|enum\s+class|typealias)\s+([A-Za-z_]\w*)"
)

# 枚举项：缩进后的大写标识符（后面可能跟构造参数 `(...)`、逗号、分号或行尾）
ENUM_ENTRY = re.compile(r"^\s{2,}([A-Z][A-Z0-9_]*)\s*(?:[(,;]|$)", re.M)

# 扩展函数：`fun Owner.name(` → 也算 Owner 的成员（编译期解析，词法上只能这样近似）
EXTENSION_FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?([A-Z]\w*)\.([A-Za-z_]\w*)")

# 扩展属性：`val Owner.name`
EXTENSION_PROPERTY = re.compile(r"\b(?:val|var)\s+([A-Z]\w*)\.([A-Za-z_]\w*)")

# 枚举 / 集合的内建成员（语言自带，不该被当成未知成员）
BUILTIN_MEMBERS = {
    "entries", "values", "valueOf", "name", "ordinal", "compareTo", "equals",
    "hashCode", "toString", "clone", "copy", "component1", "component2",
    "component3", "component4", "component5", "iterator", "invoke",
    "INSTANCE", "Companion",
}

FILES: list[str] = []


def source_files() -> list[str]:
    global FILES
    if FILES:
        return FILES
    for root in SOURCE_ROOTS:
        if os.path.isdir(root):
            for path in glob.glob(os.path.join(root, "**", "*.kt"), recursive=True):
                if "/build/" not in path.replace("\\", "/"):
                    FILES.append(path)
    return sorted(set(FILES))


def strip_line_comments(text: str) -> str:
    """去掉行注释（KDoc 里会提到成员名，不该算引用）。"""
    out = []
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith(("//", "*", "/*")):
            out.append("")
        else:
            out.append(line)
    return "\n".join(out)


def body_from(text: str, search_start: int) -> str:
    """从 `search_start` 起找第一个 `{`，返回**配对**的整段主体。

    比起「截到下一个顶层声明」的做法，这样不会被嵌套声明截断 ——
    后者会把声明在类体后半段的成员漏掉，凭空造出「成员不存在」的误报。
    扫描时跳过字符串与注释，否则字符串里的花括号会打乱配对。
    """
    start = text.find("{", search_start)
    if start == -1:
        return ""
    depth = 0
    index = start
    length = len(text)
    while index < length:
        char = text[index]
        nxt = text[index + 1] if index + 1 < length else ""
        if char == "/" and nxt == "/":
            newline = text.find("\n", index)
            index = length if newline == -1 else newline
            continue
        if char == "/" and nxt == "*":
            close = text.find("*/", index + 2)
            index = length if close == -1 else close + 2
            continue
        if char == '"':
            if text[index:index + 3] == '"""':
                close = text.find('"""', index + 3)
                index = length if close == -1 else close + 3
                continue
            index += 1
            while index < length:
                if text[index] == "\\":
                    index += 2
                    continue
                if text[index] == '"':
                    index += 1
                    break
                index += 1
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start: index + 1]
        index += 1
    return text[start:]


def extension_members() -> dict[str, set[str]]:
    """全仓库收集「X 类型的扩展函数/扩展属性」——它们也是合法成员。"""
    result: dict[str, set[str]] = {}
    for path in source_files():
        normalized = path.replace("\\", "/")
        if "/x/" not in normalized:
            continue
        try:
            with open(path, encoding="utf-8") as handle:
                text = strip_line_comments(handle.read())
        except OSError:
            continue
        for pattern in (EXTENSION_FUN, EXTENSION_PROPERTY):
            for match in pattern.finditer(text):
                result.setdefault(match.group(1), set()).add(match.group(2))
    return result


def build_member_index() -> dict[str, set[str]]:
    """对象名 → 成员名集合。

    **只索引 X 自己的类型**（路径含 `/x/`）。两条理由：

    ① 上游的类型不该由本脚本把关 —— 上游代码有编译器与 CI 把关，且我们的改动不会删上游成员；
       而 X 的类型是**我们自己反复重构的对象**，正是「删了成员漏改调用点」的高发区。
    ② 上游有大量本脚本读不懂的写法（枚举项跨行、扩展函数、父类/接口成员），
       索引它们会带来成片误报 —— 而误报会让人不再信任检查。

    **带父类的类型整体跳过**：成员可能来自父类或接口，词法上推不出来。
    """
    index: dict[str, set[str]] = {}

    for path in source_files():
        normalized = path.replace("\\", "/")
        if "/x/" not in normalized:
            continue  # 只索引 X 自己的类型
        try:
            with open(path, encoding="utf-8") as handle:
                raw = handle.read()
        except OSError:
            continue
        text = strip_line_comments(raw)

        for match in TOP_LEVEL.finditer(text):
            kind, name = match.group(1), match.group(2)
            # `enum class X` 会被前缀组吃掉 "enum "，使 kind 变成 "class" —— 故按整段判定
            is_enum = "enum" in match.group(0)
            header = match.group("header")
            # 去掉括号内的构造参数（其中的 `名: 类型` 冒号不算「继承」）
            header_without_params = re.sub(r"\([^)]*\)", "", header)
            if ":" in header_without_params or " by " in header_without_params:
                continue  # 有父类/接口/委托 → 成员来源不可知

            body = body_from(text, match.end())

            members: set[str] = set()
            for member in MEMBER.finditer(body):
                members.add(member.group(1))
            # 构造参数里声明的 val/var 也是成员
            for param in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)", header):
                members.add(param.group(1))
            if is_enum:
                for entry in ENUM_ENTRY.finditer(body):
                    members.add(entry.group(1))
            index.setdefault(name, set()).update(members)

    # 扩展函数/属性并入。**只并入已索引的 X 类型** —— 否则会把 `fun Int.xxx()` 里的
    # `Int`、以及上游类型都拉进索引，于是 `Int.MAX_VALUE` 这类语言内建被报成「成员不存在」。
    extension = extension_members()
    for owner, names in extension.items():
        if owner in index:
            index[owner].update(names)

    return index


def check_members(index: dict[str, set[str]]) -> list[str]:
    """规则 A：`Owner.member` 里的 member 必须存在。"""
    problems: list[str] = []
    if not index:
        return problems
    owners = "|".join(sorted((re.escape(name) for name in index), key=len, reverse=True))
    # 长的名字放前面：`XLogRing` 与 `XLog` 并存时，先匹配长的才不会把 `XLogRing.a` 读成 `XLog` + `Ring.a`
    usage = re.compile(r"(?<![\w.])(?P<owner>" + owners + r")\.(?P<member>[A-Za-z_]\w*)")

    for path in source_files():
        try:
            with open(path, encoding="utf-8") as handle:
                raw = handle.read()
        except OSError:
            continue
        text = strip_line_comments(raw)
        for number, line in enumerate(text.splitlines(), 1):
            for match in usage.finditer(line):
                # 声明处也是 `Owner.member` 的形状（`fun Owner.name()` / `val Owner.name`），
                # 那不是引用 —— 按同行前缀排除，否则扩展函数的定义会被自己报成错
                prefix = line[: match.start()].rstrip()
                if prefix.endswith(("fun", "val", "var")):
                    continue
                owner, member = match.group("owner"), match.group("member")
                if member in BUILTIN_MEMBERS:
                    continue
                if member not in index.get(owner, set()):
                    problems.append(
                        f"{path}:{number}: {owner}.{member} —— "
                        f"{owner} 没有成员 {member}（编译会报 Unresolved reference）"
                    )
    return problems


DECLARATION = re.compile(
    r"\b(?:fun|val|var|const\s+val|class|object|interface|enum\s+class|"
    r"data\s+class|sealed\s+class|annotation\s+class)\s+([A-Za-z_]\w*)"
)

# 通用名停用表：这些名字在项目里到处出现，搜出来是纯噪声，反而淹没真正的命中
STOPLIST = {
    "size", "name", "value", "id", "key", "index", "count", "length", "type",
    "format", "text", "data", "result", "items", "list", "map", "set", "get",
    "invoke", "equals", "hashCode", "toString", "iterator", "isEmpty",
    "isNotEmpty", "copy", "apply", "let", "run", "also", "with", "use",
    "message", "error", "path", "file", "state", "content", "config", "settings",
    "init", "main", "test", "tag", "level", "entry", "entries", "clear",
    "record", "recent", "warn", "info", "dump", "snapshot", "lines", "levels",
    "timeText", "initialValue", "tearDown", "setUp", "capability",
}


def removed_and_gone(base: str) -> dict[str, list[str]]:
    """规则 B：被删除、且**当前全仓库已无声明**、却仍被引用的符号。"""
    proc = subprocess.run(
        ["git", "diff", "--unified=0", base, "--", "*.kt"],
        capture_output=True, text=True, errors="replace",
    )
    if proc.returncode != 0:
        print(f"[错误] git diff 失败（基准 {base} 是否有效？）", file=sys.stderr)
        return {}

    removed: dict[str, str] = {}
    for line in proc.stdout.splitlines():
        if not line.startswith("-") or line.startswith("---"):
            continue
        stripped = line[1:].strip()
        if stripped.startswith(("//", "*", "/*")):
            continue
        for match in DECLARATION.finditer(line):
            name = match.group(1)
            if name in STOPLIST or len(name) < 4:
                continue
            removed[name] = stripped[:100]

    if not removed:
        return {}

    # 当前仍被声明的 → 说明是「搬家」而非「删除」，不算残留
    still_declared: set[str] = set()
    for path in source_files():
        try:
            with open(path, encoding="utf-8") as handle:
                text = strip_line_comments(handle.read())
        except OSError:
            continue
        for match in DECLARATION.finditer(text):
            still_declared.add(match.group(1))

    candidates = {name for name in removed if name not in still_declared}
    if not candidates:
        return {}

    pattern = re.compile(r"\b(" + "|".join(re.escape(n) for n in candidates) + r")\b")
    hits: dict[str, list[str]] = {}
    for path in source_files():
        try:
            with open(path, encoding="utf-8") as handle:
                text = strip_line_comments(handle.read())
        except OSError:
            continue
        for number, line in enumerate(text.splitlines(), 1):
            for match in pattern.finditer(line):
                hits.setdefault(match.group(1), []).append(f"{path}:{number}")
    return hits


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="符号引用自检")
    parser.add_argument("--base", default="HEAD", help="规则 B 的比较基准（默认 HEAD）")
    parser.add_argument("--rule", choices=["a", "b", "all"], default="all")
    args = parser.parse_args(argv)

    files = source_files()
    if not files:
        print("[错误] 没找到任何 .kt 文件 —— 检查会全部通过,形同虚设", file=sys.stderr)
        return 1

    problems: list[str] = []

    if args.rule in ("a", "all"):
        index = build_member_index()
        member_problems = check_members(index)
        print(f"规则 A 成员存在性:索引 {len(index)} 个类型,检查 {len(files)} 个文件,"
              f"{len(member_problems)} 个问题")
        problems.extend(member_problems)

    if args.rule in ("b", "all"):
        leftovers = removed_and_gone(args.base)
        count = sum(len(v) for v in leftovers.values())
        print(f"规则 B 已删符号残留:相对 {args.base},{len(leftovers)} 个符号仍被引用")
        for name, spots in sorted(leftovers.items()):
            for spot in spots[:5]:
                problems.append(f"{spot}: {name} —— 已删除且全仓库无声明,但仍被引用")
            if len(spots) > 5:
                problems.append(f"{spots[0].split(':')[0]}: {name} 另有 {len(spots) - 5} 处引用")

    if problems:
        print()
        for item in problems:
            print(f"❌ {item}")
        print()
        print("这些引用会让 CI 在编译阶段失败(本仓库本地不跑构建,一次往返很贵)。")
        return 1

    print("✅ 无问题")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
