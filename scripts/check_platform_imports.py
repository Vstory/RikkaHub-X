#!/usr/bin/env python3
"""平台类 import 自检：`java.*` / `javax.*` / `android.*` 的包名是否真实存在。

## 为什么需要它（2026-09-11 血泪）

给写入路径加流式哈希时我写了：

    import java.io.DigestOutputStream        // 错！它在 java.security

本地六项检查**全部通过**，CI 却在 `:app:compileDebugKotlin` 直接红：

    e: AssetWritePath.kt:… Unresolved reference: DigestOutputStream

于是白花掉一次 CI 往返（本仓库本地不跑构建，构建全交 CI）。

**为什么现有检查抓不到**：
- `check_missing_imports.py` 只查**项目内类型**有没有 import，不查 JDK/Android 类的**包名**；
- 语法自检查的是括号/字符串，**不认识类名**；
- 符号自检只索引**我们自己的类型**。

三者合起来留下的缺口正是：**import 了一个包名写错的平台类**。本脚本补上它。

## 它怎么做到不联网、不依赖 Gradle

平台类清单就在本地，而且都是 zip：

| 来源 | 位置 | 规模 |
|---|---|---|
| JDK | `$JAVA_HOME/jmods/*.jmod` | 70 个模块 |
| Android | `platforms/<api>/android.jar` | 约 5700 个类 |

Android 版本**从 `compileSdk` 读**，不猜：用错版本会让「在 37 里已删的类」被误判为存在，
而猜错的失败模式是静默的。索引结果缓存到 `/tmp`，同机重复运行只付一次构建成本。

## 判据与取舍

- **只报「这个包路径下查无此类」**。`androidx.*` 来自 Gradle 依赖、本地无索引 →
  **整体跳过，不猜**。
- **嵌套类**：Kotlin 写 `java.util.Map.Entry`，字节码里是 `Map$Entry` → 两种都试，
  避免把正确写法误报。
- **其余根包一律跳过**（`kotlinx.*` / `org.*` / `me.*` …）—— 宁可漏报，不可误报。

## 用法

    python3 scripts/check_platform_imports.py              # 默认扫 app/src
    python3 scripts/check_platform_imports.py app ai       # 指定根

退出码：0 = 通过，1 = 有查不到的类。
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
import zipfile

# 只在有把握的根包上判定；其余（androidx / kotlinx / 第三方）无本地索引，一律跳过
CHECKED_ROOTS = ("java.", "javax.", "android.")

# `javax.` 不是「JDK 专属」命名空间：`javax.jmdns`（第三方 jmdns）、`javax.inject` 等
# 都住在它下面，硬查 JDK 索引会**误报一片**。故对 javax 先做「命名空间存在性」判定：
# JDK 里根本没有 `javax.<第二个包段>` 时，这条 import 来自第三方 → 跳过不报。
# 代价是 `javax.crypo.Cipher` 这类**把 JDK 命名空间拼错**的写法会漏报；
# 权衡后接受 —— 误报会让人不再信任检查，漏报只是少抓一种小众错法。
NAMESPACE_GATED_ROOTS = ("javax.",)

IMPORT_LINE = re.compile(r"^\s*import\s+([A-Za-z_][\w.]*)\s*(?:as\s+\w+)?\s*$")

COMPILE_SDK_LINE = re.compile(r"^\s*compileSdk\s*=\s*(\d+)", re.M)

CACHE_PATH = "/tmp/x-platform-classes.json"


def index_from_zip(path: str) -> set[str]:
    """zip（jar / jmod）→ 全限定类名集合。"""
    names: set[str] = set()
    try:
        with zipfile.ZipFile(path) as archive:
            for entry in archive.namelist():
                if not entry.endswith(".class"):
                    continue
                # jmod 带 classes/ 前缀，jar 是直接路径
                if entry.startswith("classes/"):
                    entry = entry[len("classes/"):]
                name = entry[:-len(".class")].replace("/", ".")
                names.add(name)
                names.add(name.replace("$", "."))
    except (OSError, zipfile.BadZipFile):
        return set()
    return names


def android_roots() -> list[str]:
    candidates = [
        os.environ.get("ANDROID_HOME") or "",
        os.environ.get("ANDROID_SDK_ROOT") or "",
        "/workspace/tools/android-sdk",
    ]
    return [path for path in candidates if path and os.path.isdir(path)]


def compile_sdk() -> int | None:
    """从 `app/build.gradle.kts` 读 compileSdk —— 用错平台版本会静默放过真错误。"""
    for path in ("app/build.gradle.kts", "build.gradle.kts"):
        try:
            with open(path, encoding="utf-8") as handle:
                match = COMPILE_SDK_LINE.search(handle.read())
        except OSError:
            continue
        if match:
            return int(match.group(1))
    return None


def platform_jar() -> str | None:
    """挑出与 `compileSdk` 对应的 `android.jar`；读不到就退回可用的最高版本。"""
    for root in android_roots():
        jars = sorted(glob.glob(os.path.join(root, "platforms", "*", "android.jar")))
        if not jars:
            continue
        wanted = compile_sdk()
        if wanted is not None:
            # 目录名形如 android-37 / android-37.0 —— 按数字前缀匹配，取最具体的一个
            exact = [j for j in jars if re.search(rf"android-{wanted}(\.\d+)?/android\.jar$", j)]
            if exact:
                return exact[-1]
        return jars[-1]
    return None


def discover_sources() -> list[str]:
    """
    JDK 与 Android 平台的 zip 清单。

    **刻意不写死路径**：换机器、换 JDK 发行版、换 SDK 版本都不该让检查失效 ——
    失效的检查比没有检查更糟，它会让人以为验过了。
    """
    paths: list[str] = []
    java_home = os.environ.get("JAVA_HOME") or "/usr/lib/jvm/java-17-openjdk-arm64"
    paths.extend(sorted(glob.glob(os.path.join(java_home, "jmods", "*.jmod"))))
    jar = platform_jar()
    if jar:
        paths.append(jar)
    return paths


def build_index(sources: list[str]) -> set[str]:
    if os.path.exists(CACHE_PATH):
        try:
            with open(CACHE_PATH, encoding="utf-8") as handle:
                cached = json.load(handle)
            if cached.get("sources") == sources:
                return set(cached["classes"])
        except (OSError, ValueError, KeyError):
            pass

    classes: set[str] = set()
    for path in sources:
        classes |= index_from_zip(path)

    try:
        with open(CACHE_PATH, "w", encoding="utf-8") as handle:
            json.dump({"sources": sources, "classes": sorted(classes)}, handle)
    except OSError:
        pass
    return classes


def kotlin_files(roots: list[str]) -> list[str]:
    found: list[str] = []
    for root in roots:
        if not os.path.isdir(root):
            continue
        for path in glob.glob(os.path.join(root, "**", "*.kt"), recursive=True):
            if "/build/" not in path.replace("\\", "/"):
                found.append(path)
    return sorted(set(found))


def jdk_namespaces(classes: set[str]) -> set[str]:
    """JDK 索引里出现过的 `javax.<段>` 命名空间（用于挡掉第三方占用 javax 的情形）。"""
    spaces: set[str] = set()
    for name in classes:
        if name.startswith("javax."):
            parts = name.split(".")
            if len(parts) >= 2:
                spaces.add(f"javax.{parts[1]}.")
    return spaces


def resolves(full: str, classes: set[str]) -> bool:
    """这条全限定名能否落到索引里的某个类型上。

    两种合法写法都要认：

    ① **类型本身**：`java.security.MessageDigest`。
    ② **导入成员**：Kotlin 允许直接导入枚举项或伴生常量，如
       `java.nio.file.StandardCopyOption.REPLACE_EXISTING` —— 末段是成员不是类型。
       判据是「去掉末段后是个真实类型」。若不认这一种，就会出现成片误报
       （实测确有此类 import），而误报会让人不再信任检查。
    """
    if full in classes:
        return True
    parent = full.rsplit(".", 1)[0]
    return parent in classes


def should_check(full: str, classes: set[str], namespaces: set[str]) -> bool:
    """这条 import 该不该判定。"""
    if not full.startswith(CHECKED_ROOTS):
        return False
    for gated in NAMESPACE_GATED_ROOTS:
        if full.startswith(gated):
            parts = full.split(".")
            candidate = f"{parts[0]}.{parts[1]}." if len(parts) >= 2 else gated
            # JDK 里压根没有这个命名空间 → 它属于第三方，不在我们的索引范围内
            return candidate in namespaces
    return True


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="平台类 import 自检")
    parser.add_argument("roots", nargs="*", default=["app/src"], help="源码根（默认 app/src）")
    args = parser.parse_args(argv)

    sources = discover_sources()
    if not sources:
        print("[错误] 找不到 JDK / Android 平台索引 —— 检查会全部通过，形同虚设", file=sys.stderr)
        return 1

    classes = build_index(sources)
    if not classes:
        print("[错误] 平台类索引为空 —— 检查会全部通过，形同虚设", file=sys.stderr)
        return 1

    files = kotlin_files(args.roots)
    namespaces = jdk_namespaces(classes)
    checked = 0
    problems: list[str] = []

    for path in files:
        try:
            with open(path, encoding="utf-8") as handle:
                lines = handle.readlines()
        except OSError:
            continue
        for number, line in enumerate(lines, 1):
            match = IMPORT_LINE.match(line)
            if not match:
                continue
            full = match.group(1)
            if not should_check(full, classes, namespaces):
                continue
            checked += 1
            if not resolves(full, classes):
                problems.append(f"{path}:{number}: import {full} —— 该包下查无此类")

    jar = platform_jar()
    sdk = compile_sdk()
    print(
        f"平台 import 自检:索引 {len(classes)} 个类"
        f"（JDK {len([s for s in sources if s.endswith('.jmod')])} 模块"
        f" + android{'SDK' + str(sdk) if sdk else ''} {os.path.basename(jar) if jar else '无'}）,"
        f"检查 {len(files)} 个文件里 {checked} 条平台 import"
    )
    if not problems:
        print("✅ 无问题")
        return 0

    print()
    for item in problems:
        print(f"❌ {item}")
    print()
    print("包名写错会让编译在 CI 阶段直接失败（本仓库本地不跑构建，一次往返很贵）。")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
