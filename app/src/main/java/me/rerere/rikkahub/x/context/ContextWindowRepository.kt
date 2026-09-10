// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文容量表仓库
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
// 静态单例,免 DI 侵入。
//
// 数据来源**只有远端**(App 侧不内置任何表,只带解释器):
//   ① 落盘缓存:首启读本地缓存(同步,快),无缓存则容量未知 → 调用方隐藏圆环;
//   ② 远端刷新:超过表内声明的 TTL 或本地无可用表时拉取,失败静默保留旧值。
// 远端地址默认 main 分支的 model-contexts/context-windows.json,失败自动切 jsDelivr CDN 镜像。
//
// 远端是**不可信输入**却直接驱动 UI 的分母,故:表必须过 ContextWindowTable.parse 校验
// (schemaVersion / strategy / 条目值域),**整表被拒时保留上一份好表** —— 宁可数值停在上一版,
// 也不静默给出错值。
package me.rerere.rikkahub.x.context

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object ContextWindowRepository {
    private const val TAG = "ContextWindowRepo"

    private const val CACHE_DIR = "x-context"
    private const val CACHE_FILE = "context-windows.json"

    private const val REMOTE_URL = "https://raw.githubusercontent.com/Vstory/RikkaHub-X/main/model-contexts/context-windows.json"
    private const val MIRROR_URL = "https://cdn.jsdelivr.net/gh/Vstory/RikkaHub-X@main/model-contexts/context-windows.json"

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

    /** 无可用表 → 必然过期;否则按表内声明的 TTL 判断。 */
    private fun isStale(context: Context): Boolean {
        val t = table ?: return true
        val ttlMinutes = t.interpretation.refreshIntervalMinutes.takeIf { it > 0 } ?: FALLBACK_TTL_MINUTES
        val lastModified = cacheFile(context).lastModified()
        if (lastModified <= 0L) return true
        val ageMillis = System.currentTimeMillis() - lastModified
        return ageMillis >= ttlMinutes * 60_000L
    }

    private suspend fun refresh(context: Context) {
        val text = fetch(REMOTE_URL) ?: fetch(MIRROR_URL) ?: run {
            Log.w(TAG, "远端不可达,保留现有表(可能为空)")
            return
        }
        when (val result = ContextWindowTable.parse(text)) {
            is ParseResult.Ok -> {
                table = result.table
                writeCache(context, text)
            }
            // 关键分支:不被远端牵着走。整表拒用 → 继续用上一份好表
            is ParseResult.Rejected -> Log.w(TAG, "远端表被拒(${result.reason}),保留现有表")
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

    private fun fetch(url: String): String? = runCatching {
        val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
        if (resp.isSuccessful) resp.body.string() else null
    }.getOrNull()
}
