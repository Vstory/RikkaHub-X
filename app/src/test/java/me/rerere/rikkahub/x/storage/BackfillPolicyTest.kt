package me.rerere.rikkahub.x.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存量回填的纯逻辑自检（X 存储重构 P1 · 任务 1.7 / 1.8 / 1.9）。
 *
 * 这组用例守的是**回填最容易出错的三件事**,而它们的共同点是「错了不会报错」:
 *
 * | 风险 | 错了会怎样 | 本文件的用例 |
 * |---|---|---|
 * | 判错「该登记 / 算重复 / 已索引」 | 数字不对,要等用户看「可清理量」才发现 | `verdictFor` 三种组合 |
 * | 续跑时漏扫或多扫 | 悄悄少登记一批文件,且**看不出来** | 「分批遍历必须还原全表」 |
 * | 进度算错 | 界面停在 0% 或 100%,用户以为卡住/做完 | 边界(总数 0、已处理 ≥ 总数) |
 *
 * 纯函数不碰数据库与文件系统,故这些边界可以逐个列全 —— 而真跑一遍回填要几十秒。
 */
class BackfillPolicyTest {

    // ────────────────────────────────────────────────
    // 该登记 / 算重复 / 已索引（1.7 的核心判据）
    // ────────────────────────────────────────────────

    @Test
    fun `content not in ledger should be registered`() {
        assertEquals(
            "账本里没有这个内容 → 登记",
            BackfillPolicy.FileVerdict.REGISTER,
            BackfillPolicy.verdictFor(knownPath = null, relativePath = "upload/a.png"),
        )
    }

    @Test
    fun `content already pointing at this very file needs no action`() {
        // 重复运行回填时的常态:跑第二遍什么都不该发生
        assertEquals(
            "账本登记的正是这个文件 → 无需动作",
            BackfillPolicy.FileVerdict.ALREADY_INDEXED,
            BackfillPolicy.verdictFor(
                knownPath = "assets/ab/cd/hash.png",
                relativePath = "assets/ab/cd/hash.png",
            ),
        )
    }

    @Test
    fun `content known under another path counts as duplicate`() {
        // 老文件与新的内容寻址文件内容相同 —— 这正是「存量重复」。
        // 判定为 DUPLICATE 而不是 REGISTER,是因为**不能覆盖账本已有行**:
        // 覆盖会把已登记在内容寻址路径上的那个文件变成孤儿(有文件、无登记)。
        assertEquals(
            "内容已登记在别处 → 只统计,不覆盖",
            BackfillPolicy.FileVerdict.DUPLICATE,
            BackfillPolicy.verdictFor(
                knownPath = "assets/ab/cd/hash.png",
                relativePath = "upload/legacy-uuid.png",
            ),
        )
    }

    // ────────────────────────────────────────────────
    // 扫描范围
    // ────────────────────────────────────────────────

    @Test
    fun `hidden entries are excluded`() {
        // 内容寻址的临时目录就叫 `.x-tmp`,里面的文件是**写入中途**的产物 ——
        // 登记它们会在账本里造出假条目(而且那些文件马上会被删)
        assertFalse("顶层隐藏目录必须排除", BackfillPolicy.shouldScan(".x-tmp/w-1.tmp"))
        assertFalse("中间隐藏目录必须排除", BackfillPolicy.shouldScan("assets/.trash/x.png"))
        assertFalse("空路径必须排除", BackfillPolicy.shouldScan(""))
    }

    @Test
    fun `normal asset paths are included`() {
        assertTrue(BackfillPolicy.shouldScan("upload/legacy-uuid.png"))
        assertTrue(BackfillPolicy.shouldScan("assets/ab/cd/hash.png"))
    }

    @Test
    fun `scan roots cover legacy and managed dirs only`() {
        // 范围是有意的:老式附件目录(回填的主要对象)+ 内容寻址根(修复早期写入)。
        // 生成图 / 工具输出 / 缓存都不在内 —— 它们各有自己的生命周期。
        assertEquals(listOf("upload", "assets"), BackfillPolicy.SCAN_ROOTS)
        assertTrue("深度上限必须给(软链成环时无界递归会转不出来)", BackfillPolicy.MAX_DEPTH in 1..8)
    }

    // ────────────────────────────────────────────────
    // 续跑（1.9 的「回填中断续跑」）
    // ────────────────────────────────────────────────

    @Test
    fun `paths are ordered deterministically`() {
        val a = listOf("upload/b.png", "upload/a.png", "assets/x/y.png")
        assertEquals(
            "排序必须稳定 —— 游标在两次运行之间才可靠",
            BackfillPolicy.orderPaths(a),
            BackfillPolicy.orderPaths(a.reversed()),
        )
    }

    @Test
    fun `first batch starts from the beginning`() {
        val ordered = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "b"), BackfillPolicy.batchAfter(ordered, cursor = null, batchSize = 2))
    }

    @Test
    fun `subsequent batches continue strictly after the cursor`() {
        val ordered = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "d"), BackfillPolicy.batchAfter(ordered, cursor = "b", batchSize = 2))
    }

    @Test
    fun `cursor pointing at a vanished file still resumes correctly`() {
        // 真实场景:上次处理到 "b",但那个文件这次已经不在了(用户删了)。
        // 用「严格大于」而不是「索引进位」,才能从正确位置继续。
        val ordered = listOf("a", "c", "d")
        assertEquals(
            "游标所指的路径不存在时,应从其后第一个更大者继续",
            listOf("c", "d"),
            BackfillPolicy.batchAfter(ordered, cursor = "b", batchSize = 2),
        )
    }

    @Test
    fun `cursor past the end yields nothing`() {
        assertEquals(
            emptyList<String>(),
            BackfillPolicy.batchAfter(listOf("a", "b"), cursor = "z", batchSize = 2),
        )
    }

    @Test
    fun `batch size must be positive`() {
        val failed = runCatching { BackfillPolicy.batchAfter(listOf("a"), cursor = null, batchSize = 0) }
        assertTrue("批大小为零会让扫描永远不推进(死循环),必须显式拒绝", failed.isFailure)
    }

    @Test
    fun `paging through all batches reproduces the whole list in order`() {
        // 这是「续跑不漏不重」的直接表述:从 null 游标开始一批批取、每批用最后一个元素推进游标,
        // 拼起来必须**完整且有序**地等于原表。
        val ordered = BackfillPolicy.orderPaths(
            (1..25).map { "upload/f%02d.png".format(it) } + listOf("assets/ab/cd/h.png")
        )
        val collected = mutableListOf<String>()
        var cursor: String? = null
        var guard = 0
        while (true) {
            val batch = BackfillPolicy.batchAfter(ordered, cursor, batchSize = 7)
            if (batch.isEmpty()) break
            collected += batch
            cursor = batch.last()
            check(++guard < 100) { "分批遍历没有收敛" }
        }
        assertEquals("分批遍历必须还原全表", ordered, collected)
        assertEquals("不得重复处理", ordered.size, collected.toSet().size)
    }

    // ────────────────────────────────────────────────
    // 进度
    // ────────────────────────────────────────────────

    @Test
    fun `progress percentage handles boundaries`() {
        assertEquals("没有待处理文件 = 已完成,报 0 会让界面显得卡住", 100, BackfillPolicy.progressPercent(0, 0))
        assertEquals(0, BackfillPolicy.progressPercent(0, 10))
        assertEquals(50, BackfillPolicy.progressPercent(5, 10))
        assertEquals(100, BackfillPolicy.progressPercent(10, 10))
        assertEquals("已处理超出总数(清单在扫描期间变了)时仍夹到 100", 100, BackfillPolicy.progressPercent(11, 10))
    }

    @Test
    fun `progress percentage is monotonic`() {
        var last = -1
        for (done in 0..20) {
            val percent = BackfillPolicy.progressPercent(done, 20)
            assertTrue("进度不得回退:$done → $percent", percent >= last)
            last = percent
        }
    }

    // ────────────────────────────────────────────────
    // 存量重复统计（1.8：只统计，不合并）
    // ────────────────────────────────────────────────

    @Test
    fun `duplicate stats accumulate saved bytes not total bytes`() {
        // 两个 1MB 的相同文件:可省下的是 1MB(合并后能删掉的那份),不是 2MB。
        // 报 2MB 会让用户以为能腾出两倍空间。
        val stats = DuplicateStats().plusDuplicate(1_000_000L).plusDuplicate(500L)
        assertEquals(2L, stats.duplicateFiles)
        assertEquals(1_000_500L, stats.duplicateBytes)
    }

    @Test
    fun `duplicate stats start empty`() {
        assertEquals(0L, DuplicateStats().duplicateFiles)
        assertEquals(0L, DuplicateStats().duplicateBytes)
    }

    // ────────────────────────────────────────────────
    // 登记时写进去的元信息
    // ────────────────────────────────────────────────

    @Test
    fun `origin reflects the directory it came from`() {
        assertEquals(
            "upload/ 下的老文件来源就是它的目录",
            XStorageTables.Origins.UPLOAD,
            BackfillPolicy.originFor("upload/legacy.png"),
        )
        // 回填**无法知道**一个内容寻址路径下的文件当初是从哪来的(那个信息在写入时就丢了)——
        // 与其编一个看起来更具体的值,不如老实写 unknown
        assertEquals(
            "其余一律 unknown:回填无法还原真实来源",
            XStorageTables.Origins.UNKNOWN,
            BackfillPolicy.originFor("assets/ab/cd/hash.png"),
        )
    }

    @Test
    fun `extras json carries the origin key`() {
        val json = BackfillPolicy.extrasFor("upload/legacy.png")
        assertTrue("必须带 origin 键:$json", json.contains(XStorageTables.AssetExtras.ORIGIN))
        assertTrue("必须带来源取值:$json", json.contains(XStorageTables.Origins.UPLOAD))
        assertTrue("应是 JSON 对象形状:$json", json.startsWith("{") && json.endsWith("}"))
    }

    @Test
    fun `extras key is a registered one`() {
        // 与 `XStorageTables.AssetExtras` 保持一致:那个清单是「哪些字段住 JSON」的单一真源
        assertTrue(
            "写进 extras 的键必须在登记清单里(否则「字段在哪一侧」会说不清)",
            XStorageTables.AssetExtras.ORIGIN in XStorageTables.AssetExtras.ALL,
        )
    }
}
