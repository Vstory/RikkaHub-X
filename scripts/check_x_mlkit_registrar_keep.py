#!/usr/bin/env python3
"""守住「ML Kit 的组件注册还能工作」—— 一次真机故障的回归哨兵。

## 那次故障(2026-09-13,已定位)

扫码弹 `QRError(NullPointerException: ... zzg.zza on a null object reference in
getClient(BarcodeScannerOptions))`。

用真机日志 + 产物双向确认的链条:

1. ML Kit 的组件注册**靠反射** —— manifest 的 meta-data 里写着三个 registrar 的类名,
   运行时 `getDeclaredConstructor().newInstance()` 实例化;
2. 「保类名 + 保无参构造」靠的是 **consumer 规则**:
       -keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
   而这条规则**只在 firebase-components 19.x 里有**,16.x 没有;
3. 19.x 此前是**被 firebase-common 拉高的**(22.2.0 → components 19.0.0)。移除
   firebase-common 后解析掉回 ML Kit 要求的下限 **16.1.0** → 规则消失 → R8 把三个
   registrar 的 `<init>` 削掉;
4. 启动时 `ComponentDiscovery: Invalid component registrar` +
   `NoSuchMethodException: <init> []`(真机日志里抓到的原话);
5. 组件注册不进去 → `MlKitContext.get(zzg.class)` 返回 null(Firebase 的
   `ComponentRuntime.get` 找不到就返回 null,不抛)→ 读 `null.zza` → NPE。

⚠️ **这一路不崩、不报错,只在 logcat 留三行 W** —— 没有这道哨兵,它就会一直静默坏着。

## 判据

1. **钉版本还在**(静态,随时可跑):`gradle/libs.versions.toml` 里
   `firebaseComponents` 必须存在且 **>= 19.0.0**(那条 keep 规则从 19.x 起才有),
   且 `app/build.gradle.kts` 必须真的用上它。少了任何一半 → 规则又会消失。
2. **产物里 `<init>` 真的还在**(需要 R8 的 `mapping.txt`,由 daily-build 传):
   ML Kit 那三个 registrar 的映射条目下必须有 `void <init>()`。
   若 R8 削掉了构造,mapping 里就不会有这一行 —— 这正是要拦的。
   另:三个 registrar **必须都出现在 mapping 里**(整类被删也是同一个故障)。

## 用法

    python3 scripts/check_x_mlkit_registrar_keep.py [--mapping <mapping.txt>]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

TOML = "gradle/libs.versions.toml"
GRADLE = "app/build.gradle.kts"

# 那条 keep 规则从 19.x 起才有(实测 19.0.0 有、16.1.0 无)
MIN_COMPONENTS_MAJOR = 19

# manifest 的 meta-data 里写死的三个 registrar(见 AndroidManifest / mlkit 的 AAR)
REGISTRARS = (
    "com.google.mlkit.common.internal.CommonComponentRegistrar",
    "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
    "com.google.mlkit.vision.barcode.internal.BarcodeRegistrar",
)

# 索引下限:低于这个就说明读的不是真的 mapping
MIN_MAPPING_CLASSES = 1000


class Problem(Exception):
    """无法进行 —— 与「发现问题」区分开。"""


def check_static(root: Path) -> list:
    problems = []
    toml = root / TOML
    gradle = root / GRADLE
    if not toml.is_file():
        raise Problem(f"找不到 {TOML}")
    if not gradle.is_file():
        raise Problem(f"找不到 {GRADLE}")

    t = toml.read_text(encoding="utf-8")
    m = re.search(r'(?m)^\s*firebaseComponents\s*=\s*"(\d+)\.(\d+)\.(\d+)"', t)
    if m is None:
        problems.append(
            f"{TOML}:找不到 `firebaseComponents` 钉版 —— 它一没,firebase-components 会掉回"
            f" ML Kit 要求的下限(16.x),而「保 ComponentRegistrar 无参构造」那条 consumer"
            f" 规则 16.x 没有 → R8 削掉 `<init>` → ML Kit 组件注册失败 → 扫码 NPE。"
        )
    else:
        major = int(m.group(1))
        if major < MIN_COMPONENTS_MAJOR:
            problems.append(
                f"{TOML}:`firebaseComponents` = {m.group(0).split('\"')[1]} < {MIN_COMPONENTS_MAJOR}.x"
                f" —— 那条 keep 规则从 {MIN_COMPONENTS_MAJOR}.x 起才有,低于它等于没修。"
            )

    g = gradle.read_text(encoding="utf-8")
    if not re.search(r"(?m)^\s*implementation\(libs\.firebase\.components\)", g):
        problems.append(
            f"{GRADLE}:钉了版本但没有 `implementation(libs.firebase.components)` ——"
            f" 只写版本号不引用,依赖解析根本不会拿到它。"
        )
    return problems


def check_mapping(root: Path, mapping: Path) -> list:
    if not mapping.is_file():
        raise Problem(f"找不到 mapping:`{mapping}`")
    problems = []

    # mapping 的格式:类头 `orig -> renamed:` 缩进若干,成员行再缩进
    classes: dict[str, list] = {}
    current = None
    for line in mapping.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        if not line.startswith(" ") and "->" in line:
            current = line.split("->")[0].strip()
            classes[current] = []
        elif current is not None:
            classes[current].append(line.strip())

    if len(classes) < MIN_MAPPING_CLASSES:
        raise Problem(
            f"mapping 里只解析出 {len(classes)} 个类(期望 ≥ {MIN_MAPPING_CLASSES})——"
            f" 读错文件了?这时「registrar 有没有 <init>」会永远成立。"
        )

    for name in REGISTRARS:
        members = classes.get(name)
        if members is None:
            problems.append(
                f"{name}:**整个类都不在 mapping 里** —— R8 把它删了(或改名了)。"
                f"ML Kit 靠 manifest 的 meta-data 按类名反射实例化它,删掉就等于组件注册不上。"
            )
            continue
        if not any(re.match(r"void <init>\(\)\s*->", mm) for mm in members):
            problems.append(
                f"{name}:映射里**没有 `void <init>()`** —— 无参构造被 R8 削掉了。"
                f"运行时就是这一条导致 `ComponentDiscovery: Invalid component registrar`"
                f"(NoSuchMethodException: <init> [])→ 组件注册失败 → 扫码 NPE。"
                f"处理:确认 firebase-components 钉在 19.x(见 {TOML})。"
            )
    return problems


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--root", default=None)
    ap.add_argument("--mapping", default=None, help="R8 mapping.txt 路径(给了才跑产物判据)")
    args = ap.parse_args(argv[1:])
    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent

    try:
        problems = check_static(root)
        if args.mapping:
            problems += check_mapping(root, Path(args.mapping).resolve())
    except Problem as exc:
        print(f"[FAIL] ML Kit 组件注册自检无法进行:{exc}")
        return 1

    if problems:
        print(f"[FAIL] ML Kit 组件注册自检发现 {len(problems)} 处:")
        for p in problems:
            print(f"  - {p}")
        return 1

    scope = "钉版还在 + 三个 registrar 的 `<init>` 都保住了" if args.mapping else "钉版还在"
    print(f"[CHECK PASS] ML Kit 组件注册:{scope}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
