#!/usr/bin/env python3
"""守卫：不得从 `message_fts` 读那两列冗余副本（`title` / `update_at`）。

## 它在防什么（一个**不会报错**的 bug）

`message_fts` 里冗余存了会话的 `title` 与 `update_at`，而这两个副本**只在写入那一刻同步**。
X 把索引改为增量维护之后，它们**不再被跟着更新** —— 于是：

    若有代码从 message_fts 读 update_at 来排序
    → 拿到的是「最后写入那一刻」的旧值
    → 「最新优先 / 最旧优先」**排出来的顺序是错的**
    → 不报错，只是顺序不对（用户看到的是"搜出来的东西怪怪的"，说不出哪里怪）

同理，若把 `message_fts.title` 查出显示，用户会看到**改名前**的旧标题。

**所以这两个副本必须只写不读。** 权威真源在 `conversationentity`（见 `MessageFtsManager` 类注释）。

## 判据（刻意收窄，避免误报）

本项目里有大量**无害**的裸 `title` / `update_at` ——
`ConversationDAO` 的单表查询（`SELECT id, assistant_id, title, ... FROM conversationentity`）
完全正常，**不在本检查范围内**。

本检查只看两类字符串：

| # | 范围 | 要求 |
|:--:|---|---|
| 1 | 任何提及 `message_fts` 的**读**语句（`SELECT`） | `title` / `update_at` 必须**带表名前缀**，且前缀**不能是 `message_fts`** |
| 2 | `database/fts/` 包内**任何** SQL 字符串 | 同上（覆盖排序枚举这类会被拼进 FTS 查询的片段） |

**豁免**：`INSERT` 的列清单（写路径本来就要列出列名）、`CREATE VIRTUAL TABLE`（DDL）。

## 为什么要拼接字符串字面量

Kotlin 里的长 SQL 是这么写的：

    "SELECT node_id, ..., conversation_id," +
        "conversationentity.title, conversationentity.update_at," +
    "FROM message_fts" + ...

**含 `title` 的那一段并不含 `message_fts`** —— 逐字面量扫会漏掉。
故这里先把「只隔着 `+`、空白与行注释」的相邻字面量接成一条语句再判。

## 用法

    python3 scripts/check_fts_column_usage.py

退出码：0 = 无违规，1 = 有。
"""

from __future__ import annotations

import glob
import pathlib
import re
import sys

SOURCE_GLOB = "app/src/main/java/**/*.kt"

# 这两列是「只写不读」的冗余副本
FORBIDDEN_COLUMNS = ("title", "update_at")

# 唯一的权威来源；出现这个前缀是**允许**的
AUTHORITATIVE_TABLE = "conversationentity"

# fts 包（所有 FTS 相关 SQL 都该住在这里）
FTS_PACKAGE = "/data/db/fts/"

LITERAL = re.compile(r'"""((?:[^"]|"(?!""))*)"""|"((?:[^"\\]|\\.)*)"', re.S)

# 字面量之间允许出现的东西（Kotlin 的 `+` 拼接与注释）
GLUE_OK = re.compile(r"^[\s+]*$")
GLUE_WITH_COMMENT = re.compile(r"^(?:[\s+]|//[^\n]*\n|/\*.*?\*/)*$", re.S)


def literals_of(text: str) -> list[tuple[int, int, str]]:
    """返回 (起始, 结束, 内容) 列表。"""
    out: list[tuple[int, int, str]] = []
    for m in LITERAL.finditer(text):
        content = m.group(1) if m.group(1) is not None else m.group(2)
        out.append((m.start(), m.end(), content))
    return out


def statement_runs(text: str) -> list[str]:
    """把「只隔着 `+`/空白/注释」的相邻字面量接成一条语句。"""
    lits = literals_of(text)
    runs: list[str] = []
    current: list[str] = []
    prev_end: int | None = None
    for start, end, content in lits:
        if prev_end is not None:
            gap = text[prev_end:start]
            if not (GLUE_OK.match(gap) or GLUE_WITH_COMMENT.match(gap)):
                if current:
                    runs.append("\n".join(current))
                current = []
        current.append(content)
        prev_end = end
    if current:
        runs.append("\n".join(current))
    return runs


def is_write_only(statement: str) -> bool:
    """写路径：INSERT 的列清单、DDL。这两类列出列名是**必要**的，不算读。"""
    upper = statement.upper()
    return "INSERT INTO" in upper or "CREATE VIRTUAL TABLE" in upper


def bare_column_hits(statement: str) -> list[str]:
    """找出「裸读」或「从 message_fts 读」的列。返回违规片段。"""
    hits: list[str] = []
    for column in FORBIDDEN_COLUMNS:
        for m in re.finditer(rf"\b{re.escape(column)}\b", statement):
            before = statement[: m.start()]
            # 形如 `xxx.title` —— 取出前缀
            prefix_match = re.search(r"([A-Za-z_][\w]*)\.\s*$", before)
            if prefix_match:
                qualifier = prefix_match.group(1)
                if qualifier == "message_fts":
                    hits.append(f"`message_fts.{column}`")
                elif qualifier == AUTHORITATIVE_TABLE:
                    continue  # 权威真源，正解
                else:
                    continue  # 别的表（如 join 里的别名），不在本检查范围
            else:
                # 裸列名。别名定义（`AS title`）不是读，放过
                if re.search(r"\bAS\s+$", before, re.I):
                    continue
                hits.append(f"裸 `{column}`")
    return hits


def main() -> int:
    if not pathlib.Path("app/src/main/java").exists():
        print("[错误] 工作目录不对（应在仓库根），找不到 app/src/main/java", file=sys.stderr)
        return 1

    errors: list[str] = []
    scanned_statements = 0
    fts_statements = 0

    for path in sorted(glob.glob(SOURCE_GLOB, recursive=True)):
        normalized = path.replace("\\", "/")
        try:
            text = pathlib.Path(normalized).read_text(encoding="utf-8")
        except OSError:
            continue

        in_fts_package = FTS_PACKAGE in normalized

        for statement in statement_runs(text):
            upper = statement.upper()
            mentions_fts = "message_fts" in statement
            is_read_on_fts = mentions_fts and "SELECT" in upper and not is_write_only(statement)

            # 判据 1：读 message_fts 的语句
            # 判据 2：fts 包内任何 SQL 字符串（覆盖会被拼进 FTS 查询的排序片段）
            if not is_read_on_fts and not in_fts_package:
                continue

            # 是不是「SQL 片段」。
            # ⚠️ 只认 SELECT/FROM/... 会漏掉一类**关键**片段：排序枚举的值
            # （`"rank, update_at DESC"` 会被拼进 FTS 查询的 ORDER BY）——
            # 它自身没有任何子句关键字，实测就是这么漏掉的。
            # 故把 ASC / DESC / BY 也算进来。
            looks_like_sql = re.search(
                r"\b(SELECT|FROM|WHERE|ORDER\s+BY|GROUP\s+BY|INSERT|ASC|DESC|BY)\b", upper
            )
            mentions_forbidden = any(
                re.search(rf"\b{re.escape(c)}\b", statement) for c in FORBIDDEN_COLUMNS
            )
            if not looks_like_sql:
                continue  # 不是 SQL 片段（如日志文案 / UI 文案）

            scanned_statements += 1
            if mentions_fts:
                fts_statements += 1

            if is_write_only(statement):
                continue

            hits = bare_column_hits(statement)
            if hits:
                errors.append(
                    f"{normalized}: {'、'.join(sorted(set(hits)))} —— 语句片段: "
                    + " ".join(statement.split())[:96]
                )

    # 空索引保护：判据依赖「扫得到语句」，扫不到说明解析坏了或范围写错了
    if scanned_statements == 0:
        print("[错误] 一条相关语句都没扫到 —— 索引为空，检查形同虚设", file=sys.stderr)
        return 1
    if fts_statements == 0:
        print("[错误] 没找到任何提及 message_fts 的读语句 —— 判据可能已失效", file=sys.stderr)
        return 1

    print(
        f"FTS 冗余列自检:扫过 {scanned_statements} 条相关语句"
        f"（其中提及 message_fts 的 {fts_statements} 条）,违规 {len(errors)} 处"
    )

    if not errors:
        print("✅ 无问题（两列冗余副本只写不读）")
        return 0

    print()
    for err in errors:
        print(f"❌ {err}")
    print()
    print("这两列是**只写不读**的冗余副本：它们的值停在「最后写入那一刻」，")
    print("读它们会得到过期数据 —— 排序会错、标题会旧，而且**不会报错**。")
    print(f"权威真源在 {AUTHORITATIVE_TABLE}，请用 JOIN 取。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
