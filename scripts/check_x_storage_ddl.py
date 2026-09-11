#!/usr/bin/env python3
"""X 存储层 DDL 自检：把建表语句**真的执行一遍**。

## 为什么需要它（2026-09-11 事故）

X 表不是 Room 实体，DDL 是手写字符串。既有的 `XStorageSchemaTest` 只断言
**字符串内容**（都带 `IF NOT EXISTS`、表名与常量一一对应、语句不重复…），
**从不执行** —— 「语句本身能不能跑」在整条流水线上没有任何保障。

实测的后果不是 DDL 写错（真跑一遍证明 14 条全可执行），而是**没法立刻排除它**：
装机后 X 表没建上，只能先花时间证明「不是语句的问题」。这个脚本把这一步变成秒级。

## 判据（任一不满足即退出码 1）

1. **全部语句可执行**（含索引与升级语句所依赖的表形态）；
2. **`XStorageTables.ALL` 里的每张表都真的建出来了** —— 语句通过还不够，
   可能建到了别名/临时名字上；
3. **索引都建出来了** —— 唯一约束靠索引，少了它「同一份内容只落一份」会静默失效；
4. **解析失效也报错退出**（语句数太少）—— 否则这个检查会「永远通过」。

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
    """解析 XStorageTables.kt：常量表 + 声明的表名列表。"""
    consts: dict[str, str] = {}
    outer = None
    for line in strip_comments(TABLES_KT.read_text(encoding="utf-8")).split("\n"):
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

    # `val ALL: List<String> = listOf(ASSET, ASSET_REF, ...)`
    all_block = re.search(r"val ALL: List<String> = listOf\(([^)]*)\)", TABLES_KT.read_text(encoding="utf-8"))
    tables: list[str] = []
    if all_block:
        for token in all_block.group(1).split(","):
            token = token.strip()
            if token and f"{TOPP}.{token}" in consts:
                tables.append(consts[f"{TOPP}.{token}"])
    return consts, tables


def resolve(text: str, consts: dict[str, str]) -> str:
    def sub(m: re.Match) -> str:
        key = m.group(1)
        for candidate in ([key, key[len(TOPP) + 1:]] if key.startswith(TOPP + ".") else [key]):
            if candidate in consts:
                return consts[candidate]
        raise KeyError(key)

    return re.sub(r"\$\{([^}]+)\}", sub, text)


def collect_statements(schema: str) -> list[tuple[str, str]]:
    """按 CREATE_STATEMENTS 的顺序收集语句（含 spread 进来的索引组）。"""
    # `"a" + "b"` 跨行拼接先并起来，否则会切出半条语句
    def merged(block: str) -> str:
        return re.sub(r'"\s*\+\s*"', "", block)

    def block_of(name: str) -> str:
        m = re.search(rf"(?:private )?val {name}\b.*?(?=\n    (?:private val|val|/\*|fun ))", schema, re.S)
        return m.group(0) if m else ""

    statements: list[tuple[str, str]] = []
    for name in ("ASSET_TABLE_DDL", "ASSET_GC_TABLE_DDL"):
        for lit in re.finditer(r'"""(.*?)"""', block_of(name), re.S):
            statements.append((name, lit.group(1)))

    body = re.search(r"val CREATE_STATEMENTS: List<String> = listOf\((.*?)\n    \)", schema, re.S)
    if not body:
        return []
    body_text = body.group(1)

    for token in [x.strip().rstrip(",") for x in body_text.split("\n")]:
        if not token:
            continue
        if "_INDEX_STATEMENTS" in token:
            group = token.replace("*", "").replace(".toTypedArray()", "")
            for lit in re.finditer(r'"([^"\n]*)"', merged(block_of(group))):
                statements.append((group, lit.group(1)))
    for lit in re.finditer(r'"""(.*?)"""', body_text, re.S):
        statements.append(("inline", lit.group(1)))
    for lit in re.finditer(r'"(CREATE [^"\n]*)"', body_text):
        statements.append(("inline", lit.group(1)))
    return statements


def main() -> int:
    for path in (TABLES_KT, SCHEMA_KT):
        if not path.exists():
            print(f"[错误] 找不到 {path} —— 检查形同虚设", file=sys.stderr)
            return 1

    consts, expected_tables = load_constants()
    if len(consts) < 20 or not expected_tables:
        print(f"[错误] 常量解析异常（{len(consts)} 个常量 / {len(expected_tables)} 张表）", file=sys.stderr)
        return 1

    schema = strip_comments(SCHEMA_KT.read_text(encoding="utf-8"))
    statements = collect_statements(schema)
    if len(statements) < MIN_STATEMENTS:
        print(f"[错误] 只抽出 {len(statements)} 条语句（预期 >{MIN_STATEMENTS}）—— 解析失效", file=sys.stderr)
        return 1

    con = sqlite3.connect(":memory:")
    con.isolation_level = None
    problems: list[str] = []
    executed = 0
    resolved_sql: list[str] = []
    for name, raw in statements:
        try:
            sql = resolve(raw, consts).strip()
        except KeyError as e:
            problems.append(f"[{name}] 常量未解析: {e}")
            continue
        if not sql:
            continue
        try:
            con.execute(sql)
            executed += 1
            resolved_sql.append(sql)
        except Exception as e:
            problems.append(f"[{name}] {type(e).__name__}: {e} | {sql[:150]}")

    actual_tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    missing_tables = [t for t in expected_tables if t not in actual_tables]

    # 只拿**成功解析并执行**的语句去找索引名 —— 解析失败的语句已单独报错，
    # 不该让它在统计这一步再抛一次（那会掩盖真正的报错信息）
    all_sql = "\n".join(resolved_sql)
    declared_indexes = {
        m.group(1)
        for m in re.finditer(r"CREATE (?:UNIQUE )?INDEX IF NOT EXISTS (\w+)", all_sql)
    }
    actual_indexes = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='index'")}
    missing_indexes = sorted(declared_indexes - actual_indexes)

    print(f"X 存储层 DDL 自检:常量 {len(consts)} 个 / 语句 {len(statements)} 条 / 执行成功 {executed} 条")
    print(f"  表 {len(actual_tables)} 张,索引 {len(actual_indexes)} 个")

    if missing_tables:
        problems.append(f"声明的表没建出来: {', '.join(missing_tables)}")
    if missing_indexes:
        problems.append(f"声明的索引没建出来: {', '.join(missing_indexes)}")

    if problems:
        print()
        for item in problems:
            print(f"❌ {item}")
        print()
        print("DDL 写在 XStorageSchema,列名常量在 XStorageTables —— 两者必须对得上。")
        return 1

    print("✅ 无问题")
    return 0


if __name__ == "__main__":
    sys.exit(main())
