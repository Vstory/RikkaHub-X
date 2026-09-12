// [X-custom] RikkaHub-X 诊断框架：语义事件按域落盘
package me.rerere.rikkahub.x.diag

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 把 X 的语义事件**按域落盘** —— 每域一个文件,名字就是 `XDomain.key`:
 *
 * ```
 * session-<时间>/chat.log     ← 域 chat
 * session-<时间>/storage.log  ← 域 storage
 * …
 * ```
 *
 * ## 它补的是哪个缺口
 *
 * 语义事件此前**只进内存环**(每个域 2000 条,进程一死全没)。而用户的诊断开关是
 * **持久**的 —— 重启后仍然开着,于是"开关开着,但昨天的记录回看不到"这种自相矛盾
 * 就出现了。落盘之后:开关开着期间发生的事,重启后仍可导出。
 *
 * 内存环**保留不删**:诊断页要按域展示、快速导出也用它。这里只是**多写一份**。
 * 轻微重复是刻意的 —— 两者服务不同读法(页面上翻看 / 打包交给 AI)。
 *
 * ## 一个写入者一个锁,域之间互不阻塞
 *
 * 每个域独占自己的 writer 与锁。语义事件来自任意线程(聊天在 IO 线程、界面在 UI 线程),
 * 而域之间没有共享状态,故不需要一把全局写锁 —— 那会让一个慢写入拖住所有域。
 *
 * ## 逐行 flush 的取舍
 *
 * 每写一行就 flush。语义事件是**低频**的(一次对话几到几十条),而诊断要的正是
 * "应用崩了/被杀了,现场还在"。代价是每条一次 syscall —— 相对事件频率可忽略。
 * ⚠️ 若将来往里加高频事件,这条取舍要重新算。
 *
 * ## 体积阀
 *
 * 单域 [MAX_BYTES_PER_DOMAIN],到顶写一条 [CAP_EVENT] 记录后停写该域(其它域不受影响)。
 * 不静默丢弃:那条记录会出现在导出物里,且它本身就是一行合法 JSON。
 */
object XDiagFileStore {

    /** 单域体积安全阀。语义事件是低频的,16MB 相当于几万条 —— 到顶基本只可能是某个 bug。 */
    const val MAX_BYTES_PER_DOMAIN = 16L * 1024 * 1024

    /**
     * 到顶时写进该域文件的那条记录的事件名。
     *
     * ⚠️ 与 logcat 那边的 `CAP_MARKER`(一行非 JSON 的醒目文本)**不同**:域文件的不变量是
     * **每一行都是 JSON**,解析方才能无差别地逐行读。故这里是一条正常的记录,而不是
     * 一行裸文本 —— 它带着域、级别与时间,在导出物里同样一眼可见。
     */
    const val CAP_EVENT = "diag.file.cap"

    /** 到顶那条记录的说明。 */
    internal fun capMessage(): String =
        "单域体积已达上限(" + MAX_BYTES_PER_DOMAIN + " 字节),本域后续记录不再写入"

    private val lock = Any()

    /** [install] 的幂等标志。 */
    @Volatile
    private var installed = false

    /**
     * 一个域的写口。
     *
     * [dir] 记着它开在哪个会话目录里 —— 开关重开会产生新目录,那时必须换 writer,
     * 而不是继续往上一轮的目录里写(那会让新会话的记录混进旧目录)。
     */
    private class Sink(val dir: File, val writer: BufferedWriter) {
        var bytes = 0L
        var capped = false
    }

    private val sinks = HashMap<XDomain, Sink>()

    /**
     * 接上落盘。**必须在 [XDiagSession.install] 之后调用** —— 落盘位置由它给出。
     * 可重复调用(幂等)。
     */
    fun install() {
        if (installed) return
        installed = true
        XDiagnostics.setLineSink { domain, line -> writeLine(domain, line) }
        // 开关一动就收掉全部 writer:关闭是收尾,开启则要丢掉上一个会话的目录
        // (新目录由 XDiagSession 建好,下次写入时惰性打开)。
        XDiagnostics.addEnabledListener { closeAll() }
    }

    /**
     * 落一行。**不可写时静默返回** —— 见类注释里关于体积阀与无会话的说明。
     *
     * 两个调用者:语义事件(经 [XDiagnostics] 的 lineSink)与请求记录(见 [XRequestLog])。
     * 后者自带字段结构,不经 `XDiagLine` 的通用事件格式,故需要一个直接入口 ——
     * 但**规格与约束相同**:一行一条、每行都是合法 JSON、同一个体积阀。
     */
    fun writeLine(domain: XDomain, line: String) {
        // 没有会话目录 = 开关关着,或目录建不出来(后者已记关键失败留存)。
        val dir = XDiagSession.current() ?: return
        val sink = synchronized(lock) { sinkFor(domain, dir) } ?: return
        synchronized(sink) {
            if (sink.capped) return
            val size = line.toByteArray(Charsets.UTF_8).size + 1 // +1 = 换行
            if (sink.bytes + size > MAX_BYTES_PER_DOMAIN) {
                sink.capped = true
                runCatching {
                    sink.writer.write(XDiagLine.format(XLogRing.Level.WARN, domain, CAP_EVENT, capMessage()))
                    sink.writer.newLine()
                    sink.writer.flush()
                }
                return
            }
            runCatching {
                sink.writer.write(line)
                sink.writer.newLine()
                sink.writer.flush()
                sink.bytes += size
            }
        }
    }

    /** 取(必要时开)某域在当前会话目录里的写口。调用方须持 [lock]。 */
    private fun sinkFor(domain: XDomain, dir: File): Sink? {
        sinks[domain]?.let { existing ->
            if (existing.dir == dir) return existing
            close(existing)
            sinks.remove(domain)
        }
        val file = File(dir, domain.key + ".log")
        val opened = runCatching {
            BufferedWriter(OutputStreamWriter(FileOutputStream(file, /* append = */ true), Charsets.UTF_8))
        }.getOrNull()
        if (opened == null) {
            // 打不开就明确留存 —— 否则该域会「看起来在记录,其实一条都没写」。
            XDiagnostics.recordStickyFailure(
                domain = XDomain.CORE,
                event = XDiagSession.OPEN_FAIL_EVENT,
                message = "域日志文件打不开,该域本次不落盘:" + file.absolutePath,
            )
            return null
        }
        // 续写已有内容时,计数从现有体积起算 —— 否则阀会对不上文件实际大小。
        return Sink(dir, opened).also {
            it.bytes = file.length()
            sinks[domain] = it
        }
    }

    /** 收掉全部 writer(内容已逐行 flush,故这里只需关闭句柄)。 */
    fun closeAll() {
        synchronized(lock) {
            sinks.values.forEach { close(it) }
            sinks.clear()
        }
    }

    private fun close(sink: Sink) {
        // 持 sink 锁:否则会与正在写同一个 writer 的线程撞上(它们各自 synchronized(sink))
        synchronized(sink) {
            runCatching {
                sink.writer.flush()
                sink.writer.close()
            }
        }
    }
}
