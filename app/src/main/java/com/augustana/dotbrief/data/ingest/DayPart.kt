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
 * ## 判据为什么是 18 点
 *
 * 不按日出日落算，是为了让这条规则**单看时间就能读懂**（也便于测试）。
 * 而 18 这个点是 [SpokenTime] 那边逼出来的：**它必须和"晚上"的起点一致**。
 * 早先取的是 17 点，后果是 17:10 的播报开场说"下午好"（问候语按 18 点分档），
 * 天气段却写着"现在是晚上，天气说明天的" —— 同一段话里下午和晚上打架，
 * 主人听到的正是"上下午晚上说串了"。
 *
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
        /**
         * 18 点起算入夜。
         *
         * ⚠️ 动这个值之前先看 [SpokenTime.period] 与 [SpokenTime.greetingFor] ——
         * 三处必须是同一条线，否则就会出现"开场说下午好、天气段说现在是晚上"。
         */
        private const val EVENING_FROM_HOUR = 18

        fun of(hour: Int): DayPart = if (hour >= EVENING_FROM_HOUR) TONIGHT else TODAY
    }
}
