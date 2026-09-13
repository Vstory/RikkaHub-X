#!/usr/bin/env python3
"""包名 ↔ 渠道的三方契约自检。

## 为什么要它(2026-09-13)

nightly 的独立包名由三处文本**共同**决定,而它们**分散在两个文件里**:

| 处 | 内容 |
|---|---|
| `app/build.gradle.kts` | `applicationId = "me.rerere.rikkahub.x"`(基名) |
| 同上 | `if (xChannel == "nightly") applicationIdSuffix = ".nightly"`(后缀) |
| `daily-build.yml` | `EXPECTED="me.rerere.rikkahub.x.nightly"` / `"me.rerere.rikkahub.x"`(断言期望) |

任一处被改坏,产物包名就与渠道不符 —— 而**构建日志里完全静默**(编译 / 签名 / 命名全过),
只在用户侧暴露:nightly 覆盖到正式版上,或正式版被带上 `.nightly` 而装不上更新。

⚠️ 更要紧的是:`app/build.gradle.kts` **不在任何 workflow 的 paths 过滤里** ——
改它不触发任何 CI(Compile Check 只管 `app/src/**`)。这正是仓库文档里那条
「只改它不会触发任何工作流,只能等下次构建才暴露」。故把这条契约前移到这里。

## 判据

1. `applicationId` 恰好声明一次(多处赋值会让「基名是什么」变得不确定);
2. 存在 `applicationIdSuffix = ".nightly"`,且它**近旁**有 `xChannel == "nightly"` 的条件
   —— 否则后缀会被无条件应用,**正式版也变成 `.nightly`**;
3. `daily-build.yml` 里的两个期望包名 = {基名, 基名+后缀}。三方必须完全对上。

## 索引下限

四个值(基名 / 后缀 / 两个期望)缺任何一个 → **报错退出**,而不是「通过」。

## 用法

    python3 scripts/check_x_package_names.py [--root <仓库根>]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

GRADLE = "app/build.gradle.kts"
CI = ".github/workflows/daily-build.yml"

APP_ID_RE = re.compile(r'^\s*applicationId\s*=\s*"([^"]+)"', re.M)
SUFFIX_RE = re.compile(r'^\s*applicationIdSuffix\s*=\s*"([^"]+)"', re.M)
# ⚠️ 只认**包名**那种期望值。CI 里另有一处 `EXPECTED="$(printf '%s' "$EXPECTED_SHA256" …)"`
#    —— 那是**证书指纹**,不是包名。用两条特征把它排除掉:含 `$`(是命令替换)、不含 `.`
#    (指纹是十六进制,没有点)。不加这两条就会把指纹算进契约,报出**误报**。
#    (这条是首版实测撞出来的:一跑就报了「CI 断言」里多出一个 `$(printf …)`。)
CI_EXPECTED_RE = re.compile(r'EXPECTED="([^"$]+)"')
PACKAGE_LIKE = "."  # 包名必有点;指纹没有

NIGHTLY_SUFFIX = ".nightly"
NIGHTLY_GUARD = 'xChannel == "nightly"'

# 条件允许写在同一行的上方若干行内(留出注释的余量)。
GUARD_WINDOW = 8


class Problem(Exception):
    """解析失败 —— 与「发现问题」区分开(见 main)。"""


def check(root: Path) -> list:
    problems = []

    gradle_path, ci_path = root / GRADLE, root / CI
    for p in (gradle_path, ci_path):
        if not p.is_file():
            raise Problem(f"找不到 {p.relative_to(root)}")
    gradle = gradle_path.read_text(encoding="utf-8")
    ci = ci_path.read_text(encoding="utf-8")

    ids = APP_ID_RE.findall(gradle)
    if len(ids) != 1:
        raise Problem(f"{GRADLE} 里 applicationId 声明了 {len(ids)} 次(期望恰好 1 次)")
    base = ids[0]

    suffixes = SUFFIX_RE.findall(gradle)
    if NIGHTLY_SUFFIX not in suffixes:
        # ⚠️ 这是**发现问题**,不是**解析失败** —— 后缀被改名/删掉是完全正常的改法,
        #    该报出来让人看到后果,而不是让检查器「中止」(中止会被当成环境问题)。
        #    (首版写成了 raise Problem,于是反向验证里那条变异**没被算作报出**。)
        problems.append(
            f"{GRADLE}: 找不到 `applicationIdSuffix = \"{NIGHTLY_SUFFIX}\"` —— "
            f"nightly 就没有独立包名了(它会与正式版同包名、互相覆盖)。"
        )
        return problems  # 后面两条判据都依赖这个后缀

    # ── 判据 2:后缀必须在 nightly 条件里 ──
    lines = gradle.split("\n")
    hit = [i for i, l in enumerate(lines)
           if "applicationIdSuffix" in l and NIGHTLY_SUFFIX in l]
    if len(hit) != 1:
        problems.append(
            f"{GRADLE}: 带 `{NIGHTLY_SUFFIX}` 的后缀声明有 {len(hit)} 处(期望 1 处)"
        )
        return problems
    idx = hit[0]
    window = "\n".join(lines[max(0, idx - GUARD_WINDOW):idx])
    if NIGHTLY_GUARD not in window:
        problems.append(
            f"{GRADLE}:{idx + 1}: `{NIGHTLY_SUFFIX}` 后缀前面 {GUARD_WINDOW} 行内没有 "
            f"`{NIGHTLY_GUARD}` 的条件 —— 那它会被**无条件**应用,**正式版也会变成 "
            f"`{base}{NIGHTLY_SUFFIX}`**(正式用户将装不上更新)。"
        )

    # ── 判据 3:与 CI 的期望对上 ──
    expected = {v for v in CI_EXPECTED_RE.findall(ci) if PACKAGE_LIKE in v}
    if len(expected) != 2:
        raise Problem(
            f"{CI} 里找到 {len(expected)} 个「像包名的 EXPECTED」(期望恰好 2 个:nightly 与正式版)"
            f" —— 找不到或多了,都说明这条契约的锚点变了,这时**必须报错**。"
        )
    derived = {base, base + NIGHTLY_SUFFIX}
    if derived != expected:
        problems.append(
            f"包名契约对不上:{GRADLE} 推出 {sorted(derived)},而 {CI} 断言 {sorted(expected)} "
            f"—— 产物包名会与渠道不符(nightly 覆盖正式版,或正式版装不上更新),"
            f"而构建日志里没有任何异常。"
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
        print(f"[FAIL] 包名契约自检无法进行:{exc}")
        return 1

    if problems:
        print(f"[FAIL] 包名契约自检发现 {len(problems)} 处:")
        for p in problems:
            print(f"  - {p}")
        return 1

    print("[CHECK PASS] 包名契约一致:基名 / .nightly 后缀(带条件) / CI 期望 三方对上")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
