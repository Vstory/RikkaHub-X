// 版本号审计(2026-09-11):versionCode 取时间基数,versionName 追加构建日期与提交哈希。
//
// 守的是**静默回归**:该定制靠「在 defaultConfig 里于上游赋值之后再次赋值」生效,
// 一旦同步上游时被顺手改回,构建照样成功、APK 照样能装,只是所有构建同号、
// 关于页也看不出产物来自哪次提交 —— 现场不会有任何报错。故用断言把不变式钉住:
//   ① versionCode 是时间基数,而不是上游的三位小整数;
//   ② versionName 带 <yyMMdd>.<短哈希>;
//   ③ 追加的构建元数据不改变版本优先级(这正是选用 '+' 而非 '-' 的理由)。
package me.rerere.rikkahub.x.version

import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.utils.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XVersionAuditTest {

    /** ① versionCode 应为 2020-01-01 起的秒数。 */
    @Test
    fun `version code is time based rather than upstream small integer`() {
        val code = BuildConfig.VERSION_CODE.toLongOrNull()
        assertTrue("VERSION_CODE 不是纯数字:${BuildConfig.VERSION_CODE}", code != null)
        // 下界取 2026-01-01(1_767_225_600):上游编号是三位数,任何时间基数都远大于它。
        // 只设下界不设上界之外的限制,故此断言不会随时间失效。
        assertTrue(
            "VERSION_CODE=$code 低于时间基数下界,疑似被改回上游编号",
            code!! >= 1_767_225_600L,
        )
        assertTrue(
            "VERSION_CODE=$code 超过 AGP/Play 上限 2100000000",
            code <= 2_100_000_000L,
        )
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
