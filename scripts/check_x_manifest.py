#!/usr/bin/env python3
"""AndroidManifest 的 X 定制自检 —— 守那些**被上游 merge 冲掉后静默恢复默认**的声明。

## 为什么需要它(这一份尤其)

`AndroidManifest.xml` 是**上游会持续改**的文件,而 X 在它上面挂了三件「丢了不报错、
只改行为」的事:

| 挂什么 | 丢了会怎样 |
|---|---|
| **Firebase 应该是「不在」**(依赖/插件/两条 meta-data/源码 import) | ⚠️ 这是**反着守**:上游 merge 把 Firebase 加回来时,依赖与初始化会一起回来 —— 而那时**采集是否恢复**取决于 meta-data,两条都不在了就直接恢复 |
| 三条 `tools:node="remove"` 的广告 ID 权限 | 别的依赖把权限带回来,APK 里重新声明「读取广告 ID」 —— 装的时候看不出来 |
| `${appLabel}` 占位符 | nightly 包的**应用名静默少了后缀**(`RikkaHub X` 而非 `RikkaHub X Nightly`) |
| `android:name=".RikkaHubApp"` | 诊断框架的接线点 `onCreate` **根本不会跑** —— 开关不落盘、logcat 一条不记,而 App 行为看着完全正常 |

⚠️ **`.kt` 之外的定制不在标签存活检查的射程内**(X Custom Audit 只扫 `*.kt` 且只在
`src/main`),故这一类只能靠本检查器 + 对账表登记。这正是文档里那条
「`app/src/main` 下非 `.kt` 文件 → ❌ → C 表登记 + 尽量补检查」的落地。

## 判据(逐条都对应上面一行)

1. `xmlns:tools` 已声明 —— 少了它,`tools:node` 与 `tools:targetApi` 会让**清单合并直接失败**;
2. **Firebase 运行时必须不在** —— 没有 `firebase_*` meta-data、构建文件里没有
   firebase/google-services、Kotlin 里没有 `com.google.firebase` import。
   ⚠️ 这一条是 `2026-09-13` **反转**过来的:原来守的是「两条采集开关存在且为 false」
   (那时只关采集、不动能力);接替件真机验证通过后依赖移除,于是判据反过来守
   「别被上游 merge 带回来」。
   ⚠️ 本检查只看**构建输入**(Manifest/构建文件/源码),故它管不到「传递依赖」——
   扫 **APK** 的那一层见 `check_x_apk_no_firebase_runtime.py`(由 daily-build 调用)。
   那一层要区分「Firebase 运行时」(必须在不在)与「ML Kit 扫码库的 helper」
   (允许存在;ML Kit 有自己 `MlKitInitProvider` 引导链,不需要 firebase-common)。
3. 三条广告 ID 权限存在且都带 `tools:node="remove"`;
4. `${appLabel}` 占位符仍在 `application` 的 `android:label` 里;
5. `application` 的 `android:name` 仍指向 `.RikkaHubApp`;
6. **依赖没有再冒出新的一条广告 ID 权限**(白名单式:凡名字含 `AD_ID` / `ADSERVICES`
   的 `uses-permission` 都必须带 remove)。

## 索引下限

解析出的 `uses-permission` 少于 10 条、或 `application` 下没有 meta-data → **报错退出**。
否则文件被换掉/结构变了会让所有判据自动「通过」。

## 用法

    python3 scripts/check_x_manifest.py [--root <仓库根>]
"""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
import re
from pathlib import Path

MANIFEST = "app/src/main/AndroidManifest.xml"

A = "{http://schemas.android.com/apk/res/android}"
T = "{http://schemas.android.com/tools}"

# 依赖会带进来的广告相关权限(applicaton 源码从不声明它们)
AD_PERMISSIONS = (
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
)

# 必须**不存在**的 meta-data(2026-09-13 起——见判据 2 的说明)。
FIREBASE_META_ABSENT = (
    "firebase_analytics_collection_enabled",
    "firebase_crashlytics_collection_enabled",
)

# 必须**没有任何引用**的文件(相对仓库根)。上游把这些加回来时,这里会直接报。
FIREBASE_FREE_FILES = (
    "app/build.gradle.kts",
    "build.gradle.kts",
    "gradle/libs.versions.toml",
)

# Kotlin 源码里不许出现的 import 前缀。
FIREBASE_IMPORT_PREFIX = "com.google.firebase"

# 扫 Kotlin 时的下限 —— 防「一个文件都没扫到 → 判据自动成立」。
MIN_KT_FILES = 300

# 索引下限(见模块注释)
MIN_PERMISSIONS = 10


class Problem(Exception):
    """解析失败 —— 与「发现问题」区分开。"""


def check(root: Path) -> list[str]:
    path = root / MANIFEST
    if not path.is_file():
        raise Problem("找不到 " + MANIFEST)
    raw = path.read_text(encoding="utf-8")
    try:
        root_el = ET.fromstring(raw)
    except ET.ParseError as exc:
        raise Problem(MANIFEST + " 不是合法 XML:" + str(exc))

    problems: list[str] = []

    # ── 判据 1:xmlns:tools ──
    if 'xmlns:tools="http://schemas.android.com/tools"' not in raw:
        problems.append(
            MANIFEST + ':没有声明 xmlns:tools —— tools:node / tools:targetApi 会让清单合并失败。'
        )

    app = root_el.find("application")
    if app is None:
        raise Problem(MANIFEST + " 里找不到 application 元素")

    # ── 判据 2:两条 firebase 采集开关 ──
    declared = {}
    for el in app:
        if el.tag == "meta-data":
            declared[el.get(A + "name")] = el.get(A + "value")
    # 结构下限。
    #
    # ⚠️ 原先这里守的是「application 下至少有一条 meta-data」—— 而 2026-09-13 移除 Firebase
    #    之后,那两条采集开关一走,**整个文件里一条 meta-data 都不剩了**,于是这条下限
    #    把检查器自己卡死。下限守的应该是「解析还认得这个文件的结构」,而不是「必须有
    #    meta-data」—— 后者随内容变化,不是结构稳定性。
    #    现在改成:必须有 `application` 元素**且**它带着 `android:name`(判据 5 要用的那个)
    #    —— 这才真是「结构还在」的证据。
    if app is None or app.get(A + "name") is None:
        raise Problem(
            MANIFEST + ":解析不出带 android:name 的 application 元素 —— 结构变了?"
            "这时**必须报错**,否则下面的判据会全部自动成立。"
        )
    # ── 判据 2:Firebase 运行时必须不在(反着守) ──
    for flag in FIREBASE_META_ABSENT:
        if flag in declared:
            problems.append(
                MANIFEST + ":还有 meta-data `" + flag + "` —— Firebase 运行时已移除,"
                "这两条也随之不该在(它们只关采集、不动能力;留着会造成「看起来已关」的错觉)。"
            )

    for rel in FIREBASE_FREE_FILES:
        target = root / rel
        if not target.is_file():
            continue
        text = target.read_text(encoding="utf-8")
        # ⚠️ **注释不算依赖**(2026-09-13 加)。判据守的是「有没有引入 Firebase 的依赖/插件」,
        #    而注释里解释「为什么不许引入」时必然要提到 firebase —— 注释不可能引入依赖,
        #    把它算进来只会逼着后来人不敢写清楚原因。
        text = re.sub(r"(?m)//.*$", "", text)
        text = re.sub(r"(?m)^\s*#.*$", "", text)
        # ⚠️ **一处刻意的例外**:把 firebase-components 钉版本(见 app/build.gradle.kts 那段注释)
        #    —— 它是 ML Kit 的传递依赖、只带 firebase-annotations,且**必须**定版本才能保住
        #    ComponentRegistrar 的无参构造(否则扫码坏)。故只放行这一个坐标,别的 firebase
        #    依赖照旧会被报出来。
        text = re.sub(r"(?m)^\s*(firebaseComponents\s*=.*|firebase-components\s*=\s*\{.*|"
                      r"implementation\(libs\.firebase\.components\))\s*$", "", text)
        for needle in ("firebase", "google-services", "crashlytics"):
            if needle in text.lower():
                problems.append(
                    rel + ":出现 `" + needle + "` —— Firebase 运行时已移除(2026-09-13),"
                    "上游 merge 可能把它带回来了。要真接自家 Firebase,先改本检查器的判据。"
                )

    kt_files = sorted((root / "app/src/main/java").rglob("*.kt"))
    if len(kt_files) < MIN_KT_FILES:
        raise Problem(
            "只扫到 " + str(len(kt_files)) + " 个 .kt(期望 ≥ " + str(MIN_KT_FILES) + ")—— "
            "扫描面写错了,「没有 firebase import」那条会永远成立。"
        )
    for path in kt_files:
        for i, line in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
            if line.lstrip().startswith("import ") and FIREBASE_IMPORT_PREFIX in line:
                problems.append(
                    str(path.relative_to(root)) + ":" + str(i) + ": 还有 Firebase 的 import"
                    " —— 依赖已移除,这行会编译不过(或说明上游把它带回来了)。"
                )

    # ── 判据 3 + 6:广告 ID 权限 ──
    perms = [el for el in root_el if el.tag == "uses-permission"]
    if len(perms) < MIN_PERMISSIONS:
        raise Problem(
            MANIFEST + ":只解析出 " + str(len(perms)) + " 条 uses-permission(期望至少 "
            + str(MIN_PERMISSIONS) + ")—— 解析式样或文件结构变了,必须报错。"
        )
    remove_set = {
        el.get(A + "name") for el in perms if el.get(T + "node") == "remove"
    }
    for name in AD_PERMISSIONS:
        if name not in remove_set:
            problems.append(
                MANIFEST + ":广告相关权限 `" + name + "` 没有 tools:node=\"remove\" —— "
                "依赖会把它带进合并后的清单,APK 里重新声明「读取广告 ID」。"
            )
    # 白名单式:任何名字里带 AD_ID / ADSERVICES 的权限都必须被 remove
    for el in perms:
        name = el.get(A + "name") or ""
        if ("AD_ID" in name or "ADSERVICES" in name) and el.get(T + "node") != "remove":
            problems.append(
                MANIFEST + ":权限 `" + name + "` 看着是广告/归因相关,却没标 remove —— "
                "依赖升版带入的新权限会从这里漏过去。"
            )

    # ── 判据 4:${appLabel} 占位符 ──
    label = app.get(A + "label") or ""
    if "${appLabel}" not in label:
        problems.append(
            MANIFEST + ':application 的 android:label 里没有 ${appLabel}(现值 `' + label
            + "`)—— 应用名不再按渠道取值,nightly 包会**静默**少了 Nightly 后缀。"
        )

    # ── 判据 5:RikkaHubApp 接线点 ──
    app_class = app.get(A + "name") or ""
    if not app_class.endswith("RikkaHubApp"):
        problems.append(
            MANIFEST + ':application 的 android:name 不再是 RikkaHubApp(现值 `' + app_class
            + "`)—— 诊断框架的接线(开关读回 / 会话目录 / logcat 捕获 / 域落盘 / 请求记录)"
            "全在它的 onCreate 里,换了类就**一条都不执行**,而 App 行为看着正常。"
        )

    return problems


def main(argv) -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--root", default=None, help="仓库根(默认取脚本所在目录的上一级)")
    args = ap.parse_args(argv[1:])
    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent

    try:
        problems = check(root)
    except Problem as exc:
        print("[FAIL] AndroidManifest 自检无法进行:" + str(exc))
        return 1

    if problems:
        print("[FAIL] AndroidManifest 的 X 定制自检发现 " + str(len(problems)) + " 处问题:")
        for p in problems:
            print("  - " + p)
        return 1

    print(
        "[CHECK PASS] AndroidManifest:Firebase 运行时不在(meta-data / 构建文件 / Kotlin import)、"
        "三条广告权限已 remove、应用名占位符与 RikkaHubApp 接线点都在"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
