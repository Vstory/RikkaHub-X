// [X-custom] RikkaHub-X 定制(与上游合并对照 X-CUSTOM.md 保留): eval_javascript 工具资源安全与错误处理加固
package me.rerere.rikkahub.data.ai.tools.local

import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 单次 JS 执行的内存上限(字节)。
 *
 * QuickJS 的 JS_SetMemoryLimit 统计的是整个 runtime(含本次调用创建的全部 context/对象)。
 * 设置上限后,超限脚本会抛 JS 内存错误而不是把整个 App 打到 OOM。
 * 64MB 对常规计算/数据处理足够,同时能挡住失控的大分配。
 */
private const val MAX_JS_MEMORY_BYTES = 64 * 1024 * 1024

/**
 * console 输出捕获行数上限。
 *
 * 防止 `while(true) console.log(...)` 之类脚本把无限行文本塞进工具返回值,
 * 撑爆上下文 / 浪费 token。
 */
private const val MAX_CONSOLE_LOG_LINES = 200

internal fun buildJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        Execute JavaScript code using QuickJS engine (ES2020).
        The result is the value of the last expression in the code.
        For calculations with decimals, use toFixed() to control precision.
        Console output (log/info/warn/error) is captured and returned in 'logs' field.
        No DOM or Node.js APIs available.
        Memory allocation is limited (about 64MB); avoid unbounded loops or huge allocations.
        Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
            },
            required = listOf("code")
        )
    },
    execute = {
        val logs = arrayListOf<String>()

        fun errorPart(message: String): UIMessagePart.Text =
            UIMessagePart.Text(
                buildJsonObject {
                    put("error", JsonPrimitive(message))
                }.toString()
            )

        try {
            // use{} 保证无论脚本成功/抛错,QuickJS native context 都被 destroy,
            // 避免每次调用泄漏一个 native 引擎实例(旧实现无任何释放路径)。
            QuickJSContext.create().use { context ->
                // 显式内存上限:失控分配抛 JS 错误而非 OOM 整个 App。
                context.setMemoryLimit(MAX_JS_MEMORY_BYTES)

                context.setConsole(object : QuickJSContext.Console {
                    private fun capture(level: String, info: String?) {
                        when {
                            logs.size < MAX_CONSOLE_LOG_LINES -> logs.add("[$level] $info")
                            logs.size == MAX_CONSOLE_LOG_LINES -> logs.add(
                                "[TRUNCATED] console output exceeds $MAX_CONSOLE_LOG_LINES lines, remaining output dropped"
                            )
                        }
                    }

                    override fun log(info: String?) = capture("LOG", info)

                    override fun info(info: String?) = capture("INFO", info)

                    override fun warn(info: String?) = capture("WARN", info)

                    override fun error(info: String?) = capture("ERROR", info)
                })

                val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
                if (code.isNullOrBlank()) {
                    return@use listOf(
                        errorPart("Tool 'eval_javascript' requires a non-empty 'code' string argument.")
                    )
                }

                val result = try {
                    context.evaluate(code)
                } catch (e: Exception) {
                    // JS 语法/执行错误:作为结构化工具错误返回,让模型看到可读信息,
                    // 而不是让异常一路抛到生成循环外层。
                    return@use listOf(
                        errorPart("JavaScript execution failed: ${e.message ?: e.javaClass.simpleName}")
                    )
                }

                val payload = buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", JsonPrimitive(logs.joinToString("\n")))
                    }
                    put(
                        key = "result",
                        element = when (result) {
                            null -> JsonNull
                            is QuickJSObject -> JsonPrimitive(result.stringify())
                            else -> JsonPrimitive(result.toString())
                        }
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        } catch (e: Exception) {
            // 兜底(含 context 创建失败等引擎层错误),避免裸异常炸掉整个生成回合。
            listOf(errorPart("eval_javascript internal error: ${e.message ?: e.javaClass.simpleName}"))
        }
    }
)
