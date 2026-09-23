package com.briefwidget.data.llm

import com.briefwidget.data.ingest.BriefInput
import com.briefwidget.data.ingest.WeatherInfo
import com.briefwidget.data.settings.Defaults
import com.briefwidget.data.settings.UserSettings

/**
 * 把「今天的数据」拼成模型能直接消化的用户消息。
 *
 * 三个原则：
 * 1. **运行时数据一律由代码算好**——口语化时间、距现在多少分钟、字数区间，
 *    全在这里或 [com.briefwidget.data.ingest.CalendarSource] 里算完，
 *    不让模型做算术（模型算日期经常错位一天）；
 * 2. **用平铺文本而不是 JSON**：小模型读 JSON 容易串行错位，
 *    而这里的数据量很小，平铺反而更稳、token 更少；
 * 3. **没数据的板块整段不出现**：模型看到一个空标题就会忍不住写"暂无信息"，
 *    而那正是系统提示词明令禁止的。
 */
object PromptBuilder {

    fun systemPrompt(settings: UserSettings): String =
        settings.llm.systemPrompt
            .replace(Defaults.PLACEHOLDER_MIN_CHARS, settings.brief.minChars.toString())
            .replace(Defaults.PLACEHOLDER_MAX_CHARS, settings.brief.maxChars.toString())

    fun userPrompt(input: BriefInput, settings: UserSettings): String = buildString {
        appendLine("现在是${input.nowText}。请根据下面的素材写今天的口语简报。")
        // 日期只在今天第一次说。素材里的"现在是…"本身就已经体现了这一点
        // （第二次之后那里没有日期），这里再给它一条**明确指令**：
        // 不写清楚的话，模型很爱自己按"今天"脑补出一个日期，而且经常差一天。
        appendLine(
            if (input.includeDate) {
                "（这是今天第一次播报：开场要把今天是几月几号、星期几说出来。）"
            } else {
                "（今天已经播报过了：开场不要再报日期和星期，也不要自己猜今天几号。）"
            },
        )
        appendLine()

        if (input.anniversaries.isNotEmpty()) {
            appendLine("【纪念日】（今天最有人情味的一件事：开场单独说一句，不要当成普通日程说）")
            input.anniversaries.forEach { appendLine("- 今天是${it.title}") }
            appendLine()
        }

        if (input.weather != null) {
            appendLine("【天气】")
            input.weather.promptLines().forEach { appendLine("- $it") }
            appendLine()
        }

        if (input.events.isNotEmpty()) {
            appendLine("【日程】")
            input.events.forEach { event ->
                val distance = when {
                    event.minutesFromNow < 0 -> "已开始"
                    event.minutesFromNow == 0 -> "马上开始"
                    event.minutesFromNow < 60 -> "${event.minutesFromNow}分钟后"
                    else -> "${event.minutesFromNow / 60}小时后"
                }
                val place = if (event.location.isBlank()) "" else "，地点${event.location}"
                appendLine("- ${event.title}｜${event.atText}｜$distance$place")
            }
            appendLine()
        }

        if (input.trips.isNotEmpty()) {
            appendLine("【行程通知】")
            input.trips.forEach { item ->
                appendLine("- 来源${item.appLabel}｜${item.text}${codeSuffix(item.code)}${timeSuffix(item.timeText)}")
            }
            appendLine()
        }

        if (input.packages.isNotEmpty()) {
            appendLine("【快递通知】")
            input.packages.forEach { item ->
                appendLine("- 来源${item.appLabel}｜${item.text}${codeSuffix(item.code)}")
            }
            appendLine()
        }

        if (input.news.isNotEmpty()) {
            appendLine("【今日快讯素材】（挑最重要的${settings.rss.newsCount}条来讲）")
            input.news.forEach { item ->
                val summary = if (item.summary.isBlank()) "" else "：${item.summary}"
                appendLine("- [${item.source}] ${item.title}$summary")
            }
            appendLine()
        }

        if (input.warnings.isNotEmpty()) {
            appendLine("【取数情况】（这些源这次没拿到数据，简报里不要提它们，也不用道歉）")
            input.warnings.forEach { appendLine("- $it") }
            appendLine()
        }

        if (input.isEmpty) {
            appendLine("今天没有任何日程、行程、快递、天气和新闻素材。")
            appendLine("这时不要编造内容，只说一句自然的问候，提醒今天是自由的，并简单说明可以稍后再试。")
        }

        append("直接输出要念的正文。")
    }

    /**
     * 天气给模型看的样子：一行一个事实，组织成话的事交给模型。
     *
     * 为什么不在这里就写成句子：整体语气的连贯由模型负责，把成句的天气硬塞进去
     * 会带出"预报腔"（"当前气温二十六摄氏度"）。给它事实，让它说人话。
     *
     * 要不要提带伞 / 要不要提防晒都已经在素材阶段判完了，这里只决定"这行出不出现"：
     * 没到阈值的整行不出现 —— 模型看到一个空标题就会忍不住写"暂无信息"，
     * 而那正是系统提示词明令禁止的。
     */
    private fun WeatherInfo.promptLines(): List<String> = buildList {
        add("当前${temperature}度，$condition；今天${low}到${high}度")

        if (rainAtText != null) {
            add("$rainAtText 前后可能下雨（提醒带伞）")
        } else if ((precipitationProbability ?: 0) >= RAIN_LIKELY_PERCENT) {
            add("今天降水概率 ${precipitationProbability}%（提醒带伞）")
        }

        if (uvNeedsCare) {
            add(if (uvIsStrong) "紫外线强（提醒防晒）" else "紫外线中等（可以提一句防晒）")
        }

        if (airIsBad) add("空气质量不佳，AQI $airQuality（建议戴口罩）")

        val sun = listOfNotNull(
            sunriseText?.let { "${it}日出" },
            sunsetText?.let { "${it}日落" },
        )
        if (sun.isNotEmpty()) add(sun.joinToString("、"))
    }

    private fun codeSuffix(code: String): String =
        if (code.isBlank()) "" else "｜关键号$code"

    private fun timeSuffix(timeText: String): String =
        if (timeText.isBlank()) "" else "｜时间$timeText"

    /**
     * 与 [com.briefwidget.domain.LocalBriefComposer] 的 `RAIN_LIKELY_PERCENT` 同一个门槛。
     * 两处各有一个常量而不是共享：它们分属"给模型看的素材"与"直接念的话"两条链路，
     * 将来真要分开调（比如模型更擅长判断）也不必先解耦。
     */
    private const val RAIN_LIKELY_PERCENT = 50
}
