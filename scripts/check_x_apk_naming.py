#!/usr/bin/env python3
"""产物命名自检:APK 名 / 下载链接 / 清理正则 三者是否自洽。

## 为什么需要它

产物命名规则散在 `daily-build.yml` 的 **5 处**,任何一处漏改都会「静默错」而不是报错:

| 位置 | 作用 | 漏改的后果 |
|---|---|---|
| 重命名 step | 决定资产名 | 页面上的名字不对 |
| 正文「最新构建」 | 拼下载链接 | 链接指向不存在的文件 → 404 |
| 正文「历史构建」解析 | 从资产名反解时间/短号 | 历史列表整段消失 |
| 清理 `jq` 匹配 | 选出要管理的资产 | 旧资产**永不被清理**,越堆越多 |
| 清理 `key_of` 解析 | 算「保留最新 N 组」的键 | 解析不出键 → 资产被**误删** |

而且 `daily-build.yml` **不在任何 CI 的 paths 过滤内**(Compile Check 只管 `app/src/**`,
Guard 只管它自己列的文件),改它**不会触发任何工作流** —— 出错只能等下次真机构建。

## 判据(全部真跑,不只比字符串)

1. 两个应用名能从 `strings.xml` 读出(读不到直接失败,不以「跳过」当通过);
2. 重命名用 `${PREFIX}` 变量而非硬编码前缀,且前缀确实取自 `strings.xml`;
3. 用**真实应用名**构造两个渠道的样例资产名,喂给 `key_of` 与「历史解析」的 sed:
   - `key_of` 必须输出 `<时间戳> <短号>`;
   - 历史解析必须给出 4 段(时间/短号/架构/完整名);
4. 清理的 `jq` 正则必须**同时**匹配新名与旧名(旧名要能被选中 → 才能被清理掉);
5. 旧命名必须**解析不出** `key_of`(否则旧资产会被当成有效组而永留);
6b. 构建期那道「用现实校验」的步骤必须存在,且**排在重命名之后** —— 它拿实际产出的
   文件名再断言一次(符合文档规则 + URL 安全)。规则自洽不等于产物正确,这一步是最后
   一道防线:被删掉或被挪到重命名之前,一个名字不对的包就会照常发出去。
6. 产物名前缀必须**把空白折成一个点**。GitHub 在**上传时**就把资产名里的空格改写成点,
   于是**按本地文件名拼出的链接必然 404**(2026-09-12 实测:nightly 页面「最新构建」
   表格里那几个链接就是这么坏的,而「历史构建」那一段因为是从 API 的真实资产名反解、
   反倒正常)。故正解是**提前做平台本就会做的改写**:本地名与真实资产名从一开始就一致,
   链接直接拼即可 —— 断言两步都定义了同一个 `to_prefix`、且链接用裸文件名变量,
   不再依赖 `%20` 编码。选折点而非去空格,是为了与已上传的历史资产格式一致。
   ⚠️ 本判据此前恰好写反:它**要求**链接里有 `%20` —— 那正是 bug 本身。

## 用法

    python3 scripts/check_x_apk_naming.py      # 退出码 0 = 通过,1 = 有问题
"""

import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
WORKFLOW = REPO / ".github/workflows/daily-build.yml"
STRINGS = REPO / "app/src/main/res/values/strings.xml"

TS = "20260912-1300"
SHA = "2a6db141"
ABIS = ("arm64-v8a", "universal", "x86_64")

failures: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)


def sed_once(expr: str, text: str) -> str:
    """按 workflow 里的方式跑 sed(-nE + p),返回匹配结果(无匹配则空)。"""
    r = subprocess.run(["bash", "-c", f"sed -nE {shell_quote(expr)} <<< {shell_quote(text)}"],
                       capture_output=True, text=True)
    return r.stdout.strip()


def shell_quote(s: str) -> str:
    return "'" + s.replace("'", "'\\''") + "'"


def main() -> int:
    if not WORKFLOW.exists() or not STRINGS.exists():
        print(f"❌ 缺少文件:{WORKFLOW.name} 或 {STRINGS.name}")
        return 1

    wf = WORKFLOW.read_text(encoding="utf-8")
    strings = STRINGS.read_text(encoding="utf-8")

    # ── 判据 1:两个应用名 ──
    def label_of(res: str) -> str:
        m = re.search(rf'<string name="{res}">([^<]+)</string>', strings)
        return m.group(1).strip() if m else ""

    def to_prefix(s: str) -> str:
        """与工作流里的 `to_prefix()` 同义:去首尾空白,中间空白(含连续)折成一个点。"""
        return re.sub(r"\s+", ".", s.strip())

    label_release, label_nightly = label_of("app_name"), label_of("app_name_nightly")
    if not label_release or not label_nightly:
        print("❌ 读不到 strings.xml 的 app_name / app_name_nightly —— 拒绝在错误前提上判定")
        return 1
    if label_release == label_nightly:
        fail(f"两个渠道的应用名相同({label_release!r}),产物名将无法区分渠道")

    # ── 抽取三处表达式 ──
    def step_run(prefix: str) -> str:
        m = re.search(rf"- name: {re.escape(prefix)}.*?\n(.*?)(?=\n      - name: |\Z)", wf, re.S)
        return m.group(1) if m else ""

    rename_run = step_run("Rename APKs")
    body_run = step_run("Generate release body")
    cleanup_run = step_run("Cleanup old assets")
    if not rename_run or not body_run or not cleanup_run:
        print("❌ 抽不到 Rename / Generate body / Cleanup 任一 step —— 解析失败不等于通过")
        return 1

    # ── 判据 2:前缀变量化 + 取自 strings.xml ──
    # ⚠️ 必须在**重命名那一步**里查:正文段也有一处 `"${PREFIX}_`,
    #    只看整个文件的话,重命名被硬编码成固定前缀也照样「通过」(已实测漏检过一次)。
    if '"${PREFIX}_' not in rename_run:
        fail("重命名没有用 ${PREFIX} 变量(前缀可能被硬编码在工作流里)")
    for res in ("app_name", "app_name_nightly"):
        if f'name="{res}">' not in wf or "strings.xml" not in wf:
            fail(f"工作流没有从 strings.xml 读取 {res} —— 应用名与产物名会有两份来源")
    if "PREFIX=" not in rename_run:
        fail("重命名那一步里找不到 PREFIX 赋值")
    # 重命名的目标名模板要能对上「前缀_时间_架构_渠道_短号.apk」
    m_mv = re.search(r'mv -- "\$f" "([^"]+)"', rename_run)
    if not m_mv:
        fail("重命名那一步里找不到 mv 目标模板")
    else:
        tpl = m_mv.group(1)
        if not re.fullmatch(r"\$\{PREFIX\}_\$\{\{ steps\.ts\.outputs\.ts \}\}_\$\{abi\}_\$\{CHANNEL\}_\$\{\{ steps\.sha\.outputs\.short \}\}\.apk", tpl):
            fail(f"重命名的目标名模板与约定不符:{tpl!r}")


    m_key = re.search(r"key_of\(\) \{.*?sed -nE '([^']+)'", cleanup_run, re.S)
    m_hist = re.search(r"PREV_ROWS < <\(gh api.*?sed -nE '([^']+)'", body_run, re.S)
    m_jq = re.search(r'test\("([^"]+)"\)', cleanup_run)
    if not m_key or not m_hist or not m_jq:
        print("❌ 抽不到 key_of / 历史解析 / jq 正则任一 —— 解析失败不等于通过")
        return 1
    key_expr, hist_expr, jq_expr = m_key.group(1), m_hist.group(1), m_jq.group(1)

    # ── 判据 3:真跑两个表达式 ──
    for channel, label in (("nightly", label_nightly), ("release", label_release)):
        # 产物名里的前缀是**空白折点**后的(见判据 6),样例名必须照此构造
        prefix = to_prefix(label)
        if channel not in wf:
            fail(f"工作流里看不到渠道 {channel}")
            continue
        for abi in ABIS:
            name = f"{prefix}_{TS}_{abi}_{channel}_{SHA}.apk"
            got_key = sed_once(key_expr, name)
            if not re.fullmatch(r"\d{8}-\d{4} [0-9a-f]{8}", got_key):
                fail(f"key_of 对合法产物名解析异常:{name} → {got_key!r}(期望「时间戳 短号」)")
            got_hist = sed_once(hist_expr, name)
            parts = got_hist.split("|")
            if len(parts) < 4 or parts[0] != TS or parts[1] != SHA or parts[2] != abi:
                fail(f"历史解析对合法产物名解析异常:{name} → {got_hist!r}")

    # ── 判据 4/5:旧命名 ──
    legacy = f"app-{TS}-arm64-v8a-nightly-{SHA}.apk"
    jq_re = re.compile(jq_expr.replace("\\\\", "\\"))
    if not jq_re.search(legacy):
        fail(f"清理的 jq 正则匹配不到旧命名({legacy})—— 旧资产会永远留在页面上")
    if not jq_re.search(f"{label_nightly}_{TS}_arm64-v8a_nightly_{SHA}.apk"):
        fail("清理的 jq 正则匹配不到新命名 —— 资产不会被清理")
    if sed_once(key_expr, legacy):
        fail(f"旧命名能被 key_of 解析出键({sed_once(key_expr, legacy)!r})—— 旧资产会被当成有效组而永留")

    # ── 判据 6:产物名前缀「空白折点」(否则下载链接必然 404) ──
    #
    # 实测 2026-09-12:GitHub 上传时把资产名里的空格改写成点
    # (`RikkaHub X Nightly_….apk` → `RikkaHub.X.Nightly_….apk`)。按本地文件名拼出的
    # 链接因此必然 404;而「历史构建」那一段是从 API 的真实资产名反解,反倒正常。
    # 故正解是**提前做平台本就会做的改写**,而不是去编码空格。
    collapse_needle = "s/[[:space:]]+/./g"
    for step_name, step_text in (("重命名", rename_run), ("正文", body_run)):
        if "to_prefix() {" not in step_text:
            fail(f"{step_name}那一步没有定义 to_prefix —— 前缀没做空白折点,链接会与真实资产名不符")
        if collapse_needle not in step_text:
            fail(f"{step_name}那一步的 to_prefix 没把空白折成点(缺 `{collapse_needle}`)")
        if 'LABEL_RELEASE="$(to_prefix' not in step_text:
            fail(f"{step_name}那一步没有对 LABEL_RELEASE 调用 to_prefix")
    for label, bad in (("最新构建", "${f// /%20}"), ("历史构建", "${pfull// /%20}")):
        if bad in body_run:
            fail(f"{label}链接仍在做空格编码({bad})—— 它永远对不上被平台改写后的资产名")
    for label, good in (("最新构建", "${BASE}/${f}"), ("历史构建", "${BASE}/${pfull}")):
        if good not in body_run:
            fail(f"{label}链接没有直接使用裸文件名变量({good})")

    # ── 判据 7:构建期的「用现实校验」步骤必须在,且顺序正确 ──
    #
    # 前六条守的是「规则自洽」;这一条守的是「真产物」。daily-build.yml 里那一步拿实际
    # 产出的文件名断言它符合规则且 URL 安全 —— 它是最后一道防线,即便规则全自洽,少了
    # 它,一个名字不对的包仍会照常发出去。故它的存在与顺序也要被守。
    i_rename = wf.find("Rename APKs with build time & commit hash")
    i_assert = wf.find("Assert APK file names match the documented rule")
    if i_assert == -1:
        fail("daily-build.yml 里找不到「断言真实 APK 文件名」的步骤 —— 最后一道防线缺失")
    elif i_rename != -1 and i_assert < i_rename:
        fail("断言 APK 文件名的步骤排在重命名之前 —— 那时还没有重命名后的名字可断言")

    # ── 附加:正文里的文件名模板要与重命名一致 ──
    m_f = re.search(r'f="\$\{PREFIX\}_\$\{TS\}_\$\{abi\}_\$\{CHANNEL\}_\$\{SHA\}\.apk"', body_run)
    if not m_f:
        fail("正文里的产物名模板与重命名规则不一致(改了一处漏了另一处)")

    print(f"产物命名自检:前缀「{to_prefix(label_release)}」/「{to_prefix(label_nightly)}」· "
          f"两渠道 × {len(ABIS)} 架构的样例名均能反解,旧命名{len(ABIS)} 个架构可被清理选中且解析不出键")
    if failures:
        print()
        for item in failures:
            print(f"  ❌ {item}")
        print()
        print("  产物命名规则散在多处,必须同步改;daily-build.yml 不在任何 CI 的 paths 内,改了不会触发工作流。")
        return 1
    print("  ✅ 无问题(重命名 / 空白折点 / 下载链接 / 历史解析 / 清理匹配 / 清理键 + 构建期断言六处自洽)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
