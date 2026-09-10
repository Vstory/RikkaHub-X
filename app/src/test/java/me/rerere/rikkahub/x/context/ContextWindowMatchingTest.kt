// 容量表**匹配语义**测试(语义层)。
//
// 数据来源:**合成表** —— 结构与真实表同构(同样的 keyword 个数、同样的数字段形态),
// 但厂商名全部替换为 alpha/beta/gamma… 且不含任何真实厂商的容量数值。
// 这样改动 model-contexts/context-windows.json 的**数值**绝不会让本测试变红;
// 只有**改了匹配逻辑**才会 —— 这正是语义层该守的东西。
//
// (真源的**结构不变量**由 ContextWindowsDataInvariantTest 负责,那份读真源但不含数值断言。)
//
// 语义要点:旧语义是「每个 keyword 各自是子串」,版本号会错位命中 ——
// 真实世界的起源是 `claude-3-5-sonnet` 里的 "5"(来自 3.5)让 `["claude","sonnet","5"]` 命中,
// 报 1M 而实际 200K。本文件用等价的合成 id(`alpha-3-5-nova`)复现同一语义,但不依赖真实数据。
// 新语义(见 ContextWindowTable.kt 的 matchesInOrder):
//   ① keyword 按**顺序**匹配、互不重叠;
//   ② 连续的数字 keyword 合并为**版本组**,整体对齐 id 里的一个版本组,允许取前缀。
package me.rerere.rikkahub.x.context

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextWindowMatchingTest {

    /** 合成规则表:结构与真源同构(keyword 个数与数字段形态一一对应),但厂商名与数值均与真实世界无关。 */
    private val syntheticRules = listOf(
        ContextWindowRule(listOf("beta", "6"), 1_050_000),
        ContextWindowRule(listOf("beta", "5", "6"), 1_050_000),
        ContextWindowRule(listOf("beta", "5", "5"), 1_050_000),
        ContextWindowRule(listOf("beta", "5", "4"), 1_050_000),
        ContextWindowRule(listOf("beta", "5"), 400_000),
        ContextWindowRule(listOf("beta", "4", "1"), 1_000_000),
        ContextWindowRule(listOf("beta", "4", "5"), 400_000),
        ContextWindowRule(listOf("beta", "oss"), 131_072),
        ContextWindowRule(listOf("beta", "4"), 128_000),
        ContextWindowRule(listOf("alpha", "bard"), 1_000_000),
        ContextWindowRule(listOf("alpha", "epic"), 1_000_000),
        ContextWindowRule(listOf("alpha", "orion", "5"), 1_000_000),
        ContextWindowRule(listOf("alpha", "nova", "5"), 1_000_000),
        ContextWindowRule(listOf("alpha", "orion", "4"), 1_000_000),
        ContextWindowRule(listOf("alpha", "nova", "4", "6"), 1_000_000),
        ContextWindowRule(listOf("alpha"), 200_000),
        ContextWindowRule(listOf("gamma"), 1_000_000),
        ContextWindowRule(listOf("delta", "v4"), 1_000_000),
        ContextWindowRule(listOf("delta", "r", "1"), 64_000),
        ContextWindowRule(listOf("delta", "thinker"), 64_000),
        ContextWindowRule(listOf("delta"), 128_000),
        ContextWindowRule(listOf("kappa", "3", "8", "max"), 1_000_000),
        ContextWindowRule(listOf("kappa", "3", "8", "flash"), 1_000_000),
        ContextWindowRule(listOf("kappa", "3", "7", "max"), 1_000_000),
        ContextWindowRule(listOf("kappa"), 262_144),
        ContextWindowRule(listOf("lambda", "5", "3"), 1_000_000),
        ContextWindowRule(listOf("lambda", "5", "2"), 1_000_000),
        ContextWindowRule(listOf("lambda", "5", "1"), 200_000),
        ContextWindowRule(listOf("lambda", "5"), 200_000),
        ContextWindowRule(listOf("lambda", "4"), 128_000),
        ContextWindowRule(listOf("mu", "q3"), 1_048_576),
        ContextWindowRule(listOf("mu"), 262_144),
        ContextWindowRule(listOf("phi", "seed", "evolving"), 1_024_000),
        ContextWindowRule(listOf("phi", "2", "1"), 262_144),
        ContextWindowRule(listOf("phi"), 262_144),
        ContextWindowRule(listOf("rho", "4", "20"), 2_000_000),
        ContextWindowRule(listOf("rho", "4", "1"), 2_000_000),
        ContextWindowRule(listOf("rho", "4", "3"), 1_000_000),
        ContextWindowRule(listOf("rho", "4", "6"), 500_000),
        ContextWindowRule(listOf("rho", "4", "5"), 500_000),
        ContextWindowRule(listOf("rho"), 262_144),
        ContextWindowRule(listOf("zeta", "4", "ranger"), 10_000_000),
        ContextWindowRule(listOf("zeta", "4"), 1_000_000),
        ContextWindowRule(listOf("zeta"), 128_000),
        ContextWindowRule(listOf("sigma", "n3"), 1_000_000),
        ContextWindowRule(listOf("sigma"), 204_800),
        ContextWindowRule(listOf("tau", "2", "5"), 1_000_000),
        ContextWindowRule(listOf("tau", "3"), 1_000_000),
        ContextWindowRule(listOf("tau", "2", "pro"), 1_000_000),
        ContextWindowRule(listOf("tau"), 262_144),
        ContextWindowRule(listOf("step"), 262_144),
        ContextWindowRule(listOf("intern"), 200_000),
        ContextWindowRule(listOf("hy"), 128_000),
        ContextWindowRule(listOf("omega", "flash"), 131_072),
        ContextWindowRule(listOf("omega"), 1_048_576),
        ContextWindowRule(listOf("psi"), 1_048_576),
    )

    private val table = ContextWindowTable(
        defaultContextWindow = 200_000,
        models = listOf(
            ContextWindowExact("exact-alpha", 400_000),
            ContextWindowExact("exact-beta", 1_000_000),
        ),
        rules = syntheticRules,
    )

    private fun assertLookup(modelId: String, expected: Int) =
        assertEquals("modelId=$modelId", expected, table.lookup(modelId))

    // ── 本次修复的核心:版本号错位 ──────────────────────────────────────────────

    /** 3.5 的 "5" 不再被当成 Nova 5。 */
    @Test
    fun `version digits of 3_5 are not treated as Nova 5`() {
        assertLookup("alpha-3-5-nova-20241022", 200_000)
        assertLookup("alpha-3-5-nova", 200_000)
        assertLookup("alpha-3.7-nova", 200_000)
    }

    /** 4.5 的 "5" 同样不是 Nova 5(点号与连字符两种写法)。 */
    @Test
    fun `nova 4_5 is not treated as Nova 5`() {
        assertLookup("alpha-nova-4.5", 200_000)
        assertLookup("alpha-nova-4-5", 200_000)
        assertLookup("alpha-nova-4-5-20250929", 200_000)
        assertLookup("alpha-minor-4.5", 200_000)
    }

    /** 真正的 Nova/Orion 5 仍要命中 1M。 */
    @Test
    fun `real Nova 5 and Orion 5 still hit 1M`() {
        assertLookup("alpha-nova-5", 1_000_000)
        assertLookup("alpha-orion-5", 1_000_000)
    }

    /** 4.6 走 [4,6] 这条更具体的规则。 */
    @Test
    fun `nova 4_6 hits 1M via its own rule`() {
        assertLookup("alpha-nova-4.6", 1_000_000)
        assertLookup("alpha-nova-4-6", 1_000_000)
    }

    /** 版本规格取前缀:`alpha-orion-4` 覆盖 4.6/4.7/4.8。 */
    @Test
    fun `orion major version 4 rule covers minor variants`() {
        assertLookup("alpha-orion-4.6", 1_000_000)
    }

    // ── 日期戳(此前已修的 A7 系列,防回退) ──────────────────────────────────

    @Test
    fun `date stamps are stripped before matching`() {
        assertLookup("beta-4o-2024-08-06", 128_000)
        assertLookup("beta-4o-mini-2024-07-18", 128_000)
        assertLookup("beta-4o-20240806", 128_000)
        assertLookup("beta-4o-2024_08_06", 128_000)
        assertLookup("beta-4o-2024.08.06", 128_000)
    }

    // ── 型号名与版本紧贴 / 数字段边界 ────────────────────────────────────────

    /** `kappa3.7-max`:版本紧贴型号名,点号与连字符两种写法都要认。 */
    @Test
    fun `version glued to model name is recognised`() {
        assertLookup("kappa3.7-max", 1_000_000)
        assertLookup("kappa-3.7-max", 1_000_000)
        assertLookup("kappa-3-7-max", 1_000_000)
        assertLookup("kappa3-max", 262_144)
    }

    /** "20" 不跨位命中 "4.2";`rho-4.20` 才走 2M 规则。 */
    @Test
    fun `digit runs are matched as whole version components`() {
        assertLookup("rho-4.20", 2_000_000)
        assertLookup("rho-4.2", 262_144)
        assertLookup("rho-4", 262_144)
        assertLookup("rho-4.3", 1_000_000)
        assertLookup("rho-4.5", 500_000)
        assertLookup("rho-4.6", 500_000)
        assertLookup("rho-4.1", 2_000_000)
    }

    /** `tau-v2.5` 的 `v` 前缀要跳过。 */
    @Test
    fun `v prefixed versions are recognised`() {
        assertLookup("tau-v2.5", 1_000_000)
        assertLookup("tau-v2-pro", 1_000_000)
        assertLookup("tau-v2", 262_144)
        assertLookup("tau-v3", 1_000_000)
        assertLookup("tau-v3-pro", 1_000_000)
    }

    /** 版本可隔文字出现:`phi-seed-2.1` 的 2.1 在 seed 之后。 */
    @Test
    fun `version may appear after intervening words`() {
        assertLookup("phi-seed-2.1", 262_144)
        assertLookup("phi-seed-1.6", 262_144)
        assertLookup("phi-seed-evolving", 1_024_000)
    }

    // ── 主路径回归(不因匹配收紧而漏命) ────────────────────────────────────

    @Test
    fun `beta family keeps its documented windows`() {
        assertLookup("beta-4o", 128_000)
        assertLookup("beta-4o-mini", 128_000)
        assertLookup("beta-4-turbo", 128_000)
        assertLookup("beta-4.1", 1_000_000)
        assertLookup("beta-4.5", 400_000)
        assertLookup("beta-5", 400_000)
        assertLookup("beta-5.4", 1_050_000)
        assertLookup("beta-5.5", 1_050_000)
        assertLookup("beta-5.6", 1_050_000)
        assertLookup("beta-6", 1_050_000)
        assertLookup("beta-oss-120b", 131_072)
    }

    @Test
    fun `other families keep their documented windows`() {
        assertLookup("gamma-2.5-pro", 1_000_000)
        assertLookup("mu-k2.5", 262_144)
        assertLookup("delta-r1", 64_000)
        assertLookup("delta-thinker", 64_000)
        assertLookup("delta-chat", 128_000)
        assertLookup("delta-v3.2", 128_000)
        assertLookup("delta-v4", 1_000_000)
        assertLookup("lambda-5.3", 1_000_000)
        assertLookup("lambda-5.2", 1_000_000)
        assertLookup("lambda-5.1", 200_000)
        assertLookup("lambda-5", 200_000)
        assertLookup("lambda-4.6", 128_000)
        assertLookup("sigma-n3", 1_000_000)
        assertLookup("sigma-m2.5", 204_800)
        assertLookup("step-3.5-flash", 262_144)
        assertLookup("intern-2.5-1m", 200_000)
        assertLookup("omega-2.0", 1_048_576)
        assertLookup("psi-flare", 1_048_576)
    }

    /** 更具体者优先:同族内新代与旧代并存时，各自走自己的规则。 */
    @Test
    fun `more specific rules win within a family`() {
        // alpha 的代际分层:bard/epic 与 orion 同代，且不得污染旧款(见上组 3.5 → 200K)
        assertLookup("alpha-bard-5", 1_000_000)
        assertLookup("alpha-bard-5-1", 1_000_000)
        assertLookup("alpha-epic-5", 1_000_000)
        assertLookup("alpha-epic-5-1", 1_000_000)
        // kappa 3.8:max 与 flash 同档；同代 27b 仍 256K(版本更具体者优先)
        assertLookup("kappa3.8-max", 1_000_000)
        assertLookup("kappa3.8-flash", 1_000_000)
        assertLookup("kappa3.8-27b", 262_144)
        // mu:q3 是 1M，不属 k2 系的 256K
        assertLookup("mu-q3", 1_048_576)
        assertLookup("mu-k2.7", 262_144)
        // zeta 4:ranger 10M / troop 1M 并存；3.x 走 128K
        assertLookup("zeta-4-ranger", 10_000_000)
        assertLookup("zeta-4-troop", 1_000_000)
        assertLookup("zeta-3.3-70b", 128_000)
        // omega:2.0 原生 1M，旧 flash-chat 131072 并存
        assertLookup("omega-2.0", 1_048_576)
        assertLookup("omega-flash-chat", 131_072)
        // psi 系
        assertLookup("psi-flare-1.3", 1_048_576)
    }

    // ── 精确命中与兜底 ──────────────────────────────────────────────────────

    @Test
    fun `exact model ids win over rules`() {
        assertLookup("exact-alpha", 400_000)
        assertLookup("exact-beta", 1_000_000)
    }

    @Test
    fun `unknown ids fall back to the default window`() {
        // 样例须是**本合成表里任何规则都命中不了**的 id。
        // (真源里曾用真实模型名当"未知"样例,一收录该家族就把用例撞翻 —— 兜底样例不该取自任何
        //  可能被收录的命名空间,合成表里则天然安全。)
        assertLookup("acme-unknown-model-v9", 200_000)
        assertLookup("some-random-name", 200_000)
    }

    /** 空白 id 不做匹配(调用方据此隐藏圆环)。 */
    @Test
    fun `blank id yields null`() {
        assertEquals(null, table.lookup(""))
    }
}
