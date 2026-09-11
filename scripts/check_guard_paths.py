#!/usr/bin/env python3
"""守卫路径自检：承载 X 接线的上游文件，是否都登记进了 `x-custom-guard.yml`。

## 为什么需要它（2026-09-11 实测漏洞）

`X Custom Guard` 是**唯一**会编译并运行全部单测的工作流，而它带 `paths` 过滤 ——
只有改动落在清单里才会触发。清单是**逐个文件手写**的，于是出现一类静默失效：

    给 `FilesManager.kt` 加了内容寻址写入路径（X 的核心行为），
    但这个文件不在清单里 → 推送后 **Guard 根本没跑**，一份测试都没验过。
    CI 面板上 `Compile Check` 是绿的，看起来「CI 过了」。

实测就是如此：接线的两个提交（写入路径、备份目录）**全部跳过了守护测试**。
这类问题不会报错、不会失败，只会让「有回归网」变成「以为有回归网」。

## 判据

**上游路径下（不在 `x/` 包内）、且 import 了 `me.rerere.rikkahub.x.` 的文件 = 承载 X 接线**
→ 必须登记进 Guard 的 `paths`。

为什么用 import 而不是 `[X-custom]` 标记：标记也被用在**纯日志改动**上（X 把某行的
log 换成 XLog）。那种改动被上游冲掉只是少一行日志，没有功能后果，Compile Check 足以把关；
而 import 了 x 包的文件，意味着「X 的行为挂在这里」—— 冲掉它就冲掉了功能。

**规则的边界（诚实记录）**：它抓不到「没 import x 却承载 X 功能」的文件，例如
`RouteActivity.kt`（X 的页面挂载点，`Screen` 定义在它自己文件里）。这类只能靠人补，
已在清单里逐个注明。

## 用法

    python3 scripts/check_guard_paths.py

退出码：0 = 全部登记，1 = 有漏登。
"""

from __future__ import annotations

import glob
import pathlib
import re
import sys

GUARD_WORKFLOW = ".github/workflows/x-custom-guard.yml"

# 「承载 X 接线」的机械判据
X_IMPORT = re.compile(r"^import\s+me\.rerere\.rikkahub\.x\.", re.M)

# 上层路径下需要纳入守护的源码范围
SOURCE_GLOB = "app/src/main/java/**/*.kt"

PATH_ENTRY = re.compile(r"^\s+- '([^']+)'", re.M)


def registered_paths() -> set[str]:
    raw = pathlib.Path(GUARD_WORKFLOW).read_bytes().decode("utf-8")
    # 只取 on.push.paths 段，避免把其它地方的引号字符串也算进来
    block = raw.split("on:", 1)[1].split("workflow_dispatch", 1)[0]
    return set(PATH_ENTRY.findall(block))


def x_bearing_files() -> list[str]:
    """上游路径下、引用了 X 包的文件。"""
    found: list[str] = []
    for path in glob.glob(SOURCE_GLOB, recursive=True):
        normalized = path.replace("\\", "/")
        if "/x/" in normalized:
            continue  # x/ 包已由通配覆盖
        try:
            text = pathlib.Path(normalized).read_text(encoding="utf-8")
        except OSError:
            continue
        if X_IMPORT.search(text):
            found.append(normalized)
    return sorted(found)


def main() -> int:
    if not pathlib.Path(GUARD_WORKFLOW).exists():
        print(f"[错误] 找不到 {GUARD_WORKFLOW}", file=sys.stderr)
        return 1

    registered = registered_paths()
    if not registered:
        print("[错误] 守卫路径清单为空 —— 检查会全部通过，形同虚设", file=sys.stderr)
        return 1

    files = x_bearing_files()
    if not files:
        print("[错误] 没有找到任何承载 X 接线的上游文件 —— 索引为空，检查形同虚设", file=sys.stderr)
        return 1

    missing = [f for f in files if f not in registered]

    print(
        f"守卫路径自检:上游路径下承载 X 接线的文件 {len(files)} 个,"
        f"清单已登记 {len(registered)} 条,漏登 {len(missing)} 个"
    )
    if not missing:
        print("✅ 无问题")
        return 0

    print()
    for path in missing:
        print(f"❌ {path}")
    print()
    print("这些文件承载 X 的行为,却不在守卫清单里 → 改它们不会触发 Guard,")
    print("等于这些定制没有回归网(CI 上 Compile Check 仍是绿的,不会提示)。")
    print(f"请登记到 {GUARD_WORKFLOW} 的 on.push.paths。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
