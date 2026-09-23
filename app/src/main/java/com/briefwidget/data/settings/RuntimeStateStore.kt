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
import java.time.Instant

/**
 * 桌面小组件的视觉状态机。
 *
 * 注意：组件上**不显示任何文字**，所以这些状态只通过点阵的"颜色 + 动不动"表达，
 * 不通过文案。见 [com.briefwidget.widget.BriefWidgetRenderer] 的三份布局。
 */
enum class WidgetState {
    /** 待机：一枚安静的点阵。有没听过的新内容时是彩色的，否则是灰的。 */
    IDLE,

    /** 生成中：点阵亮着但不能动（动效只在真正开口后才有），等几秒就有声音了。 */
    GENERATING,

    /** 播放中：点阵在流动 —— 这是唯一会动的状态。 */
    PLAYING,

    /** 出错：点阵停在彩色上，点一下可以重试。具体原因看设置页与日志。 */
    ERROR,
}

/**
 * 小组件需要持久化的运行期状态。
 * 与 [UserSettings] 分开存储的核心理由：这份状态**变化极其频繁**
 *（每次点击都要写 3~4 次），不该跟配置共用同一个文件互相触发无效重组。
 */
data class WidgetRuntimeState(
    val state: WidgetState = WidgetState.IDLE,
    val speaking: Boolean = false,
    val lastBriefText: String = "",
    val lastBriefAtEpochSeconds: Long = 0L,
    val lastError: String = "",
    val notificationListenerConnected: Boolean = false,
    /**
     * 有没有**还没听过**的新内容。
     *
     * 有意**不落盘**：它不是一个可以"记住"的事实，而是一个**算出来**的比较结果 ——
     * 「库里最新一条通知的入库时间 > 上次听完的时刻」。
     * 存成布尔标志就要在"捕获到通知 / 听完 / 清理过期"三处维护它，
     * 漏掉一处就会永远停在错误的那一色上；用时间戳比较则天然不会不一致。
     * 代入者见 [com.briefwidget.widget.BriefFreshness]。
     */
    val unheard: Boolean = false,
    /** 上次**听完整段简报**的时刻（epoch 秒），由 [RuntimeStateStore.markHeard] 写入。 */
    val lastHeardAtEpochSeconds: Long = 0L,
    /**
     * 上一次"报过日期"是哪一天（epoch day）。
     *
     * 生成简报时拿它跟今天比：不相等 = 今天还没听过，开场就该报"今天几月几号"；
     * 相等 = 今天已经报过了，开场只说时段。同样**不落布尔**，
     * 理由与 [unheard] 一样：跨天失效这件事由"两个 day 值不相等"自然表达，
     * 不需要谁在零点把标志清掉。
     */
    val lastGreetedDay: Long = 0L,
) {
    val isBusy: Boolean
        get() = state == WidgetState.GENERATING

    /**
     * 点阵该不该转成"灰色静止"。
     *
     * 只有「安静待机 **且** 没有新内容」这一种组合才发灰：
     * 生成中 / 播报中 / 出错时点阵都是"活的"，颜色是它们的信息载体，不能被压成灰。
     */
    val muted: Boolean
        get() = state == WidgetState.IDLE && !speaking && !unheard
}

/**
 * 运行期状态读写。
 * 小组件（Phase 4）、TTS 服务（Phase 3）、通知监听（Phase 2）都通过它上报状态，
 * 设置页也会读它显示"最近一次简报 / 服务连接状态"，便于排查。
 */
class RuntimeStateStore(
    private val dataStore: DataStore<Preferences>,
) {

    val state: Flow<WidgetRuntimeState> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { it.toRuntimeState() }
        .distinctUntilChanged()

    suspend fun snapshot(): WidgetRuntimeState = state.first()

    suspend fun setState(state: WidgetState) {
        dataStore.edit { prefs -> prefs[RuntimeKeys.WIDGET_STATE] = state.name }
    }

    suspend fun setSpeaking(speaking: Boolean) {
        dataStore.edit { prefs ->
            prefs[RuntimeKeys.SPEAKING] = speaking
            prefs[RuntimeKeys.WIDGET_STATE] = if (speaking) WidgetState.PLAYING.name else WidgetState.IDLE.name
        }
    }

    /** 简报生成成功：落盘正文 + 时间戳，同时清空上次错误。 */
    suspend fun saveBrief(text: String) {
        dataStore.edit { prefs ->
            prefs[RuntimeKeys.LAST_BRIEF_TEXT] = text
            prefs[RuntimeKeys.LAST_BRIEF_AT] = Instant.now().epochSecond.toInt()
            prefs[RuntimeKeys.LAST_ERROR] = ""
            prefs[RuntimeKeys.WIDGET_STATE] = WidgetState.IDLE.name
        }
    }

    suspend fun setError(message: String) {
        dataStore.edit { prefs ->
            prefs[RuntimeKeys.LAST_ERROR] = message
            prefs[RuntimeKeys.LAST_ERROR_AT] = Instant.now().epochSecond.toInt()
            prefs[RuntimeKeys.WIDGET_STATE] = WidgetState.ERROR.name
        }
    }

    /** 通知监听服务连通性，由 [com.briefwidget.data.notification.BriefNotificationListener] 上报。 */
    suspend fun setNotificationListenerConnected(connected: Boolean) {
        dataStore.edit { prefs -> prefs[RuntimeKeys.NOTIFICATION_LISTENER_CONNECTED] = connected }
    }

    /**
     * 听完一次：记下此刻，桌面点阵随之转灰。
     *
     * 由播报服务在**收尾**时调用（念完、或用户主动打断）。
     * 只写时间戳、不写"有没有新内容"：下一次有没有新东西，
     * 由"通知库里最新入库时间是否又超过这个时刻"当场比出来。
     */
    suspend fun markHeard() {
        dataStore.edit { prefs ->
            prefs[RuntimeKeys.LAST_HEARD_AT] = Instant.now().epochSecond.toInt()
        }
    }

    /**
     * 今天已经报过日期了。
     *
     * 由播报服务在**真正开口那一刻**调用（TTS 的 `onStart` / 播放器的 `onStart`），
     * 刻意不放在"生成成功"或"服务启动"时：生成完但还没来得及出声就失败 / 被打断，
     * 用户其实一个字都没听见，日期不该被白白吞掉 —— 下一次重试还得带上它。
     */
    suspend fun markGreeted(epochDay: Long) {
        dataStore.edit { prefs ->
            prefs[RuntimeKeys.LAST_GREETED_DAY] = epochDay.toInt()
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs -> prefs.clear() }
    }
}

/**
 * 错误态在桌面上的存活时间。
 *
 * 过了这段时间，读取时会把 `ERROR` 当回 `IDLE`：错误文案仍旧保留（设置页的"上次出错"
 * 还要用），但小组件不再顶着"出错了"的脸不放。
 * 这么设计是因为 ERROR 是个**终态**——没有任何重试机制会把桌面自己救回来，
 * 而它几乎总是由一次偶发网络超时引起。让它挂满一整天，用户只会以为组件坏了。
 */
private const val ERROR_TTL_SECONDS = 600L

internal fun Preferences.toRuntimeState(): WidgetRuntimeState {
    val rawState = this[RuntimeKeys.WIDGET_STATE]
        ?.let { raw -> WidgetState.values().firstOrNull { it.name == raw } }
        ?: WidgetState.IDLE

    return WidgetRuntimeState(
        state = if (rawState == WidgetState.ERROR && isErrorExpired()) WidgetState.IDLE else rawState,
        speaking = this[RuntimeKeys.SPEAKING] ?: false,
        lastBriefText = this[RuntimeKeys.LAST_BRIEF_TEXT] ?: "",
        lastBriefAtEpochSeconds = (this[RuntimeKeys.LAST_BRIEF_AT] ?: 0).toLong(),
        // 错误文案有意不跟着状态一起清掉：它要留在设置页当排查线索
        lastError = this[RuntimeKeys.LAST_ERROR] ?: "",
        notificationListenerConnected = this[RuntimeKeys.NOTIFICATION_LISTENER_CONNECTED] ?: false,
        // unheard 不在这里读：它要查通知库（挂起操作），由调用方算好再 copy 进来
        lastHeardAtEpochSeconds = (this[RuntimeKeys.LAST_HEARD_AT] ?: 0).toLong(),
        lastGreetedDay = (this[RuntimeKeys.LAST_GREETED_DAY] ?: 0).toLong(),
    )
}

/** 没有错误时间戳（老数据）时视为过期，避免升级后卡在旧错误上。 */
private fun Preferences.isErrorExpired(): Boolean {
    val at = this[RuntimeKeys.LAST_ERROR_AT]?.toLong() ?: return true
    return Instant.now().epochSecond - at > ERROR_TTL_SECONDS
}
