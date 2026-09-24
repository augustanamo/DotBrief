package com.augustana.dotbrief.domain

import android.content.Context
import android.util.Log
import com.augustana.dotbrief.data.ingest.AgendaEvent
import com.augustana.dotbrief.data.ingest.BriefInput
import com.augustana.dotbrief.data.ingest.CalendarSource
import com.augustana.dotbrief.data.ingest.CapturedItem
import com.augustana.dotbrief.data.ingest.DayPart
import com.augustana.dotbrief.data.ingest.Festival
import com.augustana.dotbrief.data.ingest.LunarCalendar
import com.augustana.dotbrief.data.ingest.NewsItem
import com.augustana.dotbrief.data.ingest.RssSource
import com.augustana.dotbrief.data.ingest.SpokenTime
import com.augustana.dotbrief.data.ingest.WeatherSource
import com.augustana.dotbrief.data.ingest.isAnniversary
import com.augustana.dotbrief.data.llm.LlmCallLogStore
import com.augustana.dotbrief.data.llm.LlmCallRecord
import com.augustana.dotbrief.data.llm.LlmCallLogEntry
import com.augustana.dotbrief.data.llm.LlmClient
import com.augustana.dotbrief.data.llm.LlmResult
import com.augustana.dotbrief.data.llm.PromptBuilder
import com.augustana.dotbrief.data.local.dao.CapturedNotificationDao
import com.augustana.dotbrief.data.notification.IngestKind
import com.augustana.dotbrief.data.settings.BriefSource
import com.augustana.dotbrief.data.settings.Defaults
import com.augustana.dotbrief.data.settings.RuntimeStateStore
import com.augustana.dotbrief.data.settings.SettingsRepository
import com.augustana.dotbrief.data.settings.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** 一次生成的最终结果。 */
sealed interface BriefOutcome {
    /**
     * 有内容可念。
     *
     * [notice] 非空表示这次是**降级交付**：正文仍然能念，但某个环节没走通
     * （最典型的是模型限流 / 断网，于是退了本地简报）。它不改变"成功"这个结论 ——
     * 用户确实会听到一段完整的话 —— 但原因要留给设置页，否则
     * "今天怎么没有新闻快讯"这件事从听感上完全看不出来。
     */
    data class Success(
        val text: String,
        val input: BriefInput,
        val notice: String? = null,
    ) : BriefOutcome

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
    private val llmCallLog: LlmCallLogStore,
) {

    suspend fun execute(source: String = SOURCE_WIDGET): BriefOutcome {
        val settings = settingsRepository.snapshot()
        val input = collect(settings, source)

        // 没接大模型：不再报错，改成"只整理本地信息"。
        // 注意这一步必须在取数**之后** —— 本地简报的内容就来自这次取数。
        if (!settings.llm.anyReady) {
            return BriefOutcome.Success(LocalBriefComposer.compose(input), input)
        }

        val system = PromptBuilder.systemPrompt(settings)
        val user = PromptBuilder.userPrompt(input, isAlarm = source == SOURCE_ALARM)

        // 失败自动切换：按列表顺序逐个试，成功即止。见 [LlmProfiles] 的说明。
        val result = completeWithFailover(settings, system, user, source)
        return when (result) {
            is LlmResult.Success -> {
                // 上限只是防失控的天花板 —— 篇幅本身跟着素材走，
                // 见 Defaults.BRIEF_SANITY_MAX_CHARS。
                val clean = sanitize(result.text, Defaults.BRIEF_SANITY_MAX_CHARS)
                if (clean.isBlank()) {
                    // 模型这次没吐出可念的东西 —— 和调用失败同等对待，退到本地简报。
                    fallback(input, "模型返回的内容是空的")
                } else {
                    BriefOutcome.Success(clean, input)
                }
            }

            is LlmResult.Failure -> fallback(input, result.message)
        }
    }

    /**
     * 按顺序调用多份配置，任何一份成功就返回；全失败时返回**最后一份**的失败原因
     * （通常是最靠后那份的错误，也最接近"所有都试过了"的结论）。
     *
     * 每试一份就把结果记进调用日志（旁路、不参与控制流）：这正是设置页「接口日志」
     * 要回答的问题 —— "到底哪一次、谁成功了、谁失败了、为什么"。
     */
    private suspend fun completeWithFailover(
        settings: UserSettings,
        system: String,
        user: String,
        source: String,
    ): LlmResult {
        val ready = settings.llm.profiles.filter { it.isReady }
        var lastFailure: LlmResult.Failure? = null
        val records = mutableListOf<LlmCallRecord>()
        ready.forEachIndexed { index, config ->
            when (val r = llmClient.complete(config, system, user)) {
                is LlmResult.Success -> {
                    if (config.id != ready.first().id) {
                        Log.i(TAG, "首选配置失败后由 ${config.displayName()} 接上")
                    }
                    records += LlmCallRecord(
                        name = config.displayName(),
                        ok = true,
                        order = index + 1,
                    )
                    llmCallLog.append(
                        LlmCallLogEntry(
                            atEpochSeconds = Instant.now().epochSecond,
                            source = source,
                            records = records.toList(),
                        ),
                    )
                    return r
                }
                is LlmResult.Failure -> {
                    Log.w(TAG, "配置 ${config.displayName()} 调用失败：${r.message}")
                    records += LlmCallRecord(
                        name = config.displayName(),
                        ok = false,
                        reason = r.message,
                        order = index + 1,
                    )
                    lastFailure = r
                }
            }
        }
        // 走到这里 = 全部失败：也记一条（全失败的日志同样有排查价值，能看出"是不是都断了"）。
        llmCallLog.append(
            LlmCallLogEntry(
                atEpochSeconds = Instant.now().epochSecond,
                source = source,
                records = records.toList(),
            ),
        )
        return lastFailure ?: LlmResult.Failure("没有可用的模型配置")
    }

    /**
     * 模型这条路没走通时的兜底。
     *
     * ## 为什么不是直接判失败
     *
     * 以前这里返回 `Failure`，于是整条链路停在"生成失败"上 —— **一声不吭**。
     * 用户看到的是"点了小组件没反应"，而真实原因可能只是一次 429
     * （实测踩过：Gemini 免费额度用尽，接口回 429，点一次就是一次静默失败）。
     *
     * 但简报里的日程 / 行程 / 包裹 / 天气**本来就不需要模型**：它们在取数阶段
     * 已经是结构化事实（"取件码 86214""26 度多云"），拼起来就能念。缺的只是新闻提炼。
     * 所以能念出来的一句，永远好过一声不吭。
     *
     * ## 为什么连"素材全空"也交给本地组装
     *
     * 这一条与"没配模型"那条路径保持完全一致（见 [LocalBriefComposer]）：
     * 素材全空时它会说一句"暂时没有需要提醒的日程、行程和包裹"。
     * 用户按了按钮，就该听到一句交代 —— 而不是屏幕上换了个颜色、耳朵里什么都没有。
     * 真正一点都组装不出来的情况（文案为空）才判失败。
     */
    private fun fallback(input: BriefInput, reason: String): BriefOutcome {
        val text = LocalBriefComposer.compose(input)
        if (text.isBlank()) return BriefOutcome.Failure(reason)

        Log.w(TAG, "模型不可用，已降级为本地简报：$reason")
        return BriefOutcome.Success(text, input, notice = reason)
    }

    // ------------------------------------------------------------------
    // 取数
    // ------------------------------------------------------------------

    private suspend fun collect(settings: UserSettings, source: String): BriefInput = coroutineScope {
        val sources = settings.brief.sources
        val now = Instant.now().epochSecond
        val warnings = mutableListOf<String>()

        // 今天是不是第一次开口 —— 决定开场报不报"今天几月几号"。
        // 判据是"上次报过日期的那天是不是今天"，跨天自然失效，不需要任何定时任务。
        val includeDate = runtimeStateStore.snapshot().lastGreetedDay != LocalDate.now().toEpochDay()

        // 这次开口落在一天里的哪一段 —— 天气说今天还是说明天由它决定（见 [DayPart]）。
        // 与 includeDate 一样在这里算一次就交给下游：交给"念素材"的两条链路各算一遍，
        // 迟早会出现同一时刻两种链路的天气说法不一致。
        //
        // 取一次时刻而不是各处 LocalDateTime.now()：跨零点的那几毫秒里，
        // "开场说几点"和"天气说哪天"必须来自同一个瞬间。
        val at = LocalDateTime.now()
        val dayPart = DayPart.of(at.hour)

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

        // 节日跟着日历走（来源就是日历里的"中国节假日"订阅），但查询窗口是"未来 30 天"
        // 而不是"未来几小时"，且没权限时**不算 warning**：没给日历权限的人已经知道
        // 日程读不到，没必要再为"节日也读不到"多念一条。
        val festivalJob = async(Dispatchers.IO) {
            if (!calendarSource.hasPermission() || BriefSource.CALENDAR !in sources) {
                emptyList<Festival>()
            } else {
                calendarSource.upcomingFestivals(FESTIVAL_LOOKAHEAD_DAYS)
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
            if (BriefSource.NEWS !in sources || !settings.llm.anyReady) {
                emptyList<NewsItem>() to null
            } else {
                rssSource.fetch(settings.rss.feeds)
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
        val festivals = festivalJob.await()
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

        // 开场白形态按来源分：闹钟像报时（"现在是上午7点"），其余只说问候语（"早上好"）。
        // 这句话原样进 BriefInput.nowText，由 LocalBriefComposer 和 PromptBuilder 直接拼开场；
        // BriefClock.refresh 播缓存时也按同一套 isAlarm 规则补开场。
        val isAlarm = source == SOURCE_ALARM
        BriefInput(
            nowText = if (isAlarm) {
                "现在是" + SpokenTime.nowText(includeDate, at)
            } else {
                SpokenTime.greetingOpen(includeDate, at)
            },
            includeDate = includeDate,
            dayPart = dayPart,
            lateNightHint = SpokenTime.lateNightCare(at.hour),
            anniversaries = anniversaries,
            events = agenda,
            trips = trips,
            packages = packages,
            festivals = festivals,
            moonPhase = LunarCalendar.moonPhase(at.toLocalDate()),
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

    // 开场白形态已搬到 [SpokenTime.nowText] / [SpokenTime.greetingOpen]：生成时写进素材
    // （这里）、[BriefClock.refresh] 播放定时缓存时把过时的开场换成此刻，两处共用同一套。
    // 闹钟用 nowText（具体时间），其余用 greetingOpen（问候语）—— 见本文件 collect 里 isAlarm 的分流。

    private fun com.augustana.dotbrief.data.local.entity.CapturedNotification.toItem() = CapturedItem(
        kind = kind,
        appLabel = appLabel,
        code = code,
        timeText = timeText,
        text = listOf(title, body).filter { it.isNotBlank() }.joinToString("，"),
    )

    companion object {
        const val TAG = "BriefGenerate"

        /** 节日倒计时的预读天数：最多提前这么久开始提"距 X 还有 N 天"。 */
        const val FESTIVAL_LOOKAHEAD_DAYS = 30

        /** 触发来源标签，写进调用日志（`LlmCallLogEntry.source`），供设置页区分。 */
        const val SOURCE_WIDGET = "widget"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_REFRESH = "refresh"
        const val SOURCE_AUTO = "auto"
        const val SOURCE_ALARM = "alarm"

        /** 正则替换串里的"第一组"。写成 ${'$'}1 是为了躲开 Kotlin 的字符串模板解析。 */
        val GROUP_ONE = "${'$'}1"

        val BOLD_ITALIC = Regex("[*_]{1,3}")
        val INLINE_CODE = Regex("`([^`]*)`")
        val LINE_LEAD = Regex("(?m)^\\s*(#{1,6}\\s*|[-+*>]\\s+|\\d+[.、]\\s*)")
        val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
        val CHINESE_PAUSES = charArrayOf('。', '！', '？', '；', '，', '\n')
    }
}
