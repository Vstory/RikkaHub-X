package me.rerere.rikkahub.x.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧会话目录清理策略的自检。
 *
 * ## 为什么这个纯逻辑值得单测
 *
 * 它**删用户的数据**,而且三处判错了都**不会报错**:
 *
 * ① **最新那个被删掉** —— 导出只取最新会话,删了就是当场把现场扔掉,而用户看到的
 *    只是「导出的包里少了东西」;
 * ② **删了个洞** —— 若从中间挑着删,时间线上会出现空洞,而读者看不出那是删除造成的;
 * ③ **上限算错一位** —— 本来要留 3 个,结果留了 2 个或 4 个,谁都不会发现。
 *
 * 故这里逐条钉住 `XDiagRotate` 的三条硬规则(见其类注释)。
 */
class XDiagRotateTest {

    private fun e(name: String, megabytes: Long) =
        XDiagRotate.Entry(name, megabytes * 1024 * 1024)

    /** 造一批会话,**最新在前**(与真实调用方的排序一致)。 */
    private fun newestFirst(vararg megabytes: Long): List<XDiagRotate.Entry> =
        megabytes.mapIndexed { i, mb -> e("session-20260913-00000${megabytes.size - i}", mb) }

    // ────────────────────────────────────
    // 规则 ①:最新那个永不动
    // ────────────────────────────────────

    @Test
    fun `a single session is never deleted`() {
        assertTrue(XDiagRotate.selectToDelete(newestFirst(500)).isEmpty())
    }

    @Test
    fun `an empty list deletes nothing`() {
        assertTrue(XDiagRotate.selectToDelete(emptyList()).isEmpty())
    }

    @Test
    fun `the newest survives even when it alone blows the byte budget`() {
        // ⚠️ 这条是**最容易写错**的一处:若把「累计超预算」的判据写成不带
        //    `kept.isNotEmpty()` 的前置条件,那么第一个(最新的)就会被判超限而删掉 ——
        //    等于「上限设小了就把现场扔了」。
        val sessions = newestFirst(500, 1, 1)
        val doomed = XDiagRotate.selectToDelete(sessions, keepCount = 3, maxBytes = 100L * 1024 * 1024)

        assertTrue("最新的那个不得出现在删除名单里", sessions.first().name !in doomed)
    }

    // ────────────────────────────────────
    // 规则 ②:从最老的一头**连着**删
    // ────────────────────────────────────

    @Test
    fun `the count limit keeps the newest N and drops the rest`() {
        val sessions = newestFirst(1, 1, 1, 1, 1) // 5 个,都是 1MB
        val doomed = XDiagRotate.selectToDelete(sessions, keepCount = 3, maxBytes = Long.MAX_VALUE)

        assertEquals(sessions.drop(3).map { it.name }, doomed)
        assertEquals(2, doomed.size)
    }

    @Test
    fun `deletions are always a contiguous run at the oldest end`() {
        // 规则 ② 的性质化验证:无论上限怎么给,删除名单必须是输入的**后缀**。
        val sessions = newestFirst(3, 40, 40, 40, 40, 40)
        for (keep in 1..6) {
            for (maxMb in listOf(1L, 50L, 100L, 200L, 1000L)) {
                val doomed = XDiagRotate.selectToDelete(sessions, keep, maxMb * 1024 * 1024)
                assertEquals(
                    "keep=$keep max=${maxMb}MB 时删除名单不是后缀(时间线会被挖洞)",
                    sessions.takeLast(doomed.size).map { it.name },
                    doomed,
                )
            }
        }
    }

    // ────────────────────────────────────
    // 规则 ③:两个上限**都要**起作用
    // ────────────────────────────────────

    @Test
    fun `the byte budget can trim harder than the count limit`() {
        // 5 个各 40MB:个数上限 3 允许留 3 个,但总预算 100MB 只放得下 2 个。
        val sessions = newestFirst(40, 40, 40, 40, 40)
        val doomed = XDiagRotate.selectToDelete(sessions, keepCount = 3, maxBytes = 100L * 1024 * 1024)

        // 留最新的两个(40+40=80 ≤ 100),第三个会让累计到 120 → 从这里起连着删。
        assertEquals(sessions.drop(2).map { it.name }, doomed)
        assertEquals(3, doomed.size)
    }

    @Test
    fun `small sessions are bounded by count, not by bytes`() {
        // 反向:一堆几百 KB 的小会话永远撞不到字节上限,只能靠个数上限收住 ——
        // 这就是「两个上限都要」的理由(光有字节上限会让目录数量无限增长)。
        val sessions = newestFirst(*(1..20).map { 1L }.toLongArray())
        val doomed = XDiagRotate.selectToDelete(sessions, keepCount = 3, maxBytes = 1024L * 1024 * 1024)

        assertEquals(17, doomed.size)
    }

    @Test
    fun `zero sized sessions still respect the count limit`() {
        // 空目录(开关开了又立刻关)也是会话;它们不占字节但占目录项,同样要收住。
        val sessions = (1..5).map { e("session-20260913-00000$it", 0L) }.reversed()
        val doomed = XDiagRotate.selectToDelete(sessions, keepCount = 2, maxBytes = Long.MAX_VALUE)

        assertEquals(3, doomed.size)
    }

    // ────────────────────────────────────
    // 防御性:上限参数本身写错时,**不许删东西**
    // ────────────────────────────────────

    @Test
    fun `a nonsensical keep count deletes nothing rather than everything`() {
        // ⚠️ `keepCount = 0` 若不加守卫,遍历第一次就会判「已超出个数上限」→ cutFrom = 0
        //    → **把最新的也删了**。这是最坏的一种错,而它只在有人把常量改错时才出现。
        val sessions = newestFirst(1, 1, 1)
        assertTrue(XDiagRotate.selectToDelete(sessions, keepCount = 0, maxBytes = Long.MAX_VALUE).isEmpty())
        assertTrue(XDiagRotate.selectToDelete(sessions, keepCount = -1, maxBytes = Long.MAX_VALUE).isEmpty())
    }
}
