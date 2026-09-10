// [X-custom] RikkaHub-X 定制(merge 上游时保留): 容量表时间显示格式化
// .x 独立新文件,上游无此文件,merge 零冲突。
//
// 设置页要显示**两个**时间,都精确到秒:
//   ① 数据源时间 —— 表内 `updatedAt` 声明的数据核验时刻;
//   ② 本地更新时间 —— 本机最后一次成功拉取并落盘的时刻。
// 时区可注入(默认本机时区),便于单测固定时区做确定性断言。
package me.rerere.rikkahub.x.context

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 显示格式:`年-月-日 时:分:秒`。 */
private val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * 把表内的 `updatedAt`(ISO-8601,带时区偏移,如 `2026-09-10T23:30:30+08:00`)转成
 * 指定时区的 `yyyy-MM-dd HH:mm:ss`。
 *
 * 解析失败返回 null —— 调用方应回退显示原始字符串,而不是隐藏该行:时间是给人判断数据新旧用的,
 * 显示得难看也远好过不显示。
 */
fun formatTableUpdatedAt(raw: String, zone: ZoneId = ZoneId.systemDefault()): String? = runCatching {
    OffsetDateTime.parse(raw).atZoneSameInstant(zone).format(DISPLAY_FORMAT)
}.getOrNull()

/**
 * 解析表内 `updatedAt` 为**时刻**,用于跨源比较哪一版更新。
 *
 * 兼容两种格式:
 * - `2026-09-10T23:30:30+08:00`(现行,带秒与时区偏移);
 * - `2026-09-10`(旧格式,只到日)—— 按**当日零点**计。
 *
 * 旧格式必须能解析:部分 CDN 缓存仍在供旧格式的表,若解析不了就会被判成"最旧",
 * 从而误把旧表当新版覆盖下去 —— 能解析才比较得准。
 *
 * 无法解析返回 null(调用方视为最旧)。
 */
fun parseTableUpdatedAt(raw: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
    runCatching { return OffsetDateTime.parse(raw).toInstant() }
    return runCatching { LocalDate.parse(raw).atStartOfDay(zone).toInstant() }.getOrNull()
}

/** 把本机时间戳(毫秒)转成指定时区的 `yyyy-MM-dd HH:mm:ss`。 */
fun formatRefreshedAt(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(DISPLAY_FORMAT)
