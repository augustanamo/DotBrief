package com.augustana.dotbrief.data.llm

import com.augustana.dotbrief.data.ingest.BriefInput
import com.augustana.dotbrief.data.ingest.DayForecast
import com.augustana.dotbrief.data.ingest.DayPart
import com.augustana.dotbrief.data.ingest.MoonPhase
import com.augustana.dotbrief.data.ingest.WeatherInfo
import com.augustana.dotbrief.data.settings.Defaults
import com.augustana.dotbrief.data.settings.UserSettings

/**
 * 把「今天的数据」拼成模型能直接消化的用户消息。
 *
 * 三个原则：
 * 1. **运行时数据一律由代码算好**——口语化时间、距现在多少分钟、篇幅上下限，
 *    全在这里或 [com.augustana.dotbrief.data.ingest.CalendarSource] 里算完，
 *    不让模型做算术（模型算日期经常错位一天）；
 * 2. **用平铺文本而不是 JSON**：小模型读 JSON 容易串行错位，
 *    而这里的数据量很小，平铺反而更稳、token 更少；
 * 3. **没数据的板块整段不出现**：模型看到一个空标题就会忍不住写"暂无信息"，
 *    而那正是系统提示词明令禁止的。
 */
object PromptBuilder {

    /**
     * 系统提示词。
     *
     * 篇幅不再按设置页的档位注入 —— 规则本身就写在默认提示词里（跟着素材走）。
     * 这两行替换保留下来只为**老用户存过的自定义提示词**：他们那份里可能还写着
     * "全文不超过 {{MAX_CHARS}} 个字"，不替换的话模型会直接读到花括号。
     */
    fun systemPrompt(settings: UserSettings): String =
        settings.llm.primary.systemPrompt
            .replace(Defaults.PLACEHOLDER_MIN_CHARS, Defaults.LEGACY_MIN_CHARS.toString())
            .replace(Defaults.PLACEHOLDER_MAX_CHARS, Defaults.LEGACY_MAX_CHARS.toString())

    /**
     * 用户消息：素材 + 这一轮的特殊指令。
     *
     * 不再收 [UserSettings]：从前它只被用来读"讲几条"（`rss.newsCount`），
     * 而那个数字已经去掉了（见 `RssConfig`）—— 素材怎么用，全交给模型按素材量判断。
     * 系统提示词那一侧仍然按配置走，见 [systemPrompt]。
     */
    fun userPrompt(input: BriefInput, isAlarm: Boolean = false): String = buildString {
        appendLine("${input.nowText}。请根据下面的素材写今天的口语简报。")
        // 开场形态已由素材给定：闹钟是"现在是上午7点"（报时），其余是"早上好"（问候语）。
        // 给模型一条明确指令，别让它自作主张改写开场——否则它会自己加"现在是几点几分"，
        // 或把问候语换成时间，这恰恰是这版想消掉的东西。
        appendLine(
            if (isAlarm) {
                "（这是定时播报，像闹钟：开场保留素材里的具体时间，别改成问候语。）"
            } else {
                "（开场只说素材里的时段问候语，不要报具体几点几分。）"
            },
        )
        // 日期只在今天第一次说。素材里的开场本身就体现了这一点
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

        if (input.festivals.isNotEmpty()) {
            appendLine("【临近节日】（今天过节就单独祝福一句；还没到的就说还有几天，别报具体日期）")
            input.festivals.forEach { festival ->
                appendLine(
                    if (festival.daysFromNow == 0) "- 今天是${festival.title}"
                    else "- 距${festival.title}还有 ${festival.daysFromNow} 天",
                )
            }
            appendLine()
        }

        if (input.moonPhase != MoonPhase.UNKNOWN) {
            appendLine("【月相】（今晚的月亮，可以随口提一句，别展开天文解释）")
            appendLine("- 今晚是${input.moonPhase.label}")
            appendLine()
        }

        if (input.weather != null) {
            // 时段写进标题给模型看：晚上那份素材里同时有"现在多少度"和"明天……"两组数，
            // 不点明它会按自己的习惯把两组都当成今天说（用户抓到的就是"晚上还说天气防晒"）。
            appendLine(
                if (input.dayPart == DayPart.TONIGHT) "【天气】（现在是晚上，天气说明天的）"
                else "【天气】",
            )
            input.weather.promptLines(input.dayPart).forEach { appendLine("- $it") }
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
            // 不给条数：讲几条由素材量决定。从前这里写死"挑最重要的 3 条"，
            // 勾了十个源也只讲三条 —— 多勾的源白勾，模型手里多余的素材只能扔掉。
            appendLine("【今日快讯素材】（从中挑值得一听的讲；不重要的可以不提，条数不用凑）")
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

        if (input.lateNightHint != null) {
            appendLine("（现在是深夜，结尾带一句关心用户休息的话，自然地说，比如“夜深了，早点休息”。）")
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
     *
     * 时段由 [part] 决定：白天说今天，入夜说明天。防晒那条不需要在这里再判一次 ——
     * `uvNeedsCare` 本身就带了"说还来不来得及"（见 [WeatherInfo.uvNeedsCare]），
     * 入夜取数时它自然是 false。
     */
    private fun WeatherInfo.promptLines(part: DayPart): List<String> {
        // 刻意不用 buildList：它的 lambda 会把 this 换成构建器，
        // 于是"明天有没有取到"就得靠 this@promptLines 这类标签绕回来，读起来费劲。
        val lines = mutableListOf<String>()

        // 局部变量叫 forecast 而不是 tomorrow：后者与 WeatherInfo 自己的属性同名，
        // `val tomorrow = ... tomorrow ...` 读起来像自引用（SpokenTime 里为同一个理由改过名）。
        val forecast = if (part == DayPart.TONIGHT) tomorrow else null

        if (forecast == null) {
            lines += "当前${temperature}度，$condition；今天${low}到${high}度"
        } else {
            lines += "现在${temperature}度，$condition"
            lines += "明天${forecast.condition}，${forecast.low}到${forecast.high}度"
        }

        rainMaterial(forecast)?.let { lines += it }

        if (uvNeedsCare) {
            lines += if (uvIsStrong) "紫外线强（提醒防晒）" else "紫外线中等（可以提一句防晒）"
        }

        if (airIsBad) lines += "空气质量不佳，AQI $airQuality（建议戴口罩）"

        // 日出日落：白天说今天，入夜说**明天**那对（今天这对已经过去了，
        // 夜里再念"今天 5 点 42 分日出"是在播报一件早就发生的事）。
        val sun = if (forecast == null) {
            listOfNotNull(
                sunriseText?.let { "${it}日出" },
                sunsetText?.let { "${it}日落" },
            )
        } else {
            listOfNotNull(
                forecast.sunriseText?.let { "明天${it}日出" },
                forecast.sunsetText?.let { "明天${it}日落" },
            )
        }
        if (sun.isNotEmpty()) lines += sun.joinToString("、")

        return lines
    }

    /**
     * 带伞提醒。夜里给的是**明天**那份，白天给今天那份。
     *
     * [forecast] 非空表示这次说明天（参数名不叫 tomorrow，免得与
     * [WeatherInfo.tomorrow] 同名、读的时候要回头确认说的是哪一个）。
     * 返回 null = 这次不值得提（概率没到阈值）—— 不会返回一句"今天没雨"，
     * 那样模型会把它当素材念出来。
     */
    private fun WeatherInfo.rainMaterial(forecast: DayForecast?): String? {
        val prefix: String
        val atText: String?
        val probability: Int?
        if (forecast == null) {
            prefix = "今天"
            atText = rainAtText
            probability = precipitationProbability
        } else {
            prefix = "明天"
            atText = forecast.rainAtText
            probability = forecast.precipitationProbability
        }

        return when {
            atText != null -> "$prefix${atText}前后可能下雨（提醒带伞）"
            // ⚠️ 必须写成 ${prefix}：中文汉字是合法的标识符字符，
            // `"$prefix降水概率"` 会被解析成一个叫 `prefix降水概率` 的变量。
            (probability ?: 0) >= RAIN_LIKELY_PERCENT -> "${prefix}降水概率 $probability%（提醒带伞）"
            else -> null
        }
    }

    private fun codeSuffix(code: String): String =
        if (code.isBlank()) "" else "｜关键号$code"

    private fun timeSuffix(timeText: String): String =
        if (timeText.isBlank()) "" else "｜时间$timeText"

    /**
     * 与 [com.augustana.dotbrief.domain.LocalBriefComposer] 的 `RAIN_LIKELY_PERCENT` 同一个门槛。
     * 两处各有一个常量而不是共享：它们分属"给模型看的素材"与"直接念的话"两条链路，
     * 将来真要分开调（比如模型更擅长判断）也不必先解耦。
     */
    private const val RAIN_LIKELY_PERCENT = 50
}
