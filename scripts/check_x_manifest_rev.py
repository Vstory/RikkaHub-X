#!/usr/bin/env python3
"""`check_x_manifest.py` 的**反向验证**(进 CI)。

判据只有一条:**每一种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
否则它只是「看起来在守」。每条变异都断言「真的改动了东西」,变异没生效会被记成
「未验证」而不是「通过」。

## 2026-09-13 的两处结构变化(这条脚本跟着改的地方)

① **Firebase 已整体移除**,故判据 2 从「两条采集开关存在且为 false」**反转**成
   「Firebase 必须整体不在」—— 于是原来的两条变异(「删掉采集开关」「把开关改回 true」)
   靶子没了,换成了**反向**的三种:把 meta-data / 构建依赖 / Kotlin import 加回来。
② 判据 2 现在还要扫 `.kt`(找 `com.google.firebase` import)与三个构建文件,故临时根
   **必须拷一份真实骨架**(`app/src/main`),否则新加的「至少要扫到 300 个 .kt」那条下限
   会让检查器**中止** —— 而「中止」若被记成「报出」就是**假绿**(本会话踩过一次)。

用法:  python3 scripts/check_x_manifest_rev.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts" / "check_x_manifest.py"

MANIFEST = "app/src/main/AndroidManifest.xml"
APP_GRADLE = "app/build.gradle.kts"
ROOT_GRADLE = "build.gradle.kts"
CHATVM = "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt"

# 临时根要拷的骨架 —— 含 AndroidManifest、全部 .kt(判据 2 要扫)与两个构建文件。
COPY_DIRS = ("app/src/main",)
COPY_FILES = (APP_GRADLE, ROOT_GRADLE)


def replace_once(text: str, old: str, new: str) -> str:
    assert old in text, f"找不到锚点 `{old}`"
    out = text.replace(old, new, 1)
    assert out != text, "变异没有生效"
    return out


# ─── 必须被报出来的 ───
# (名字, 目标文件, 变异)
CASES = [
    # ── Firebase 加回来(判据 2 是反着守的,故这三条是**新增**的核心变异)──
    ("Firebase 采集开关被加回来", MANIFEST, lambda t: replace_once(
        t,
        '    <!--\n      [X-custom] Firebase 已**整体移除**',
        '    <meta-data\n      android:name="firebase_analytics_collection_enabled"\n'
        '      android:value="false" />\n\n    <!--\n      [X-custom] Firebase 已**整体移除**',
    )),
    ("构建文件里 firebase 依赖被加回来", APP_GRADLE, lambda t: replace_once(
        t,
        "dependencies {",
        "dependencies {\n    implementation(platform(libs.firebase.bom))",
    )),
    ("Kotlin 里 firebase import 被加回来", CHATVM, lambda t: replace_once(
        t, "package me.rerere.rikkahub.ui.pages.chat\n",
        "package me.rerere.rikkahub.ui.pages.chat\n\nimport com.google.firebase.analytics.FirebaseAnalytics\n",
    )),
    # ── 既有判据 ──
    ("摘掉 AD_ID 的 remove 指令", MANIFEST, lambda t: replace_once(
        t,
        '    android:name="com.google.android.gms.permission.AD_ID"\n    tools:node="remove" />',
        '    android:name="com.google.android.gms.permission.AD_ID" />',
    )),
    ("漏声明 xmlns:tools", MANIFEST, lambda t: replace_once(
        t, '  xmlns:tools="http://schemas.android.com/tools"', ""
    )),
    ("应用名占位符被写死", MANIFEST, lambda t: replace_once(
        t, 'android:label="${appLabel}"', 'android:label="RikkaHub X"')),
    ("application 类换成别的", MANIFEST, lambda t: replace_once(
        t, 'android:name=".RikkaHubApp"', 'android:name=".OtherApp"')),
    ("依赖冒出一条新的广告权限", MANIFEST, lambda t: replace_once(
        t,
        "  <uses-feature",
        '  <uses-permission android:name="com.google.android.gms.permission.ADSERVICES_NEW" />\n\n  <uses-feature',
    )),
    # ── 结构下限(这一条**应该走「中止」**而不是列问题;见下面的分类)──
    ("整个 uses-permission 区被删空", MANIFEST,
     lambda t: "\n".join(l for l in t.split("\n") if "uses-permission" not in l)),
    ("application 丢了 android:name", MANIFEST, lambda t: replace_once(
        t, '    android:name=".RikkaHubApp"\n', "")),
]

# 走「中止」而非「列问题」的那几条(结构下限)。分开列是为了让报告读得清楚。
STRUCTURE_CASES = {
    "整个 uses-permission 区被删空",
    "application 丢了 android:name",
    # ⚠️ 这一条**只能走中止**:两处 `tools:` 属性引用未绑定的前缀 → XML **解析即失败**
    #    (unbound prefix),判据 1 根本没机会跑。故它验的是「解析失败会不会被吞掉」,
    #    而不是「判据 1 会不会报」。
    #    ⚠️⚠️ 而首版脚本把**任何** returncode != 0 都记成「被报出」—— 于是这条变异
    #    **在判据 1 被整个删掉的情况下也照样是绿的**(假绿)。现在按类型分开断言。
    "漏声明 xmlns:tools",
}


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="x-manifest-rev-") as tmp:
        root = Path(tmp)
        for rel in COPY_DIRS:
            dest = root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(SRC / rel, dest)
        for rel in COPY_FILES:
            dest = root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(SRC / rel, dest)

        ok = True
        for name, rel, mutate in CASES:
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
            detail = (out[1] if len(out) > 1 else out[0])[:96]
            aborted = "无法进行" in text_all
            if r.returncode == 0:
                print(f"  ❌ {name} → 仍然通过,检查器失效!")
                ok = False
            elif name in STRUCTURE_CASES:
                if aborted:
                    print(f"  ✅ {name} → 结构下限中止(正确):{detail}")
                else:
                    print(f"  ⚠️  {name} → 被报出但**没走结构下限** —— 期望中止:{detail}")
                    ok = False
            elif aborted:
                # ⚠️ 「中止」不算「报出」:中止说明判据没跑完。那是对本条判据的**假绿**。
                print(f"  ⚠️  {name} → 检查器中止(不算验证通过):{detail}")
                ok = False
            else:
                print(f"  ✅ {name} → 被报出:{detail}")
            target.write_text(original, encoding="utf-8")

        # ── 反例:未改动时**必须通过**(否则说明检查器把正常状态也报了) ──
        print()
        r = subprocess.run([sys.executable, str(CHECKER), "--root", str(root)],
                           capture_output=True, text=True)
        if r.returncode == 0:
            print("  ✅ 未改动 → 通过(正确)")
        else:
            out = (r.stdout + r.stderr).strip().split("\n")
            print(f"  ❌ 未改动却报错 —— 误报!{(out[1] if len(out) > 1 else out[0])[:80]}")
            ok = False

        print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
        return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
