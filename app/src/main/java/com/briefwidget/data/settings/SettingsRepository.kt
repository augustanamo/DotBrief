package com.briefwidget.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.net.URI

/** 添加订阅源的结果，交给 UI 层翻译成提示文案。 */
enum class AddRssFeedResult {
    ADDED,
    NAME_REQUIRED,
    INVALID_URL,
    DUPLICATE,
}

/**
 * 用户配置的唯一读写入口。
 *
 * 线程模型：DataStore 的读写本身是线程安全 + 事务性的，
 * 所以这里**不额外加锁**；并发场景（例如小组件里改配置、设置页同时保存）
 * 一律走 [edit]，读改写三步在同一个事务里完成，不会丢更新。
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {

    /** 配置变化流。设置页订阅它做实时回显，Worker 订阅它取最新配置。 */
    val settings: Flow<UserSettings> = dataStore.data
        .catch { throwable ->
            // DataStore 读盘失败（磁盘满、文件损坏）时不要炸掉整个 App，回落到默认配置
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { it.toUserSettings() }
        .distinctUntilChanged()

    /** 一次性读取当前配置快照。 */
    suspend fun snapshot(): UserSettings = settings.first()

    /** 整体覆盖。 */
    suspend fun replace(settings: UserSettings) {
        dataStore.edit { prefs -> prefs.applyUserSettings(settings) }
    }

    /**
     * 原子读改写。并发安全：读取、变换、写回都在同一个 DataStore 事务内完成。
     * 后续 Worker 里"更新最近一次简报时间"这类操作都应该走这里。
     */
    suspend fun edit(transform: (UserSettings) -> UserSettings) {
        dataStore.edit { prefs -> prefs.applyUserSettings(transform(prefs.toUserSettings())) }
    }

    /** 恢复出厂配置（API Key 也会一并清空）。 */
    suspend fun resetToDefaults() {
        replace(UserSettings())
    }

    /**
     * 跑一次性的存量配置迁移。幂等，随便调多少次都行（有标记位兜底）。
     *
     * 之所以不写成"读取时顺手把旧默认值映射成新默认值"：
     * 那种写法会让**用户主动选了 30 秒**的配置在下次读取时又被顶回 90，
     * 变成"设置页永远存不住 30"。迁移必须只发生一次，之后用户想设多少就设多少。
     */
    suspend fun ensureMigrations() {
        dataStore.edit { prefs ->
            migrateLlmTimeout(prefs)
            migrateWeatherSource(prefs)
        }
    }

    private fun migrateLlmTimeout(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        if (prefs[SettingsKeys.MIGRATION_LLM_TIMEOUT_V2] == true) return

        if (prefs[SettingsKeys.LLM_TIMEOUT_SECONDS] == Defaults.LEGACY_LLM_TIMEOUT_SECONDS) {
            prefs[SettingsKeys.LLM_TIMEOUT_SECONDS] = Defaults.LLM_TIMEOUT_SECONDS
        }
        prefs[SettingsKeys.MIGRATION_LLM_TIMEOUT_V2] = true
    }

    /**
     * 给老配置补上「天气」这一档数据源。
     *
     * `brief.sources` 是整体存下来的集合，新加的枚举值不会自动出现 ——
     * 不补这一步的话，老用户升级后天气在设置页是没勾上的，而那不是他们选的。
     * 只补一次：之后用户真的想关掉天气，不会被下一次启动又打开。
     */
    private fun migrateWeatherSource(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        if (prefs[SettingsKeys.MIGRATION_WEATHER_SOURCE_V3] == true) return

        val stored = prefs[SettingsKeys.BRIEF_SOURCES]
        if (stored != null) {
            prefs[SettingsKeys.BRIEF_SOURCES] = stored + BriefSource.WEATHER.name
        }
        prefs[SettingsKeys.MIGRATION_WEATHER_SOURCE_V3] = true
    }

    /** 只清空 API Key，保留其它配置。 */
    suspend fun clearApiKey() {
        edit { it.copy(llm = it.llm.copy(apiKey = "")) }
    }

    /**
     * 新增订阅源。
     * 校验（名称非空 / URL 合法 / 去重）与写入在同一个事务内完成，
     * 因此并发重复提交也只会成功一次。
     */
    suspend fun addRssFeed(name: String, url: String): AddRssFeedResult {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return AddRssFeedResult.NAME_REQUIRED

        val cleanUrl = normalizeFeedUrl(url) ?: return AddRssFeedResult.INVALID_URL

        var result = AddRssFeedResult.ADDED
        dataStore.edit { prefs ->
            val current = prefs.toUserSettings()
            val duplicated = current.rss.feeds.any { it.url.equals(cleanUrl, ignoreCase = true) }
            if (duplicated) {
                result = AddRssFeedResult.DUPLICATE
                return@edit
            }
            val feed = RssFeed(id = Defaults.newFeedId(), name = cleanName, url = cleanUrl)
            prefs.applyUserSettings(
                current.copy(rss = current.rss.copy(feeds = current.rss.feeds + feed)),
            )
        }
        return result
    }

    suspend fun removeRssFeed(id: String) {
        edit { current ->
            current.copy(rss = current.rss.copy(feeds = current.rss.feeds.filterNot { it.id == id }))
        }
    }

    suspend fun setRssFeedEnabled(id: String, enabled: Boolean) {
        edit { current ->
            val updated = current.rss.feeds.map { feed ->
                if (feed.id == id) feed.copy(enabled = enabled) else feed
            }
            current.copy(rss = current.rss.copy(feeds = updated))
        }
    }

    /** 只保留启用中的订阅源，交给 RssFetcher（Phase 2）使用。 */
    suspend fun enabledFeeds(): List<RssFeed> = snapshot().rss.feeds.filter { it.enabled }

    companion object {
        /**
         * 归一化订阅源地址：
         * - 去掉首尾空白；
         * - 没写协议就补 https://（用户常见输入是 sspai.com/feed）；
         * - 校验必须能解析出 host，否则判为非法。
         *
         * @return 归一化后的 URL；非法时返回 null。
         */
        fun normalizeFeedUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = when {
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "https://$trimmed"
            }
            return runCatching {
                val uri = URI(withScheme)
                if (uri.host.isNullOrBlank()) null else withScheme
            }.getOrNull()
        }
    }
}
