#!/usr/bin/env python3
"""X 存储层结构与升级自检：把 DDL 与升级语句**真的执行一遍**。

## 为什么需要它（2026-09-11，同一天两次栽在同一句话上）

X 表不是 Room 实体，DDL 与升级语句都是手写字符串。既有的 `XStorageSchemaTest`
只断言**字符串内容**（都带 `IF NOT EXISTS`、表名与常量一一对应…），
**从不执行** —— 「语句本身能不能跑」在整条流水线上没有任何保障。

两次事故都从这里溜过去：

1. **建表从未生效**：`onOpen` 回调里嵌套事务抛异常，被 `runCatching` 吞掉。
2. **全新库上跑了升级语句**：meta 缺失被当成版本 0 → 跑 v2/v3 升级 → 可那几张表
   刚被建成为**最新形态** → `INSERT ... SELECT not_before` 撞上「无此列」→
   整笔事务回滚，表一张没建。表现为同一张图在 A/B 会话各存一份。

## 场景（任一失败即退出码 1）

| 场景 | 验什么 |
|---|---|
| **A 全新库** | 建表语句可执行；6 张表与索引全部建出 |
| **B 全新库 + 门控** | **升级语句一句都不该跑**（表已是新形态，没有旧标志列） |
| **C v1 旧库 → v2** | 造一张 v1 形态的表并塞数据，跑升级 → 数据搬过来、旧列消失、索引重建 |
| **D v2 旧库 → v3** | 同上，验 `not_before` → `first_unreferenced_at` 的迁移 |
| **E 门控在实现里** | `ensure` 必须调用 `needsRebuildTo`（见下） |

场景 C/D 是**升级链第一次被真正执行** —— 此前没人验过它的列名对不对。

## 关于场景 B/E 的诚实说明

B 的「门控」是**照 Kotlin 的规则镜像**过来的，判据取 Kotlin 源码里的标志列常量
（`LEGACY_*_MARKER`，单一来源）。镜像有分家的风险，故加场景 E：
断言 `ensure` 里确实调用了 `needsRebuildTo` —— 实现若去掉门控，E 立刻报错。

## 已知边界

用 python 自带 SQLite，与 App 内的 requery SQLite 版本可能不同：
能抓语法/列名/约束错误，**抓不到版本特有的行为差异**。

## 用法

    python3 scripts/check_x_storage_ddl.py
"""

import pathlib
import re
import sqlite3
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TABLES_KT = ROOT / "app/src/main/java/me/rerere/rikkahub/x/storage/XStorageTables.kt"
SCHEMA_KT = ROOT / "app/src/main/java/me/rerere/rikkahub/x/storage/XStorageSchema.kt"

TOPP = "XStorageTables"
MIN_STATEMENTS = 10


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def load_constants() -> tuple[dict[str, str], list[str]]:
    """XStorageTables 的常量 + 声明的表名列表。"""
    raw = TABLES_KT.read_text(encoding="utf-8")
    consts: dict[str, str] = {}
    outer = None
    for line in strip_comments(raw).split("\n"):
        m_obj = re.match(r"^\s*object\s+(\w+)\s*\{", line)
        if m_obj:
            outer = m_obj.group(1)
        m_val = re.match(r'^\s*const val (\w+) = "([^"]*)"', line)
        if m_val and outer:
            name, value = m_val.group(1), m_val.group(2)
            if outer == TOPP:
                consts[f"{TOPP}.{name}"] = value
            else:
                consts[f"{TOPP}.{outer}.{name}"] = value
                consts[f"{outer}.{name}"] = value

    all_block = re.search(r"val ALL: List<String> = listOf\(([^)]*)\)", raw)
    tables: list[str] = []
    if all_block:
        for token in all_block.group(1).split(","):
            token = token.strip()
            if token and f"{TOPP}.{token}" in consts:
                tables.append(consts[f"{TOPP}.{token}"])
    return consts, tables


def load_schema_consts() -> dict[str, str]:
    """XStorageSchema 里的字面量常量：LEGACY_* 临时名、*_MARKER 标志列。"""
    return {
        m.group(1): m.group(2)
        for m in re.finditer(r'private const val (\w+) = "([^"]*)"', SCHEMA_KT.read_text(encoding="utf-8"))
    }


def resolve(text: str, consts: dict[str, str], local: dict[str, str]) -> str:
    """把 ${XStorageTables.Xxx.Yyy} 与 $LEGACY_XXX 换成字面值。"""
    def sub(m: re.Match) -> str:
        key = m.group(1) or m.group(2)
        if key in local:
            return local[key]
        candidates = [key, key.split(".", 1)[1]] if key.startswith(TOPP + ".") else [key]
        for candidate in candidates:
            if candidate in consts:
                return consts[candidate]
        raise KeyError(key)

    return re.sub(r"\$\{([^}]+)\}|\$(\w+)", sub, text)


def block_of(schema: str, name: str) -> str:
    m = re.search(rf"(?:private )?val {name}\b.*?(?=\n    (?:private val|val|/\*|fun ))", schema, re.S)
    return m.group(0) if m else ""


def collect_list(schema: str, body: str) -> list[tuple[str, str]]:
    """
    把一段 `listOf(...)` 按**书写顺序**翻译成语句列表。

    ⚠️ 顺序不能丢：建索引的语句必须排在它那张表的建表语句**之后**。
    第一版把三引号块统一挪到末尾，索引就跑到了表前面，报出一片
    「no such table」的假阳性。
    """
    def merge_concat(text: str) -> str:
        return re.sub(r'"\s*\+\s*"', "", text)  # "a" + "b" → "ab"

    out: list[tuple[str, str]] = []
    i, n = 0, len(body)
    while i < n:
        char = body[i]
        if char in " \t\r\n,":
            i += 1
            continue

        if body.startswith('"""', i):
            close = body.index('"""', i + 3)
            out.append(("block", body[i + 3:close]))
            i = close + 3
            continue

        if char == '"':
            j = i + 1
            parts: list[str] = []
            while True:
                k = body.index('"', j)
                parts.append(body[j:k])
                j = k + 1
                cont = re.match(r'\s*\+\s*"', body[j:])
                if cont:
                    j += cont.end()
                    continue
                break
            out.append(("inline", "".join(parts)))
            i = j
            continue

        m = re.match(r"[^\n,]+", body[i:])
        token = m.group(0).strip()
        i += m.end()
        if not token or token.startswith("."):
            continue
        if "_INDEX_STATEMENTS" in token:
            group = token.replace("*", "").split(".")[0].strip()
            for lit in re.findall(r'"([^"\n]*)"', merge_concat(block_of(schema, group))):
                out.append((group, lit))
        elif token.endswith("_DDL"):
            for lit in re.finditer(r'"""(.*?)"""', block_of(schema, token), re.S):
                out.append((token, lit.group(1)))
        # 其它表达式（不认识）忽略：宁可漏，不可误报
    return out


def parse_create_statements(schema: str) -> list[tuple[str, str]]:
    body = re.search(r"val CREATE_STATEMENTS: List<String> = listOf\((.*?)\n    \)", schema, re.S)
    return collect_list(schema, body.group(1)) if body else []


def parse_upgrade_statements(schema: str, version: int) -> list[tuple[str, str]]:
    m = re.search(rf"\n        {version} -> listOf\((.*?)\n        \)", schema, re.S)
    return collect_list(schema, m.group(1)) if m else []


def columns(con: sqlite3.Connection, table: str) -> set[str]:
    return {r[1] for r in con.execute(f"PRAGMA table_info({table})")}


def run(con: sqlite3.Connection, statements: list[tuple[str, str]], consts, local) -> None:
    for _, raw in statements:
        con.execute(resolve(raw, consts, local).strip())


def main() -> int:
    for path in (TABLES_KT, SCHEMA_KT):
        if not path.exists():
            print(f"[错误] 找不到 {path} —— 检查形同虚设", file=sys.stderr)
            return 1

    consts, expected_tables = load_constants()
    local = load_schema_consts()
    if len(consts) < 20 or not expected_tables:
        print(f"[错误] 常量解析异常（{len(consts)} / {len(expected_tables)}）", file=sys.stderr)
        return 1

    schema = strip_comments(SCHEMA_KT.read_text(encoding="utf-8"))
    creates = parse_create_statements(schema)
    if len(creates) < MIN_STATEMENTS:
        print(f"[错误] 只抽出 {len(creates)} 条建表语句（预期 >{MIN_STATEMENTS}）—— 解析失效", file=sys.stderr)
        return 1

    v2_marker = local.get("LEGACY_ASSET_V1_MARKER")
    v3_marker = local.get("LEGACY_ASSET_GC_V2_MARKER")
    if not v2_marker or not v3_marker:
        print("[错误] 取不到标志列常量（LEGACY_*_MARKER）—— 无法验「全新库不跑升级」", file=sys.stderr)
        return 1

    problems: list[str] = []

    # ── 场景 A：全新库 ──
    con = sqlite3.connect(":memory:")
    con.isolation_level = None
    for name, raw in creates:
        try:
            con.execute(resolve(raw, consts, local).strip())
        except Exception as e:
            problems.append(f"[A 全新库/{name}] {type(e).__name__}: {e} | {raw[:110]}")

    actual_tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    missing = [t for t in expected_tables if t not in actual_tables]
    if missing:
        problems.append(f"[A] 声明的表没建出来: {', '.join(missing)}")

    all_sql = resolve("\n".join(s for _, s in creates), consts, local)
    declared_idx = {m.group(1) for m in re.finditer(r"CREATE (?:UNIQUE )?INDEX IF NOT EXISTS (\w+)", all_sql)}
    actual_idx = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='index'")}
    for idx in sorted(declared_idx - actual_idx):
        problems.append(f"[A] 声明的索引没建出来: {idx}")

    # ── 场景 B：全新库 + 门控 → 升级语句一句都不该跑 ──
    # 这是本次事故的复现点：老代码在全新库上跑 v2/v3 升级（表刚建成新形态）→
    # v3 的 INSERT ... SELECT not_before 撞上「无此列」→ 整个事务回滚。
    ran_in_fresh = 0
    for version, marker, table in ((2, v2_marker, "x_asset"), (3, v3_marker, "x_asset_gc")):
        if marker in columns(con, table):
            ran_in_fresh += 1
            try:
                run(con, parse_upgrade_statements(schema, version), consts, local)
            except Exception as e:
                problems.append(f"[B 全新库/{version}] 不该跑的升级语句被跑了且失败: {type(e).__name__}: {e}")
    if ran_in_fresh:
        problems.append(f"[B] 全新库上判定需要重建 {ran_in_fresh} 次 —— 门控失效（本次事故的根因）")

    # ── 场景 C：v1 旧库 → v2 ──
    con2 = sqlite3.connect(":memory:")
    con2.isolation_level = None
    con2.execute(
        "CREATE TABLE x_asset (id TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, "
        "byte_size INTEGER NOT NULL, created_at INTEGER NOT NULL, last_referenced_at INTEGER NOT NULL, "
        "extras_json TEXT NOT NULL DEFAULT '{}', mime_type TEXT, origin TEXT, width INTEGER, "
        "height INTEGER, thumbnail_path TEXT)"
    )
    con2.execute(
        "INSERT INTO x_asset (id, path, byte_size, created_at, last_referenced_at, extras_json, mime_type) "
        "VALUES ('h1', 'assets/ab/cd/h1.jpg', 10, 1, 2, '{}', 'image/jpeg')"
    )
    if v2_marker not in columns(con2, "x_asset"):
        problems.append(f"[C] 造出的 v1 表里没有标志列 {v2_marker} —— 场景构造失败，这一场景形同虚设")
    else:
        try:
            run(con2, parse_upgrade_statements(schema, 2), consts, local)
        except Exception as e:
            problems.append(f"[C v1→v2] {type(e).__name__}: {e}")
        else:
            cols = columns(con2, "x_asset")
            if v2_marker in cols:
                problems.append(f"[C] 升级后旧列 {v2_marker} 仍在")
            if "extras_json" not in cols:
                problems.append("[C] 升级后缺少 extras_json")
            rows = list(con2.execute("SELECT id, path, byte_size FROM x_asset"))
            if rows != [("h1", "assets/ab/cd/h1.jpg", 10)]:
                problems.append(f"[C] 数据没搬过来: {rows}")

    # ── 场景 D：v2 旧库 → v3 ──
    con2.execute(
        "CREATE TABLE x_asset_gc (asset_id TEXT NOT NULL PRIMARY KEY, not_before INTEGER NOT NULL, "
        "generation INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '')"
    )
    con2.execute("INSERT INTO x_asset_gc (asset_id, not_before, generation, reason) VALUES ('h1', 123, 0, '')")
    if v3_marker not in columns(con2, "x_asset_gc"):
        problems.append(f"[D] 造出的 v2 表里没有标志列 {v3_marker} —— 场景构造失败，这一场景形同虚设")
    else:
        try:
            run(con2, parse_upgrade_statements(schema, 3), consts, local)
        except Exception as e:
            problems.append(f"[D v2→v3] {type(e).__name__}: {e}")
        else:
            cols = columns(con2, "x_asset_gc")
            if v3_marker in cols:
                problems.append(f"[D] 升级后旧列 {v3_marker} 仍在")
            if "first_unreferenced_at" not in cols:
                problems.append("[D] 升级后缺少 first_unreferenced_at")
            rows = list(con2.execute("SELECT asset_id, first_unreferenced_at FROM x_asset_gc"))
            if rows != [("h1", 123)]:
                problems.append(f"[D] 数据没搬过来: {rows}")

    # ── 场景 E：门控必须在实现里（防镜像与实现分家）──
    ensure_body = re.search(r"fun ensure\(db: SupportSQLiteDatabase\)\s*\{(.*?)\n    \}", schema, re.S)
    if not ensure_body or "needsRebuildTo(" not in ensure_body.group(1):
        problems.append("[E] ensure 里没有调用 needsRebuildTo —— 场景 B 的门控是镜像，实现若去掉它就无人守")

    print(
        f"X 存储层自检:建表语句 {len(creates)} 条 / 表 {len(expected_tables)} 张 / 场景 A-E"
    )
    if problems:
        print()
        for item in problems:
            print(f"❌ {item}")
        print()
        print("DDL 与升级语句写在 XStorageSchema，列名常量在 XStorageTables —— 必须对得上；")
        print("且全新库上不得执行升级语句（那些语句引用的是旧形态的列名）。")
        return 1

    print("✅ 5 个场景全过（全新库建表 / 全新库不跑升级 / v1→v2 / v2→v3 / 门控在位）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
