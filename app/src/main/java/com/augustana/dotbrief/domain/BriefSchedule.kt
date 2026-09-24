package com.augustana.dotbrief.domain

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 「什么时候刷新内容」与「点一下要不要重新问模型」这两件事的**唯一**判据。
 *
 * ## 为什么把两件事收在一处
 *
 * 它们看着是两个功能（后台定时刷新、点击时的缓存命中），实际是同一个事实的两面：
 * **内容归属于"最近一个已过的更新时刻"那一段**。定了这条，两边的行为就自动一致 ——
 * 定时任务在 10:00 生成，用户在 11:30 点开听到的就是 10:00 那一份；
 * 而 09:00 点开时，因为"今天还没有任何时刻到过"，它会自己生成一份新的
 * （否则要念昨天晚上七点的内容，那已经不是早上该听的东西了）。
 *
 * 分成两处各写一遍判定，就会出现"后台照 10/14/19 刷、点击却按别的口径判新鲜"
 * 这种对不上的问题 —— 表现是用户偶尔听到明显过时的内容，且完全无法解释为什么。
 *
 * ## 判据
 *
 * ```
 * 时刻锚点 slotStart(now) = 今天所有 <= now 的时刻里最大的那个
 *                          ; 今天一个都没到过 -> 今天 00:00
 * 缓存新鲜               = 缓存生成时刻 >= slotStart(now)
 * ```
 *
 * 那条"下限取今天 00:00"是跨天失效的全部实现：昨天的内容在新的一天必然不新鲜，
 * 不需要任何人在零点清什么（与 `WidgetRuntimeState.lastGreetedDay` 同一个套路）。
 *
 * ## 为什么不是布尔标志
 *
 * 同 `BriefFreshness` 的理由：缓存"新不新"是**算出来**的比较结果，
 * 存成标志就要在"生成成功 / 用户改时刻 / 跨天"三处维护，漏一处就静默不一致。
 */
object BriefSchedule {

    /**
     * 当前内容归属的时刻锚点：今天已过的最后一个更新时刻；今天还没到过任何时刻，
     * 就退回今天 00:00。
     *
     * 退回 00:00 而不是"昨天最后一个时刻"，是为了让早晨第一次点击**一定会取新内容**：
     * 昨晚七点的新闻拿到今早念，天气也早就不对了。代价是早晨第一次点会多一次模型请求，
     * 这是值得的 —— 那种"内容明显是昨天的"才是真正让人不信任的东西。
     */
    fun slotStart(now: ZonedDateTime, times: List<LocalTime>): ZonedDateTime {
        val startOfDay = now.toLocalDate().atStartOfDay(now.zone)
        return times
            .map { now.toLocalDate().atTime(it).atZone(now.zone) }
            .filter { !it.isAfter(now) }
            .maxOrNull()
            ?: startOfDay
    }

    /**
     * 这份缓存现在还算不算新鲜 —— 也就是"点一下能不能直接念，不用再问模型"。
     *
     * [cachedAtEpochSeconds] 为 0（从没生成过）一律判不新鲜。
     */
    fun isCacheFresh(
        cachedAtEpochSeconds: Long,
        now: ZonedDateTime,
        times: List<LocalTime>,
    ): Boolean {
        if (cachedAtEpochSeconds <= 0L) return false
        return cachedAtEpochSeconds >= slotStart(now, times).toEpochSecond()
    }

    /**
     * 下一次该刷新内容的时刻。
     *
     * 今天的还没过完就用今天的，否则顺延到明天最早的那个。列表为空返回 null
     * （= 没有自动更新这回事，调用方据此取消任务）。
     *
     * 严格取"晚于 now"的那一个：正好卡在时刻上时不该立刻再刷一次。
     */
    fun nextSlot(now: ZonedDateTime, times: List<LocalTime>): ZonedDateTime? {
        if (times.isEmpty()) return null
        val today = times.map { now.toLocalDate().atTime(it).atZone(now.zone) }
        return today.firstOrNull { it.isAfter(now) }
            ?: times.min().let { now.toLocalDate().plusDays(1).atTime(it).atZone(now.zone) }
    }

    /** `09:30` 这种显示形式。存进 DataStore 用的也是它，见 `SettingsCodec.encodeTimes`。 */
    fun format(time: LocalTime): String {
        val hour = if (time.hour < 10) "0${time.hour}" else time.hour.toString()
        val minute = if (time.minute < 10) "0${time.minute}" else time.minute.toString()
        return "$hour:$minute"
    }

    /**
     * 把 `"9:30"` / `"09:30"` 解析成时刻，认不出来返回 null。
     *
     * 解析与格式化都放在这里（而不是各写一份在存储层和界面层）：
     * 用户输入、配置存储、界面显示走的是同一个格式，三处各写一遍就迟早对不上 ——
     * 典型症状是"存进去是 9:30、显示成 09:30、再解析失败"。
     */
    fun parse(raw: String): LocalTime? {
        val parts = raw.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }
}
