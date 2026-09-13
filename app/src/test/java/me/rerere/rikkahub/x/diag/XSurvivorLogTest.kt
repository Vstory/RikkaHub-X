package me.rerere.rikkahub.x.diag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.io.File

/**
 * 存活层的自检 —— **这一层丢了就永远没有**,故它自己必须被验。
 *
 * ## 守什么
 *
 * ① **不静默丢**:到顶要写一条可见的到顶记录,且此后不再写(而不是悄悄不写了);
 * ② **一条记录恰好占一行**:崩溃栈是多行文本,转义错了会让「按行筛取」失效 ——
 *    而这一层装的恰恰全是多行栈;
 * ③ **续写而不是覆盖**:应用重启后新记的失败不能把上一次的崩掉记录冲掉;
 * ④ **清空真删**:用户点「清空」之后文件必须不在(这一页最不该出现的自相矛盾就是
 *    「清了却还在」)。
 *
 * ## 为什么走 [XSurvivorLog.installAt] 而不是 `install(Context)`
 *
 * 项目里没有 Robolectric,单测拿不到 `Context`。而本类真正会出错的不是「写一行」,
 * 是体积阀的记账 —— 那几处错了都**静默丢数据**。所以给一个只改根目录的接缝,
 * 把那条路径纳入 JVM 测试;生产路径仍走 `install(Context)`。
 */
class XSurvivorLogTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("survivors-test").toFile()
        XSurvivorLog.installAt(root)
    }

    @After
    fun tearDown() {
        XSurvivorLog.close()
        root.deleteRecursively()
    }

    private fun readLines(): List<String> =
        XSurvivorLog.file()!!.takeIf { it.isFile }?.readLines().orEmpty()

    private fun parse(line: String) = Json.parseToJsonElement(line).jsonObject

    @Test
    fun `a record lands on disk even though nothing else was ever installed`() {
        // 这就是本类存在的理由:**与开关无关**。测试里从未开启诊断,却依然要落盘。
        XSurvivorLog.append(XDomain.CORE, "diag.crash.detected", "上次运行发生了崩溃")

        val lines = readLines()
        assertEquals("应恰好落一行", 1, lines.size)
        val json = parse(lines[0])
        assertEquals("core", json.getValue("domain").jsonPrimitive.content)
        assertEquals("diag.crash.detected", json.getValue("event").jsonPrimitive.content)
    }

    @Test
    fun `the file lives in the root, not in a session folder`() {
        // 跨会话保留**正是靠这一点**实现:开关重开只产生新的 session-*,而它原地不动。
        XSurvivorLog.append(XDomain.CORE, "diag.crash.detected", "x")

        assertEquals(
            "存活层必须在根目录",
            File(root, XSurvivorLog.SURVIVORS_FILE).absolutePath,
            XSurvivorLog.file()!!.absolutePath,
        )
        assertFalse("不该在任何 session 目录里", XSurvivorLog.file()!!.path.contains("session-"))
    }

    @Test
    fun `a multi line stack stays on exactly one line`() {
        // ⚠️ 这一层装的**全是多行栈** —— 转义一破,「按行号定位」当场失效。
        val stack = """
            java.lang.IllegalStateException: 建表失败
            	at me.rerere.rikkahub.x.storage.AssetDao.rebuild(AssetDao.kt:88)
            	at me.rerere.rikkahub.data.db.DatabaseManager.init(DatabaseManager.kt:41)
        """.trimIndent()

        XSurvivorLog.append(XDomain.CORE, "diag.crash.detected", "崩溃", detail = stack)

        val lines = readLines()
        assertEquals("多行栈不得把一条记录撑成多行", 1, lines.size)
        assertEquals("栈要能原样还原", stack, parse(lines[0]).getValue("detail").jsonPrimitive.content)
    }

    @Test
    fun `records accumulate across calls instead of overwriting`() {
        // 应用重启 = 进程重来 = 重新 install,而文件必须**接着写**。
        // 覆盖式写入会让「上一次崩溃」被「这一次启动」冲掉 —— 静默丢数据。
        XSurvivorLog.append(XDomain.CORE, "diag.crash.detected", "第一条")
        XSurvivorLog.close()
        XSurvivorLog.installAt(root) // 等价于「重启后重新接上」
        XSurvivorLog.append(XDomain.CORE, "diag.session.fail", "第二条")

        val events = readLines().map { parse(it).getValue("event").jsonPrimitive.content }
        assertEquals(listOf("diag.crash.detected", "diag.session.fail"), events)
    }

    @Test
    fun `reaching the size cap leaves a visible marker and stops writing`() {
        // ⚠️ 关键的一条:到顶**不能静默停写**。否则读包的人会以为「就这些失败」,
        //    而真相是后面全被丢掉了 —— 那正是「静默失败比崩溃更贵」。
        val chunk = "x".repeat(64 * 1024)
        // 先塞到接近上限:64KB × 17 > 1MB
        repeat(17) { XSurvivorLog.append(XDomain.CORE, "diag.crash.detected", chunk) }

        val capped = readLines()
        assertTrue("到顶后必须有痕", capped.any { it.contains(XSurvivorLog.CAP_EVENT) })
        val beforeCap = capped.size
        val sizeAtCap = XSurvivorLog.file()!!.length()

        // 到顶之后继续调用:不得再长
        XSurvivorLog.append(XDomain.CORE, "diag.crash.detected", "到顶之后这条不该进去")
        assertEquals("到顶后不得再写入", sizeAtCap, XSurvivorLog.file()!!.length())
        assertEquals("到顶后行数不得再变", beforeCap, readLines().size)
    }

    @Test
    fun `byte accounting uses utf8 size, not character count`() {
        // 中文一字三字节。按 `String.length` 记账会把阀提前撞上或延后撞上,
        // 而两种都看不出来 —— 只是「能装的记录条数」与预期不符。
        XSurvivorLog.append(XDomain.CORE, "diag.crash.detected", "中文")

        val line = readLines()[0]
        assertEquals(
            "文件长度应等于该行的 UTF-8 字节数加一个换行",
            line.toByteArray(Charsets.UTF_8).size + 1L,
            XSurvivorLog.file()!!.length(),
        )
    }

    @Test
    fun `clear removes the file and later appends start a fresh one`() {
        XSurvivorLog.append(XDomain.CORE, "diag.crash.detected", "x")
        assertTrue("先确认真的写了", XSurvivorLog.file()!!.isFile)

        XSurvivorLog.clear()
        assertFalse("用户点清空后文件必须不在", XSurvivorLog.file()!!.isFile)

        // 清空之后还要能继续记(否则「清空」等于把这一层关掉了)
        XSurvivorLog.append(XDomain.CORE, "diag.session.fail", "y")
        assertEquals("清空后应重新开始,而不是接着旧内容", 1, readLines().size)
    }

    @Test
    fun `installAt tolerates a root that does not exist yet`() {
        // 目录由本类**自己**建:开关关着时 XDiagSession 根本不会开目录,
        // 而本文件恰恰要在那种时候也能写。
        val nested = File(root, "not/created/yet")
        XSurvivorLog.installAt(nested)

        XSurvivorLog.append(XDomain.CORE, "diag.crash.detected", "x")

        assertEquals(1, readLines().size)
        assertTrue("应自己把目录建出来", nested.isDirectory)
    }
}
