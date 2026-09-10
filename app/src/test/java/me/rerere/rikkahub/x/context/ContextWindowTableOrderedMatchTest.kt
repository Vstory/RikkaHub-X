// 容量表 rules 的「结构化匹配」测试(feat/context-window-ordered-match)。
//
// 背景:旧语义是「每个 keyword 各自是归一化 id 的子串」,因而版本号会错位命中 ——
// `claude-3-5-sonnet` 的 "5"(来自 3.5)让 `["claude","sonnet","5"]` 命中,报 1M 而实际 200K。
//
// 新语义(见 ContextWindowTable.kt 的 matchesInOrder):
//   ① keyword 按**顺序**匹配、互不重叠;
//   ② 连续的数字 keyword 合并为**版本组**,整体对齐 id 里的一个版本组,允许取前缀。
//
// 规则表按 model-contexts/context-windows.json 的**真实内容与顺序**内联(顺序敏感)。
package me.rerere.rikkahub.x.context

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextWindowTableOrderedMatchTest {

    /** 真实规则表(model-contexts/context-windows.json,原顺序)。 */
    private val realRules = listOf(
        ContextWindowRule(listOf("gpt", "6"), 1_050_000),
        ContextWindowRule(listOf("gpt", "5", "6"), 1_050_000),
        ContextWindowRule(listOf("gpt", "5", "5"), 1_050_000),
        ContextWindowRule(listOf("gpt", "5", "4"), 1_050_000),
        ContextWindowRule(listOf("gpt", "5"), 400_000),
        ContextWindowRule(listOf("gpt", "4", "1"), 1_000_000),
        ContextWindowRule(listOf("gpt", "4", "5"), 400_000),
        ContextWindowRule(listOf("gpt", "oss"), 131_072),
        ContextWindowRule(listOf("gpt", "4"), 128_000),
        ContextWindowRule(listOf("claude", "opus", "5"), 1_000_000),
        ContextWindowRule(listOf("claude", "sonnet", "5"), 1_000_000),
        ContextWindowRule(listOf("claude", "opus", "4"), 1_000_000),
        ContextWindowRule(listOf("claude", "sonnet", "4", "6"), 1_000_000),
        ContextWindowRule(listOf("claude"), 200_000),
        ContextWindowRule(listOf("gemini"), 1_000_000),
        ContextWindowRule(listOf("deepseek", "v4"), 1_000_000),
        ContextWindowRule(listOf("deepseek", "r", "1"), 64_000),
        ContextWindowRule(listOf("deepseek", "reasoner"), 64_000),
        ContextWindowRule(listOf("deepseek"), 128_000),
        ContextWindowRule(listOf("qwen", "3", "7", "max"), 1_000_000),
        ContextWindowRule(listOf("qwen"), 262_144),
        ContextWindowRule(listOf("glm", "5", "3"), 1_000_000),
        ContextWindowRule(listOf("glm", "5", "2"), 1_000_000),
        ContextWindowRule(listOf("glm", "5", "1"), 200_000),
        ContextWindowRule(listOf("glm", "5"), 200_000),
        ContextWindowRule(listOf("glm", "4"), 128_000),
        ContextWindowRule(listOf("kimi"), 262_144),
        ContextWindowRule(listOf("doubao", "seed", "evolving"), 1_024_000),
        ContextWindowRule(listOf("doubao", "2", "1"), 512_000),
        ContextWindowRule(listOf("doubao"), 262_144),
        ContextWindowRule(listOf("grok", "4", "20"), 2_000_000),
        ContextWindowRule(listOf("grok", "4", "1"), 2_000_000),
        ContextWindowRule(listOf("grok", "4", "3"), 1_000_000),
        ContextWindowRule(listOf("grok", "4", "6"), 500_000),
        ContextWindowRule(listOf("grok", "4", "5"), 500_000),
        ContextWindowRule(listOf("grok"), 262_144),
        ContextWindowRule(listOf("minimax", "m3"), 1_000_000),
        ContextWindowRule(listOf("minimax"), 204_800),
        ContextWindowRule(listOf("mimo", "2", "5"), 1_000_000),
        ContextWindowRule(listOf("mimo", "3"), 1_000_000),
        ContextWindowRule(listOf("mimo", "2", "pro"), 1_000_000),
        ContextWindowRule(listOf("mimo"), 262_144),
        ContextWindowRule(listOf("step"), 262_144),
        ContextWindowRule(listOf("intern"), 200_000),
        ContextWindowRule(listOf("hy"), 128_000),
        ContextWindowRule(listOf("longcat"), 200_000),
        ContextWindowRule(listOf("muse"), 128_000),
    )

    private val table = ContextWindowTable(
        defaultContextWindow = 200_000,
        models = listOf(
            ContextWindowExact("o1", 200_000),
            ContextWindowExact("o3", 400_000),
            ContextWindowExact("o4-mini", 400_000),
            ContextWindowExact("nano-banana", 1_000_000),
        ),
        rules = realRules,
    )

    private fun assertLookup(modelId: String, expected: Int) =
        assertEquals("modelId=$modelId", expected, table.lookup(modelId))

    // ── 本次修复的核心:版本号错位 ──────────────────────────────────────────────

    /** 3.5 的 "5" 不再被当成 Sonnet 5。 */
    @Test
    fun `version digits of 3_5 are not treated as Sonnet 5`() {
        assertLookup("claude-3-5-sonnet-20241022", 200_000)
        assertLookup("claude-3-5-sonnet", 200_000)
        assertLookup("claude-3.7-sonnet", 200_000)
    }

    /** 4.5 的 "5" 同样不是 Sonnet 5(点号与连字符两种写法)。 */
    @Test
    fun `sonnet 4_5 is not treated as Sonnet 5`() {
        assertLookup("claude-sonnet-4.5", 200_000)
        assertLookup("claude-sonnet-4-5", 200_000)
        assertLookup("claude-sonnet-4-5-20250929", 200_000)
        assertLookup("claude-haiku-4.5", 200_000)
    }

    /** 真正的 Sonnet/Opus 5 仍要命中 1M。 */
    @Test
    fun `real Sonnet 5 and Opus 5 still hit 1M`() {
        assertLookup("claude-sonnet-5", 1_000_000)
        assertLookup("claude-opus-5", 1_000_000)
    }

    /** 4.6 走 [4,6] 这条更具体的规则。 */
    @Test
    fun `sonnet 4_6 hits 1M via its own rule`() {
        assertLookup("claude-sonnet-4.6", 1_000_000)
        assertLookup("claude-sonnet-4-6", 1_000_000)
    }

    /** 版本规格取前缀:`claude-opus-4` 覆盖 4.6/4.7/4.8。 */
    @Test
    fun `opus major version 4 rule covers minor variants`() {
        assertLookup("claude-opus-4.6", 1_000_000)
    }

    // ── 日期戳(此前已修的 A7 系列,防回退) ──────────────────────────────────

    @Test
    fun `date stamps are stripped before matching`() {
        assertLookup("gpt-4o-2024-08-06", 128_000)
        assertLookup("gpt-4o-mini-2024-07-18", 128_000)
        assertLookup("gpt-4o-20240806", 128_000)
        assertLookup("gpt-4o-2024_08_06", 128_000)
        assertLookup("gpt-4o-2024.08.06", 128_000)
    }

    // ── 型号名与版本紧贴 / 数字段边界 ────────────────────────────────────────

    /** `qwen3.7-max`:版本紧贴型号名,点号与连字符两种写法都要认。 */
    @Test
    fun `version glued to model name is recognised`() {
        assertLookup("qwen3.7-max", 1_000_000)
        assertLookup("qwen-3.7-max", 1_000_000)
        assertLookup("qwen-3-7-max", 1_000_000)
        assertLookup("qwen3-max", 262_144)
    }

    /** "20" 不跨位命中 "4.2";`grok-4.20` 才走 2M 规则。 */
    @Test
    fun `digit runs are matched as whole version components`() {
        assertLookup("grok-4.20", 2_000_000)
        assertLookup("grok-4.2", 262_144)
        assertLookup("grok-4", 262_144)
        assertLookup("grok-4.3", 1_000_000)
        assertLookup("grok-4.5", 500_000)
        assertLookup("grok-4.6", 500_000)
        assertLookup("grok-4.1", 2_000_000)
    }

    /** `mimo-v2.5` 的 `v` 前缀要跳过。 */
    @Test
    fun `v prefixed versions are recognised`() {
        assertLookup("mimo-v2.5", 1_000_000)
        assertLookup("mimo-v2-pro", 1_000_000)
        assertLookup("mimo-v2", 262_144)
        assertLookup("mimo-v3", 1_000_000)
        assertLookup("mimo-v3-pro", 1_000_000)
    }

    /** 版本可隔文字出现:`doubao-seed-2.1` 的 2.1 在 seed 之后。 */
    @Test
    fun `version may appear after intervening words`() {
        assertLookup("doubao-seed-2.1", 512_000)
        assertLookup("doubao-seed-1.6", 262_144)
        assertLookup("doubao-seed-evolving", 1_024_000)
    }

    // ── 主路径回归(不因匹配收紧而漏命) ────────────────────────────────────

    @Test
    fun `gpt family keeps its documented windows`() {
        assertLookup("gpt-4o", 128_000)
        assertLookup("gpt-4o-mini", 128_000)
        assertLookup("gpt-4-turbo", 128_000)
        assertLookup("gpt-4.1", 1_000_000)
        assertLookup("gpt-4.5", 400_000)
        assertLookup("gpt-5", 400_000)
        assertLookup("gpt-5.4", 1_050_000)
        assertLookup("gpt-5.5", 1_050_000)
        assertLookup("gpt-5.6", 1_050_000)
        assertLookup("gpt-6", 1_050_000)
        assertLookup("gpt-oss-120b", 131_072)
    }

    @Test
    fun `other vendors keep their documented windows`() {
        assertLookup("gemini-2.5-pro", 1_000_000)
        assertLookup("kimi-k2.5", 262_144)
        assertLookup("deepseek-r1", 64_000)
        assertLookup("deepseek-reasoner", 64_000)
        assertLookup("deepseek-chat", 128_000)
        assertLookup("deepseek-v3.2", 128_000)
        assertLookup("deepseek-v4", 1_000_000)
        assertLookup("glm-5.3", 1_000_000)
        assertLookup("glm-5.2", 1_000_000)
        assertLookup("glm-5.1", 200_000)
        assertLookup("glm-5", 200_000)
        assertLookup("glm-4.6", 128_000)
        assertLookup("minimax-m3", 1_000_000)
        assertLookup("minimax-m2.5", 204_800)
        assertLookup("step-3.5-flash", 262_144)
        assertLookup("internlm2.5-1m", 200_000)
        assertLookup("longcat-2.0", 200_000)
        assertLookup("muse-spark", 128_000)
    }

    // ── 精确命中与兜底 ──────────────────────────────────────────────────────

    @Test
    fun `exact model ids win over rules`() {
        assertLookup("o3", 400_000)
        assertLookup("nano-banana", 1_000_000)
    }

    @Test
    fun `unknown ids fall back to the default window`() {
        assertLookup("llama-3.3-70b", 200_000)
    }

    /** 空白 id 不做匹配(调用方据此隐藏圆环)。 */
    @Test
    fun `blank id yields null`() {
        assertEquals(null, table.lookup(""))
    }
}
