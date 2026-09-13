package me.rerere.rikkahub.x.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * logcat 分片命名与保留策略的自检。
 *
 * ## 为什么这几条值得单测
 *
 * 分片之后有两处**算错了也不会报错**的地方:
 *
 * ① **保留范围差一**。声称「保留 12 片」而实际留了 13 片 —— 磁盘上限就不再是
 *    单片 × 片数,而这件事只在磁盘快满时才有人发现;
 * ② **名字判据放宽**。若把「以 logcat 开头」当成是分片名,别的同名文件会被当成原始日志
 *    (打包时说成「本应用的原始日志」),反过来真日志也可能被漏掉 —— 两个方向都不会报错。
 *
 * 故把它们抽成纯逻辑并逐项钉住(见 `XLogcatParts` 的类注释)。
 */
class XLogcatPartsTest {

    // ────────────────────────────────────
    // 命名
    // ────────────────────────────────────

    @Test
    fun `part names start at one and carry the number`() {
        assertEquals("logcat_1.log", XLogcatParts.nameOf(1))
        assertEquals("logcat_12.log", XLogcatParts.nameOf(12))
    }

    @Test
    fun `a name round trips through partOf`() {
        for (n in listOf(1, 2, 9, 10, 999)) {
            assertEquals(n, XLogcatParts.partOf(XLogcatParts.nameOf(n)))
        }
    }

    @Test
    fun `the name predicate is deliberately narrow`() {
        // ⚠️ 放宽成「以 logcat 开头」是**最容易犯**的错,且两个方向都静默:
        //    认错的会被当成原始日志说给读者;而真日志若被漏掉,包里就少了那一层。
        listOf(
            "logcat.log",        // 无编号 —— 不是分片(它其实是旧版的命名)
            "logcat_.log",       // 空编号
            "logcat_0.log",      // 第零片不存在
            "logcat_01.log",     // 前导零:能读成 1,但**不接受** —— 同一片会有两种写法
            "logcat_1.txt",      // 后缀不对
            "Logcat_1.log",      // 大小写
            "logcat_1.log.bak",
            "logcat_backup.log", // 形似而实非
            "mylogcat_1.log",
            "events.log",
            "survivors.log",
            "logcat_1_x.log",
        ).forEach { name ->
            assertNull("'$name' 不该被当作分片名", XLogcatParts.partOf(name))
            assertFalse(XLogcatParts.isPartName(name))
        }
    }

    @Test
    fun `leading zeros are rejected on purpose`() {
        // 单独一条用例,因为它是**刻意的取舍**而非疏漏:接受前导零意味着 `logcat_1.log`
        // 与 `logcat_01.log` 都指向第 1 片,而排序会给出两个不同的位置 ——
        // 「按片号排序 = 按时间排序」这条就不再成立,而打包时的顺序正是靠它。
        assertNull(XLogcatParts.partOf("logcat_01.log"))
        assertEquals(1, XLogcatParts.partOf("logcat_1.log"))
    }

    // ────────────────────────────────────
    // 保留范围(差一错误的常驻点)
    // ────────────────────────────────────

    @Test
    fun `keeping N parts means the window is exactly N wide`() {
        // 写到第 12 片、保留 12 片 → 最早保留的是第 1 片(没满,什么都不删)
        assertEquals(1, XLogcatParts.oldestKept(latest = 12, keep = 12))
        // 写到第 13 片 → 窗口是 [2, 13],正好 12 片
        assertEquals(2, XLogcatParts.oldestKept(latest = 13, keep = 12))
        assertEquals(12, XLogcatParts.oldestKept(latest = 13, keep = 12).let { 13 - it + 1 })
    }

    @Test
    fun `oldestKept never goes below one`() {
        // 会话刚开始(第 1、2 片)时当然不该删 —— 0 与负数的片号不存在,
        // 而一旦返回 0,调用方就会去删一个不存在的文件(删不掉,也不报错)。
        assertEquals(1, XLogcatParts.oldestKept(latest = 1, keep = 12))
        assertEquals(1, XLogcatParts.oldestKept(latest = 5, keep = 12))
    }

    @Test
    fun `rotation drops exactly one part, and only once the window is full`() {
        // 还没写满:不删。
        // 算一遍边界:写到第 11 片、保留 12 片 → 下一片是 12,窗口 [1,12] 正好放得下 → 不删。
        assertNull(XLogcatParts.partToDrop(latest = 11, keep = 12))
        // ⚠️ 而**第 12 片时就要删了**:下一片是 13,窗口变成 [2,13],第 1 片该走。
        //    这条我一开始写成了 `assertNull` —— 与自己下一行的 `assertEquals(1, …)`
        //    **直接矛盾**(同一个调用两种期望),CI 上红了两条。
        assertEquals(1, XLogcatParts.partToDrop(latest = 12, keep = 12))
        assertEquals(2, XLogcatParts.partToDrop(latest = 13, keep = 12))
        assertEquals(3, XLogcatParts.partToDrop(latest = 14, keep = 12))
    }

    @Test
    fun `after a drop the window is exactly keep wide`() {
        // 把「删一片 + 开新片」连起来算一遍:删完、开完,窗口宽度必须**恰好**是 keep。
        // 这是最容易被差一毁掉的一条不变式,故不靠人眼推,直接算。
        val keep = 4
        for (latest in 1..20) {
            val dropped = XLogcatParts.partToDrop(latest, keep)
            val newPart = latest + 1
            val oldestAfter = dropped?.let { maxOf(XLogcatParts.oldestKept(latest, keep), it + 1) }
                ?: 1
            val width = newPart - oldestAfter + 1
            assertTrue(
                "latest=$latest 删了 $dropped 之后窗口宽度成了 $width(期望 ≤ $keep)",
                width <= keep,
            )
        }
    }

    @Test
    fun `a single kept part still works`() {
        // keep = 1 是边界:每轮转都该把上一片删掉。
        assertEquals(1, XLogcatParts.partToDrop(latest = 1, keep = 1))
        assertEquals(2, XLogcatParts.partToDrop(latest = 2, keep = 1))
        assertEquals(1, XLogcatParts.oldestKept(latest = 3, keep = 1).let { 3 - it + 1 })
    }
}
