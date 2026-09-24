package com.augustana.dotbrief.data.ingest

import java.time.LocalDate

/**
 * 农历（阴历）换算与月相推算。
 *
 * ## 为什么系统日历读不到，却要自己算
 *
 * 手机日历 App 里能看到的"农历八月十五""月相图标"，都是**厂商日历 App 内置**的
 * 数据，不是 [android.provider.CalendarContract] 的字段 —— 第三方 App 通过日历提供器
 * 拿不到任何"农历"或"月相"的信息（它只有 title/start/end 这些通用字段）。
 *
 * 所以这里用**纯算法**自己算：零网络、零权限、零第三方依赖。算法是查表法，
 * 一张 1900–2099 年的农历数据表（compact 编码），覆盖常用范围。
 *
 * ## 数据表编码（务必先看懂再改）
 *
 * 每年一个整数 `info`：
 * - `info % 16` = 闰月月份（0 = 无闰月）；
 * - 普通第 m 月的天数在**高位**：`(info >> (16 - m)) % 2`，1 = 30 天、0 = 29 天，
 *   即 bit 16 是正月、bit 5 是腊月（高位往低位走）；
 * - 闰月天数在 bit 16：`(info >> 16) % 2`。
 *
 * 这张表与业界通行的 `lunar`/`lunardate` 库同源（紫金山天文台历算组公布的农历表），
 * 在 1900–2099 范围内与权威历书一致。
 *
 * ## 月相怎么推
 *
 * 月相由"月龄"决定 —— 距离上一个朔日（新月、农历初一）过去的天数。
 * 一个朔望月约 29.53 天，于是初一新月、初八上弦、十五满月、廿三下弦。
 * 这里**只做播报级近似**，够"今晚月亮圆不圆"这种一句带过的用途，
 * 不追求天文台精确分秒（那需要月球轨道参数，不值得）。
 */
object LunarCalendar {

    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2099

    /** 1900 年正月初一，作为查表的历元基准。 */
    private val BASE_EPOCH_DAY: Long = LocalDate.of(1900, 1, 31).toEpochDay()

    /**
     * 公历日期 -> 农历，超出 1900–2099 返回 `null`（不瞎编）。
     */
    fun fromSolar(date: LocalDate): LunarDate? {
        if (date.year < MIN_YEAR || date.year > MAX_YEAR) return null

        var offset = date.toEpochDay() - BASE_EPOCH_DAY
        if (offset < 0) return null

        // 逐年减：找到落在哪一年。
        var year = MIN_YEAR
        while (year < MAX_YEAR) {
            val days = lunarYearDays(year)
            if (offset < days) break
            offset -= days
            year++
        }
        if (year > MAX_YEAR) return null

        val info = LUNAR_INFO[year - MIN_YEAR]
        val leapMonth = info % 16

        // 逐月走：先普通月，若该月是闰月再走闰月。
        var month = 1
        while (month <= 12) {
            val normalDays = monthDays(info, month)
            if (offset < normalDays) {
                return LunarDate(lunarYear = year, month = month, day = offset.toInt() + 1, leap = false)
            }
            offset -= normalDays

            if (leapMonth == month) {
                val leapDays = leapMonthDays(info)
                if (offset < leapDays) {
                    return LunarDate(lunarYear = year, month = month, day = offset.toInt() + 1, leap = true)
                }
                offset -= leapDays
            }
            month++
        }
        return null // 理论到不了：offset 已被该年总天数约束
    }

    /** 当月月相（播报级），见 [MoonPhase]。算不出来返回 [MoonPhase.UNKNOWN]。 */
    fun moonPhase(date: LocalDate): MoonPhase {
        val lunar = fromSolar(date) ?: return MoonPhase.UNKNOWN
        // 月龄 = 农历日 - 1（初一即新月，月龄 0）。
        return MoonPhase.fromAge(lunar.day - 1)
    }

    // ------------------------------------------------------------------
    // 内部：位运算查表
    // ------------------------------------------------------------------

    /** 第 [month]（1..12）普通月的天数。 */
    private fun monthDays(info: Int, month: Int): Int =
        ((info shr (16 - month)) and 1) + 29

    /** 闰月的天数（无闰月时调用方不该走到这里）。 */
    private fun leapMonthDays(info: Int): Int =
        ((info shr 16) and 1) + 29

    /** 某农历年的总天数（含闰月）。 */
    private fun lunarYearDays(year: Int): Int {
        val info = LUNAR_INFO[year - MIN_YEAR]
        val leapMonth = info % 16
        var sum = 0
        for (m in 1..12) sum += monthDays(info, m)
        if (leapMonth > 0) sum += leapMonthDays(info)
        return sum
    }

    /** 1900–2099 农历数据表，编码见类注释。 */
    private val LUNAR_INFO = intArrayOf(
        0x4bd8, 0x4ae0, 0xa570, 0x54d5, 0xd260, 0xd950, 0x16554, 0x56a0, 0x9ad0, 0x55d2,
        0x4ae0, 0xa5b6, 0xa4d0, 0xd250, 0x1d255, 0xb540, 0xd6a0, 0xada2, 0x95b0, 0x14977,
        0x4970, 0xa4b0, 0xb4b5, 0x6a50, 0x6d40, 0x1ab54, 0x2b60, 0x9570, 0x52f2, 0x4970,
        0x6566, 0xd4a0, 0xea50, 0x6e95, 0x5ad0, 0x2b60, 0x186e3, 0x92e0, 0x1c8d7, 0xc950,
        0xd4a0, 0x1d8a6, 0xb550, 0x56a0, 0x1a5b4, 0x25d0, 0x92d0, 0xd2b2, 0xa950, 0xb557,
        0x6ca0, 0xb550, 0x15355, 0x4da0, 0xa5d0, 0x14573, 0x52b0, 0xa9a8, 0xe950, 0x6aa0,
        0xaea6, 0xab50, 0x4b60, 0xaae4, 0xa570, 0x5260, 0xf263, 0xd950, 0x5b57, 0x56a0,
        0x96d0, 0x4dd5, 0x4ad0, 0xa4d0, 0xd4d4, 0xd250, 0xd558, 0xb540, 0xb5a0, 0x195a6,
        0x95b0, 0x49b0, 0xa974, 0xa4b0, 0xb27a, 0x6a50, 0x6d40, 0xaf46, 0xab60, 0x9570,
        0x4af5, 0x4970, 0x64b0, 0x74a3, 0xea50, 0x6b58, 0x5ac0, 0xab60, 0x96d5, 0x92e0,
        0xc960, 0xd954, 0xd4a0, 0xda50, 0x7552, 0x56a0, 0xabb7, 0x25d0, 0x92d0, 0xcab5,
        0xa950, 0xb4a0, 0xbaa4, 0xad50, 0x55d9, 0x4ba0, 0xa5b0, 0x15176, 0x52b0, 0xa930,
        0x7954, 0x6aa0, 0xad50, 0x5b52, 0x4b60, 0xa6e6, 0xa4e0, 0xd260, 0xea65, 0xd530,
        0x5aa0, 0x76a3, 0x96d0, 0x4afb, 0x4ad0, 0xa4d0, 0x1d0b6, 0xd250, 0xd520, 0xdd45,
        0xb5a0, 0x56d0, 0x55b2, 0x49b0, 0xa577, 0xa4b0, 0xaa50, 0x1b255, 0x6d20, 0xada0,
        0x14b63, 0x9370, 0x49f8, 0x4970, 0x64b0, 0x168a6, 0xea50, 0x6aa0, 0x1a6c4, 0xaae0,
        0x92e0, 0xd2e3, 0xc960, 0xd557, 0xd4a0, 0xda50, 0x5d55, 0x56a0, 0xa6d0, 0x55d4,
        0x52d0, 0xa9b8, 0xa950, 0xb4a0, 0xb6a6, 0xad50, 0x55a0, 0xaba4, 0xa5b0, 0x52b0,
        0xb273, 0x6930, 0x7337, 0x6aa0, 0xad50, 0x14b55, 0x4b60, 0xa570, 0x54e4, 0xd160,
        0xe968, 0xd520, 0xdaa0, 0x16aa6, 0x56d0, 0x4ae0, 0xa9d4, 0xa2d0, 0xd150, 0xf252,
    )
}

/** 一个农历日期。 */
data class LunarDate(
    val lunarYear: Int,
    /** 1..12。 */
    val month: Int,
    /** 1..30。 */
    val day: Int,
    /** 是不是闰月。 */
    val leap: Boolean,
) {
    /** "八月十五" 这种口语表达（闰月加"闰"字）。 */
    val spoken: String
        get() {
            val monthName = LUNAR_MONTH_NAMES[month - 1]
            val dayName = LUNAR_DAY_NAMES[day - 1]
            return (if (leap) "闰" else "") + monthName + dayName
        }

    private companion object {
        val LUNAR_MONTH_NAMES = arrayOf(
            "正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月",
        )
        val LUNAR_DAY_NAMES = arrayOf(
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
        )
    }
}

/** 月相（播报级近似）。 */
enum class MoonPhase(val label: String) {
    /** 初一前后，几乎不可见。 */
    NEW("新月"),

    /** 初二～初六。 */
    WAXING_CRESCENT("蛾眉月"),

    /** 初七～初九。 */
    FIRST_QUARTER("上弦月"),

    /** 初十～十四。 */
    WAXING_GIBBOUS("盈凸月"),

    /** 十五～十七。 */
    FULL("满月"),

    /** 十八～廿一。 */
    WANING_GIBBOUS("亏凸月"),

    /** 廿二～廿四。 */
    LAST_QUARTER("下弦月"),

    /** 廿五～月底。 */
    WANING_CRESCENT("残月"),

    /** 算不出来（超出可查范围）。 */
    UNKNOWN(""),

    ;

    companion object {
        /** 由月龄（0 = 初一）映射到月相档位。 */
        fun fromAge(age: Int): MoonPhase = when (age) {
            0 -> NEW
            in 1..5 -> WAXING_CRESCENT
            in 6..8 -> FIRST_QUARTER
            in 9..13 -> WAXING_GIBBOUS
            in 14..16 -> FULL
            in 17..20 -> WANING_GIBBOUS
            in 21..23 -> LAST_QUARTER
            else -> WANING_CRESCENT
        }
    }
}
