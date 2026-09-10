// [X-custom] RikkaHub-X 定制(merge 上游时保留): 上下文容量表仓库
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
// 静态单例,免 DI 侵入:assets 内置表兜底(离线/首启即用) + 远端表异步静默刷新(失败不阻塞)。
// 远端地址默认 main 分支的 model-contexts/context-windows.json,失败自动切 jsDelivr CDN 镜像。
package me.rerere.rikkahub.x.context

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ContextWindowRepository {
    private const val ASSET_PATH = "context-windows/context-windows.json"
    private const val REMOTE_URL = "https://raw.githubusercontent.com/Vstory/RikkaHub-X/main/model-contexts/context-windows.json"
    private const val MIRROR_URL = "https://cdn.jsdelivr.net/gh/Vstory/RikkaHub-X@main/model-contexts/context-windows.json"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var table: ContextWindowTable? = null

    /** 幂等初始化:assets 表必然可用;远端异步刷新,失败静默保留旧表 */
    fun ensureLoaded(context: Context) {
        if (table != null) return
        synchronized(this) {
            if (table != null) return
            table = loadAssets(context) ?: ContextWindowTable()
            refreshRemote()
        }
    }

    /** 查某 modelId 的上下文容量(token)。表永远有 default 兜底,正常不会返回 null。 */
    fun contextWindowFor(modelId: String?): Int? {
        val t = table ?: return null
        return modelId?.let(t::lookup)
    }

    private fun loadAssets(context: Context): ContextWindowTable? = runCatching {
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
    }.getOrNull()?.let(ContextWindowTable::parse)

    private fun refreshRemote() {
        refreshScope.launch {
            val text = fetch(REMOTE_URL) ?: fetch(MIRROR_URL) ?: return@launch
            ContextWindowTable.parse(text)?.let { table = it }
        }
    }

    private fun fetch(url: String): String? = runCatching {
        val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    }.getOrNull()
}
