package com.augustana.dotbrief.data.ingest

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
                        // 全天 + 像节日 → 交给节日通道（[upcomingFestivals]）去讲，
                        // 不在这里当成一项"安排"。
                        //
                        // 这一条是主人抓出来的：中秋连休那几天，日历里每天一个全天的"中秋节"，
                        // 于是简报里既有"今天是中秋节"，又有一条"全天 中秋节"的安排 ——
                        // 同一件事被当成两回事说，而且"全天×节日"这个说法本身就是把
                        // 节假日订阅的存储方式念给了用户听。
                        if (allDay && FestivalMerge.looksLikeFestival(title)) continue

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
     * 取从现在起 [days] 天内会过到的「节日假期」。
     *
     * ## 为什么单开一个方法、而不是并进 [upcoming]
     *
     * [upcoming] 查的是"未来几小时"，而节日（中秋、国庆……）往往在**几天之后**，
     * 窗口对不上；而且节日要的是"哪几天放假"这种**假期**口径，日程要的是"几点开始"，
     * 两种语义不该挤在一条查询里。
     *
     * ## 节日在系统日历里长什么样，以及为什么要合并
     *
     * 手机日历里显示的节日多半来自厂商预置的"中国节假日"订阅，它们是**全天事件**，
     * 标题就是节日名。而且**一天一行**：中秋连休三天就是三行，标题还都带"中秋" ——
     * 照着念就是"今天是中秋节，明天是中秋节，后天也是中秋节"（主人抓到的原话）。
     *
     * 所以这里只负责把行读出来，"合并成一次假期"的活交给 [FestivalMerge]：
     * 那段是纯计算，能单测，而"连续多天如何归并"的边界靠真机日历是试不全的。
     *
     * ⚠️ 查询窗口**往前多取** [LOOKBACK_DAYS] 天：今天正在一个连休中间时，只看往后
     * 会把假期前几天切掉，算出来的"假期从今天开始"就是假的。往前取够，
     * [FestivalMerge] 才能看到完整的假期，再由它把已经放完的滤掉。
     *
     * ## 会不会把"出差""休假"误判成节日
     *
     * 不会。判据是**白名单**（见 [FestivalMerge.looksLikeFestival]）：归一化之后必须
     * 正好是某个节日名，或者"节日名 + 末尾一个'节'"。"出差""年假"不在名单里；
     * "中秋家庭聚会""中秋值班表"这类虽然含"中秋"，归一化后也不是名单里的名字。
     * 名单还能挡住"音乐节""中秋聚餐"这种含着疑似字样的私事。
     * 识别刻意偏保守：漏一个不常见的节日，好过把私事念成节日 ——
     * 后者不只是说错一句话，还会连带把用户自己的全天日程从"安排"里摘掉。
     */
    fun upcomingFestivals(days: Int, lookbackDays: Int = LOOKBACK_DAYS, limit: Int = 3): List<Festival> {
        if (!hasPermission()) return emptyList()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val begin = today.minusDays(lookbackDays.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

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
                    // ⚠️ 这里**不能**用 `size < limit` 提前退出：合并前的一行不等于一条假期，
                    // 提前截断会把一个连休切成两段。读满窗口（最多几十行）交给合并去挑。
                    while (cursor.moveToNext()) {
                        if (cursor.getInt(allDayIndex) != 1) continue
                        val title = cursor.getString(titleIndex)?.trim()?.takeIf { it.isNotBlank() }
                            ?: continue

                        val startMs = cursor.getLong(beginIndex)
                        val date = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(startMs),
                            zone,
                        ).toLocalDate()

                        add(FestivalMerge.Row(title = title, epochDay = date.toEpochDay()))
                    }
                }
            }
        }.getOrNull().orEmpty()

        return FestivalMerge.merge(rows, today.toEpochDay(), limit)
    }

    companion object {
        /**
         * 节日查询往前多看几天。
         *
         * 为的是把"跨在今天的连休"完整读出来：国内最长的一次连休是国庆接中秋的 8 天，
         * 取 10 天足够覆盖，多读几行没有成本（合并时会滤掉放完的）。
         */
        const val LOOKBACK_DAYS = 10
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
