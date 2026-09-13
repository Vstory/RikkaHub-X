// [X-custom] RikkaHub-X 诊断框架：把整个会话目录打成压缩包
package me.rerere.rikkahub.x.diag

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
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
 * ## 「文件里留全,包里脱敏」—— 两件事分开
 *
 * 会话目录里的文件**始终原样**(见 [XLogScrub.ENABLED] 的说明):现场不被破坏,也便于
 * 复核脱敏器到底掩了什么。打包时逐行过一遍 [XLogScrub],把凭据形态的值换成 [XLogScrub.MASK]。
 *
 * ⚠️ 但**正则只认凭据的形态**,它抓不到聊天内容 —— 故清单里既写「已脱敏」,也写明
 * 「哪些东西照旧在包里」。见 [manifest] 的 `CAUTION` 段。
 *
 * ## 为什么吃的是「头部行」而不是 `Context`
 *
 * 要往清单里写设备与版本信息,最直接的做法是传 `Context` 进来取。但那样**这个类就碰了
 * Android**,于是「包到底打成什么样」只能装机去看 —— 而打包正是这一步最该被验的地方
 * (条目名、清单内容、脱敏是否生效、空目录判据)。故把那些行**由调用方取好传进来**:
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
    fun write(
        header: List<String>,
        out: OutputStream,
        dir: File,
        progress: XExportProgress = NoExportProgress,
    ): Boolean {
        val files = filesOf(dir)
        if (files.isEmpty()) return false

        val redacted = XLogScrub.ENABLED
        // 进度以**输入字节数**为准:压缩后的体积事先不可知,而读了多少是确定的。
        val total = files.sumOf { it.length() }
        var read = 0L

        // 先预扫一遍取「每个文件掩了几处」:那个数只有读完才知道,而清单要落在**最前**。
        // 代价是两遍顺序读 —— 与单文件文本导出同一套取舍(见 DiagnosticPage 的注释)。
        // 报告成 ANALYSING 阶段,好在界面上与「写入」区分开 —— 否则进度条会先走满一遍再重来。
        val hits = if (redacted) {
            files.associate { f ->
                f.name to countHits(f) { n ->
                    read += n
                    progress.report(XExportPhase.ANALYSING, read, total)
                }
            }
        } else {
            emptyMap()
        }

        // ⚠️ **必须归零**:上面预扫已经把 read 累加到了 total。不归零的话写入阶段会从
        //    100% 开始一路报超,进度条整段是满的 —— 等于没有进度。
        read = 0L

        ZipOutputStream(BufferedOutputStream(out, BUFFER)).use { zip ->
            // 清单**先写**:它是读包时的唯一指引,放到最后等于没人会先看到。
            // ⚠️ 清单本身不过脱敏 —— 那是我们自己生成的一段已知文本,且它里面就写着
            //    「Authorization」这类词;过一遍反而有被改坏的风险(改坏的正是那条警告)。
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(
                manifest(header, dir.name, files, redacted, hits).toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()

            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                if (redacted) {
                    writeScrubbed(file, zip) { n ->
                        read += n
                        progress.report(XExportPhase.WRITING, read, total)
                    }
                } else {
                    countingStream(file.inputStream()) { n ->
                        read += n
                        progress.report(XExportPhase.WRITING, read, total)
                    }.use { it.copyTo(zip, BUFFER) }
                }
                zip.closeEntry()
            }
        }
        return true
    }

    private fun filesOf(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedBy { it.name }
            .orEmpty()

    /** 逐行读 → 脱敏 → 写。内存占用与文件大小无关,故几百 MB 的日志也压得动。 */
    private fun writeScrubbed(file: File, zip: ZipOutputStream, onRead: (Long) -> Unit = {}) {
        // ⚠️ 只 flush、**不 close** —— close 会把整个 zip 流一并关掉。
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        val source = countingStream(file.inputStream(), onRead)
        BufferedReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                writer.write(XLogScrub.scrub(line))
                writer.write("\n")
            }
        }
        writer.flush()
    }

    /**
     * 数一个文件里有多少行被脱敏器**动过**。
     *
     * 判据用 `scrub(line) != line` —— 直接问脱敏器「你动它了吗」,而不是另写一套规则去猜
     * 哪些行「应该」被掩。两套判据迟早漂移(这条与单文件导出用的是同一套判据)。
     */
    private fun countHits(file: File, onRead: (Long) -> Unit = {}): Long {
        var n = 0L
        val source = countingStream(file.inputStream(), onRead)
        BufferedReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (XLogScrub.scrub(line) != line) n++
            }
        }
        return n
    }

    /**
     * 组包内清单。**纯函数**(模式与命中数都由调用方传)便于单测 —— 这份文本是读包时的
     * 唯一指引,它自己出错就全错了,故**两种模式都要能测**。
     *
     * @param redacted 本次是否真的过了脱敏。清单必须**如实**说,不能一边脱敏一边写「原样导出」,
     *   也不能反过来 —— 后者会让人以为已经安全了。
     * @param hits 文件 → 被掩的行数。仅 [redacted] 为真时有意义。
     */
    internal fun manifest(
        header: List<String>,
        sessionName: String,
        files: List<File>,
        redacted: Boolean,
        hits: Map<String, Long> = emptyMap(),
    ): String = buildString {
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
            appendLine("    ${XLogcatCapture.sizeText(f.length())}${hitNote(f.name, redacted, hits)}")
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
        appendLine("                      respHeaders, and respBody. reqBody is the FULL request body")
        appendLine("                      -- for chat requests that means the complete prompt sent to")
        appendLine("                      the model.")
        appendLine("                      respBody is present only when the request FAILED (non-2xx):")
        appendLine("                      an error response says why it was rejected, and that text is")
        appendLine("                      not available anywhere else. Successful (2xx) responses carry")
        appendLine("                      no respBody -- for chat they are SSE streams, and buffering")
        appendLine("                      one would stall the conversation. If an error body exceeded")
        appendLine("                      the capture limit, the line also has respBodyTruncated=true.")
        appendLine()
        appendRedactionNote(this, redacted)
    }

    /**
     * 逐文件的命中数。**这个数的作用是「可被证伪」** —— 若清单说某文件「0 处命中」而正文里
     * 明摆着一个 `sk-...`,那就是脱敏没生效,一眼看得出来。静默地掩掉则无从判断。
     */
    private fun hitNote(name: String, redacted: Boolean, hits: Map<String, Long>): String {
        if (!redacted) return " · not redacted"
        val n = hits[name] ?: 0L
        return if (n > 0L) " · redacted, $n value(s) masked" else " · redacted, nothing matched"
    }

    private fun appendRedactionNote(sb: StringBuilder, redacted: Boolean) {
        if (!redacted) {
            sb.appendLine("${XDiagEnv.MARK} CAUTION: not redacted ${XDiagEnv.MARK}")
            sb.appendLine()
            sb.appendLine("  This bundle is exported AS-IS. Redaction is currently disabled, so it may")
            sb.appendLine("  contain credentials and personal content, including:")
            sb.appendLine("    - Authorization / api keys in net.log request headers")
            sb.appendLine("    - the full model request body in net.log (system prompt, chat history,")
            sb.appendLine("      and anything typed by the user)")
            sb.appendLine("    - whatever the app itself happened to log into logcat")
            sb.appendLine()
            sb.appendLine("  Review it before sharing it with anyone.")
            return
        }
        sb.appendLine("${XDiagEnv.MARK} redaction: credentials masked, content NOT sanitised ${XDiagEnv.MARK}")
        sb.appendLine()
        sb.appendLine("  Every file except this README was passed through a regex redactor line by line")
        sb.appendLine("  (see XLogScrub). Credential-shaped values were replaced with \"${XLogScrub.MASK}\":")
        sb.appendLine("  Authorization headers, Bearer tokens, JWTs, cookies, and known vendor key")
        sb.appendLine("  prefixes such as sk-, ghp_, glpat-, AIza, AKIA. Per-file counts are listed")
        sb.appendLine("  above -- if a file says \"nothing matched\" but you can see a key in it, the")
        sb.appendLine("  redactor missed it, and that is worth reporting.")
        sb.appendLine()
        sb.appendLine("  What this does NOT do -- the redactor only knows credential *patterns*. It")
        sb.appendLine("  cannot tell that ordinary text is private, so the following are still in here:")
        sb.appendLine("    - the full model request body in net.log: system prompt, chat history, and")
        sb.appendLine("      anything typed by the user")
        sb.appendLine("    - app and framework log lines that happen to contain user content")
        sb.appendLine()
        sb.appendLine("  Review it before sharing it with anyone.")
    }

    /** 一个文件是什么 —— 按名字给读者一句说明。认不出就明说认不出。 */
    private fun describe(name: String): String {
        if (name == XLogcatCapture.LOG_NAME) {
            return "raw logcat of the app itself (upstream + framework lines included)"
        }
        if (name == NET_NAME) {
            return "HTTP requests: full request bodies, plus error response bodies"
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
