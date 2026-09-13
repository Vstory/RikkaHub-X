#!/usr/bin/env python3
"""artifact 名字里不许出现「带斜杠的上下文值」自检。

## 为什么要它(2026-09-13)

实测踩到,而且代价不小 —— **整个 nightly 构建在最后一步失败,连带「发布 release」没执行**,
于是拿不到 APK:

    ##[error]The artifact name is not valid:
      r8-mapping-diag/consolidate-bdd72442…
    Contains the following character: Forward slash /

根因:artifact 名里拼了 `${{ github.ref_name }}`,而特性分支名是 `diag/consolidate` —— 带斜杠。
GitHub 为跨文件系统兼容(如 NTFS)禁掉了 `/ \\ : * ? " < > |`。

⚠️ 它**坏了整整一天而看板上全绿**:那一步是 `692f4c7b` 加的,而 **nightly 只能手动触发**,
之后一直没人跑过 Daily Build。又一次印证:**看板绿 ≠ 真的验过**(带 paths 白名单、或只能
手动触发的工作流,都可能让某一步长期不执行)。

## 判据(只认 artifact 步骤,故零误报)

`run-name:` 用 `github.ref_name` 是**允许的**(那是运行显示名,斜杠没问题)—— 故必须
**只在 artifact 步骤内**判。做法:逐行找出 `uses: actions/(upload|download)-artifact@…`,
然后按缩进判断该步骤的范围内有没有 `name:` 用了 `github.ref_name` / `github.ref` / `github.head_ref`。

⚠️ 三种上下文都可能带斜杠:
· `github.ref_name` → `diag/consolidate`
· `github.ref`      → `refs/heads/diag/consolidate`
· `github.head_ref` → PR 源分支名(同样可能带斜杠)

## 索引下限

扫到的 workflow 文件 < 5、或 artifact 步骤 < 1 → **报错退出**。
(这个仓库里 artifact 步骤只有两处,故下限取 1;而**一条都没有**说明扫描坏了。)

## 用法

    python3 scripts/check_gh_artifact_names.py [--root <仓库根>]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

WORKFLOW_DIR = ".github/workflows"

MIN_FILES = 5
MIN_ARTIFACT_STEPS = 1

ARTIFACT_USE_RE = re.compile(r"uses:\s*actions/(?:upload|download)-artifact@")

# 可能带斜杠的上下文值。用 `github.X` / `env.X` 之外的形式也一样判 —— 这里只认这几种,
# 因为别的写法(自定义 output)没法判它到底有没有斜杠。
SLASHY = ("github.ref_name", "github.ref", "github.head_ref")

NAME_RE = re.compile(r"^\s*(?:-\s*)?name:")


class Problem(Exception):
    """会让检查直接中止的解析失败 —— 与「发现问题」区分开(见 main)。"""


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip())


def check(root: Path) -> list:
    base = root / WORKFLOW_DIR
    if not base.is_dir():
        raise Problem(f"找不到 {WORKFLOW_DIR}")
    files = sorted(list(base.glob("*.yml")) + list(base.glob("*.yaml")))
    if len(files) < MIN_FILES:
        raise Problem(f"只扫到 {len(files)} 个 workflow(期望 ≥ {MIN_FILES})—— 扫描面写错了。")

    problems = []
    artifact_steps = 0
    for path in files:
        lines = path.read_text(encoding="utf-8").split("\n")
        rel = path.relative_to(root)
        for i, line in enumerate(lines):
            if not ARTIFACT_USE_RE.search(line):
                continue
            artifact_steps += 1
            step_indent = indent_of(line)
            # 该步骤的范围。
            #
            # ⚠️ 首版写成「缩进 <= 本步就结束」,**当场失效**:`uses:` 之后第一行通常是
            #    `with:`,它与 `uses:` **同级**(YAML 里同一映射的兄弟键)—— 于是循环第一次
            #    迭代就 break,`name:` 永远扫不到,判据在 mutated 代码上也照样「通过」。
            #    (这条漏洞是反向验证逼出来的:三条变异一个都没被报出。)
            #
            # 正确的界限:同级或更深**继续**,直到遇见
            #   · 更外层的行(缩进 < 本步),或
            #   · **下一个同级列表项**(缩进相同且以 `- ` 开头,那就是下一步了)。
            for j in range(i + 1, len(lines)):
                following = lines[j]
                if not following.strip():
                    continue  # 空行不断步骤
                ind = indent_of(following)
                if ind < step_indent or (ind == step_indent and following.lstrip().startswith("-")):
                    break
                if not NAME_RE.match(following):
                    continue
                for ctx in SLASHY:
                    if ctx in following:
                        problems.append(
                            f"{rel}:{j + 1}: artifact 步骤的 `name:` 用了 `{ctx}` —— "
                            f"分支名带斜杠时(GitHub 禁 `/ \\ : * ? \" < > |`)整步会失败,"
                            f"并**连带让后面的步骤不执行**(实测:发布 release 因此没跑)。"
                            f"用一个先用 `tr '/' '-'` 洗过的 output。"
                        )
                        break

    if artifact_steps < MIN_ARTIFACT_STEPS:
        raise Problem(
            f"一个 artifact 步骤都没找到(期望 ≥ {MIN_ARTIFACT_STEPS})—— "
            f"式样可能已与实际写法不符,这时**必须报错**,否则扫到 0 条也会「通过」。"
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
        print(f"[FAIL] artifact 名字自检无法进行:{exc}")
        return 1

    if problems:
        print(f"[FAIL] artifact 名字自检发现 {len(problems)} 处:")
        for p in problems:
            print(f"  - {p}")
        return 1

    print("[CHECK PASS] artifact 名字里没有「带斜杠的上下文值」")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
