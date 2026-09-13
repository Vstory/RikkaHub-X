// [X-custom] RikkaHub-X 诊断框架:事后无法补救的内容,常开留存
package me.rerere.rikkahub.x.diag

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * **存活层** —— 与诊断开关无关、跨会话保留的那一份记录。
 *
 * ```
 * x-diag/
 *   survivors.log            ← 本文件:常开
 *   session-20260913-…/
 *     logcat.log             ← 开关开着才有
 *     events.log             ← 开关开着才有
 * ```
 *
 * 它在**根目录**,不在任何会话目录里 —— 这正是「跨会话」的实现方式:开关重开只是产生
 * 新的 `session-*`,而本文件原地不动。**只有用户点「清空」才删。**
 *
 * ## 为什么需要它(2026-09-13 用户定)
 *
 * 原先「关键失败留存」([XDiagnostics.recordStickyFailure])只有两处落点,两处都留不住:
 *
 * | 落点 | 为什么留不住 |
 * |---|---|
 * | 内存里的 sticky 槽 | **进程一死就没**。而崩溃恰恰就是进程死掉 |
 * | `events.log` | 写它要**会话目录**,而目录只在开关开着时才有 → **开关关着时一个字都不落** |
 *
 * 合起来就是那句话:**崩溃之后,现场留不下来** —— 而用户往往是崩溃**之后**才想起来开开关。
 * 这类内容与「日常流水」的根本差别就在这里:
 *
 * > 日常流水错过了还能再造一次;**这一类错过了就永远没有了**。
 *
 * 用户的原话是「像 Firebase 日常统计的那些**事后无法补救**的内容」。
 *
 * ## 与 `events.log` 的分工(刻意重复,不是疏漏)
 *
 * 同一个失败会**同时**出现在两处(开关开着时)。这是有意的,理由与 LSPosed 的
 * `modules.log` / `verbose.log` 重叠一致 —— 两者服务不同读法:
 *
 * | | `events.log` | `survivors.log` |
 * |---|---|---|
 * | 判据 | **什么时候发生了什么**(时间线,顺序即信息) | **哪些事不能丢**(清单) |
 * | 寿命 | 随会话 | **跨会话,直到用户清空** |
 * | 开关 | 关了就没有 | **无关** |
 *
 * ## 一行一条,形状与语义事件一致
 *
 * 复用 [XDiagLine.format] 而不是另写一套字段 —— 两个组行函数一旦分头演化,读包的人就得
 * 逐行猜「这行是哪一类的」。`detail` 字段是这里唯一的额外项(崩溃栈、异常栈文本)。
 *
 * ## 体积阀:到顶**停写并留痕**,不静默丢弃
 *
 * 本文件的密度极低(崩溃与关键失败都是**罕见**事件),故阀给得比 `events.log` 小得多:
 * 1MB 已相当于上万条。到顶时写入一条 [CAP_EVENT] 记录后停止追加 ——
 * 那条记录会在导出物里,读者能立刻看出「这里被截断了」,而不是以为「就这些」。
 */
object XSurvivorLog {

    /** 文件名。**单点定义** —— 别处一律引用它(由 `check_x_diag_layout.py` 机检)。 */
    const val SURVIVORS_FILE = "survivors.log"

    /**
     * 体积阀(1MB)。
     *
     * 比 `events.log` 的 16MB 小得多,因为它是**清单**而非流水:一条记录就是一次崩溃或
     * 一次关键失败,密度极低。真到 1MB 只会是某个 bug 在循环里刷。
     */
    const val MAX_BYTES = 1L * 1024 * 1024

    /** 到顶时写进文件的那条记录的事件名。 */
    const val CAP_EVENT = "diag.survivor.cap"

    /** 到顶那条记录里的话。 */
    internal const val CAP_MESSAGE = "存活记录已达体积上限,后续不再写入(此前内容仍完整保留)"

    private val lock = Any()

    /** 根目录(`x-diag/`)。[install] 时定下,之后不再变。 */
    @Volatile
    private var dir: File? = null

    @Volatile
    private var installed = false

    /** 打开中的写口。`bytes` 与 `capped` 只在持锁时读写。 */
    private class Sink(val writer: BufferedWriter) {
        var bytes = 0L
        var capped = false
    }

    private var sink: Sink? = null

    /**
     * 接上。**必须在 [XDiagSession.install] 之前调用**。
     *
     * ⚠️ 顺序是硬要求:`XDiagSession` 在建不出目录时会记一条关键失败留存
     * ([XDiagSession.OPEN_FAIL_EVENT]),而那条记录要落到本文件 ——
     * 顺序反了,「目录建不出来」这件事本身就留不下痕迹,而那正是最需要它的一次。
     *
     * 只做一件事:把根目录记下来。**不建目录、不开文件** —— 建目录是
     * [XDiagSession.open] 的事,而本文件的目录要能单独建出来(开关关着也要能写)。
     * 可重复调用(幂等)。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        dir = File(context.applicationContext.filesDir, XDiagSession.ROOT_DIR)
    }

    /**
     * **测试接缝** —— 直接把根目录指到别处,不必造 `Context`。
     *
     * 为什么值得开这个口:本类最容易出错的不是「写一行」,而是**体积阀**的记账
     * (字节数按 UTF-8 算、到顶时要留痕且此后不再写、续写已有文件时起算点要对)。
     * 那几处错了都会**静默丢数据** —— 而这一层恰恰是「丢了就永远没有」的那一层。
     * 没有接缝就只能靠装机验,于是那条路径实际上永远没人验。
     *
     * 项目里没有 Robolectric,故不能靠它拿 `Context`;而把根目录抽出来只需两个参数。
     * 生产路径仍走 [install]。
     */
    internal fun installAt(root: File) {
        synchronized(lock) {
            close()
            dir = root
            installed = true
        }
    }

    /** 文件句柄(未必存在)。诊断页与打包用它判断「有没有内容」。 */
    fun file(): File? = dir?.let { File(it, SURVIVORS_FILE) }

    /**
     * 追加一条。**与开关无关** —— 这是本文件存在的全部理由,故这里刻意不看
     * [XDiagnostics.isEnabled]。
     *
     * @param detail 长文本(崩溃栈、异常栈)。**不截断**:栈被裁掉一半就失去了定位价值,
     *   而总体积由 [MAX_BYTES] 兜底。
     */
    fun append(domain: XDomain, event: String, message: String, detail: String? = null) {
        val target = sinkFor() ?: return
        synchronized(target) {
            if (target.capped) return
            // lvl 一律 W:这一层装的都是「需要注意」的事,没有普通流水。
            val line = XDiagLine.format(
                level = XLogRing.Level.WARN,
                domain = domain,
                event = event,
                message = message,
                detail = detail,
            )
            val size = line.toByteArray(Charsets.UTF_8).size + 1 // +1 = 换行
            if (target.bytes + size > MAX_BYTES) {
                target.capped = true
                writeLine(target, XDiagLine.format(XLogRing.Level.WARN, domain, CAP_EVENT, CAP_MESSAGE))
                return
            }
            writeLine(target, line)
            target.bytes += size
        }
    }

    private fun writeLine(target: Sink, line: String) {
        // 失败就作罢:这是**最后一道留存**,它再失败也没有更下一层可记 ——
        // 而在这里抛异常会顺着 recordStickyFailure 的调用栈上溯到崩溃路径,得不偿失。
        runCatching {
            target.writer.write(line)
            target.writer.newLine()
            target.writer.flush()
        }
    }

    /** 取(必要时开)写口。开不出来就返回 `null`(并记一条关键失败留存,见其注释)。 */
    private fun sinkFor(): Sink? {
        val root = dir ?: return null
        synchronized(lock) {
            sink?.let { return it }
            // ⚠️ 这里**必须自己建目录**,不能等 XDiagSession:开关关着时它根本不会开目录,
            //    而本文件恰恰要在那种时候也能写。
            if (!root.exists() && !root.mkdirs()) return null
            val file = File(root, SURVIVORS_FILE)
            val opened = runCatching {
                BufferedWriter(OutputStreamWriter(FileOutputStream(file, /* append = */ true), Charsets.UTF_8))
            }.getOrNull() ?: return null
            return Sink(opened).also {
                // 续写已有内容时从现有体积起算,否则阀会对不上文件实际大小。
                it.bytes = file.length()
                sink = it
            }
        }
    }

    /** 收掉写口(逐行 flush,故这里只需关闭句柄)。 */
    fun close() {
        synchronized(lock) {
            sink?.let { target -> synchronized(target) { runCatching { target.writer.close() } } }
            sink = null
        }
    }

    /**
     * 删掉本文件(用户点「清空」时调用)。
     *
     * ⚠️ 与 [close] 的先后由调用方保证:**先 close 再删** —— 反过来的话,写口会继续往
     * 已删除的文件里写,那些内容进了无人可读的 inode,不报错但也永远看不到。
     */
    fun clear() {
        close()
        runCatching { file()?.delete() }
    }
}
