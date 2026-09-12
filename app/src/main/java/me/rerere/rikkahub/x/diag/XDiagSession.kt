// [X-custom] RikkaHub-X 诊断框架：会话目录的唯一归属者
package me.rerere.rikkahub.x.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 一次诊断会话的目录 —— 「开关关 → 开」对应一个新目录，里面按**来源**分文件：
 *
 * ```
 * x-diag/session-20260912-173001/
 *   logcat.log    ← 上游全部 logcat
 *   net.log       ← 上游请求记录（请求头 + 请求体）
 *   chat.log      ← 域 chat 的语义事件
 *   storage.log   ← 域 storage
 *   …
 * ```
 *
 * ## 为什么把「目录」单列出来管
 *
 * 三个写入者（logcat 捕获、请求记录、域文件）必须落进**同一个**目录，否则导出与阅读
 * 都得先拼目录名。若让它们各自按当前时刻造目录，就会因「谁先谁后、跨没跨过一秒」
 * 而分裂成两个目录 —— 这种分裂很隐蔽：每处单看都对，合起来读才发现少了一半。
 * 故目录由本对象**唯一**创建，其余只来取（见 [current]）。
 *
 * 另一个好处：**logcat 起不来不再殃及域文件**。捕获取不到进程时只是它自己不写，
 * 域文件照旧落在同一目录里；反之若目录归捕获所有，两者会一起消失。
 *
 * ## 生命周期
 *
 * 开启 = 建目录；关闭 = 停止写入，但**目录与文件都保留** —— 要取证的常常正是
 * 上一轮发生了什么。进程退出也不删。
 */
object XDiagSession {

    /** 会话根目录，位于应用私有目录（导出时才交给用户）。 */
    const val ROOT_DIR = "x-diag"

    /** 会话目录名前缀。 */
    const val SESSION_PREFIX = "session-"

    /** [install] 的幂等标志。 */
    @Volatile
    private var installed = false

    /** 当前会话目录；`null` = 未开启。 */
    @Volatile
    private var dir: File? = null

    /**
     * 目录名里的时间戳。
     *
     * `yyyyMMdd-HHmmss` 的一个附带好处：**按名字排序即按时间排序**，[latest] 靠这条
     * 取最近一次。若改成不带前导零的写法，这个性质就没了。
     */
    private val STAMP = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }

    /**
     * 接进诊断开关。**必须早于其它消费者** —— 它们都要往这个目录里写，故目录得先存在。
     *
     * 在 `Application.onCreate` 里紧随 [DiagnosticSwitchStore.install] 之后调用。
     * 可重复调用（幂等）。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        XDiagnostics.addEnabledListener { enabled ->
            if (enabled) open(app) else close()
        }
        // 开关在启动前就是「开」的（落盘读回来的）→ 立刻建立目录，否则启动期
        // 第一波记录无处可写。
        if (XDiagnostics.isEnabled()) open(app)
    }

    /** 当前会话目录；`null` = 未开启（或建不出来）。 */
    fun current(): File? = dir

    /**
     * 建立本次会话目录。已在会话中则**原样返回**（幂等）。
     *
     * 建不出来时记一条**关键失败留存**：那会让整个诊断会话没有落盘位置。这里的静默
     * 失败代价特别大 —— 用户以为在记录，其实什么都没记。
     */
    fun open(context: Context, startedAt: Long = System.currentTimeMillis()): File? {
        dir?.let { return it }
        val target = File(File(context.filesDir, ROOT_DIR), SESSION_PREFIX + STAMP.get()!!.format(startedAt))
        if (!target.exists() && !target.mkdirs()) {
            XDiagnostics.recordStickyFailure(
                domain = XDomain.CORE,
                event = OPEN_FAIL_EVENT,
                message = "诊断目录创建失败，本次不落盘任何记录:" + target.absolutePath,
            )
            return null
        }
        dir = target
        return target
    }

    /** 结束本次会话（目录与文件保留）。 */
    fun close() {
        dir = null
    }

    /** 目录创建失败的事件名（三段点分隔，由脚本机检）。 */
    const val OPEN_FAIL_EVENT = "diag.session.fail"

    /**
     * 最近一次会话目录 —— **即使当前未在会话中**。
     *
     * 停止之后仍然要能导出：用户很自然会「先关掉开关，再把刚录的那段导出来」，
     * 若只在会话中可取，那段现场等于白录。
     */
    fun latest(context: Context): File? =
        current() ?: File(context.filesDir, ROOT_DIR)
            .listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(SESSION_PREFIX) }
            ?.maxByOrNull { it.name }

    /** 清空全部会话目录（用户点「清空」时调用）。 */
    fun clearAll(context: Context) {
        val root = File(context.filesDir, ROOT_DIR)
        runCatching { root.listFiles()?.forEach { it.deleteRecursively() } }
    }
}
