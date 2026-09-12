// [X-custom] RikkaHub-X 诊断框架：把整个会话目录打成压缩包
package me.rerere.rikkahub.x.diag

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把一次会话目录打成**一个压缩包** —— 用户点一次导出就能拿走全部记录。
 *
 * ## 为什么必须是压缩包,而不是「一个拼起来的大文本」
 *
 * 三件事,前两件是硬的:
 *
 * ① **体积**:`logcat.log` 单独就能到 200MB(它的安全阀),而日志文本压缩比很高 ——
 *    压完常常只有十分之一。不压就得先传 200MB 出去。
 * ② **文件边界**:包里同时有 logcat(纯文本行)、`net.log`(请求 JSON 行)、各域事件 JSON 行。
 *    拼成一份就得靠分隔标记猜「哪几行属于哪个文件」,而这个猜测正是最容易出错的地方
 *    (消息正文里本来就带各种标记)。分文件则不需要猜。
 * ③ 顺带:一个文件比分四个文件好传。
 *
 * ## 为什么不像文本导出那样逐行过脱敏
 *
 * 包**不脱敏**(与既有的文本导出保持一致:脱敏当前是关的,见 [XLogScrub.ENABLED])。
 * 但包里放了一份 [MANIFEST_NAME] 说清这件事 —— 逐条列出每个文件里有什么、以及
 * 「可能含凭据,别外传」。**读者第一眼就该看到它**,而不是自己去猜。
 *
 * ## 为什么吃的是「头部行」而不是 `Context`
 *
 * 要往清单里写设备与版本信息,最直接的做法是传 `Context` 进来取。但那样**这个类就碰了
 * Android**,于是「包到底打成什么样」只能装机去看 —— 而打包正是这一步最该被验的地方
 * (条目名、清单内容、空目录判据)。故把那些行**由调用方取好传进来**:
 * 本类因此是纯逻辑,CI 里能真的写一个 zip 再读回来逐项核对。
 *
 * ## 流式写,不占内存
 *
 * 用 [ZipOutputStream] 边读边压,`logcat.log` 多大都不会把内存吃掉。
 * ⚠️ **不给条目设 `size`**:文件正被捕获线程追加时,取到的长度会与随后读出的字节数不一致,
 * 设了反而让 zip 写坏。不设则走数据描述符,天然容忍。
 */
object XDiagZip {

    /** 包内清单的名字。放最前,读者第一眼看到。 */
    const val MANIFEST_NAME = "README.txt"

    /** 打包时每条目的缓冲区。默认 8KB 对几百 MB 的文件偏小,故显式给大些。 */
    private const val BUFFER = 64 * 1024

    /**
     * [dir] 里有没有可打的东西。用于**提前**给出「还没有记录」的提示 ——
     * 让用户先走一趟保存位置选择、再被告知没内容,是白费一步。
     */
    fun hasContent(dir: File?): Boolean =
        dir?.listFiles()?.any { it.isFile && it.length() > 0L } == true

    /**
     * 把 [dir] 下**所有非空文件**打进 [out]。
     *
     * @return 是否至少打进了一个文件。`false` 时调用方应提示「还没有记录」——
     *   包里有内容却报「写失败」会让人以为出了问题,而其实只是没东西可打。
     */
    fun write(header: List<String>, out: OutputStream, dir: File): Boolean {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) return false

        ZipOutputStream(BufferedOutputStream(out, BUFFER)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifest(header, dir.name, files).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip, BUFFER) }
                zip.closeEntry()
            }
        }
        return true
    }

    /**
     * 组包内清单。**纯函数**(头部行由调用方取好)便于单测 —— 这份文本是 AI 读包时的
     * 唯一指引,它自己出错就全错了。
     */
    internal fun manifest(header: List<String>, sessionName: String, files: List<File>): String = buildString {
        appendLine("${XDiagEnv.MARK} RikkaHub X diagnostic bundle ${XDiagEnv.MARK}")
        appendLine()
        appendLine("This archive is a snapshot of one diagnostic session of the RikkaHub X app.")
        appendLine("Everything in it was collected from this device only.")
        appendLine()
        appendLine("session : $sessionName")
        header.forEach { appendLine(it) }
        appendLine()
        appendLine("${XDiagEnv.MARK} what is inside ${XDiagEnv.MARK}")
        appendLine()
        files.forEach { f ->
            appendLine("  ${f.name}")
            appendLine("    ${describe(f.name)}")
            appendLine("    ${XLogcatCapture.sizeText(f.length())}")
            appendLine()
        }
        appendLine("${XDiagEnv.MARK} how to read it ${XDiagEnv.MARK}")
        appendLine()
        appendLine("  logcat.log is plain logcat text, one log record per line, oldest first.")
        appendLine("  Every other file holds one compact JSON object per line. Newlines and quotes")
        appendLine("  inside a message are JSON-escaped, so ONE LINE IS ALWAYS ONE RECORD --")
        appendLine("  you can locate a record by line number and filter with a single grep.")
        appendLine()
        appendLine("  Domain files carry: at (HH:mm:ss.SSS), lvl (I/W), domain, event, msg.")
        appendLine("  net.log carries:    at, method, url, code, durationMs, reqHeaders, reqBody,")
        appendLine("                      respHeaders. reqBody is the FULL request body -- for chat")
        appendLine("                      requests that means the complete prompt sent to the model.")
        appendLine()
        appendLine("${XDiagEnv.MARK} CAUTION: not redacted ${XDiagEnv.MARK}")
        appendLine()
        appendLine("  This bundle is exported AS-IS. Redaction is currently disabled on purpose, so")
        appendLine("  it may contain credentials and personal content, including:")
        appendLine("    - Authorization / api keys in net.log request headers")
        appendLine("    - the full model request body in net.log (system prompt, chat history,")
        appendLine("      and anything typed by the user)")
        appendLine("    - whatever the app itself happened to log into logcat")
        appendLine()
        appendLine("  Review it before sharing it with anyone. If you only need the app's own")
        appendLine("  custom log lines, filter logcat on the XCustom tag instead of sharing this.")
    }

    /** 一个文件是什么 —— 按名字给读者一句说明。认不出就明说认不出。 */
    private fun describe(name: String): String {
        if (name == XLogcatCapture.LOG_NAME) {
            return "raw logcat of the app itself (upstream + framework lines included)"
        }
        if (name == NET_NAME) {
            return "HTTP requests, including full request bodies"
        }
        val stem = name.removeSuffix(".log")
        val domain = XDomain.entries.firstOrNull { it.key == stem }
        return if (domain != null) {
            "X custom semantic events for domain '${domain.key}' (${domain.label})"
        } else {
            "unrecognised file (name does not match a known domain or the logcat capture)"
        }
    }

    /** 与 [XDomain.NET] 的 `key` 同源 —— 写成常量只为让上面那处判断读起来自明。 */
    private val NET_NAME = XDomain.NET.key + ".log"
}
