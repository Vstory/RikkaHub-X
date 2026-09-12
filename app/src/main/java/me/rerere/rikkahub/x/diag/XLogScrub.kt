// [X-custom] RikkaHub-X 诊断框架：日志导出前的凭据脱敏
package me.rerere.rikkahub.x.diag

/**
 * 应用日志导出前的**凭据脱敏**。
 *
 * ## 为什么另起一个,而不是复用 [XRedaction]
 *
 * [XRedaction] 是为 X 自己的**事件文本**写的:它管的是「路径太长读着累、哈希太长没意义」——
 * 缩短路径、掩掉长哈希。而 logcat 是**任意文本**:上游 206 处 `Log.*`、框架日志、系统文案,
 * 内容不可预知。
 *
 * 两者要防的东西根本不同:日志要防的是**凭证泄漏**(key、token、密码),不是可读性。
 * 故这里只做一件事 —— **正则把凭据值替换掉**。规则少、意图直白,一眼能看懂它在防什么。
 *
 * ## 三条必须守住的不变式
 *
 * **① 顺序即优先级**(规则自上而下依次施加,前面的结果进入后面的匹配):
 *
 * - `Authorization` 规则必须最先 —— 它后面整段都是凭据;
 * - `Bearer` 必须早于键值规则 —— 否则 `Authorization: Bearer <长令牌>` 里那个令牌
 *   只会被按「一个词」处理,主体留在后面;
 * - 长厂商前缀必须早于短前缀(`sk-ant-` 先于 `sk-`),否则短规则吃掉长前缀的前一段、留下尾巴。
 *
 * **②b 值必须在「引号/换行」处停,不能一律吃到行尾。** 这一条是准备接上游请求日志**时
 * 才暴露的:`Authorization: Bearer x` 在 logcat 里确实独占一行,掩到行尾没问题;但请求日志里
 * 同样一行是**一整条 JSON**(`{"headers":{"Authorization":"Bearer x"},"body":"…"}`),
 * 掩到行尾会把**同一行的请求正文一起吞掉** —— 那正是最该留下的内容。
 * 故这两条规则的值都收在 `[^"\r\n]*`:两种载体都对。
 *
 * **② 值不能吃结构字符。** 值用 `[^\s&"'}\],;)]+` 而不是 `\S+` —— 否则
 * `{"api_key": "sk-x"}` 的收尾 `"}`、`?api_key=x&page=2` 的 `&page=2` 都会被一并吃掉。
 * (两处都是离线验算时才发现的。)
 *
 * **③ 用量数字不能掩。** 要求凭据词**紧贴分隔符**且**后面不跟 s**:于是
 * `access_token=` 命中,而 `max_tokens=1000`、`token_count=5`、`tokens=1000` 不命中 ——
 * 后者在 LLM 应用里遍地都是,掩掉等于把最需要看的诊断信息抹了。
 *
 * ## 已知取舍:宁可多掩,不可漏
 *
 * 误掩的代价是这一行少点可读性;漏掩的代价是**凭证被带进聊天/AI**。两者不对等,故一律从宽 ——
 * 比如短值 `token=abc` 也照样掩掉。
 */
object XLogScrub {

    /** 掩码。保留键名、只换掉值 —— 键名不敏感,留着便于定位。 */
    const val MASK = "***"

    /**
     * 值字符集:排除空白与**结构字符**。
     *
     * `&` 是 URL 参数分隔、`"` `'` 是引号、`}` `]` `)` `,` `;` 是容器收尾 ——
     * 把它们当作值的一部分会吃掉本应保留的结构(见类注释 ②)。
     */
    private const val VALUE = "[^\\s&\"'}\\],;)]+"

    /**
     * 导出时是否应用脱敏。**当前为 `false`(暂时关闭)**。
     *
     * ## 为什么关掉
     *
     * 先把手头功能做完,隐私保护之后单独处理。开着它会把导出物里的凭据换成 [MASK],
     * 而调试期经常正需要看到原文。关掉后导出物是**原样日志**。
     *
     * ## 关掉意味着什么(不是"以后再说的小事")
     *
     * 导出物里会**原样带着** `Authorization: Bearer <key>`、请求体里的 `api_key` 等。
     * 而这个导出物的去向通常是**聊天/AI** —— 也就是把密钥交出去了。故:
     * 分享前请自己过一眼;别把导出物提交进仓库(见下面那条注释)。
     *
     * ## 恢复办法
     *
     * 把这个常量改回 `true` 即可 —— 规则与单测都还在,`XLogScrubTest` 一直会跑。
     */
    const val ENABLED: Boolean = false

    private class Rule(val regex: Regex, val replacement: String)

    private val RULES: List<Rule> = listOf(
        // ① Authorization 头:后面整段都是凭据,掩到行尾。必须最先(见类注释 ①)。
        // 值取到**行尾或最近的引号**为止,不能无条件吃到行尾 —— 见类注释 ②b。
        Rule(
            Regex("(?i)(\\bauthorization\\b[\"']?\\s*[=:]\\s*[\"']?)([^\"\\r\\n]*)"),
            "\$1" + MASK,
        ),

        // ② 厂商前缀。长前缀排在短前缀之前。
        Rule(Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}"), "github_pat_" + MASK),
        Rule(Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}"), "ghp_" + MASK),
        Rule(Regex("\\bsk-ant-[A-Za-z0-9_\\-]{16,}"), "sk-ant-" + MASK),
        Rule(Regex("\\bsk-or-v1-[A-Za-z0-9]{16,}"), "sk-or-v1-" + MASK),
        Rule(Regex("\\bsk-proj-[A-Za-z0-9_\\-]{16,}"), "sk-proj-" + MASK),
        Rule(Regex("\\bsk-[A-Za-z0-9]{16,}"), "sk-" + MASK),
        Rule(Regex("\\bglpat-[A-Za-z0-9_\\-]{16,}"), "glpat-" + MASK),
        Rule(Regex("\\bxai-[A-Za-z0-9]{16,}"), "xai-" + MASK),
        Rule(Regex("\\bhf_[A-Za-z0-9]{16,}"), "hf_" + MASK),
        Rule(Regex("\\bAIza[A-Za-z0-9_\\-]{20,}"), "AIza" + MASK),
        Rule(Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}"), "AKIA" + MASK),

        // ③ JWT:三段 base64url,头段必以 eyJ 开头(即 `{"alg"` …)。整体掩掉。
        Rule(
            Regex("\\beyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]+"),
            "eyJ" + MASK,
        ),

        // ④ Bearer 令牌(可能不在 Authorization 头里:正文、URL、代码片段)。
        Rule(Regex("(?i)\\bBearer\\s+" + VALUE), "Bearer " + MASK),

        // ④b Cookie 与 Set-Cookie。**这条是补记请求头/响应头时才加的** ——
        //     会话令牌常放在 cookie 里,而 `Authorization` 规则管不到它。
        //     两个方向都掩:`Cookie:` 是发出去的,`Set-Cookie:` 是服务端下发的。
        Rule(
            Regex("(?i)\\b(Set-Cookie|Cookie)([\"']?\\s*:\\s*[\"']?)([^\"\\r\\n]*)"),
            "\$1\$2" + MASK,
        ),

        // ⑤ 键值形式。凭据词紧贴分隔符(故 `token_count=5` 不匹配)、且后面不跟 s
        //    (故 `max_tokens=1000` 不匹配)。分隔符两侧允许引号,以覆盖 JSON。
        //    `secret_access_key` / `private_key` 单列:这两个词在中间、末尾是 key,
        //    不列出来整串都不匹配(AWS 密钥值没有可识别前缀,漏了就是真漏)。
        Rule(
            Regex(
                "(?i)([A-Za-z0-9_\\-]*(?:secret[_-]?access[_-]?key|private[_-]?key" +
                    "|api[_-]?key|secret|token|password|passwd|pwd|credential)(?!s))" +
                    "([\"']?\\s*[=:]\\s*[\"']?)(" + VALUE + ")"
            ),
            "\$1\$2" + MASK,
        ),

        // ⑥ URL 查询参数:`?key=`/`&token=` 这类。这里允许裸 `key` ——
        //    出现在查询串里基本就是凭证位。
        Rule(
            Regex(
                "(?i)([?&](?:api[_-]?key|apikey|key|token|access[_-]?token|secret|password|pwd)=)" +
                    VALUE
            ),
            "\$1" + MASK,
        ),
    )

    /**
     * 脱敏一行。**纯函数**,可离线验算与单测。
     *
     * 逐行处理是刻意的:logcat 天然按行,且逐行与流式写出配对,内存占用与文件大小无关。
     */
    fun scrub(line: String): String {
        var out = line
        for (rule in RULES) {
            out = rule.regex.replace(out, rule.replacement)
        }
        return out
    }

    /** 规则条数。公开出来是为了让「规则被误删成空表」这件事可被断言发现。 */
    val ruleCount: Int get() = RULES.size
}
