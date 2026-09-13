#!/usr/bin/env python3
"""单测断言的类型陷阱自检 —— 守「`assertEquals` 两边类型必须对得上」。

## 为什么要它(2026-09-13)

实测踩到:写了 `assertEquals(1L, result.getValue("core").size)`。
`Map.size` 是 **Int**,于是 JUnit 选中了 `assertEquals(Object, Object)` 那个重载 ——
`Long(1)` 与 `Integer(1)` 用 `equals` 比**不相等**,断言在**运行时**炸。

代价形态与仓库纪律里那条一模一样:**编译完全通过,本地也看不出来** ——
我这边跑的只有 python 检查器,而它是在 CI 的 Guard 上红掉的
(`expected: java.lang.Long<1> but was: java.lang.Integer<1>`)。
故把这条规则前移:纯语法可判定,不必等编译加四分钟。

## 判据(刻意收窄)

只报**一类**形状:`assertEquals(<带 L 的整数>, <以 .size / .length / .count 结尾的表达式>)`
与它的反向(`assertEquals(<不带 L 的整数>, …)` 对上一个**已知返回 Long** 的属性)。

⚠️ 收窄是有意的:放宽成「两边的数字字面量形态不同就报」会满屏误报,
而**误报会被容忍到「没人再信这个检查」** —— 比不检查更坏。
故这里只认「字面量的 Java 类型」与「少数几个属性名的返回类型」都确定无疑的组合:

| 属性 | 返回 | 故 |
|---|---|---|
| `.size`(List/Map/Set) | `Int` | `1L` 对它是错的 |
| `.length`(数组/String) | `Int` | 同上 |
| `.count()`(`List.count()`) | `Int` | 同上 |
| `.bytes` / `.lines`(本项目的 Session,是 `AtomicLong.get()`) | `Long` | 对它是 `1L`,不是 `1` |

⚠️ 最后一类**不通用**(`.bytes` 在别的类里可能是别的类型),故只对本仓库里
**已经这么定义**的那几处加成白名单;加错方向的代价是误报,故宁可不加。

## ⚠️ 射程边界(刻意不覆盖,写下来免得被当成漏检)

**「表达式推断出来的类型」不在射程内**。例如
`assertEquals(2, map.getValue("k").getValue("x"))` —— 右边到底是 `Long` 还是 `Int`
取决于那个 map 的值类型,要判它就得做类型推断。宁可漏检,也不做「看起来像就报」:
那类判据迟早误报,而误报会让这个检查器被无视(比不检查更坏)。

故:**它只挡确定无疑的组合**。挡不住的那些,仍然只能靠 CI —— 这一点不假装。

## 索引下限

扫到的测试文件 < 20、或 `assertEquals` 总数 < 200 → **报错退出**,而不是「通过」。
否则扫描面写错(目录改名)时会「扫到 0 条 → 全部判据自动成立 → 永远绿灯」。

## 用法

    python3 scripts/check_x_test_assert_types.py [--root <仓库根>]

`--root` 供反向验证(`check_x_test_assert_types_rev.py`)把测试拷到临时目录故意改坏。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

TEST_DIR = "app/src/test/java"

# 下限(见模块注释「索引下限」)
MIN_FILES = 20
MIN_ASSERTS = 200

# `assertEquals(` 的起点。**参数不在这里切** —— 见 `split_args`。
ASSERT_START_RE = re.compile(r"assertEquals\(")


def split_args(text: str, open_paren: int) -> tuple:
    """从 `(` 的位置起,按**括号深度**切出顶层参数 → ([arg...], 右括号位置)。

    ⚠️ 首版用一条正则硬抓两个参数(形如 `[^,()]+?`),结果**两处失效**:
      · 参数里带括号(凡 `getValue("core").size` 都带)→ 整条断言**根本没被匹配**,
        于是「Long 在右边」那种写法一条都查不到;
      · 嵌套两层(`….values.count()`)同样匹配不上。
    这两个缺口都是**反向验证**逼出来的 —— 一个测不出问题、或干脆漏掉一半写法的
    正则,会让人以为判据在,其实没验过。
    故改成老老实实数深度:参数里想有什么括号都行。
    """
    depth = 0
    in_str = False
    escape = False
    current = []
    args = []
    i = open_paren
    while i < len(text):
        ch = text[i]
        if in_str:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_str = False
            current.append(ch)
        elif ch == '"':
            in_str = True
            current.append(ch)
        elif ch in "([{":
            depth += 1
            if depth == 1 and ch == "(":
                i += 1
                continue
            current.append(ch)
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                args.append("".join(current).strip())
                return args, i
            current.append(ch)
        elif ch == "," and depth == 1:
            args.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
        i += 1
    return args, -1

# 以 L 结尾的整数字面量(Java/Kotlin 里就是 Long)。
LONG_LITERAL_RE = re.compile(r"^[0-9][0-9_]*L$")

# 以 .size / .length / .count() 结尾 —— 这些的返回类型是 Int,确定无疑。
INT_PROPERTY_RE = re.compile(r"\.(size|length)\s*$|\.count\(\)\s*$")

# **本项目里**确定返回 Long 的属性(见模块注释的表)。
KNOWN_LONG_PROPERTY_RE = re.compile(r"\.(bytes|lines|noiseFilteredLines|droppedParts)\s*$")


class Problem(Exception):
    """会让检查直接中止的解析失败 —— 与「发现问题」区分开(见 main)。"""


def check(root: Path) -> list:
    problems = []
    base = root / TEST_DIR
    if not base.is_dir():
        raise Problem(f"找不到 {TEST_DIR}")

    files = sorted(base.rglob("*.kt"))
    if len(files) < MIN_FILES:
        raise Problem(
            f"只扫到 {len(files)} 个测试文件(期望 ≥ {MIN_FILES})—— 扫描面写错了。"
        )

    total = 0
    for path in files:
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(root)
        for m in ASSERT_START_RE.finditer(text):
            args, _ = split_args(text, m.end() - 1)
            if len(args) != 2:
                continue  # 三参数写法(带 message)与其它重载不在射程内
            a, b = args
            total += 1

            a_long, b_long = bool(LONG_LITERAL_RE.match(a)), bool(LONG_LITERAL_RE.match(b))
            a_int_lit = bool(re.match(r"^[0-9][0-9_]*$", a))
            b_int_lit = bool(re.match(r"^[0-9][0-9_]*$", b))

            # 方向一:Long 字面量 对 Int 属性 —— 必炸
            if a_long and INT_PROPERTY_RE.search(b):
                problems.append(f"{rel}: {a} 是 Long 而 `{b}` 是 Int —— JUnit 会选中 "
                                f"assertEquals(Object, Object),运行时才炸。去掉那个 L。")
            if b_long and INT_PROPERTY_RE.search(a):
                problems.append(f"{rel}: `{a}` 是 Int 而 {b} 是 Long —— 同上。")

            # 方向二:裸整数 对本项目里确定是 Long 的属性 —— 也炸(反向)
            if a_int_lit and KNOWN_LONG_PROPERTY_RE.search(b):
                problems.append(f"{rel}: {a} 是 Int 而 `{b}` 是 Long(本项目已如此定义)"
                                f" —— 同上,改成 {a}L。")
            if b_int_lit and KNOWN_LONG_PROPERTY_RE.search(a):
                problems.append(f"{rel}: `{a}` 是 Long 而 {b} 是 Int —— 同上。")

    if total < MIN_ASSERTS:
        raise Problem(
            f"全仓只数到 {total} 处 assertEquals(期望 ≥ {MIN_ASSERTS})—— "
            f"解析式样可能已与实际写法不符,这时**必须报错**,否则扫到 0 条也会「通过」。"
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
        print(f"[FAIL] 断言类型自检无法进行:{exc}")
        return 1

    if problems:
        print(f"[FAIL] 断言类型自检发现 {len(problems)} 处问题:")
        for p in problems:
            print(f"  - {p}")
        return 1

    print("[CHECK PASS] 断言两边的类型对得上(无 Long/Int 混比)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
