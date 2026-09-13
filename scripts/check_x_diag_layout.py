#!/usr/bin/env python3
"""诊断日志布局自检 —— 域枚举、文件名、目录名三处不许各说各话。

## 为什么需要它

诊断包里的**文件名就是域的唯一标识**:`XDiagFileStore` 按 `domain.key + ".log"` 建文件,
`XDiagZip.describe()` 反过来用文件名去 `XDomain.entries` 里找域。

于是三处必须严格对齐,而它们**在编译期毫无约束**:

| 漂移 | 后果 |
|---|---|
| 有人硬编码 `"chat.log"` 而枚举 key 改成 `chat_history` | 该文件在清单里被写成 `unrecognised file`,读者看不出这堆记录属于谁 |
| 枚举 key 含大写或 `-` | 文件名落在不同平台/大小写敏感度上不一致,同一域可能开成两个文件 |
| `LOG_NAME` 与某个域名撞车 | `describe()` 先判 logcat、后判域 → 域文件被误标成「原生 logcat」 |
| 目录名(`x-diag` / `session-`)在别处再写一份 | 「清空」扫不到旧目录 → **点了清空却没清** |

这些都是**编译得过、测试也过得去**的错误,只在读懂包内容时才暴露 —— 而那时已经晚了。

## 判据

1. `XDomain` 枚举解析出的条目数与 key 形态(小写、无路径分隔符、不重复);
2. 全仓每个 `xxx.log` 文件名引用,必须是**某个域的 key + `.log`**,或 `logcat.log`;
3. `net.log` 在 `XDiagZip` 里必须**派生自枚举**(不许硬编码),`XDiagFileStore` 必须用 `domain.key` 拼名;
4. `x-diag` / `session-` 两个目录字面量只许出现在 `XDiagSession.kt` 一处。

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

DOMAIN_ENUM_FILE = "app/src/main/java/me/rerere/rikkahub/x/diag/XDiagnostics.kt"
SESSION_FILE = "app/src/main/java/me/rerere/rikkahub/x/diag/XDiagSession.kt"
FILE_STORE_FILE = "app/src/main/java/me/rerere/rikkahub/x/diag/XDiagFileStore.kt"
ZIP_FILE = "app/src/main/java/me/rerere/rikkahub/x/diag/XDiagZip.kt"
LOGCAT_FILE = "app/src/main/java/me/rerere/rikkahub/x/diag/XLogcatCapture.kt"

# 枚举条目:`NAME("key", "label")` —— 只认带两个字符串参数的那种,注释里的示例不受影响。
ENUM_ENTRY_RE = re.compile(r'^\s{4}([A-Z][A-Z0-9_]*)\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\)', re.M)

# 枚举块的边界:从 `enum class XDomain` 到第一个顶层 `}`。
ENUM_BLOCK_RE = re.compile(r"enum class XDomain[^{]*\{", re.M)

# 一个「日志文件名」引用。
# 前后的排除条件是为了不误抓 `Logging.log(` 这类**方法调用**:要求点号前至少一个字母,
# 且点号前那个字符不是 `.`(否则 `foo.bar.log` 会被截出 `bar.log`)。
LOG_NAME_RE = re.compile(r'(?<![\w.])([a-z][a-z0-9_\-]*)\.log\b')

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
    keys = [k for _, k in domains]
    files = diag_files(root)

    # ── 判据 1:key 形态与唯一性 ──
    seen: dict[str, str] = {}
    for name, key in domains:
        if not re.fullmatch(r"[a-z][a-z0-9_]*", key):
            problems.append(
                f"{DOMAIN_ENUM_FILE}:域 {name} 的 key '{key}' 不合规 —— "
                f"只允许小写字母/数字/下划线,且以字母开头(它是文件名的一部分)。"
            )
        if key in seen:
            problems.append(
                f"{DOMAIN_ENUM_FILE}:域 {name} 与 {seen[key]} 的 key 重复('{key}')—— "
                f"两个域会写进同一个文件,导出时分不开。"
            )
        else:
            seen[key] = name

    domain_files = {k + ".log" for k in keys}

    # ── 判据 2:LOG_NAME 不许与某个域名撞车 ──
    logcat_text = read(root, LOGCAT_FILE)
    m = re.search(r'const val LOG_NAME\s*=\s*"([^"]+)"', logcat_text)
    if not m:
        raise Problem(f"{LOGCAT_FILE}:找不到 `LOG_NAME` 常量")
    logcat_name = m.group(1)
    if logcat_name in domain_files:
        problems.append(
            f"{LOGCAT_FILE}:LOG_NAME ('{logcat_name}')与某个域的 key 撞车 —— "
            f"XDiagZip.describe() 先判 logcat,该域文件会被误标成「原生 logcat」。"
        )

    allowed = domain_files | {logcat_name}

    # ── 判据 3:每个文件名引用都必须落在允许集合里 ──
    for path in files:
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(root)
        for match in LOG_NAME_RE.finditer(text):
            name = match.group(0)
            if name in allowed:
                continue
            line = text.count("\n", 0, match.start()) + 1
            problems.append(
                f"{rel}:{line}: 文件名 '{name}' 不挂在任何域上 —— "
                f"XDiagZip.describe() 会把它写成 'unrecognised file'。"
                f"合法值:{sorted(allowed)}"
            )

    # ── 判据 4:域名必须派生自枚举,不许硬编码 ──
    zip_text = read(root, ZIP_FILE)
    net_name = re.search(r'NET_NAME\s*=\s*([^\n]+)', zip_text)
    if not net_name:
        problems.append(f"{ZIP_FILE}:找不到 NET_NAME(describe() 依赖它判 net.log)")
    elif "XDomain.NET.key" not in net_name.group(1):
        problems.append(
            f"{ZIP_FILE}:NET_NAME 未派生自 XDomain.NET.key(现值:{net_name.group(1).strip()})—— "
            f"硬编码会在枚举改 key 时漂移。"
        )

    store_text = read(root, FILE_STORE_FILE)
    if 'domain.key + ".log"' not in store_text:
        problems.append(
            f"{FILE_STORE_FILE}:建文件处不再用 `domain.key + \".log\"` —— "
            f"文件名从此与枚举脱钩,本检查器的其余判据也就失去意义。"
        )

    # ── 判据 5:目录字面量单点定义 ──
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
    # describe() 是**通用**的(拿文件名去 XDomain.entries 里找),本来就没有逐域的代码。
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
        f"文件名与域枚举一致,目录字面量单点定义"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
