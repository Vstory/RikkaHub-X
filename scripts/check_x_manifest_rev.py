#!/usr/bin/env python3
"""`check_x_manifest.py` 的**反向验证**(不进 CI 也可手动跑)。

判据只有一条:**每一种破坏都必须被报出来**。检查器自己也是代码,也要被验证 ——
否则它只是「看起来在守」。本脚本的每条变异都断言「真的改动了东西」,
变异没生效会被记成「未验证」而不是「通过」。

用法:  python3 scripts/check_x_manifest_rev.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts/check_x_manifest.py"
MANIFEST = "app/src/main/AndroidManifest.xml"


def replace_once(text: str, old: str, new: str) -> str:
    assert old in text, f"找不到锚点 `{old}`"
    out = text.replace(old, new, 1)
    assert out != text, "变异没有生效"
    return out


CASES = [
    ("删掉 analytics 采集开关",
     lambda t: replace_once(
         t,
         '    <meta-data\n      android:name="firebase_analytics_collection_enabled"\n      android:value="false" />\n',
         "",
     )),
    ("把 Crashlytics 开关改回 true",
     lambda t: replace_once(
         t,
         'android:name="firebase_crashlytics_collection_enabled"\n      android:value="false"',
         'android:name="firebase_crashlytics_collection_enabled"\n      android:value="true"',
     )),
    ("摘掉 AD_ID 的 remove 指令",
     lambda t: replace_once(
         t,
         '    android:name="com.google.android.gms.permission.AD_ID"\n    tools:node="remove" />',
         '    android:name="com.google.android.gms.permission.AD_ID" />',
     )),
    ("漏声明 xmlns:tools",
     lambda t: replace_once(
         t, '  xmlns:tools="http://schemas.android.com/tools"', ""
     )),
    ("应用名占位符被写死",
     lambda t: replace_once(t, 'android:label="${appLabel}"', 'android:label="RikkaHub X"')),
    ("application 类换成别的",
     lambda t: replace_once(t, 'android:name=".RikkaHubApp"', 'android:name=".OtherApp"')),
    ("依赖冒出一条新的广告权限",
     lambda t: replace_once(
         t,
         '  <uses-feature',
         '  <uses-permission android:name="com.google.android.gms.permission.ADSERVICES_NEW" />\n\n  <uses-feature',
     )),
    ("整个 uses-permission 区被删空",
     lambda t: "\n".join(l for l in t.split("\n") if "uses-permission" not in l)),
    ("application 下 meta-data 全被删",
     lambda t: t.replace('<meta-data\n      android:name="firebase_analytics_collection_enabled"\n      android:value="false" />\n', "")
                 .replace('<meta-data\n      android:name="firebase_crashlytics_collection_enabled"\n      android:value="false" />\n', "")),
]


def main() -> int:
    ok = True
    for name, mutate in CASES:
        root = Path("/tmp/manifest-rev/" + str(abs(hash(name)) % 100000))
        shutil.rmtree(root, ignore_errors=True)
        (root / Path(MANIFEST).parent).mkdir(parents=True, exist_ok=True)
        shutil.copy(SRC / MANIFEST, root / MANIFEST)
        try:
            before = (root / MANIFEST).read_text(encoding="utf-8")
            after = mutate(before)
            assert after != before, "变异没有生效"
            (root / MANIFEST).write_text(after, encoding="utf-8")
        except AssertionError as exc:
            print(f"  ⚠️  {name} → 变异失败,未验证:{exc}")
            ok = False
            continue

        r = subprocess.run([sys.executable, str(CHECKER), "--root", str(root)],
                           capture_output=True, text=True)
        out = (r.stdout + r.stderr).strip().split("\n")
        detail = (out[1] if len(out) > 1 else out[0])[:98]
        if r.returncode != 0:
            print(f"  ✅ {name} → 被报出:{detail}")
        else:
            print(f"  ❌ {name} → 仍然通过,检查器失效!")
            ok = False

    print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
