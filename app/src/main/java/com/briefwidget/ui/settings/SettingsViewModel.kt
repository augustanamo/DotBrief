package com.briefwidget.ui.settings

import android.app.Application
import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.briefwidget.R
import com.briefwidget.data.llm.LlmClient
import com.briefwidget.data.llm.LlmProbeResult
import com.briefwidget.data.local.dao.CapturedNotificationDao
import com.briefwidget.data.settings.AddRssFeedResult
import com.briefwidget.data.settings.RuntimeStateStore
import com.briefwidget.data.settings.SettingsRepository
import com.briefwidget.data.settings.TtsProvider
import com.briefwidget.data.settings.UserSettings
import com.briefwidget.data.settings.WidgetRuntimeState
import com.briefwidget.data.tts.DoubaoTtsClient
import com.briefwidget.data.tts.SpeechResult
import com.briefwidget.di.AppContainer
import com.briefwidget.tts.BriefPlaybackService
import com.briefwidget.tts.CloudTtsPlayer
import com.briefwidget.tts.TtsPreviewPlayer
import com.briefwidget.widget.BriefWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 小组件运行状态 + 最近一次简报 + **还有没有没听过的新内容**，用于状态卡片回显。
     *
     * [WidgetRuntimeState.unheard] 不在 DataStore 里（它是算出来的，见字段注释），
     * 所以这里把"通知库最新入库时间"这条 Room Flow 并进来当场算：
     * 用户在系统里收到一条测试通知、切回本页，这一行字会自己变，不需要重进页面。
     */
    val runtimeState: StateFlow<WidgetRuntimeState> = combine(
        runtimeStateStore.state,
        capturedNotificationDao.latestCapturedAtFlow(),
    ) { state, latestCapturedAt ->
        state.copy(unheard = (latestCapturedAt ?: 0L) > state.lastHeardAtEpochSeconds)
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
        val draft = _uiState.value.draft

        if (draft.brief.minChars > draft.brief.maxChars) {
            postMessage(R.string.msg_length_range_invalid)
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true) }
            settingsRepository.replace(draft)
            _uiState.update { it.copy(saving = false) }
            // 保存后立刻把配置推一次到桌面。
            // 少了这一步，"换色"就要等到下一次点击小组件才生效 ——
            // 用户会以为滑块坏了（桌面明明什么都没变）。
            BriefWidgetProvider.refreshAll(getApplication())
            postMessage(
                if (draft.llm.isReady) R.string.msg_saved else R.string.msg_api_key_missing,
            )
        }
    }

    fun reset() {
        viewModelScope.launch {
            settingsRepository.resetToDefaults()
            _uiState.update { it.copy(draft = UserSettings()) }
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
                previewPlayer.speak(sample, tts.speechRate, tts.pitch)
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
     * 走的是**同一个** [BriefPlaybackService]，和点桌面小组件完全一致，
     * 所以这个按钮既是「试听」，也是验证端到端链路的探针：
     * 桌面动效状态、前台通知、TTS 朗读都会真实发生。
     */
    fun playBriefNow() {
        val context = getApplication<Application>()
        val intent = Intent(context, BriefPlaybackService::class.java)
            .setAction(BriefPlaybackService.ACTION_TOGGLE)
        runCatching { context.startForegroundService(intent) }
            .onFailure { postMessage(R.string.msg_playback_start_failed) }
    }

    /** 若正在朗读则立刻打断，与再点一次小组件等价。 */
    fun stopBrief() {
        val context = getApplication<Application>()
        val intent = Intent(context, BriefPlaybackService::class.java)
            .setAction(BriefPlaybackService.ACTION_STOP)
        runCatching { context.startForegroundService(intent) }
    }

    /**
     * 连通性自检。
     *
     * 用**草稿**而不是已保存的配置去测：用户多半是刚粘完 Key 就想验证一下，
     * 这时候还没点保存，如果读落盘值就会测到旧配置，徒增困惑。
     */
    fun probeLlm() {
        val draft = _uiState.value.draft
        if (!draft.llm.isReady) {
            _uiState.update {
                it.copy(probeOk = false, probeResult = "Base URL / API Key / 模型名还没填全")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(probing = true, probeResult = null) }
            val result = llmClient.probe(draft.llm)
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
                )
            }
        }
    }
}
