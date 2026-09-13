// [X-custom] RikkaHub-X 诊断框架:事件按时间线落盘
package me.rerere.rikkahub.x.diag

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 把 X 的语义事件与网络元数据**落进同一条时间线** —— `events.log`。
 *
 * ``` 
 * session-<时间>/events.log   ← 全部事件,按发生顺序
 * session-<时间>/logcat.log   ← 原始 logcat(另一个写入者)
 * ```
 *
 * ## 为什么是**一个**文件,而不是每域一个(2026-09-13 用户决定)
 *
 * 原先每域一个文件(11 个域就有 11 个文件,文件名直接用域键)。
 * 改成一个,理由是**排查时最常问的恰恰是跨域因果**:
 *
 * > 「发消息 → 存储写失败 → 同步起来了 → 崩了」
 *
 * 分成 11 个文件时,要回答它得同时打开几份、**按时间戳手工合并** —— 而分域恰恰把这个
 * 交错**拆散**了。**时间顺序本身就是信息**,一条时间线直接读得出来。
 *
 * 想只看某一域?**读的时候过滤**就行 —— 每行都带 `domain` 字段
 * (`grep '"domain":"storage"'`),数据本来就支持。于是分域的收益**从写入端搬到了读取端**,
 * 而写入端省下了「文件 ↔ 域」这套耦合(它原先还要一个检查器专门守着)。
 *
 * ## 为什么网络记录也在里面
 *
 * 它同样按时间发生(「什么时候发了什么请求」),正是时间线该有的东西。
 * 行格式与语义事件**已对齐**(见 [XNetLine]):`at` / `lvl` / `domain` / `event` 四个核心字段
 * 一致,专有字段平铺、靠 `domain` 区分。
 *
 * ⚠️ **请求体不在里面** —— 只记 `reqBytes`。理由是请求体可达数百 KB,会让时间线读不出来;
 * 详见 [XNetLine] 的类注释。
 *
 * ## 它补的是哪个缺口
 *
 * 语义事件此前**只进内存环**(每个域 2000 条,进程一死全没)。而用户的诊断开关是
 * **持久**的 —— 重启后仍然开着,于是「开关开着,但昨天的记录回看不到」这种自相矛盾
 * 就出现了。落盘之后:开关开着期间发生的事,重启后仍可导出。
 *
 * 内存环**保留不删**:诊断页要按域展示、快速导出也用它。这里只是**多写一份**。
 * 轻微重复是刻意的 —— 两者服务不同读法(页面上翻看 / 打包交给 AI)。
 *
 * ## 逐行 flush 的取舍
 *
 * 每写一行就 flush。事件是**低频**的(一次对话几到几十条),而诊断要的正是
 * 「应用崩了/被杀了,现场还在」。代价是每条一次 syscall —— 相对事件频率可忽略。
 * ⚠️ 若将来往里加高频事件,这条取舍要重新算。
 *
 * ## 体积阀
 *
 * 单文件 [MAX_BYTES],到顶写一条 [CAP_EVENT] 记录后停写。**不静默丢弃**:
 * 那条记录会出现在导出物里,且它本身就是一行合法 JSON。
 *
 * 合并成单文件后,**一个阀管全部** —— 原先每域一个阀是「互不挤占」的保障,
 * 而单文件下这层保障不再有意义(挤占是必然且可接受的:到顶本就意味着异常)。
 */
object XDiagFileStore {

    /** 事件时间线的文件名。 */
    const val EVENTS_FILE = "events.log"

    /**
     * 单文件体积安全阀。事件是低频的,16MB 相当于几万条 —— 到顶基本只可能是某个 bug。
     *
     * (合并前是「每域 16MB」;合并后总量仍是这个数,因为总量从来不是瓶颈,
     *  `logcat.log` 才是大头,而它有单独的阀。)
     */
    const val MAX_BYTES = 16L * 1024 * 1024

    /**
     * 到顶时写进文件的那条记录的事件名。
     *
     * ⚠️ 与 logcat 那边的分片标记(一行非 JSON 的醒目文本)**不同**:这个文件的不变量是
     * **每一行都是 JSON**,解析方才能无差别地逐行读。故这里是一条正常的记录,而不是
     * 一行裸文本 —— 它带着域、级别与时间,在导出物里同样一眼可见。
     */
    const val CAP_EVENT = "diag.file.cap"

    /** 到顶那条记录的说明。 */
    internal fun capMessage(): String =
        "事件日志体积已达上限(" + MAX_BYTES + " 字节),后续记录不再写入"

    private val lock = Any()

    /** [install] 的幂等标志。 */
    @Volatile
    private var installed = false

    /**
     * 写口。
     *
     * [dir] 记着它开在哪个会话目录里 —— 开关重开会产生新目录,那时必须换 writer,
     * 而不是继续往上一轮的目录里写(那会让新会话的记录混进旧目录)。
     */
    private class Sink(val dir: File, val writer: BufferedWriter) {
        var bytes = 0L
        var capped = false
    }

    /** 只有一个写口(合并成单文件之后)。 */
    private var sink: Sink? = null

    /**
     * 接上落盘。**必须在 [XDiagSession.install] 之后调用** —— 落盘位置由它给出。
     * 可重复调用(幂等)。
     */
    fun install() {
        if (installed) return
        installed = true
        XDiagnostics.setLineSink { domain, line -> writeLine(domain, line) }
        // 开关一动就收掉 writer:关闭是收尾,开启则要丢掉上一个会话的目录
        // (新目录由 XDiagSession 建好,下次写入时惰性打开)。
        XDiagnostics.addEnabledListener { closeAll() }
    }

    /**
     * 落一行。**不可写时静默返回** —— 见类注释里关于体积阀与无会话的说明。
     *
     * 两个调用者:语义事件(经 [XDiagnostics] 的 lineSink)与请求记录(见 [XRequestLog])。
     * 后者自带字段结构,不经 `XDiagLine` 的通用事件格式,故需要一个直接入口 ——
     * 但**规格与约束相同**:一行一条、每行都是合法 JSON、同一个体积阀。
     *
     * @param domain 只用于到顶那条记录(它需要一个域来归属)。
     */
    fun writeLine(domain: XDomain, line: String) {
        // 没有会话目录 = 开关关着,或目录建不出来(后者已记关键失败留存)。
        val dir = XDiagSession.current() ?: return
        val target = synchronized(lock) { sinkFor(dir) } ?: return
        synchronized(target) {
            if (target.capped) return
            val size = line.toByteArray(Charsets.UTF_8).size + 1 // +1 = 换行
            if (target.bytes + size > MAX_BYTES) {
                target.capped = true
                runCatching {
                    target.writer.write(XDiagLine.format(XLogRing.Level.WARN, domain, CAP_EVENT, capMessage()))
                    target.writer.newLine()
                    target.writer.flush()
                }
                return
            }
            runCatching {
                target.writer.write(line)
                target.writer.newLine()
                target.writer.flush()
                target.bytes += size
            }
        }
    }

    /** 取(必要时开)当前会话目录里的写口。调用方须持 [lock]。 */
    private fun sinkFor(dir: File): Sink? {
        sink?.let { existing ->
            if (existing.dir == dir) return existing
            close(existing)
            sink = null
        }
        val file = File(dir, EVENTS_FILE)
        val opened = runCatching {
            BufferedWriter(OutputStreamWriter(FileOutputStream(file, /* append = */ true), Charsets.UTF_8))
        }.getOrNull()
        if (opened == null) {
            // 打不开就明确留存 —— 否则会「看起来在记录,其实一条都没写」。
            XDiagnostics.recordStickyFailure(
                domain = XDomain.CORE,
                event = XDiagSession.OPEN_FAIL_EVENT,
                message = "事件日志文件打不开,本次不落盘:" + file.absolutePath,
            )
            return null
        }
        // 续写已有内容时,计数从现有体积起算 —— 否则阀会对不上文件实际大小。
        return Sink(dir, opened).also {
            it.bytes = file.length()
            sink = it
        }
    }

    /** 收掉 writer(内容已逐行 flush,故这里只需关闭句柄)。 */
    fun closeAll() {
        synchronized(lock) {
            sink?.let { close(it) }
            sink = null
        }
    }

    private fun close(target: Sink) {
        // 持 sink 锁:否则会与正在写同一个 writer 的线程撞上(它们各自 synchronized(sink))
        synchronized(target) {
            runCatching {
                target.writer.flush()
                target.writer.close()
            }
        }
    }
}
