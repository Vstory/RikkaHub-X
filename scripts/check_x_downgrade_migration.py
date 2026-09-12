#!/usr/bin/env python3
"""降级迁移自检:`Migration_26_25` 删的表,是否恰好等于 v26 相对 v25 多出来的那些。

## 为什么需要它(2026-09-12 生产崩溃)

存储重构把库升到 26(X 的 6 张表进 Room 托管),而 main 仍是 25。两者 `applicationId`
相同,同 key 自签名可以**互相覆盖安装** —— 于是「先装 26、再装 25」是真实路径。缺
`26 → 25` 的迁移时 Room 抛 `A migration from 26 to 25 was required but not found`,
`onDowngrade` 崩溃、应用启动即退。用户真机上撞到过一次。

补上迁移后,这个「删哪几张表」的清单就成了**新的单点**:清单漏一张 → 降级后库自称
v25 却带着一张 v26 的表(将来 26 → 27 若改列,AutoMigration 会因 `IF NOT EXISTS`
跳过建表、结构校验不过而崩);清单多写一张 → 降级会误删上游表。

## 判据

1. 待删清单**恰好 6 张**,且与 `X_TABLES_IN_V26`(权威来源:存储重构分支的
   `x/storage/XStorageEntities.kt` 的 `@Entity(tableName = ...)`)一致;
2. 把那 6 张表**真的建出来**(先叠在由 `schemas/25.json` 真实建出的 v25 库上),
   再**真的执行**迁移里的 `DROP TABLE IF EXISTS ...`,然后:
   - 剩余表集合必须**等于** v25 的表集合;
   - 每张 v25 表的建表语句必须与 `25.json` **逐字一致**(归一化后);
   - 各表行数不得变化(**数据没动**);
   - 再执行一次不得报错(幂等)。

第 2 条是**真跑**,不是断言 SQL 字符串 —— 只断言字符串的检查抓不到「语句本身有错」。

## 用法

    python3 scripts/check_x_downgrade_migration.py     # 退出码 0 = 通过,1 = 有问题

## 已知边界(诚实记录)

- 若存储重构分支**将来再加一张 X 表**,v26 就多了第 7 张,而本脚本的 `X_TABLES_IN_V26`
  不会自动跟着变 —— 那种情况下必须**同时**改这里与 `Migration_26_25.kt`,这会由本脚本
  的清单比对在本地就报出来(拿它当「改了 v26 结构要回头改降级路径」的提醒)。
- 本脚本只覆盖 SQL 层面。Room 是否接受这条迁移、降级后结构校验是否通过,只有
  真机覆盖安装能回答。
"""

import json
import pathlib
import re
import sqlite3
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
MIGRATION = REPO / "app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_26_25.kt"
SCHEMA_DIR = REPO / "app/schemas/me.rerere.rikkahub.data.db.AppDatabase"
FROM_VERSION = 26
TO_VERSION = 25

# 权威来源:存储重构分支 x/storage/XStorageEntities.kt 的 6 个 @Entity(tableName)。
X_TABLES_IN_V26 = [
    "x_asset",
    "x_asset_ref",
    "x_asset_gc",
    "x_gc_audit",
    "x_tombstone",
    "x_storage_meta",
]

failures: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)


def extract_tables(source: str) -> list[str]:
    """从 Kotlin 源里取出 `private val xTables = listOf(...)` 的字面量。"""
    m = re.search(r"private val xTables = listOf\((.*?)\n\s*\)", source, re.S)
    if not m:
        return []
    return re.findall(r'"([a-z_0-9]+)"', m.group(1))


def normalize(sql: str) -> str:
    """归一化建表语句;SQLite 落库会去掉 IF NOT EXISTS,故一并去掉再比。"""
    s = re.sub(r"\s+", " ", sql or "").strip().rstrip(";").lower()
    return re.sub(r"^create table if not exists ", "create table ", s)


def insert_dummy(conn: sqlite3.Connection, table: str) -> None:
    """给表插一行,把 NOT NULL 且无默认值的列填上占位值(用于验证数据未被迁移动过)。"""
    names, values = [], []
    for _, name, ctype, notnull, default, pk in conn.execute(f"PRAGMA table_info({table})"):
        if default is not None:
            continue
        if notnull or pk:
            affinity = (ctype or "").upper()
            names.append(name)
            values.append(1 if "INT" in affinity else (1.0 if ("REAL" in affinity or "FLOA" in affinity or "DOUB" in affinity) else "x"))
    if names:
        conn.execute(
            f"INSERT OR IGNORE INTO {table} ({','.join(names)}) VALUES ({','.join('?' * len(names))})",
            values,
        )


def main() -> int:
    if not MIGRATION.exists():
        print(f"❌ 找不到迁移文件:{MIGRATION.relative_to(REPO)}")
        print("   降级路径被删掉了?那会让「先装 26、再装 25」的设备启动即崩。")
        return 1

    schema_path = SCHEMA_DIR / f"{TO_VERSION}.json"
    if not schema_path.exists():
        print(f"❌ 找不到 schema:{schema_path.relative_to(REPO)}")
        return 1
    entities = json.loads(schema_path.read_text(encoding="utf-8"))["database"]["entities"]
    if len(entities) < 5:
        print(f"❌ v{TO_VERSION} 的实体只有 {len(entities)} 个 —— schema 文件不像真的,拒绝在错误前提上判定。")
        return 1

    source = MIGRATION.read_text(encoding="utf-8")
    tables = extract_tables(source)
    if not tables:
        print("❌ 没能从迁移文件里解析出 xTables 清单 —— 解析失败不等于通过。")
        return 1

    # 判据 1:清单本身
    if sorted(tables) != sorted(X_TABLES_IN_V26):
        missing = sorted(set(X_TABLES_IN_V26) - set(tables))
        extra = sorted(set(tables) - set(X_TABLES_IN_V26))
        fail(f"待删清单与 v{FROM_VERSION} 实际新增的表不一致(漏 {missing or '无'} / 多 {extra or '无'})")
    if len(tables) != len(set(tables)):
        fail("待删清单里有重复项")

    # 判据 2:真跑一遍
    conn = sqlite3.connect(":memory:")
    for entity in entities:
        conn.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for entity in entities:
        insert_dummy(conn, entity["tableName"])
    conn.commit()
    rows_before = {e["tableName"]: conn.execute(f"SELECT COUNT(*) FROM {e['tableName']}").fetchone()[0] for e in entities}

    # 叠出 v26 形态
    for table in tables:
        conn.execute(f"CREATE TABLE IF NOT EXISTS {table} (id TEXT NOT NULL PRIMARY KEY)")
    conn.commit()

    def migrate() -> None:
        conn.execute("BEGIN")
        for table in tables:
            conn.execute(f"DROP TABLE IF EXISTS {table}")
        conn.commit()

    try:
        migrate()
    except sqlite3.Error as exc:
        print(f"❌ 迁移本体执行报错:{exc}")
        return 1

    query = "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    remaining = sorted(r[0] for r in conn.execute(query))
    expected = sorted(e["tableName"] for e in entities)
    if remaining != expected:
        fail(
            "降级后的表集合与 v{n} 不符 —— 多出 {extra} / 缺少 {missing}".format(
                n=TO_VERSION,
                extra=sorted(set(remaining) - set(expected)) or "无",
                missing=sorted(set(expected) - set(remaining)) or "无",
            )
        )

    for entity in entities:
        table = entity["tableName"]
        row = conn.execute("SELECT sql FROM sqlite_master WHERE name=?", (table,)).fetchone()
        if not row:
            fail(f"降级后 {table} 不见了")
        elif normalize(row[0]) != normalize(entity["createSql"].replace("${TABLE_NAME}", table)):
            fail(f"降级后 {table} 的建表语句与 v{TO_VERSION} 定义不一致")

    rows_after = {e["tableName"]: conn.execute(f"SELECT COUNT(*) FROM {e['tableName']}").fetchone()[0] for e in entities}
    for table, before in rows_before.items():
        if rows_after.get(table) != before:
            fail(f"降级动了数据:{table} 从 {before} 行变成 {rows_after.get(table)} 行")

    try:
        migrate()
    except sqlite3.Error as exc:
        fail(f"重复执行不幂等:{exc}")

    print(
        f"降级迁移自检:清单 {len(tables)} 张表,叠在 v{TO_VERSION} 的真实 schema 上跑了一遍 —— "
        f"剩余 {len(remaining)} 张表、{len(entities)} 张上游表结构与数据均未变"
    )
    if failures:
        print()
        for item in failures:
            print(f"  ❌ {item}")
        print()
        print("  这条迁移是「先装 v26、再装 v25」的设备唯一能启动的路径,请先修好再提交。")
        return 1
    print("  ✅ 无问题(清单与 v26 实际增量一致;降级后与 v25 定义逐字相同;数据未动;幂等)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
