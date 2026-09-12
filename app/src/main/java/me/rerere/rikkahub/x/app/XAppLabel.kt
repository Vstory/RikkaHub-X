package me.rerere.rikkahub.x.app

import android.content.Context
import me.rerere.rikkahub.R

/**
 * [X-custom] 应用显示名的**单一来源**。
 *
 * 应用名按渠道取值(nightly 包追加后缀,见 `app/build.gradle.kts` 的 `x.channel`),
 * 而 `R.string.app_name` 只是**基础名**、不含渠道后缀。于是凡是「把应用名显示给用户」
 * 的地方都要经这里取系统实际生效的标签 —— 否则夜间包会出现
 * 「桌面图标叫 X Nightly,关于页却还叫 X」这类自相矛盾。
 *
 * 取值就是 `PackageManager` 里本应用的标签,与桌面图标、应用信息逐字一致。
 * 拿不到标签时回退 `R.string.app_name`(理论上不会发生:本包自己就是该应用)。
 *
 * 说明:这是 Android 框架查询、不是纯函数,故没有 JVM 单测 ——
 * 由产物核对(`aapt2 dump badging` 看 `application-label`)与真机确认。
 */
fun Context.xAppLabel(): CharSequence {
    val label = runCatching { packageManager.getApplicationLabel(applicationInfo) }.getOrNull()
    return label?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
}
