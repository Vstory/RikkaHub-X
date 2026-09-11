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

## 判据（2026-09-11 修正）

**上游路径下（不在 `x/` 包内），且满足以下**任一**条件的文件 = 承载 X 功能** → 必须登记：

| # | 条件 | 为什么算 |
|:--:|---|---|
| 1 | `import me.rerere.rikkahub.x.` | X 的行为挂在这里，冲掉即冲掉功能 |
| 2 | **头部带 `[X-custom]` 标记** | 同样是「上游没有、X 加的」行为 |

### ⚠️ 判据 2 是**补上来的**：原判据只有条件 1，漏了 19 个文件

原脚本只用 import 判定，并在注释里写「标记也用在**纯日志改动**上，没有功能后果」。
**2026-09-11 实测推翻了这个说法**：逐个查看未登记文件头部的标记内容，结果是
「eval 工具资源加固 / OCR 仅识别新增图片 / `model_id` 字段 / 工作区归档 / 备份前缀 /
MCP 显示名解耦 / 更新检查停用…」——**19 个全是实质定制，没有一个只是日志**。

后果与当初立这个脚本的原因**完全同类**：改这些文件时 Guard 不会跑，
「有回归网」实际是「以为有回归网」。

判据 2 的实测口径：取文件**前 5 行**内是否出现 `[X-custom]`（X 的约定是把它写在第一行）。

**仍存在的边界（诚实记录）**：既没有 import 也没有标记、却承载 X 功能的文件抓不到。
当前已核实的这类只有 `RouteActivity.kt`（X 的页面挂载点）——它**带标记**，故现在被条件 2 覆盖。

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

# 「承载 X 功能」的两条机械判据（满足任一即算）
X_IMPORT = re.compile(r"^import\s+me\.rerere\.rikkahub\.x\.", re.M)

# ⚠️ 只看**前 5 行**：X 的约定是把 `[X-custom]` 写在文件第一行。
# 全文搜会把「深在处理逻辑里的那行注释」也算进来，那是另一回事。
X_MARKER = re.compile(r"\[X-custom\]")
MARKER_HEAD_LINES = 5

# 上层路径下需要纳入守护的源码范围
SOURCE_GLOB = "app/src/main/java/**/*.kt"

PATH_ENTRY = re.compile(r"^\s+- '([^']+)'", re.M)


def registered_paths() -> set[str]:
    raw = pathlib.Path(GUARD_WORKFLOW).read_bytes().decode("utf-8")
    # 只取 on.push.paths 段，避免把其它地方的引号字符串也算进来
    block = raw.split("on:", 1)[1].split("workflow_dispatch", 1)[0]
    return set(PATH_ENTRY.findall(block))


def x_bearing_files() -> tuple[list[str], list[str]]:
    """上游路径下承载 X 功能的文件。返回 (全部, 仅靠标记判定的那些)。

    第二个返回值用于说明「判据 2 补了多少」——若条件 1 单独覆盖够了，这个列表会是空的。
    """
    found: list[str] = []
    marker_only: list[str] = []
    for path in glob.glob(SOURCE_GLOB, recursive=True):
        normalized = path.replace("\\", "/")
        if "/x/" in normalized:
            continue  # x/ 包已由通配覆盖
        try:
            text = pathlib.Path(normalized).read_text(encoding="utf-8")
        except OSError:
            continue
        imported = bool(X_IMPORT.search(text))
        marked = any(
            X_MARKER.search(line) for line in text.splitlines()[:MARKER_HEAD_LINES]
        )
        if imported:
            found.append(normalized)
        elif marked:
            found.append(normalized)
            marker_only.append(normalized)
    return sorted(found), sorted(marker_only)


def main() -> int:
    if not pathlib.Path(GUARD_WORKFLOW).exists():
        print(f"[错误] 找不到 {GUARD_WORKFLOW}", file=sys.stderr)
        return 1

    registered = registered_paths()
    if not registered:
        print("[错误] 守卫路径清单为空 —— 检查会全部通过，形同虚设", file=sys.stderr)
        return 1

    files, marker_only = x_bearing_files()
    if not files:
        print("[错误] 没有找到任何承载 X 接线的上游文件 —— 索引为空，检查形同虚设", file=sys.stderr)
        return 1

    missing = [f for f in files if f not in registered]

    print(
        f"守卫路径自检:上游路径下承载 X 功能的文件 {len(files)} 个"
        f"(其中 {len(marker_only)} 个靠 [X-custom] 标记判定),"
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
