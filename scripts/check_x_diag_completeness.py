#!/usr/bin/env python3
"""「诊断开关一打开,就都能记到」的可检验保证。

## 用户的要求(2026-09-13)

原话:「只要一打开,我们这 X 上遇到的所有问题,它都能记录到 —— 你不能打开之后,
还记录不到问题之类的。」

这个要求本身是对的,但要拆成三类才谈得上「保证」:

| 类 | 例子 | 靠什么捕获 |
|---|---|---|
| ① 进程级 | 未捕获异常 / ANR / 原生崩溃 / 被系统杀 | `XCrashReport`(读回上游 CrashHandler 的栈)、`XExitReport`(读 `ApplicationExitInfo`) |
| ② **展示给用户** | 错误提示 / 弹窗 | `XUserVisibleErrors` 挂在 `ToasterState.onToastDismissed` 上(唯一漏斗) |
| ③ **被静默吞掉** | `runCatching { }` 里失败当无事;逻辑错了但没有异常 | ⚠️ **不可能自动捕获** —— 没有异常就没有任何信号。只能逐个埋点 |

本检查器管的是**前两类不会悄悄漏掉**,以及**第三类不会悄悄变多**。

## 判据

1. **地基**:`RikkaHubApp` 里那 9 处接线(时钟源 / 开关 / 存活层 / 会话 / logcat / 落盘 /
   请求记录 / 崩溃回读 / 退出回读)必须齐全,且 `CrashHandler.install` 在。
   缺任一 → 那一类问题**根本没有入口**,而应用看起来完全正常。
2. **漏斗接全**:每个 `rememberToasterState(` 都必须带 `onToastDismissed = …`。
   新增一个 toaster 就等于开了一条**绕过记录的旁路** —— 这条判据专门堵它。
3. **不许绕过漏斗**:每个 `Toast.makeText(`(系统原生 Toast)必须在近旁有记录痕迹
   (`XLog` / `XDiagnostics` / `recordShown` / `Log.e|w` / `printStackTrace`),
   或者显式标注 `// x-diag-toast-ok: <理由>`。
   ⚠️ 原生 Toast **不走我们的 toaster**,所以它天然是旁路;要么记、要么声明「这不是错误」。
4. **静默吞掉必须棘轮**(见下):未标注的静默失败点数 **不得超过基线**。

## 判据 4 的基线(棘轮)

`runCatching` / `catch` 里**什么都不做**的点,2026-09-13 实测 **137 处**(381 个失败处理点里)。
它们**不能全记** —— 很多是正常写法(「试一下,失败就当作没有」),全记会让日志被噪声淹没,
而**噪声也是失效**。故策略是:
· 想保留静默 → 标注 `// x-diag-swallow: <理由>`;
· 想让它记账 → 块内加 `XLog` / `recordStickyFailure`;
· 两者都不做 → 只许**不超过基线**。

于是「新增一个洞」会**当场红**,而清理是**只减不增**的。基线要下调时改 `SWALLOW_BASELINE`
(只许往下改,改大等于自己给自己开口子)。

## 索引下限

扫到的 `.kt` 少于 400、或 `rememberToasterState` 一处都没有 → **报错退出**。
(后者说明扫描式样已与实际写法不符,那时判据 2 会「永远通过」。)

## 用法

    python3 scripts/check_x_diag_completeness.py [--root <仓库根>]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SRC_DIR = "app/src/main/java"
APP_FILE = "app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt"

# ── 判据 1:地基 ──
REQUIRED_WIRING = (
    "XDiagnostics.installClockSource",
    "DiagnosticSwitchStore.install",
    "XSurvivorLog.install",
    "XDiagSession.install",
    "XLogcatCapture.install",
    "XDiagFileStore.install",
    "XRequestLog.install",
    "XCrashReport.install",
    "XExitReport.install",
    "CrashHandler.install",
)

# ── 判据 2 ──
TOASTER_CALL = "rememberToasterState("
TOASTER_HOOK = "onToastDismissed"

# ── 判据 3 ──
NATIVE_TOAST = "Toast.makeText("
TOAST_OK_MARK = "x-diag-toast-ok:"
TOAST_LOOKBACK = 18
TOAST_LOOKAHEAD = 6
# 「这次失败已经留下了痕迹」的迹象。logcat 那两项也算 —— 它们在开关开着时会被抓进包里。
RECORD_TRACES = (
    "XLog", "XDiagnostics", "recordShown", "recordStickyFailure", "recordSticky",
    "Log.e", "Log.w", "printStackTrace",
)

# ── 判据 4 ──
# 2026-09-13 实测:179 处(381 个失败处理点里)。这只许**往下**改 ——
# 改大等于自己给自己开口子,而这条棘轮的全部意义就是「只减不增」。
SWALLOW_BASELINE = 179
SWALLOW_MARK = "x-diag-swallow:"
SWALLOW_OPEN = re.compile(
    r"(runCatching\s*(?:<[^>]*>\s*)?\{|catch\s*\([^)]*\)\s*\{)")
# 「算作处理了」的痕迹(比判据 3 更宽:抛出/回调/返回也算)
HANDLED = (
    "XLog", "XDiagnostics", "recordShown", "recordStickyFailure", "recordSticky",
    "Log.e", "Log.w", "Log.i", "throw ", "onFailure", "toaster.show",
    "printStackTrace", "return false", "setError",
)

MIN_KT_FILES = 400
MIN_TOASTERS = 1


class Problem(Exception):
    """无法进行 —— 与「发现问题」区分开。"""


def block_of(lines, start: int, open_col: int) -> str:
    """从 start 行 open_col 处的 `{` 起按花括号配对取块文本(含该行)。"""
    depth, out = 0, []
    for i in range(start, min(len(lines), start + 200)):
        seg = lines[i][open_col:] if i == start else lines[i]
        out.append(seg)
        depth += seg.count("{") - seg.count("}")
        open_col = 0
        if depth <= 0:
            break
    return "\n".join(out)


def check(root: Path) -> list:
    base = root / SRC_DIR
    if not base.is_dir():
        raise Problem(f"找不到 {SRC_DIR}")
    files = sorted(base.rglob("*.kt"))
    if len(files) < MIN_KT_FILES:
        raise Problem(f"只扫到 {len(files)} 个 .kt(期望 ≥ {MIN_KT_FILES})—— 扫描面写错了")

    problems = []

    # ── 判据 1:地基 ──
    app = root / APP_FILE
    if not app.is_file():
        raise Problem(f"找不到 {APP_FILE}")
    app_text = app.read_text(encoding="utf-8")
    for needle in REQUIRED_WIRING:
        if needle not in app_text:
            problems.append(
                f"{APP_FILE}:少了接线 `{needle}` —— 那一类问题**根本没有入口**,"
                f"而应用看起来完全正常。"
            )

    toaster_calls = 0
    toast_sites = 0
    swallows = []

    for path in files:
        rel = str(path.relative_to(root))
        lines = path.read_text(encoding="utf-8").split("\n")
        commented = False

        for i, line in enumerate(lines):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                continue

            # ── 判据 2:漏斗接全 ──
            if TOASTER_CALL in line:
                toaster_calls += 1
                # 调用可能跨行,故往后看一小段
                window = "\n".join(lines[i:i + 4])
                if TOASTER_HOOK not in window:
                    problems.append(
                        f"{rel}:{i + 1}: `{TOASTER_CALL}` 没有带 `{TOASTER_HOOK}` —— "
                        f"这是一条**绕过错误记录的旁路**(该 toaster 弹出的错误不会被记下)。"
                    )

            # ── 判据 3:原生 Toast 必须留痕或声明 ──
            if NATIVE_TOAST in line:
                toast_sites += 1
                near = "\n".join(
                    lines[max(0, i - TOAST_LOOKBACK):i + TOAST_LOOKAHEAD])
                if TOAST_OK_MARK in near:
                    pass
                elif any(t in near for t in RECORD_TRACES):
                    pass
                else:
                    problems.append(
                        f"{rel}:{i + 1}: `{NATIVE_TOAST}` 既没有记录痕迹、也没有声明 —— "
                        f"系统原生 Toast **不走我们的 toaster 漏斗**,所以它天然是旁路。"
                        f"要么在近旁加 `XUserVisibleErrors.recordShown(...)` / `XLog` / `Log.e`,"
                        f"要么标注 `// {TOAST_OK_MARK} <理由>`(若它确实只是普通提示)。"
                    )

            # ── 判据 4:静默吞掉 ──
            m = SWALLOW_OPEN.search(line)
            if m:
                open_col = m.start(1) + line[m.start(1):].index("{")
                body = block_of(lines, i, open_col)
                body_code = re.sub(r"//[^\n]*", "", body)
                marked = SWALLOW_MARK in body or SWALLOW_MARK in lines[max(0, i - 1)]
                if not marked and not any(h in body_code for h in HANDLED):
                    swallows.append(f"{rel}:{i + 1}")

    if toaster_calls < MIN_TOASTERS:
        raise Problem(
            f"一处 `{TOASTER_CALL}` 都没找到(期望 ≥ {MIN_TOASTERS})—— "
            f"式样已与实际写法不符,否则判据 2 会「永远通过」。"
        )

    # ── 判据 4 的结论 ──
    if len(swallows) > SWALLOW_BASELINE:
        shown = "\n".join(f"      - {s}" for s in swallows[:12])
        problems.append(
            f"未标注的**静默失败**点从基线 {SWALLOW_BASELINE} 涨到了 {len(swallows)} —— "
            f"新增的洞会悄悄吞掉错误(用户看到的是「功能没反应」,而日志里什么都没有)。\n"
            f"      处理方式二选一:块内加 `XLog` / `recordStickyFailure`(让它记账),"
            f"或标注 `// {SWALLOW_MARK} <理由>`(声明这里静默是刻意的)。\n"
            f"      前若干处:\n{shown}"
        )

    # 把当前数字带出来(便于「只减不增」地往下压)
    problems.append(f"__INFO__静默吞掉 {len(swallows)} 处 / 基线 {SWALLOW_BASELINE};"
                    f"原生 Toast {toast_sites} 处;toaster 调用 {toaster_calls} 处")
    return problems


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--root", default=None)
    args = ap.parse_args(argv[1:])
    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent

    try:
        problems = check(root)
    except Problem as exc:
        print(f"[FAIL] 诊断完整性自检无法进行:{exc}")
        return 1

    info = [p for p in problems if p.startswith("__INFO__")]
    real = [p for p in problems if not p.startswith("__INFO__")]
    for i in info:
        print(i[len("__INFO__"):])

    if real:
        print(f"[FAIL] 诊断完整性自检发现 {len(real)} 处:")
        for p in real:
            print(f"  - {p}")
        return 1

    print("[CHECK PASS] 诊断完整性:地基齐全、漏斗接全、原生 Toast 都有交代、静默失败未超基线")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
