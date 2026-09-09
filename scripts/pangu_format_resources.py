#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
盘古之白 · Android 资源字符串格式化脚本
========================================
把项目内中文 UI 文案(strings.xml 值)按「盘古之白」排版规则直接改写:
中文字符与 ASCII 字母/数字之间自动插入空格(如:图片URL → 图片 URL)。

逻辑忠实移植自 RikkaTune 的 Pangu.java
(com.vstory.hook.rikkahub.Pangu, 2026-09-09):
- CJK 基本区(0x4E00-0x9FFF) / 扩展A(0x3400-0x4DBF) / 兼容(0xF900-0xFAFF)
- 不处理 String.format 占位符(%s/%d/%1$s 内部,避免破坏格式化串)
- 幂等:已格式化文本再次运行零改动;新增文案重跑本脚本即可应用

用法:
    python3 scripts/pangu_format_resources.py             # 实际改写(默认简体 values-zh)
    python3 scripts/pangu_format_resources.py --dry-run   # 仅预览,不改文件
    python3 scripts/pangu_format_resources.py --all-langs # 含繁体 values-zh-rTW
    python3 scripts/pangu_format_resources.py --langs zh,zh-rTW,ja  # 指定语言目录后缀
    python3 scripts/pangu_format_resources.py --check     # 非零退出:有文本需要格式化(供 CI)

安全保护:
- 跳过 translatable="false" 的条目(技术名词/包名等保持原样)
- 跳过值内含 '<' 子标签(xliff/annotation 等)的条目,避免破坏
- 保留标签属性、注释、缩进、实体(&gt; 等)原样
"""

import argparse
import re
import sys
from pathlib import Path

# ============ 盘古之白核心(移植自 Pangu.java) ============

def is_cjk(c: str) -> bool:
    o = ord(c)
    return (0x4E00 <= o <= 0x9FFF) or (0x3400 <= o <= 0x4DBF) or (0xF900 <= o <= 0xFAFF)

def is_alnum(c: str) -> bool:
    return ('a' <= c <= 'z') or ('A' <= c <= 'Z') or ('0' <= c <= '9')

def in_format(index: int, text: str) -> bool:
    """判断 index 处字符是否位于 String.format 占位符的"非终点"部分。

    相对原版 Pangu.java 的增强:Java 格式串(如 %1$d)内唯一允许的字母是
    **转换符**(d/s/f/x/e/g…),它标志占位符结束;若 index 处恰是转换符字母
    (如 "%d天" 的 'd'),在其后插空格不影响占位符(渲染为 "7 天"),因此
    不再保护。
    """
    def ascii_alpha(ch: str) -> bool:
        return ('a' <= ch <= 'z') or ('A' <= ch <= 'Z')

    if index < 0 or text is None or index >= len(text) or len(text) == 0:
        return False
    if index == 0:
        return text[0] == '%'
    for j in range(index - 1, -1, -1):
        c = text[j]
        if c == '%':
            # 扫到占位符起点:index 处若为转换符字母则占位符已结束,放行
            return not ascii_alpha(text[index])
        if c.isdigit() or c in '$.-+ ':
            continue
        return False
    return False

def pangu(text: str) -> str:
    """中英文/数字之间插入空格;null/空输入原样返回。"""
    if text is None:
        return text
    n = len(text)
    if n == 0:
        return text
    out = []
    for i in range(n):
        c = text[i]
        out.append(c)
        if i + 1 < n:
            nx = text[i + 1]
            if (is_cjk(c) and is_alnum(nx)) or (is_alnum(c) and is_cjk(nx)):
                if not in_format(i, text):
                    out.append(' ')
    return ''.join(out)


# ============ XML / 文件处理 ============

STRING_RE = re.compile(r'(<string\b[^>]*>)(.*?)(</string>)', re.S)

def fix_entry(match: re.Match) -> str:
    """改写一个 <string> 条目;不可改的条目原样返回。"""
    tag_open, value, tag_close = match.group(1), match.group(2), match.group(3)
    # 跳过 translatable="false" 的条目
    if re.search(r'translatable\s*=\s*"false"', tag_open):
        return match.group(0)
    # 跳过含子标签(如 <xliff:g> / <annotation>)的值,避免破坏结构
    if '<' in value:
        return match.group(0)
    fixed = pangu(value)
    if fixed == value:
        return match.group(0)
    return tag_open + fixed + tag_close

def process_file(path: Path, dry_run: bool) -> tuple[int, int]:
    """改写单个 strings.xml,返回 (变更条数, 总条数)。"""
    original = path.read_text(encoding='utf-8')
    # 逐条处理并统计真实变更(re.subn 的计数是函数调用次数,不准确)
    parts: list[str] = []
    pos = 0
    total = 0
    changed = 0
    for m in STRING_RE.finditer(original):
        parts.append(original[pos:m.start()])
        new = fix_entry(m)
        parts.append(new)
        if new != m.group(0):
            changed += 1
        pos = m.end()
        total += 1
    parts.append(original[pos:])
    updated = ''.join(parts)
    if not dry_run and changed > 0:
        path.write_text(updated, encoding='utf-8')
    return changed, total


# ============ 入口 ============

def collect_files(langs: list[str]) -> list[Path]:
    """收集各模块 src/main/res/values-<lang>/strings.xml(lang 为空则 values/)。"""
    files: list[Path] = []
    # 从仓库根(脚本的上一级)开始,覆盖 app / common / oauth / search 等所有 Gradle 模块
    root = Path(__file__).resolve().parent.parent
    for res_dir in sorted(root.glob('*/src/main/res')):
        for lang in langs:
            target = res_dir / ('values' if not lang else f'values-{lang}') / 'strings.xml'
            if target.exists():
                files.append(target)
    return files

def main() -> int:
    ap = argparse.ArgumentParser(description='盘古之白:格式化 Android 中文资源字符串')
    ap.add_argument('--dry-run', action='store_true', help='仅预览,不改写文件')
    ap.add_argument('--all-langs', action='store_true',
                    help='处理所有 values-zh*(简体+繁体);默认仅 values-zh')
    ap.add_argument('--langs', default=None,
                    help='指定语言目录后缀,逗号分隔,如 zh,zh-rTW;默认 zh')
    ap.add_argument('--check', action='store_true',
                    help='检查模式:若有文本需要格式化则退出码 1(供 CI 用)')
    args = ap.parse_args()

    if args.langs is not None:
        langs = [x.strip() for x in args.langs.split(',') if x.strip()]
    elif args.all_langs:
        langs = ['zh', 'zh-rTW']
    else:
        langs = ['zh']

    files = collect_files(langs)
    if not files:
        print('未找到任何 strings.xml')
        return 1

    total_changed = 0
    need_fix_files = []
    for f in files:
        changed, total = process_file(f, dry_run=True)  # 先算一遍,决定 --check/报告
        if changed:
            need_fix_files.append((f, changed))
    total_changed = sum(c for _, c in need_fix_files)

    if args.check:
        if need_fix_files:
            print(f'[CHECK FAIL] {total_changed} 条文本需要盘古格式化:')
            for f, c in need_fix_files:
                print(f'  {f.relative_to(Path(__file__).resolve().parent.parent)}: {c} 条')
            return 1
        print('[CHECK PASS] 所有中文文案均已盘古格式化')
        return 0

    if args.dry_run:
        if not need_fix_files:
            print('无需改动(所有文本已是盘古格式)')
            return 0
        print(f'[DRY-RUN] 共 {len(files)} 个文件,{total_changed} 条文本将被格式化:')
        for f, c in need_fix_files:
            print(f'  {f.relative_to(Path(__file__).resolve().parent.parent)}: {c} 条')
        return 0

    # 实际改写
    real_changed = 0
    for f in files:
        changed, total = process_file(f, dry_run=False)
        real_changed += changed
        if changed:
            print(f'  ✓ {f.relative_to(Path(__file__).resolve().parent.parent)}: {changed} 条')
    print(f'完成:共改写 {real_changed} 条文本')
    return 0

if __name__ == '__main__':
    sys.exit(main())
