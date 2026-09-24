package com.augustana.dotbrief.data.ingest

/**
 * 这次开口是"今天"还是"入夜"。
 *
 * ## 为什么需要它
 *
 * 天气是唯一一条**只跟时刻有关、跟数据无关**的信息：同一个"26 度、多云"，
 * 早上说"今天 22 到 31 度"有用，晚上再说一遍就是废话 —— 那时候人关心的是明天穿什么。
 * 实测被用户抓出来的原话是"晚上还说天气防晒什么的"。
 *
 * ## 判据为什么是 17 点
 *
 * 不按日出日落算，是为了让这条规则**单看时间就能读懂**（也便于测试）：
 * 夏冬的差别（18 点天还亮着）影响很小，而"入夜就说明天"在体感上是对的。
 * 另外，凌晨 0–4 点**仍算今天** —— 那是今天的前半段，太阳几小时后才升起来，
 * 这时候说"明天"会直接指错一天。
 *
 * ## 谁读它
 *
 * 两处消费者都必须照它分流：[com.augustana.dotbrief.data.llm.PromptBuilder]
 * （交给模型的素材）和 [com.augustana.dotbrief.domain.LocalBriefComposer]（没模型时直接念）。
 * 只有一处读就够了吗？不够 —— 同一个时刻、两种链路说出两套天气
 * （一边"记得防晒"、一边"明天要下雨"）是用户最容易察觉的那种不一致。
 */
enum class DayPart {
    /** 白天与凌晨：天气说今天。 */
    TODAY,

    /** 入夜：今天已经过去，天气说明天。 */
    TONIGHT;

    companion object {
        /** 17 点起算入夜。 */
        private const val EVENING_FROM_HOUR = 17

        fun of(hour: Int): DayPart = if (hour >= EVENING_FROM_HOUR) TONIGHT else TODAY
    }
}
