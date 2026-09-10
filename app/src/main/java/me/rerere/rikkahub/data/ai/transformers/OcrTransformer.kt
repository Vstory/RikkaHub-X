// [X-custom] RikkaHub-X 定制(merge 上游时保留): OCR 仅识别本轮新增图片;
// 历史图片一律只读缓存(未命中给占位),纯文字续聊不再强制进入图片识别流程(上游 issue #1736)
package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "OcrTransformer"

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 256,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            // [X-fix 上游 issue #1736] 缓存窗口 3天/64条 → 30天/256条:
            // 历史图片 OCR 文本尽量跨会话复用,减少"未命中→占位"降级频率
            expireAfterWriteMillis = 30.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        // [X-fix 上游 issue #1736] 不再对整段历史图片强制重新 OCR:
        // 仅"本轮输入(最后一条 USER 消息)"携带的本地图片才是真正需要识别的新图;
        // 历史消息里的图片一律只读缓存:命中→复用 OCR 文本,未命中→占位(不触发网络 OCR),
        // 从而纯文字续聊不再进入"正在识别图片..."流程、不再破坏 provider 前缀缓存。
        val newImageUrls = messages.lastOrNull { it.role == MessageRole.USER }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Image>()
            ?.filter { it.url.startsWith("file:") }
            ?.map { it.url }
            ?.toSet()
            ?: emptySet()

        val hasAnyImage = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        if (!hasAnyImage) return messages

        return withContext(Dispatchers.IO) {
            try {
                // 只有真正需要调 OCR 模型(本轮新图未命中缓存)时才展示处理状态;
                // 放 IO 内避免首次 cache 预载/读盘阻塞调用线程
                val needsRealOcr = newImageUrls.any { url -> cache.get(url) == null }
                if (needsRealOcr) {
                    ctx.processingStatus.value = "正在识别图片..."
                }
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    if (part.url in newImageUrls) {
                                        // 本轮新图:正常识别(内部先查缓存)
                                        UIMessagePart.Text(performOcr(part))
                                    } else {
                                        // 历史图片:只读缓存,未命中给占位,不重新识别
                                        UIMessagePart.Text(cache.get(part.url) ?: "[Image]")
                                    }
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    suspend fun performOcr(part: UIMessagePart.Image): String = runCatching {
        // Check cache first
        cache.get(part.url)?.let { cachedResult ->
            Log.i(TAG, "performOcr: Using cached result for ${part.url}")
            return cachedResult
        }

        val settings = get<SettingsStore>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId) ?: return "[Image]"
        val providerSetting = model.findProvider(settings.providers) ?: return "[Image]"
        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(settings.ocrPrompt),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image(part.url))
                )
            ),
            params = TextGenerationParams(
                model = model,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val content = result.message.toText().ifBlank { "[ERROR, OCR failed]" }
        Log.i(TAG, "performOcr: $content")
        val ocrResult = """
            <image_file_ocr>
               $content
            </image_file_ocr>
            * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
        """.trimIndent()

        // Cache the result
        cache.put(part.url, ocrResult)
        return ocrResult
    }.getOrElse {
        "[ERROR, OCR failed: $it]"
    }
}
