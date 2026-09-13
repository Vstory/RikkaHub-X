#!/usr/bin/env python3
"""`check_x_apk_no_firebase_runtime.py` 的**离线**反向验证(进 CI)。

判据:**每种破坏都必须被报出来;每种「看着像但合法」的都必须放行;读数坏了必须中止。**

## 为什么这条能离线验(不需要真 APK)

那个检查器只读两件东西:**zip 条目名** 与 **dex 里的可打印串**。故用 zipfile 手搓一个
「够用的假 APK」就行 —— 这比依赖真产物可靠:真产物只在 daily-build 里存在,而静态检查
每次 push 都跑。

## 三类必须区分开

| 类 | 例子 | 期望 |
|---|---|---|
| 破坏 | dex 里有 `FirebaseInitProvider`;包里有 `firebase-analytics.properties` | **报出** |
| 合法(易误报) | dex 里有 `ComponentRuntime`;包里有 `firebase-components.properties` | **放行** |
| 读数坏了 | 没有 dex;dex 里没有 `rikkahub` | **中止** |

第二类尤其重要:真实产物就是这样(ML Kit 借用 firebase-components),
判据若写成「搜 firebase」就会把它误报 —— 那种误报会让人把检查器关掉。

用法:  python3 scripts/check_x_apk_no_firebase_runtime_rev.py
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts" / "check_x_apk_no_firebase_runtime.py"

# 一个「够像」的 dex:检查器只关心里面的可打印串
GOOD_DEX = b"rikkahub\x00me/rerere/rikkahub/MainActivity\x00okhttp3/OkHttpClient"


def make_apk(path: Path, entries: dict) -> None:
    with zipfile.ZipFile(path, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)


CASES = [
    (
        "dex 里出现 FirebaseInitProvider(运行时)",
        {"classes.dex": GOOD_DEX + b"\x00com/google/firebase/provider/FirebaseInitProvider"},
        "fail",
    ),
    (
        "dex 里出现 FirebaseApp(运行时)",
        {"classes.dex": GOOD_DEX + b"\x00com/google/firebase/FirebaseApp"},
        "fail",
    ),
    (
        "包里有 firebase-analytics.properties(那个 jar 被打进来了)",
        {"classes.dex": GOOD_DEX, "firebase-analytics.properties": b"23.2.0"},
        "fail",
    ),
    (
        "【反例】只有 ML Kit 的 helper(ComponentRuntime + firebase-components.properties)",
        {
            "classes.dex": GOOD_DEX + b"\x00com/google/firebase/components/ComponentRuntime",
            "firebase-components.properties": b"19.0.0",
            "firebase-encoders.properties": b"17.0.0",
        },
        "pass",
    ),
    (
        "【读数坏了】APK 里没有 dex",
        {"AndroidManifest.xml": b"<manifest/>"},
        "abort",
    ),
    (
        "【读数坏了】dex 里搜不到 rikkahub(等于搜了个空文件)",
        {"classes.dex": b"nothing-interesting-here"},
        "abort",
    ),
]


def main() -> int:
    ok = True
    with tempfile.TemporaryDirectory(prefix="x-apk-fb-rev-") as tmp:
        root = Path(tmp)
        for i, (name, entries, expect) in enumerate(CASES):
            apk = root / f"case{i}.apk"
            make_apk(apk, entries)
            r = subprocess.run([sys.executable, str(CHECKER), str(apk)],
                               capture_output=True, text=True)
            text = r.stdout + r.stderr
            first = text.strip().split("\n")[0][:100]
            if expect == "fail":
                if r.returncode != 0 and "无法进行" not in text:
                    print(f"  ✅ {name} → 被报出:{first}")
                elif "无法进行" in text:
                    print(f"  ⚠️  {name} → 检查器中止(不算报出):{first}")
                    ok = False
                else:
                    print(f"  ❌ {name} → 仍然通过,检查器失效!")
                    ok = False
            elif expect == "pass":
                if r.returncode == 0:
                    print(f"  ✅ {name} → 放行(正确;这条钉住「别把 ML Kit helper 误报」)")
                else:
                    print(f"  ❌ {name} → 被误报!{first}")
                    ok = False
            else:  # abort
                if r.returncode != 0 and "无法进行" in text:
                    print(f"  ✅ {name} → 中止(正确):{first}")
                else:
                    print(f"  ❌ {name} → 没有中止 —— 会变成「搜了个空文件也算通过」:{first}")
                    ok = False

    print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
