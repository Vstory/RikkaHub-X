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

## 认哪些写法

`assertEquals(期望, 实际)` 与 `assertEquals(消息, 期望, 实际)` **都认** ——
后者在这份代码库里很常见,漏掉它等于放掉一半断言。
(三参数时要求第一个参数是**字符串字面量**,那才是 JUnit 的 message 位;其余重载不猜。)

## 判据(刻意收窄)

只报**两类**形状:

① `assertEquals(<带 L 的整数>, <以 .size / .length / .count 结尾的表达式>)` 与它的反向
   —— 那几个属性的返回类型确定无疑是 `Int`;
② 字面量与**本仓库里声明为 `Long` 的属性**对不上(两个方向)。这一类比看上去重要:
   写 `assertEquals(-60_010, jump.driftMs)` 时,**`-60_010` 是 Int 而 `driftMs` 是 Long**
   —— 又一次选中 `assertEquals(Object, Object)`,运行时才炸。而负数字面量尤其容易漏掉 L。

⚠️ ②的名单是**从源码推导**的(扫 `val xxx: Long` / `var xxx: Long`),不是写死的:
写死迟早与实际漂移,而「检查器说没事、其实是名单过期」比不检查更坏。
推导规则里还要求那个名字**从不**被声明为 `Int`(同名属性在两个类里类型不同的情况真实存在),
否则会误报 —— 这条是零误报的必要条件。

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
# 从源码推导出的 Long 属性名数量下限(见 check 里的用法)。
MIN_LONG_NAMES = 10

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

# 整数字面量。⚠️ **必须允许负号** —— 而首版没允许,于是
# `assertEquals(-60_010, jump.driftMs)` 这类(负数最容易忘 L)整类漏检。
INT_LITERAL_RE = re.compile(r"^-?[0-9][0-9_]*$")
LONG_LITERAL_RE = re.compile(r"^-?[0-9][0-9_]*L$")

# 以 .size / .length / .count() 结尾 —— 这些的返回类型是 Int,确定无疑。
INT_PROPERTY_RE = re.compile(r"\.(size|length)\s*$|\.count\(\)\s*$")

# 表达式末尾的属性名(如 `jump.driftMs`、`session.bytes`)。
PROP_TAIL_RE = re.compile(r"\.([A-Za-z_]\w*)\s*$")

# 扫源码时的声明:`val NAME: Long` / `var NAME: Long?`;Int 那边连同**函数返回类型**一起收。
LONG_DECL_RE = re.compile(r"\b(?:val|var)\s+(\w+)\s*:\s*Long(?!\w)")
INT_DECL_RE = re.compile(r"\b(?:val|var)\s+(\w+)\s*:\s*Int(?!\w)")
INT_FUN_RE = re.compile(r"\bfun\s+(\w+)\s*\([^)]*\)\s*:\s*Int(?!\w)")

# **标准库成员名一律排除** —— 它们的类型由标准库决定,我们控制不了,按名字判必误报。
#
# ⚠️ 这条是被一次真实误报逼出来的:仓库里有 5 处 `val size: Long`(S3/WebDAV 的远端
#    文件大小),于是 `size` 进了「Long 属性」名单 —— 接着**每一条**
#    `assertEquals(1, someList.size)`(List.size 是 Int)都被报成类型不符,**一次 59 处**。
#    误报会被容忍到没人再信这个检查,故这类名字必须直接排除。
STDLIB_MEMBERS = frozenset({
    "size", "length", "count", "index", "ordinal", "hashCode", "isEmpty",
})

# 推导「属性名 → 类型」时要扫的源码根(与 X 无关:这条判据是通用的)。
MAIN_DIRS = ("app/src/main/java", "common/src/main/java", "ai/src/main/java")


class Problem(Exception):
    """会让检查直接中止的解析失败 —— 与「发现问题」区分开(见 main)。"""


def long_property_names(root: Path) -> set:
    """从源码推导出「**只可能**是 Long 的属性名」。

    ⚠️ 要求那个名字**从不**被声明为 `Int`:同名属性在不同类里类型不同是真实存在的
    (`at` 在一处是 Long、在另一处可能是 Int),按名字一刀切会误报 ——
    而误报会被容忍到没人再信这个检查。
    """
    longs, ints = set(), set()
    for rel in MAIN_DIRS:
        base = root / rel
        if not base.is_dir():
            continue
        for f in base.rglob("*.kt"):
            text = f.read_text(encoding="utf-8")
            longs.update(LONG_DECL_RE.findall(text))
            ints.update(INT_DECL_RE.findall(text))
            ints.update(INT_FUN_RE.findall(text))
    return longs - ints - STDLIB_MEMBERS


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

    long_names = long_property_names(root)
    if len(long_names) < 10:
        raise Problem(
            f"只从源码推导出 {len(long_names)} 个 Long 属性(期望 ≥ 10)—— 扫描面或式样写错了。"
            f"这时**必须报错**,否则下面那条判据会永远成立。"
        )

    total = 0
    for path in files:
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(root)
        for m in ASSERT_START_RE.finditer(text):
            args, _ = split_args(text, m.end() - 1)
            # ⚠️ **三参数写法(`assertEquals(消息, 期望, 实际)`)也必须认** ——
            #    而首版只认两参数,于是整类带消息的断言全在射程外。那条缺口同样是
            #    反向验证逼出来的:我按代码库的常见写法(消息在前)写了一条负数字面量的
            #    断言,而检查器对它视而不见。
            #
            # 判据:三参数时**第一参数必须是字符串字面量** —— 那才是 JUnit 的 message 位。
            # 否则(例如第一个参数是个值)当作别的重载,不猜。
            if len(args) == 2:
                a, b = args
            elif len(args) == 3 and args[0].lstrip().startswith('"'):
                a, b = args[1], args[2]
            else:
                continue
            total += 1

            a_long, b_long = bool(LONG_LITERAL_RE.match(a)), bool(LONG_LITERAL_RE.match(b))
            # ⚠️ 用 INT_LITERAL_RE(它**认负号**)而不是这里原来那条内联正则 ——
            #    首版加了常量却忘了换过来,于是「负数字面量漏 L」整类漏检,
            #    而那一类恰恰最容易漏(负号在前,人眼更不敏感)。
            #    这正是反向验证逼出来的第二处缺口。
            a_int_lit = bool(INT_LITERAL_RE.match(a))
            b_int_lit = bool(INT_LITERAL_RE.match(b))

            # 方向一:Long 字面量 对 Int 属性 —— 必炸
            if a_long and INT_PROPERTY_RE.search(b):
                problems.append(f"{rel}: {a} 是 Long 而 `{b}` 是 Int —— JUnit 会选中 "
                                f"assertEquals(Object, Object),运行时才炸。去掉那个 L。")
            if b_long and INT_PROPERTY_RE.search(a):
                problems.append(f"{rel}: `{a}` 是 Int 而 {b} 是 Long —— 同上。")

            # 方向二:字面量 对**源码里声明为 Long** 的属性 —— 也炸(两个方向都要)
            a_tail = PROP_TAIL_RE.search(a)
            b_tail = PROP_TAIL_RE.search(b)
            if a_int_lit and b_tail and b_tail.group(1) in long_names:
                problems.append(
                    f"{rel}: {a} 是 Int 而 `{b}` 是 Long(源码里声明为 Long)"
                    f" —— 同上,改成 {a}L。"
                )
            if b_int_lit and a_tail and a_tail.group(1) in long_names:
                problems.append(
                    f"{rel}: `{a}` 是 Long 而 {b} 是 Int —— 同上,改成 {b}L。"
                )

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
