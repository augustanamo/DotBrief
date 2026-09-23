package com.augustana.dotbrief.data.settings

import kotlinx.serialization.Serializable

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

/** 豆包语音合成走哪一代接口。 */
enum class DoubaoApiVersion {
    /** 大模型接口，音色最全（含 2.0），推荐。 */
    V3,

    /** 老的小模型接口，只有标准音色，但存量账号兼容性最好。 */
    V1,
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
data class LlmConfig(
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
}

/** 新闻流配置。 */
data class RssConfig(
    val feeds: List<RssFeed> = Defaults.RSS_FEEDS,
    val maxItemsPerFeed: Int = Defaults.RSS_MAX_ITEMS_PER_FEED,
    val newsCount: Int = Defaults.RSS_NEWS_COUNT,
)

/** 语音播报配置。 */
data class TtsConfig(
    val provider: TtsProvider = TtsProvider.SYSTEM,
    val speechRate: Float = Defaults.TTS_SPEECH_RATE,
    val pitch: Float = Defaults.TTS_PITCH,
    val localeTag: String = Defaults.TTS_LOCALE_TAG,
    /** 为空表示跟随系统语言，不做语音包指定。 */
    val voiceName: String = "",

    // ---------- 豆包（火山引擎） ----------
    val doubaoApiVersion: DoubaoApiVersion = DoubaoApiVersion.V3,
    val doubaoAppId: String = "",
    /** 火山引擎控制台里的 Access Token。 */
    val doubaoAccessToken: String = "",
    /** v3 用：seed-tts-2.0 / seed-tts-1.0 / seed-icl-2.0 …，必须是账号已开通的那一档。 */
    val doubaoResourceId: String = Defaults.DOUBAO_RESOURCE_ID,
    /** v1 用：标准音色的业务集群。 */
    val doubaoCluster: String = Defaults.DOUBAO_CLUSTER,
    /** 音色 ID：v3 叫 speaker，v1 叫 voice_type，填法一样。 */
    val doubaoSpeaker: String = Defaults.DOUBAO_SPEAKER,
) {
    /** 云端引擎才算「已配置」；系统引擎永远可用，不需要额外校验。 */
    val isDoubaoReady: Boolean
        get() = doubaoAppId.isNotBlank() && doubaoAccessToken.isNotBlank() && doubaoSpeaker.isNotBlank()
}

/** 简报正文的生成规则。 */
data class BriefConfig(
    val minChars: Int = Defaults.BRIEF_MIN_CHARS,
    val maxChars: Int = Defaults.BRIEF_MAX_CHARS,
    val sources: Set<BriefSource> = Defaults.BRIEF_SOURCES,
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
    val llm: LlmConfig = LlmConfig(),
    val rss: RssConfig = RssConfig(),
    val tts: TtsConfig = TtsConfig(),
    val brief: BriefConfig = BriefConfig(),
    val ingest: IngestConfig = IngestConfig(),
    val accent: AccentColor = AccentColor(),
)
