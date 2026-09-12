// [X-custom] RikkaHub-X 诊断框架：logcat 自读能力验证（⚠️ 临时 spike，验完删除）
package me.rerere.rikkahub.x.diag

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * ⚠️⚠️ **临时验证代码（2026-09-12），不是正式实现，验完即删。**
 *
 * ## 它要回答的问题
 *
 * 「App 能不能读到自己 UID 的 logcat？能读到什么？量有多大？」
 *
 * 权威资料（AOSP 官方、Stack Overflow 多处实测）说：自 Android 4.1（API 16）起普通 App
 * **不能**读全部设备日志（那需要 `READ_LOGS`，只授特权系统应用），**但能读自己 UID 的日志，
 * 且不需要任何权限** —— logd 按 UID 过滤，其他 UID 的行根本不会返回。
 *
 * 本项目要据此决定诊断框架的架构：
 * - 若**能**读 → 上游两百余处 `Log.*` 与框架日志（OkHttp / Room 等）全部白捡，无需逐处插桩；
 * - 若**不能** → 回退「逐点插桩」路线，工作量大得多。
 *
 * **文档说了不算** —— 最新 Android 版本与不同 ROM 可能有差异，故必须真机实测。
 *
 * ## 三个探针
 *
 * | 探针 | 命令 | 回答什么 |
 * |---|---|---|
 * | ① 一次性 dump | `logcat -d -v threadtime` | 能否读、缓冲区里有多少、有哪些来源 |
 * | ② 崩溃缓冲 | `logcat -d -b crash` | 能否拿到崩溃栈 |
 * | ③ 限时流读 | `logcat -v threadtime`（读数秒） | **每秒多少行 / 字节 → 体积量级** |
 *
 * 探针③是为了定「落盘安全阀」的阈值：诊断开关开启期间要持续写文件，
 * 必须先知道正常速率，否则阈值只能靠猜。
 *
 * ## 自身的约束（spike 不能变成故障源）
 *
 * - 全部在调用方指定的后台线程跑，**不碰主线程**；
 * - 读取有**行数上限**与**超时兜底**，极端情况下不会把内存吃光或挂住；
 * - 只为验证，**不入库、不落盘**。
 */
object XLogcatProbe {

    private const val LOGCAT = "/system/bin/logcat"

    /** 单次读取最多保留的行数。防极端情况吃光内存 —— spike 自身不能成为故障源。 */
    private const val MAX_LINES = 20000

    /** 样本取**尾部**若干行：刚发生的事在最后。 */
    private const val SAMPLE_LINES = 8

    private const val DUMP_TIMEOUT_SECONDS = 30L

    /** 限时流读的时长（秒）。 */
    const val STREAM_SECONDS = 5

    /**
     * 关注标记 → 人话说明。决定「白捡」能覆盖到什么。
     *
     * 这些标记覆盖了三类来源：X 自己的诊断、第三方框架、系统对进程的回显。
     */
    private val MARKERS = listOf(
        "XCustom" to "X 自己的诊断日志",
        "okhttp" to "OkHttp（网络）",
        "Room" to "Room（数据库）",
        "SQLite" to "SQLite",
        "AndroidRuntime" to "崩溃栈",
        "chatty" to "logd 丢弃提示",
        "Choreographer" to "掉帧",
        "StrictMode" to "主线程 I/O",
    )

    /** 一次命令执行的结果。 */
    data class Dump(
        val command: String,
        val exitCode: Int,
        val elapsedMs: Long,
        val lines: Int,
        val bytes: Long,
        val truncated: Boolean,
        val markers: List<Pair<String, Int>>,
        val sample: List<String>,
        val error: String?,
    )

    data class ProbeResult(
        val sdk: Int,
        val abi: String,
        val readLogsDeclared: Boolean,
        val dump: Dump,
        val crash: Dump,
        val stream: Dump,
    ) {
        /** 报告文本（复制到剪贴板用）。 */
        fun report(): String = render(this)
    }

    /**
     * 跑完三个探针。**必须在后台线程调用**（内部有阻塞 I/O）。
     *
     * @param readLogsDeclared Manifest 里是否声明了 `READ_LOGS`。预期为 `false`
     *   —— 本次要验证的正是「不声明也能读自己的」。若实现里声明了它反而会被系统忽略。
     */
    fun run(readLogsDeclared: Boolean): ProbeResult {
        val dump = exec(listOf("-d", "-v", "threadtime"), DUMP_TIMEOUT_SECONDS)
        val crash = exec(listOf("-d", "-b", "crash", "-v", "threadtime"), DUMP_TIMEOUT_SECONDS)
        val stream = stream(STREAM_SECONDS)
        return ProbeResult(
            sdk = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            readLogsDeclared = readLogsDeclared,
            dump = dump,
            crash = crash,
            stream = stream,
        )
    }

    /** 一次性 dump：读完即退出。 */
    private fun exec(args: List<String>, timeoutSeconds: Long): Dump {
        val started = System.currentTimeMillis()
        val command = "logcat " + args.joinToString(" ")
        // 用非空局部量持有进程：可空 var 在闭包里会被禁止智能转换，
        // 那样下面每一处成员访问都要 !! 或 ?.，既啰嗦又容易漏。
        val proc = try {
            ProcessBuilder(listOf(LOGCAT) + args).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            return failure(command, started, e)
        }
        return try {
            val lines = ArrayList<String>(512)
            var bytes = 0L
            var truncated = false
            BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (lines.size >= MAX_LINES) {
                        truncated = true
                        break
                    }
                    bytes += line.length + 1
                    lines.add(line)
                }
            }
            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) proc.destroyForcibly()
            Dump(
                command = command,
                exitCode = if (finished) proc.exitValue() else -2,
                elapsedMs = System.currentTimeMillis() - started,
                lines = lines.size,
                bytes = bytes,
                truncated = truncated,
                markers = MARKERS.map { (marker, _) -> marker to lines.count { it.contains(marker) } },
                sample = lines.takeLast(SAMPLE_LINES),
                error = null,
            )
        } catch (e: Throwable) {
            runCatching { proc.destroyForcibly() }
            failure(command, started, e)
        }
    }

    /**
     * 限时流读：读若干秒后强制结束。
     *
     * 用**看门狗线程**而不是「读循环里查截止时间」：`readLine()` 一旦阻塞在半个行上，
     * 循环内的判断就没机会执行。看门狗到点直接杀进程 → 读取端拿到 EOF 自然退出。
     */
    private fun stream(seconds: Int): Dump {
        val started = System.currentTimeMillis()
        val command = "logcat -v threadtime（流读 $seconds 秒）"
        val proc = try {
            ProcessBuilder(listOf(LOGCAT, "-v", "threadtime")).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            return failure(command, started, e)
        }
        return try {
            Thread {
                runCatching {
                    Thread.sleep(seconds * 1000L + 300)
                    proc.destroyForcibly()
                }
            }.apply { isDaemon = true; name = "x-logcat-probe-watchdog" }.start()

            val lines = ArrayList<String>(512)
            var bytes = 0L
            var truncated = false
            BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (lines.size >= MAX_LINES) {
                        truncated = true
                        break
                    }
                    bytes += line.length + 1
                    lines.add(line)
                }
            }
            val elapsed = System.currentTimeMillis() - started
            Dump(
                command = command,
                exitCode = 0,
                elapsedMs = elapsed,
                lines = lines.size,
                bytes = bytes,
                truncated = truncated,
                markers = MARKERS.map { (marker, _) -> marker to lines.count { it.contains(marker) } },
                sample = lines.takeLast(SAMPLE_LINES),
                error = null,
            )
        } catch (e: Throwable) {
            runCatching { proc.destroyForcibly() }
            failure(command, started, e)
        }
    }

    private fun failure(command: String, started: Long, e: Throwable) = Dump(
        command = command,
        exitCode = -1,
        elapsedMs = System.currentTimeMillis() - started,
        lines = 0,
        bytes = 0L,
        truncated = false,
        markers = MARKERS.map { (marker, _) -> marker to 0 },
        sample = emptyList(),
        error = e.javaClass.simpleName + ": " + e.message,
    )

    // ── 报告 ──

    private fun render(r: ProbeResult): String = buildString {
        appendLine("=== RikkaHub-X logcat 自读验证（临时 spike）===")
        appendLine("时间: " + XLogRing.timeText(System.currentTimeMillis()))
        appendLine("Android SDK: " + r.sdk + "    ABI: " + r.abi)
        appendLine("Manifest 声明了 READ_LOGS: " + (if (r.readLogsDeclared) "是" else "否"))
        appendLine()
        appendLine(describe("① 一次性 dump", r.dump))
        appendLine()
        appendLine(describe("② 崩溃缓冲", r.crash))
        appendLine()
        appendLine(describe("③ 限时流读", r.stream))
        appendLine()
        appendLine("=== 判读 ===")
        appendLine(verdict(r))
    }

    private fun describe(title: String, d: Dump): String = buildString {
        appendLine("-- " + title + " --")
        appendLine("命令: " + d.command)
        if (d.error != null) {
            appendLine("错误: " + d.error)
            return@buildString
        }
        appendLine("退出码: " + d.exitCode + "    耗时: " + d.elapsedMs + " ms")
        appendLine("行数: " + d.lines + "    字节: " + d.bytes + (if (d.truncated) "（已截断）" else ""))
        val hit = d.markers.filter { it.second > 0 }
        if (hit.isEmpty()) {
            appendLine("标记: 一个都没命中")
        } else {
            hit.forEach { (marker, count) ->
                val label = MARKERS.firstOrNull { it.first == marker }?.second ?: ""
                appendLine("标记: " + marker + " × " + count + "    " + label)
            }
        }
        if (d.sample.isNotEmpty()) {
            appendLine("样本（尾部 " + d.sample.size + " 行）:")
            d.sample.forEach { appendLine("  " + it) }
        }
    }

    /** 结论：能否读到、白捡覆盖多少、体积量级。 */
    private fun verdict(r: ProbeResult): String = buildString {
        val d = r.dump
        when {
            d.error != null ->
                appendLine("❌ 执行失败，无法判断。错误见上。")
            d.lines == 0 -> {
                appendLine("⚠️ 命令成功但一行都没有 —— 可能缓冲区恰好为空，或本 ROM 做了额外限制。")
                appendLine("   建议：先正常用一会儿 App 再点一次；若仍为空，按「读不到」处理。")
            }
            else -> {
                appendLine("✅ 能读到本 UID 的日志（" + d.lines + " 行 / " + d.bytes + " 字节）。")
                appendLine()
                appendLine("白捡评估（这些来源无需插桩）:")
                val benefit = listOf("okhttp" to "网络", "Room" to "数据库", "AndroidRuntime" to "崩溃")
                benefit.forEach { (marker, label) ->
                    val n = d.markers.firstOrNull { it.first == marker }?.second ?: 0
                    appendLine("  " + label + ": " + if (n > 0) "有（" + n + " 行）" else "本次未见")
                }
            }
        }
        appendLine()
        if (r.crash.error == null && r.crash.lines > 0) {
            appendLine("崩溃缓冲: 可读（" + r.crash.lines + " 行）。")
        } else {
            appendLine("崩溃缓冲: 本次为空或不可读 —— 崩溃栈主要仍靠「未捕获异常处理器」兜底。")
        }
        appendLine()
        val s = r.stream
        if (s.error == null && s.lines > 0 && s.elapsedMs > 0) {
            val linesPerSec = s.lines * 1000.0 / s.elapsedMs
            val bytesPerSec = s.bytes * 1000.0 / s.elapsedMs
            val mbPerHour = bytesPerSec * 3600 / 1024 / 1024
            appendLine("体积量级（决定落盘安全阀阈值）:")
            appendLine("  约 " + fmt(linesPerSec) + " 行/秒 · " + fmt(bytesPerSec) + " 字节/秒")
            appendLine("  外推: 约 " + fmt(mbPerHour) + " MB/小时（空闲态，非生成态）")
            appendLine("  ⚠️ 这只是待机速率。生成态（流式输出 + 工具调用）会明显更高，")
            appendLine("     安全阀不能只按这个数定。")
        } else {
            appendLine("体积量级: 流读未取到数据，本次无法外推。")
        }
    }

    private fun fmt(value: Double): String {
        val scaled = Math.round(value * 10.0).toDouble() / 10.0
        return scaled.toString()
    }
}
