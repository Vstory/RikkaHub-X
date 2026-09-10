// [X-custom] RikkaHub-X 定制(merge 上游时保留): 手动刷新未取到新内容后的自动重试
// .x 独立新文件(me.rerere.rikkahub.x.context),上游无此文件,merge 零冲突。
//
// 为什么需要它:数据源是 CDN,推完之后各源刷新不同步,主源可能仍返回上一版(HTTP 仍是 200)。
// 用户点「立即更新」时若恰好撞上这个窗口,拿到的还是旧数据 —— 而此时**再等几分钟就好了**。
// 让人守在设置页反复点按钮是没道理的,故:记下点击时刻,之后自动重试几次。
//
// 重试用完即止(不无限重试),回落到按表内 TTL 的定时刷新 —— 重试是"给用户那次点击一个交代",
// 不是常驻轮询;常态的新鲜度由 TTL 负责。
package me.rerere.rikkahub.x.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 自动重试的间隔(分钟)。第 N 次重试发生在点击后 N × 该值,故三次分别在 5 / 10 / 15 分钟。 */
const val RETRY_INTERVAL_MINUTES: Long = 5

/** 手动刷新未取到新内容后,最多自动重试几次。用完回落定时刷新。 */
const val MAX_RETRY_ATTEMPTS: Int = 3

/**
 * 重试链的整体时限(分钟)。
 *
 * 防的是**陈旧链条**:进程被杀后隔几小时重启,不该把几小时前那次点击的链子接着跑完 ——
 * 那时的数据状况早已变了,让 TTL 决定更合适。
 */
const val RETRY_WINDOW_MINUTES: Long = 30

/**
 * 一条重试链的状态。
 *
 * 持久化到磁盘而不只放内存:用户点完按钮往往就切走了,若状态随进程消失,重试就形同虚设。
 * 落盘后即便 App 被杀,下次启动只要还在 [RETRY_WINDOW_MINUTES] 内就接着跑。
 */
@Serializable
data class RefreshRetryPlan(
    /** 用户点击「立即更新」的时刻(毫秒),也是整条链的计时基准。 */
    val requestedAt: Long,
    /** 已用完的自动重试次数。 */
    val attemptsDone: Int = 0,
) {
    /** 下一次重试的时刻。 */
    val nextAttemptAt: Long
        get() = requestedAt + RETRY_INTERVAL_MINUTES * 60_000L * (attemptsDone + 1)

    /** 供界面显示的序号(从 1 起),即"第几次"。 */
    val attemptNumber: Int get() = attemptsDone + 1

    /** 还有重试额度。 */
    val hasAttemptsLeft: Boolean get() = attemptsDone < MAX_RETRY_ATTEMPTS

    /**
     * 额度已用尽 —— 此后交给按 TTL 的自动刷新,不再由本链负责。
     *
     * 与 [shouldContinue] 的区别很重要:用尽的链条不该再重试,但**仍要显示**出来,
     * 好让界面把"已转交自动刷新"这句话说清楚。故两者必须是不同的判断。
     */
    val isExhausted: Boolean get() = !hasAttemptsLeft

    /**
     * 这条链此刻是否**仍在活动**(还有额度且在时限内)。
     *
     * 自动刷新据此决定要不要让路 —— 让给一条已用尽的链,会让自动刷新被永久堵死。
     */
    fun isActive(now: Long): Boolean = shouldContinue(now)

    /** 是否仍在整体时限内。 */
    fun withinWindow(now: Long): Boolean = now <= requestedAt + RETRY_WINDOW_MINUTES * 60_000L

    /** 此刻是否还应继续这条链。 */
    fun shouldContinue(now: Long): Boolean = hasAttemptsLeft && withinWindow(now)

    /** 记一次失败,推进到下一档间隔。 */
    fun afterFailure(): RefreshRetryPlan = copy(attemptsDone = attemptsDone + 1)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 以"此刻点击"开一条新链。 */
        fun newRequest(now: Long): RefreshRetryPlan = RefreshRetryPlan(requestedAt = now)

        /** 解码;内容损坏或格式不符一律返回 null(重试是尽力而为,读不出就当没有)。 */
        fun decode(text: String): RefreshRetryPlan? =
            runCatching { json.decodeFromString<RefreshRetryPlan>(text) }.getOrNull()

        fun encode(plan: RefreshRetryPlan): String = json.encodeToString(plan)
    }
}
