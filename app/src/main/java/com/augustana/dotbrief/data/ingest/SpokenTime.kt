package com.augustana.dotbrief.data.ingest

/**
 * 把时间转成"人话说出来的样子"。
 *
 * ## 为什么要收在一处
 *
 * 这段逻辑原先在三个地方各写了一遍 —— 日历取数、天气取数、简报开场，
 * 结果就是**三份实现悄悄长得不一样**：开场把 6~10 点叫"早上"，日程和日出日落
 * 叫"上午"；6:02 三处都念成"六点二分"（中文里该说"六点零二分"）。
 * 时间口语化是同一件事，规则就该只有一处，改的时候不必去找有几个副本。
 *
 * ## 为什么不交给模型
 *
 * 这是要**听**的文字。模型换算日期经常错位一天、把"下午三点"写成"15:00"，
 * 而用代码做是零成本的 —— 与 [CalendarSource] 里"时间表达不交给模型"同一个理由。
 */
object SpokenTime {

    /**
     * 时段前缀。
     *
     * 6 点前是"凌晨"（此时天没亮），12 点前后一个小时是"中午"，
     * 18 点起是"晚上" —— 覆盖到 24 点，"深夜11点"这种说法反而不如"晚上11点"顺口。
     */
    fun period(hour: Int): String = when {
        hour < 6 -> "凌晨"
        hour < 12 -> "上午"
        hour < 13 -> "中午"
        hour < 18 -> "下午"
        else -> "晚上"
    }

    /** 24 小时制 -> 12 小时制。0 点和 12 点都念"12 点"，不念"0 点"。 */
    fun hour12(hour: Int): Int = (hour % 12).let { if (it == 0) 12 else it }

    /**
     * 分钟部分。
     *
     * 整点不说分钟；不足 10 分要补一个"零"—— 6:02 念成"六点零二分"，
     * 少了那个"零"就成了"六点二分"，听着像漏了一个字。TTS 不会替我们补。
     */
    fun minutePart(minute: Int): String = when {
        minute == 0 -> ""
        minute < 10 -> "零${minute}分"
        else -> "${minute}分"
    }

    /** 15 -> "下午3点"；0 -> "凌晨12点"。 */
    fun hourOf(hour: Int): String = "${period(hour)}${hour12(hour)}点"

    /** (15, 5) -> "下午3点零5分"；(6, 0) -> "上午6点"。 */
    fun clock(hour: Int, minute: Int): String = "${hourOf(hour)}${minutePart(minute)}"
}
