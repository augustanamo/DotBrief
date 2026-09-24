package com.augustana.dotbrief.data.settings

import kotlinx.serialization.Serializable
import java.time.LocalTime

/**
 * 单条 RSS / Atom 订阅源配置。
 *
 * 注意：整个列表以 JSON 字符串形式存在一个 DataStore key 里（见 SettingsCodec），
 * 因为订阅源是「整体增删改」的小集合，拆成多 key 反而更难保证原子性。
 */
@Serializable
data class RssFeed(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
)

/**
 * 推荐源目录里的一个分组：同类源归在一起，设置页按组渲染小标题。
 *
 * 与 [RssFeed] 的区别：这只是"可勾选的候选清单"的分组标签，不带"启用"语义。
 * 用户的实际 feeds 列表才是数据来源（见 `SettingsScreen` 的 `RssSection`）。
 */
data class RssCatalogGroup(
    val title: String,
    val feeds: List<RssFeed>,
)

/**
 * 播报引擎。
 * [SYSTEM] 走系统 TextToSpeech（离线、零成本、音色取决于手机）；
 * [DOUBAO] 走火山引擎豆包语音合成（云端、要联网、音色好得多）。
 *
 * 曾经还有一档 [EDGE]（自建 edge-tts 端点）：它要求用户自己跑一个服务，
 * 属于"开发者才知道自己在干什么"的选项，已经从产品里去掉了。
 * 老配置里存着 `EDGE` 的会由 [SettingsCodec.decodeTtsProvider] 静默回落到 [SYSTEM]。
 */
enum class TtsProvider {
    SYSTEM,
    DOUBAO,
}

/**
 * 霓虹轮播的节奏。
 *
 * 拿一个色环周期需要多少帧来表示（见 [Defaults.WIDGET_ACCENT_CAROUSEL_PACE]）。
 *
 * 为什么不直接给"帧间隔"：`AdapterViewFlipper.setFlipInterval()` **没有**
 * `@RemotableViewMethod` 标注，远程调用会抛 `ActionException`（这个坑在小组件布局里踩过），
 * 所以帧间隔只能写死在布局 XML 里、靠换布局来切。
 * 而**帧数是我们完全可控的** —— 帧数越多，同样间隔下走完 360° 就越慢、越顺。
 * 于是"调快慢"这件事就从一个只能改布局的硬件约束，变成了一个纯数据参数。
 */
enum class CarouselPace(val stepsPerCycle: Int) {
    /** 约 7 秒一圈。 */
    FAST(28),

    /** 约 11 秒一圈（默认）。 */
    NORMAL(42),

    /** 约 15 秒一圈。 */
    SLOW(56),
}

/**
 * 点阵配色。
 *
 * 只开放「色相 + 饱和」两个自由度，**不**放开任意 RGB，原因是：
 * 这套点阵靠「暗底 → 中间调 → 高光」三段色阶撑出体积感，
 * 三段必须保持各自固定的明度（0.137 / 0.549 / 1.0）才不会糊成一团。
 * 换色时我们保留每段的 S/V，只把 H 整体旋转过去 ——
 * 于是任何色相下得到的都是同一套"有光晕体积"的观感，用户不可能调丑。
 */
data class AccentColor(
    /** 0..360。默认 64° 就是改动前那版黄绿。 */
    val hue: Float = Defaults.WIDGET_ACCENT_HUE,

    /** 0..1。1 = 原始鲜艳度，往下调会一路褪成灰白。 */
    val saturation: Float = Defaults.WIDGET_ACCENT_SATURATION,

    /**
     * 霓虹轮播。
     *
     * 打开后色相不再固定，而是沿着色环**持续扫动**：一个呼吸周期（约 2.4s）
     * 正好走完 360°，于是 14 帧串起来就是一条完整的霓虹彩虹。
     *
     * 实现上没有增加任何新的渲染成本 —— 桌面侧的 `AdapterViewFlipper`
     * 本来就在逐帧切图（播报时），这里只是让每帧的色相再多走 25.7°。
     * 待机时为了也能转，会把小组件切到"动效布局"，代价是常驻动画；
     * 因此这是一个显式开关，默认关闭。
     *
     * [hue] 在轮播模式下变成"起始色相"，仍然有意义（决定彩虹从哪个颜色起跳）。
     */
    val carousel: Boolean = Defaults.WIDGET_ACCENT_CAROUSEL,

    /**
     * 轮播节奏。
     *
     * 注意：色相走得多快，和"呼吸"没有关系 —— 呼吸只改**亮度**。
     * 两者共用桌面侧那一个 AdapterViewFlipper 的帧时钟，但各自的周期是独立的：
     * 一个色环周期固定取 [CarouselPace.stepsPerCycle] 帧，而呼吸固定 14 帧一圈。
     * 只要是 14 的整数倍，两个循环就能严丝合缝地对齐、不会在接缝处跳一下。
     */
    val carouselPace: CarouselPace = Defaults.WIDGET_ACCENT_CAROUSEL_PACE,
)

/** 参与简报聚合的数据源开关。 */
enum class BriefSource {
    CALENDAR,
    TODO,
    TRIP,
    PACKAGE,

    /**
     * 天气。
     *
     * 它是唯一一个**既不需要用户数据、也不需要大模型**就能念出来的源：
     * 定位 + 一个免密钥的天气接口，拿到的是"26 度、多云"这种可以直接说出口的事实。
     * 所以没接大模型时它照样出现在简报里（见 `LocalBriefComposer`），
     * 也不需要读日历、不需要通知使用权。
     *
     * 位置只取"上一次已知位置"，且过旧的定位不用 —— 报出另一个城市的天气
     * 比不报天气更糟。
     */
    WEATHER,

    NEWS,
}

/** 大模型接口配置，兼容 OpenAI 标准协议。 */
@kotlinx.serialization.Serializable
data class LlmConfig(
    /** 稳定标识，用于「当前使用哪一份」的引用，不随用户改内容而变。 */
    val id: String = "",
    val name: String = "",
    val baseUrl: String = Defaults.LLM_BASE_URL,
    val apiKey: String = "",
    val model: String = Defaults.LLM_MODEL,
    val timeoutSeconds: Int = Defaults.LLM_TIMEOUT_SECONDS,
    val temperature: Float = Defaults.LLM_TEMPERATURE,
    val systemPrompt: String = Defaults.SYSTEM_PROMPT,
) {
    /** 三个关键项齐全才算配置可用，设置页据此给出提示。 */
    val isReady: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 列表里展示的标题：优先用用户起的名字，没起就用「模型名 @ 服务商」。 */
    fun displayName(): String = when {
        name.isNotBlank() -> name
        model.isNotBlank() && baseUrl.isNotBlank() -> {
            val host = runCatching {
                java.net.URI(baseUrl.trimEnd('/')).host.orEmpty()
            }.getOrDefault("")
            if (host.isNotBlank()) "$model @ $host" else model
        }
        else -> "未命名配置"
    }
}

/**
 * 多份 AI 配置的聚合：一份有序列表 + 一个「当前使用」的引用。
 *
 * ## 为什么整体存一个 JSON key
 *
 * 参照 [RssFeed] 已经踩通的路子：配置份数是「整体增删改」的小集合，
 * 拆成多 key 反而难保原子性（增删一份要动好几个 key）。
 *
 * ## 「当前使用」是 [activeId]，而不是列表第 0 项
 *
 * 因为「失败自动切换」的语义是**按顺序回退**：第 0 项是首选，失败试下一份。
 * 用户想"换一个主用"，本质是**把那一份挪到最前**（或调整顺序），
 * 而不是单独改一个布尔开关 —— 那样顺序和"当前"两套状态会打架。
 * [activeId] 只用于**设置页高亮显示当前编辑的那一份**，生成链路认的是列表顺序。
 */
@kotlinx.serialization.Serializable
data class LlmProfiles(
    val profiles: List<LlmConfig> = listOf(LlmConfig()),
    val activeId: String = "",
) {
    /** 有没有至少一份填全了关键项 —— 决定"要不要走模型"（否则退本地简报）。 */
    val anyReady: Boolean get() = profiles.any { it.isReady }

    /** 「当前使用」的那一份；引用丢了（被删/空）就回落列表第一份。 */
    val active: LlmConfig
        get() = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull() ?: LlmConfig()

    /** 生成时的首选（列表第一份）。「失败自动切换」按 [profiles] 顺序回退。 */
    val primary: LlmConfig get() = profiles.firstOrNull() ?: LlmConfig()
}


/**
 * 新闻流配置。
 *
 * 这里曾经还有两个数字：「每个源取几条候选」与「最终讲几条」。都去掉了 ——
 * 候选池的宽度由**用户勾了哪些源**决定（[feeds]），讲几条由模型按素材决定。
 * 从前那套的毛病是"勾了十个源也只讲三条"：多勾的源等于白勾，
 * 而模型明明拿到了十条都值得说的素材，还得砍掉七条。
 */
data class RssConfig(
    val feeds: List<RssFeed> = Defaults.RSS_FEEDS,
)

/** 语音播报配置。 */
data class TtsConfig(
    val provider: TtsProvider = TtsProvider.SYSTEM,
    // 这里曾有过 speechRate / pitch 两个字段，对应设置页那两个滑块，现已移除。
    // 原因不是"简化 UI"，而是这两个值**用户调完之后就失去了可见性**：
    // 界面上只显示一个数字，"2.0X" 看不出异常，可语速确实快了一倍。
    // 现在语音一律按原速合成，两个引擎都不再接受外部倍率。
    val localeTag: String = Defaults.TTS_LOCALE_TAG,
    /** 为空表示跟随系统语言，不做语音包指定。 */
    val voiceName: String = "",
    /**
     * 播报时垫一层背景音乐（`res/raw/bgm.mp3`）。
     *
     * 两个作用，见 `BgmPlayer` 的说明：等待模型的那几秒先放音乐（那段原来是完全静默的），
     * 真正开口后压到垫底音量。关掉则整段都不放，回到纯粹的语音播报。
     */
    val bgmEnabled: Boolean = Defaults.TTS_BGM_ENABLED,

    // ---------- 豆包（火山引擎 · 语音技术） ----------
    /**
     * 火山引擎**语音技术**控制台的 API Key，一串 UUID 形式的值。
     *
     * 只填这一个就够了 —— 它同时顶替了老接口那套
     * 「App ID + Access Token + Cluster」三件套。
     *
     * ⚠️ 别把方舟（Ark）那边的 Key 填进来。两边的控制台各签各的，
     * 方舟那套 `api-key-…` 在语音接口上会被判 `Invalid X-Api-Key`（实测踩过）。
     * 这里**不写任何真实值**：这个字段是用户自己的凭据，不该出现在源码或提交历史里。
     */
    val doubaoApiKey: String = "",

    /** 资源 ID：seed-tts-2.0 / seed-tts-1.0 …，决定这把 Key 能调哪一代模型，必须和音色代次匹配。 */
    val doubaoResourceId: String = Defaults.DOUBAO_RESOURCE_ID,

    /** 音色 ID（`*_bigtts` 系列，如 `zh_female_vv_uranus_bigtts`）。 */
    val doubaoSpeaker: String = Defaults.DOUBAO_SPEAKER,
) {
    /** 云端引擎才算「已配置」；系统引擎永远可用，不需要额外校验。 */
    val isDoubaoReady: Boolean
        get() = doubaoApiKey.isNotBlank() && doubaoSpeaker.isNotBlank()
}

/** 简报正文的生成规则。 */
data class BriefConfig(
    // 这里曾经有 `length: BriefLength`（约 30 秒 / 1 分钟 / 2 分钟三档），已移除：
    // **念多长由素材决定**，不由一个预设档位决定。
    //
    // 那三档两头都不讨好 —— 素材多的日子（十条都值得说的快讯）被硬砍到两分钟，
    // 素材少的日子（只有天气加一条日程）又被下限逼着注水。现在篇幅规则直接写在
    // 系统提示词里（见 [Defaults.SYSTEM_PROMPT] 第 8 条），
    // 代码层只留一个防失控的天花板（见 [Defaults.BRIEF_SANITY_MAX_CHARS]）。
    val sources: Set<BriefSource> = Defaults.BRIEF_SOURCES,

    /** 到点自动刷新内容（只生成、不出声）。 */
    val updateEnabled: Boolean = Defaults.BRIEF_UPDATE_ENABLED,

    /**
     * 每天自动刷新的时刻。
     *
     * 它同时决定"点击时要不要重新请求模型"：落在最近一个**已过**时刻之后的那份内容
     * 被视为新鲜，点击时直接念出来；否则才现场生成。所以这张表改的是**两个**行为 ——
     * 什么时候后台刷新、以及一天最多请求几次模型。
     *
     * 空列表 = 只在用户点的时候生成（自动更新那一档就变成摆设，见设置页的提示）。
     */
    val updateTimes: List<LocalTime> = Defaults.BRIEF_UPDATE_TIMES,

    /**
     * 定时自动播报（当闹钟用）。
     *
     * 与 [updateEnabled] / [updateTimes] 是**两回事**：那套是"到点只把内容刷好、不出声"，
     * 这套是"到点**出声**把简报念出来"。所以开关和时刻表都独立存，互不影响。
     *
     * 到点的行为见 [com.augustana.dotbrief.data.update.BriefAlarmWorker]：
     * 缓存 ≤4 小时就直接念现成的，否则先刷新再念，保证听到的不是昨晚的旧内容。
     */
    val alarmEnabled: Boolean = Defaults.BRIEF_ALARM_ENABLED,

    /**
     * 每天出声播报的时刻（`HH:mm`）。
     *
     * 默认**空**：出声是打扰性动作，不该由默认值替用户决定每天几点被吵醒，
     * 得他主动加一个时刻（如早上 7:00）才生效。
     */
    val alarmTimes: List<LocalTime> = Defaults.BRIEF_ALARM_TIMES,
)

/** 数据抓取（Phase 2）相关配置。 */
data class IngestConfig(
    val calendarEnabled: Boolean = true,
    /** 日程预读时长，任务书要求未来 24 小时。 */
    val lookaheadHours: Int = Defaults.LOOKAHEAD_HOURS,
    val todoEnabled: Boolean = true,
    val notificationListenerEnabled: Boolean = true,
    val tripKeywords: List<String> = Defaults.TRIP_KEYWORDS,
    val packageKeywords: List<String> = Defaults.PACKAGE_KEYWORDS,
    /** 行程/快递记录保留天数，过期由 Worker 清理。 */
    val retentionDays: Int = Defaults.RETENTION_DAYS,
)

/**
 * 全部用户配置的唯一聚合根。
 * 所有字段都有默认值，因此 `UserSettings()` 即「出厂配置」，
 * DataStore 里缺 key 时会自然回落到默认值，无需写迁移。
 */
data class UserSettings(
    val llm: LlmProfiles = LlmProfiles(),
    val rss: RssConfig = RssConfig(),
    val tts: TtsConfig = TtsConfig(),
    val brief: BriefConfig = BriefConfig(),
    val ingest: IngestConfig = IngestConfig(),
    val accent: AccentColor = AccentColor(),
)
