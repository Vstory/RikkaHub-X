#!/usr/bin/env python3
"""诊断脱敏自检 —— 守「导出物一律过脱敏」「规则顺序」「每条规则都有样例」。

## 为什么是**静态**检查器,而不是再写一套脱敏

「掩对了没有」是**行为**,归 Kotlin 单测(`XLogScrubTest`,跑在 X Custom Guard 里)。
本文件**刻意不复刻任何正则** —— 那会得到两套判据,迟早漂移,而漂移的方向恰好是
「检查器说没事、真导出却漏了」。这里只守四类**静态就能判死**的事:

| # | 判据 | 漏了会怎样 |
|---|---|---|
| 1 | 每种 `PendingExport` 的写出分支都调了脱敏 | 新增/改动导出路径**静默**不经脱敏 —— 2026-09-12 实测发生过:三条文本导出一条都没过 |
| 1b | 导出形态数不少于 `MIN_EXPORT_KINDS` | 解析式样失效时会扫到 0 种形态,而「0 种都过了脱敏」永远成立 —— 那种绿灯最坏 |
| 2 | `RULES` 的**顺序**不变式 | 顺序即优先级:`Authorization` 不在最前 → 令牌主体留在后面;`sk-ant-` 排在 `sk-` 之后 → 长前缀被吃掉一半、留尾巴 |
| 3 | 每条规则在 `XLogScrubTest` 里都有样例 | 没被验证过「能报错」的检查不算检查;没样例的规则 = 从没跑过的规则 |
| 4 | 反例组(用量数字)仍在测试里 | 误掩不会有人发现,只会让日志「少了一半」——掩掉 `max_tokens` 就是抹了最该看的诊断信息 |

另两条便宜的:`ENABLED` 闸门必须显式为 `true`(关掉它是一次产品决策,不是改个值);
掩码 `MASK` 不许在别处硬编码(清单文案必须插值引用,否则改掩码会出现两套)。

## 索引下限

规则数 < 12、或测试里 `assertScrub` 少于 25 处 → **报错退出**。
否则写法一变就会「解析出 0 条 → 全部判据自动成立 → 永远绿灯」。

## 用法

    python3 scripts/check_x_diag_redaction.py [--root <仓库根>]

`--root` 供反向验证:把文件拷到临时目录故意改坏,断言它真能报出来。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DIAG = "app/src/main/java/me/rerere/rikkahub/x/diag"
PAGE = "app/src/main/java/me/rerere/rikkahub/ui/pages/diagnostic/DiagnosticPage.kt"
SCRUB = DIAG + "/XLogScrub.kt"
SCRUB_TEST = "app/src/test/java/me/rerere/rikkahub/x/diag/XLogScrubTest.kt"

# 下限(见模块注释「索引下限」)
MIN_RULES = 12
MIN_SAMPLES = 25

# 导出形态数。**2026-09-13:3 → 2**。
#
# 原先三种(压缩包 / 文本记录 / 应用日志文本),诊断页上摆了四张卡。实际只有两种内容 ——
# 「导出应用日志」是压缩包里的一个文件、「导出诊断记录(已脱敏/完整)」是同一份文本的两种
# 路径处理。收敛之后只剩:压缩包(主入口)与失败详情文本(在关键失败卡片里,不是导出区)。
#
# ⚠️ 这个数**必须跟着实际形态走**,而不是「凑够就不报」。它和 MIN_RULES 一样是
# 「永远通过」的防线:若哪天解析式样失效、扫到 0 种形态,这里会先报错而不是放行。
MIN_EXPORT_KINDS = 2

# ── 判据 2:顺序不变式 ────────────────────────────────────────────────
# 每条给「在 RULES 块里的定位片段」,要求首次出现的位置**严格递增**。
# 片段取源码里逐字存在的写法(不是正则语义),故它同时钉住「规则没被删掉」。
#
# ⚠️ 匹配一律用**纯子串查找**,不能拿这些片段当正则 —— 2026-09-12 首版就是这么错的:
#    `sk-[A-Za-z0-9]{16,}`(想找源码里那行)被当成「sk- 后跟 16 个以上字母数字」,
#    而源码里紧跟的是 `[`,于是永远匹配不上;`[?&]`(查询参数规则的定位片段)被当成
#    字符组,匹配到**更早**的任意一个 `?`,于是报出「顺序不对」的假阳性。
#    两个假阳性都指向同一条纪律:检查器自己的判据也要反向验证。
ORDERED_MARKERS = [
    ("Authorization 头(后面整段都是凭据,必须最先)", "authorization"),
    ("新式 GitHub PAT", "github_pat_"),
    ("GitHub PAT", "gh[pousr]_"),
    ("Anthropic(必须早于 sk-)", "sk-ant-"),
    ("OpenRouter(必须早于 sk-)", "sk-or-v1-"),
    ("OpenAI 项目级(必须早于 sk-)", "sk-proj-"),
    ("OpenAI(短前缀,必须排在长前缀之后)", "sk-[A-Za-z0-9]{16,}"),
    ("GitLab PAT", "glpat-"),
    ("xAI", "xai-"),
    ("HuggingFace", "hf_"),
    ("Google API key", "AIza"),
    ("AWS access key id", "AKIA|ASIA"),
    ("JWT", "eyJ"),
    ("裸 Bearer(必须早于键值规则)", "Bearer "),
    ("Cookie 两个方向", "Set-Cookie|Cookie"),
    ("键值形式", "secret[_-]?access[_-]?key"),
    ("URL 查询参数(最后)", "[?&]"),
]

# ── 判据 3:每条规则都要有样例 ────────────────────────────────────────
# marker → 测试文件里必须出现的样例。规则新增时必须同时补样例,否则这里报错。
#
# ⚠️ 样例一律取**完整的样例值**(不是前缀),且要长到不可能被「掩码后的期望值」满足 ——
#    2026-09-12 首版写的是前缀(如 `sk-proj-`),反向验证时把样例正文换成别的、
#    只留下期望值 `"sk-proj-***"`,检查器**照样通过** —— 那等于没查。
#    改成整值之后,样例被删就必报。
SAMPLE_FOR_RULE = [
    ("authorization", "Authorization: Bearer $JWT"),
    ("github_pat_", "github_pat_11ABCDEFG0abcdefghij_klmnopqrstuvwxyz"),
    ("gh[pousr]_", "ghp_abcdefghijklmnopqrstuvwxyz01"),
    ("sk-ant-", "sk-ant-api03-abcdefghijklmnopqr"),
    ("sk-or-v1-", "sk-or-v1-abcdefghijklmnopqrst"),
    ("sk-proj-", "sk-proj-abcdefghijklmnopqrstuvwxyz"),
    ("sk-[A-Za-z0-9]{16,}", "sk-abcdefghijklmnopqrstuvwxyz"),
    ("glpat-", "glpat-abcdefghijklmnopqrst"),
    ("xai-", "xai-abcdefghijklmnopqrstuvwx"),
    ("hf_", "hf_abcdefghijklmnopqrstuvwx"),
    ("AIza", "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7"),
    ("AKIA|ASIA", "AKIAIOSFODNN7EXAMPLE"),
    ("eyJ", "eyJhbGciOiJIUzI1NiJ9"),
    ("Bearer ", "Bearer $JWT"),
    ("Set-Cookie|Cookie", "Cookie: a=1; sessionid=deadbeef"),
    ("secret[_-]?access[_-]?key", "aws_secret_access_key=wJalrXUtnFEMI"),
    ("private[_-]?key", "private_key=LS0tLS1CRUdJTiBSU0E"),
    ("api[_-]?key", "api_key=sk-abcdefghijklmnopqrstuvwxyz"),
    ("[?&]", "?api_key=abc123&page=2"),
]

# ── 判据 4:反例组(误掩才是看不见的风险)────────────────────────────
NEGATIVE_SAMPLES = ["max_tokens", "token_count"]

# ── 判据 1:导出形态与脱敏调用 ───────────────────────────────────────
# 每个 PendingExport 分支里至少要出现其中一处 —— 它们把内容交给用户前过一遍脱敏。
REDACTION_CALLS = ("XLogScrub.", "XDiagZip.write(")


class Problem(Exception):
    """解析失败 —— 与「发现问题」区分开(见 main)。"""


def read(root: Path, rel: str) -> str:
    path = root / rel
    if not path.is_file():
        raise Problem("找不到 " + rel + "(仓库结构变了?)")
    return path.read_text(encoding="utf-8")


def block(text: str, start_marker: str, open_char: str = "(", close_char: str = ")") -> str:
    """取从 start_marker 起**配平**的一段 —— 比正则找收尾稳(规则里本来就有括号)。"""
    i = text.find(start_marker)
    if i < 0:
        raise Problem("找不到 `" + start_marker + "`")
    j = text.find(open_char, i)
    if j < 0:
        raise Problem("`" + start_marker + "` 后面没有 `" + open_char + "`")
    depth = 0
    for k in range(j, len(text)):
        if text[k] == open_char:
            depth += 1
        elif text[k] == close_char:
            depth -= 1
            if depth == 0:
                return text[j:k + 1]
    raise Problem("`" + start_marker + "` 的括号不配平")


def check_export_paths(root: Path) -> list:
    """判据 1:导出形态 ↔ 写出分支 ↔ 脱敏调用 三者对齐。"""
    problems = []
    page = read(root, PAGE)

    sealed = block(page, "sealed interface PendingExport", "{", "}")
    kinds = re.findall(r"data class (\w+)\s*\(", sealed)
    if len(kinds) < MIN_EXPORT_KINDS:
        raise Problem(
            PAGE + ":只解析出 " + str(len(kinds)) + " 种导出形态(期望至少 "
            + str(MIN_EXPORT_KINDS) + ")—— 解析式样可能已与实际写法不符;"
            "这时**必须报错**,否则下面的判据会全部自动成立。"
        )

    write_fn = block(page, "private fun writeExport", "{", "}")
    spans = list(re.finditer(r"is PendingExport\.(\w+)\s*->", write_fn))
    if len(spans) != len(kinds):
        problems.append(
            PAGE + ":导出形态 " + str(len(kinds)) + " 种,写出分支 " + str(len(spans))
            + " 个 —— 形态: " + str(sorted(kinds)) + " / 分支: "
            + str(sorted(m.group(1) for m in spans))
        )
    for idx, m in enumerate(spans):
        name = m.group(1)
        end = spans[idx + 1].start() if idx + 1 < len(spans) else len(write_fn)
        body = write_fn[m.end():end]
        if not any(call in body for call in REDACTION_CALLS):
            problems.append(
                PAGE + ":导出形态 " + name + " 的写出分支里没有任何脱敏调用("
                + " / ".join(REDACTION_CALLS) + ")—— 这份内容会原样交给用户。"
            )
    return problems


def check_rules(root: Path):
    """判据 2 + 3:规则顺序与样例覆盖。返回 (问题, 规则数)。"""
    problems = []
    scrub = read(root, SCRUB)
    rules = block(scrub, "private val RULES", "(", ")")
    count = len(re.findall(r"\bRule\(", rules))
    if count < MIN_RULES:
        raise Problem(
            SCRUB + ":只解析出 " + str(count) + " 条规则(期望至少 " + str(MIN_RULES)
            + ")—— 规则表被删空或被改写;这时**必须报错**,否则下面判据全部自动成立。"
        )

    # 顺序:每个 marker 首次出现的位置必须严格递增(纯子串查找,见上面那段警告)
    positions = []
    for label, marker in ORDERED_MARKERS:
        at = rules.find(marker)
        if at < 0:
            problems.append(
                SCRUB + ":找不到 " + label + " 的规则(定位片段 `" + marker + "`)—— 规则被删了?"
            )
            continue
        positions.append((at, label, marker))
    for prev, cur in zip(positions, positions[1:]):
        if cur[0] < prev[0]:
            problems.append(
                SCRUB + ":规则顺序不对 —— 「" + cur[1] + "」排在「" + prev[1] + "」之前。"
                "顺序即优先级(见类注释 ①):排错会让长前缀被短前缀吃掉、令牌主体留在后面。"
            )

    # 样例覆盖
    test = read(root, SCRUB_TEST)
    samples = len(re.findall(r"assertScrub\(", test))
    if samples < MIN_SAMPLES:
        raise Problem(
            SCRUB_TEST + ":只数到 " + str(samples) + " 处 assertScrub(期望至少 "
            + str(MIN_SAMPLES) + ")—— 用例被删空或写法变了;这时**必须报错**,"
            "否则「每条规则都有样例」会假成立。"
        )
    for marker, sample in SAMPLE_FOR_RULE:
        if marker not in rules:
            continue  # 规则本身没了 —— 已在顺序判据里报过,不重复
        if sample not in test:
            problems.append(
                SCRUB_TEST + ":规则 `" + marker + "` 没有对应样例(期望样例里出现 `"
                + sample + "`)—— 没被验证过的规则等于从没跑过。"
            )
    for negative in NEGATIVE_SAMPLES:
        if negative not in test:
            problems.append(
                SCRUB_TEST + ":缺少反例 `" + negative + "` —— 误掩不会被发现,"
                "只会让日志少一半;这组反例是用例的主体。"
            )
    return problems, count


def check_gates(root: Path) -> list:
    """判据 5:闸门与掩码单点。"""
    problems = []
    scrub = read(root, SCRUB)
    m = re.search(r"const val ENABLED: Boolean = (true|false)", scrub)
    if not m:
        raise Problem(SCRUB + ":找不到 `const val ENABLED: Boolean = true` —— 闸门写法变了?")
    if m.group(1) != "true":
        problems.append(
            SCRUB + ":脱敏闸门被关掉了(ENABLED = false)。这是一次**产品决策**,不是改个值:"
            "关闭意味着导出物可能带凭据,必须同时改 XDiagZip 的 CAUTION 段、XLogScrub 的"
            "KDoc 与本检查器,并在提交信息里写明理由。"
        )

    for path in sorted((root / DIAG).rglob("*.kt")):
        if path.name == "XLogScrub.kt":
            continue
        if '"***"' in path.read_text(encoding="utf-8"):
            problems.append(
                str(path.relative_to(root)) + ':硬编码了掩码 "***" —— 必须引用 '
                "XLogScrub.MASK,否则改掩码时会出现两套文案。"
            )
    if '"***"' not in scrub:
        problems.append(SCRUB + ':找不到掩码常量 MASK = "***"')
    return problems


def check(root: Path):
    problems = check_export_paths(root)
    rule_problems, count = check_rules(root)
    problems.extend(rule_problems)
    problems.extend(check_gates(root))
    return problems, count


def main(argv) -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--root", default=None, help="仓库根(默认取脚本所在目录的上一级)")
    args = ap.parse_args(argv[1:])

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent
    try:
        problems, count = check(root)
    except Problem as exc:
        print("[FAIL] 诊断脱敏自检无法进行:" + str(exc))
        return 1

    if problems:
        print("[FAIL] 诊断脱敏自检发现 " + str(len(problems)) + " 处问题:")
        for p in problems:
            print("  - " + p)
        return 1

    print(
        "[CHECK PASS] " + str(count) + " 条规则:顺序不变式成立、每条都有样例、"
        "所有导出形态都过脱敏、闸门为开"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
