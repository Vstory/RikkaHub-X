#!/usr/bin/env python3
"""漏 import 自检 —— 守「用了本仓库的类/对象,就得 import 或同包」。

## 为什么要它(2026-09-13)

**同类错误在这一次会话里出现了三次**,每次都要等 CI 的 Compile Check 才暴露:

| 次数 | 形态 |
|---|---|
| ① | `XSurvivorLog.kt` 新建后,在 `RikkaHubApp.kt` 里加了调用却**漏了 import** |
| ② | `XDiagZip.kt` 里 `filesOf(extra)` —— 形参改成 `File?` 之后**调用点没跟着改** |
| ③ | 又在 `RikkaHubApp.kt` 漏了 `XExitReport` 的 import(同一个文件、同一种漏法) |

前两次我写的是「Kotlin 侧的错误本地一条都验不到」—— **这句对 ①③ 不成立**:
「用了别的包里声明的类却没 import」是**纯文本可判定**的,不需要编译器。
把能前移的检查前移,正是仓库纪律里那条。

## 判据(刻意收窄到不会误报)

1. 扫出仓库里**声明的类/对象** → 简单名 → 它所在的包;
2. 对每个 `.kt`:取它的包名、**本文件里声明的名字**、**import 进来的名字**(含 `as` 别名
   与 `.*` 通配);
3. 在**剥掉注释与字符串**的正文里找「像类型名的标识符」(`[A-Z][a-z]…`,故 `ALL_CAPS`
   常量与 `Outer.Inner` 里被点号前缀的那个都不会被算);
4. 若某个标识符**是本仓库某个类/对象的简单名**,而它既不是本文件声明的、也不与本文同包、
   也没 import → 报。

## 另一条便宜但值钱的判据:`import me.rerere.*` 的包必须真实存在

同一个类名在**两个不同包**里都有时,简单名判据分不出来(它只看最后一段)。但有一类
拼写错误是**纯文本可判定**的:`import me.rerere.rikkahub.x.other.XLogcatCapture` ——
包名被写错,编译必炸。故额外判:凡 `import me.rerere.…` 的包,必须能在仓库里找到
对应的 `package` 行。

⚠️ 用**沿点号前缀回溯**的方式判(而不是只砍最后一段):Kotlin 允许 import 嵌套类
(`import a.b.Outer.Inner`),那时真实包是 `a.b` 而不是 `a.b.Outer` —— 只砍一段会**误报**。
只要某一级前缀能在仓库里找到 `package` 行,就算这个 import 是合理的。

⚠️⚠️ 而且**只判「我们自己模块的命名空间」**,命名空间集合**从扫描结果自推导** ——
判据是「某个已扫到的包的前两段」。这一条是被逼出来的:这个仓库有个第三方依赖
(`me.rerere.hugeicons`,图标库)**恰好也用 `me.rerere` 命名空间**,如果照「凡
`me.rerere.` 开头就判」,它那几百个 import 会全成误报(实测 517 处)。
自推导的好处是:新增一个自有模块,它会自动被覆盖;而第三方永远不在集合里。

## ⚠️⚠️ 只守 **X 自己的包**(这是**刻意**的收窄,不是漏)

首版扫全仓,一跑就 6 处误报,修掉「嵌套声明/函数名」之后又冒出 13 处 ——
根因是**简单名撞车**:`Result`(kotlin 标准库有、仓库里也有个同名类)、
`Idle` / `Loading` / `Connecting`(枚举项,经 `import a.b.State.*` 进来)。

那类**没有真名解析就必然误报**,而误报会被容忍到「没人再信这个检查」——
比不检查更坏。故判据收窄成:**只判「声明在 X 自己的包(`.x.`)下的类」**。
于是:
· `Result` / `Idle` 那类来自上游包的名字一律不判(它们本来就有一堆同名);
· 而 X 的类名(`XSurvivorLog` / `XExitReport` / `XLogcatParts` …)几乎不可能与标准库撞名 ——
  实测全仓零误报。

诚实记下它**挡不住**什么:上游包之间的漏 import、以及「同名类在两个上游包里」的歧义,
都不在射程内(那些靠 CI 的编译)。它挡的是**我改 X 时最容易犯的那种**——
新建一个 `x/` 下的类,然后在别处加调用却漏了 import(本次会话犯了两次)。

## 索引下限

扫到的 `.kt` < 200、或收集到的类/对象 < 200 → **报错退出**,而不是「通过」。

## 用法

    python3 scripts/check_x_missing_imports.py [--root <仓库根>]

`--root` 供反向验证(`check_x_missing_imports_rev.py`)把源码拷到临时目录故意改坏。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# 扫描面:**每个模块**的 main 源码。
#
# ⚠️ 首版只扫了 `app` 与 `common` —— 于是 `me.rerere.ai.*`、`me.rerere.workspace.*`、
#    `me.rerere.oauth.*` 这些**独立模块**的包被判成「不存在」,一次报了 912 处。
#    这个仓库是多模块的(`ai/` `search/` `oauth/` `speech/` `workspace/` …),
#    判「包存在」就必须把所有模块的源码根都算进来 —— 少扫一个模块 = 那一整块全误报。
SCAN_DIRS = (
    "ai/src/main/java",
    "app/src/main/java",
    "common/src/main/java",
    "document/src/main/java",
    "highlight/src/main/java",
    "material3/src/main/java",
    "oauth/src/main/java",
    "search/src/main/java",
    "speech/src/main/java",
    "videogen/src/main/java",
    "web/src/main/java",
    "workspace/src/main/java",
)

# 下限按**实测值**取约 80%(实测:12 个模块根、629 个 .kt、1025 个类/对象),
# 留出正常增删的余地。定得太高会在有人删几个文件时误报「解析失效」。
MIN_FILES = 500
MIN_CLASSES = 800
MIN_MODULE_DIRS = 10

# 声明处:`class Foo` / `object Foo` / `enum class Foo` / `data class Foo` …
#
# ⚠️ **必须允许行首缩进**:嵌套声明在 Kotlin 里遍地都是
#    (`sealed interface X { data class Y(...) }`、`object Foo { private data class Bar }`)。
#    首版用了 `^` 严格要求顶格,于是把**嵌套里声明的名字**全漏掉 —— 实测一跑就报了 5 处
#    误报(全都「明明自己声明了却说我漏 import」)。误报比不检查更坏,故这里必须收准。
DECL_RE = re.compile(
    r"^\s*(?:(?:internal|private|public|protected|abstract|open|sealed|data|enum|annotation|value|inline)\s+)*"
    r"(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)

# `fun Foo(` / `fun <T> Foo(` / `fun Recv.Foo(` —— **函数名**也算「本文件声明」。
#
# ⚠️ 为什么需要它:Compose 里「函数名与某个数据类同名」很常见(`Tag.kt` 里既有
#    `fun Tag(...)` 又有对 `TagType` 的用法),而函数名同样长得像类型名 ——
#    不收集它就会把「自己声明的函数」报成漏 import(实测就是这样误报了一次)。
FUN_DECL_RE = re.compile(
    r"^\s*(?:(?:internal|private|public|protected|override|suspend|inline|operator|infix|"
    r"tailrec|external|abstract|open|final|actual|expect)\s+)*"
    r"fun\s+(?:<[^>\n]*>\s*)?(?:(?:[\w.<>?, ]+)\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(",
    re.M,
)

# `typealias Foo = ...`
TYPEALIAS_RE = re.compile(r"^\s*typealias\s+([A-Z][A-Za-z0-9_]*)", re.M)
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)", re.M)
# `import a.b.C` / `import a.b.C as D` / `import a.b.*`
IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", re.M)

# 「像类型名」的标识符:首字母大写、且**不是**全大写下划线式。
#
# ⚠️⚠️ 这里踩过一次**致命**的坑:首版写成 `[A-Z][a-z][A-Za-z0-9_]*`(要求第二个字符小写),
# 用意是排除 `ALL_CAPS`。结果是 **`X` 开头的类名一个都看不见** —— `XSurvivorLog`、
# `XExitReport`、`XDiagFileStore` 的第二个字符都是大写。于是这个检查器**恰好对
# 它存在的理由视而不见**:在真实漏 import 的代码上它照样「通过」。
# 而这个漏洞**只有反向验证能发现** —— 它在当前代码上是绿的,不跑「故意改坏」就永远不知道。
# 现在改为:先按「大写开头」收集,再单独排除全大写式。
TYPEISH_RE = re.compile(r"(?<![\w.])([A-Z][A-Za-z0-9_]*)")

# 全大写下划线式(`VERSION` / `CORE` / `MAX_QUERY`)—— 那是常量而非类型名。
ALL_CAPS_RE = re.compile(r"^[A-Z0-9_]+$")

# 只判**声明在 X 自己的包**下的类 —— 见模块注释「只守 X 自己的包」。
# 判据:包名里含 `.x.` 或以 `.x` 结尾(`me.rerere.rikkahub.x.diag`、`…rikkahub.x`)。
X_PACKAGE_RE = re.compile(r"\.x(\.|$)")

# (剥注释与字符串的实现在下面的 `code_only` —— 它是逐字符状态机,不是正则。见其注释。)


class Problem(Exception):
    """会让检查直接中止的解析失败 —— 与「发现问题」区分开(见 main)。"""


def _skip_string(text: str, i: int) -> int:
    """跳过一段 `"…"`,返回闭合引号之后的位置。

    ⚠️ **必须处理 `${…}`**:模板里可以换行(`"… ${foo(\n  bar\n)}"`),故一段普通字符串
    在文本上**可能跨行**。首版直接用正则 `"(?:[^"\\\n])*"` 去剥,遇到这种就剥不掉 ——
    实测把 `"Failed to guess MIME type: ${…}"` 里的 `Failed` 当成了代码里的类型名,
    报了一处**误报**。误报会让这个检查器被无视,故这里老实按字符走。
    """
    i += 1  # 开引号
    while i < len(text):
        ch = text[i]
        if ch == "\\":
            i += 2
            continue
        if ch == "$" and i + 1 < len(text) and text[i + 1] == "{":
            depth = 0
            while i < len(text):
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if depth == 0:
                        i += 1
                        break
                i += 1
            continue
        if ch == '"':
            return i + 1
        i += 1
    return i


def code_only(text: str) -> str:
    """剥掉注释、字符串与字符字面量,只留代码。

    **逐字符状态机**(而不是几条正则):正则处理不了「原始字符串里可以嵌引号」「模板
    `${…}` 里可以换行」这些情形,而它们在本仓库里都真实存在。
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if text[i:i + 2] == "//":
            j = text.find("\n", i)
            i = n if j < 0 else j
            out.append(" ")
        elif text[i:i + 2] == "/*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")
        elif text[i:i + 3] == '"""':
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
            out.append('""')
        elif ch == '"':
            i = _skip_string(text, i)
            out.append('""')
        elif ch == "'":
            i += 4 if text[i + 1:i + 2] == "\\" else 3  # 'x' 或 '\\n'
            out.append("'x'")
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def namespace_prefixes(known_packages: set) -> set:
    """从已扫到的包**自推导**出「我们自己的命名空间」= 每个包的**前三段**。

    `me.rerere.rikkahub.x.diag` → `me.rerere.rikkahub`。用它把第三方挡在外面 ——
    这个仓库的图标库依赖就叫 `me.rerere.hugeicons`,与我们的 `me.rerere.*` 同前缀。

    ⚠️ **必须是前三段,不是前两段**:取前两段会得到 `me.rerere` ——
    那恰好把第三方也圈进来了,于是它那几百个 import 又成了误报(实测 517 处没降)。
    差一段之差在这里就是「守住了」与「没守住」。
    """
    prefixes = set()
    for pkg in known_packages:
        parts = pkg.split(".")
        if len(parts) >= 3:
            prefixes.add(".".join(parts[:3]))
    return prefixes


def is_our_namespace(full: str, prefixes: set) -> bool:
    return any(full.startswith(p + ".") for p in prefixes)


def package_known(full: str, known: set, classes_by_pkg: dict) -> bool:
    """`full` 这个**全名**能落在仓库里吗?

    两种合法形状:
    ① **包 + 末段**:`a.b.C` —— 只要 `a.b` 是已知包即可;
    ② **嵌套类**:`a.b.Outer.Inner` —— 真实包是 `a.b`,那个 `Outer` 是包里的类。

    ⚠️ 首版只做①+「回溯到第一个已知前缀就放行」,于是判据**几乎空转**:
    任何以已知包开头的名字都被接受(`me.rerere.rikkahub.x.other.XLogcatCapture`
    照样放过),而那条判据的全部意义就是抓这种包名写错。
    这条漏洞同样是**反向验证**逼出来的 —— 它在当前代码上是绿的。

    故②要求:**回溯到某个已知包之后,它的下一段必须真是那个包里的类**。
    这才区分得开「嵌套类」与「包名写错」。
    """
    parts = full.split(".")
    if parts[:-1] and ".".join(parts[:-1]) in known:
        return True
    for i in range(len(parts) - 2, 0, -1):
        prefix = ".".join(parts[:i])
        if prefix in known:
            # 已知包之后的下一段必须是它声明的类 —— 否则就是把包名写错了。
            return parts[i] in classes_by_pkg.get(prefix, set())
    return False


def collect(root: Path):
    """→ (files, class_to_packages, package_to_classes, known_packages)。"""
    files = []
    present = 0
    for rel in SCAN_DIRS:
        base = root / rel
        if not base.is_dir():
            continue  # 某个模块被删/改名不该让检查直接不可用(下面有「至少要几个」的下限)
        present += 1
        files.extend(sorted(base.rglob("*.kt")))
    if present < MIN_MODULE_DIRS:
        raise Problem(
            f"只找到 {present} 个模块源码根(期望 ≥ {MIN_MODULE_DIRS})—— 目录结构变了,"
            f"这时**必须报错**,否则「包存在」那条判据会把整块模块判成不存在而刷屏误报。"
        )
    if len(files) < MIN_FILES:
        raise Problem(f"只扫到 {len(files)} 个 .kt(期望 ≥ {MIN_FILES})—— 扫描面写错了。")

    class_to_packages: dict = {}
    package_to_classes: dict = {}
    known_packages: set = set()
    for path in files:
        text = path.read_text(encoding="utf-8")
        m = PACKAGE_RE.search(text)
        if not m:
            continue
        pkg = m.group(1)
        known_packages.add(pkg)
        for name in set(DECL_RE.findall(code_only(text))):
            class_to_packages.setdefault(name, set()).add(pkg)
            package_to_classes.setdefault(pkg, set()).add(name)

    total = len(class_to_packages)
    if total < MIN_CLASSES:
        raise Problem(f"只收集到 {total} 个类/对象(期望 ≥ {MIN_CLASSES})—— 解析式样可能失效了。")
    return files, class_to_packages, package_to_classes, known_packages


def check(root: Path) -> list:
    problems = []
    files, class_to_packages, package_to_classes, known_packages = collect(root)
    our_prefixes = namespace_prefixes(known_packages)

    for path in files:
        text = path.read_text(encoding="utf-8")
        code = code_only(text)
        m = PACKAGE_RE.search(code)
        if not m:
            continue
        pkg = m.group(1)

        local = set(DECL_RE.findall(code))                     # 本文件声明的
        local |= set(FUN_DECL_RE.findall(code))                # 本文件声明的函数(见其注释)
        local |= set(TYPEALIAS_RE.findall(code))               # 本文件声明的别名
        local |= set(package_to_classes.get(pkg, set()))       # 同包的(不必 import)
        imported = set()
        for full, alias in IMPORT_RE.findall(text):
            # 包存在性:只判**我们自己模块的命名空间**(自推导,见 namespace_prefixes)。
            # 第三方的包我们没扫,故无从判断 —— 而这个仓库里恰好有个第三方依赖
            # 也用 `me.rerere.` 前缀,照前缀硬判会刷屏误报。
            if not full.endswith(".*") \
                    and is_our_namespace(full, our_prefixes) \
                    and not package_known(full, known_packages, package_to_classes):
                problems.append(
                    f"{path.relative_to(root)}: import `{full}` 的包在仓库里找不到 —— "
                    f"包名写错了(编译不过:Unresolved reference)。"
                )
            if full.endswith(".*"):
                wildcard_pkg = full[:-2]
                imported |= package_to_classes.get(wildcard_pkg, set())
                continue
            imported.add(alias or full.rsplit(".", 1)[-1])

        for use in sorted(set(TYPEISH_RE.findall(code))):
            if ALL_CAPS_RE.match(use):
                continue  # 常量名,不是类型名
            if use in local or use in imported:
                continue
            pkgs = class_to_packages.get(use)
            if not pkgs:
                continue  # 不是本仓库的类 —— 不判(Android/JDK/第三方太多,判了必误报)
            x_pkgs = {q for q in pkgs if X_PACKAGE_RE.search(q)}
            if not x_pkgs:
                continue  # 不是 X 的类 —— 不判(简单名撞车会满屏误报,见模块注释)
            line = code.count("\n", 0, code.find(use)) + 1
            problems.append(
                f"{path.relative_to(root)}:~{line}: 用了 `{use}` 但既没 import、也不与本文件同包"
                f" —— 它声明在 {sorted(x_pkgs)}(编译不过:Unresolved reference)。"
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
        print(f"[FAIL] 漏 import 自检无法进行:{exc}")
        return 1

    if problems:
        print(f"[FAIL] 漏 import 自检发现 {len(problems)} 处:")
        for p in problems[:40]:
            print(f"  - {p}")
        if len(problems) > 40:
            print(f"  …(还有 {len(problems) - 40} 处)")
        return 1

    print("[CHECK PASS] 没有「用了本仓库的类却没 import」的地方")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
