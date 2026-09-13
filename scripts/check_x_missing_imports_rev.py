#!/usr/bin/env python3
"""`check_x_missing_imports.py` 的**反向验证**(进 CI —— 见下)。

判据只有一条:**每种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
没有验证过「能报错」的检查不算检查,它可能只是在永远通过。

## 为什么这条特别需要验

它的判据**收窄过两轮**(全仓 → 只守 X 自己的包),而收窄最容易把判据收死:
一不小心就变成「永远通过」。故这里用**真实发生过的漏法**做变异 ——
不是编出来的形状,是本次会话里那两次:

① `RikkaHubApp.kt` 里加了 `XSurvivorLog.install(this)` 却**漏了 import**;
② 同一个文件里加了 `XExitReport.install(this)` 又漏了。

外加一条反例:上游包之间的同名(`Result` / `Idle` 那类简单名撞车)**不该**被报 ——
那正是收窄的理由,若不验,收窄就会被后来人「顺手放宽」而误报重生。

用法:  python3 scripts/check_x_missing_imports_rev.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts/check_x_missing_imports.py"

APP = "app/src/main/java/me/rerere/rikkahub"
APP_FILE = APP + "/RikkaHubApp.kt"
EXPECTED_IMPORT = "import me.rerere.rikkahub.x.diag.XSurvivorLog\n"

# ⚠️ **必须拷全部模块的源码根**,不能只拷 mutation 目标所在的那两个:
#    检查器有「模块源码根 ≥ 10」的下限,只拷两个会让它**直接中止** —— 于是每条变异都
#    「被判为报出」,而其实它一条都没真报(实测就是这么假绿了一轮)。
#    而且「包存在」那条判据要靠全模块的包集合:少拷一个模块,那个模块的 import 全成误报。
COPY_DIRS = (
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


def drop_import(text: str) -> str:
    """删掉 XSurvivorLog 的 import —— 这正是本次会话真实犯过的那次。"""
    assert EXPECTED_IMPORT in text, "找不到 XSurvivorLog 的 import"
    return text.replace(EXPECTED_IMPORT, "", 1)


def drop_import_and_alias(text: str) -> str:
    """删 import 但留下调用 —— 与上一条等价,换成 `XExitReport`(第二次真实犯的)。"""
    needle = "import me.rerere.rikkahub.x.diag.XExitReport\n"
    assert needle in text, "找不到 XExitReport 的 import"
    return text.replace(needle, "", 1)


def shorten_import_to_wrong_package(text: str) -> str:
    """把 import 指到一个**不存在**的包 —— 简单名对不上,应被报出。"""
    needle = "import me.rerere.rikkahub.x.diag.XLogcatCapture\n"
    assert needle in text, "找不到 XLogcatCapture 的 import"
    return text.replace(needle, "import me.rerere.rikkahub.x.other.XLogcatCapture\n", 1)


MUTATIONS = [
    ("漏 import(XSurvivorLog,真实犯过)", APP_FILE, drop_import),
    ("漏 import(XExitReport,真实犯过)", APP_FILE, drop_import_and_alias),
    ("import 指到不存在的包", APP_FILE, shorten_import_to_wrong_package),
]


def run_checker(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(CHECKER), "--root", str(root)],
        capture_output=True, text=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="x-imports-rev-") as tmp:
        root = Path(tmp)
        for rel in COPY_DIRS:
            dest = root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(SRC / rel, dest)

        ok = True
        for name, rel, mutate in MUTATIONS:
            original = (SRC / rel).read_text(encoding="utf-8")
            try:
                after = mutate(original)
                assert after != original, "变异没有生效"
                (root / rel).write_text(after, encoding="utf-8")
            except AssertionError as exc:
                print(f"  ⚠️  {name} → 变异失败,未验证:{exc}")
                ok = False
                continue

            r = run_checker(root)
            text_all = r.stdout + r.stderr
            out = text_all.strip().split("\n")
            detail = (out[1] if len(out) > 1 else out[0])[:92]
            # ⚠️ 「中止」不算「报出」:中止说明检查器压根没跑完判据(例如扫描面写错),
            #    那时把它记成通过就是**假绿**(实测踩过)。故单独区分。
            if "无法进行" in text_all:
                print(f"  ⚠️  {name} → 检查器中止(不算验证通过):{detail}")
                ok = False
            elif r.returncode != 0:
                print(f"  ✅ {name} → 被报出:{detail}")
            else:
                print(f"  ❌ {name} → 仍然通过,检查器失效!")
                ok = False
            (root / rel).write_text(original, encoding="utf-8")

        # ── 反例:收窄的理由必须仍然成立 ──
        # 在上游包里放一个与标准库同名(`Result`)的类用法 —— **不该**被报。
        # 若不验这条,后来人可能「顺手放宽」判据,于是误报重生(那正是收窄要避免的)。
        print()
        probe_rel = APP + "/data/export/ExportHooks.kt"
        probe = root / probe_rel
        original = (SRC / probe_rel).read_text(encoding="utf-8")
        # 锚点用文件头那行 `package …` —— 它**一定存在**,不会像首版那样拿一个
        # 写错的声明去锚、于是这条反例**静默跳过**(打印「未生效,跳过」)。
        # 「验证跑了但没验到」是最难发现的一种假绿。
        injected = original.replace(
            "package me.rerere.rikkahub.data.export\n",
            "package me.rerere.rikkahub.data.export\n\n"
            "private val probe: Result<String>? = null\n",
            1,
        )
        assert injected != original, "反例注入没生效"
        probe.write_text(injected, encoding="utf-8")
        r2 = run_checker(root)
        if r2.returncode == 0:
            print("  ✅ 上游包的同名类(kotlin 的 Result,未 import)→ 未被报出(收窄仍然有效)")
        else:
            out = (r2.stdout + r2.stderr).strip().split("\n")
            print(f"  ❌ 误报重生:{(out[1] if len(out) > 1 else out[0])[:80]}")
            ok = False
        probe.write_text(original, encoding="utf-8")

        # ── 「永远通过」那道防线:扫描面失效必须中止 ──
        print()
        patched = (SRC / "scripts/check_x_missing_imports.py").read_text(encoding="utf-8")
        broken = patched.replace(
            'base = root / rel', 'base = root / ("nonexistent-" + rel)', 1,
        )
        assert broken != patched, "没能改写扫描面"
        checker = Path(tmp) / "chk.py"
        checker.write_text(broken, encoding="utf-8")
        r = subprocess.run(
            [sys.executable, str(checker), "--root", str(root)],
            capture_output=True, text=True,
        )
        if r.returncode != 0 and "无法进行" in (r.stdout + r.stderr):
            print("  ✅ 扫描面失效 → 检查器中止(而不是静默通过)")
        else:
            print("  ❌ 扫描面失效却仍然通过 —— 这就是「永远通过」的假检查")
            ok = False

        print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
        return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
