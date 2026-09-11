// [X-custom] RikkaHub-X 诊断框架：诊断信息脱敏(纯函数)
package me.rerere.rikkahub.x.diag

/**
 * 诊断信息的脱敏。
 *
 * **为什么必须做**：诊断包是拿来**发出去**的（发给开发者、贴进对话）。
 * 若里面带着完整路径与完整哈希，每次导出前都得先人工审一遍 ——
 * 那这功能就没法顺手用了，最终结局是「懒得导出」。
 * 故默认脱敏，让诊断包**能放心发**。
 *
 * **脱什么、为什么**：
 * | 原始 | 处理后 | 理由 |
 * |---|---|---|
 * | 应用私有目录前缀 | 去掉 | 前綴里含包名与设备用户号，且对排查零价值 |
 * | 64/32 位十六进制串 | 前 [HASH_KEEP] 位 + `…` | 排查只需**定位是哪一条**；完整值既无必要，又可能被拿去比对外部数据 |
 * | 其余内容 | 原样 | **不做过度脱敏** —— 过度会导致排查信息不足，等于白记 |
 *
 * **绝不依赖本类兜底的**：凭证（API key、token、密码）。这类内容
 * **根本不该被写进日志**（见知识库「凭证零回显」铁律），
 * 靠脱敏拦是第二道防线，不是第一道。
 *
 * **已知边界**：只认「十六进制」形态的标识符。若将来某处记录了
 * base64 形态的密钥，本类**不会**识别 —— 那种情况必须在调用方避免记录。
 */
object XRedaction {

    /** 哈希保留的前缀长度 —— 8 位十六进制在单机数据量下足够唯一定位。 */
    const val HASH_KEEP = 8

    /**
     * 前缀被抹去时留下的提示，让人知道这里原本是路径。
     *
     * **不带尾斜杠**：替换后原文里的 `/` 正好补上位置，写作 `<files>/assets/x.png`。
     * 若把尾斜杠写进占位符，会得到 `<files>//assets/...` 这种双斜杠 ——
     * 已由离线验算抓出过一次。
     */
    const val ROOT_PLACEHOLDER = "<files>"

    /** 哈希被截断时的省略号（单个字符，避免与英文句点混淆）。 */
    const val ELLIPSIS = "…"

    // 32 位 = MD5，64 位 = SHA-256。都用词边界限定，避免误伤普通十六进制文本。
    //
    // ⚠️ **前瞻 `(?=[0-9a-f]*[a-f])` 要求串里至少有一个 a~f 字母**：
    // 否则一串 32 位的纯数字（如时间戳拼接、长 ID）会被当成 MD5 抹掉 ——
    // 而那类数字在日志里比哈希常见得多。真实哈希含至少一个字母的概率
    // 是 1-(10/16)^32 ≈ 1，故这个条件几乎不会漏掉哈希，却能挡掉误伤。
    private val HASH_32 = Regex("\\b(?=[0-9a-f]*[a-f])[0-9a-f]{32}\\b")
    private val HASH_64 = Regex("\\b(?=[0-9a-f]*[a-f])[0-9a-f]{64}\\b")

    /**
     * 脱敏入口。
     *
     * @param full `true` = 保留完整信息（用户在诊断页显式选了「含完整信息」）。
     *   默认 `false`。
     * @param filesRoot 应用私有文件根目录（`context.filesDir.absolutePath`）。
     *   传 `null` 表示调用方拿不到，此时只做哈希脱敏。
     */
    fun redact(text: String, filesRoot: String? = null, full: Boolean = false): String {
        if (full) return text
        var result = text
        if (filesRoot != null && filesRoot.isNotEmpty()) {
            result = redactFilesRoot(result, filesRoot)
        }
        return redactHashes(result)
    }

    /**
     * 把应用私有目录前缀替换为 [ROOT_PLACEHOLDER]。
     *
     * 同时处理 `/data/user/0/<pkg>/files` 与 `/data/data/<pkg>/files` 两种写法 ——
     * 二者指向同一目录，Android 会在不同版本/场景下给出不同形态。
     */
    fun redactFilesRoot(text: String, filesRoot: String): String {
        if (filesRoot.isEmpty()) return text
        var result = text
        for (prefix in variantsOf(filesRoot)) {
            result = result.replace(prefix, ROOT_PLACEHOLDER)
        }
        return result
    }

    /**
     * 由给定的根目录推出它的等价写法。
     *
     * 输入通常是 `/data/user/0/me.rerere.rikkahub.x/files`，
     * 等价形态是 `/data/data/me.rerere.rikkahub.x/files`。
     */
    private fun variantsOf(filesRoot: String): List<String> {
        val trimmed = filesRoot.trimEnd('/')
        val variants = linkedSetOf(trimmed)
        when {
            trimmed.startsWith("/data/user/0/") ->
                variants += "/data/data/" + trimmed.removePrefix("/data/user/0/")
            trimmed.startsWith("/data/data/") ->
                variants += "/data/user/0/" + trimmed.removePrefix("/data/data/")
        }
        // 长的先替换：避免短前缀先把长前缀切掉一半，留下半截路径
        return variants.sortedByDescending { it.length }
    }

    /**
     * 截断长十六进制串（哈希）。
     *
     * 先处理 64 位再处理 32 位：64 位串里含有 32 位子串，
     * 顺序反了会把一个 64 位哈希切成两段各自保留 8 位，得到 `xxxxxxxx…xxxxxxxx…` 这种怪结果。
     */
    fun redactHashes(text: String): String {
        var result = HASH_64.replace(text) { shorten(it.value) }
        result = HASH_32.replace(result) { shorten(it.value) }
        return result
    }

    private fun shorten(value: String): String =
        if (value.length <= HASH_KEEP) value else value.take(HASH_KEEP) + ELLIPSIS
}
