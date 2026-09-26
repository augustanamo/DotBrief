package com.augustana.dotbrief.data.ingest

/**
 * 交给大模型之前的"这一天"的原始素材。
 *
 * 刻意保持扁平、无嵌套、字段名直白 —— 因为它是**要序列化进 prompt 的**，
 * 结构越简单，模型越不容易看错，出问题时也越容易肉眼核对。
 */
data class BriefInput(
    /**
     * 开场白：闹钟场景是"现在是下午3点20分"（报时），
     * 其余场景是"下午好"（问候语，不说具体几点）。
     *
     * **一律不带日期**：简报天天听，报"今天是几月几号"是把用户瞄一眼手机就知道的
     * 信息念一遍，而且会让开场句的语音缓存每天失效一次。理由见
     * [com.augustana.dotbrief.data.ingest.SpokenTime] 开头。
     * 开场形态见 [com.augustana.dotbrief.data.ingest.SpokenTime.nowText] /
     * [com.augustana.dotbrief.data.ingest.SpokenTime.greetingOpen]。
     */
    val nowText: String,
    /**
     * 今天是不是**第一次**播报。
     *
     * 只用于一件事：**日出日落一天只说一次**（见
     * [com.augustana.dotbrief.data.ingest.WeatherSource.fetch] 的 `includeSunTimes`）。
     * 它曾经还管"开场报不报日期"，那个用途已经去掉（见 [nowText]）。
     *
     * 判据是 `runtime.last_greeted_day` 与今天的一次比较，跨天自然失效。
     */
    val firstOfDay: Boolean,
    /**
     * 这次开口是白天还是入夜 —— 决定天气说今天还是说明天（见 [DayPart]）。
     *
     * 与 [firstOfDay] 是两种不同尺度的"时间语境"：那个管**一天里的第一次**开口，
     * 这个管**一天里的哪一段**开口。两者都在 `GenerateBriefUseCase` 取数时算好，
     * 下游两条链路（交给模型的素材 / 本地简报）只读不算 ——
     * 同一时刻各算一遍，迟早会出现两种链路说法不一致。
     */
    val dayPart: DayPart,
    /**
     * 深夜结尾贴心话（"夜深了，早点休息。"），白天为 null。
     *
     * 和 [dayPart] 一样是"此刻的时间语境"：23 点到次日 5 点前还醒着的人，
     * 简报结尾应该带一句关心休息的话，而不是干巴巴地说完就停。
     * 由 [SpokenTime.lateNightCare] 算出，现场生成时拼进结尾
     * （见 [com.augustana.dotbrief.domain.LocalBriefComposer]），
     * 模型生成时给一条明确指令（见 [com.augustana.dotbrief.data.llm.PromptBuilder]），
     * 缓存播放时由 `BriefClock.refresh` 按此刻补一句。
     */
    val lateNightHint: String?,
    /**
     * 纪念日：全天日程里带"生日 / 纪念 / 周年 / 结婚"的那些。
     *
     * **它们已经从 [events] 里摘出去了**，两边不会重复念。
     * 摘出来的理由不是"数据来源不同"（来源就是日历，读取权限也是同一个），
     * 而是**呈现方式不同**：一条日程会被念成"上午九点半 项目评审"，
     * 而生日要念成"今天是妈妈生日"—— 它是一句祝福，不是一项安排，
     * 混在"接下来有 N 项安排"里既不显眼也不像人说的话。
     */
    val anniversaries: List<AgendaEvent>,
    val events: List<AgendaEvent>,
    val trips: List<CapturedItem>,
    val packages: List<CapturedItem>,
    /**
     * 临近的节日（距今天 0～N 天）。空的 = 最近没有节日可提。
     *
     * 与 [anniversaries] 不同：纪念日来自用户自己的日程（生日/周年），
     * 节日来自系统日历的"中国节假日"订阅（中秋/国庆）。两者都值得单说一句，
     * 但来源和判据不同，见 [CalendarSource.upcomingFestivals]。
     */
    val festivals: List<Festival>,
    /**
     * 今天的月相（由农历推算，见 [LunarCalendar.moonPhase]）。
     * [MoonPhase.UNKNOWN] 表示算不出来（超出可查范围），那就整句不提。
     */
    val moonPhase: MoonPhase,
    /** 当前天气；定位不可用 / 接口失败时为 null（那就整段不提）。 */
    val weather: WeatherInfo?,
    val news: List<NewsItem>,
    /** 取数过程中的降级说明（权限没给、某个源超时……），会一并交给模型，让它如实交代。 */
    val warnings: List<String>,
) {
    val isEmpty: Boolean
        get() = anniversaries.isEmpty() && events.isEmpty() && trips.isEmpty() &&
            packages.isEmpty() && festivals.isEmpty() && moonPhase == MoonPhase.UNKNOWN &&
            weather == null && news.isEmpty()
}

/**
 * 这条日程是不是"纪念日"。
 *
 * 判据是**全天 + 标题里带关键词**：生日/纪念日这类东西在日历里几乎总是
 * 建为全天事件，而"上午十点 给妈妈过生日"这种带具体时间的是安排，不是纪念日本身，
 * 不该被摘出来祝福。
 */
val AgendaEvent.isAnniversary: Boolean
    get() = allDay && ANNIVERSARY_KEYWORDS.any { it in title }

private val ANNIVERSARY_KEYWORDS = listOf("生日", "纪念", "周年", "结婚")

/**
 * 当前天气。
 *
 * 温度一律取整 —— 这是要念出来的，"二十六点三度"没有意义。
 * 天气是少数**不需要模型也能直接念**的信息（见
 * [com.augustana.dotbrief.domain.LocalBriefComposer]），所以没接大模型时它照样出现。
 *
 * ## 时间文本为什么在这里就转好
 *
 * `rainAtText` / `sunriseText` / `sunsetText` 都是已经口语化好的
 * （"下午三点"而不是 15:00），和 [AgendaEvent.atText] 同一个理由：
 * 转时间这件事用代码做是零成本的，交给模型则经常出错。
 *
 * ## 只有"值得说的"才说
 *
 * 紫外线和空气质量都存原始数值，但同时在 [uvNeedsCare] / [airIsBad]
 * 上给出判断 —— 判断规则收在这里一处，免得本地简报和 prompt 各写一遍、
 * 将来改阈值时漏掉一个。
 *
 * ## 时段
 *
 * 同一份天气，白天和夜里要说的话不一样：白天说今天，夜里说明天（[tomorrow]）。
 * 这个分流**不在数据里下结论**，而是由读它的地方按 [DayPart] 去取 ——
 * 数据只多带一个 [fetchedAtHour]（事实），判断留给 [uvNeedsCare] 这类派生属性。
 */
data class WeatherInfo(
    /** 天气现象，例如"多云""小雨"。 */
    val condition: String,
    /** 现在多少度（摄氏）。 */
    val temperature: Int,
    /** 今天最高 / 最低。 */
    val high: Int,
    val low: Int,
    /**
     * 明天。取不到就是 null —— 那时夜里退回说今天那组数字，也不至于整段不提天气。
     *
     * 白天用不到它（说的就是今天），但**每次取数都顺手取回来**：等 18 点之后才发现
     * "明天那份还没取"要么得多个网络往返，要么得回头改写已经生成好的简报。
     */
    val tomorrow: DayForecast? = null,
    /** 今天剩下来的时间里**第一个可能下雨**的整点，已口语化（"下午三点"）；不会再下就是 null。 */
    val rainAtText: String? = null,
    /** 今天最大的降水概率（0–100），用于兜底：雨已经下过时按它提一句。 */
    val precipitationProbability: Int? = null,
    /** 今天的紫外线指数峰值。 */
    val uvIndex: Int? = null,
    /**
     * 这份快照是几点取的。
     *
     * 只服务于一件事：判断防晒提醒**还来不来得及**（见 [uvNeedsCare]）。
     * 存时刻而不是存一个布尔，是因为"来不来得及"是会随阈值调整的规则，
     * 而"几点取的"是事实 —— 事实留在数据里，判断放在读它的地方。
     */
    val fetchedAtHour: Int,
    /** 日出 / 日落，已口语化；**只有当天第一次播报才会带上**（其余时候为 null）。 */
    val sunriseText: String? = null,
    val sunsetText: String? = null,
    /** 空气质量指数（美国 AQI 口径）。取不到就是 null。 */
    val airQuality: Int? = null,
) {
    /**
     * 要不要提一句防晒。
     *
     * 两个条件都满足才算：紫外线真到了该防护的档位（WHO 的 3 起），
     * **且**这个点说还来得及（见 [uvStillRelevant]）。
     */
    val uvNeedsCare: Boolean get() = (uvIndex ?: 0) >= UV_CARE_THRESHOLD && uvStillRelevant

    /** 同上，只是档位更高。 */
    val uvIsStrong: Boolean get() = (uvIndex ?: 0) >= UV_STRONG_THRESHOLD && uvStillRelevant

    /**
     * 紫外线提醒还来不来得及说。
     *
     * `uv_index_max` 是**今天**的峰值，所以过了正午再说"紫外线强记得防晒"就是事后通知 ——
     * 太阳最烈的时候已经过去了。实测被用户抓出来的原话就是"晚上还说天气防晒什么的"。
     * 只在 [UV_CUTOFF_HOUR] 之前开口才提；入夜那一次自然就整条消失了。
     */
    private val uvStillRelevant: Boolean get() = fetchedAtHour <= UV_CUTOFF_HOUR

    /** AQI 越过了"轻度污染"，值得提醒一句。 */
    val airIsBad: Boolean get() = (airQuality ?: 0) > AQI_MASK_THRESHOLD

    private companion object {
        /** WHO：UV 3 起就建议防护。 */
        const val UV_CARE_THRESHOLD = 3
        const val UV_STRONG_THRESHOLD = 6

        /**
         * 紫外线提醒的截止时刻。
         *
         * 取 14 点：峰值大约出现在 13 点，往后留一小段余量给"刚过峰值"的情形。
         * 再晚说这句话就没有行动价值了 —— 不会有人下午四点才开始防晒。
         */
        const val UV_CUTOFF_HOUR = 14

        /**
         * 美国 AQI 101 起是"对敏感人群不健康"，大致对应国内的"轻度污染"。
         * 定在 101 而不是 100：100 整是"良"的上边界，卡在那儿会天天报。
         */
        const val AQI_MASK_THRESHOLD = 100
    }
}

/**
 * 明天的天气。入夜之后那几次播报的主述内容。
 *
 * 为什么不复用一份 [WeatherInfo]：两者的字段并不一样 —— 这里没有"现在多少度"
 * （明天不存在此刻），也没有紫外线（明天的峰值还没到，说了也没法安排防晒）。
 * 硬塞进同一个类型，就得让一半字段在夜里为 null，读起来全是"这个可能没有"。
 */
data class DayForecast(
    /** 天气现象，例如"多云""小雨"。 */
    val condition: String,
    val high: Int,
    val low: Int,
    /** 明天第一个可能下雨的整点，已口语化（"上午9点"）；不会再下就是 null。 */
    val rainAtText: String? = null,
    /** 明天的最大降水概率（0–100），逐小时里找不到雨点时的兜底。 */
    val precipitationProbability: Int? = null,
    /** 明天的日出 / 日落，已口语化；只有"当天第一次播报"才带上。 */
    val sunriseText: String? = null,
    val sunsetText: String? = null,
)

/** 一条日程。 */
data class AgendaEvent(
    val title: String,
    /** "上午9点30分"这种口语表达，直接由这里算好，省得模型自己换算。 */
    val atText: String,
    /** 距离现在多少分钟，负数表示已开始。 */
    val minutesFromNow: Int,
    val location: String,
    val allDay: Boolean,
)

/** 一条从通知里抓到的行程 / 快递。 */
data class CapturedItem(
    val kind: String,
    val appLabel: String,
    val code: String,
    val timeText: String,
    val text: String,
)

/** 一条新闻。 */
data class NewsItem(
    val source: String,
    val title: String,
    val summary: String,
)

/**
 * 一次节日假期（来自系统日历的"中国节假日"订阅，见 [CalendarSource.upcomingFestivals]）。
 *
 * ## 为什么是"假期"而不是"节日那几天里的某一天"
 *
 * 节假日在日历里是**一天一个全天事件**：中秋放三天就是三行。所以要由
 * [FestivalMerge] 合并成一条 —— 否则会念出"今天是中秋节，明天是中秋节，
 * 后天也是中秋节"，同一件事说三遍，还听不出哪天是正日子。
 *
 * ## 三个字段各自回答一个问题
 *
 * - [daysFromNow]：**哪天过节**（0 = 今天，N = 还有 N 天）—— "今天是中秋节"；
 * - [spanDays]：**连着放几天**（1 = 只有节日当天，没有连休）—— "放三天"；
 * - [startDaysFromNow]：**假期从哪天开始**（0 = 今天就开始放）——
 *   节日当天未必是假期第一天（中秋可能落在三天连休的中间）。
 *
 * 播报时怎么组合这三个数（"今天开始放假""还有三天到中秋"）由读它的地方分流，
 * 数据这一侧只放事实。
 */
data class Festival(
    val title: String,
    /** 节日当天距今天数：0 = 今天，1 = 明天，N = 还有 N 天。可能是负数（假期还没放完、节日已过）。 */
    val daysFromNow: Int,
    /** 连着放假的天数。1 = 没有连休，只有节日当天。 */
    val spanDays: Int = 1,
    /** 假期第一天距今天数：0 = 今天就开始放。没有连休时与 [daysFromNow] 相同。 */
    val startDaysFromNow: Int = daysFromNow,
)

/**
 * 今天还在这个假期里（含第一天与最后一天）。
 *
 * ⚠️ 它**不等于**"今天是节日当天"：中秋可能落在三天连休的中间，那时节日已经过了、
 * 假还在放。说成"今天是中秋节"是错的，说成"中秋假期还在放"才对 ——
 * 两种说法的分流靠这个属性。
 */
val Festival.isHolidayNow: Boolean
    get() = startDaysFromNow <= 0 && startDaysFromNow + spanDays > 0
