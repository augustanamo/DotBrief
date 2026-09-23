package com.augustana.dotbrief.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * DataStore 的 key 清单。
 *
 * 约定：
 * - key 名用 `域.字段` 点分命名，方便日后 Debug 时直接到 shared_prefs 里定位；
 * - 所有类型都取 Preferences 原生支持的类型（String / Int / Float / Boolean / Set<String>），
 *   复杂结构（订阅源列表、关键字列表）序列化成字符串后再存。
 */
internal object SettingsKeys {

    // ---------- 大模型 ----------
    val LLM_BASE_URL = stringPreferencesKey("llm.base_url")
    val LLM_API_KEY = stringPreferencesKey("llm.api_key")
    val LLM_MODEL = stringPreferencesKey("llm.model")
    val LLM_TIMEOUT_SECONDS = intPreferencesKey("llm.timeout_seconds")
    val LLM_TEMPERATURE = floatPreferencesKey("llm.temperature")
    val LLM_SYSTEM_PROMPT = stringPreferencesKey("llm.system_prompt")

    // ---------- 新闻流 ----------
    val RSS_FEEDS_JSON = stringPreferencesKey("rss.feeds_json")
    val RSS_MAX_ITEMS_PER_FEED = intPreferencesKey("rss.max_items_per_feed")
    val RSS_NEWS_COUNT = intPreferencesKey("rss.news_count")

    // ---------- 语音播报 ----------
    val TTS_PROVIDER = stringPreferencesKey("tts.provider")
    val TTS_SPEECH_RATE = floatPreferencesKey("tts.speech_rate")
    val TTS_PITCH = floatPreferencesKey("tts.pitch")
    val TTS_LOCALE_TAG = stringPreferencesKey("tts.locale_tag")
    val TTS_VOICE_NAME = stringPreferencesKey("tts.voice_name")

    /**
     * 已废弃的 Edge-TTS 自建端点（`tts.edge.endpoint` / `tts.edge.voice`）。
     *
     * 功能已从产品里去掉，但这两个 key 还留在老用户的 DataStore 里。
     * 保存配置时顺手删掉（见 `applyUserSettings`），不做成一次性迁移 ——
     * 它只是两个没人读的字符串，早删晚删都一样，不值得为它多一套迁移机制。
     */
    val LEGACY_EDGE_TTS_ENDPOINT = stringPreferencesKey("tts.edge.endpoint")
    val LEGACY_EDGE_TTS_VOICE = stringPreferencesKey("tts.edge.voice")

    // ---------- 豆包（火山引擎）语音合成 ----------
    val DOUBAO_API_VERSION = stringPreferencesKey("tts.doubao.api_version")
    val DOUBAO_APP_ID = stringPreferencesKey("tts.doubao.app_id")
    val DOUBAO_ACCESS_TOKEN = stringPreferencesKey("tts.doubao.access_token")
    val DOUBAO_RESOURCE_ID = stringPreferencesKey("tts.doubao.resource_id")
    val DOUBAO_CLUSTER = stringPreferencesKey("tts.doubao.cluster")
    val DOUBAO_SPEAKER = stringPreferencesKey("tts.doubao.speaker")

    // ---------- 小组件外观 ----------
    val WIDGET_ACCENT_HUE = floatPreferencesKey("widget.accent_hue")
    val WIDGET_ACCENT_SATURATION = floatPreferencesKey("widget.accent_saturation")
    val WIDGET_ACCENT_CAROUSEL = booleanPreferencesKey("widget.accent_carousel")
    val WIDGET_ACCENT_CAROUSEL_PACE = stringPreferencesKey("widget.accent_carousel_pace")

    // ---------- 简报 ----------
    val BRIEF_MIN_CHARS = intPreferencesKey("brief.min_chars")
    val BRIEF_MAX_CHARS = intPreferencesKey("brief.max_chars")
    val BRIEF_SOURCES = stringSetPreferencesKey("brief.sources")

    // ---------- 抓取 ----------
    val CALENDAR_ENABLED = booleanPreferencesKey("ingest.calendar_enabled")
    val LOOKAHEAD_HOURS = intPreferencesKey("ingest.lookahead_hours")
    val TODO_ENABLED = booleanPreferencesKey("ingest.todo_enabled")
    val NOTIFICATION_LISTENER_ENABLED = booleanPreferencesKey("ingest.notification_listener_enabled")
    val TRIP_KEYWORDS = stringPreferencesKey("ingest.trip_keywords")
    val PACKAGE_KEYWORDS = stringPreferencesKey("ingest.package_keywords")
    val RETENTION_DAYS = intPreferencesKey("ingest.retention_days")

    // ---------- 一次性迁移标记 ----------
    /**
     * 迁移标记本身也存进 DataStore，而不是用 BuildConfig 版本号判断。
     * 理由：用户可能降级、可能清数据、也可能直接装一个更新版覆盖，
     * 只有"配置和标记在同一个事务里"才能保证迁移既不会漏跑、也不会重复跑。
     */
    val MIGRATION_LLM_TIMEOUT_V2 = booleanPreferencesKey("migration.llm_timeout_default_v2")

    /**
     * 把新加的「天气」数据源补进老配置。
     *
     * `brief.sources` 是一个整体存下来的字符串集合，新枚举值不会自己出现在里面：
     * 老用户装上新版之后，「天气」在设置页是**没勾上**的状态，而他们从没做过这个决定。
     * 所以这里补一次：只要那份集合里还没有 WEATHER，就加上它。
     */
    val MIGRATION_WEATHER_SOURCE_V3 = booleanPreferencesKey("migration.weather_source_v3")
}

/** 运行期状态（小组件 UI 状态、最近一次简报、服务连接状态），与用户配置分开存，避免互相覆盖。 */
internal object RuntimeKeys {
    val WIDGET_STATE = stringPreferencesKey("runtime.widget_state")
    val LAST_BRIEF_TEXT = stringPreferencesKey("runtime.last_brief_text")
    val LAST_BRIEF_AT = intPreferencesKey("runtime.last_brief_at_epoch_seconds")
    val LAST_ERROR = stringPreferencesKey("runtime.last_error")
    val SPEAKING = booleanPreferencesKey("runtime.speaking")

    /** 通知监听服务是否已连接（用户在系统设置里勾选后由服务自己上报）。 */
    val NOTIFICATION_LISTENER_CONNECTED = booleanPreferencesKey("runtime.listener_connected")

    /**
     * 出错时刻。
     *
     * 单独存时间戳（而不是复用"最近一次简报时间"）是为了能判断这个错误"过期"没有：
     * 一次网络超时留下的 ERROR 如果永远挂在桌面上，用户会以为组件坏了 ——
     * 实际上它只是需要一个重画的机会。
     */
    val LAST_ERROR_AT = intPreferencesKey("runtime.last_error_at_epoch_seconds")

    /**
     * 上次听完简报的时刻。
     *
     * 与 [LAST_BRIEF_AT] 不是一回事：那个是"生成完"的时刻，这个是"念完"的时刻。
     * 桌面点阵靠它跟"通知库里最新入库时间"比较，判断还有没有没听过的新内容 ——
     * 因此它存的是时间戳而不是一个布尔，见 [WidgetRuntimeState.unheard] 的说明。
     */
    val LAST_HEARD_AT = intPreferencesKey("runtime.last_heard_at_epoch_seconds")

    /**
     * 上次"报过日期"是哪一天。
     *
     * 每天第一次播报要报出"今天几月几号"，之后再听就只说时段 ——
     * 同一句日期听第二遍就是纯噪音。
     *
     * 这里存的是**那天的 epoch day**（1970-01-01 起的天数）而不是时间戳，
     * 于是判断退化成一句"存的这一天 == 今天吗"：
     * 跨天自动失效，不需要任何定时任务；用户手改系统日期再改回来也依然自洽
     * （两次算出来的都是同一个 day 值）。
     */
    val LAST_GREETED_DAY = intPreferencesKey("runtime.last_greeted_day_epoch")
}
