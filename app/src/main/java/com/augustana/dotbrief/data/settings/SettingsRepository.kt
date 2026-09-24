package com.augustana.dotbrief.data.settings

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
            migrateLlmProfiles(prefs)
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
     * 单份大模型配置 -> 多份（`LlmProfiles`）的一次性迁移。
     *
     * 把旧六个平铺键（`llm.base_url` / `llm.api_key` / `llm.model` / `llm.timeout_seconds` /
     * `llm.temperature` / `llm.system_prompt`）读出来拼成一份 [LlmConfig]，塞进 `profiles[0]`，
     * `activeId` 指向它，然后删掉旧键。**用户零重配**（这正是"已保存的能不能直接转过去"的答案：能）。
     *
     * 边界：如果旧键一项都没有（首次安装、或已经迁过），就什么都不做 —— 不伪造一份空配置。
     */
    private fun migrateLlmProfiles(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        if (prefs[SettingsKeys.MIGRATION_LLM_PROFILES] == true) return

        val legacyBaseUrl = prefs[SettingsKeys.LLM_BASE_URL]
        val legacyApiKey = prefs[SettingsKeys.LLM_API_KEY]
        val legacyModel = prefs[SettingsKeys.LLM_MODEL]

        // 三者全缺 = 从没配过（或全新安装），无需迁移，但照样打上标记避免下次再查。
        if (legacyBaseUrl == null && legacyApiKey == null && legacyModel == null) {
            prefs[SettingsKeys.MIGRATION_LLM_PROFILES] = true
            return
        }

        val id = Defaults.newProfileId()
        val legacy = LlmConfig(
            id = id,
            baseUrl = legacyBaseUrl ?: Defaults.LLM_BASE_URL,
            apiKey = legacyApiKey.orEmpty(),
            model = legacyModel ?: Defaults.LLM_MODEL,
            timeoutSeconds = prefs[SettingsKeys.LLM_TIMEOUT_SECONDS] ?: Defaults.LLM_TIMEOUT_SECONDS,
            temperature = prefs[SettingsKeys.LLM_TEMPERATURE] ?: Defaults.LLM_TEMPERATURE,
            systemPrompt = prefs[SettingsKeys.LLM_SYSTEM_PROMPT] ?: Defaults.SYSTEM_PROMPT,
        )
        prefs[SettingsKeys.LLM_PROFILES_JSON] =
            SettingsCodec.encodeProfiles(LlmProfiles(profiles = listOf(legacy), activeId = id))

        // 旧键搬家完成，清掉（api_key 是凭据，更不该留着两份）。
        prefs.remove(SettingsKeys.LLM_BASE_URL)
        prefs.remove(SettingsKeys.LLM_API_KEY)
        prefs.remove(SettingsKeys.LLM_MODEL)
        prefs.remove(SettingsKeys.LLM_TIMEOUT_SECONDS)
        prefs.remove(SettingsKeys.LLM_TEMPERATURE)
        prefs.remove(SettingsKeys.LLM_SYSTEM_PROMPT)

        prefs[SettingsKeys.MIGRATION_LLM_PROFILES] = true
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
        edit { it.copy(llm = it.llm.copy(profiles = it.llm.profiles.map { p -> p.copy(apiKey = "") })) }
    }

    // ---------- 多份 AI 配置 ----------

    /** 新增一份空配置（字段全默认，Key 留空让用户填）。返回新配置的 id。 */
    suspend fun addLlmProfile(): String {
        val id = Defaults.newProfileId()
        edit { current ->
            current.copy(
                llm = current.llm.copy(
                    profiles = current.llm.profiles + LlmConfig(id = id),
                    activeId = id,
                ),
            )
        }
        return id
    }

    /** 删除一份配置。删除「当前」那份时，把 active 指向列表第一份。 */
    suspend fun removeLlmProfile(id: String) {
        edit { current ->
            val remaining = current.llm.profiles.filterNot { it.id == id }
            current.copy(
                llm = current.llm.copy(
                    profiles = remaining.ifEmpty { listOf(LlmConfig()) },
                    activeId = if (current.llm.activeId == id) {
                        remaining.firstOrNull()?.id.orEmpty()
                    } else {
                        current.llm.activeId
                    },
                ),
            )
        }
    }

    /**
     * 把某一份挪到最前（即设为主用）。
     *
     * 「失败自动切换」按列表顺序回退，所以"换主用" = 调整顺序；
     * 单列一个布尔开关会和顺序打架，不如统一成"排序"这一个动作。
     */
    suspend fun moveLlmProfileToFront(id: String) {
        edit { current ->
            val list = current.llm.profiles
            val target = list.firstOrNull { it.id == id } ?: return@edit current
            val reordered = listOf(target) + list.filterNot { it.id == id }
            current.copy(llm = current.llm.copy(profiles = reordered, activeId = id))
        }
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

    /**
     * 勾选 / 取消一个推荐源（来自 `Defaults.RSS_CATALOG`）。
     *
     * 与 [addRssFeed] 的区别：预设源带固定 id，勾选时直接用它，方便下次按 id 回显状态；
     * 取消时按 id 从 feeds 里移除（而不是设 enabled=false —— 用户明确"不要这个源"，
     * 留一条 disabled 记录没意义，还占 JSON）。
     *
     * 去重：勾选时如果已有同 id 或同 url 的，就把已有的设成 enabled=true，不重复添加
     * （兼容老用户用过的随机 id：按 url 也能认出来）。
     */
    suspend fun togglePresetFeed(preset: RssFeed, checked: Boolean) {
        edit { current ->
            val feeds = current.rss.feeds
            val existing = feeds.firstOrNull {
                it.id == preset.id || it.url.equals(preset.url, ignoreCase = true)
            }
            val updated = when {
                checked && existing != null ->
                    feeds.map { if (it.id == existing.id) it.copy(enabled = true) else it }
                checked && existing == null -> feeds + preset.copy(enabled = true)
                !checked && existing != null -> feeds.filterNot { it.id == existing.id }
                else -> feeds
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
