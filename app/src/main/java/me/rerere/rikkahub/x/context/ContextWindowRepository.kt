// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文容量表仓库
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
// 静态单例,免 DI 侵入。
//
// 数据来源**只有远端**(App 侧不内置任何表,只带解释器):
//   ① 落盘缓存:首启读本地缓存(同步,快),无缓存则容量未知 → 调用方隐藏圆环;
//   ② 远端刷新:超过表内声明的 TTL 或本地无可用表时拉取,失败静默保留旧值。
// 远端有**多个并列候选源**(均为 main 分支同一文件的 CDN 副本),全部拉取后按表内 updatedAt
// 取最新的一版 —— 见 ContextWindowSourceSelection。
//
// 远端是**不可信输入**却直接驱动 UI 的分母,故:表必须过 ContextWindowTable.parse 校验
// (schemaVersion / strategy / 条目值域),**整表被拒时保留上一份好表** —— 宁可数值停在上一版,
// 也不静默给出错值。
package me.rerere.rikkahub.x.context

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 容量表的对外状态,供设置页展示与「立即更新」按钮使用。
 *
 * 只暴露「人需要知道的东西」:表的数据日期、是否正在拉取、上次失败原因。
 */
data class ContextWindowTableStatus(
    /** 表内声明的**数据源时间**(`updatedAt`,ISO-8601);null 表示本地还没有可用表。 */
    val tableUpdatedAt: String? = null,
    /**
     * 本机**最后一次成功拉取并落盘**的时刻(毫秒)。
     *
     * 取值来自缓存文件的修改时间,故跨进程重启依然保留 —— 隔很久再进来看到的是上次真正更新的时刻,
     * 而不是"打开设置页"的时刻。
     */
    val lastRefreshedAtMillis: Long? = null,
    /** 是否正在拉取(用于禁用按钮、显示进度文案)。 */
    val isRefreshing: Boolean = false,
    /** 上次刷新失败原因;成功后清空。只给**类型**,展示文案由 UI 映射到字符串资源(仓库层不该持用户可见文案)。 */
    val lastError: RefreshError? = null,
)

/** 刷新失败的原因类型。 */
enum class RefreshError {
    /** 远端表未通过校验(被拒),已保留上一份好表。 */
    REJECTED,

    /** 主站与镜像都不可达。 */
    UNREACHABLE,
}

object ContextWindowRepository {
    private const val TAG = "ContextWindowRepo"

    private const val CACHE_DIR = "x-context"
    private const val CACHE_FILE = "context-windows.json"

    /**
     * 数据源候选 —— **并列**而非主从。
     *
     * 各 CDN 缓存的是同一条分支,刷新时刻并不同步:实测(2026-09-10)推完 main 后,
     * raw.githubusercontent.com 仍在供上一版(边缘缓存未过期,HTTP 仍是 200),
     * 而 githack / statically 已是新内容。故必须**全部拉取再择优**,
     * 串行"主源成功就不看备源"在此时拿到的恰恰是旧表。
     *
     * 注:`cdn.jsdelivr.net` 曾被用作镜像,但本仓库体积(约 65 MB)超过 jsDelivr 的 50 MB
     * 上限,该域名**稳定返回一段错误文案却带 200 状态码** —— 因此改用其备用域名 fastly。
     */
    private val SOURCES = listOf(
        Source(
            "raw.githubusercontent.com",
            "https://raw.githubusercontent.com/Vstory/RikkaHub-X/main/model-contexts/context-windows.json",
        ),
        Source(
            "raw.githack.com",
            "https://raw.githack.com/Vstory/RikkaHub-X/main/model-contexts/context-windows.json",
        ),
        Source(
            "cdn.statically.io",
            "https://cdn.statically.io/gh/Vstory/RikkaHub-X/main/model-contexts/context-windows.json",
        ),
        Source(
            "fastly.jsdelivr.net",
            "https://fastly.jsdelivr.net/gh/Vstory/RikkaHub-X@main/model-contexts/context-windows.json",
        ),
        // GitHub API 直读文件,不经任何 CDN 缓存 → 永不滞后。未认证有按 IP 的速率限制,
        // 命中限制时只是这一个候选失败,不影响其余源,故值得并列为一个候选。
        Source(
            "api.github.com",
            "https://api.github.com/repos/Vstory/RikkaHub-X/contents/model-contexts/context-windows.json?ref=main",
            headers = mapOf("Accept" to "application/vnd.github.raw"),
        ),
    )

    /** 缓存不可用(缺失/损坏)时的兜底 TTL —— 此时无论如何都要尝试刷新。 */
    private const val FALLBACK_TTL_MINUTES = ContextWindowInterpretation.DEFAULT_REFRESH_MINUTES

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var table: ContextWindowTable? = null

    @Volatile
    private var appContext: Context? = null

    private var initialized = false

    private val _status = MutableStateFlow(ContextWindowTableStatus())
    /** 容量表状态(数据日期 / 是否拉取中 / 上次失败原因),供设置页展示与手动刷新按钮使用。 */
    val status: StateFlow<ContextWindowTableStatus> = _status.asStateFlow()

    /**
     * 幂等初始化:同步读本地缓存(可能没有),再按 TTL 决定是否异步拉远端。
     *
     * 无缓存且离线时 [table] 保持 null → [contextWindowFor] 返回 null → 调用方隐藏圆环。
     * 不编造兜底数值:显示一个错的百分比比不显示更有害。
     */
    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val app = context.applicationContext
            appContext = app
            // 同步读缓存:本地文件,开销可忽略,且能让首帧就有正确分母
            table = readCache(app)
            _status.value = _status.value.copy(
                tableUpdatedAt = table?.updatedAt,
                lastRefreshedAtMillis = lastRefreshMillis(app),
            )
            refreshScope.launch { refreshIfNeeded() }
        }
    }

    /**
     * 查某 modelId 的上下文容量(token)。
     *
     * 表未就绪(从未联网且无缓存)时返回 null —— 调用方据此隐藏圆环,不要臆造数值。
     */
    fun contextWindowFor(modelId: String?): Int? {
        val t = table ?: return null
        return modelId?.let(t::lookup)
    }

    /**
     * 本机上次成功更新的时刻 = 缓存文件的修改时间;从未成功落盘时为 null。
     *
     * 复用文件时间戳而非另存一份状态:它与 TTL 判断用的是**同一个**依据,不会出现
     * "显示说刚更新过、TTL 却认为早过期了"这种自相矛盾。
     */
    private fun lastRefreshMillis(context: Context): Long? =
        cacheFile(context).lastModified().takeIf { it > 0L }

    /** 缓存文件的绝对路径。 */
    private fun cacheFile(context: Context): File = File(File(context.filesDir, CACHE_DIR), CACHE_FILE)

    private fun readCache(context: Context): ContextWindowTable? = runCatching {
        val file = cacheFile(context)
        if (!file.isFile) return@runCatching null
        when (val result = ContextWindowTable.parse(file.readText())) {
            is ParseResult.Ok -> result.table
            is ParseResult.Rejected -> {
                Log.w(TAG, "本地缓存不可用(${result.reason}),将重新拉取")
                null
            }
        }
    }.getOrNull()

    private suspend fun refreshIfNeeded() {
        val context = appContext ?: return
        if (!isStale(context)) return
        refresh(context)
    }

    /** 无可用表(从未成功落盘) → 必然过期;否则按表内声明的 TTL 判断。 */
    private fun isStale(context: Context): Boolean {
        val lastModified = cacheFile(context).lastModified()
        if (lastModified <= 0L) return true
        val ttlMinutes = table?.interpretation?.refreshIntervalMinutes
            ?.takeIf { it > 0 } ?: FALLBACK_TTL_MINUTES
        return System.currentTimeMillis() - lastModified >= ttlMinutes * 60_000L
    }

    /** 刷新结果。 */
    private enum class RefreshOutcome {
        /** 取到了比本地更新的表并落盘。 */
        UPDATED,

        /** 各源可达,但没有比本地更新的版本(CDN 滞后),本地保持不变。 */
        UP_TO_DATE,

        /** 拿到了内容,但都不是可用的表。 */
        REJECTED,

        /** 所有源都连不上。 */
        UNREACHABLE,
    }

    /**
     * 手动触发一次立即更新(忽略 TTL)。
     *
     * **异步执行**:内部是网络请求,绝不能卡在 UI 线程上 —— 结果通过 [status] 反馈
     * (拉取中 / 成功后的数据日期 / 失败原因)。
     *
     * 与自动刷新的差别只在"是否检查 TTL";**校验与拒表策略完全一致** ——
     * 手动刷新同样不能绕过校验,否则按一下按钮就能把坏表灌进来。
     */
    fun refreshNow() {
        val context = appContext ?: return
        if (_status.value.isRefreshing) return
        refreshScope.launch { refresh(context) }
    }

    /** 实际拉取 + 校验 + 落盘,并把结果写入 [status]。并发调用由 isRefreshing 挡住。 */
    private suspend fun refresh(context: Context) {
        if (_status.value.isRefreshing) return
        _status.value = _status.value.copy(isRefreshing = true, lastError = null)
        try {
            // fetchAndApply 是阻塞的 OkHttp 调用,必须切到 IO
            val outcome = withContext(Dispatchers.IO) { fetchAndApply(context) }
            _status.value = when (outcome) {
                RefreshOutcome.UPDATED -> _status.value.copy(
                    tableUpdatedAt = table?.updatedAt,
                    lastRefreshedAtMillis = lastRefreshMillis(context),
                    lastError = null,
                )

                // 本地表未变,但"最后检查时刻"已推进,故更新时间要跟着刷新
                RefreshOutcome.UP_TO_DATE -> _status.value.copy(
                    lastRefreshedAtMillis = lastRefreshMillis(context),
                    lastError = null,
                )

                RefreshOutcome.REJECTED -> _status.value.copy(lastError = RefreshError.REJECTED)
                RefreshOutcome.UNREACHABLE -> _status.value.copy(lastError = RefreshError.UNREACHABLE)
            }
        } finally {
            _status.value = _status.value.copy(isRefreshing = false)
        }
    }

    /**
     * 拉取全部候选源 → 各自校验 → 取最新的一版落盘。
     *
     * 并行发起:各源互不依赖,串行只会把最慢的那个叠加成总耗时。
     */
    private suspend fun fetchAndApply(context: Context): RefreshOutcome {
        val results = fetchAll()
        Log.i(TAG, "拉取结果:" + results.joinToString(" | ") { it.describe() })

        val candidates = results.filterIsInstance<SourceResult.Usable>().map { it.candidate }
        return when (val decision = selectTableSource(candidates, local = table)) {
            is SelectionOutcome.Use -> {
                table = decision.candidate.table
                writeCache(context, decision.candidate.raw)
                RefreshOutcome.UPDATED
            }

            // 各源都不比本地新(CDN 滞后):保持本地表不动,但把"最后检查时刻"推前
            SelectionOutcome.KeepLocal -> {
                Log.i(TAG, "各源均不比本地新,保持本地表:本地=${table?.updatedAt}")
                touchCache(context)
                RefreshOutcome.UP_TO_DATE
            }

            // 区分"全不可达"与"有响应但都不可用":前者是网络问题,后者是数据/源问题,
            // 给用户的提示与排查方向都不同。
            SelectionOutcome.NoCandidate -> if (results.any { it is SourceResult.Unusable }) {
                Log.w(TAG, "各源均未提供可用表,保留现有表")
                RefreshOutcome.REJECTED
            } else {
                Log.w(TAG, "远端不可达,保留现有表(可能为空)")
                RefreshOutcome.UNREACHABLE
            }
        }
    }

    /** 一个候选源的拉取结果。 */
    private sealed interface SourceResult {
        val name: String

        /** 取到了可用的表。 */
        data class Usable(val candidate: TableCandidate) : SourceResult {
            override val name: String get() = candidate.source
        }

        /** 有响应,但内容不是可用的表(CDN 错误页 / 表校验不过)。 */
        data class Unusable(override val name: String, val reason: String) : SourceResult

        /** 连不上或非 2xx。 */
        data class Failed(override val name: String, val reason: String) : SourceResult

        fun describe(): String = when (this) {
            is Usable -> "$name=${candidate.table.updatedAt}"
            is Unusable -> "$name=✗$reason"
            is Failed -> "$name=✗$reason"
        }
    }

    /** 一个远端候选源。[headers] 用于需要特定 Accept 的源(如 GitHub API 的 raw 直出)。 */
    private class Source(
        val name: String,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    )

    /** 并行拉取全部候选源。单个源的异常不得影响其余源,故各自 runCatching 后成对返回。 */
    private suspend fun fetchAll(): List<SourceResult> = coroutineScope {
        SOURCES.map { source -> async(Dispatchers.IO) { fetchOne(source) } }.awaitAll()
    }

    private fun fetchOne(source: Source): SourceResult {
        val raw = runCatching {
            val request = Request.Builder().url(source.url).get()
                .apply { source.headers.forEach { (key, value) -> header(key, value) } }
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body.string() else null
        }.getOrNull() ?: return SourceResult.Failed(source.name, "请求失败")

        return when (val parsed = ContextWindowTable.parse(raw)) {
            is ParseResult.Ok -> SourceResult.Usable(TableCandidate(source.name, raw, parsed.table))
            is ParseResult.Rejected -> SourceResult.Unusable(
                source.name,
                if (parsed.unusableSource) "内容不是本表" else "表被拒(${parsed.reason})",
            )
        }
    }

    /** 先写临时文件再改名,避免进程中断留下半截缓存。 */
    private fun writeCache(context: Context, text: String) {
        runCatching {
            val file = cacheFile(context)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "缓存写入失败", it) }
    }

    /**
     * 只推进缓存文件的修改时间,内容不动。
     *
     * 各源都比本地旧时,这次"检查"本身仍算成功 —— 把最后检查时刻前推,既让设置页显示的
     * 更新时间如实反映"刚问过源",也避免短时间内反复重试。
     */
    private fun touchCache(context: Context) {
        runCatching { cacheFile(context).setLastModified(System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "更新时间戳失败", it) }
    }
}
