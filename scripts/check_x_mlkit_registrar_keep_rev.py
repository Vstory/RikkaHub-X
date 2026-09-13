#!/usr/bin/env python3
"""`check_x_mlkit_registrar_keep.py` 的反向验证(进 CI)。

判据:**每种破坏都必须被报出来;修复后的真实状态必须通过;读数坏了必须中止。**

mapping 那份用手搓样本 —— 真 mapping 只在 daily-build 里存在,而「没被验证过能报错的
检查不算检查」。样本里刻意放 **1000 个填充类**:检查器有「类数不足就中止」的下限,
不放够就分不清「中止」和「通过」。
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent
CHECKER = SRC / "scripts" / "check_x_mlkit_registrar_keep.py"
TOML = "gradle/libs.versions.toml"
GRADLE = "app/build.gradle.kts"
REGISTRARS = (
    "com.google.mlkit.common.internal.CommonComponentRegistrar",
    "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
    "com.google.mlkit.vision.barcode.internal.BarcodeRegistrar",
)


def replace_once(text, old, new):
    assert old in text, f"找不到锚点 `{old[:70]}`"
    out = text.replace(old, new, 1)
    assert out != text, "变异没生效"
    return out


def fake_mapping(path: Path, with_init: set, present: set) -> None:
    """手搓 mapping:含 1000 个填充类,registrar 按参数决定在不在 / 有没有 <init>。"""
    lines = []
    for i in range(1000):
        lines.append(f"com.example.Filler{i} -> a.b.c{i}:")
        lines.append("    int x -> a")
    for r in REGISTRARS:
        if r in present:
            lines.append(f"{r} -> {r}:")
            if r in with_init:
                lines.append("    void <init>() -> <init>()")
            lines.append("    java.util.List getComponents() -> a")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    ok = True
    with tempfile.TemporaryDirectory(prefix="x-mlkit-rev-") as tmp:
        root = Path(tmp)
        for rel in (TOML, GRADLE):
            dest = root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(SRC / rel, dest)
        toml0 = (SRC / TOML).read_text(encoding="utf-8")
        gradle0 = (SRC / GRADLE).read_text(encoding="utf-8")

        def run(extra=()):
            return subprocess.run([sys.executable, str(CHECKER), "--root", str(root), *extra],
                                  capture_output=True, text=True)

        def case(name, mutate, expect, extra=()):
            nonlocal ok
            try:
                mutate()
            except AssertionError as exc:
                print(f"  ⚠️  {name} → 变异失败,未验证:{exc}")
                ok = False
                return
            r = run(extra)
            text = r.stdout + r.stderr
            first = text.strip().split("\n")[0][:105]
            if expect == "fail":
                if "无法进行" in text:
                    print(f"  ⚠️  {name} → 中止(不算报出):{first}"); ok = False
                elif r.returncode != 0:
                    print(f"  ✅ {name} → 被报出:{first}")
                else:
                    print(f"  ❌ {name} → 仍然通过,检查器失效!"); ok = False
            elif expect == "pass":
                if r.returncode == 0:
                    print(f"  ✅ {name} → 通过(正确)")
                else:
                    print(f"  ❌ {name} → 被误报:{first}"); ok = False
            else:  # abort
                if r.returncode != 0 and "无法进行" in text:
                    print(f"  ✅ {name} → 中止(正确):{first}")
                else:
                    print(f"  ❌ {name} → 没中止 —— 会变成「永远通过」:{first}"); ok = False
            # 复位
            (root / TOML).write_text(toml0, encoding="utf-8")
            (root / GRADLE).write_text(gradle0, encoding="utf-8")

        print("── 静态判据")
        case("未改动", lambda: None, "pass")
        case("钉版整行被删", lambda: (root / TOML).write_text(
            replace_once(toml0, 'firebaseComponents = "19.0.0"', 'ff = "19.0.0"'), encoding="utf-8"), "fail")
        case("版本被降到 16.x(等于没修)", lambda: (root / TOML).write_text(
            replace_once(toml0, 'firebaseComponents = "19.0.0"', 'firebaseComponents = "16.1.0"'),
            encoding="utf-8"), "fail")
        case("implementation 被删(只留版本号)", lambda: (root / GRADLE).write_text(
            replace_once(gradle0, "    implementation(libs.firebase.components)", ""),
            encoding="utf-8"), "fail")

        print("\n── 产物判据(mapping,手搓样本)")
        good = root / "good.txt"
        fake_mapping(good, with_init=set(REGISTRARS), present=set(REGISTRARS))
        case("三个 registrar 都带 <init>", lambda: None, "pass", ("--mapping", str(good)))

        noinit = root / "noinit.txt"
        fake_mapping(noinit, with_init=set(), present=set(REGISTRARS))
        case("构造被削掉(=真实故障形态)", lambda: None, "fail", ("--mapping", str(noinit)))

        missing = root / "missing.txt"
        fake_mapping(missing, with_init=set(REGISTRARS), present=set(REGISTRARS[:1]))
        case("两个 registrar 整类被删", lambda: None, "fail", ("--mapping", str(missing)))

        tiny = root / "tiny.txt"
        tiny.write_text("com.example.A -> a:\n    int x -> a\n", encoding="utf-8")
        case("mapping 太小(读错文件?)", lambda: None, "abort", ("--mapping", str(tiny)))

    print("\n反向验证全部通过" if ok else "\n有未通过项 —— 先修检查器")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
