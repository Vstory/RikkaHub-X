#!/usr/bin/env python3
"""Koin 依赖注入自检：`get()` 取的类型，在模块里有没有登记。

## 为什么需要它（2026-09-11 实测事故：装机后一开聊天页就闪退）

给 `FilesManager` 加了一个**接口类型**的构造参数：

    class FilesManager(..., private val assetLedger: AssetLedger)

而模块里只写了实现类：

    single { AssetRepository(get(), get<Context>().filesDir) }

**Koin 按「注册的精确类型」解析，不认继承关系。** `AssetRepository : AssetLedger`
类型正确，但 `get<AssetLedger>()` 在**运行时**抛 `NoDefinitionFound`
→ Koin 建不出 `ChatVM` → `ChatVM` 直接依赖 `FilesManager` → **一开聊天页就崩**。

### 这个错误为什么必须靠专门检查

| 手段 | 能抓到吗 |
|---|---|
| 编译 | ❌ 类型完全正确 |
| 单测 | ❌ 单测**手工构造**对象，不经过 Koin 容器 |
| 其余检查 | ❌ 都不解析依赖图 |

**只有真机启动、真正解析依赖图时才暴露。** 代价是一次无效出包 + 一次装机。

## 判据

1. 收集每个 `di/*.kt` 里**登记的类型**（`single` / `single<T>` / `viewModel` /
   `viewModelOf` / `bind` / `androidContext`）。
2. 收集模块里**手动传参**的注册体内每个 `get()` / `get<T>()` 的**目标类型**
   （`get()` → 构造参数声明的类型；`get<T>()` → 就是 `T`）。
3. 逐个核对：**目标类型必须在登记集合里**。

## 为什么只查 `get()` 位置

位置是可靠的对应关系（构造参数按声明顺序），但**只对 `get()` 形式成立**：

- `ChatVM(id: String, ...)` 的 `id` 是 `String`，从来不会写成 `get()` → 跳过；
- `AssetRepository(get(), get<Context>().filesDir)` 第二个参数不是 `get()` → 跳过该位。

故规则是「**只核对写成 `get()` 的位置**」，而不是「核对每个构造参数是什么」——
后者一定会把 `String` / `Boolean` 这类参数误报成错误。

## 已知边界

- 不解析 `typealias`（出现时只提示，不判定）。
- 不判定泛型参数类型（`List<X>` 之类跳过，不猜）。
- 不解析 `viewModel<X> { params -> ... }` 体内的显式构造（那是按需构造，不算依赖声明）。

## 用法

    python3 scripts/check_koin_definitions.py

退出码：0 = 通过，1 = 有未登记的类型。
"""

from __future__ import annotations

import glob
import pathlib
import re
import sys

DI_GLOB = "app/src/main/java/me/rerere/rikkahub/di/*.kt"
SOURCE_GLOBS = ("app/src/main/java/**/*.kt", "workspace/src/main/java/**/*.kt")

# 手工传参的注册：`single {` / `single<X> {` / `factory {` …
REGISTRATION_OPEN = re.compile(r"\b(single|factory)\s*(?:<([\w.]+)>)?\s*(?:\([^)]*\))?\s*\{")

# ── 从注册体推出「登记了什么类型」的几种形态 ──
#
# 实测过四种，**每一种都是真实存在的写法，少覆盖一种就报成片误报**：
#
#   single { ConversationRepository(get(), ...) }            → X(       构造调用
#   single { get<AppDatabase>().conversationDao() }          → 取访问器的**返回类型**
#   single { AppDatabaseFactory.create(context) }            → 取工厂方法的**返回类型**
#   single { PebbleEngine.Builder()...build() }              → Builder 链，登记的是**前导类型**
#   single { val x = get(); WorkspaceManager(...) }          → 构造调用不在首行
CTOR_ANY = re.compile(r"\b([A-Z]\w*)\s*\(")
CALL_ON_TYPE = re.compile(r"\b([A-Z]\w*)\s*\.\s*(\w+)\s*\(")
CALL_ON_GET = re.compile(r"\bget\s*<\s*[\w.]+\s*>\s*\(\s*\)\s*\.\s*(\w+)\s*\(")
BARE_CALL = re.compile(r"(?<![\w.])(\w+)\s*\(")

# `Type.Builder()...build()` —— 表达式整体是 Type，不是 Builder
BUILDER_CHAIN = re.compile(r"^\s*([A-Z]\w*)\s*\.\s*Builder\s*\(")

# `fun name(...): ReturnType` —— 用来把「调用了某个访问器/工厂」换算成「登记了哪个类型」
FUNC_RET = re.compile(r"\bfun\s+(?:<[^>(]*>\s*)?(\w+)\s*\([^)]*\)\s*(?::\s*([^={\n]+))?")

# 类型来自**依赖库**、我们自己的源码里推不出静态类型的登记。
# 每个都要写清理由 —— 这是唯一一类「靠清单」的判定，必须能一眼看出为什么在这里。
LIBRARY_REGISTRATIONS = {
    # `Firebase.analytics` 是 Firebase SDK 的扩展属性，返回 FirebaseAnalytics。
    # 表达式类型来自库，本地推不出，故显式登记。
    "Firebase.analytics": "FirebaseAnalytics",
    "Firebase.crashlytics": "FirebaseCrashlytics",
}

# `androidContext(...)` 连带提供的类型。
#
# 实证依据：`ChatService(context = get())` / `ChatVM(context = get())` 的参数类型是
# `Application`，而项目里只有 `androidContext(this@RikkaHubApp)` 一行 —— 既然每个版本
# 都能正常启动（ChatService 是 createdAtStart 之外的普通单例，但首屏就会建），
# 说明 koin-android 的 androidContext 不是只登记 `Context`。故一并视为已提供。
ANDROID_CONTEXT_TYPES = ("Context", "Application")

# 视图模型登记
VIEWMODEL_OPEN = re.compile(r"\bviewModel\s*<\s*([\w.]+)\s*>\s*\{")
VIEWMODEL_OF = re.compile(r"\bviewModelOf\s*\(\s*::\s*(\w+)\s*\)")

# `single<Foo> { get<Bar>() }` —— 登记 Foo、委托给 Bar
DELEGATE = re.compile(r"^get\s*<\s*([\w.]+)\s*>\s*\(\s*\)\s*$")

# 取依赖的写法
GET_TYPED = re.compile(r"get\s*<\s*([\w.]+)\s*>\s*\(\s*\)")
GET_PLAIN = re.compile(r"get\s*\(\s*\)")

CLASS_DECL = re.compile(
    r"^[ \t]*(?:internal |private |abstract |sealed |data |open |enum |annotation )*"
    r"class\s+(\w+)"
    r"(?:\s*<[^>{}]*>)?\s*\(",
    re.M,
)

TYPE_ALIAS = re.compile(r"^[ \t]*(?:internal |private )?typealias\s+(\w+)\s*=", re.M)

ANDROID_CONTEXT = re.compile(r"\bandroidContext\s*\(")

# 解析失效的哨兵：低于这些数量说明解析器坏了，而不是代码没问题
MIN_CLASSES = 50
MIN_REGISTERED = 20


def strip_comments(text: str) -> str:
    """
    去掉行注释与块注释。

    **必须去**：本项目注释里常写示例代码（本次事故的说明里就出现了 `get<AssetLedger>()`
    字样）。不去注释会把说明文字当成真实调用，报出根本不存在的问题。
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def match_pair(text: str, open_index: int, open_char: str, close_char: str) -> int:
    """配对 `(`/`)` 或 `{`/`}`；找不到返回 -1。"""
    depth = 0
    for i in range(open_index, len(text)):
        char = text[i]
        if char == open_char:
            depth += 1
        elif char == close_char:
            depth -= 1
            if depth == 0:
                return i
    return -1


def split_top_level(text: str, separator: str = ",") -> list[str]:
    """按顶层分隔符切分（跳过 `<...>`、`(...)`、`{...}` 内的分隔符）。"""
    parts: list[str] = []
    depth = 0
    current: list[str] = []
    for char in text:
        if char in "<({[":
            depth += 1
        elif char in ">)}]":
            depth -= 1
        if char == separator and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    if current:
        parts.append("".join(current))
    return parts


def base_type(type_text: str) -> str | None:
    """
    `Foo?` → `Foo`；`List<Foo>` / `() -> Unit` → None（不判定，不猜）。
    """
    text = type_text.strip()
    if "->" in text or text.startswith("("):
        return None
    if "<" in text:
        return None
    text = text.rstrip("?").strip()
    if not re.fullmatch(r"[\w.]+", text):
        return None
    return text.split(".")[-1]


def constructor_params(sources: list[str]) -> dict[str, list[tuple[str, str]]]:
    """
    类名 → 主构造参数列表 `[(参数名, 类型名), ...]`（顺序保持，无法解析的记为 `?`）。

    **同时保留参数名**：`viewModel<X> { X(a = get(), b = get()) }` 用的是具名参数，
    位置对应在那里不成立，必须按名字查。
    """
    result: dict[str, list[tuple[str, str]]] = {}
    for path in sources:
        try:
            text = strip_comments(pathlib.Path(path).read_text(encoding="utf-8"))
        except OSError:
            continue
        for match in CLASS_DECL.finditer(text):
            open_index = match.end() - 1
            close_index = match_pair(text, open_index, "(", ")")
            if close_index < 0:
                continue
            params: list[tuple[str, str]] = []
            for part in split_top_level(text[open_index + 1:close_index]):
                piece = part.strip()
                if not piece:
                    continue
                piece = re.sub(r"^(?:private |internal |protected )*(?:val |var )?", "", piece)
                if ":" not in piece:
                    params.append(("?", "?"))
                    continue
                name, type_text = piece.split(":", 1)
                type_text = split_top_level(type_text, "=")[0]
                params.append((name.strip(), base_type(type_text) or "?"))
            result[match.group(1)] = params
    return result


def function_returns(sources: list[str]) -> dict[str, set[str]]:
    """
    函数名 → 可能的返回类型。

    用来解析「调用了某访问器 / 工厂」时登记的是哪个类型
    （`get<AppDatabase>().conversationDao()` → `ConversationDAO`）。

    **一个函数名对应多个返回类型时保留全部**：判定方按「任一命中即算已登记」处理，
    避免因重名而误报。
    """
    returns: dict[str, set[str]] = {}
    for path in sources:
        try:
            text = strip_comments(pathlib.Path(path).read_text(encoding="utf-8"))
        except OSError:
            continue
        for match in FUNC_RET.finditer(text):
            name, type_text = match.group(1), match.group(2)
            if not type_text:
                continue
            base = base_type(type_text)
            if base:
                returns.setdefault(name, set()).add(base)
    return returns


def registered_types(di_files: list[str], sources: list[str]) -> set[str]:
    """模块里登记的类型集合。"""
    types: set[str] = set()
    returns = function_returns(sources)

    def by_call(name: str) -> None:
        """把「调用了 name」换算成「登记了它返回的类型」。"""
        types.update(returns.get(name, ()))

    for path in di_files:
        try:
            text = strip_comments(pathlib.Path(path).read_text(encoding="utf-8"))
        except OSError:
            continue
        for match in REGISTRATION_OPEN.finditer(text):
            explicit = match.group(2)
            if explicit:
                types.add(explicit.split(".")[-1])
                continue
            end = match_pair(text, match.end() - 1, "{", "}")
            if end < 0:
                continue
            body = text[match.end():end]

            # ① 任意位置的构造调用 —— 不要求它在首行
            #    （`single { val c = get(); WorkspaceManager(...) }` 就不是首行）
            for ctor in CTOR_ANY.finditer(body):
                types.add(ctor.group(1))

            # ② 取访问器的返回类型：`get<AppDatabase>().conversationDao()`
            for accessor in CALL_ON_GET.finditer(body):
                by_call(accessor.group(1))

            # ③ 工厂 / 静态方法的返回类型：`AppDatabaseFactory.create(context)`
            for call in CALL_ON_TYPE.finditer(body):
                by_call(call.group(2))

            # ④ 裸调用：`provideFoo()`
            for call in BARE_CALL.finditer(body):
                by_call(call.group(1))

            # ⑤ Builder 链：`PebbleEngine.Builder()...build()` → 登记 PebbleEngine
            builder = BUILDER_CHAIN.match(body)
            if builder:
                types.add(builder.group(1))

            # ⑥ 类型来自依赖库的登记：`Firebase.analytics`
            bare = body.strip()
            if bare in LIBRARY_REGISTRATIONS:
                types.add(LIBRARY_REGISTRATIONS[bare])

        for match in VIEWMODEL_OPEN.finditer(text):
            types.add(match.group(1).split(".")[-1])
        for match in VIEWMODEL_OF.finditer(text):
            types.add(match.group(1))
        for match in re.finditer(r"\bbind\s+(\w+)::class", text):
            types.add(match.group(1))

    # `Context` / `Application` 由启动器 `androidContext(...)` 连带提供
    for path in sources:
        try:
            if ANDROID_CONTEXT.search(pathlib.Path(path).read_text(encoding="utf-8")):
                types.update(ANDROID_CONTEXT_TYPES)
                break
        except OSError:
            continue
    return types


# 具名参数：`name = value`
NAMED_ARG = re.compile(r"^(\w+)\s*=\s*(.+)$", re.S)

# 视图模型参数：`params.get()`
PARAMS_GET = re.compile(r"params\s*\.\s*get\s*\(\s*\)")


def resolve_ctor_args(
    body: str,
    ctor_open_index: int,
    params: list[tuple[str, str]],
    line: int,
    ctor_name: str,
) -> list[tuple[int, str, str]]:
    """
    解析一次构造调用的实参，返回 [(行号, 注册类型, 注入类型)]。

    支持两种传参风格（项目里两种都有）：

    - **位置**：`FilesManager(get(), get(), ...)` —— 按**序号**对应构造参数；
    - **具名**：`ChatVM(context = get(), ...)` —— 按**参数名**对应。

    只处理取值恰好是 `get()` / `get<T>()` 的实参；其它形态（`get<Context>().filesDir`、
    字面量）一律跳过 —— 它们要么不是依赖，要么解析不可靠，报出来就是误报。
    """
    out: list[tuple[int, str, str]] = []

    open_index = ctor_open_index
    close_index = match_pair(body, open_index, "(", ")")
    if close_index < 0:
        return out

    by_name = {name: type_name for name, type_name in params}

    for index, arg in enumerate(split_top_level(body[open_index + 1:close_index])):
        piece = arg.strip().replace("\n", " ")
        if not piece:
            continue
        if PARAMS_GET.search(piece):
            continue  # viewModel 的 params → 不是容器依赖

        named = NAMED_ARG.match(piece)
        if named:
            arg_name, value = named.group(1), named.group(2).strip()
            wanted_from_name = by_name.get(arg_name)
        else:
            arg_name, value = None, piece
            wanted_from_name = params[index][1] if index < len(params) else None

        if wanted_from_name in (None, "?"):
            continue  # 参数类型解析不出来 → 不判定（宁可漏报）

        typed = GET_TYPED.fullmatch(value)
        if typed:
            wanted = typed.group(1).split(".")[-1]
        elif GET_PLAIN.fullmatch(value):
            wanted = wanted_from_name
        else:
            continue  # 不是纯粹的 get() → 跳过

        out.append((line, ctor_name, wanted))
    return out


def injected_positions(path: str, constructors: dict[str, list[tuple[str, str]]]) -> list[tuple[int, str, str]]:
    """返回 [(行号, 注册类型, 注入类型)]。"""
    try:
        clean = strip_comments(pathlib.Path(path).read_text(encoding="utf-8"))
    except OSError:
        return []

    out: list[tuple[int, str, str]] = []

    # ① single / factory 注册体
    for match in REGISTRATION_OPEN.finditer(clean):
        end = match_pair(clean, match.end() - 1, "{", "}")
        if end < 0:
            continue
        body = clean[match.end():end]
        ctor = re.match(r"\s*([A-Z]\w*)\s*\(", body)
        if not ctor:
            continue
        ctor_name = ctor.group(1)
        params = constructors.get(ctor_name)
        if params is None:
            continue
        line = clean[:match.start()].count("\n") + 1
        out.extend(resolve_ctor_args(body, ctor.end() - 1, params, line, ctor_name))

    # ② viewModel<X> { ... X(...) } 注册体
    #
    # ⚠️ 这一段曾被我漏掉，而**正在出问题的 ChatVM 恰恰登记在这里** ——
    # 它的 8 个依赖全在 `viewModel<ChatVM> { params -> ChatVM(...) }` 体内。
    # 漏了它，检查器对「ChatVM 建不出来」这类崩溃完全无感。
    for match in VIEWMODEL_OPEN.finditer(clean):
        ctor_name = match.group(1).split(".")[-1]
        params = constructors.get(ctor_name)
        if params is None:
            continue
        end = match_pair(clean, match.end() - 1, "{", "}")
        if end < 0:
            continue
        body = clean[match.end():end]
        ctor = re.search(rf"\b{re.escape(ctor_name)}\s*\(", body)
        if not ctor:
            continue
        line = clean[:match.start()].count("\n") + 1
        out.extend(resolve_ctor_args(body, ctor.end() - 1, params, line, ctor_name))

    return out


def main() -> int:
    di_files = sorted(glob.glob(DI_GLOB))
    if not di_files:
        print(f"[错误] 找不到模块文件（{DI_GLOB}）—— 检查会全部通过，形同虚设", file=sys.stderr)
        return 1

    sources: list[str] = []
    for pattern in SOURCE_GLOBS:
        sources.extend(glob.glob(pattern, recursive=True))
    if not sources:
        print("[错误] 找不到源码 —— 检查形同虚设", file=sys.stderr)
        return 1

    constructors = constructor_params(sources)
    if len(constructors) < MIN_CLASSES:
        print(
            f"[错误] 只解析出 {len(constructors)} 个类的构造签名（预期 >{MIN_CLASSES}）—— "
            "解析大概率失效，检查形同虚设",
            file=sys.stderr,
        )
        return 1

    registered = registered_types(di_files, sources)
    if len(registered) < MIN_REGISTERED:
        print(
            f"[错误] 只收集到 {len(registered)} 个登记类型（预期 >{MIN_REGISTERED}）—— "
            "大概率漏收集，检查形同虚设",
            file=sys.stderr,
        )
        return 1

    problems: list[str] = []
    checked = 0
    for path in di_files:
        for line, ctor, wanted in injected_positions(path, constructors):
            checked += 1
            if wanted not in registered:
                problems.append(f"{path}:{line}: {ctor} 注入 {wanted} —— 模块里没有登记这个类型")

    print(
        f"Koin 依赖自检:解析 {len(constructors)} 个类的构造签名,"
        f"登记类型 {len(registered)} 个,核对 {checked} 处注入"
    )
    if not problems:
        print("✅ 无问题")
        return 0

    print()
    for item in problems:
        print(f"❌ {item}")
    print()
    print("Koin 按「注册的精确类型」解析,不认继承关系 —— 接口 / 父类要显式登记类型:")
    print("    single { Impl(...) }              // 只能取到 Impl")
    print("    single<Api> { get<Impl>() }       // 这样才能取到 Api")
    print("这类错误编译与单测都抓不到,只在真机启动解析依赖图时崩。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
