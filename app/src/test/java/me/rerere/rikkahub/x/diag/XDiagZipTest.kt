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

    @Test
    fun `manifest comes first and files follow in name order`() {
        file("logcat.log", "a\n")
        file("net.log", "b\n")
        file("chat.log", "c\n")

        // 清单必须**最前**:它是读包时的唯一指引,放在末尾等于没人会先看到。
        assertEquals(
            listOf(XDiagZip.MANIFEST_NAME, "chat.log", "logcat.log", "net.log"),
            pack().map { it.first },
        )
    }

    @Test
    fun `contents survive the round trip including newlines and non ascii`() {
        // 内容是 JSON 行(自带换行)且可能含中文 —— 打包环节不得改动字节
        val body = "{\"msg\":\"第一行\\n第二行\"}\n{\"msg\":\"café ünïcode\"}\n"
        file("chat.log", body)

        assertEquals(body, pack().first { it.first == "chat.log" }.second)
    }

    @Test
    fun `empty files are skipped and the call reports nothing packed`() {
        // 0 字节的文件进包只会让读者以为「这个文件里什么都没有」——
        // 而真相是那一项压根没记录。剔除更诚实。
        file("chat.log", "")
        val buf = ByteArrayOutputStream()
        assertFalse("没有任何非空文件时应返回 false", XDiagZip.write(listOf("x"), buf, dir))
        assertEquals("返回 false 时不该写出任何字节", 0, buf.size())
    }

    @Test
    fun `empty files are skipped while non empty ones are kept`() {
        file("chat.log", "")
        file("net.log", "kept")

        val entries = pack()
        assertEquals(listOf(XDiagZip.MANIFEST_NAME, "net.log"), entries.map { it.first })
    }

    @Test
    fun `manifest names every file and states that it is not redacted`() {
        file("logcat.log", "a")
        file("net.log", "bb")
        file("chat.log", "ccc")
        file("mystery.log", "dddd")

        val text = manifestOf(pack())

        listOf("logcat.log", "net.log", "chat.log", "mystery.log").forEach {
            assertTrue("清单应点名 $it", text.contains(it))
        }
        assertTrue("清单应写明未脱敏", text.contains("not redacted"))
        assertTrue("清单应点名可能含凭据", text.contains("Authorization"))
        assertTrue("清单应说明一行一条", text.contains("ONE LINE IS ALWAYS ONE RECORD"))
        assertTrue("应带上调用方给的头部行(设备与版本)", text.contains("RikkaHub X"))
    }

    @Test
    fun `manifest reports each file size`() {
        val f = file("chat.log", "12345")
        assertTrue(
            "清单应给出体积,实得:\n" + manifestOf(pack()),
            manifestOf(pack()).contains(XLogcatCapture.sizeText(f.length())),
        )
    }

    @Test
    fun `manifest describes known files and admits unknown ones`() {
        file("logcat.log", "a")
        file("net.log", "b")
        file("chat.log", "c")
        file("mystery.log", "d")

        val text = manifestOf(pack())

        assertTrue("logcat 应被说明为原始日志", text.contains("raw logcat"))
        // ⚠️ 这一条守的是**判断顺序**:net.log 既可能被当成「net 域的事件文件」,
        //    也可能被当成「请求记录」。两者只有一处对。
        assertTrue("net.log 应说明为请求记录", text.contains("HTTP requests"))
        assertFalse("net.log 不该被当成 net 域的事件文件", text.contains("domain 'net'"))
        assertTrue("chat 域的说明应带中文标签", text.contains("会话"))
        assertTrue("认不出的文件要明说认不出,而不是猜", text.contains("unrecognised"))
    }

    @Test
    fun `hasContent treats missing empty and blank dirs alike`() {
        assertFalse("null 视为没有内容", XDiagZip.hasContent(null))
        assertFalse("空目录没有内容", XDiagZip.hasContent(dir))

        file("chat.log", "")
        assertFalse("只有 0 字节文件时仍算没有内容", XDiagZip.hasContent(dir))

        file("net.log", "x")
        assertTrue("有非空文件即有内容", XDiagZip.hasContent(dir))
    }
}
