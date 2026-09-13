#!/usr/bin/env python3
"""`check_x_package_names.py` 的**反向验证**(进 CI)。

判据只有一条:**每种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
没有验证过「能报错」的检查不算检查,它可能只是在永远通过。

## 为什么这条要验四类破坏

包名契约有三方(基名 / 后缀 / CI 期望),任一处漂开都该报,故逐一试:
① CI 的期望被改(与 Gradle 推出对不上);
② Gradle 的后缀被改(同上);
③ **后缀前面的条件被去掉**(`xChannel == "nightly"` → 变成无条件)→ 正式版也会带上它;
④ 基名被改。

另加一条反例:未改动时必须通过。**这一条不是走过场** —— 首版的 `EXPECTED` 正则会
把 CI 里那处**证书指纹**的期望值也算进来,于是检查器对着正常代码报「契约对不上」;
反例就是用来钉住这件事的(误报会让检查器被无视)。

用法:  python3 scripts/check_x_package_names_rev.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts/check_x_package_names.py"

GRADLE = "app/build.gradle.kts"
CI = ".github/workflows/daily-build.yml"
COPY_FILES = (GRADLE, CI)


def replace_once(text: str, old: str, new: str) -> str:
    assert old in text, f"找不到锚点 `{old}`"
    out = text.replace(old, new, 1)
    assert out != text, "变异没有生效"
    return out


# (名字, 目标文件, 变异)
MUTATIONS = [
    ("CI 的 nightly 期望包名被改", CI, lambda t: replace_once(
        t, 'EXPECTED="me.rerere.rikkahub.x.nightly"', 'EXPECTED="me.rerere.rikkahub.nightly"')),
    ("Gradle 的 .nightly 后缀被改", GRADLE, lambda t: replace_once(
        t, 'applicationIdSuffix = ".nightly"', 'applicationIdSuffix = ".nightlyX"')),
    ("后缀前的 nightly 条件被去掉", GRADLE, lambda t: replace_once(
        t, 'if (xChannel == "nightly") {', 'if (true) {')),
    ("applicationId 基名被改", GRADLE, lambda t: replace_once(
        t, 'applicationId = "me.rerere.rikkahub.x"', 'applicationId = "me.rerere.rikkahub.y"')),
]


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="x-pkg-rev-") as tmp:
        root = Path(tmp)
        for rel in COPY_FILES:
            dest = root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(SRC / rel, dest)

        ok = True
        for name, rel, mutate in MUTATIONS:
            target = root / rel
            original = (SRC / rel).read_text(encoding="utf-8")
            try:
                after = mutate(original)
                assert after != original, "变异没有生效"
                target.write_text(after, encoding="utf-8")
            except AssertionError as exc:
                print(f"  ⚠️  {name} → 变异失败,未验证:{exc}")
                ok = False
                continue

            r = subprocess.run([sys.executable, str(CHECKER), "--root", str(root)],
                               capture_output=True, text=True)
            text_all = r.stdout + r.stderr
            out = text_all.strip().split("\n")
            detail = (out[1] if len(out) > 1 else out[0])[:95]
            # ⚠️ 「中止」不算「报出」:中止说明判据没跑完,记成通过就是假绿。
            if "无法进行" in text_all:
                print(f"  ⚠️  {name} → 检查器中止(不算验证通过):{detail}")
                ok = False
            elif r.returncode != 0:
                print(f"  ✅ {name} → 被报出:{detail}")
            else:
                print(f"  ❌ {name} → 仍然通过,检查器失效!")
                ok = False
            target.write_text(original, encoding="utf-8")

        # ── 反例:未改动必须通过 ──
        print()
        r = subprocess.run([sys.executable, str(CHECKER), "--root", str(root)],
                           capture_output=True, text=True)
        if r.returncode == 0:
            print("  ✅ 未改动 → 通过(正确;这条钉住「别把证书指纹算进契约」)")
        else:
            out = (r.stdout + r.stderr).strip().split("\n")
            print(f"  ❌ 未改动却报错 —— 误报!{(out[1] if len(out) > 1 else out[0])[:85]}")
            ok = False

        # ── 「永远通过」那道防线:锚点没了必须中止 ──
        print()
        patched = (SRC / "scripts/check_x_package_names.py").read_text(encoding="utf-8")
        # ⚠️ 破坏**解析结果**(而不是把定义与使用一起改名 —— 首版那样写等于没破坏,
        #    那条自检**照样通过**;也不能靠嵌套引号去改正则模式,那种写法在本会话里
        #    已经栽过好几次)。这里改成让 findall 永远返回空 → 必然走「声明次数不对」。
        needle = "ids = APP_ID_RE.findall(gradle)"
        assert needle in patched, "找不到解析语句"
        broken = patched.replace(needle, "ids = []", 1)
        assert broken != patched, "没能破坏解析结果"
        checker = root / "chk.py"
        checker.write_text(broken, encoding="utf-8")
        r = subprocess.run([sys.executable, str(checker), "--root", str(root)],
                           capture_output=True, text=True)
        if r.returncode != 0 and "无法进行" in (r.stdout + r.stderr):
            print("  ✅ 解析锚点失效 → 检查器中止(而不是静默通过)")
        else:
            print("  ❌ 解析锚点失效却仍然通过 —— 这就是「永远通过」的假检查")
            ok = False

        print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
        return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
