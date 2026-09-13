#!/usr/bin/env python3
"""`check_x_test_assert_types.py` 的**反向验证**(进 CI)。

判据只有一条:**每种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
没有验证过「能报错」的检查不算检查,它可能只是在永远通过。

## 为什么这条检查器值得配反向验证

它是为一次**真实付过代价的失败**补的(2026-09-13:`assertEquals(1L, map.size)`
在 CI 的 Guard 上红了,而编译通过、本地检查全绿)。

⚠️ 而它的**第二类判据**(字面量 对 源码里声明为 Long 的属性)又差点漏掉一处真错:
写时间跳变测试时我写了 `assertEquals(-60_010, jump.driftMs)` —— 字面量是 Int、
`driftMs` 是 Long,又一次运行时才炸。**负号尤其容易漏 L**,而首版的字面量正则
压根不认负号。故这里把那一处也做成变异。

⚠️ 另**必须**拷 `x/diag` 的 **main** 源码:第二类判据要从源码推导「哪些属性是 Long」,
临时目录里没有 main 源码 → 检查器会**中止**(而不是通过)→ 每条变异都会被误记成
「已报出」。故本脚本区分「中止」与「报出」,并把下限调小以适配副本。`

用法:  python3 scripts/check_x_test_assert_types_rev.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts/check_x_test_assert_types.py"

TEST_DIR = "app/src/test/java"
MAIN_DIR = "app/src/main/java"
DIAG_TEST = TEST_DIR + "/me/rerere/rikkahub/x/diag"
DIAG_MAIN = MAIN_DIR + "/me/rerere/rikkahub/x/diag"

COPY_DIRS = (DIAG_TEST, DIAG_MAIN)

# 靶子文件
EVENTS = DIAG_TEST + "/XEventIndexTest.kt"
CLOCK = DIAG_TEST + "/XClockWatchTest.kt"


# ────────────────────────────────────
# 变异(每个都断言真的改动了内容,否则不算验证)
# ────────────────────────────────────

def long_vs_size(text: str) -> str:
    """`assertEquals(1, x.size)` → `1L` —— 真实踩到的那一处。"""
    old = 'assertEquals(1, result.getValue("core").size)'
    new = 'assertEquals(1L, result.getValue("core").size)'
    assert old in text, "找不到靶子断言"
    return text.replace(old, new, 1)


def long_vs_size_reversed(text: str) -> str:
    """把 Long 字面量放到**右边** —— 两个方向都要挡(否则换边就漏)。"""
    old = 'assertEquals(1, result.getValue("core").size)'
    new = 'assertEquals(result.getValue("core").size, 1L)'
    assert old in text, "找不到靶子断言"
    return text.replace(old, new, 1)


def long_vs_count(text: str) -> str:
    """`.count()` 也是 Int —— 同样要挡。"""
    old = 'assertEquals(2L, result.getValue("chat").getValue("chat.message.sent"))'
    new = 'assertEquals(1L, result.getValue("chat").values.count())'
    assert old in text, "找不到靶子断言"
    return text.replace(old, new, 1)


def int_vs_declared_long(text: str) -> str:
    """裸整数对**源码里声明为 Long** 的属性(`.lines`) —— 反方向必须挡。"""
    old = 'assertEquals(1, result.getValue("core").size)'
    new = 'assertEquals(1, session.lines)'
    assert old in text, "找不到靶子断言"
    return text.replace(old, new, 1)


def drop_long_suffix(text: str) -> str:
    """去掉 Long 属性断言里的 `L` —— 本次差点犯下的那一处。"""
    old = "assertEquals(3_599_990L, jump!!.driftMs)"
    new = "assertEquals(3_599_990, jump!!.driftMs)"
    assert old in text, "找不到 driftMs 的断言"
    return text.replace(old, new, 1)


def drop_long_suffix_negative(text: str) -> str:
    """**负数**字面量去掉 L —— 负号是最容易漏 L 的地方(首版正则不认负号)。"""
    old = 'assertEquals("漂移应为负", -60_010L, jump!!.driftMs)'
    new = 'assertEquals("漂移应为负", -60_010, jump!!.driftMs)'
    assert old in text, "找不到负漂移的断言"
    return text.replace(old, new, 1)


# (名字, 文件, 变异)
MUTATIONS = [
    ("Long 字面量对 .size(真实踩到的那一处)", EVENTS, long_vs_size),
    ("同一处但 Long 在右边", EVENTS, long_vs_size_reversed),
    ("Long 字面量对 .count()", EVENTS, long_vs_count),
    ("裸整数对 .lines(反方向)", EVENTS, int_vs_declared_long),
    ("去掉 Long 属性的 L 后缀(driftMs)", CLOCK, drop_long_suffix),
    ("负数字面量去掉 L(最容易漏的地方)", CLOCK, drop_long_suffix_negative),
]

# 不该被报出来的(射程边界:推断出来的类型不覆盖,免得误报)
NEGATIVE_CASES = [
    ("两边都是裸整数对 .size(正确写法)", EVENTS, lambda t: t),
    ("Long 字面量对 Long 属性(正确写法)", CLOCK, lambda t: t),
]


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="x-assert-rev-") as tmp:
        root = Path(tmp)
        for rel in COPY_DIRS:
            dest = root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(SRC / rel, dest)

        # 检查器的下限是为整仓定的,这里按副本调小 —— 否则它会中止,
        # 而「中止」会被误记成「报出」(见模块注释)。
        patched = (SRC / "scripts/check_x_test_assert_types.py").read_text(encoding="utf-8")
        patched = (
            patched.replace("MIN_FILES = 20", "MIN_FILES = 1")
            .replace("MIN_ASSERTS = 200", "MIN_ASSERTS = 2")
            .replace("MIN_LONG_NAMES = 10", "MIN_LONG_NAMES = 2")
        )
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
            detail = (out[1] if len(out) > 1 else out[0])[:92]
            # ⚠️ 「中止」不算「报出」:中止说明检查器没跑完判据,那时记成通过就是**假绿**。
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
                print(f"  ❌ {name} → 被误报!{(out[1] if len(out) > 1 else out[0])[:80]}")
                ok = False
            (root / rel).write_text(original, encoding="utf-8")

        # 「永远通过」那道防线:扫描面失效必须**中止**
        print()
        broken = patched.replace('base = root / TEST_DIR', 'base = root / "nonexistent-dir"')
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
