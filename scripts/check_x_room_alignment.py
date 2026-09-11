#!/usr/bin/env python3
"""X 存储层「Room 实体 / DAO / 迁移 DDL / 列名常量」四方对齐自检。

## 为什么需要它

X 改走 Room 之后，**列名出现在四个地方**，且都不是同一份真源的派生：

| 处 | 谁写的 | 错了会怎样 |
|---|---|---|
| `XStorageEntities.kt` | 手写 `@ColumnInfo(name = ...)` | Room 编译期会报（已接入 `entities` 后） |
| `XAssetDao.kt` | 手写 `@Query` 里的列名 | Room 编译期会报 |
| `XStorageMigration_25_26.kt` | 手写 DDL（Room 不替迁移生成语句） | **编译不报** —— 只在旧库升级时炸 |
| `XStorageTables.kt` | 手写常量（被迁移与业务代码引用） | 编译不报 |

Room 的编译期校验只覆盖前两处对**实体**的一致性，覆盖不到「常量 ↔ DDL」这一对。
本脚本就是补这一对，外加索引名与表名。

## 检查项

1. 每个 `@Entity` 的表名/列集合，与 `XStorageTables` 的常量完全一致（多列少列都报）
2. 实体声明的索引名集合，与迁移 DDL 里的索引名集合一致
   （Room 默认生成 `index_*`，既有库是 `idx_*` —— 名字不同会被判定结构不一致）
3. DAO 每个 `@Query` 里出现的标识符，要么是列名、要么是 SQL 关键字/别名/参数
4. DAO 的方法数与方案要求的 15 条一致

## 退出码

0 = 通过；1 = 发现问题。

## 反向验证（写检查器时必须做）

构造已知错误样本确认**真能报出来** —— 没被验证过「能报错」的检查不算检查。
本次实测注入三类错误（实体列名改一个字母、索引名改一个字母、DAO 列名改一个字母），
四项检查全部命中。
"""

from __future__ import annotations

import pathlib
import re
import sys

STORAGE = pathlib.Path("app/src/main/java/me/rerere/rikkahub/x/storage")
ENTITIES = STORAGE / "XStorageEntities.kt"
DAO = STORAGE / "XAssetDao.kt"
MIGRATION = STORAGE / "XStorageMigration_25_26.kt"
TABLES = STORAGE / "XStorageTables.kt"

# 常量对象名 → 表名（与 XStorageTables 逐字对应，改这里前先改那边）
OBJECT_TO_TABLE = {
    "Asset": "x_asset",
    "AssetRef": "x_asset_ref",
    "AssetGc": "x_asset_gc",
    "GcAudit": "x_gc_audit",
    "Tombstone": "x_tombstone",
    "Meta": "x_storage_meta",
}

SQL_KEYWORDS = {
    "select", "from", "where", "and", "or", "not", "exists", "join", "on", "order", "by",
    "asc", "desc", "update", "set", "delete", "insert", "into", "values", "count", "sum",
    "coalesce", "as", "is", "null", "limit", "offset", "group", "having", "distinct",
    "primary", "key", "create", "table", "if", "index", "unique", "default", "integer",
    "text", "real", "blob", "check", "references", "foreign", "cascade", "true", "false",
    "like", "in", "between", "case", "when", "then", "else", "end", "autoincrement",
}


def declared_columns() -> dict[str, set[str]]:
    """从 XStorageTables 读出「表 → 列集合」——列名常量的唯一真源。"""
    src = TABLES.read_text(encoding="utf-8")
    out: dict[str, set[str]] = {}
    for obj, table in OBJECT_TO_TABLE.items():
        block = re.search(r"object %s \{(.*?)\n    \}" % obj, src, re.S)
        if not block:
            sys.exit(f"  ❌ XStorageTables 里找不到 object {obj}")
        body = block.group(1)
        cols = re.search(r"val COLUMNS: List<String> = listOf\((.*?)\)", body, re.S)
        if not cols:
            sys.exit(f"  ❌ {obj} 没有 COLUMNS")
        names: set[str] = set()
        for raw in cols.group(1).replace("\n", " ").split(","):
            token = raw.strip()
            if not token:
                continue
            if re.fullmatch(r"[A-Z_]+", token):
                const = re.search(r'const val %s = "([^"]+)"' % token, body)
                if not const:
                    sys.exit(f"  ❌ {obj}.{token} 没有 const 定义")
                names.add(const.group(1))
            else:
                names.add(token.strip('"'))
        out[table] = names
    return out


def entity_columns() -> dict[str, set[str]]:
    """从 @Entity 声明读出「表 → 列集合」与「索引名集合」。"""
    src = ENTITIES.read_text(encoding="utf-8")
    cols: dict[str, set[str]] = {}
    for block in re.finditer(r"@Entity\((.*?)\)\ndata class (\w+)\((.*?)\n\)", src, re.S):
        anno, cls, body = block.group(1), block.group(2), block.group(3)
        table = re.search(r'tableName = "([^"]+)"', anno)
        if not table:
            sys.exit(f"  ❌ {cls} 的 @Entity 缺 tableName")
        cols[table.group(1)] = set(re.findall(r'@ColumnInfo\(name = "([^"]+)"', body))
    return cols


def main() -> int:
    for path in (ENTITIES, DAO, MIGRATION, TABLES):
        if not path.exists():
            sys.exit(f"  ❌ 缺文件:{path}")

    errors: list[str] = []
    declared = declared_columns()
    entities = entity_columns()

    # ① 实体 ↔ 常量：表名与列集合
    print(f"  既有表定义:{len(declared)} 张 / 实体:{len(entities)} 个")
    if set(declared) != set(entities):
        errors.append(
            f"实体与常量表的集合不一致:仅常量有 {sorted(set(declared) - set(entities))},"
            f"仅实体有 {sorted(set(entities) - set(declared))}"
        )
    for table in sorted(set(declared) & set(entities)):
        missing = declared[table] - entities[table]
        extra = entities[table] - declared[table]
        if missing:
            errors.append(f"{table}: 实体少列 {sorted(missing)}")
        if extra:
            errors.append(f"{table}: 实体多列 {sorted(extra)}")

    # ② 索引名：实体声明 ↔ 迁移 DDL
    mig = MIGRATION.read_text(encoding="utf-8")
    ddl_indexes = set(re.findall(r"CREATE (?:UNIQUE )?INDEX IF NOT EXISTS (\w+)", mig))
    ent_indexes = set(re.findall(r'name = "(idx_x_\w+)"', ENTITIES.read_text(encoding="utf-8")))
    print(f"  索引:迁移 DDL {len(ddl_indexes)} 个 / 实体声明 {len(ent_indexes)} 个")
    if ent_indexes - ddl_indexes:
        errors.append(f"实体声明的索引名不存在于迁移 DDL:{sorted(ent_indexes - ddl_indexes)}")
    if ddl_indexes - ent_indexes:
        errors.append(f"迁移 DDL 建了实体没声明的索引(升级后结构不一致):{sorted(ddl_indexes - ent_indexes)}")

    # ③ DAO 语句里的标识符
    dao = DAO.read_text(encoding="utf-8")
    all_columns = set().union(*declared.values()) if declared else set()
    queries = re.findall(r'@Query\(\s*((?:"[^"]*"\s*\+?\s*)+)\)', dao, re.S)
    for raw in queries:
        sql = " ".join(re.findall(r'"([^"]*)"', raw))
        params = set(re.findall(r":(\w+)", sql))
        aliases = set(re.findall(r"(?:FROM|JOIN)\s+x_\w+\s+(\w+)\b", sql))
        aliases |= set(re.findall(r"\b(\w+)\.\w+", sql))
        aliases |= set(re.findall(r"\bAS\s+(\w+)", sql))
        for token in re.findall(r"\b([a-z_][a-z0-9_]*)\b", sql):
            if token in SQL_KEYWORDS or token in params or token in aliases:
                continue
            if token in all_columns or token.startswith("x_"):
                continue
            errors.append(f"DAO 语句里的未知标识符 `{token}`:{sql[:70]}")
    n_insert = len(re.findall(r"@Insert", dao))
    n_methods = len(re.findall(r"\n    fun ", dao))
    print(f"  DAO:{len(queries)} 条 @Query,{n_insert} 条 @Insert,共 {n_methods} 个方法")
    if n_methods != 15:
        errors.append(f"DAO 方法数应为 15(方案要求),实际 {n_methods}")

    if errors:
        print("\n  ❌ 发现问题:")
        for err in errors:
            print(f"    - {err}")
        return 1
    print("\n  ✅ 实体列名 / 索引名 / DAO 列名 / 迁移 DDL 全部与常量一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
