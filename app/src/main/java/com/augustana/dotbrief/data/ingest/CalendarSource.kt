package com.augustana.dotbrief.data.ingest

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 命中任一关键字且为全天事件，才判定为「节日」（见 [CalendarSource.upcomingFestivals]）。 */
private val FESTIVAL_KEYWORDS = listOf(
    "节", "新年", "元旦", "除夕", "春节", "端午", "中秋", "清明", "国庆",
    "圣诞", "元宵", "重阳", "七夕", "腊八", "情人节", "儿童节", "妇女节", "劳动节",
)

/**
 * 日程取数：读系统日历（CalendarContract.Instances）。
 *
 * 用 Instances 而不是 Events 的原因：Instances 已经把循环日程展开成"每一次具体发生"，
 * 不用自己算 recurrence，也不会漏掉本周重复的例会。
 *
 * 时间表达在**这里**转成口语（"今天上午9点30分"），不交给模型换算，
 * 因为模型算日期经常错位一天，而这件事用代码做是零成本的。
 */
class CalendarSource(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 取从现在起 [hours] 小时内的日程。
     * 没有权限 / 查询失败时返回空表，由上层归纳成 warning，绝不抛给用户。
     */
    fun upcoming(hours: Int, limit: Int = 8): List<AgendaEvent> {
        if (!hasPermission()) return emptyList()

        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val begin = now.toEpochMilli()
        val end = now.plus(hours.toLong(), ChronoUnit.HOURS).toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )

        val rows = runCatching {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                CalendarContract.Instances.BEGIN + " ASC",
            )?.use { cursor ->
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)

                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        val startMs = cursor.getLong(beginIndex)
                        // 已经结束的跳过
                        if (cursor.getLong(endIndex) < begin) continue

                        val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() }
                            ?: continue
                        val allDay = cursor.getInt(allDayIndex) == 1

                        add(
                            AgendaEvent(
                                title = title,
                                atText = speakableTime(startMs, zone, allDay),
                                minutesFromNow =
                                    ((startMs - begin) / 60_000L).toInt(),
                                location = cursor.getString(locationIndex).orEmpty(),
                                allDay = allDay,
                            ),
                        )
                    }
                }
            }
        }.getOrNull().orEmpty()

        return rows
    }

    /**
     * 取从现在起到未来 [days] 天内的「节日」。
     *
     * ## 为什么单开一个方法、而不是并进 [upcoming]
     *
     * [upcoming] 查的是"未来几小时"，而节日（中秋、国庆……）往往在**几天之后**，
     * 窗口对不上；而且节日要的是"还有几天"这种**倒计时**口径，日程要的是"几点开始"，
     * 两种语义不该挤在一条查询里。
     *
     * ## 节日在系统日历里长什么样
     *
     * 手机日历里显示的节日，多半来自厂商预置的"中国节假日"订阅，它们是**全天事件**，
     * 标题就是节日名（"中秋节""国庆节"）。所以这里的判据是：
     * 全天 + 标题命中 [FESTIVAL_KEYWORDS]。
     *
     * ## 会不会把"出差""休假"误判成节日
     *
     * 不会：那些全天日程的标题不含"节/新年/圣诞"这类词。而"国庆节""中秋节"这种
     * 命中关键词的，几乎只可能是真节日。识别偏保守（漏一个不常见的节日 > 把出差念成节日）。
     */
    fun upcomingFestivals(days: Int, limit: Int = 3): List<Festival> {
        if (!hasPermission()) return emptyList()

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val begin = now.atZone(zone).toInstant().toEpochMilli()
        val end = now.plusDays(days.toLong()).atZone(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
        )

        val rows = runCatching {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                CalendarContract.Instances.BEGIN + " ASC",
            )?.use { cursor ->
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)

                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        if (cursor.getInt(allDayIndex) != 1) continue
                        val title = cursor.getString(titleIndex)?.trim()?.takeIf { it.isNotBlank() }
                            ?: continue
                        if (FESTIVAL_KEYWORDS.none { it in title }) continue

                        val startMs = cursor.getLong(beginIndex)
                        val date = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(startMs),
                            zone,
                        ).toLocalDate()
                        val daysFromNow = (date.toEpochDay() - now.toLocalDate().toEpochDay()).toInt()
                        // 已经过去的（今天之前的）不算"还有几天"
                        if (daysFromNow < 0) continue

                        add(
                            Festival(
                                title = title,
                                daysFromNow = daysFromNow,
                            ),
                        )
                    }
                }
            }
        }.getOrNull().orEmpty()

        return rows
    }

    private fun speakableTime(epochMillis: Long, zone: ZoneId, allDay: Boolean): String {
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        if (allDay) return "全天"

        val today = LocalDateTime.now(zone).toLocalDate()
        val dayText = when (dateTime.toLocalDate()) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            today.minusDays(1) -> "昨天"
            else -> "${dateTime.monthValue}月${dateTime.dayOfMonth}日"
        }

        // 时间口语化（12 小时制、整点不报分、不足 10 分补"零"）统一在 [SpokenTime]：
        // 原先这里、天气取数、简报开场三处各写了一遍，改一处就会漏两处。
        return "$dayText${SpokenTime.clock(dateTime.hour, dateTime.minute)}"
    }
}
