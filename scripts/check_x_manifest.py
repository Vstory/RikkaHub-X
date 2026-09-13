#!/usr/bin/env python3
"""AndroidManifest 的 X 定制自检 —— 守那些**被上游 merge 冲掉后静默恢复默认**的声明。

## 为什么需要它(这一份尤其)

`AndroidManifest.xml` 是**上游会持续改**的文件,而 X 在它上面挂了三件「丢了不报错、
只改行为」的事:

| 挂什么 | 丢了会怎样 |
|---|---|
| 两条 `firebase_*_collection_enabled=false` | 统计采集**静默恢复**(依赖还在、provider 还在) —— 只有抓包或看 Firebase 后台才发现 |
| 三条 `tools:node="remove"` 的广告 ID 权限 | 依赖又把权限带回来,APK 里重新声明「读取广告 ID」 —— 装的时候看不出来 |
| `${appLabel}` 占位符 | nightly 包的**应用名静默少了后缀**(`RikkaHub X` 而非 `RikkaHub X Nightly`) |
| `android:name=".RikkaHubApp"` | 诊断框架的接线点 `onCreate` **根本不会跑** —— 开关不落盘、logcat 一条不记,而 App 行为看着完全正常 |

⚠️ **`.kt` 之外的定制不在标签存活检查的射程内**(X Custom Audit 只扫 `*.kt` 且只在
`src/main`),故这一类只能靠本检查器 + 对账表登记。这正是文档里那条
「`app/src/main` 下非 `.kt` 文件 → ❌ → C 表登记 + 尽量补检查」的落地。

## 判据(逐条都对应上面一行)

1. `xmlns:tools` 已声明 —— 少了它,`tools:node` 与 `tools:targetApi` 会让**清单合并直接失败**;
2. 两条 `firebase_*_collection_enabled` 的 meta-data 存在,且值都是 `false`;
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

FIREBASE_FLAGS = (
    "firebase_analytics_collection_enabled",
    "firebase_crashlytics_collection_enabled",
)

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
    if len(declared) == 0:
        raise Problem(
            MANIFEST + ":application 下解析不出任何 meta-data —— 结构变了?"
            "这时**必须报错**,否则下面的判据会全部自动成立。"
        )
    for flag in FIREBASE_FLAGS:
        if flag not in declared:
            problems.append(
                MANIFEST + ":少了 meta-data `" + flag + "` —— 采集会**静默恢复**(实测 2026-09-13:"
                "占位配置下 Crashlytics 照样发 settings 请求)。"
            )
        elif declared[flag] != "false":
            problems.append(
                MANIFEST + ":`" + flag + "` 的值是 `" + str(declared[flag]) + "`,期望 `false`。"
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
        "[CHECK PASS] AndroidManifest:两条采集开关为 false、三条广告权限已 remove、"
        "应用名占位符与 RikkaHubApp 接线点都在"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
