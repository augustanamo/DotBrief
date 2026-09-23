package com.augustana.dotbrief.domain

import android.content.Context
import com.augustana.dotbrief.data.ingest.AgendaEvent
import com.augustana.dotbrief.data.ingest.BriefInput
import com.augustana.dotbrief.data.ingest.CalendarSource
import com.augustana.dotbrief.data.ingest.CapturedItem
import com.augustana.dotbrief.data.ingest.NewsItem
import com.augustana.dotbrief.data.ingest.RssSource
import com.augustana.dotbrief.data.ingest.SpokenTime
import com.augustana.dotbrief.data.ingest.WeatherSource
import com.augustana.dotbrief.data.ingest.isAnniversary
import com.augustana.dotbrief.data.llm.LlmClient
import com.augustana.dotbrief.data.llm.LlmResult
import com.augustana.dotbrief.data.llm.PromptBuilder
import com.augustana.dotbrief.data.local.dao.CapturedNotificationDao
import com.augustana.dotbrief.data.notification.IngestKind
import com.augustana.dotbrief.data.settings.BriefSource
import com.augustana.dotbrief.data.settings.RuntimeStateStore
import com.augustana.dotbrief.data.settings.SettingsRepository
import com.augustana.dotbrief.data.settings.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 一次生成的最终结果。 */
sealed interface BriefOutcome {
    data class Success(val text: String, val input: BriefInput) : BriefOutcome
    data class Failure(val message: String) : BriefOutcome
}

/**
 * 生成一份口语简报。
 *
 * 流程：读配置 -> 四个数据源**并行**取数 -> （有模型则）拼 prompt / 调模型；无模型则本地整理。
 *
 * 「并行取数」是这里最要紧的一点：日历是本地查询（毫秒级），
 * 通知在本地库里（毫秒级），RSS 是网络（秒级），LLM 也是网络（数秒级）。
 * 串行总耗时约等于各段之和，并行之后 Feed 的等待被模型调用覆盖掉大半，
 * 用户从点击到听见声音的时间能砍掉将近一半。
 *
 * 「没配模型」不是错误路径：日程 / 行程 / 包裹是照抄即可的结构化事实，
 * 交给 [LocalBriefComposer] 直接拼成话就能念。只有**需要提炼**的新闻快讯会缺席
 * —— 那一类的原文没法直接念。
 */
class GenerateBriefUseCase(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val capturedNotificationDao: CapturedNotificationDao,
    /** 只读它的两个时间戳：上次"听完"的时刻决定点阵颜色，上次"报过日期"的那天决定开场要不要报日期。 */
    private val runtimeStateStore: RuntimeStateStore,
    private val calendarSource: CalendarSource = CalendarSource(context),
    private val rssSource: RssSource = RssSource(),
    private val weatherSource: WeatherSource = WeatherSource(context),
    private val llmClient: LlmClient = LlmClient(),
) {

    suspend fun execute(): BriefOutcome {
        val settings = settingsRepository.snapshot()
        val input = collect(settings)

        // 没接大模型：不再报错，改成"只整理本地信息"。
        // 注意这一步必须在取数**之后** —— 本地简报的内容就来自这次取数。
        if (!settings.llm.isReady) {
            return BriefOutcome.Success(LocalBriefComposer.compose(input), input)
        }

        val system = PromptBuilder.systemPrompt(settings)
        val user = PromptBuilder.userPrompt(input, settings)

        return when (val result = llmClient.complete(settings.llm, system, user)) {
            is LlmResult.Success -> {
                val clean = sanitize(result.text, settings.brief.maxChars)
                if (clean.isBlank()) {
                    BriefOutcome.Failure("模型返回的内容是空的，等会儿再试一次")
                } else {
                    BriefOutcome.Success(clean, input)
                }
            }

            is LlmResult.Failure -> BriefOutcome.Failure(result.message)
        }
    }

    // ------------------------------------------------------------------
    // 取数
    // ------------------------------------------------------------------

    private suspend fun collect(settings: UserSettings): BriefInput = coroutineScope {
        val sources = settings.brief.sources
        val now = Instant.now().epochSecond
        val warnings = mutableListOf<String>()

        // 今天是不是第一次开口 —— 决定开场报不报"今天几月几号"。
        // 判据是"上次报过日期的那天是不是今天"，跨天自然失效，不需要任何定时任务。
        val includeDate = runtimeStateStore.snapshot().lastGreetedDay != LocalDate.now().toEpochDay()

        // 顺手清一次过期条目，省得再起一个定时任务
        capturedNotificationDao.purgeExpired(now)

        val calendarJob = async(Dispatchers.IO) {
            if (BriefSource.CALENDAR !in sources && BriefSource.TODO !in sources) {
                emptyList<AgendaEvent>() to null
            } else if (!calendarSource.hasPermission()) {
                emptyList<AgendaEvent>() to "日历权限没给，日程读不到"
            } else {
                calendarSource.upcoming(settings.ingest.lookaheadHours) to null
            }
        }

        val capturedJob = async(Dispatchers.IO) {
            if (BriefSource.TRIP !in sources && BriefSource.PACKAGE !in sources) {
                emptyList<com.augustana.dotbrief.data.local.entity.CapturedNotification>() to null
            } else {
                capturedNotificationDao.active(now) to null
            }
        }

        val newsJob = async(Dispatchers.IO) {
            // 没接模型时**不抓新闻**：头条原文没法直接念，抓回来也用不上，
            // 白等几秒网络不说，还会让"点一下立刻出声"这件事退化。
            if (BriefSource.NEWS !in sources || !settings.llm.isReady) {
                emptyList<NewsItem>() to null
            } else {
                rssSource.fetch(settings.rss.feeds, settings.rss.maxItemsPerFeed)
            }
        }

        // 天气与模型无关：它是"照抄即可"的事实，没接模型时也照抓。
        // 日出日落只在今天第一次播报时带上，所以把 includeDate 一并传进去。
        val weatherJob = async(Dispatchers.IO) {
            if (BriefSource.WEATHER !in sources) {
                null to null
            } else {
                weatherSource.fetch(includeSunTimes = includeDate)
            }
        }

        val (events, calendarWarning) = calendarJob.await()
        val (captured, _) = capturedJob.await()
        val (news, newsWarnings) = newsJob.await()
        val (weather, weatherWarning) = weatherJob.await()

        calendarWarning?.let(warnings::add)
        newsWarnings?.let(warnings::addAll)
        weatherWarning?.let(warnings::add)

        // 纪念日从日程里**摘出来**，两边只留一份。见 [BriefInput.anniversaries] 的说明：
        // 来源都是日历，区别只在怎么说 —— 生日该是一句祝福，不该混进"接下来有 N 项安排"。
        val (anniversaries, agenda) = events.partition { it.isAnniversary }

        val trips = if (BriefSource.TRIP in sources) {
            captured.filter { it.kind == IngestKind.TRIP.name }.map { it.toItem() }
        } else {
            emptyList()
        }
        val packages = if (BriefSource.PACKAGE in sources) {
            captured.filter { it.kind == IngestKind.PACKAGE.name }
                // 只有真抠到取件码的才留下。
                //
                // 快递通知经常成串推送（"已揽收" -> "已到驿站" -> "已到丰巢柜"），
                // 其中几条只命中关键字、抠不出码。这类条目念出来是半句残话
                // （实测："菜鸟驿站，您的包裹已到丰巢快递柜，取件码。"—— 码在推送里就被截掉了），
                // 听的人既不知道去哪取也不知道输什么，等于占了一次开口的时间。
                //
                // 过滤放在**取数这一步**而不是展示层：本地简报与交给模型的素材
                // 是两条链路，规则只该有一处，否则改了一边漏一边。
                .filter { it.code.isNotBlank() }
                .map { it.toItem() }
        } else {
            emptyList()
        }

        BriefInput(
            nowText = nowText(includeDate),
            includeDate = includeDate,
            anniversaries = anniversaries,
            events = agenda,
            trips = trips,
            packages = packages,
            weather = weather,
            news = news,
            warnings = warnings,
        )
    }

    // ------------------------------------------------------------------
    // 输出清洗
    // ------------------------------------------------------------------

    /**
     * 兜底清洗。
     *
     * 系统提示词已经要求"只输出纯文本"，但模型偶尔还是会漏出 `**` 或 `- `。
     * 这些符号进 TTS 会被念成"星号""减号"，所以这里再扫一遍。
     * 只做**安全**的清洗：宁可多留一点符号，也不要把正常句子的标点吃掉。
     */
    private fun sanitize(raw: String, maxChars: Int): String {
        var text = raw

        // 模型偶尔会把正文包在 ``` 代码块里，连语言标记一起剥掉
        text = text.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
                .removePrefix("json").removePrefix("text").removePrefix("markdown")
                .trim()
            text = text.removeSuffix("```")
        }

        // Markdown 强调符号
        text = text.replace(BOLD_ITALIC, "")
        text = text.replace(INLINE_CODE, GROUP_ONE)

        // 行首列表符号 / 标题符号
        text = text.replace(LINE_LEAD, "")

        // 链接 [文字](地址) -> 文字
        text = text.replace(MD_LINK, GROUP_ONE)

        // 连续空白压成一个空格，换行统一成中文句号后的停顿
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n{2,}"), "\n")

        text = text.trim()

        // 模型偶尔会超字数，硬截在句读处，避免念到一半断掉
        if (text.length > maxChars) {
            text = text.take(maxChars)
            val lastPause = text.indexOfLast { it in CHINESE_PAUSES }
            if (lastPause > maxChars / 2) {
                text = text.take(lastPause + 1)
            }
        }

        return text
    }

    /**
     * "现在是几点"的口语表达。
     *
     * [includeDate] 为真（今天第一次）时带上日期与星期：「9月23日 星期三 下午2点52分」；
     * 之后只说时段与时刻：「下午2点52分」。
     *
     * 为什么第二次连星期也去掉：用户问的是"每天第一次说今天几月几号，再听就不会了"，
     * 而"星期三"和日期是同一类信息（都是"今天是哪天"），
     * 留下它等于换了个说法重复同一句废话。时段与时刻不同 —— 它们每次都变，
     * 是"现在这一刻"的坐标，听第二遍也有意义。
     *
     * 时刻本身（12 小时制、"零5分"要补零、0 点念"12点"）统一交给 [SpokenTime]。
     * 这里曾经自己拼过一次 `"${now.hour}点"`，结果念出了"下午14点52分"
     * 这种没人这么说的句子（实测踩过）—— 同一件事有第二份实现就迟早会走样。
     */
    private fun nowText(includeDate: Boolean): String {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        val clock = SpokenTime.clock(now.hour, now.minute)

        return if (includeDate) {
            val weekday = WEEKDAYS[now.dayOfWeek.value - 1]
            "${now.monthValue}月${now.dayOfMonth}日 $weekday $clock"
        } else {
            clock
        }
    }

    private fun com.augustana.dotbrief.data.local.entity.CapturedNotification.toItem() = CapturedItem(
        kind = kind,
        appLabel = appLabel,
        code = code,
        timeText = timeText,
        text = listOf(title, body).filter { it.isNotBlank() }.joinToString("，"),
    )

    private companion object {
        /** 正则替换串里的"第一组"。写成 ${'$'}1 是为了躲开 Kotlin 的字符串模板解析。 */
        val GROUP_ONE = "${'$'}1"

        val BOLD_ITALIC = Regex("[*_]{1,3}")
        val INLINE_CODE = Regex("`([^`]*)`")
        val LINE_LEAD = Regex("(?m)^\\s*(#{1,6}\\s*|[-+*>]\\s+|\\d+[.、]\\s*)")
        val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
        val CHINESE_PAUSES = charArrayOf('。', '！', '？', '；', '，', '\n')
        val WEEKDAYS = arrayOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    }
}
