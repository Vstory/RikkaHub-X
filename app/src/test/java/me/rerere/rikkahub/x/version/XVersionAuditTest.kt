// 版本号审计(2026-09-11):versionCode 取时间基数,versionName 追加构建日期与提交哈希。
//
// 守的是**静默回归**:该定制靠「在 defaultConfig 里于上游赋值之后再次赋值」生效,
// 一旦同步上游时被顺手改回,构建照样成功、APK 照样能装,只是所有构建同号、
// 关于页也看不出产物来自哪次提交 —— 现场不会有任何报错。故用断言把不变式钉住:
//   ① versionCode 编码的是**构建时刻**,而不是上游的三位小整数;
//   ② versionName 带 <yyMMdd>.<短哈希>;
//   ③ 追加的构建元数据不改变版本优先级(这正是选用 '+' 而非 '-' 的理由)。
package me.rerere.rikkahub.x.version

import java.time.Instant
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.utils.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XVersionAuditTest {

    /** 与 app/build.gradle.kts 的实现一致:versionCode = 构建时刻 − 本基准。 */
    private val baseEpochSecond = 1_577_836_800L // 2020-01-01T00:00:00Z

    /** AGP / Google Play 允许的上限。 */
    private val maxVersionCode = 2_100_000_000L

    /**
     * ① versionCode 应编码构建时刻。
     *
     * 断言的是「VERSION_CODE + 基准 ≈ 现在」,而非拿某个魔数当下界 —— 后者极易写错:
     * 本用例初版即把下界写成 2026-01-01 的**绝对** epoch(1_767_225_600),而 versionCode
     * 是**减去** 2020 基准后的值(2026 年约 2.1e8),两者差一个数量级 → 断言恒假,
     * 在 CI 上必然变红。用时间窗口既不会因年份推移失效,也照样能抓住被改回上游编号
     * (上游 186 → 编码成 2020-01-01,离窗口十万八千里)。
     */
    @Test
    fun `version code encodes build time rather than upstream counter`() {
        val code = BuildConfig.VERSION_CODE.toLongOrNull()
        assertTrue("VERSION_CODE 不是纯数字:${BuildConfig.VERSION_CODE}", code != null)

        val encodedInstant = code!! + baseEpochSecond
        val nowEpochSecond = System.currentTimeMillis() / 1000
        // 构建完成到测试运行之间的耗时(CI 排队 + 编译实测数分钟),放宽到 2 天
        val toleranceSecond = 2L * 24 * 60 * 60

        assertTrue(
            "VERSION_CODE=$code 编码的时刻 ${Instant.ofEpochSecond(encodedInstant)} " +
                "不在本次构建附近(当前 ${Instant.ofEpochSecond(nowEpochSecond)})," +
                "疑似被改回上游编号",
            encodedInstant in (nowEpochSecond - toleranceSecond)..(nowEpochSecond + toleranceSecond),
        )
        assertTrue("VERSION_CODE=$code 超过上限 $maxVersionCode", code <= maxVersionCode)
    }

    /** ② versionName 应形如 `2.5.1+260911.87b34618`。 */
    @Test
    fun `version name carries build date and short commit hash`() {
        val name = BuildConfig.VERSION_NAME
        assertTrue(
            "VERSION_NAME=$name 不符合 <语义版本>+<yyMMdd>.<短哈希>",
            Regex("""^\d+\.\d+\.\d+\+\d{6}\.[0-9a-z]+$""").matches(name),
        )
    }

    /** ③ 构建元数据不参与比较 —— 否则追加信息会干扰更新检查的版本判定。 */
    @Test
    fun `appended build metadata does not change version comparison`() {
        val decorated = BuildConfig.VERSION_NAME
        val bare = decorated.substringBefore("+")
        assertEquals(
            "构建元数据不应改变版本优先级",
            0,
            Version(decorated).compareTo(Version(bare)),
        )
    }
}
