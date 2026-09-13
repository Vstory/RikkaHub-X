#!/usr/bin/env python3
"""`check_x_event_lists.py` 的**反向验证**(不进 CI,供检查器改动时手动跑)。

判据只有一条:**每一种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
没有验证过「能报错」的检查不算检查,它可能只是在永远通过。

## 为什么特别要验这条

本检查器是为了接住一次**真实的、已经付过代价的**失败(2026-09-13:往对象里加了 5 个
常量却没登记进 ALL,CI 红了才发现)。故这里逐条复现那个场景,外加它的两种变体:
改名(拼错)、形态破坏,以及「解析失效必须中止」那道防「永远通过」的线。

⚠️ 写变异时注意:一个**测不出问题**的变异最危险 —— 它让人以为防线在,其实没验过。
(2026-09-13 在布局检查器上就踩到过:改名那个变异因为正则的 `[^{]*` 顺手吃掉了改动,
测不出来。故这里每个变异都带 `assert` 确认真的改动了内容。)

用法:  python3 scripts/check_x_event_lists_rev.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts" / "check_x_event_lists.py"

X_DIR = "app/src/main/java/me/rerere/rikkahub/x"
EVENTS = X_DIR + "/chat/XChatEvents.kt"

# 检查器扫的是整个 x 目录,故整体拷过去。
COPY_DIRS = (X_DIR,)


# ── 变异函数(每个都断言真的改动了东西)──────────────────────────────

def declare_without_register(text: str) -> str:
    """加一个常量但不放进 ALL —— 这就是 2026-09-13 真实发生的那种。"""
    anchor = "    /** 全部事件名：单测据此校验命名规则与唯一性。 */"
    assert anchor in text, "找不到 ALL 上方的注释锚点"
    return text.replace(
        anchor,
        '    const val SNEAKY_NEW = "chat.sneaky.new"\n\n' + anchor,
        1,
    )


def register_without_declare(text: str) -> str:
    """在 ALL 里写一个找不到常量的名字(拼错) —— 按名字检索会失配。"""
    old = "        MESSAGE_SENT,\n"
    assert old in text, "找不到 MESSAGE_SENT 登记行"
    return text.replace(old, old + "        MESSAGE_SENT_TYPO,\n", 1)


def break_name_shape(text: str) -> str:
    """把某个事件名改成四段 —— 形态判据必须报出来。"""
    old = 'const val MESSAGE_SENT = "chat.message.sent"'
    assert old in text, "找不到 MESSAGE_SENT 常量"
    return text.replace(old, 'const val MESSAGE_SENT = "chat.message.sent.extra"', 1)


def put_regex_meta_in_name(text: str) -> str:
    """名字里塞正则元字符 —— 按名字过滤日志会失配。"""
    old = 'const val TOOL_APPROVED = "chat.tool.approved"'
    assert old in text, "找不到 TOOL_APPROVED 常量"
    return text.replace(old, 'const val TOOL_APPROVED = "chat.tool.approved*"', 1)


MUTATIONS = [
    ("常量已声明但未登记进 ALL(真实踩过的那次)", EVENTS, declare_without_register),
    ("ALL 里写了一个拼错的名字", EVENTS, register_without_declare),
    ("事件名不是三段", EVENTS, break_name_shape),
    ("事件名含正则元字符", EVENTS, put_regex_meta_in_name),
]


def run_checker(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(CHECKER), "--root", str(root)],
        capture_output=True, text=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="x-events-rev-") as tmp:
        root = Path(tmp)
        for rel in COPY_DIRS:
            shutil.copytree(SRC / rel, root / rel)

        ok = True
        for name, rel, mutate in MUTATIONS:
            # 每个变异都从**原始**内容出发(而不是在上一轮改坏的基础上叠加),
            # 否则一个变异会污染下一个,报出来的原因也就说不清了。
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
            out = (r.stdout + r.stderr).strip().split("\n")
            detail = (out[1] if len(out) > 1 else out[0])[:95]
            if r.returncode != 0:
                print(f"  ✅ {name} → 被报出:{detail}")
            else:
                print(f"  ❌ {name} → 仍然通过,检查器失效!")
                ok = False

            (root / rel).write_text(original, encoding="utf-8")

        # 「永远通过」那道防线:解析失效必须**中止**
        print()
        original = (SRC / EVENTS).read_text(encoding="utf-8")
        # 把 ALL 的写法改掉,让 ALL_RE 匹配不上 → 扫到 0 个对象 → 必须命中下限并报错
        broken = original.replace("val ALL: List<String> = listOf(", "val ALL_EVENTS = listOf(")
        assert broken != original, "没能改动 ALL 的写法"
        (root / EVENTS).write_text(broken, encoding="utf-8")
        r = run_checker(root)
        text = r.stdout + r.stderr
        if r.returncode != 0 and "无法进行" in text:
            print("  ✅ ALL 的写法变了(扫不到清单)→ 检查器中止,而不是静默通过")
        else:
            print("  ❌ ALL 的写法变了却仍然通过 —— 这就是「永远通过」的假检查")
            ok = False
        (root / EVENTS).write_text(original, encoding="utf-8")

        print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
        return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
