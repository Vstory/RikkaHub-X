package me.rerere.rikkahub.x.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * 打包逻辑的自检 —— **真的写出一个 zip,再读回来逐项核对**。
 *
 * ## 为什么值得这么测
 *
 * 打包是最容易「看着对、其实坏」的一步:条目名、清单内容、空目录判据、以及
 * 「一条记录占一行」这类不变量,全都不在编译器的检查范围里;而它又是整条链路的
 * **出口** —— 包里缺一个文件,前面记的全都白记。
 *
 * `XDiagZip` 刻意不碰 `Context`(头部行由调用方给),正是为了让它能在 JVM 里跑完这一整套。
 */
class XDiagZipTest {

    /**
     * 事件时间线的文件名 —— **引用同一处常量**,不在测试里另写一份字面量。
     *
     * 合并成单文件(2026-09-13)之前,这里散布着 `chat.log` / `net.log` / `storage.log`
     * 等一串「域文件名」。那些名字现在**不再是文件名**(域退回了每行的一个字段)——
     * 若测试继续照旧写法,它守的就是一个已经不存在的东西。
     */
    private val EVENTS = XDiagFileStore.EVENTS_FILE

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("xdiag-zip-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, content: String): File =
        File(dir, name).apply { writeText(content, Charsets.UTF_8) }

    /** 打一次包,返回包内 (条目名 → 内容)。 */
    private fun pack(): List<Pair<String, String>> {
        val buf = ByteArrayOutputStream()
        XDiagZip.write(listOf("app     : RikkaHub X"), buf, dir)
        val out = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(buf.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out += entry.name to zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private fun manifestOf(entries: List<Pair<String, String>>): String =
        entries.first { it.first == XDiagZip.MANIFEST_NAME }.second

    /** 直接组一份清单,模式与命中数由测试给定 —— 于是**两种模式都能验**,不依赖全局开关。 */
    private fun manifest(
        redacted: Boolean,
        hits: Map<String, Long> = emptyMap(),
    ): String = XDiagZip.manifest(
        header = listOf("app     : RikkaHub X"),
        sessionName = "session-20260912-180227",
        files = dir.listFiles()!!.filter { it.isFile }.sortedBy { it.name },
        redacted = redacted,
        hits = hits,
    )

    @Test
    fun `manifest comes first and files follow in name order`() {
        file("logcat.log", "a\n")
        file(EVENTS, "b\n")
        file("mystery.log", "c\n")

        // 清单必须**最前**:它是读包时的唯一指引,放在末尾等于没人会先看到。
        assertEquals(
            listOf(XDiagZip.MANIFEST_NAME, EVENTS, "logcat.log", "mystery.log"),
            pack().map { it.first },
        )
    }

    @Test
    fun `contents survive the round trip including newlines and non ascii`() {
        // 内容是 JSON 行(自带换行)且可能含中文 —— 打包环节不得改动字节
        val body = "{\"msg\":\"第一行\\n第二行\"}\n{\"msg\":\"café ünïcode\"}\n"
        file(EVENTS, body)

        assertEquals(body, pack().first { it.first == EVENTS }.second)
    }

    @Test
    fun `empty files are skipped and the call reports nothing packed`() {
        // 0 字节的文件进包只会让读者以为「这个文件里什么都没有」——
        // 而真相是那一项压根没记录。剔除更诚实。
        file(EVENTS, "")
        val buf = ByteArrayOutputStream()
        assertFalse("没有任何非空文件时应返回 false", XDiagZip.write(listOf("x"), buf, dir))
        assertEquals("返回 false 时不该写出任何字节", 0, buf.size())
    }

    @Test
    fun `empty files are skipped while non empty ones are kept`() {
        file(EVENTS, "")
        file("logcat.log", "kept")

        val entries = pack()
        assertEquals(listOf(XDiagZip.MANIFEST_NAME, "logcat.log"), entries.map { it.first })
    }

    @Test
    fun `manifest names every file and states that it is not redacted`() {
        file("logcat.log", "a")
        file(EVENTS, "bb")
        file("mystery.log", "dddd")

        val text = manifestOf(pack())

        listOf("logcat.log", EVENTS, "mystery.log").forEach {
            assertTrue("清单应点名 $it", text.contains(it))
        }
        assertTrue("清单应说明一行一条", text.contains("ONE LINE IS ALWAYS ONE RECORD"))
        assertTrue("应带上调用方给的头部行(设备与版本)", text.contains("RikkaHub X"))
    }

    @Test
    fun `manifest in disabled mode warns that nothing was redacted`() {
        file(EVENTS, "x")
        val text = manifest(redacted = false)

        assertTrue("未脱敏时必须明说", text.contains("not redacted"))
        assertTrue("并点名 Authorization", text.contains("Authorization"))
        assertFalse("未脱敏时不该声称已掩", text.contains("value(s) masked"))
    }

    @Test
    fun `manifest in redacted mode says so and keeps warning about content`() {
        file(EVENTS, "x")
        val text = manifest(redacted = true)

        assertFalse("已脱敏时不该说未脱敏", text.contains("not redacted"))
        assertTrue("应说明是逐行正则脱敏", text.contains("regex redactor"))
        // ⚠️ 这一段是清单里**最要紧**的:正则只认凭据形态,抓不到聊天内容。
        //    不写清楚,读者会以为「已脱敏 = 可以随便发」。
        assertTrue("必须写明不处理内容", text.contains("NOT sanitised"))
        // ⚠️ 断言随行为变(2026-09-13):请求体**已不再落盘**,故清单不再声称它在包里 ——
        //    改口为「错误响应正文与框架日志照旧在」,那才是现在真正剩下的。
        assertTrue("必须点名错误响应正文照旧在包里", text.contains("error response bodies"))
        assertTrue("必须点名框架日志照旧在", text.contains("framework log lines"))
        assertTrue("必须提醒分享前自己过目", text.contains("Review it before sharing"))
        assertTrue("应说明可被证伪(命中数对不上就是脱敏漏了)", text.contains("redactor missed it"))
    }

    @Test
    fun `manifest lists per file masked counts so a miss is visible`() {
        file(EVENTS, "x")
        file("logcat.log", "y")
        val text = manifest(redacted = true, hits = mapOf(EVENTS to 3L, "logcat.log" to 0L))

        assertTrue("有命中应给出条数", text.contains("3 value(s) masked"))
        assertTrue("零命中应说「没匹配到」而不是省略", text.contains("nothing matched"))
    }

    @Test
    fun `manifest reports each file size`() {
        val f = file(EVENTS, "12345")
        assertTrue(
            "清单应给出体积,实得:\n" + manifestOf(pack()),
            manifestOf(pack()).contains(XLogcatCapture.sizeText(f.length())),
        )
    }

    @Test
    fun `manifest describes known files and admits unknown ones`() {
        file("logcat.log", "a")
        file(EVENTS, "b")
        file("mystery.log", "d")

        val text = manifestOf(pack())

        assertTrue("logcat 应被说明为原始日志", text.contains("raw logcat"))
        // ⚠️ 这一条守的是**判断顺序**:logcat 必须**先判**,否则它会被当成普通事件文件。
        assertTrue("事件时间线应被说明为时间线", text.contains("timeline"))
        assertFalse("logcat 不该被当成时间线", !text.contains("raw logcat of the app itself"))
        assertTrue("认不出的文件要明说认不出,而不是猜", text.contains("unrecognised"))
        // ⚠️ 域标签那层已随合并移除(2026-09-13):文件名不再与域绑定,
        //    describe() 也就不该再提任何"域"。这条是**反向**断言。
        assertFalse("合并后不该再按域描述文件", text.contains("semantic events for domain"))
    }

    @Test
    fun `credentials inside packed files are masked`() {
        // 这是本次改动的**要害**:包里不能带出真密钥。
        // 三种形态各来一个:Authorization 头、裸的厂商前缀 key、JSON 里的键值对。
        file(EVENTS, "{\"headers\":{\"Authorization\":\"Bearer sk-abcdefghijklmnopqrstuvwxyz\"}}")
        file("logcat.log", "using key sk-ant-aaaaaaaaaaaaaaaaaaaa to call")
        file("mystery.log", "{\"api_key\":\"ghp_aaaaaaaaaaaaaaaaaaaaaaaaaa\"}")

        val packed = pack().toMap()

        listOf("sk-abcdefghijklmnopqrstuvwxyz", "sk-ant-aaaaaaaaaaaaaaaaaaaa", "ghp_aaaaaaaaaaaaaaaaaaaaaaaaaa")
            .forEach { secret ->
                packed.forEach { (name, body) ->
                    assertFalse("$name 里不该出现原始密钥 $secret,实得:\n$body", body.contains(secret))
                }
            }
        assertTrue("应留下掩码,让人知道这里原本有值", packed.getValue(EVENTS).contains(XLogScrub.MASK))
    }

    @Test
    fun `the manifest itself is not passed through the redactor`() {
        // 清单是我们自己生成的一段已知文本,里面**就写着**「Authorization」这类词。
        // 过一遍脱敏反而有把它改坏的风险(改坏的正是那条警告)。
        file(EVENTS, "x")
        val text = manifestOf(pack())

        assertTrue("清单里的警告文字应原样保留", text.contains("Authorization headers, Bearer tokens"))
        assertTrue("清单里的掩码说明应原样保留", text.contains(XLogScrub.MASK))
    }

    @Test
    fun `progress is reported per phase and never exceeds the total`() {
        // 文件要足够大,进度才会分成多次上报 —— 太小的话一次就读完了,分段逻辑测不出来。
        val line = "x".repeat(1024) + "\n"
        listOf("logcat.log", EVENTS, "mystery.log").forEach { name ->
            File(dir, name).bufferedWriter(Charsets.UTF_8).use { w -> repeat(64) { w.write(line) } }
        }
        val reports = mutableListOf<Triple<XExportPhase, Long, Long>>()
        val progress = XExportProgress { phase, processed, total ->
            reports += Triple(phase, processed, total)
        }

        XDiagZip.write(listOf("app     : RikkaHub X"), ByteArrayOutputStream(), dir, progress)

        assertTrue("应上报进度", reports.isNotEmpty())

        // ① 任何一次上报都不能越过总量。
        //    ⚠️ 这一条是**实测踩到过的**:预扫阶段把计数器累加到总量后没归零,于是写入阶段
        //    从 100% 起步、整段进度条都是满的 —— 等于没有进度。不夹的话这里就会炸。
        reports.forEach { (phase, processed, total) ->
            assertTrue("$phase 上报 $processed 超过了总量 $total", processed <= total)
        }

        // ② 每个阶段内部单调不减(否则进度条会往回跳)。
        XExportPhase.entries.forEach { phase ->
            val seq = reports.filter { it.first == phase }.map { it.second }
            assertEquals("$phase 的进度应单调不减", seq.sorted(), seq)
        }

        val total = reports.first().third
        val writing = reports.filter { it.first == XExportPhase.WRITING }.map { it.second }
        assertTrue("必须有写入阶段", writing.isNotEmpty())

        // ③ 写入阶段要从接近 0 开始(而不是接着上一阶段的计数)。
        assertTrue(
            "写入阶段应从头开始,实得首次上报 ${writing.first()} / 总量 $total",
            writing.first() < total / 2,
        )

        // ④ 收尾要走到总量 —— 否则进度条永远差一截到不了头。
        assertEquals("写入阶段应走到总量", total, writing.last())

        // ⑤ 开了脱敏才有预扫阶段(它正是为了算「掩了几处」而多读一遍)。
        if (XLogScrub.ENABLED) {
            val analysing = reports.filter { it.first == XExportPhase.ANALYSING }.map { it.second }
            assertTrue("开了脱敏就应有分析阶段", analysing.isNotEmpty())
            assertEquals("分析阶段也应走到总量", total, analysing.last())
        }
    }

    // ────────────────────────────────────
    // 存活层作为额外项(2026-09-13)
    //
    // 它在根目录、不在任何 session 里 —— 而打包的输入一直是「一个会话目录」。
    // 下面几条守的正是「它到底进没进包」,以及**最关键的那种情形**:
    // 开关从未开过(没有会话目录)却崩溃过时,包里应当**只有它**。
    // ────────────────────────────────────

    /** 造一个「根目录下的存活层」—— 与测试自己的会话目录**不同**,模拟真实布局。 */
    private fun survivors(content: String): File =
        File(Files.createTempDirectory("survivors").toFile(), XSurvivorLog.SURVIVORS_FILE)
            .apply { writeText(content, Charsets.UTF_8) }

    private fun packWith(extra: List<File>, sessionDir: File? = dir): List<Pair<String, String>> {
        val buf = ByteArrayOutputStream()
        XDiagZip.write(listOf("app     : RikkaHub X"), buf, sessionDir, extra = extra)
        val out = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(buf.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out += entry.name to zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun `the survivors file is packed alongside the session files`() {
        file(EVENTS, "event\n")
        val packed = packWith(listOf(survivors("{\"event\":\"diag.crash.detected\"}\n")))

        assertEquals(
            listOf(XDiagZip.MANIFEST_NAME, EVENTS, XSurvivorLog.SURVIVORS_FILE),
            packed.map { it.first },
        )
    }

    @Test
    fun `a bundle with no session at all still carries the survivors file`() {
        // ⚠️ 这条守的是最容易漏掉的那种情形:**开关从未开过、但崩溃过**。
        //    此时没有会话目录,而存活层是唯一有内容的东西 —— 若打包以「有没有会话目录」
        //    为准,这个包会被判成空,而它恰恰是最需要导出的一个。
        val packed = packWith(listOf(survivors("{\"event\":\"diag.crash.detected\"}\n")), sessionDir = null)

        assertEquals(listOf(XDiagZip.MANIFEST_NAME, XSurvivorLog.SURVIVORS_FILE), packed.map { it.first })
        assertTrue("清单得给一个说得过去的 session 名", packed.first().second.contains("(no session"))
    }

    @Test
    fun `hasContent sees the survivors file even without a session dir`() {
        val s = survivors("x")
        assertTrue("只有存活层也算有内容", XDiagZip.hasContent(null, listOf(s)))
        assertFalse("存活层为空时不算", XDiagZip.hasContent(null, listOf(survivors(""))))
        assertFalse("都没有时不算", XDiagZip.hasContent(null, emptyList()))
        // 反向:会话目录有内容时,即使 extra 为空也算(别把判据改成只看 extra)
        file(EVENTS, "x")
        assertTrue("会话目录有内容即算", XDiagZip.hasContent(dir, emptyList()))
    }

    @Test
    fun `the manifest explains what the survivors file is`() {
        file(EVENTS, "x")
        val text = packWith(listOf(survivors("y"))).first { it.first == XDiagZip.MANIFEST_NAME }.second

        assertTrue("应说明存活层是什么", text.contains("must-not-lose"))
        assertTrue("应写明它与开关无关", text.contains("does NOT depend on the recording switch"))
    }

    @Test
    fun `hasContent treats missing empty and blank dirs alike`() {
        assertFalse("null 视为没有内容", XDiagZip.hasContent(null))
        assertFalse("空目录没有内容", XDiagZip.hasContent(dir))

        file(EVENTS, "")
        assertFalse("只有 0 字节文件时仍算没有内容", XDiagZip.hasContent(dir))

        file("logcat.log", "x")
        assertTrue("有非空文件即有内容", XDiagZip.hasContent(dir))
    }
}
