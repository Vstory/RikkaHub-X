#!/usr/bin/env python3
"""X 定制日志闸门自检：禁止绕过 XLog 直接调 android.util.Log。

背景（2026-09-11 用户要求）：诊断开关关闭时，**X 的正常流程不得向 logcat 输出任何东西**，
失败仍输出（与上游一致）。这条承诺靠 `XLog.info` / `XLog.warn` 实现 ——
`XLog.info` 在关闭时直接 return，连消息字符串都不拼。

**一旦某处直接写 `Log.i(...)`，它就绕过了开关**：用户关掉诊断后仍会在 logcat 里看到它，
承诺失效。这类错**编译不报错、单测也测不到**（没人会为「某处没走统一入口」写断言），
只有机检能拦。故有本脚本。

两条规则（外加一条精确补充）：
  ① `app/src/main/java/me/rerere/rikkahub/x` 及其子包内，除 `x/diag/XLog.kt` 外，
     **禁止出现 `Log.v/d/i/w/e(`** —— X 自己的代码必须全部走 XLog。
  ② **被 X 改动的上游文件**里，凡紧跟在 `[X-custom]` 标记之后（[MARKER_LOOKBACK] 行内）的
     `Log.*` 调用同样禁止 —— 那些行是 X 加的，同样必须走 XLog。
  ③ X 的 logcat tag 字面量（`"XStorage"` / `"XCustom"`）不得出现在 `x/diag` 之外 ——
     凡是写这个 tag 的地方，本该是在写 X 的日志，那就等于绕过了闸门。

规则 ② 用「[X-custom] 标记」判定，是因为本项目的约定是「X 在上游文件里的改动一律带该标记」，
故标记即「这段是 X 的代码」，比维护一份文件清单更不易漏。

**已知边界**：规则 ② 是**行距启发式**，不是语法分析。
[MARKER_LOOKBACK] = 8 是实测选定的：3 会漏掉真实的两处（注释块把距离推到 7 行），
12 会误伤 `S3Sync` / `WebDavSync` 里上游自身的日志（X 在那里只改了文件名前缀）。
**改动上游文件时若把 `[X-custom]` 标记挪远，本规则会漏报** —— 故它是兜底提醒，
真正的保证是「所有 X 日志都写 XLog」这个习惯。

**查不到什么**：不检查事件名规范（那是 `XStorageEventsTest` 等单测的事）、
不检查日志内容是否已脱敏、不检查是否正确使用了域。本脚本只管「有没有绕过闸门」。
"""

from __future__ import annotations

import glob
import os
import re
import sys

SCRIPT_VERSION = "1.0.0"

DEFAULT_ROOT = "app/src/main/java"
X_PACKAGE_SUFFIX = os.path.join("me", "rerere", "rikkahub", "x")

# 唯一允许直接使用 android.util.Log / 写 X tag 的目录（闸门自身）
GATE_DIR_SUFFIX = os.path.join("x", "diag")

# 标记：X 在上游文件里的改动一律带它
X_CUSTOM_MARKER = "[X-custom]"

# 标记之后多少行内的 Log 调用算「X 加的」（实测选定，见文档串「已知边界」）
MARKER_LOOKBACK = 8

# X 的 logcat tag 字面量 —— 它们只应出现在闸门里
X_TAG_LITERALS = ('"XStorage"', '"XCustom"')

LOG_CALL = re.compile(r"\bLog\s*\.\s*[vdwie]\s*\(")


def is_comment_line(line: str) -> bool:
    """行注释或块注释体（KDoc 里会提到 Log.i(...)，那是在说明而非调用）。"""
    stripped = line.lstrip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def check_x_package(path: str, lines: list[str]) -> list[str]:
    problems: list[str] = []
    for index, line in enumerate(lines, 1):
        if is_comment_line(line):
            continue
        if LOG_CALL.search(line):
            problems.append(
                f"{path}:{index}: X 包内直接调用了 android.util.Log —— "
                f"请改用 XLog.info / XLog.warn（否则会绕过诊断开关）\n"
                f"        {line.strip()[:100]}"
            )
    return problems


def check_upstream_file(path: str, lines: list[str]) -> list[str]:
    problems: list[str] = []
    for index, line in enumerate(lines, 1):
        if is_comment_line(line) or not LOG_CALL.search(line):
            continue
        # 往上找 [X-custom] 标记
        start = max(0, index - 1 - MARKER_LOOKBACK)
        window = lines[start:index]
        if any(X_CUSTOM_MARKER in candidate for candidate in window):
            problems.append(
                f"{path}:{index}: 该行位于 [X-custom] 标记之后的日志调用 —— "
                f"请改用 XLog.info / XLog.warn（否则会绕过诊断开关）\n"
                f"        {line.strip()[:100]}"
            )
    return problems


def check_x_tag_literal(path: str, lines: list[str]) -> list[str]:
    """规则 ③：X 的 tag 字面量不得出现在闸门之外。

    精确、无启发式：凡是写这个 tag 的地方，本该是在写 X 的日志 ——
    直接写 tag 就等于绕过了闸门。
    """
    problems: list[str] = []
    for index, line in enumerate(lines, 1):
        if is_comment_line(line):
            continue
        for literal in X_TAG_LITERALS:
            if literal in line:
                problems.append(
                    f"{path}:{index}: 出现了 X 的 logcat tag 字面量 {literal} —— "
                    f"该 tag 只应由 x/diag 下的闸门使用，此处疑似绕过\n"
                    f"        {line.strip()[:100]}"
                )
    return problems


def read_lines(path: str) -> list[str]:
    with open(path, encoding="utf-8") as handle:
        return handle.read().splitlines()


def main(argv: list[str]) -> int:
    root = DEFAULT_ROOT
    if "--root" in argv:
        index = argv.index("--root")
        if index + 1 < len(argv):
            root = argv[index + 1]

    if not os.path.isdir(root):
        print(f"[错误] 源码根不存在: {root}", file=sys.stderr)
        return 1

    x_root = os.path.join(root, X_PACKAGE_SUFFIX)
    files = sorted(glob.glob(os.path.join(root, "**", "*.kt"), recursive=True))
    if not files:
        print("[错误] 没找到任何 .kt 文件 —— 检查会全部通过,形同虚设", file=sys.stderr)
        return 1

    problems: list[str] = []
    checked_x = 0
    checked_upstream = 0
    gate_dir = os.path.abspath(os.path.join(root, X_PACKAGE_SUFFIX, "diag"))

    for path in files:
        abs_path = os.path.abspath(path)
        lines = read_lines(path)
        if abs_path.startswith(gate_dir + os.sep):
            continue  # 闸门自身
        if abs_path.startswith(os.path.abspath(x_root) + os.sep):
            checked_x += 1
            problems.extend(check_x_package(path, lines))
        else:
            checked_upstream += 1
            problems.extend(check_upstream_file(path, lines))
        problems.extend(check_x_tag_literal(path, lines))

    if problems:
        print(f"检查 {checked_x} 个 X 文件 + {checked_upstream} 个上游文件,"
              f"发现 {len(problems)} 处绕过闸门:")
        for item in problems:
            print(f"  - {item}")
        return 1

    print(f"检查 {checked_x} 个 X 文件 + {checked_upstream} 个上游文件,"
          f"0 处绕过(全部经 XLog)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
