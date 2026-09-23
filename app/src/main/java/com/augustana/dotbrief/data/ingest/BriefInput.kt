package com.augustana.dotbrief.data.ingest

/**
 * 交给大模型之前的"这一天"的原始素材。
 *
 * 刻意保持扁平、无嵌套、字段名直白 —— 因为它是**要序列化进 prompt 的**，
 * 结构越简单，模型越不容易看错，出问题时也越容易肉眼核对。
 */
data class BriefInput(
    /**
     * 口语化时间，例如"9月23日 星期三 下午3点20分"。
     *
     * 日期部分是**可选的**：今天已经听过一次之后，它只留"下午3点20分" ——
     * 同一天里把"今天是几月几号"重复念给同一个人听，是纯噪音。
     * 判断依据见 [includeDate]。
     */
    val nowText: String,
    /**
     * 今天是不是**第一次**播报。
     *
     * 与 [nowText] 里有没有日期是同一件事的两种表达：这里给模型下明确指令
     * （"开场要报日期" / "别再报日期"），[nowText] 负责把事实摆对，
     * 两者都来自 `runtime.last_greeted_day` 与今天的一次比较。
     */
    val includeDate: Boolean,
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
    /** 当前天气；定位不可用 / 接口失败时为 null（那就整段不提）。 */
    val weather: WeatherInfo?,
    val news: List<NewsItem>,
    /** 取数过程中的降级说明（权限没给、某个源超时……），会一并交给模型，让它如实交代。 */
    val warnings: List<String>,
) {
    val isEmpty: Boolean
        get() = anniversaries.isEmpty() && events.isEmpty() && trips.isEmpty() &&
            packages.isEmpty() && weather == null && news.isEmpty()
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
 */
data class WeatherInfo(
    /** 天气现象，例如"多云""小雨"。 */
    val condition: String,
    /** 现在多少度（摄氏）。 */
    val temperature: Int,
    /** 今天最高 / 最低。 */
    val high: Int,
    val low: Int,
    /** 今天剩下来的时间里**第一个可能下雨**的整点，已口语化（"下午三点"）；不会再下就是 null。 */
    val rainAtText: String? = null,
    /** 今天最大的降水概率（0–100），用于兜底：雨已经下过时按它提一句。 */
    val precipitationProbability: Int? = null,
    /** 今天的紫外线指数峰值。 */
    val uvIndex: Int? = null,
    /** 日出 / 日落，已口语化；**只有当天第一次播报才会带上**（其余时候为 null）。 */
    val sunriseText: String? = null,
    val sunsetText: String? = null,
    /** 空气质量指数（美国 AQI 口径）。取不到就是 null。 */
    val airQuality: Int? = null,
) {
    /** 紫外线到了需要防晒的程度（WHO 的"中等"档起点）。 */
    val uvNeedsCare: Boolean get() = (uvIndex ?: 0) >= UV_CARE_THRESHOLD

    /** 紫外线到了"强"档。 */
    val uvIsStrong: Boolean get() = (uvIndex ?: 0) >= UV_STRONG_THRESHOLD

    /** AQI 越过了"轻度污染"，值得提醒一句。 */
    val airIsBad: Boolean get() = (airQuality ?: 0) > AQI_MASK_THRESHOLD

    private companion object {
        /** WHO：UV 3 起就建议防护。 */
        const val UV_CARE_THRESHOLD = 3
        const val UV_STRONG_THRESHOLD = 6

        /**
         * 美国 AQI 101 起是"对敏感人群不健康"，大致对应国内的"轻度污染"。
         * 定在 101 而不是 100：100 整是"良"的上边界，卡在那儿会天天报。
         */
        const val AQI_MASK_THRESHOLD = 100
    }
}

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
