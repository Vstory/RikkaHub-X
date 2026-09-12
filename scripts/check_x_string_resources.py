#!/usr/bin/env python3
"""字符串资源自检 —— 拦 aapt2 会拒收、而纯 XML 解析看不出来的写法。

## 为什么需要它

2026-09-12:`values/strings.xml` 里一条文案写成 `The app's full log`,撇号没转义。
XML 层面完全合法(用 ElementTree 解析通过),但 aapt2 报
`does not contain a valid string resource`,`:app:mergeDebugResources` 失败,
**X Custom Guard 与 nightly 构建双双中断**。

当时本地已经跑过一轮「扫 strings」,用的是 XML 解析 —— 于是这一条漏过去了。
**能秒级判定的规则不该等到 CI 才报**:这个检查器把 aapt2 那条规则前移到这里,
本地 0.1 秒就能发现。

## 判据(刻意收窄)

只查两条**确定无疑**的规则,宁可漏也不误报 —— 误报会被容忍到「没人再信这个检查」。

1. **字符串正文里未转义的撇号**。Android 要求写成 `\\'`;`&#39;` / `&apos;` 是等价的
   实体写法,一律放行(那是合法的,报它就是误报)。
2. **同一文件里重复的 `name`**。aapt2 会因此报重复资源,而人工合并文案时很容易撞。

刻意**不查**:正文以 `@` / `?` 开头(那可以是合法的资源引用)、`%` 占位符个数
(与调用点比对需要解析 Kotlin,判据不够确定)、`"` 的转义(aapt2 在现行写法下接受)。

## 用法

    python3 scripts/check_x_string_resources.py [文件...]

不带参数时扫描**所有模块**的 `values*/strings.xml`。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# 找的是 <string name="..."> 正文 </string>。正文用非贪婪,跨行。
STRING_RE = re.compile(
    r'<string\b(?P<attrs>[^>]*?)\bname="(?P<name>[^"]+)"[^>]*?>(?P<body>.*?)</string>',
    re.DOTALL,
)

# 未转义的撇号:前面不是反斜杠。
# `&#39;` / `&apos;` 是合法实体写法,故先把它们摘出去再判。
APOSTROPHE_RE = re.compile(r"(?<!\\)'")
ENTITY_APOSTROPHE = ("&#39;", "&#x27;", "&apos;")

# 扫描面:所有模块的 values / values-zh / values-zh-rTW …
DEFAULT_GLOB = "*/src/main/res/values*/strings.xml"

# 索引下限。**低于它就报错退出而不是「通过」** —— 否则 glob 写错(比如模块目录改名)
# 会让检查器扫到 0 个文件然后永远绿灯,比不检查更坏。
MIN_FILES = 5
MIN_STRINGS = 200


def strip_entities(body: str) -> str:
    """把合法的撇号实体写法换成占位符,免得被判成未转义。"""
    out = body
    for ent in ENTITY_APOSTROPHE:
        out = out.replace(ent, "\x00")
    return out


def check_file(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    problems: list[str] = []
    seen: dict[str, int] = {}

    matches = list(STRING_RE.finditer(text))
    for m in matches:
        name = m.group("name")
        body = m.group("body")
        line = text.count("\n", 0, m.start()) + 1

        # 判据 2:重复 name
        if name in seen:
            problems.append(
                f"{path}:{line}: 重复的字符串名 '{name}'(首次出现在第 {seen[name]} 行)"
            )
        else:
            seen[name] = line

        # 判据 1:未转义的撇号
        if APOSTROPHE_RE.search(strip_entities(body)):
            snippet = body.strip().replace("\n", " ")
            if len(snippet) > 60:
                snippet = snippet[:60] + "…"
            problems.append(
                f"{path}:{line}: '{name}' 正文里有未转义的撇号 —— "
                f"aapt2 会拒收。写成 \\' 或 &#39;。正文: {snippet}"
            )

    return problems


def main(argv: list[str]) -> int:
    explicit = len(argv) > 1
    if explicit:
        files = [Path(a) for a in argv[1:]]
        missing = [f for f in files if not f.is_file()]
        if missing:
            print(f"[FAIL] 指定的文件不存在: {missing}")
            return 1
    else:
        files = sorted(Path(".").glob(DEFAULT_GLOB))
        # 索引下限**只在默认扫描时**适用:它防的是「glob 写错 → 扫到 0 个文件 → 永远绿灯」。
        # 显式传文件是拿来单点核查(如把某版历史文件喂进来做反向验证)的,不受此限。
        if len(files) < MIN_FILES:
            print(
                f"[FAIL] 只找到 {len(files)} 个 strings.xml(期望至少 {MIN_FILES} 个,"
                f"扫描式样 {DEFAULT_GLOB})。\n"
                f"       索引过小说明扫描面写错了 —— 这时**必须报错**而不是通过,"
                f"否则检查器会永远绿灯。"
            )
            return 1

    total_strings = 0
    problems: list[str] = []
    for f in files:
        problems.extend(check_file(f))
        total_strings += len(STRING_RE.findall(f.read_text(encoding="utf-8")))

    if not explicit and total_strings < MIN_STRINGS:
        print(
            f"[FAIL] {len(files)} 个文件里只解析出 {total_strings} 条 string"
            f"(期望至少 {MIN_STRINGS})—— 解析式样可能已与实际格式不符。"
        )
        return 1

    if problems:
        print(f"[FAIL] 字符串资源自检发现 {len(problems)} 处问题:")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(
        f"[CHECK PASS] {len(files)} 个文件 / {total_strings} 条 string:"
        f"无未转义撇号、无重名"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
