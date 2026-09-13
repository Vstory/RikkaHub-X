// [X-custom] RikkaHub-X 诊断框架:logcat 分片的命名与保留策略(纯逻辑)
package me.rerere.rikkahub.x.diag

/**
 * logcat 捕获的**分片命名**与**保留策略** —— 纯逻辑,不碰文件系统。
 *
 * ```
 * session-20260913-…/
 *   logcat_1.log   ← 第一批(带捕获头:设备、版本、开关状态)
 *   logcat_2.log   ← 接着写;片首有一行分片标记
 *   logcat_3.log   ← 当前正在写的那一片永远是编号最大的
 * ```
 *
 * ## 为什么分片(2026-09-13)
 *
 * 原先是**一个** 200MB 的文件,到顶写一条警示就停。两处都不对:
 *
 * ① **到顶之后丢的是最新那一段**。日志的用法是「刚出问题,去看刚才发生了什么」——
 *    而那种情形下最该看的恰恰是**刚发生的**行,却被丢了。反了。
 * ② **单文件 200MB 对「交给 AI 读」极不友好**。要它读一段,结果得先吞下 200MB。
 *
 * 改成分片 + LRU:单片 [XLogcatCapture.DEFAULT_PART_BYTES]、保留最近
 * [XLogcatCapture.DEFAULT_KEEP_PARTS] 片 —— 磁盘上限是两者相乘(比原来更小),
 * 而**最新那段一定在**。旧片自然老去,不再有「停写」这件事。
 *
 * ## 为什么单独一个纯逻辑对象
 *
 * 命名与「保留哪几片」是**能被算错的**:编号差一、保留范围多留一片少留一片,
 * 都不会报错,只在某天发现「怎么少了/多了」时才暴露。抽出来就能在 JVM 里逐项验
 * (见 `XLogcatPartsTest`);而真正的写盘循环没法单测,它只负责调用这里。
 *
 * ⚠️ 名字**不许在别处硬编码**:`check_x_diag_layout.py` 专门守着这条 —— 各处各写一份
 * 字面量,改了这里忘了那里就会静默失配(打包时认不出文件、清单写成 `unrecognised`)。
 */
object XLogcatParts {

    /** 文件名前缀。分片名 = 它 + `_` + 编号 + [SUFFIX]。 */
    const val PREFIX = "logcat"

    /** 文件名后缀。 */
    const val SUFFIX = ".log"

    /**
     * 第 [part] 片的文件名。**从 1 开始**(不是 0)—— 与人的说法一致(「第一片」),
     * 而 `partOf` 遇到 `logcat_0.log` 会判为不是分片,避免出现「第零片」这种半成品。
     */
    fun nameOf(part: Int): String = PREFIX + "_" + part + SUFFIX

    /**
     * 这个名字是不是本模块的分片名;是则给出编号。
     *
     * 判据**刻意收窄**:必须恰好是 `logcat_<正整数>.log`。
     * 放宽成「以 logcat 开头」会把 `logcat_backup.log` 之类算进来,而那些不是本模块写的 ——
     * 认错名字的代价是「打包时把它当原始日志、或者反过来漏掉真日志」,两边都不轻。
     */
    fun partOf(name: String): Int? {
        if (!name.startsWith("$PREFIX" + "_") || !name.endsWith(SUFFIX)) return null
        val digits = name.substring(PREFIX.length + 1, name.length - SUFFIX.length)
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
        // ⚠️ **拒前导零**:否则 `logcat_1.log` 与 `logcat_01.log` 都指向第 1 片,
        //    而「按名字排序」会给它们两个不同的位置 —— 「按片号排序 = 按时间排序」
        //    这条就不再成立,而打包时的顺序正靠它。
        //    (上面那段注释早就这么写了,而代码里漏了 —— 是**单测**把它逼出来的:
        //     我照注释写了一条断言,CI 上红了才发现实现没跟上。)
        if (digits.length > 1 && digits[0] == '0') return null
        val n = digits.toIntOrNull() ?: return null
        return if (n >= 1) n else null
    }

    /** 便捷判断(同 [partOf] 的判据)。 */
    fun isPartName(name: String): Boolean = partOf(name) != null

    /**
     * 已经写到第 [latest] 片、保留 [keep] 片时,**最老的那一片**是第几片。
     *
     * 等于 `latest - keep + 1`,但不小于 1 —— 会话刚开始时还没写满,当然不删。
     *
     * ⚠️ 这里是**差一错误的常驻点**:「保留 N 片」意味着可接受的范围是
     * `[latest - N + 1, latest]`。写成 `latest - N` 就会多留一片(磁盘上限比声称的多一份),
     * 而两种都不会报错。
     */
    fun oldestKept(latest: Int, keep: Int): Int = maxOf(1, latest - keep + 1)

    /**
     * 轮转到下一片时应该删掉哪一片;没有该删的则返回 `null`。
     *
     * @param latest 当前已写到的片号(调用方在**新建下一片之前**问)。
     * @param keep 保留片数。
     * @return 该删的片号 —— 即「新片写完之后会超出保留范围」的那一片。
     */
    fun partToDrop(latest: Int, keep: Int): Int? {
        if (keep < 1) return null
        // 新片的编号是 latest + 1;写完之后保留范围是 [latest+1-keep+1, latest+1]。
        val lowestToKeep = oldestKept(latest + 1, keep)
        // 比它更小的就都该删了。一次轮转只可能多出一片,故取那一片。
        val candidate = lowestToKeep - 1
        return if (candidate >= 1 && candidate <= latest) candidate else null
    }
}
