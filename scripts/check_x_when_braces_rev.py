#!/usr/bin/env python3
"""`check_x_when_braces.py` 的**反向验证**(进 CI)。

判据只有一条:**每种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
没有验证过「能报错」的检查不算检查,它可能只是在永远通过。

## 为什么这条特别需要「反例」

它的判据**刻意收窄**(只报一种形状),而收窄最容易把判据收死 ——
一不小心就变成「永远通过」。故这里除了三种破坏,还有**两条反例**:
· 同行就有表达式(`-> expr()`)—— 合法,不该报;
· 带花括号的分支体里放 `val` —— 合法,不该报(那是**正确**的多语句写法)。

后者尤其重要:若判据写成「分支体里有 val 就报」,它会**把正确写法一并报掉**,
而那种误报会让人把检查器关掉。

用法:  python3 scripts/check_x_when_braces_rev.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts/check_x_when_braces.py"

COPY_DIRS = ("app/src/main", "app/src/test")

# 真实发生过那一处的文件(用它做靶子,而不是编一个)
PAGE = "app/src/main/java/me/rerere/rikkahub/ui/pages/diagnostic/DiagnosticPage.kt"
LOGCAT = "app/src/main/java/me/rerere/rikkahub/x/diag/XLogcatCapture.kt"


def drop_braces(text: str) -> str:
    """把 SessionZip 分支的花括号去掉、并在体里留一个 `val` —— **这正是实测踩到的那一处**。"""
    old = """        is PendingExport.SessionZip -> {
            // 快照**已经在入口处抓好并挂在这个载荷上了**(见导出卡片那段注释),
            // 这里只管写、不再自己抓一次:再抓一次会得到**第二份**、而且时机晚于
            // 用户选保存位置 —— 「导出那一刻」就不再是同一个时刻了。
            // 时间跳变那句话挂在**头部行**里(而不是新加一个参数):头部行本来就是
            // 「关于这次记录的元信息」,而跳变正是这样一种信息 —— 且这样 XDiagZip 仍是
            // 纯逻辑(它不碰 XDiagnostics)。
            val header = XDiagEnv.appLines(context) + listOfNotNull(XDiagnostics.clockNote())
            XDiagZip.write(header, out, payload.dir, progress, payload.extra)
        }"""
    new = """        is PendingExport.SessionZip ->
            val header = XDiagEnv.appLines(context) + listOfNotNull(XDiagnostics.clockNote())
            XDiagZip.write(header, out, payload.dir, progress, payload.extra)"""
    assert old in text, "找不到 SessionZip 分支(写法变了?)"
    return text.replace(old, new, 1)


def braced_with_indented_val(text: str) -> str:
    """反例:分支体**带花括号**、里面有 `val` —— 正确写法,不该报。"""
    old = """        is PendingExport.SessionZip -> {"""
    new = """        is PendingExport.SessionZip -> {
            val harmless = 1"""
    assert old in text, "找不到 SessionZip 分支"
    return text.replace(old, new, 1)


def arrow_eol_single_expression(text: str) -> str:
    """反例:箭头在行尾但体是**单表达式** —— 合法,不该报。

    用 `XLogcatCapture` 里那条真实的 `else ->`(它的体就是一串字符串)当靶子。
    """
    # 原样返回 = 不做修改,而 `main` 会断言「变异真的改了东西」——故这里显式改一处
    # **与判据无关**的等价改写(加一个空格),用来验证「这条反例没有触发判据」。
    marker = "                else ->"
    assert marker in text, "找不到 else -> (写法变了?)"
    return text.replace(marker, "                else  ->", 1)


MUTATIONS = [
    ("去掉分支花括号、体里留 val(实测那一处)", PAGE, drop_braces),
]

NEGATIVE_CASES = [
    ("分支带花括号、体里有 val(正确写法)", PAGE, braced_with_indented_val),
    ("箭头在行尾但体是单表达式(合法)", LOGCAT, arrow_eol_single_expression),
]


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="x-when-rev-") as tmp:
        root = Path(tmp)
        for rel in COPY_DIRS:
            dest = root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(SRC / rel, dest)

        # 下限是按整仓定的(不扫 300 个 .kt 就会中止);副本里文件数是够的(整个 src/main)。
        # 若不够就调小 —— 那会让检查器中止,而「中止」不算验证通过(见下)。
        patched = (SRC / "scripts/check_x_when_braces.py").read_text(encoding="utf-8")
        patched = patched.replace("MIN_FILES = 300", "MIN_FILES = 100")
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
            # ⚠️ 「中止」不算「报出」:中止说明检查器没跑完判据,记成通过就是假绿。
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
        broken = patched.replace('base = root / rel', 'base = root / ("nonexistent-" + rel)', 1)
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
