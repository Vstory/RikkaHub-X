#!/usr/bin/env python3
"""`check_x_diag_redaction.py` 的**反向验证**(不进 CI,供检查器改动时手动跑)。

判据只有一条:**每一种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
2026-09-12 写它的时候,首版就被本脚本抓出两处失效:

· 样例检查写成「前缀」,于是被掩码后的期望值 `"sk-proj-***"` 满足 → 样例删了也不报;
· 判据片段被当**正则**用(`sk-[A-Za-z0-9]{16,}` / `[?&]`),于是要么永远匹配不上、
  要么匹配到别处,报出假的「顺序不对」。

用法:  python3 scripts/check_x_diag_redaction_rev.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts/check_x_diag_redaction.py"

D = "app/src/main/java/me/rerere/rikkahub/x/diag"
P = "app/src/main/java/me/rerere/rikkahub/ui/pages/diagnostic"
T = "app/src/test/java/me/rerere/rikkahub/x/diag"
SCRUB = D + "/XLogScrub.kt"
PAGE = P + "/DiagnosticPage.kt"
TEST = T + "/XLogScrubTest.kt"
FILES = [SCRUB, D + "/XDiagZip.kt", D + "/XDiagFileStore.kt", PAGE, TEST]


# ── 变异函数(每个都断言真的改动了东西,否则不算验证)──────────────────

def drop_lines(text: str, needle: str) -> str:
    """删掉含 needle 的整行(用于删规则)。"""
    out = [l for l in text.split("\n") if needle not in l]
    assert len(out) < len(text.split("\n")), f"找不到含 `{needle}` 的行"
    return "\n".join(out)


def move_short_prefix_first(text: str) -> str:
    """把 sk-ant- 规则挪到 sk- 规则之后 —— 顺序不变式必须报出来。"""
    lines = text.split("\n")
    ant = [i for i, l in enumerate(lines) if 'Rule(Regex("\\\\bsk-ant-' in l]
    sk = [i for i, l in enumerate(lines) if 'Rule(Regex("\\\\bsk-[A-Za-z0-9]{16,}")' in l]
    assert len(ant) == 1 and len(sk) == 1, (ant, sk)
    line = lines.pop(ant[0])
    sk = [i for i, l in enumerate(lines) if 'Rule(Regex("\\\\bsk-[A-Za-z0-9]{16,}")' in l][0]
    lines.insert(sk + 1, line)
    return "\n".join(lines)


def replace_once(text: str, old: str, new: str) -> str:
    assert old in text, f"找不到 `{old}`"
    return text.replace(old, new, 1)


def replace_all(text: str, old: str, new: str) -> str:
    """全文替换。

    ⚠️ 命中数 >1 时必须用它 —— 2026-09-12 本脚本首版对 `Bearer` / `max_tokens` /
    `assertScrub(` 都只替换了**第一处**,于是「破坏」没造成、检查器理所当然地通过,
    却把结果记成「检查器失效」。**变异脚本自己也要被验证**:替换后必须断言目标字样
    已彻底消失,否则这条验证等于没做。
    """
    assert old in text, f"找不到 `{old}`"
    out = text.replace(old, new)
    assert old not in out, f"替换后仍残留 `{old}` —— 说明漏了改写"
    return out


CASES: list[tuple[str, str, object]] = [
    ("导出路径没脱敏(本次修的正是这个)",
     PAGE, lambda t: replace_once(t, "val body = XLogScrub.scrubBlock(payload.text)", "val body = payload.text")),
    ("规则被删(hf_)", SCRUB, lambda t: drop_lines(t, 'Rule(Regex("\\\\bhf_')),
    ("长前缀排到短前缀之后", SCRUB, move_short_prefix_first),
    ("样例被删(sk-proj- 只剩掩码后的期望值)", TEST,
     lambda t: replace_once(t, "sk-proj-abcdefghijklmnopqrstuvwxyz", "REDACTED")),
    ("Bearer 样例全被改掉", TEST, lambda t: replace_all(t, "Bearer", "Bearex")),
    ("反例被删(max_tokens)", TEST, lambda t: replace_all(t, "max_tokens", "max_tokns")),
    ("闸门被关(ENABLED = false)", SCRUB,
     lambda t: replace_once(t, "const val ENABLED: Boolean = true", "const val ENABLED: Boolean = false")),
    ("别处硬编码掩码 ***", D + "/XDiagZip.kt", lambda t: replace_once(t, "XLogScrub.MASK", '"***"')),
    ("规则表被清空", SCRUB,
     lambda t: "\n".join(l for l in t.split("\n") if not l.strip().startswith("Rule("))),
    ("用例被清空(assertScrub 被改名)", TEST, lambda t: replace_all(t, "assertScrub(", "asserScrub(")),
    ("新增导出形态但不加写出分支", PAGE,
     lambda t: replace_once(
         t,
         "    data class Text(val text: String) : PendingExport",
         "    data class Extra(val text: String) : PendingExport,\n\n"
         "    data class Text(val text: String) : PendingExport",
     )),
]


def main() -> int:
    ok = True
    for name, rel, mutate in CASES:
        root = Path("/tmp/scrub-rev/" + rel.split("/")[-1] + "-" + str(abs(hash(name)) % 100000))
        shutil.rmtree(root, ignore_errors=True)
        root.mkdir(parents=True)
        for f in FILES:
            (root / f).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(SRC / f, root / f)
        try:
            before = (root / rel).read_text(encoding="utf-8")
            after = mutate(before)
            assert after != before, "变异没有生效"
            (root / rel).write_text(after, encoding="utf-8")
        except AssertionError as exc:
            print(f"  ⚠️  {name} → 变异失败,未验证:{exc}")
            ok = False
            continue

        r = subprocess.run([sys.executable, str(CHECKER), "--root", str(root)],
                           capture_output=True, text=True)
        out = (r.stdout + r.stderr).strip().split("\n")
        detail = (out[1] if len(out) > 1 else out[0])[:100]
        if r.returncode != 0:
            print(f"  ✅ {name} → 被报出:{detail}")
        else:
            print(f"  ❌ {name} → 仍然通过,检查器失效!")
            ok = False

    print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
