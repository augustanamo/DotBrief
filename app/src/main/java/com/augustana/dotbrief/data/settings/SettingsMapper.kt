package com.augustana.dotbrief.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.augustana.dotbrief.domain.BriefSchedule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalTime

/**
 * Preferences <-> 领域模型的编解码。
 *
 * 设计要点：**读取时全部提供默认值，写入时全部落盘**。
 * 这样新增配置项不会让老用户的 DataStore 变成"缺字段"，也就不需要写 DataStore 数据迁移。
 */
internal object SettingsCodec {

    /** 关键字列表用一个不常见字符做分隔，避免和用户输入里的逗号打架。 */
    private const val LIST_SEPARATOR = "\u0001"

    private val json = Json {
        ignoreUnknownKeys = true   // 老版本写入的多余字段不致命
        encodeDefaults = true
    }

    fun encodeFeeds(feeds: List<RssFeed>): String =
        runCatching { json.encodeToString(feeds) }.getOrDefault("[]")

    fun decodeFeeds(raw: String): List<RssFeed> =
        runCatching { json.decodeFromString<List<RssFeed>>(raw) }.getOrDefault(emptyList())

    fun encodeProfiles(profiles: LlmProfiles): String =
        runCatching { json.encodeToString(profiles) }.getOrDefault("")

    fun decodeProfiles(raw: String): LlmProfiles =
        runCatching { json.decodeFromString<LlmProfiles>(raw) }.getOrNull() ?: LlmProfiles()

    fun encodeKeywords(keywords: List<String>): String = keywords.joinToString(LIST_SEPARATOR)

    fun decodeKeywords(raw: String): List<String> =
        if (raw.isEmpty()) emptyList()
        else raw.split(LIST_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    fun encodeSources(sources: Set<BriefSource>): Set<String> = sources.map { it.name }.toSet()

    fun decodeSources(raw: Set<String>): Set<BriefSource> =
        raw.mapNotNull { name -> BriefSource.values().firstOrNull { it.name == name } }.toSet()

    /**
     * 自动更新时刻：列表 <-> `10:00\u000114:30`。
     *
     * 编码前**排序 + 去重**，解码时**丢弃解析不了的值**：
     * 这张表会被拿去算"最近一个已过时刻"，一两个脏值就足以让判据整体错位，
     * 而少一个时刻只是少刷一次 —— 两害相权，宁可丢。
     *
     * 解析与格式化本身委托给 [BriefSchedule]：用户输入、存储、界面显示共用同一套，
     * 免得出现"存进去 9:30、显示 09:30、再解析失败"这种只在某一条路径上发作的怪事。
     */
    fun encodeTimes(times: List<LocalTime>): String =
        times.distinct().sorted().joinToString(LIST_SEPARATOR) { BriefSchedule.format(it) }

    fun decodeTimes(raw: String): List<LocalTime> =
        if (raw.isEmpty()) {
            emptyList()
        } else {
            raw.split(LIST_SEPARATOR).mapNotNull { BriefSchedule.parse(it) }.distinct().sorted()
        }

    fun decodeTtsProvider(raw: String): TtsProvider =
        TtsProvider.values().firstOrNull { it.name == raw } ?: TtsProvider.SYSTEM

    /** 枚举存名字而不是序号：以后往中间插一档，老数据也不会被错位解析。 */
    fun decodeCarouselPace(raw: String): CarouselPace =
        CarouselPace.values().firstOrNull { it.name == raw } ?: Defaults.WIDGET_ACCENT_CAROUSEL_PACE

    /** 同上，存名字。取不到（老配置、手改过的值）就回落到默认档。 */
    fun decodeBriefLength(raw: String): BriefLength =
        BriefLength.values().firstOrNull { it.name == raw } ?: Defaults.BRIEF_LENGTH
}

/** DataStore Preferences -> 领域模型。任何缺失的 key 都回落到 [Defaults]。 */
internal fun Preferences.toUserSettings(): UserSettings {
    // 首次启动时文件为空，所有 key 都取不到，自然回落到默认值
    val prefs = this
    val defaults = UserSettings()

    return UserSettings(
        // 多份配置：优先读 JSON。旧单份平铺键只在迁移里用，这里不再读它们
        //（迁移跑完之后旧键已被删，就算没跑，迁移也会在 ensureMigrations 里补上）。
        llm = prefs[SettingsKeys.LLM_PROFILES_JSON]
            ?.let { SettingsCodec.decodeProfiles(it) }
            ?: defaults.llm,
        rss = RssConfig(
            feeds = prefs[SettingsKeys.RSS_FEEDS_JSON]
                ?.let { SettingsCodec.decodeFeeds(it) }
                ?: defaults.rss.feeds,
            maxItemsPerFeed = prefs[SettingsKeys.RSS_MAX_ITEMS_PER_FEED] ?: defaults.rss.maxItemsPerFeed,
            newsCount = prefs[SettingsKeys.RSS_NEWS_COUNT] ?: defaults.rss.newsCount,
        ),
        tts = TtsConfig(
            provider = prefs[SettingsKeys.TTS_PROVIDER]
                ?.let { SettingsCodec.decodeTtsProvider(it) }
                ?: defaults.tts.provider,
            // speechRate / pitch 已从 TtsConfig 移除（一律原速），这两行映射也随之删掉。
            localeTag = prefs[SettingsKeys.TTS_LOCALE_TAG] ?: defaults.tts.localeTag,
            voiceName = prefs[SettingsKeys.TTS_VOICE_NAME] ?: defaults.tts.voiceName,
            bgmEnabled = prefs[SettingsKeys.TTS_BGM_ENABLED] ?: defaults.tts.bgmEnabled,
            doubaoApiKey = prefs[SettingsKeys.DOUBAO_API_KEY] ?: defaults.tts.doubaoApiKey,
            doubaoResourceId = prefs[SettingsKeys.DOUBAO_RESOURCE_ID] ?: defaults.tts.doubaoResourceId,
            doubaoSpeaker = prefs[SettingsKeys.DOUBAO_SPEAKER] ?: defaults.tts.doubaoSpeaker,
        ),
        brief = BriefConfig(
            // 篇幅：两个字数滑杆换成一档枚举之后，min/max 字段已从 BriefConfig 移除，
            // 这里也就不再读 brief.min_chars / brief.max_chars（它们会在下次保存时被清掉）。
            length = prefs[SettingsKeys.BRIEF_LENGTH]
                ?.let { SettingsCodec.decodeBriefLength(it) }
                ?: defaults.brief.length,
            sources = prefs[SettingsKeys.BRIEF_SOURCES]
                ?.let { SettingsCodec.decodeSources(it) }
                ?: defaults.brief.sources,
            updateEnabled = prefs[SettingsKeys.BRIEF_UPDATE_ENABLED]
                ?: defaults.brief.updateEnabled,
            // ⚠️ 这里的 `?:` 只在 **key 完全不存在** 时才触发，空串会先被 decodeTimes 变成空列表。
            // 这两种状态含义不同：空串 = 用户把时刻都删了（不自动刷），
            // 缺 key = 从没配过（用默认的 10/14/19）。
            // 顺手写成 `?: emptyList()` 就抹掉了这个区别 —— 用户删完时刻、重启应用，
            // 那三个时刻会自己回来。
            updateTimes = prefs[SettingsKeys.BRIEF_UPDATE_TIMES]
                ?.let { SettingsCodec.decodeTimes(it) }
                ?: defaults.brief.updateTimes,
            alarmEnabled = prefs[SettingsKeys.BRIEF_ALARM_ENABLED]
                ?: defaults.brief.alarmEnabled,
            alarmTimes = prefs[SettingsKeys.BRIEF_ALARM_TIMES]
                ?.let { SettingsCodec.decodeTimes(it) }
                ?: defaults.brief.alarmTimes,
        ),
        ingest = IngestConfig(
            calendarEnabled = prefs[SettingsKeys.CALENDAR_ENABLED] ?: defaults.ingest.calendarEnabled,
            lookaheadHours = prefs[SettingsKeys.LOOKAHEAD_HOURS] ?: defaults.ingest.lookaheadHours,
            todoEnabled = prefs[SettingsKeys.TODO_ENABLED] ?: defaults.ingest.todoEnabled,
            notificationListenerEnabled = prefs[SettingsKeys.NOTIFICATION_LISTENER_ENABLED]
                ?: defaults.ingest.notificationListenerEnabled,
            tripKeywords = prefs[SettingsKeys.TRIP_KEYWORDS]
                ?.let { SettingsCodec.decodeKeywords(it) }
                ?: defaults.ingest.tripKeywords,
            packageKeywords = prefs[SettingsKeys.PACKAGE_KEYWORDS]
                ?.let { SettingsCodec.decodeKeywords(it) }
                ?: defaults.ingest.packageKeywords,
            retentionDays = prefs[SettingsKeys.RETENTION_DAYS] ?: defaults.ingest.retentionDays,
        ),
        accent = AccentColor(
            hue = prefs[SettingsKeys.WIDGET_ACCENT_HUE] ?: defaults.accent.hue,
            saturation = prefs[SettingsKeys.WIDGET_ACCENT_SATURATION] ?: defaults.accent.saturation,
            carousel = prefs[SettingsKeys.WIDGET_ACCENT_CAROUSEL] ?: defaults.accent.carousel,
            carouselPace = prefs[SettingsKeys.WIDGET_ACCENT_CAROUSEL_PACE]
                ?.let { SettingsCodec.decodeCarouselPace(it) }
                ?: defaults.accent.carouselPace,
        ),
    )
}

/** 领域模型 -> DataStore Preferences（全量写入）。 */
internal fun MutablePreferences.applyUserSettings(settings: UserSettings) {
    // 多份配置整体落 JSON；顺手清掉旧单份平铺键（迁移已把它们搬进 JSON，不再需要）。
    this[SettingsKeys.LLM_PROFILES_JSON] = SettingsCodec.encodeProfiles(settings.llm)
    this.remove(SettingsKeys.LLM_BASE_URL)
    this.remove(SettingsKeys.LLM_API_KEY)
    this.remove(SettingsKeys.LLM_MODEL)
    this.remove(SettingsKeys.LLM_TIMEOUT_SECONDS)
    this.remove(SettingsKeys.LLM_TEMPERATURE)
    this.remove(SettingsKeys.LLM_SYSTEM_PROMPT)

    this[SettingsKeys.RSS_FEEDS_JSON] = SettingsCodec.encodeFeeds(settings.rss.feeds)
    this[SettingsKeys.RSS_MAX_ITEMS_PER_FEED] = settings.rss.maxItemsPerFeed
    this[SettingsKeys.RSS_NEWS_COUNT] = settings.rss.newsCount

    this[SettingsKeys.TTS_PROVIDER] = settings.tts.provider.name
    // tts.speech_rate / tts.pitch 不再写入：字段已从 TtsConfig 移除（一律原速）。
    this[SettingsKeys.TTS_LOCALE_TAG] = settings.tts.localeTag
    this[SettingsKeys.TTS_VOICE_NAME] = settings.tts.voiceName
    this[SettingsKeys.TTS_BGM_ENABLED] = settings.tts.bgmEnabled
    // 顺手清理已废弃的 Edge-TTS 键：见 SettingsKeys 里那两个 LEGACY_ 常量的说明
    this.remove(SettingsKeys.LEGACY_EDGE_TTS_ENDPOINT)
    this.remove(SettingsKeys.LEGACY_EDGE_TTS_VOICE)

    this[SettingsKeys.DOUBAO_API_KEY] = settings.tts.doubaoApiKey.trim()
    this[SettingsKeys.DOUBAO_RESOURCE_ID] = settings.tts.doubaoResourceId.trim()
    this[SettingsKeys.DOUBAO_SPEAKER] = settings.tts.doubaoSpeaker.trim()
    // 旧双头鉴权的四个键一并清掉（其中 access_token 是凭据，更不该留着）
    this.remove(SettingsKeys.LEGACY_DOUBAO_API_VERSION)
    this.remove(SettingsKeys.LEGACY_DOUBAO_APP_ID)
    this.remove(SettingsKeys.LEGACY_DOUBAO_ACCESS_TOKEN)
    this.remove(SettingsKeys.LEGACY_DOUBAO_CLUSTER)

    this[SettingsKeys.BRIEF_LENGTH] = settings.brief.length.name
    // 顺手清理已废弃的字数上下限键：见 SettingsKeys 里那两个 LEGACY_ 常量的说明
    this.remove(SettingsKeys.LEGACY_BRIEF_MIN_CHARS)
    this.remove(SettingsKeys.LEGACY_BRIEF_MAX_CHARS)
    this[SettingsKeys.BRIEF_SOURCES] = SettingsCodec.encodeSources(settings.brief.sources)
    this[SettingsKeys.BRIEF_UPDATE_ENABLED] = settings.brief.updateEnabled
    this[SettingsKeys.BRIEF_UPDATE_TIMES] = SettingsCodec.encodeTimes(settings.brief.updateTimes)
    this[SettingsKeys.BRIEF_ALARM_ENABLED] = settings.brief.alarmEnabled
    this[SettingsKeys.BRIEF_ALARM_TIMES] = SettingsCodec.encodeTimes(settings.brief.alarmTimes)

    this[SettingsKeys.CALENDAR_ENABLED] = settings.ingest.calendarEnabled
    this[SettingsKeys.LOOKAHEAD_HOURS] = settings.ingest.lookaheadHours
    this[SettingsKeys.TODO_ENABLED] = settings.ingest.todoEnabled
    this[SettingsKeys.NOTIFICATION_LISTENER_ENABLED] = settings.ingest.notificationListenerEnabled
    this[SettingsKeys.TRIP_KEYWORDS] = SettingsCodec.encodeKeywords(settings.ingest.tripKeywords)
    this[SettingsKeys.PACKAGE_KEYWORDS] = SettingsCodec.encodeKeywords(settings.ingest.packageKeywords)
    this[SettingsKeys.RETENTION_DAYS] = settings.ingest.retentionDays

    this[SettingsKeys.WIDGET_ACCENT_HUE] = settings.accent.hue
    this[SettingsKeys.WIDGET_ACCENT_SATURATION] = settings.accent.saturation
    this[SettingsKeys.WIDGET_ACCENT_CAROUSEL] = settings.accent.carousel
    this[SettingsKeys.WIDGET_ACCENT_CAROUSEL_PACE] = settings.accent.carouselPace.name
}
