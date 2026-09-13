#!/usr/bin/env python3
"""`check_gh_artifact_names.py` 的**反向验证**(进 CI)。

判据只有一条:**每种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
没有验证过「能报错」的检查不算检查,它可能只是在永远通过。

## 为什么这条要特别验「反例」

它的判据是「**在 artifact 步骤内**看 `name:`」—— 而 `run-name:` 用 `github.ref_name` 是
**合法的**(那是运行显示名,斜杠没问题)。故必须有一条反例断言它**不**被报:
若判据写成「全文件搜 ref_name」,那条反例会红,而那种误报会让人把检查器关掉。

用法:  python3 scripts/check_gh_artifact_names_rev.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts/check_gh_artifact_names.py"

COPY_DIR = ".github/workflows"
DAILY = COPY_DIR + "/daily-build.yml"
STATIC = COPY_DIR + "/x-custom-static.yml"

GOOD_NAME = "name: r8-mapping-${{ steps.refslug.outputs.slug }}-${{ github.sha }}"


def reintroduce_ref_name(text: str) -> str:
    """把 artifact 名改回裸 `github.ref_name` —— 这正是 2026-09-13 踩到的那一处。"""
    assert GOOD_NAME in text, "找不到 r8-mapping 的 artifact 名(写法变了?)"
    return text.replace(
        GOOD_NAME, "name: r8-mapping-${{ github.ref_name }}-${{ github.sha }}", 1)


def use_github_ref(text: str) -> str:
    """换成 `github.ref`(`refs/heads/diag/consolidate`,**斜杠更多**)—— 必须报。"""
    assert GOOD_NAME in text, "找不到 r8-mapping 的 artifact 名"
    return text.replace(
        GOOD_NAME, "name: r8-mapping-${{ github.ref }}-${{ github.sha }}", 1)


def use_head_ref(text: str) -> str:
    """换成 `github.head_ref`(PR 源分支名,同样可能带斜杠)—— 必须报。"""
    assert GOOD_NAME in text, "找不到 r8-mapping 的 artifact 名"
    return text.replace(
        GOOD_NAME, "name: r8-mapping-${{ github.head_ref }}-${{ github.sha }}", 1)


MUTATIONS = [
    ("改回裸 github.ref_name(实测那一处)", DAILY, reintroduce_ref_name),
    ("换成 github.ref(斜杠更多)", DAILY, use_github_ref),
    ("换成 github.head_ref(PR 源分支)", DAILY, use_head_ref),
]

# 反例:**不该**被报的。`run-name:` 是运行显示名,用 ref_name 完全合法。
NEGATIVE_CASES = [
    ("run-name 里用 github.ref_name(合法)", STATIC, lambda t: t),
]


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="x-artifact-rev-") as tmp:
        root = Path(tmp)
        dest = root / COPY_DIR
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(SRC / COPY_DIR, dest)

        patched = (SRC / "scripts/check_gh_artifact_names.py").read_text(encoding="utf-8")
        patched = patched.replace("MIN_FILES = 5", "MIN_FILES = 3")
        checker = root / "chk.py"
        checker.write_text(patched, encoding="utf-8")

        def run() -> subprocess.CompletedProcess:
            return subprocess.run(
                [sys.executable, str(checker), "--root", str(root)],
                capture_output=True, text=True,
            )

        ok = True
        for name, rel, mutate in MUTATIONS:
            original = (SRC / rel).read_text(encoding="utf-8")
            after = mutate(original)
            assert after != original, f"{name}:变异没生效"
            (root / rel).write_text(after, encoding="utf-8")
            r = run()
            text_all = r.stdout + r.stderr
            out = text_all.strip().split("\n")
            detail = (out[1] if len(out) > 1 else out[0])[:95]
            if "无法进行" in text_all:
                print(f"  ⚠️  {name} → 检查器中止(不算验证通过):{detail}")
                ok = False
            elif r.returncode != 0:
                print(f"  ✅ {name} → 被报出:{detail}")
            else:
                print(f"  ❌ {name} → 仍然通过,检查器失效!")
                ok = False
            (root / rel).write_text(original, encoding="utf-8")

        print()
        for name, rel, mutate in NEGATIVE_CASES:
            original = (SRC / rel).read_text(encoding="utf-8")
            (root / rel).write_text(mutate(original), encoding="utf-8")
            r = run()
            if r.returncode == 0:
                print(f"  ✅ {name} → 未被报出(正确)")
            else:
                out = (r.stdout + r.stderr).strip().split("\n")
                print(f"  ❌ {name} → 被误报!{(out[1] if len(out) > 1 else out[0])[:85]}")
                ok = False
            (root / rel).write_text(original, encoding="utf-8")

        # 「永远通过」那道防线:扫描面失效必须**中止**
        print()
        broken = patched.replace('base = root / WORKFLOW_DIR', 'base = root / "nonexistent"')
        assert broken != patched, "没能改写扫描面"
        checker.write_text(broken, encoding="utf-8")
        r = run()
        if r.returncode != 0 and "无法进行" in (r.stdout + r.stderr):
            print("  ✅ 扫描面失效 → 检查器中止(而不是静默通过)")
        else:
            print("  ❌ 扫描面失效却仍然通过 —— 这就是「永远通过」的假检查")
            ok = False

        print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
        return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
