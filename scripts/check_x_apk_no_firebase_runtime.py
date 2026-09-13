#!/usr/bin/env python3
"""断言 **APK 里没有 Firebase 运行时**(用法:check_x_apk_no_firebase_runtime.py <apk>)。

## 为什么是「运行时」而不是「Firebase 字样」(2026-09-13 实测)

移除 Firebase(Crashlytics/Analytics)之后,我先按「APK 里不该有 firebase 字样」去查 ——
**那是错的不变式**,当场被真实产物打脸:当前 APK 的 manifest 里有 6 处 `firebase`,而且
是**合法的**:

    com.google.firebase.components:com.google.mlkit.vision.barcode.internal.BarcodeRegistrar
    com.google.firebase.components.ComponentRegistrar
    …

它们来自**扫码库**(`com.google.mlkit:barcode-scanning`,它传递依赖
`firebase-components` / `firebase-encoders` / `firebase-encoders-json`)。
ML Kit 有**自己的**引导链(`MlKitInitProvider` + `MlKitComponentDiscoveryService`),
它只是**借用** firebase-components 的组件系统 —— 完全不需要 firebase-common。

所以真正该守的是:**没有 Firebase 运行时**(App / provider / analytics / crashlytics)。
ML Kit 那几个 helper 库属于**允许存在**,但要打印出来,免得下次又被当成问题。

## 判据

不许出现(缺任一即失败):
· `com/google/firebase/FirebaseApp`                —— Firebase 的入口类
· `com/google/firebase/provider/FirebaseInitProvider` —— 依赖在就一定会被初始化的那个 provider
· `com/google/firebase/components/ComponentDiscoveryService` —— firebase-common 的组件发现
· `com/google/firebase/crashlytics/` 与 `com/google/firebase/analytics/` 两个包

不许出现(APK 根部):`firebase-analytics/crashlytics/common/measurement-connector/datatransport*.properties`
(AGP 为每个打进包里的 AAR 生成一份这样的标记 —— 有它=那个 jar 被打进去了)

## 索引下限

找不到 dex、或搜不到 `rikkahub`(证明它是本应用的包而不是空文件)→ **报错退出**。
否则「搜不到 firebase」可能只是**搜了个空文件**。

## 用法

    python3 scripts/check_x_apk_no_firebase_runtime.py app-release.apk
"""
from __future__ import annotations

import re
import subprocess
import sys
import zipfile
from pathlib import Path

FORBIDDEN_DEX = (
    "com/google/firebase/FirebaseApp",
    "com/google/firebase/provider/FirebaseInitProvider",
    "com/google/firebase/components/ComponentDiscoveryService",
    "com/google/firebase/crashlytics/",
    "com/google/firebase/analytics/",
)

FORBIDDEN_JARS = re.compile(
    r"firebase-(analytics|crashlytics|common|measurement-connector|datatransport)[a-z-]*\.properties"
)

# ML Kit 传递带入的 helper 库 —— 允许,但要打印出来
ALLOWED_JARS = re.compile(r"firebase-(annotations|components|encoders|encoders-json)\.properties")

MIN_DEX = 1
SANITY_NEEDLE = "rikkahub"


class Problem(Exception):
    """无法进行 —— 与「发现问题」区分开。"""


def dex_strings(blob: bytes) -> str:
    """用 strings 的等价物取可打印串(dex 里类名是明文)。"""
    try:
        out = subprocess.run(["strings", "-a"], input=blob, capture_output=True)
    except FileNotFoundError:
        raise Problem("系统里没有 `strings`(binutils)—— 无法读 dex 字符串")
    return out.stdout.decode("latin-1", "replace")


def check(apk: Path) -> tuple:
    if not apk.is_file():
        raise Problem(f"找不到 {apk}")

    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        dexes = [n for n in names if re.match(r"classes\d*\.dex$", n)]
        if len(dexes) < MIN_DEX:
            raise Problem(f"APK 里没有 dex(找到 {len(dexes)} 个)—— 这不是一个可分析的 APK")
        blob = b"".join(z.read(n) for n in dexes)
        entries = names

    text = dex_strings(blob)
    if SANITY_NEEDLE not in text:
        raise Problem(
            f"dex 里搜不到 `{SANITY_NEEDLE}` —— 读数看着是空的。"
            f"这时「搜不到 firebase」不能算证据(可能是搜了个空文件)。"
        )

    problems = []
    for needle in FORBIDDEN_DEX:
        hits = text.count(needle)
        if hits:
            problems.append(
                f"dex 里有 Firebase 运行时类 `{needle}`({hits} 处)—— "
                f"Firebase 的运行时已移除,它出现说明依赖又被带回来了。"
            )

    bad_jars = sorted({m.group(0) for n in entries for m in [FORBIDDEN_JARS.search(n)] if m})
    for jar in bad_jars:
        problems.append(
            f"APK 里有 `{jar}` —— 说明对应的 firebase jar 被打进包里了"
            f"(AGP 会为每个打进去的 AAR 生成一份这样的标记)。"
        )

    allowed = sorted({m.group(0) for n in entries for m in [ALLOWED_JARS.search(n)] if m})
    return problems, allowed


def main(argv: list) -> int:
    if len(argv) < 2:
        print("用法: python3 scripts/check_x_apk_no_firebase_runtime.py <apk>")
        return 2
    apk = Path(argv[1]).resolve()
    try:
        problems, allowed = check(apk)
    except Problem as exc:
        print(f"[FAIL] Firebase 运行时自检无法进行:{exc}")
        return 1

    if problems:
        print(f"[FAIL] {apk.name}:发现 {len(problems)} 处 Firebase 运行时残留:")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(f"[CHECK PASS] {apk.name}:没有 Firebase 运行时(App / provider / analytics / crashlytics)")
    if allowed:
        print(f"   (ML Kit 扫码库的 helper,允许存在:{', '.join(allowed)})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
