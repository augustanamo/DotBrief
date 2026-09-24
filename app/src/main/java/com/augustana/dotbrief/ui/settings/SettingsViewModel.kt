package com.augustana.dotbrief.ui.settings

import android.app.Application
import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.augustana.dotbrief.R
import com.augustana.dotbrief.data.llm.LlmClient
import com.augustana.dotbrief.data.llm.LlmCallLogStore
import com.augustana.dotbrief.data.llm.LlmCallLogEntry
import com.augustana.dotbrief.data.llm.LlmProbeResult
import com.augustana.dotbrief.data.local.dao.CapturedNotificationDao
import com.augustana.dotbrief.data.settings.AddRssFeedResult
import com.augustana.dotbrief.data.settings.RuntimeStateStore
import com.augustana.dotbrief.data.settings.SettingsRepository
import com.augustana.dotbrief.data.settings.TtsProvider
import com.augustana.dotbrief.data.settings.UserSettings
import com.augustana.dotbrief.data.settings.WidgetRuntimeState
import com.augustana.dotbrief.data.tts.DoubaoTtsClient
import com.augustana.dotbrief.data.tts.SpeechResult
import com.augustana.dotbrief.data.update.BriefUpdateScheduler
import com.augustana.dotbrief.data.update.BriefUpdater
import com.augustana.dotbrief.data.update.BriefAlarmScheduler
import com.augustana.dotbrief.di.AppContainer
import com.augustana.dotbrief.tts.BriefPlaybackService
import com.augustana.dotbrief.tts.CloudTtsPlayer
import com.augustana.dotbrief.tts.TtsPreviewPlayer
import com.augustana.dotbrief.widget.BriefWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel。
 *
 * 编辑模型：**草稿 + 显式保存**。
 * 输入框里的字符先落在 [UiState.draft]，点保存才写进 DataStore。
 * 这么设计的原因：Base URL / Key 这类字段如果边打边存，
 * 会中途触发 Worker（小组件点击）读到半截配置而报错。
 * 但订阅源的增删改属于"明确动作"，走 [addFeed] / [removeFeed] 立即落盘。
 */
class SettingsViewModel(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val runtimeStateStore: RuntimeStateStore,
    private val capturedNotificationDao: CapturedNotificationDao,
    private val llmClient: LlmClient,
    private val doubaoTtsClient: DoubaoTtsClient,
    private val briefUpdateScheduler: BriefUpdateScheduler,
    private val briefUpdater: BriefUpdater,
    private val briefAlarmScheduler: BriefAlarmScheduler,
    private val llmCallLog: LlmCallLogStore,
) : AndroidViewModel(application) {

    data class UiState(
        val loaded: Boolean = false,
        val draft: UserSettings = UserSettings(),
        val saving: Boolean = false,
        val message: String? = null,
        /** 连通性自检的进行态与结果，null 表示还没测过。 */
        val probing: Boolean = false,
        val probeResult: String? = null,
        val probeOk: Boolean = false,
        /** 「刷新内容」的进行态：正在问模型，期间按钮要禁掉（连点会并发发好几次）。 */
        val updating: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** LLM 调用日志（最近 N 条，新的在前），设置页「接口日志」弹窗订阅它。 */
    val logEntries: StateFlow<List<LlmCallLogEntry>> = llmCallLog.entries
        .map { it.asReversed() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * 小组件运行状态 + 最近一次简报 + **还有没有没听过的新内容**，用于状态卡片回显。
     *
     * [WidgetRuntimeState.unheard] 不在 DataStore 里（它是算出来的，见字段注释），
     * 所以这里把"通知库最新入库时间"这条 Room Flow 并进来当场算：
     * 用户在系统里收到一条测试通知、切回本页，这一行字会自己变，不需要重进页面。
     *
     * 判据有两半，与 [com.augustana.dotbrief.widget.BriefFreshness] 保持一致：
     * 有没听过的通知，**或者**定时刷新出来的那份简报还没被听过。
     */
    val runtimeState: StateFlow<WidgetRuntimeState> = combine(
        runtimeStateStore.state,
        capturedNotificationDao.latestCapturedAtFlow(),
    ) { state, latestCapturedAt ->
        val newerNotification = (latestCapturedAt ?: 0L) > state.lastHeardAtEpochSeconds
        val freshBrief = state.lastBriefAtEpochSeconds > state.lastHeardAtEpochSeconds
        state.copy(unheard = newerNotification || freshBrief)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WidgetRuntimeState())

    private val previewPlayer = TtsPreviewPlayer(application)

    /** 选豆包时的试听播放器：与播报服务用的是同一套播放实现，行为一致。 */
    private val previewCloudPlayer = CloudTtsPlayer(application)

    private val previewCallback = object : CloudTtsPlayer.Callback {
        override fun onStart() = Unit
        override fun onCompletion() = Unit
        override fun onError(message: String) = postMessageText(message)
    }

    init {
        viewModelScope.launch {
            val snapshot = settingsRepository.snapshot()
            _uiState.update { it.copy(draft = snapshot, loaded = true) }
        }
    }

    /** 任意字段的草稿更新，UI 层用 copy 构造新对象后整体传入。 */
    fun onChangeDraft(settings: UserSettings) {
        _uiState.update { it.copy(draft = settings) }
    }

    fun save() {
        // 读盘还没回来时草稿还是全默认值，这时候保存 = 把整份默认配置盖到用户配置上
        // （API Key 一起没）。UI 层的按钮也是禁用的（ActionBar 的 saveEnabled），
        // 这里再兜一道：按钮状态和状态流之间总有窗口，而这一次写坏的东西不可恢复。
        if (!_uiState.value.loaded) return

        // 「下限不能大于上限」这条校验随两个字数滑杆一起删掉了：篇幅现在是枚举档位，
        // 上下限成对写在枚举里，结构上不可能矛盾 —— 那个校验属于两个滑块各调各的年代。
        val draft = _uiState.value.draft

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true) }
            settingsRepository.replace(draft)
            _uiState.update { it.copy(saving = false) }
            // 时刻表可能刚被改过，排期得跟着重排。少了这一步，改完时刻要等到
            // 原来那个时刻跑完一轮才生效 —— 表现是"我明明改成下午三点了，它还是两点刷"。
            briefUpdateScheduler.reschedule()
            // 定时播报的开关/时刻也可能刚改过，同样重排。
            briefAlarmScheduler.reschedule()
            // 保存后立刻把配置推一次到桌面。
            // 少了这一步，"换色"就要等到下一次点击小组件才生效 ——
            // 用户会以为滑块坏了（桌面明明什么都没变）。
            BriefWidgetProvider.refreshAll(getApplication())
            postMessage(
                if (draft.llm.anyReady) R.string.msg_saved else R.string.msg_api_key_missing,
            )
        }
    }

    fun reset() {
        viewModelScope.launch {
            settingsRepository.resetToDefaults()
            _uiState.update { it.copy(draft = UserSettings()) }
            // 恢复默认后时刻表也回到默认（定时播报默认关闭），排期要跟着重排——
            // 否则"恢复默认"之后旧的闹钟排期还在，第二天照样出声。
            briefUpdateScheduler.reschedule()
            briefAlarmScheduler.reschedule()
            postMessage(R.string.msg_reset_done)
        }
    }

    /** 新增订阅源：校验与落盘都在仓库层同一事务内完成，成功后回读一次草稿。 */
    fun addFeed(name: String, url: String) {
        viewModelScope.launch {
            val result = settingsRepository.addRssFeed(name, url)
            if (result == AddRssFeedResult.ADDED) {
                _uiState.update { it.copy(draft = settingsRepository.snapshot()) }
            }
            postMessage(
                when (result) {
                    AddRssFeedResult.ADDED -> R.string.msg_feed_added
                    AddRssFeedResult.NAME_REQUIRED -> R.string.msg_feed_name_required
                    AddRssFeedResult.INVALID_URL -> R.string.msg_feed_url_invalid
                    AddRssFeedResult.DUPLICATE -> R.string.msg_feed_duplicated
                },
            )
        }
    }

    fun removeFeed(id: String) {
        viewModelScope.launch {
            settingsRepository.removeRssFeed(id)
            _uiState.update { it.copy(draft = settingsRepository.snapshot()) }
        }
    }

    fun toggleFeed(id: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRssFeedEnabled(id, enabled)
            _uiState.update { it.copy(draft = settingsRepository.snapshot()) }
        }
    }

    /** 勾选 / 取消推荐源目录（`Defaults.RSS_CATALOG`）里的一个预设源。 */
    fun togglePresetFeed(preset: com.augustana.dotbrief.data.settings.RssFeed, checked: Boolean) {
        viewModelScope.launch {
            settingsRepository.togglePresetFeed(preset, checked)
            _uiState.update { it.copy(draft = settingsRepository.snapshot()) }
        }
    }

    /**
     * 用当前配置念一句示例文本。
     *
     * 必须**按引擎分流**：选了豆包却还走系统 TTS，用户点"试听"听到的是手机自带的机器音，
     * 会以为豆包没配上 —— 这个按钮的意义就是让配置问题当场暴露出来。
     */
    fun previewSpeech() {
        val tts = _uiState.value.draft.tts
        val sample = getApplication<Application>().getString(R.string.preview_sample_text)

        when (tts.provider) {
            TtsProvider.SYSTEM -> {
                previewPlayer.speak(sample)
                postMessage(R.string.msg_preview_speaking)
            }

            TtsProvider.DOUBAO -> {
                if (!tts.isDoubaoReady) {
                    postMessageText(
                        getApplication<Application>().getString(R.string.msg_doubao_incomplete),
                    )
                    return
                }
                postMessage(R.string.msg_preview_synthesizing)
                viewModelScope.launch {
                    when (val result = doubaoTtsClient.synthesize(tts, sample)) {
                        is SpeechResult.Success -> {
                            previewPlayer.stop()
                            previewCloudPlayer.play(result.audio, previewCallback)
                        }

                        is SpeechResult.Failure -> postMessageText(result.message)
                    }
                }
            }
        }
    }

    /**
     * 不等桌面小组件，直接从设置页跑一遍完整播报链路。
     *
     * 走的是**同一个** [BriefPlaybackService]，和点桌面小组件完全一致（包括"缓存还新鲜就先念现成的"
     * 这条规矩），所以这个按钮既是「试听」，也是验证端到端链路的探针：
     * 桌面动效状态、前台通知、TTS 朗读都会真实发生。
     */
    fun playBriefNow() {
        startPlayback(BriefPlaybackService.ACTION_TOGGLE)
    }

    /**
     * 不等下一个更新时刻，现在就去要一份新的内容。**只刷不念。**
     *
     * ## 为什么"刷"和"念"是两个按钮，而不是"带不带缓存"两个按钮
     *
     * 这里一度摆过一对按钮：「立即播报」+「重新生成并播报」，差别只有"用不用缓存"。
     * 那个划分是坏的 —— 用户没法从字面上看出区别，两个按钮九成时间表现还一样。
     * 后来我把「重新生成」删掉了，那是删过头："我现在就想要一份新的"从此没了出口
     * （真机上被问回来一句"那怎么手动更新？"）。
     *
     * 现在按**生产 / 消费**划开，两者永不重叠：
     * - 「立即播报」= 消费：把现成的那份念出来（缓存过期才顺带生成）；
     * - 「刷新内容」= 生产：问一次模型、写好、点亮桌面点阵，**但不出声**。
     *
     * 于是"刷新完点阵变彩"正好成了成功的可见反馈，用户回桌面点一下就能听到；
     * 而"每次点小组件都请求模型"这条仍然被堵着 —— 强制刷新是个显式动作，
     * 只有用户站在设置页、明说要一份新的时才发生。
     *
     * 跑在 viewModelScope 里而不丢给 WorkManager：这是用户在前台等着看结果的动作，
     * 结论要马上变成提示语。真正的生成逻辑已经抽到 [BriefUpdater]，
     * 不存在"两处实现漂移"，所以没必要为了复用再绕一趟后台任务。
     */
    fun updateBriefNow() {
        // 连点两下会并发发两次模型请求。按钮那边也会禁用，这里再兜一道。
        if (_uiState.value.updating) return

        viewModelScope.launch {
            _uiState.update { it.copy(updating = true) }
            val result = briefUpdater.run(force = true)
            _uiState.update { it.copy(updating = false) }

            when (result) {
                is BriefUpdater.Outcome.Updated -> postMessage(R.string.msg_brief_updated)
                is BriefUpdater.Outcome.Failed -> postMessageText(result.message)
                // force = true 时闸门被跳过，走不到这儿；真走到了也只是"什么也没做"，不必打扰。
                BriefUpdater.Outcome.Skipped -> Unit
            }
        }
    }

    /** 若正在朗读则立刻打断，与再点一次小组件等价。 */
    fun stopBrief() {
        startPlayback(BriefPlaybackService.ACTION_STOP)
    }

    private fun startPlayback(action: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, BriefPlaybackService::class.java).setAction(action)
        runCatching { context.startForegroundService(intent) }
            .onFailure { postMessage(R.string.msg_playback_start_failed) }
    }

    /**
     * 连通性自检。
     *
     * 用**草稿**而不是已保存的配置去测：用户多半是刚粘完 Key 就想验证一下，
     * 这时候还没点保存，如果读落盘值就会测到旧配置，徒增困惑。
     */
    fun probeLlm() {
        val draft = _uiState.value.draft
        if (!draft.llm.primary.isReady) {
            _uiState.update {
                it.copy(probeOk = false, probeResult = "Base URL / API Key / 模型名还没填全")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(probing = true, probeResult = null) }
            val result = llmClient.probe(draft.llm.primary)
            _uiState.update {
                it.copy(
                    probing = false,
                    probeOk = result is LlmProbeResult.Reachable,
                    probeResult = when (result) {
                        is LlmProbeResult.Reachable -> "接口可用，往返 ${result.latencyMillis} 毫秒"
                        is LlmProbeResult.Unreachable -> result.message
                    },
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // ---------- 多份 AI 配置 ----------

    /** 新增一份空配置并设为当前编辑。立即落盘（与订阅源的增删同一个"明确动作"口径）。 */
    fun addLlmProfile() {
        viewModelScope.launch {
            settingsRepository.addLlmProfile()
            _uiState.update { it.copy(draft = settingsRepository.snapshot()) }
        }
    }

    /** 删除某一份配置。 */
    fun removeLlmProfile(id: String) {
        viewModelScope.launch {
            settingsRepository.removeLlmProfile(id)
            _uiState.update { it.copy(draft = settingsRepository.snapshot()) }
        }
    }

    /** 把某一份挪到最前（设为主用，失败自动切换的首选）。 */
    fun moveLlmProfileToFront(id: String) {
        viewModelScope.launch {
            settingsRepository.moveLlmProfileToFront(id)
            _uiState.update { it.copy(draft = settingsRepository.snapshot()) }
        }
    }

    /** 切换「当前编辑」的那一份（只改 UI 高亮，不动顺序/落盘）。 */
    fun selectLlmProfile(id: String) {
        _uiState.update { it.copy(draft = it.draft.copy(llm = it.draft.llm.copy(activeId = id))) }
    }

    /** 清空接口日志。 */
    fun clearLog() {
        viewModelScope.launch { llmCallLog.clear() }
    }

    override fun onCleared() {
        super.onCleared()
        previewPlayer.shutdown()
        previewCloudPlayer.stop()
    }

    private fun postMessage(@StringRes resId: Int) {
        val text = getApplication<Application>().getString(resId)
        _uiState.update { it.copy(message = text) }
    }

    /** 服务端返回的文案（已经是人话了），直接透传。 */
    private fun postMessageText(text: String) {
        _uiState.update { it.copy(message = text) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** 用 Application + 手写容器构造，避免引入注入框架。 */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("SettingsViewModel 需要 Application 才能构造")
                SettingsViewModel(
                    application = application,
                    settingsRepository = container.settingsRepository,
                    runtimeStateStore = container.runtimeStateStore,
                    capturedNotificationDao = container.capturedNotificationDao,
                    llmClient = container.llmClient,
                    doubaoTtsClient = container.doubaoTtsClient,
                    briefUpdateScheduler = container.briefUpdateScheduler,
                    briefUpdater = container.briefUpdater,
                    briefAlarmScheduler = container.briefAlarmScheduler,
                    llmCallLog = container.llmCallLog,
                )
            }
        }
    }
}
