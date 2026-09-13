#!/usr/bin/env python3
"""`check_x_diag_completeness.py` 的**反向验证**(进 CI)。

判据:**每条判据的破坏都必须被报出来;未改动必须通过;扫描面失效必须中止。**

用真实文件(而不是手搓桩)做变异 —— 四条判据分别落在:
· 判据 1(地基)      → `RikkaHubApp.kt` 的接线
· 判据 2(漏斗接全)   → `RouteActivity.kt` 的 `rememberToasterState(onToastDismissed = …)`
· 判据 3(原生 Toast) → `SettingPage.kt` 的标注 / `ChatService.kt` 的记录
· 判据 4(静默棘轮)   → 任一处 `runCatching { }`

⚠️ 两条实测踩过的坑,写在这里免得重犯:
· 「中止」**不算**「报出」—— 中止说明判据没跑完,记成通过就是假绿;
· 破坏要改**真的会影响结果**的东西(本会话有过「把定义和使用一起改名 = 等于没破坏」)。

用法:  python3 scripts/check_x_diag_completeness_rev.py
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts/check_x_diag_completeness.py"

J = "app/src/main/java/me/rerere/rikkahub/"
COPY = (
    J + "RikkaHubApp.kt",
    J + "RouteActivity.kt",
    J + "service/ChatService.kt",
    J + "ui/pages/setting/SettingPage.kt",
)


def replace_once(text: str, old: str, new: str) -> str:
    assert old in text, f"找不到锚点 `{old[:60]}`"
    out = text.replace(old, new, 1)
    assert out != text, "变异没有生效"
    return out


# (名字, 目标文件, 变异, 期望)
MUTATIONS = [
    ("地基少一处接线(XSurvivorLog.install)",
     J + "RikkaHubApp.kt",
     lambda t: replace_once(t, "        XSurvivorLog.install(this)\n", ""),
     "fail"),
    ("toaster 丢掉 onToastDismissed(等于开旁路)",
     J + "RouteActivity.kt",
     lambda t: replace_once(
         t, "rememberToasterState(onToastDismissed = { XUserVisibleErrors.recordDismissedToast(it) })",
         "rememberToasterState()"),
     "fail"),
    ("原生 Toast 的记录被拿掉且不留声明",
     J + "service/ChatService.kt",
     lambda t: replace_once(
         t, """        if (!success) {
            XUserVisibleErrors.recordShown(message = message)
        }
""", ""),
     "fail"),
    ("原生 Toast 的「不是错误」声明被拿掉",
     J + "ui/pages/setting/SettingPage.kt",
     lambda t: replace_once(t, "// x-diag-toast-ok: 成功提示,不是问题\n", ""),
     "fail"),
    ("新增一处静默吞掉(超基线)",
     J + "RikkaHubApp.kt",
     lambda t: replace_once(
         t, "class RikkaHubApp",
         "private fun xDiagRevProbe() {\n    runCatching { 1 + 1 }\n}\n\nclass RikkaHubApp"),
     "fail"),
]


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="x-diagc-rev-") as tmp:
        root = Path(tmp)
        for rel in COPY:
            dest = root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(SRC / rel, dest)

        patched = (SRC / "scripts/check_x_diag_completeness.py").read_text(encoding="utf-8")
        # 扫描面放宽到「够用」—— 反向验证只关心判据本身,不需要 400 个文件
        patched = patched.replace("MIN_KT_FILES = 400", "MIN_KT_FILES = 2")

        def run(checker_text: str):
            c = root / "chk.py"
            c.write_text(checker_text, encoding="utf-8")
            return subprocess.run([sys.executable, str(c), "--root", str(root)],
                                  capture_output=True, text=True)

        # 先用未改动状态跑一遍:应当 PASS,并读出静默点计数 → 作为棘轮基线的「当前值」
        base = run(patched)
        text = base.stdout + base.stderr
        m = re.search(r"静默吞掉 (\d+) 处", text)
        if m is None:
            print(f"  ❌ 读不到静默点计数 —— 检查器输出变了?{text.strip()[:120]}")
            return 1
        cur = int(m.group(1))
        if base.returncode != 0:
            print(f"  ❌ 未改动就没通过(应为 PASS):{text.strip()[:160]}")
            return 1
        print(f"  ✅ 未改动 → 通过(静默点 {cur} 处,作为本轮棘轮基线)")

        # 把基线调到刚测到的值:于是「新增一处」必然超线
        patched = patched.replace(
            re.search(r"SWALLOW_BASELINE = \d+", patched).group(0),
            f"SWALLOW_BASELINE = {cur}")

        ok = True
        for name, rel, mutate, expect in MUTATIONS:
            target = root / rel
            original = (SRC / rel).read_text(encoding="utf-8")
            try:
                after = mutate(original)
                target.write_text(after, encoding="utf-8")
            except AssertionError as exc:
                print(f"  ⚠️  {name} → 变异失败,未验证:{exc}")
                ok = False
                continue

            r = run(patched)
            alltext = r.stdout + r.stderr
            # 报告要取**问题行**,不是那行 INFO(首版取到了 INFO,读起来像「没报问题」)
            problem_lines = [l for l in alltext.split("\n")
                             if l.strip().startswith("- ") and "静默吞掉" not in l]
            if not problem_lines:
                problem_lines = [l for l in alltext.split("\n") if l.strip().startswith("- ")]
            first = (problem_lines[0].strip() if problem_lines
                     else alltext.strip().split("\n")[0])[:110]
            if "无法进行" in alltext:
                print(f"  ⚠️  {name} → 检查器中止(不算报出):{first}")
                ok = False
            elif r.returncode != 0:
                print(f"  ✅ {name} → 被报出:{first}")
            else:
                print(f"  ❌ {name} → 仍然通过,判据失效!")
                ok = False
            target.write_text(original, encoding="utf-8")

        # 扫描面失效必须中止(否则判据 2 会「永远通过」)
        print()
        broken = patched.replace('TOASTER_CALL = "rememberToasterState("',
                                'TOASTER_CALL = "neverAppears("')
        assert broken != patched, "没能改写扫描面"
        r = run(broken)
        if r.returncode != 0 and "无法进行" in (r.stdout + r.stderr):
            print("  ✅ 扫描面失效 → 检查器中止(而不是静默通过)")
        else:
            print("  ❌ 扫描面失效却仍然通过 —— 这就是「永远通过」的假检查")
            ok = False

        print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
        return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
