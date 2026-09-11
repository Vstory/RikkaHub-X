#!/usr/bin/env python3
"""X 存储层「声明一致性」自检 —— 实体 / DAO / 列名常量 / 设计契约。

## 为什么需要它

X 改走 Room 之后，**不再有手写 SQL**，但仍有两处**手写声明**必须互相一致：

| 处 | 谁写的 | 错了会怎样 |
|---|---|---|
| `XStorageEntities.kt` | 手写 `@ColumnInfo(name = ...)` / `primaryKeys` / `indices` | Room 编译期多半能报，但**表名/列名与常量漂移**它管不着 |
| `XStorageTables.kt` | 手写常量（被业务代码与 DAO 引用） | 编译不报 |

Room 的编译期校验覆盖「实体内部自洽」（列类型、`@Query` 里的列名），
**覆盖不到「实体 ↔ 常量」这一对**。本脚本补的就是这一对，外加几条设计纪律的机器化守护。

## 检查项

| # | 查什么 | 为什么 |
|---|---|---|
| 1 | 实体表名/列集合 == 常量表名/列集合 | 漂移时编译不报，运行时才炸 |
| 2 | 实体声明的索引名以 `idx_x_` 开头，且必需索引都在 | 与既有命名一致；缺唯一索引则去重不成立 |
| 3 | DAO 每个标识符都是列名 / 关键字 / 别名 / 参数 | 列名写错编译期虽会报，但这里是**秒级**拦 |
| 4 | DAO 方法名集合 == 登记清单 | 数量对不上时说不出少了哪个;并强制「新加方法要显式登记」 |
| 5 | 表名一律 `x_` 前缀 | 避免与上游新增表撞名 |
| 6 | extras 键带 `asset.` 前缀、唯一、不与真列同名 | 免 schema 纪律：只读字段必须住 JSON |
| 7 | 资产表真列恰为 6 个指定列 | 同上，把「别顺手加列」变成机器可查 |
| 8 | 取值常量（origin / kind / audit kind）唯一非空 | 重复会让审计与引用分类互相覆盖 |
| 9 | 引用表主键 = (message, asset, kind)；墓碑表主键 = (scope, entity) | 这两条是设计核心，改动必须是有意的 |
| 10 | 回收候选表不得出现重试/宽限列 | 删除是用户显式动作，没有自动重试 |
| 11 | 候选查询带**代数列**与**非活跃过滤** | 两道守卫缺一不可,否则不该出现的资产会进可清理清单 |

## 退出码

0 = 通过；1 = 发现问题。

## 反向验证

写检查器时必须构造已知错误样本确认**真能报出来** —— 没被验证过「能报错」的检查不算检查。
本次实测注入 4 类错误（实体列名、索引名、DAO 列名、DAO 另一处列名），五项检查全部命中。
"""

from __future__ import annotations

import pathlib
import re
import sys

STORAGE = pathlib.Path("app/src/main/java/me/rerere/rikkahub/x/storage")
ENTITIES = STORAGE / "XStorageEntities.kt"
DAO = STORAGE / "XAssetDao.kt"
META_DAO = STORAGE / "XStorageMetaDao.kt"
TABLES = STORAGE / "XStorageTables.kt"

# 常量对象名 → 表名（与 XStorageTables 逐字对应；改前先改那边）
OBJECT_TO_TABLE = {
    "Asset": "x_asset",
    "AssetRef": "x_asset_ref",
    "AssetGc": "x_asset_gc",
    "GcAudit": "x_gc_audit",
    "Tombstone": "x_tombstone",
    "Meta": "x_storage_meta",
}

# 允许的业务标识符之外，DAO 语句里合法出现的 SQL 词
SQL_KEYWORDS = {
    "select", "from", "where", "and", "or", "not", "exists", "join", "on", "order", "by",
    "asc", "desc", "update", "set", "delete", "insert", "into", "values", "count", "sum",
    "coalesce", "as", "is", "null", "limit", "offset", "group", "having", "distinct",
    "primary", "key", "create", "table", "if", "index", "unique", "default", "integer",
    "text", "real", "blob", "check", "references", "foreign", "cascade", "true", "false",
    "like", "in", "between", "case", "when", "then", "else", "end", "autoincrement",
}

# 免 schema 纪律：资产表只允许这 6 个真列，其余字段一律进 extras_json
ASSET_ALLOWED_COLUMNS = {
    "id", "path", "byte_size", "created_at", "last_referenced_at", "extras_json",
}

# DAO 必须提供的方法（按名核对,而不是数数量 —— 数量对不上时说不出少了哪个、多了哪个）
REQUIRED_DAO_METHODS = {
    # 资产
    "selectPathByHash", "upsertAsset", "touchLastReferenced", "deleteAsset",
    # 引用
    "insertRef", "deleteRefsOfConversation", "deleteRefsOfMessage", "countRefsOfAsset",
    # 回收:统计与状态流转
    "sumUnreferencedBytes", "selectGcCandidates", "insertGcCandidate",
    "markGcCandidateInactive", "restartGcObservation",
    "deleteGcCandidate", "deleteAllGcCandidates", "selectGeneration",
    # 审计
    "insertAudit",
}

# `x_storage_meta` DAO 必须提供的方法（同样按名核对）
REQUIRED_META_DAO_METHODS = {"getValue", "put", "remove"}

# 必需索引（名字与语义）
REQUIRED_INDEXES = {
    "idx_x_asset_path": "同一份内容只应有一个落盘文件（去重的前提，必须是唯一索引）",
    "idx_x_asset_ref_asset": "按资产反查引用（回收判定的热查询）",
    "idx_x_asset_ref_message": "按消息查资产（渲染）",
    "idx_x_asset_gc_first_unreferenced": "首次无引用时刻是候选排序与「闲置 N 天」的依据",
}


def read_const_block(src: str, obj: str) -> tuple[dict[str, str], list[str]]:
    """读一个 object 里的 `const val NAME = "value"` 与 `val ALL = listOf(...)`。

    返回 (常量映射, ALL 解析后取值)。ALL 里的项可能是常量名或字面量。
    """
    block = re.search(r"object %s \{(.*?)\n    \}" % obj, src, re.S)
    if not block:
        sys.exit(f"  ❌ XStorageTables 里找不到 object {obj}")
    body = block.group(1)

    consts: dict[str, str] = {}
    for name, value in re.findall(r'const val (\w+) = "([^"]*)"', body):
        consts[name] = value

    # 列集合用 COLUMNS(如 Asset/AssetRef),取值集合用 ALL(如 Origins);
    # AuditKinds 两个都没有 → 调用方 fallback 到全部 const
    all_body = re.search(r"val (?:ALL|COLUMNS)[^=]*=\s*(?:listOf\()?(.*?)\)?\s*$", body, re.S | re.M)
    resolved: list[str] = []
    if all_body:
        for raw in all_body.group(1).replace("\n", " ").split(","):
            token = raw.strip().strip('"')
            if not token:
                continue
            resolved.append(consts.get(token, token))
    return consts, resolved


def declared_columns() -> dict[str, set[str]]:
    """表 → 列集合（列名常量的唯一真源）。"""
    src = TABLES.read_text(encoding="utf-8")
    out: dict[str, set[str]] = {}
    for obj, table in OBJECT_TO_TABLE.items():
        consts, all_values = read_const_block(src, obj)
        if not all_values:
            # 没有 ALL 的（如 Asset 直接列常量）取全部 const 值
            all_values = list(consts.values())
        out[table] = set(all_values)
    return out


def entity_declarations() -> dict[str, dict]:
    """从 @Entity 读出表名、列集合、主键、索引名。"""
    src = ENTITIES.read_text(encoding="utf-8")
    out: dict[str, dict] = {}
    for block in re.finditer(r"@Entity\((.*?)\)\ndata class (\w+)\((.*?)\n\)", src, re.S):
        anno, cls, body = block.group(1), block.group(2), block.group(3)
        table_match = re.search(r'tableName = "([^"]+)"', anno)
        if not table_match:
            sys.exit(f"  ❌ {cls} 的 @Entity 缺 tableName")
        primary = re.search(r'primaryKeys = \[(.*?)\]', anno)
        out[table_match.group(1)] = {
            "class": cls,
            "columns": set(re.findall(r'@ColumnInfo\(name = "([^"]+)"', body)),
            "primary_keys": set(re.findall(r'"([^"]+)"', primary.group(1))) if primary else set(),
            "indexes": set(re.findall(r'name = "(\w+)"', anno)),
        }
    return out


def main() -> int:
    for path in (ENTITIES, DAO, META_DAO, TABLES):
        if not path.exists():
            sys.exit(f"  ❌ 缺文件:{path}")

    errors: list[str] = []
    tables_src = TABLES.read_text(encoding="utf-8")
    declared = declared_columns()
    entities = entity_declarations()

    # ── 1. 实体 ↔ 常量：表名与列集合 ──
    print(f"  表定义 {len(declared)} 张 / 实体 {len(entities)} 个")
    if set(declared) != set(entities):
        errors.append(
            f"实体与常量表集合不一致:仅常量 {sorted(set(declared) - set(entities))},"
            f"仅实体 {sorted(set(entities) - set(declared))}"
        )
    for table in sorted(set(declared) & set(entities)):
        missing = declared[table] - entities[table]["columns"]
        extra = entities[table]["columns"] - declared[table]
        if missing:
            errors.append(f"{table}: 实体少列 {sorted(missing)}")
        if extra:
            errors.append(f"{table}: 实体多列 {sorted(extra)}")

    # ── 5. 表名 x_ 前缀 ──
    for table in sorted(declared):
        if not table.startswith("x_"):
            errors.append(f"表名需加 x_ 前缀以避免与上游新增表撞名:{table}")

    # ── 2. 索引 ──
    all_indexes: set[str] = set()
    for info in entities.values():
        all_indexes |= info["indexes"]
    print(f"  实体声明索引 {len(all_indexes)} 个")
    for name in sorted(all_indexes):
        if not name.startswith("idx_x_"):
            errors.append(f"索引名应以 idx_x_ 开头（与既有命名一致）:{name}")
    for name, why in REQUIRED_INDEXES.items():
        if name not in all_indexes:
            errors.append(f"缺少必需索引 {name} —— {why}")

    # ── 9. 主键构成 ──
    ref_pk = entities.get("x_asset_ref", {}).get("primary_keys", set())
    if ref_pk != {"message_id", "asset_id", "kind"}:
        errors.append(f"引用表主键应为 (message_id, asset_id, kind),实际 {sorted(ref_pk)}")
    tomb_pk = entities.get("x_tombstone", {}).get("primary_keys", set())
    if tomb_pk != {"scope", "entity_id"}:
        errors.append(f"墓碑表主键应为 (scope, entity_id),实际 {sorted(tomb_pk)}")

    # ── 10. 回收候选表不得重现自动重试列 ──
    gc_cols = declared.get("x_asset_gc", set())
    for banned in ("attempt", "attempts", "not_before", "last_attempt_at"):
        if banned in gc_cols:
            errors.append(f"回收候选表不得再有 `{banned}` —— 删除是用户显式动作,没有自动重试")

    # ── 7. 免 schema 纪律：资产表真列固定 ──
    asset_cols = declared.get("x_asset", set())
    if asset_cols != ASSET_ALLOWED_COLUMNS:
        errors.append(
            "资产表真列集合被改动。只读/展示字段必须住 extras_json（免 schema 纪律）——"
            f"多出 {sorted(asset_cols - ASSET_ALLOWED_COLUMNS)}，"
            f"少了 {sorted(ASSET_ALLOWED_COLUMNS - asset_cols)}"
        )

    # ── 6. extras 键 ──
    extras_consts, extras_all = read_const_block(tables_src, "AssetExtras")
    if len(extras_all) != len(set(extras_all)):
        errors.append("extras 键重复会互相覆盖")
    for key in extras_all:
        if not key.startswith("asset."):
            errors.append(f"extras 键需带 asset. 前缀:{key}")
        if key in asset_cols:
            errors.append(f"extras 键不得与真列同名（会分不清数据在哪一侧）:{key}")

    # ── 8. 取值常量唯一非空 ──
    for obj in ("Origins", "RefKinds", "AuditKinds"):
        consts, values = read_const_block(tables_src, obj)
        if not values:
            # AuditKinds 没有 ALL —— 全部 const 就是取值集合
            values = list(consts.values())
        if not values:
            errors.append(f"{obj} 取值为空（解析失败或真的空）")
            continue
        if len(values) != len(set(values)):
            errors.append(f"{obj} 取值重复:{values}")
        for value in values:
            if not value.strip():
                errors.append(f"{obj} 取值不得为空白")

    # ── DAO 源码（供下面各段解析查询文本）──
    dao_src = DAO.read_text(encoding="utf-8")
    # ── 11. 回收候选查询的两道必需守卫 ──
    # 两道都**不可省**,少了任一条都会让「不该出现的资产」出现在可清理清单里:
    # ① 代数(generation 列)必须随清单读出 —— 否则界面无法在确认时交回校验,代数机制失效;
    # ② 非活跃过滤(first_unreferenced_at > :inactiveAt)——
    #    曾无引用、后被重新引用的资产其值是哨兵 -1,不过滤会直接出现在候选里
    #    (此时观察期尚未起算,却能立刻被删)。
    gc_query = None
    for raw in re.findall(r'@Query\(\s*((?:"[^"]*"\s*\+?\s*)+)\)\s*\n\s*fun selectGcCandidates', dao_src, re.S):
        gc_query = " ".join(re.findall(r'"([^"]*)"', raw))
    if gc_query is None:
        errors.append("找不到 selectGcCandidates 的 @Query")
    else:
        if "generation AS generation" not in gc_query:
            errors.append(
                "selectGcCandidates 必须把 generation 读出来(界面要在确认删除时交回校验)"
            )
        if "first_unreferenced_at > :inactiveAt" not in gc_query:
            errors.append(
                "selectGcCandidates 必须带非活跃过滤 `first_unreferenced_at > :inactiveAt` —— "
                "少了它,被重新引用过的资产(值为哨兵)会直接进候选清单、观察期未起算即可被删"
            )
        # ③ 内容寻址前缀:回填会把 upload/ 下的老文件也登记进账本,而它们的引用登记不上
        #    (路径里没有内容指纹)→ 在引用表里表现为「无引用」。不过滤就会把**正在被消息使用**
        #    的老文件算成可清理、甚至列为可删候选。
        if "a.path LIKE :managedPrefix" not in gc_query:
            errors.append(
                "selectGcCandidates 必须带内容寻址过滤 `a.path LIKE :managedPrefix` —— "
                "少了它,回填登记的老文件(引用登记不上,但确实被消息用着)会被当成可删候选"
            )

    # 「可清理字节数」同样必须过滤:它比候选清单更早出现在界面上,不过滤会直接虚报可清理量
    sum_query = None
    for raw in re.findall(r'@Query\(\s*((?:"[^"]*"\s*\+?\s*)+)\)\s*\n\s*fun sumUnreferencedBytes', dao_src, re.S):
        sum_query = " ".join(re.findall(r'"([^"]*)"', raw))
    if sum_query is None:
        errors.append("找不到 sumUnreferencedBytes 的 @Query")
    elif "a.path LIKE :managedPrefix" not in sum_query:
        errors.append(
            "sumUnreferencedBytes 必须带内容寻址过滤 `a.path LIKE :managedPrefix` —— "
            "否则「可清理 N 字节」会把回填进来的老文件算进去(它们仍被消息用着)"
        )

    # ── 12. 元数据键:x.storage. 前缀 + 唯一 ──
    # 这些键是回填「可暂停/可续跑/进度可见」的载体,也是将来看「存储底账」的入口。
    # 前缀不统一会让「存储域有哪些状态」无法靠 grep 答出来。
    meta_keys_block = re.search(r"object MetaKeys \{(.*?)\n    \}", tables_src, re.S)
    if not meta_keys_block:
        errors.append("XStorageTables 里找不到 MetaKeys")
    else:
        keys = re.findall(r'const val \w+ = "([^"]+)"', meta_keys_block.group(1))
        if not keys:
            errors.append("MetaKeys 里没有解析到任何键")
        if len(keys) != len(set(keys)):
            errors.append(f"元数据键重复会互相覆盖:{keys}")
        for key in keys:
            if not key.startswith("x.storage."):
                errors.append(f"元数据键需带 x.storage. 命名空间:{key}")
        # ALL 必须与常量集合一致(漏登记会让「遍历全部键」的代码漏掉新键)
        all_block = re.search(r"val ALL: List<String> = listOf\((.*?)\)", meta_keys_block.group(1), re.S)
        if not all_block:
            errors.append("MetaKeys 缺 ALL 清单")
        else:
            listed = [x.strip() for x in all_block.group(1).replace("\n", " ").split(",") if x.strip()]
            consts = re.findall(r"const val (\w+) = ", meta_keys_block.group(1))
            if len(listed) != len(consts):
                errors.append(
                    f"MetaKeys.ALL 与常量数量不一致:ALL {len(listed)} 项 vs 常量 {len(consts)} 个 —— "
                    "新增键必须同时登记进 ALL"
                )
        print(f"  元数据键 {len(keys)} 个")

    # ── 3 & 4 通用部分:对每个 DAO 各跑一遍 ──
    all_columns = set().union(*declared.values()) if declared else set()
    for dao_path, required in ((DAO, REQUIRED_DAO_METHODS), (META_DAO, REQUIRED_META_DAO_METHODS)):
        src = dao_path.read_text(encoding="utf-8")
        label = dao_path.name
        queries = re.findall(r'@Query\(\s*((?:"[^"]*"\s*\+?\s*)+)\)', src, re.S)
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
                errors.append(f"{label} 语句里的未知标识符 `{token}`:{sql[:70]}")
        methods = set(re.findall(r"\n    fun (\w+)\(", src))
        print(f"  {label}:{len(queries)} 条 @Query,{len(re.findall(r'@Insert', src))} 条 @Insert,"
              f"{len(methods)} 个方法")
        missing = required - methods
        extra = methods - required
        if missing:
            errors.append(f"{label} 缺少应有的方法:{sorted(missing)}")
        if extra:
            errors.append(
                f"{label} 多出未登记的方法:{sorted(extra)} —— "
                "新加方法请同时登记进本脚本的 REQUIRED_*_DAO_METHODS(这是有意的确认步骤)"
            )

    if errors:
        print("\n  ❌ 发现问题:")
        for err in errors:
            print(f"    - {err}")
        return 1
    print("\n  ✅ 实体 / DAO / 常量 / 设计契约 全部一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
