package com.briefwidget.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun encodeKeywords(keywords: List<String>): String = keywords.joinToString(LIST_SEPARATOR)

    fun decodeKeywords(raw: String): List<String> =
        if (raw.isEmpty()) emptyList()
        else raw.split(LIST_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    fun encodeSources(sources: Set<BriefSource>): Set<String> = sources.map { it.name }.toSet()

    fun decodeSources(raw: Set<String>): Set<BriefSource> =
        raw.mapNotNull { name -> BriefSource.values().firstOrNull { it.name == name } }.toSet()

    fun decodeTtsProvider(raw: String): TtsProvider =
        TtsProvider.values().firstOrNull { it.name == raw } ?: TtsProvider.SYSTEM

    fun decodeDoubaoApiVersion(raw: String): DoubaoApiVersion =
        DoubaoApiVersion.values().firstOrNull { it.name == raw } ?: DoubaoApiVersion.V3

    /** 枚举存名字而不是序号：以后往中间插一档，老数据也不会被错位解析。 */
    fun decodeCarouselPace(raw: String): CarouselPace =
        CarouselPace.values().firstOrNull { it.name == raw } ?: Defaults.WIDGET_ACCENT_CAROUSEL_PACE
}

/** DataStore Preferences -> 领域模型。任何缺失的 key 都回落到 [Defaults]。 */
internal fun Preferences.toUserSettings(): UserSettings {
    // 首次启动时文件为空，所有 key 都取不到，自然回落到默认值
    val prefs = this
    val defaults = UserSettings()

    return UserSettings(
        llm = LlmConfig(
            baseUrl = prefs[SettingsKeys.LLM_BASE_URL] ?: defaults.llm.baseUrl,
            apiKey = prefs[SettingsKeys.LLM_API_KEY] ?: defaults.llm.apiKey,
            model = prefs[SettingsKeys.LLM_MODEL] ?: defaults.llm.model,
            timeoutSeconds = prefs[SettingsKeys.LLM_TIMEOUT_SECONDS] ?: defaults.llm.timeoutSeconds,
            temperature = prefs[SettingsKeys.LLM_TEMPERATURE] ?: defaults.llm.temperature,
            systemPrompt = prefs[SettingsKeys.LLM_SYSTEM_PROMPT] ?: defaults.llm.systemPrompt,
        ),
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
            speechRate = prefs[SettingsKeys.TTS_SPEECH_RATE] ?: defaults.tts.speechRate,
            pitch = prefs[SettingsKeys.TTS_PITCH] ?: defaults.tts.pitch,
            localeTag = prefs[SettingsKeys.TTS_LOCALE_TAG] ?: defaults.tts.localeTag,
            voiceName = prefs[SettingsKeys.TTS_VOICE_NAME] ?: defaults.tts.voiceName,
            doubaoApiVersion = prefs[SettingsKeys.DOUBAO_API_VERSION]
                ?.let { SettingsCodec.decodeDoubaoApiVersion(it) }
                ?: defaults.tts.doubaoApiVersion,
            doubaoAppId = prefs[SettingsKeys.DOUBAO_APP_ID] ?: defaults.tts.doubaoAppId,
            doubaoAccessToken = prefs[SettingsKeys.DOUBAO_ACCESS_TOKEN] ?: defaults.tts.doubaoAccessToken,
            doubaoResourceId = prefs[SettingsKeys.DOUBAO_RESOURCE_ID] ?: defaults.tts.doubaoResourceId,
            doubaoCluster = prefs[SettingsKeys.DOUBAO_CLUSTER] ?: defaults.tts.doubaoCluster,
            doubaoSpeaker = prefs[SettingsKeys.DOUBAO_SPEAKER] ?: defaults.tts.doubaoSpeaker,
        ),
        brief = BriefConfig(
            minChars = prefs[SettingsKeys.BRIEF_MIN_CHARS] ?: defaults.brief.minChars,
            maxChars = prefs[SettingsKeys.BRIEF_MAX_CHARS] ?: defaults.brief.maxChars,
            sources = prefs[SettingsKeys.BRIEF_SOURCES]
                ?.let { SettingsCodec.decodeSources(it) }
                ?: defaults.brief.sources,
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
    this[SettingsKeys.LLM_BASE_URL] = settings.llm.baseUrl.trim()
    this[SettingsKeys.LLM_API_KEY] = settings.llm.apiKey.trim()
    this[SettingsKeys.LLM_MODEL] = settings.llm.model.trim()
    this[SettingsKeys.LLM_TIMEOUT_SECONDS] = settings.llm.timeoutSeconds
    this[SettingsKeys.LLM_TEMPERATURE] = settings.llm.temperature
    this[SettingsKeys.LLM_SYSTEM_PROMPT] = settings.llm.systemPrompt

    this[SettingsKeys.RSS_FEEDS_JSON] = SettingsCodec.encodeFeeds(settings.rss.feeds)
    this[SettingsKeys.RSS_MAX_ITEMS_PER_FEED] = settings.rss.maxItemsPerFeed
    this[SettingsKeys.RSS_NEWS_COUNT] = settings.rss.newsCount

    this[SettingsKeys.TTS_PROVIDER] = settings.tts.provider.name
    this[SettingsKeys.TTS_SPEECH_RATE] = settings.tts.speechRate
    this[SettingsKeys.TTS_PITCH] = settings.tts.pitch
    this[SettingsKeys.TTS_LOCALE_TAG] = settings.tts.localeTag
    this[SettingsKeys.TTS_VOICE_NAME] = settings.tts.voiceName
    // 顺手清理已废弃的 Edge-TTS 键：见 SettingsKeys 里那两个 LEGACY_ 常量的说明
    this.remove(SettingsKeys.LEGACY_EDGE_TTS_ENDPOINT)
    this.remove(SettingsKeys.LEGACY_EDGE_TTS_VOICE)

    this[SettingsKeys.DOUBAO_API_VERSION] = settings.tts.doubaoApiVersion.name
    this[SettingsKeys.DOUBAO_APP_ID] = settings.tts.doubaoAppId.trim()
    this[SettingsKeys.DOUBAO_ACCESS_TOKEN] = settings.tts.doubaoAccessToken.trim()
    this[SettingsKeys.DOUBAO_RESOURCE_ID] = settings.tts.doubaoResourceId.trim()
    this[SettingsKeys.DOUBAO_CLUSTER] = settings.tts.doubaoCluster.trim()
    this[SettingsKeys.DOUBAO_SPEAKER] = settings.tts.doubaoSpeaker.trim()

    this[SettingsKeys.BRIEF_MIN_CHARS] = settings.brief.minChars
    this[SettingsKeys.BRIEF_MAX_CHARS] = settings.brief.maxChars
    this[SettingsKeys.BRIEF_SOURCES] = SettingsCodec.encodeSources(settings.brief.sources)

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
