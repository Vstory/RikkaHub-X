// 容量表真源的**结构不变量**测试(数据层)。
//
// 与 ContextWindowMatchingTest 的分工:
//   语义层 用**合成表**测匹配逻辑 —— 与真源无关,永不漂移;
//   数据层(本文件)读**真源** model-contexts/context-windows.json,只断言**结构性质**。
//
// ★ 本文件**刻意不断言任何具体数值**。"某模型是不是 1M"是外部事实,代码断言它只会变成
//   真源之外的第二份数据 —— 改了真源忘了改这里就会红,而那种红毫无信息量。
//   数值正确性只能靠联网核对官方文档 + 人工确认(见 model-contexts/README.md)。
//
// 本文件守的是"结构与排序自洽",这些错误靠人眼极难发现,却会让整族模型静默错值。
package me.rerere.rikkahub.x.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContextWindowsDataInvariantTest {

    /**
     * 定位真源。
     *
     * 单测的工作目录随构建方式而变(模块目录 / 仓库根),故从 `user.dir` 逐级上溯查找。
     * **找不到就失败** —— 静默跳过会让这个测试在路径变化时悄悄失效,比没有还危险。
     */
    private val sourceFile: File by lazy {
        val candidates = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .take(6)
            .map { File(it, "model-contexts/context-windows.json") }
            .toList()
        candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "找不到真源 model-contexts/context-windows.json。" +
                    "已尝试:${candidates.joinToString { it.absolutePath }}"
            )
    }

    /** 真源必须可解析且通过整表校验(与 App 运行时同一套校验)。 */
    private val table: ContextWindowTable by lazy {
        val result = ContextWindowTable.parse(sourceFile.readText())
        assertTrue(
            "真源未通过校验:${(result as? ParseResult.Rejected)?.reason}",
            result is ParseResult.Ok,
        )
        (result as ParseResult.Ok).table
    }

    // ─────────────── 解析与自描述块 ───────────────

    @Test
    fun `source parses and declares supported schema`() {
        assertEquals(ContextWindowTable.SUPPORTED_SCHEMA_VERSION, table.schemaVersion)
    }

    /** strategy 必须是 App 已实现的枚举,否则用户端会整表拒用 —— 等于线上没有容量表。 */
    @Test
    fun `strategy is one the app implements`() {
        assertTrue(
            "真源用了 App 未实现的 strategy=${table.interpretation.strategy},会导致整表拒用",
            table.interpretation.strategy in ContextWindowInterpretation.KNOWN_STRATEGIES,
        )
    }

    @Test
    fun `lookup order only contains known stages and is not empty`() {
        assertTrue(table.interpretation.lookupOrder.isNotEmpty())
        assertTrue(
            "未知阶段:${table.interpretation.lookupOrder}",
            table.interpretation.lookupOrder.all { it in ContextWindowInterpretation.STAGES },
        )
    }

    /** TTL 必须是正数,否则客户端刷新逻辑会退化成每次冷启动都拉(或永不刷新)。 */
    @Test
    fun `refresh interval is positive`() {
        assertTrue(
            "refreshIntervalMinutes=${table.interpretation.refreshIntervalMinutes}",
            table.interpretation.refreshIntervalMinutes > 0,
        )
    }

    // ─────────────── 条目值域 ───────────────

    @Test
    fun `every capacity value lies within bounds`() {
        table.rules.forEach { rule ->
            assertTrue(
                "规则 ${rule.keywords} 的值越界:${rule.contextWindow}",
                rule.contextWindow in MIN_CONTEXT_WINDOW..MAX_CONTEXT_WINDOW,
            )
        }
        table.models.forEach { model ->
            assertTrue(
                "精确条目 ${model.id} 的值越界:${model.contextWindow}",
                model.contextWindow in MIN_CONTEXT_WINDOW..MAX_CONTEXT_WINDOW,
            )
        }
        table.defaultContextWindow?.let {
            assertTrue("default 越界:$it", it in MIN_CONTEXT_WINDOW..MAX_CONTEXT_WINDOW)
        }
    }

    @Test
    fun `rules and exact ids are well formed`() {
        table.rules.forEach { rule ->
            assertTrue("存在空 keywords 的规则", rule.keywords.isNotEmpty())
            assertTrue("存在含空白 keyword 的规则:${rule.keywords}", rule.keywords.none { it.isBlank() })
        }
        table.models.forEach { assertTrue("存在空 id 的精确条目", it.id.isNotBlank()) }
    }

    // ─────────────── 排序铁律:无死规则 ───────────────

    /**
     * 更具体的规则必须排在更一般的之前。
     *
     * 若某条 keywords 是**前面**某条的**真前缀**(如 `["claude"]` 排在 `["claude","sonnet","5"]` 之前),
     * 则后者**永远不可能命中** —— 匹配是顺序推进的,先命中的必然是前者。
     * 这类错误不会报错、不会崩,只会让整族模型静默落到错误的容量上。
     */
    @Test
    fun `no rule is shadowed by a preceding more general rule`() {
        val rules = table.rules.map { it.keywords }
        val shadowed = mutableListOf<String>()
        for (i in rules.indices) {
            for (j in i + 1 until rules.size) {
                val earlier = rules[i]
                val later = rules[j]
                val isStrictPrefix = earlier.size < later.size && later.subList(0, earlier.size) == earlier
                if (isStrictPrefix) {
                    shadowed += "第 $j 条 $later 被第 $i 条 $earlier 遮蔽(永远命中不到)"
                }
            }
        }
        assertTrue(shadowed.joinToString("\n"), shadowed.isEmpty())
    }

    /** 完全重复的 keywords 列表:后一条必然不可达。 */
    @Test
    fun `no duplicated keyword lists`() {
        val duplicates = table.rules
            .groupBy { it.keywords }
            .filterValues { it.size > 1 }
            .keys
        assertTrue("重复规则:$duplicates", duplicates.isEmpty())
    }

    // ─────────────── 自洽:精确条目必须真的被命中 ───────────────

    /**
     * `models[]` 里声明的精确条目,查表必须返回它自己声明的值。
     *
     * 若某条规则把它遮蔽(或 lookupOrder 把 rules 排在 exact 前),就说明表**自相矛盾** ——
     * 这是结构问题,不是数值问题,故可在此断言(依然不涉及任何具体数值是否正确)。
     */
    @Test
    fun `exact entries resolve to their own declared value`() {
        table.models.forEach { model ->
            assertEquals(
                "精确条目 ${model.id} 被遮蔽:表自身声明 ${model.contextWindow} 却查到其它值",
                model.contextWindow,
                table.lookup(model.id),
            )
        }
    }

    // 注:曾想在此断言「每条规则都能被由自身 keywords 拼出的 id 命中」,但该断言**形同虚设** ——
    // 表有 defaultContextWindow 兜底,lookup 对任何非空 id 都返回非 null,断言恒真。
    // 而「命中值是否来自本规则」无法在不复制匹配逻辑的前提下判定(前序规则可能合法地抢先命中,
    // 如 `["beta","6"]` 会命中 `beta-5-6`,两者同值时并不算错)。
    // 故规则可达性交由上面的**前缀遮蔽**检查精确覆盖 —— 那是可静态判定的部分,不在此处放水。

    /** 表的更新时间须是 YYYY-MM-DD,便于人眼判断是否需要重新核验。 */
    @Test
    fun `updatedAt looks like an ISO date`() {
        assertTrue("updatedAt=${table.updatedAt}", Regex("""\d{4}-\d{2}-\d{2}""").matches(table.updatedAt))
    }
}
