#!/usr/bin/env python3
"""`check_x_diag_layout.py` 的**反向验证**(不进 CI,供检查器改动时手动跑)。

判据只有一条:**每一种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
没有验证过「能报错」的检查不算检查,它可能只是在永远通过。

## 为什么现在特别需要它

2026-09-13 那次简化把域文件合并成了一个 `events.log`,布局检查器的判据**换了大半**:
旧的「文件 ↔ 域」耦合消失了,取而代之的是**「每行都必须带 domain」**这条全新约束。
新判据有没有真的生效,只能靠把源码故意改坏来问。

用法:  python3 scripts/check_x_diag_layout_rev.py
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts/check_x_diag_layout.py"

D = "app/src/main/java/me/rerere/rikkahub/x/diag"
P = "app/src/main/java/me/rerere/rikkahub/ui/pages/diagnostic"

DOMAIN_ENUM = D + "/XDiagnostics.kt"
STORE = D + "/XDiagFileStore.kt"
ZIP = D + "/XDiagZip.kt"
LINE = D + "/XDiagLine.kt"
NETLINE = D + "/XNetLine.kt"

# 检查器扫的是整个目录(要求 ≥ MIN_FILES 个 .kt),故把两个目录整体拷过去。
COPY_DIRS = (D, P)


# ── 变异函数(每个都断言真的改动了东西,否则不算验证)──────────────────

def drop_put_domain(text: str) -> str:
    """删掉 `put("domain", …)` 那一行 —— 判据 4 必须报出来。"""
    lines = text.split("\n")
    out = [l for l in lines if 'put("domain"' not in l]
    assert len(out) < len(lines), '找不到 put("domain") 那一行'
    return "\n".join(out)


def rename_events_const(text: str) -> str:
    """把 EVENTS_FILE 的值改成别的名字 —— 别处仍引用旧名,判据 3 必须报出来。"""
    assert 'const val EVENTS_FILE = "events.log"' in text, "找不到 EVENTS_FILE 常量"
    return text.replace('const val EVENTS_FILE = "events.log"', 'const val EVENTS_FILE = "timeline.log"')


def collide_with_logcat(text: str) -> str:
    """让事件文件与 logcat 同名 —— 判据 2 的撞车检查必须报出来。"""
    assert 'const val EVENTS_FILE = "events.log"' in text, "找不到 EVENTS_FILE 常量"
    return text.replace('const val EVENTS_FILE = "events.log"', 'const val EVENTS_FILE = "logcat.log"')


def hardcode_name_in_zip(text: str) -> str:
    """把 describe() 里的常量引用换成字面量 —— 判据 5 必须报出来。"""
    assert "XDiagFileStore.EVENTS_FILE" in text, "describe() 里没有引用常量"
    return text.replace("XDiagFileStore.EVENTS_FILE", '"events.log"')


def add_duplicate_domain_key(text: str) -> str:
    """把某个域的 key 改成与另一个重复 —— 判据 1 必须报出来。"""
    assert 'STORAGE("storage", "存储")' in text, "找不到 STORAGE 域"
    return text.replace('STORAGE("storage", "存储")', 'STORAGE("chat", "存储")')


def leak_dir_literal(text: str) -> str:
    """在 XDiagLine 里写一个目录字面量 —— 判据 6 必须报出来。"""
    assert "object XDiagLine {" in text, "找不到 XDiagLine 声明"
    return text.replace("object XDiagLine {", 'object XDiagLine {\n    const val LEAK = "x-diag"')


# ── 跑一遍 ─────────────────────────────────────────────────────────────

MUTATIONS = [
    ("语义行丢掉 domain 字段", LINE, drop_put_domain),
    ("网络行丢掉 domain 字段", NETLINE, drop_put_domain),
    ("事件文件名常量改名(别处仍用旧名)", STORE, rename_events_const),
    ("事件文件与 logcat 同名", STORE, collide_with_logcat),
    ("describe() 硬编码文件名", ZIP, hardcode_name_in_zip),
    ("两个域 key 重复", DOMAIN_ENUM, add_duplicate_domain_key),
    ("目录字面量泄漏到别处", LINE, leak_dir_literal),
]


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="xdiag-layout-rev-") as tmp:
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

            r = subprocess.run(
                [sys.executable, str(CHECKER), "--root", str(root)],
                capture_output=True, text=True,
            )
            out = (r.stdout + r.stderr).strip().split("\n")
            detail = (out[1] if len(out) > 1 else out[0])[:95]
            if r.returncode != 0:
                print(f"  ✅ {name} → 被报出:{detail}")
            else:
                print(f"  ❌ {name} → 仍然通过,检查器失效!")
                ok = False

            # 还原,给下一个变异一个干净起点
            (root / rel).write_text(original, encoding="utf-8")

        # 解析失败必须**中止**而不是「通过」—— 这是「永远通过」那道防线
        #
        # ⚠️ 变异方式很讲究:最初我用「把 `enum class XDomain(` 改名成 `XDomainX(`」,
        #    结果**测不出来** —— 因为 `ENUM_BLOCK_RE` 是 `enum class XDomain[^{]*\{`,
        #    那个 `[^{]*` 顺手把 `X ` 也吃掉了,改名后照样匹配得上。
        #    **一个测不出问题的变异会让人以为防线在,其实没验过。**
        #    故改成动**条目缩进**(`ENUM_ENTRY_RE` 要求行首恰好 4 个空格):
        #    枚举体解析出 0 条 → 必须命中 `MIN_DOMAINS` 那条下限并报错中止。
        print()
        original = (SRC / DOMAIN_ENUM).read_text(encoding="utf-8")
        broken = re.sub(r"^ {4}([A-Z][A-Z0-9_]*\()", r"        \1", original, flags=re.M)
        assert broken != original, "没能改动枚举条目的缩进"
        (root / DOMAIN_ENUM).write_text(broken, encoding="utf-8")
        r = subprocess.run(
            [sys.executable, str(CHECKER), "--root", str(root)],
            capture_output=True, text=True,
        )
        text = r.stdout + r.stderr
        if r.returncode != 0 and "无法进行" in text:
            print("  ✅ 枚举解析不出来 → 检查器中止(而不是静默通过)")
        else:
            print("  ❌ 枚举解析不出来 → 检查器竟然通过了!这就是「永远通过」的假检查")
            ok = False
        (root / DOMAIN_ENUM).write_text(original, encoding="utf-8")

        print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
        return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
