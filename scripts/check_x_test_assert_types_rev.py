#!/usr/bin/env python3
"""`check_x_test_assert_types.py` 的**反向验证**(不进 CI?—— 进,见下)。

判据只有一条:**每种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
没有验证过「能报错」的检查不算检查,它可能只是在永远通过。

## 为什么这条检查器值得配反向验证

它是为一次**真实付过代价的失败**补的(2026-09-13:`assertEquals(1L, map.size)`
在 CI 的 Guard 上红了,而编译通过、本地检查全绿)。

⚠️ 本脚本**同时**守住它的射程边界:有一条反例断言「刻意不覆盖的那一类不该被报」——
因为「太吵」也是一种失效(误报会被容忍到没人再信这个检查)。

用法:  python3 scripts/check_x_test_assert_types_rev.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts" / "check_x_test_assert_types.py"

TEST_DIR = "app/src/test/java"
# 只拷需要的目录:检查器扫的是整个测试根,故把 x/diag 那一份拷过去即可 ——
# 但它有 MIN_FILES 下限,故反向验证时把下限改写小(见 main)。
COPY_DIR = TEST_DIR + "/me/rerere/rikkahub/x/diag"
TARGET = TEST_DIR + "/me/rerere/rikkahub/x/diag/XEventIndexTest.kt"


def long_vs_size(text: str) -> str:
    """`assertEquals(1, x.size)` 改成 `assertEquals(1L, x.size)` —— 判据必须报出来。

    这正是 2026-09-13 真实踩到的那一处。
    """
    old = 'assertEquals(1, result.getValue("core").size)'
    new = 'assertEquals(1L, result.getValue("core").size)'
    assert old in text, "找不到 target 断言"
    return text.replace(old, new, 1)


def long_vs_length_reversed(text: str) -> str:
    """把 Long 字面量放到**右边** —— 两个方向都要挡(否则换边就漏)。"""
    old = 'assertEquals(1, result.getValue("core").size)'
    new = 'assertEquals(result.getValue("core").size, 1L)'
    assert old in text, "找不到 target 断言"
    return text.replace(old, new, 1)


def long_vs_count(text: str) -> str:
    """`.count()` 也是 Int —— 同样要挡。"""
    old = 'assertEquals(2L, result.getValue("chat").getValue("chat.message.sent"))'
    new = 'assertEquals(1L, result.getValue("chat").values.count())'
    assert old in text, "找不到 target 断言"
    return text.replace(old, new, 1)


def int_vs_known_long(text: str) -> str:
    """裸整数对**本项目里确定是 Long** 的属性(`.lines`) —— 反方向必须挡。"""
    old = 'assertEquals(1, result.getValue("core").size)'
    new = 'assertEquals(1, session.lines)'
    assert old in text, "找不到 target 断言"
    return text.replace(old, new, 1)


# 必须被报出来的
MUTATIONS = [
    ("Long 字面量对 .size(真实踩到的那一处)", long_vs_size),
    ("同一处但 Long 在右边", long_vs_length_reversed),
    ("Long 字面量对 .count()", long_vs_count),
    ("裸整数对 .lines(反方向)", int_vs_known_long),
]

# 不该被报出来的(射程边界:推断出来的类型不覆盖,免得误报)
NEGATIVE_CASES = [
    ("两边都是裸整数(正确写法)",
     lambda t: t.replace('assertEquals(1, result.getValue("core").size)',
                         'assertEquals(1, result.getValue("core").size)')),
    ("两边都是 Long(正确写法)",
     lambda t: t.replace('assertEquals(2L, result.getValue("chat").getValue("chat.message.sent"))',
                         'assertEquals(2L, result.getValue("chat").getValue("chat.message.sent"))')),
]


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="x-assert-rev-") as tmp:
        root = Path(tmp)
        # 只拷一个目录:检查器的 MIN_FILES 是为整仓定的,这里把它改写小,
        # 免得反向验证反过来被下限挡住(那会让人误以为「检查器坏了」)。
        (root / TEST_DIR).mkdir(parents=True, exist_ok=True)
        # 父目录要先建出来 —— `cp -r` 不会替你递归造父目录。
        dest = root / COPY_DIR
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(SRC / COPY_DIR, dest)
        patched = (SRC / "scripts/check_x_test_assert_types.py").read_text(encoding="utf-8")
        patched = patched.replace("MIN_FILES = 20", "MIN_FILES = 1").replace(
            "MIN_ASSERTS = 200", "MIN_ASSERTS = 2")
        checker = Path(tmp) / "chk.py"
        checker.write_text(patched, encoding="utf-8")

        def run() -> subprocess.CompletedProcess:
            return subprocess.run(
                [sys.executable, str(checker), "--root", str(root)],
                capture_output=True, text=True,
            )

        target = root / TARGET
        original = (SRC / TARGET).read_text(encoding="utf-8")
        ok = True

        for name, mutate in MUTATIONS:
            after = mutate(original)
            assert after != original, f"{name}:变异没生效"
            target.write_text(after, encoding="utf-8")
            r = run()
            if r.returncode != 0:
                out = (r.stdout + r.stderr).strip().split("\n")
                detail = (out[1] if len(out) > 1 else out[0])[:92]
                print(f"  ✅ {name} → 被报出:{detail}")
            else:
                print(f"  ❌ {name} → 仍然通过,检查器失效!")
                ok = False
            target.write_text(original, encoding="utf-8")

        print()
        for name, mutate in NEGATIVE_CASES:
            target.write_text(mutate(original), encoding="utf-8")
            r = run()
            if r.returncode == 0:
                print(f"  ✅ {name} → 未被报出(正确)")
            else:
                out = (r.stdout + r.stderr).strip().split("\n")
                print(f"  ❌ {name} → 被误报!{(out[1] if len(out) > 1 else out[0])[:80]}")
                ok = False
            target.write_text(original, encoding="utf-8")

        # 「永远通过」那道防线:扫描面失效必须**中止**
        print()
        broken = patched.replace('base = root / TEST_DIR', 'base = root / "nonexistent-dir"')
        checker.write_text(broken, encoding="utf-8")
        r = run()
        text = r.stdout + r.stderr
        if r.returncode != 0 and ("无法进行" in text):
            print("  ✅ 扫描面失效 → 检查器中止(而不是静默通过)")
        else:
            print("  ❌ 扫描面失效却仍然通过 —— 这就是「永远通过」的假检查")
            ok = False

        print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
        return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
