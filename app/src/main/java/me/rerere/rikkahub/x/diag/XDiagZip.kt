// [X-custom] RikkaHub-X 诊断框架:整次会话打成压缩包,交给 AI
package me.rerere.rikkahub.x.diag

import android.content.Context
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把一次诊断会话打成**一个压缩包** —— 用户点「导出诊断包」时做的事。
 *
 * ## 为什么是压缩包
 *
 * 会话目录里有多个文件(原始 logcat 可能几十上百 MB、事件时间线、存活层),
 * 逐个「分享」等于让用户手忙脚乱地挑,而漏掉一个就少一份现场。打成一个包:一次保存、
 * 一次发送,包内清单说明每一处。
 *
 * ## 类注释里两处要点
 *
 * ① **脱敏在哪一层**:打包时**逐行**过 [XLogScrub]。它是导出路径上唯一的凭据闸门 ——
 *    「脱敏有没有生效」由 [MANIFEST_NAME] 里那句可被证伪的计数兜住(见 [manifest])。
 *    文件本身不脱敏:那是热路径,而且文件就在应用私有目录里。
 *
 * ② **文件边界**:包里同时有 logcat(纯文本行)与事件时间线(JSON 行),外加存活层。
 *    清单按名字逐项说明「这是什么、多少体积、掩了几处」。见 [describe]。
 *
 * ## 清单里为什么有「什么不在这份包里」
 *
 * 因为**漏掉的比在的更危险**:包里没有的东西,读的人不会去找。故 [manifest] 的
 * `CAUTION` 段明确列出「我们没做脱敏的部分」与「已经不在这份包里」的东西。
 * 见 [manifest] 的 `CAUTION` 段。
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
 *
 * ## 存活层为什么单独作为 `extra` 传进来(2026-09-13)
 *
 * 它在 `x-diag/` **根目录**,不在任何 `session-*` 里 —— 那正是它「跨会话保留」的实现
 * 方式。而打包的输入是「一个会话目录」,故它得作为额外项传进来。
 *
 * 另一种做法是「导出前把 survivors.log 复制进会话目录」,被否掉了:**那会动磁盘上的
 * 证据**,而导出这一步理想情况下应只读。传路径进来只多一个形参,却保住了「导出不改现场」。
 */
object XDiagZip {

    /** 包内清单的名字。放最前,读者第一眼看到。 */
    const val MANIFEST_NAME = "README.txt"

    /** 打包时每条目的缓冲区。默认 8KB 对几百 MB 的文件偏小,故显式给大些。 */
    private const val BUFFER = 64 * 1024

    /**
     * 有没有可打的东西。
     *
     * ⚠️ 判据必须**同时看会话目录与存活层**(2026-09-13):存活层是独立于开关的,
     * 「开关从未开过、但崩溃过」时包里**只有** survivors.log。只看会话目录会得出
     * 「还没有记录」—— 而那恰恰是最需要导出的情形。
     *
     * 用于**提前**给出提示 —— 让用户先走一趟保存位置选择、再被告知没内容,是白费一步。
     */
    fun hasContent(dir: File?, extra: List<File> = emptyList()): Boolean =
        dir?.listFiles()?.any { it.isFile && it.length() > 0L } == true ||
            extra.any { it.isFile && it.length() > 0L }

    /**
     * 把 [dir] 下**所有非空文件**与 [extra] 打进 [out]。
     *
     * @param dir 会话目录。**可以为 `null`** —— 「开关从未开过、但崩溃过」时没有会话目录,
     *   而存活层仍有内容,那种情况必须照样导得出来。
     * @param extra 会话目录之外的额外文件(存活层)。同名的以先出现的为准,故调用方
     *   应保证名字不撞(当前只有一个存活层,与另两个不撞,由
     *   `check_x_diag_layout.py` 机检)。
     * @return 是否至少打进了一个文件。`false` 时调用方应提示「还没有记录」——
     *   包里有内容却报「写失败」会让人以为出了问题,而其实只是没东西可打。
     */
    fun write(
        header: List<String>,
        out: OutputStream,
        dir: File?,
        progress: XExportProgress = NoExportProgress,
        extra: List<File> = emptyList(),
    ): Boolean {
        val files = filesOf(dir) + filesOf(extra)
        if (files.isEmpty()) return false

        val redacted = XLogScrub.ENABLED
        // 进度以**输入字节数**为准:压缩后的体积事先不可知,而读了多少是确定的。
        val total = files.sumOf { it.length() }
        var read = 0L

        // 先预扫一遍取「每个文件掩了几处」:那个数只有读完才知道,而清单要落在**最前**。
        // 代价是两遍顺序读 —— 与单文件文本导出同一套取舍(见 DiagnosticPage 的注释)。
        // 报告成 ANALYSING 阶段,好在界面上与「写入」区分开 —— 否则进度条会先走满一遍再重来。
        // 预扫**一律跑**(2026-09-13 起不再只在开脱敏时跑):它现在同时产出两样东西 ——
        // 每个文件被掩的行数,以及**关键词索引**(读者按图索骥的那份)。索引必须无论
        // 脱敏开关如何都存在,否则「关掉脱敏就没有索引」这种事没人会想到。
        //
        // ⚠️ logcat.log **不进索引**:它是任意文本,行里没有 `domain`/`event` 字段,
        //    数它只会白读一遍。索引的输入是那些「一行一条 JSON」的文件。
        val keywords = XEventIndex.Counter()
        val hits = files.associate { f ->
            val indexable = f.name != XLogcatCapture.LOG_NAME
            f.name to preScan(f, indexable, keywords) { n ->
                read += n
                progress.report(XExportPhase.ANALYSING, read, total)
            }
        }

        // ⚠️ **必须归零**:上面预扫已经把 read 累加到了 total。不归零的话写入阶段会从
        //    100% 开始一路报超,进度条整段是满的 —— 等于没有进度。
        read = 0L

        ZipOutputStream(BufferedOutputStream(out, BUFFER)).use { zip ->
            // 清单**先写**:它是读包时的唯一指引,放到最后等于没人会先看到。
            // ⚠️ 清单本身不过脱敏 —— 那是我们自己生成的一段已知文本,且它里面就写着
            //    「Authorization」这类词;过一遍反而有被改坏的风险(改坏的正是那条警告)。
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            // 没有会话时(只导出存活层)也要给清单一个说得过去的 session 名,
            // 而不是空串或 `null` —— 清单是读包时的唯一指引,那一行不能含糊。
            zip.write(
                manifest(
                    header = header,
                    sessionName = dir?.name ?: "(no session recorded)",
                    files = files,
                    redacted = redacted,
                    hits = hits,
                    keywords = XEventIndex.render(keywords.result()),
                ).toByteArray(Charsets.UTF_8),
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

    private fun filesOf(dir: File?): List<File> =
        dir?.listFiles()
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
     * 预扫一个文件:**一遍读盘产出两样东西** —— 被脱敏器动过的行数,以及(可选)关键词索引。
     *
     * 判据用 `scrub(line) != line` —— 直接问脱敏器「你动它了吗」,而不是另写一套规则去猜
     * 哪些行「应该」被掩。两套判据迟早漂移(这条与单文件导出用的是同一套判据)。
     *
     * 合在一遍里是有意的:拆成两遍就是把大文件读两回,而这一层刻意做到「几百 MB 也压得动」。
     *
     * @param indexable `false` 表示本文件不进索引(如 logcat.log —— 它是任意文本,行里
     *   没有 `domain`/`event` 字段,数它只会白读一遍)。
     * @return 被掩的行数。
     */
    private fun preScan(
        file: File,
        indexable: Boolean,
        indexInto: XEventIndex.Counter,
        onRead: (Long) -> Unit = {},
    ): Long {
        var n = 0L
        val source = countingStream(file.inputStream(), onRead)
        BufferedReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (XLogScrub.scrub(line) != line) n++
                if (indexable) indexInto.add(line)
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
     * @param keywords [XEventIndex.render] 的结果;`null` = 包里没有可索引的事件(整段不写)。
     */
    internal fun manifest(
        header: List<String>,
        sessionName: String,
        files: List<File>,
        redacted: Boolean,
        hits: Map<String, Long> = emptyMap(),
        keywords: String? = null,
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
        appendLine("  Every line of events.log carries: at (HH:mm:ss.SSS), lvl (I/W), domain, event.")
        appendLine()
        appendLine("  Domains are NOT split into separate files, and that is deliberate: THE ORDER IS")
        appendLine("  THE POINT. One timeline answers 'what happened when', including across areas.")
        appendLine("  To look at one area only, filter instead of opening another file:")
        appendLine("      grep '\\"domain\\":\\"storage\\"' events.log")
        appendLine()
        appendLine("  Lines with domain=net also carry: method, url, code, durationMs, reqHeaders,")
        appendLine("  respHeaders, and -- when the request carried one -- reqBytes.")
        appendLine("  reqBytes is only the SIZE of the request body. The body itself is NOT in this")
        appendLine("  bundle, on purpose (a chat request body is the whole prompt plus history,")
        appendLine("  hundreds of KB: it would drown the timeline, and the redactor cannot tell")
        appendLine("  ordinary user text from credentials).")
        appendLine("  respBody IS included, but only when the request FAILED (non-2xx): an error")
        appendLine("  response says why it was rejected, and that text is available nowhere else.")
        appendLine("  Successful (2xx) responses carry no respBody -- for chat they are SSE streams,")
        appendLine("  and buffering one would stall the conversation. If an error body exceeded the")
        appendLine("  capture limit, the line also has respBodyTruncated=true.")
        appendLine()
        appendLine("  survivors.log is the one file that does NOT depend on the recording switch.")
        appendLine("  It holds crashes and other failures that would otherwise vanish -- the kind you")
        appendLine("  cannot re-create by simply running the app again. It sits OUTSIDE the session")
        appendLine("  folder and is kept across sessions until you clear diagnostics.")
        appendLine("  Its lines may carry an extra field, detail, holding a full stack trace.")
        appendLine()
        if (keywords != null) {
            appendLine("${XDiagEnv.MARK} keyword index ${XDiagEnv.MARK}")
            appendLine()
            appendLine(keywords)
            appendLine()
        }
        appendRedactionNote(this, redacted)
    }

    /**
     * 一个文件是什么 —— 按名字给读者一句说明。认不出就明说认不出。
     *
     * ⚠️ 这里**不再按域查表**(2026-09-13 合并单文件后,域不再是文件名的一部分)。
     * 从前它要拿文件名去 `XDomain.entries` 里找域,那层耦合由 `check_x_diag_layout.py`
     * 专门守着;合并之后那个检查器的判据大半作废 —— 耦合本身没有了。
     */
    private fun describe(name: String): String {
        if (name == XLogcatCapture.LOG_NAME) {
            return "raw logcat of the app itself (upstream + framework lines included)"
        }
        if (name == XDiagFileStore.EVENTS_FILE) {
            return "X custom timeline: semantic events and HTTP request metadata, in occurrence order"
        }
        if (name == XSurvivorLog.SURVIVORS_FILE) {
            return "crashes and other must-not-lose failures, kept across sessions (switch-independent)"
        }
        return "unrecognised file (name does not match a known file of the diagnostic bundle)"
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

    /**
     * 脱敏说明。**两种模式都必须如实说** —— 一边脱敏一边写「原样导出」会让人误判风险,
     * 反过来写「已脱敏」而闸门关着更危险:读者会以为可以随便发。
     *
     * ⚠️ 未脱敏分支**必须 `return`**:否则会接着打下面那段「credentials masked」——
     * 而那句在那种模式下是**谎话**。这就是所谓「文案撒谎」,它不会让任何测试变红,
     * 只会让人在错误的前提下决定要不要分享。
     */
    private fun appendRedactionNote(sb: StringBuilder, redacted: Boolean) {
        if (!redacted) {
            sb.appendLine("${XDiagEnv.MARK} CAUTION: not redacted ${XDiagEnv.MARK}")
            sb.appendLine()
            sb.appendLine("  This bundle is exported AS-IS. Redaction is currently disabled, so it may")
            sb.appendLine("  contain credentials and personal content, including:")
            sb.appendLine("    - Authorization / api keys in request headers recorded in events.log")
            sb.appendLine("    - whatever the app itself happened to log into logcat")
            sb.appendLine()
            sb.appendLine("  Review it before sharing it with anyone.")
            return
        }
        // ⚠️ 下面这段 2026-09-13 曾被整段弄丢过(重写本文件时),而它**是唯一的
        //    「已脱敏」说明** —— 丢了以后清单在脱敏模式下只剩一句「哪些没做」,
        //    读者看不到「凭据已被换成 ***」。XDiagZipTest 里那两条断言正是守它的。
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
        sb.appendLine("    - error response bodies (respBody), which are server text we do not control")
        sb.appendLine("    - app and framework log lines that happen to contain user content")
        sb.appendLine()
        sb.appendLine("  Review it before sharing it with anyone.")
    }
}
