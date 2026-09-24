package com.augustana.dotbrief.data.llm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 一次生成里，某一份 LLM 配置的调用结果。
 *
 * 与 [LlmResult] 不同：它是一份**只读的记录**，供设置页回溯"到底哪一次、谁成功、谁失败"。
 * 生成链路的正确性仍由 [LlmResult] 承载，这里不参与控制流，只是旁路留痕。
 */
@Serializable
data class LlmCallRecord(
    /** 这份配置当时的显示名（"DeepSeek @ api.deepseek.com"）。改名后旧记录不变，可追溯。 */
    val name: String,
    /** 结果：success / failure。 */
    val ok: Boolean,
    /** 失败时的原因（人话）。成功时为空。 */
    val reason: String = "",
    /** 这一份是列表里的第几个（1 起），即"首选 / 备选 N"。 */
    val order: Int = 1,
)

/**
 * 一次完整生成的调用日志。
 *
 * [records] 按实际尝试顺序排：首选失败会接着列备选，直到某份成功或全部失败。
 * 因此「到底谁成功了」= 列表里最后一个 `ok == true` 的那条（也可能一条都没有，全失败退本地）。
 */
@Serializable
data class LlmCallLogEntry(
    /** epoch 秒，用于排序与显示。 */
    val atEpochSeconds: Long,
    /** 触发来源：widget（点小组件）/ manual（设置页立即播报）/ refresh（刷新内容）/ auto（定时） */
    val source: String,
    val records: List<LlmCallRecord>,
)

/**
 * LLM 调用日志的读写。存一份**独立的** DataStore（`brief_llm_log`），
 * 与配置 / 运行态彻底隔离：这份日志增长无上限，不该和"每次点击都要写三四次"的
 * 运行态共用同一个文件互相触发无效重组，也不该混进带 API Key 的配置文件。
 *
 * 只保留最近 [MAX_ENTRIES] 条，超出时丢最旧的 —— 日志是给"最近出了什么问题"看的，
 * 不是一份永久的审计账。
 */
class LlmCallLogStore(
    private val dataStore: DataStore<Preferences>,
) {

    val entries: Flow<List<LlmCallLogEntry>> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { it.toEntries() }

    suspend fun snapshot(): List<LlmCallLogEntry> = entries.first()

    /**
     * 追加一条。调用方（生成链路）**不关心**写没写进去 —— 日志失败绝不该影响播报，
     * 所以所有异常都在这里咽掉。
     */
    suspend fun append(entry: LlmCallLogEntry) {
        runCatching {
            dataStore.edit { prefs ->
                val existing = prefs.toEntries()
                val merged = (existing + entry).takeLast(MAX_ENTRIES)
                prefs[KEY_JSON] = json.encodeToString(merged)
            }
        }
    }

    /** 清空日志。 */
    suspend fun clear() {
        runCatching { dataStore.edit { prefs -> prefs.remove(KEY_JSON) } }
    }

    private fun Preferences.toEntries(): List<LlmCallLogEntry> {
        val raw = this[KEY_JSON] ?: return emptyList()
        return runCatching { json.decodeFromString<List<LlmCallLogEntry>>(raw) }
            .getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_ENTRIES = 50
        val KEY_JSON = stringPreferencesKey("llm_call_log.entries")

        val json = Json { ignoreUnknownKeys = true }
    }
}

/** 独立 DataStore：见类注释，日志单独一个文件。 */
private val Context.llmCallLogDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "brief_llm_log")

/** 用 Application context 构造一个日志存储（顶层工厂，避开与类同名的函数重载歧义）。 */
fun buildLlmCallLogStore(context: Context): LlmCallLogStore =
    LlmCallLogStore(context.applicationContext.llmCallLogDataStore)
