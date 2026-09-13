// [X-custom] RikkaHub-X 诊断框架:导出时取一份「未过滤的全缓冲快照」
package me.rerere.rikkahub.x.diag

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * 导出时抓一份 **logd 全缓冲快照** → 包里的 `logcat_dump.log`。
 *
 * ## 它补的是哪一块(抄 LSPosed 的 `full.log`)
 *
 * 我们**实时捕获**的那份 logcat 有三个刻意的取舍,每一个都会丢掉东西:
 *
 * | 取舍 | 丢掉什么 |
 * |---|---|
 * | 只从**开关打开那一刻**开始录 | 在那之前的行(启动期、上次崩溃前后) |
 * | 分片轮转,只留最近一窗 | 更早的行 |
 * | 剔掉认得出的 OEM/渲染噪声 | 那些行(通常占三分之二) |
 *
 * 前两条对「刚出问题就去看」是对的,但**导出这一刻**是另一回事:此时我们要的是
 * 「**尽可能一条不漏**」。故这里回答一个不同的问题:
 *
 * > **logd 现在还记得什么?全拿走。**
 *
 * 于是它天然包含:开关打开之前的行、已被轮转掉的行、以及被噪声过滤丢掉的行 ——
 * 三块**在别处都拿不到**的东西。
 *
 * ## 为什么只在导出时抓,而不是一直跟着录
 *
 * 两条路的目标不同(LSPosed 也是这么分的:`verbose.log` 实时可读、`full.log` 导出时补):
 * 实时那条要**便宜、连续**(每秒都在写,不能因为一次 dump 卡住应用);
 * 导出这条要**一条不漏**(宁可大、宁可慢,反正只做一次、在 IO 线程上)。
 * 用一个开关二选一,两边都做不好 —— 这正是原先的毛病。
 *
 * ## 它比实时捕获更诚实的地方
 *
 * 实时那份是**按 UID 过滤后的**本应用日志(与肉眼 `adb logcat` 看到的一致);
 * 这份是同一来源、**没经我们任何过滤**的原文。所以两者**对不上是正常的**:
 * 少了的是被我们剔掉的噪声,多的可能是更早的行。清单里会写明这一点 ——
 * 否则读者会以为两份应当逐行一致,进而怀疑日志坏了。
 *
 * ## 它是临时的
 *
 * 落在 `cacheDir` 下,由调用方在写包**之后**删掉。**不进应用私有目录里那份长期记录** ——
 * 那是「现场」,不该被导出动作改写。
 */
object XLogcatDump {

    /**
     * 包内文件名。
     *
     * **单点定义**:`describe()` 按它认这一项,别处再写一份字面量迟早漂移
     * (改了这里忘了那里 → 包内被说成 `unrecognised file`)。由 `check_x_diag_layout.py` 机检。
     */
    const val DUMP_FILE = "logcat_dump.log"

    /** 抓取失败的事件名(三段点分隔,由检查器机检)。 */
    const val CAPTURE_FAIL_EVENT = "diag.dump.fail"

    private const val LOGCAT = "/system/bin/logcat"

    /**
     * 快照体积上限。
     *
     * logd 各缓冲加起来通常是几 MB,32MB 已是很宽裕的余地。设它是为了**防病态**:
     * 万一某个进程在刷屏,不能让导出把内存/磁盘吃光。到顶写一条可见的标记后停止 ——
     * 与别处同一个口径:宁可截断得**看得见**,也不静默地截。
     */
    const val MAX_BYTES = 32L * 1024 * 1024

    /** 到顶时写进文件的那一行。 */
    internal const val CAP_MARKER =
        "!!! [x-diag] snapshot size cap reached, later buffer lines were not written (file ends here)."

    /**
     * 抓一份快照,写到 [cacheDir] 下的 [DUMP_FILE]。
     *
     * **在 IO 线程调用**(要起进程、要读管道)。
     *
     * @return 写好的文件;`null` 表示没抓到(进程起不来、或缓冲是空的)——
     *   失败会记一条**关键失败留存**,因为「导出时以为拿到了全量、其实没有」
     *   会让读的人对一个不完整的快照下判断。
     */
    fun capture(cacheDir: File): File? {
        val file = File(cacheDir, DUMP_FILE)
        val proc = try {
            // `-b all` 取全部缓冲(main/system/crash/events…):崩溃栈就在 crash 缓冲里,
            // 而那正是最该拿到的东西。`-d` = dump 后退出,不跟随(我们要的是此刻的快照)。
            ProcessBuilder(listOf(LOGCAT, "-v", "threadtime", "-b", "all", "-d"))
                .redirectErrorStream(true)
                .start()
        } catch (e: Throwable) {
            XDiagnostics.recordStickyFailure(
                domain = XDomain.CORE,
                event = CAPTURE_FAIL_EVENT,
                message = "导出时无法启动 logcat 取全缓冲快照,包内将缺少 logcat_dump.log:" + e,
                error = e,
            )
            return null
        }

        val ok = try {
            val fos = FileOutputStream(file) // 覆盖:每次导出都是一份新的快照
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { out ->
                writeHeader(out)
                val written = drain(proc, out)
                // 空缓冲(极罕见):不留下一个只有头、没有正文的文件 —— 那会让读者
                // 以为「缓冲是空的」,而真相可能是我们没读出来。
                if (written == 0L) {
                    file.delete()
                    return null
                }
            }
            true
        } catch (e: Throwable) {
            XDiagnostics.recordStickyFailure(
                domain = XDomain.CORE,
                event = CAPTURE_FAIL_EVENT,
                message = "读取 logcat 全缓冲快照时中断,包内那份可能不完整:" + e,
                error = e,
            )
            false
        } finally {
            runCatching { proc.destroyForcibly() }
        }
        // 等待进程收尾:destroyForcibly 是异步的,不等就可能留下僵尸/句柄,
        // 而调用方马上就要去读这个文件。给一个很短的宽限即可(dump 早已结束)。
        runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        return if (ok && file.isFile && file.length() > 0L) file else null
    }

    /**
     * 头:这份文件是什么、以及**它为什么与实时那份对不上**。
     *
     * ⚠️ 这段是自述、不是日志行。故与实时捕获同一口径:用 [XDiagEnv.MARK] 起头,
     * 并在末尾写一句「以下为日志」—— 读者扫到那一行就知道头到此为止。
     */
    private fun writeHeader(out: BufferedWriter) {
        val lines = listOf(
            "${XDiagEnv.MARK} logcat buffer snapshot ${XDiagEnv.MARK}",
            "taken   : ${XDiagEnv.stamp(System.currentTimeMillis())}",
            "source  : logcat -b all -d  (every logd buffer: main, system, crash, events, ...)",
            "note    : this is the RAW buffer, taken at the moment of export, with NO filtering",
            "          of any kind by the app. It therefore also holds:",
            "            - lines from BEFORE the diagnostic switch was turned on",
            "            - lines that the live capture already rotated out",
            "            - the OEM/render noise that the live capture drops",
            "          So it will NOT match logcat_N.log line for line. Fewer noisy lines here",
            "          than in the buffer is impossible; more lines here than in logcat_N.log is",
            "          expected. Use this one when you need 'everything logd still remembers'.",
            "          Only this app's lines are here (the kernel filters by UID).",
            "${XDiagEnv.MARK} raw log lines follow ${XDiagEnv.MARK}",
        )
        lines.forEach { out.write(it); out.newLine() }
        // 立刻落盘:头若留在缓冲里而进程马上被杀,就等于没写。
        out.flush()
    }

    /** 把进程输出灌进文件,返回写入的字节数。到顶写标记并停止(**可见地**截断)。 */
    private fun drain(proc: Process, out: BufferedWriter): Long {
        var written = 0L
        BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val n = line.length + 1L
                if (written + n > MAX_BYTES) {
                    out.write(CAP_MARKER)
                    out.newLine()
                    break
                }
                out.write(line)
                out.newLine()
                written += n
            }
        }
        return written
    }
}
