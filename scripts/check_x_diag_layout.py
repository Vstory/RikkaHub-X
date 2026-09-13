#!/usr/bin/env python3
"""诊断日志布局自检 —— 文件名、目录名、以及「每行都带 domain」三者不许各说各话。

## 它守的东西在 2026-09-13 变了(重要)

**改革前**:每个域一个文件(`chat.log` / `storage.log` / …),文件名**就是**域的标识。
于是三处必须严格对齐,而它们在编译期毫无约束:

| 漂移 | 后果 |
|---|---|
| 有人硬编码 `"chat.log"` 而枚举 key 改成 `chat_history` | 该文件在清单里被写成 `unrecognised file`,读者看不出这堆记录属于谁 |
| 枚举 key 含大写或 `-` | 文件名落在不同平台/大小写敏感度上不一致,同一域可能开成两个文件 |
| `LOG_NAME` 与某个域名撞车 | `describe()` 先判 logcat、后判域 → 域文件被误标成「原生 logcat」 |

**改革后**:所有事件落**一个** `events.log`,**域退回成每行的一个字段**。
于是「文件 ↔ 域」这层耦合**不存在了**,上面那张表里的判据大半自动作废。

⚠️ 但**有一条新约束取代了它**,而且更要紧 —— 既然不再靠文件名区分域,
那「按域挑记录」这件事就**只剩读取端过滤一条路**。若某一行忘了写 `domain`,
那条记录就**永远分不出属于谁**,而它不会有任何编译或运行时报错。
故本检查器现在的头号判据是:**两个组行函数都必须写出 `domain` 字段**。

## 判据

1. `XDomain` 枚举解析出的条目数与 key 形态(小写、无路径分隔符、不重复);
2. 文件名**单点定义**:`events.log` 派生自 `XDiagFileStore.EVENTS_FILE`、
   `logcat.log` 派生自 `XLogcatCapture.LOG_NAME`,不许在别处再写一份字面量;
3. 全仓每个 `xxx.log` 文件名引用都必须落在**会真正写出的名字**之内
   (否则 `XDiagZip.describe()` 会把它写成 `unrecognised file`)。
   ⚠️ **注释不算引用** —— 详见文件内 `comment_ranges` 的注释:首版没跳过注释,
   把「对比 LSPosed 的 modules.log」这种正当叙述也报了,属误报;
4. `XDiagZip.describe()` 必须引用上面三个常量(而不是硬编码文件名);
   ⚠️ **logcat 是分片的**(2026-09-13 起),名字带编号(`logcat_7.log`),故判据 2/3/5
   对它的口径是「引用 [XLogcatParts]」而不是「等于某个固定名」。
4. `XDiagLine` 与 `XNetLine` 都必须写出 `domain` 字段(见上「头号判据」);
5. `x-diag` / `session-` 两个目录字面量只许出现在 `XDiagSession.kt` 一处。

## 索引下限

解析出的域数 < 5、或扫到的 Kotlin 文件数 < 8 → **报错退出**,而不是「通过」。
否则枚举被重写(改个写法)会让本检查器静默扫到 0 条、永远绿灯 —— 那比不检查更坏。

## 用法

    python3 scripts/check_x_diag_layout.py [--root <仓库根>]

`--root` 只用于反向验证(把几份文件拷到临时目录、故意改坏,断言它真能报出来)。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# ── 扫描面 ──────────────────────────────────────────────────────────────
# 只扫诊断框架自己的两个目录:文件名与目录名只在这里被引用。
# 用通配而不是逐个列文件 —— 新增文件时不必回来登记(漏登记 = 新文件不受检)。
DIAG_DIRS = (
    "app/src/main/java/me/rerere/rikkahub/x/diag",
    "app/src/main/java/me/rerere/rikkahub/ui/pages/diagnostic",
)

# 下限(见模块注释「索引下限」)
MIN_DOMAINS = 5
MIN_FILES = 8

DIAG_DIR = "app/src/main/java/me/rerere/rikkahub/x/diag/"
DOMAIN_ENUM_FILE = DIAG_DIR + "XDiagnostics.kt"
SESSION_FILE = DIAG_DIR + "XDiagSession.kt"
FILE_STORE_FILE = DIAG_DIR + "XDiagFileStore.kt"
ZIP_FILE = DIAG_DIR + "XDiagZip.kt"
LOGCAT_FILE = DIAG_DIR + "XLogcatCapture.kt"
SURVIVOR_FILE = DIAG_DIR + "XSurvivorLog.kt"
DUMP_FILE = DIAG_DIR + "XLogcatDump.kt"
PARTS_FILE = DIAG_DIR + "XLogcatParts.kt"

# 两个组行函数 —— 判据 4 的对象。它们是「每行带 domain」这条不变量的**唯一**责任方。
LINE_FORMATTERS = (
    (DIAG_DIR + "XDiagLine.kt", "语义事件"),
    (DIAG_DIR + "XNetLine.kt", "网络记录"),
)

# 枚举条目:`NAME("key", "label")` —— 只认带两个字符串参数的那种,注释里的示例不受影响。
ENUM_ENTRY_RE = re.compile(r'^\s{4}([A-Z][A-Z0-9_]*)\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\)', re.M)

# 枚举块的边界:从 `enum class XDomain` 到第一个顶层 `}`。
ENUM_BLOCK_RE = re.compile(r"enum class XDomain[^{]*\{", re.M)

# 一个「日志文件名」引用。
# 前后的排除条件是为了不误抓 `Logging.log(` 这类**方法调用**:要求点号前至少一个字母,
# 且点号前那个字符不是 `.`(否则 `foo.bar.log` 会被截出 `bar.log`)。
LOG_NAME_RE = re.compile(r'(?<![\w.])([a-z][a-z0-9_\-]*)\.log\b')

# 常量定义处 —— 判据 2 从这里取「唯一真源」的名字。
EVENTS_CONST_RE = re.compile(r'const val EVENTS_FILE\s*=\s*"([^"]+)"')
SURVIVORS_CONST_RE = re.compile(r'const val SURVIVORS_FILE\s*=\s*"([^"]+)"')
DUMP_CONST_RE = re.compile(r'const val DUMP_FILE\s*=\s*"([^"]+)"')
PREFIX_CONST_RE = re.compile(r'const val PREFIX\s*=\s*"([^"]+)"')

# logcat 分片名:`<前缀>_<正整数><后缀>`。与 Kotlin 侧 `XLogcatParts.partOf` **同一判据** ——
# 两边必须收得一样窄,否则检查器会放行一个运行时认不出的名字:
# · 放宽成「以 logcat 开头」→ `logcat_backup.log` 被当成真日志;
# · 前导零(`[1-9]\d*` 写成 `\d+`)→ `logcat_01.log` 被放行,而 Kotlin 那边会判它不是分片
#   (实测:实现漏了拒前导零,是单测逼出来的;检查器这里也要跟上,免得两边又漂)。
PART_NAME_RE = re.compile(r"^(?P<prefix>[a-z][a-z0-9_]*)_[1-9]\d*\.log$")


def comment_ranges(text: str) -> list:
    """注释区间 —— 判据 3 **刻意跳过它们**(见模块注释「注释不算引用」)。

    ⚠️ `//` 的识别带一个引号启发:`https://…` 这种字符串里的双斜杠不该被当成注释起点
    (否则同一行后面的真字面量会被连带跳过)。数一下 `//` 之前的引号个数即可 ——
    奇数说明在字符串里。够用,且失效方向是「多报」而不是「漏报」。
    """
    ranges = []
    for m in re.finditer(r"/\*.*?\*/", text, re.S):
        ranges.append((m.start(), m.end()))
    for m in re.finditer(r"//[^\n]*", text):
        line_start = text.rfind("\n", 0, m.start()) + 1
        if text.count('"', line_start, m.start()) % 2 == 1:
            continue  # 在字符串里,不是注释
        ranges.append((m.start(), m.end()))
    return ranges

# 目录字面量(带引号,精确匹配 —— XDiagEnv 的标记 `===== [x-diag]` 是另一回事,不该被算进来)
ROOT_DIR_RE = re.compile(r'"x-diag"')
SESSION_PREFIX_RE = re.compile(r'"session-"')


class Problem(Exception):
    """会让检查直接中止的解析失败 —— 与「发现问题」区分开(见 main)。"""


def read(root: Path, rel: str) -> str:
    path = root / rel
    if not path.is_file():
        raise Problem(f"找不到 {rel}(仓库结构变了?)")
    return path.read_text(encoding="utf-8")


def parse_domains(root: Path) -> list[tuple[str, str]]:
    """解析 XDomain 枚举 → [(NAME, key)]，保持声明顺序。"""
    text = read(root, DOMAIN_ENUM_FILE)
    open_match = ENUM_BLOCK_RE.search(text)
    if not open_match:
        raise Problem(f"{DOMAIN_ENUM_FILE}:找不到 `enum class XDomain` 声明")
    # 从 `{` 起做括号配平,拿到整个枚举体 —— 比正则找 `}` 稳,因为 KDoc 里也有花括号。
    depth = 0
    end = None
    for i in range(open_match.end() - 1, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                end = i
                break
    if end is None:
        raise Problem(f"{DOMAIN_ENUM_FILE}:XDomain 枚举的花括号不配平")
    body = text[open_match.end():end]
    entries = [(m.group(1), m.group(2)) for m in ENUM_ENTRY_RE.finditer(body)]
    if len(entries) < MIN_DOMAINS:
        raise Problem(
            f"{DOMAIN_ENUM_FILE}:只解析出 {len(entries)} 个域(期望至少 {MIN_DOMAINS} 个)。\n"
            f"       解析式样可能已与实际写法不符 —— 这时**必须报错**,"
            f"否则本检查器扫到 0 条也会「通过」。"
        )
    return entries


def diag_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for rel in DIAG_DIRS:
        d = root / rel
        if not d.is_dir():
            raise Problem(f"找不到诊断目录 {rel}")
        files.extend(sorted(p for p in d.rglob("*.kt")))
    if len(files) < MIN_FILES:
        raise Problem(
            f"诊断目录里只扫到 {len(files)} 个 .kt(期望至少 {MIN_FILES})—— 扫描面写错了。"
        )
    return files


def check(root: Path) -> list[str]:
    problems: list[str] = []
    domains = parse_domains(root)
    files = diag_files(root)

    # ── 判据 1:key 形态与唯一性 ──
    # key 不再是文件名,但它**仍然是每行 `domain` 字段的取值** —— 形态错了一样会让
    # 读取端的 `grep '"domain":"..."'` 变得别扭,故这条留着。
    seen: dict[str, str] = {}
    for name, key in domains:
        if not re.fullmatch(r"[a-z][a-z0-9_]*", key):
            problems.append(
                f"{DOMAIN_ENUM_FILE}:域 {name} 的 key '{key}' 不合规 —— "
                f"只允许小写字母/数字/下划线,且以字母开头(它是每行 domain 字段的取值)。"
            )
        if key in seen:
            problems.append(
                f"{DOMAIN_ENUM_FILE}:域 {name} 与 {seen[key]} 的 key 重复('{key}')—— "
                f"读取端按 domain 过滤时分不开两者。"
            )
        else:
            seen[key] = name

    # ── 判据 2:文件名单点定义 ──
    store_text = read(root, FILE_STORE_FILE)
    m = EVENTS_CONST_RE.search(store_text)
    if not m:
        raise Problem(f"{FILE_STORE_FILE}:找不到 `const val EVENTS_FILE` 常量")
    events_name = m.group(1)

    # ⚠️ logcat 的名字**不再是一个固定常量**:它分片,名字带编号。真源是
    #    XLogcatParts.PREFIX + SUFFIX,而各处一律走 XLogcatParts.nameOf() 拼。
    parts_text = read(root, PARTS_FILE)
    m = PREFIX_CONST_RE.search(parts_text)
    if not m:
        raise Problem(f"{PARTS_FILE}:找不到 `const val PREFIX` 常量")
    logcat_prefix = m.group(1)

    survivor_text = read(root, SURVIVOR_FILE)
    m = SURVIVORS_CONST_RE.search(survivor_text)
    if not m:
        raise Problem(f"{SURVIVOR_FILE}:找不到 `const val SURVIVORS_FILE` 常量")
    survivors_name = m.group(1)

    dump_text = read(root, DUMP_FILE)
    m = DUMP_CONST_RE.search(dump_text)
    if not m:
        raise Problem(f"{DUMP_FILE}:找不到 `const val DUMP_FILE` 常量")
    dump_name = m.group(1)

    # 事件/存活层不得长成分片名的样子(那样 describe() 会先按「原始 logcat」判它,
    # 于是时间线被说成原始日志 —— 读者据此判断「这里没有结构化记录」)。
    for fixed_name, where in (
        (events_name, FILE_STORE_FILE),
        (survivors_name, SURVIVOR_FILE),
        (dump_name, DUMP_FILE),
    ):
        m2 = PART_NAME_RE.match(fixed_name)
        if m2 and m2.group("prefix") == logcat_prefix:
            problems.append(
                f"{where}:'{fixed_name}' 长得像 logcat 分片名 —— describe() 会先按"
                f"「原始 logcat」判它,于是这个文件被说成别的用途。"
            )

    allowed = {events_name, survivors_name, dump_name}

    # ── 判据 3:代码里每个文件名引用都必须落在允许集合里(注释不算引用) ──
    #
    # ⚠️ **刻意跳过注释**。这条判据守的是「代码里有没有一个会产生文件名的字面量」——
    # 那种字面量会与常量漂移,于是 describe() 认不出文件。而**注释里的文件名不会产生任何
    # 行为**,它只会出现在两种正当场合:解释历史(「原先独占一个文件」)与对比其它项目
    # (「LSPosed 的 modules.log / verbose.log 也是重叠的」)。
    #
    # 首版没有跳过,于是把后一种也报了出来(实测一次 10 处里有 2 处是 LSPosed 的名字)——
    # 这类误报会被容忍到「没人再信这个检查」,比不检查更坏。
    #
    # 代价(诚实记下):**过时的文档不再被守**。比如注释里还写着早已并入 events.log 的
    # 旧文件名,这里不会报 —— 那是文档漂移,与「包打不对」不是一类,故接受。
    for path in files:
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(root)
        comments = comment_ranges(text)
        for match in LOG_NAME_RE.finditer(text):
            name = match.group(0)
            if name in allowed:
                continue
            # 分片名(`logcat_7.log`)放行:它由 XLogcatParts 拼出来,是本次会话真会写的文件。
            if PART_NAME_RE.match(name) and name.startswith(logcat_prefix + "_"):
                continue
            if any(start <= match.start() < end for start, end in comments):
                continue
            # 常量定义那一行本身就是真源,不算「别处又写一份」。
            line_start = text.rfind("\n", 0, match.start()) + 1
            line_text = text[line_start:text.find("\n", match.start())]
            if "const val EVENTS_FILE" in line_text or "const val LOG_NAME" in line_text \
                    or "const val SURVIVORS_FILE" in line_text:
                continue
            line = text.count("\n", 0, match.start()) + 1
            problems.append(
                f"{rel}:{line}: 文件名 '{name}' 不是本次会话会写出的文件 —— "
                f"XDiagZip.describe() 会把它写成 'unrecognised file'。"
                f"合法值:{sorted(allowed)}(历史文件名可在注释里叙述,但不要写成带引号的字面量)"
            )

    # ── 判据 4:两个组行函数都必须写出 domain(取代了旧的「文件↔域」耦合) ──
    # 这是合并成单文件后**最要紧**的一条:不再靠文件名区分域,只剩读取端过滤一条路。
    # 某一行漏了 domain → 那条记录永远分不出属于谁,而且没有任何编译/运行时报错。
    for rel, what in LINE_FORMATTERS:
        text = read(root, rel)
        if not re.search(r'put\(\s*"domain"', text):
            problems.append(
                f"{rel}:没有写出 `domain` 字段 —— 所有事件现在同住一个 {events_name},"
                f"按域挑记录**只剩读取端过滤一条路**(grep '\"domain\":\"…\"')。"
                f"{what}的行漏了它,那条记录就永远分不出属于谁。"
            )

    # ── 判据 5:描述函数必须引用常量,不许硬编码文件名 ──
    zip_text = read(root, ZIP_FILE)
    if "XDiagFileStore.EVENTS_FILE" not in zip_text:
        problems.append(
            f"{ZIP_FILE}:describe() 没有引用 `XDiagFileStore.EVENTS_FILE` —— "
            f"硬编码文件名会在常量改名时漂移,清单就会把 {events_name} 写成 'unrecognised file'。"
        )
    if "XLogcatParts." not in zip_text:
        problems.append(
            f"{ZIP_FILE}:describe() 没有引用 `XLogcatParts` —— logcat 分片的名字带编号,"
            f"只认某个固定名(或硬编码)会在轮转之后静默失效:包里的分片被说成 "
            f"'unrecognised file',读者以为那堆日志不属于本应用。"
        )
    if "XLogcatDump.DUMP_FILE" not in zip_text:
        problems.append(
            f"{ZIP_FILE}:describe() 没有引用 `XLogcatDump.DUMP_FILE` —— 快照与实时捕获"
            f"**长得像但来路不同**(一份未过滤、一份剔过噪),不说明的话读者会拿两份逐行"
            f"对比,然后怀疑日志坏了。"
        )
    if "XSurvivorLog.SURVIVORS_FILE" not in zip_text:
        problems.append(
            f"{ZIP_FILE}:describe() 没有引用 `XSurvivorLog.SURVIVORS_FILE` —— "
            f"存活层是**唯一跨会话**的文件(独立于开关),清单必须能说明它是什么。"
        )

    # ── 判据 6:目录字面量单点定义 ──
    for path in files:
        if path.name == Path(SESSION_FILE).name:
            continue
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(root)
        for regex, what in ((ROOT_DIR_RE, '"x-diag"'), (SESSION_PREFIX_RE, '"session-"')):
            for match in regex.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                problems.append(
                    f"{rel}:{line}: 目录字面量 {what} 出现在 {Path(SESSION_FILE).name} 之外 —— "
                    f"复制一份就会与 XDiagSession 漂移,「清空」可能扫不到旧目录。"
                )

    # ⚠️ 刻意**不查**「每个域在 XDiagZip.describe() 里有没有自己的说明」——
    # describe() 是**通用**的,而且合并之后它连域都不再提(域不是文件名了)。
    # 硬要写一条,只会得到一条永远通过的检查 —— 那比不检查更坏(见仓库纪律「两种假检查」)。
    return problems


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--root", default=None, help="仓库根(默认取脚本所在目录的上一级)")
    args = ap.parse_args(argv[1:])

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent
    try:
        problems = check(root)
    except Problem as exc:
        print(f"[FAIL] 诊断布局自检无法进行:{exc}")
        return 1

    if problems:
        print(f"[FAIL] 诊断日志布局自检发现 {len(problems)} 处问题:")
        for p in problems:
            print(f"  - {p}")
        return 1

    domains = parse_domains(root)
    print(
        f"[CHECK PASS] {len(domains)} 个域 / {len(diag_files(root))} 个文件:"
        f"文件名单点定义,两个组行函数都带 domain,目录字面量单点定义"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
