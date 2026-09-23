package com.briefwidget.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.briefwidget.BriefWidgetApp
import com.briefwidget.R
import com.briefwidget.data.settings.TtsConfig
import com.briefwidget.data.settings.TtsProvider
import com.briefwidget.data.settings.WidgetState
import com.briefwidget.data.tts.SpeechResult
import com.briefwidget.di.AppContainer
import com.briefwidget.domain.BriefOutcome
import com.briefwidget.ui.settings.SettingsActivity
import com.briefwidget.widget.BriefWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/**
 * 简报播报服务（前台服务）。
 *
 * 完整链路：小组件点击 -> 本服务 -> 置 GENERATING（桌面开始转圈）-> 生成正文
 *          -> 置 PLAYING（桌面开始呼吸）-> TTS 朗读 -> 读完回 IDLE。
 *
 * 三个关键设计：
 * 1. **前台服务**：TTS 朗读可能持续几十秒，普通后台服务会被系统随时回收；
 * 2. **音频焦点用 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK**：让系统把其他媒体自动压低音量，
 *    而不是直接掐断对方——用户想要的是"我说话的时候音乐小声点"，不是"音乐停掉"；
 * 3. **再点即打断**：onStartCommand 里如果正在朗读，直接走 finish()，
 *    这样"点一下开始、再点一下停"是同一个入口，不需要额外判断。
 *
 * 正文来自 [com.briefwidget.domain.GenerateBriefUseCase]：
 * 并行聚合日历 / 行程 / 快递 / 新闻 -> 拼 prompt -> 调大模型 -> 清洗 Markdown，见 [startBriefGeneration]。
 */
class BriefPlaybackService : Service() {

    private data class PendingUtterance(val text: String, val rate: Float, val pitch: Float)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer

    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var pendingUtterance: PendingUtterance? = null
    private var speaking = false
    private var finishing = false

    /** 取数 + 调模型这段在飞。用它防连点：生成中再点一次应当**打断**，而不是再发一次请求。 */
    private var generationJob: Job? = null

    /**
     * 云端引擎（豆包）的合成任务。
     *
     * 单独一个 Job 而不是并进 [generationJob]，是因为两段是可分离的：
     * 正文已经生成好了、正在合成语音时打断，只需要掐合成，不必重跑模型。
     * 但**防连点的判断必须把两者都算上**，否则用户在"正在合成"的空档连点两下会并发合成两次。
     */
    private var synthesisJob: Job? = null

    private var cloudPlayer: CloudTtsPlayer? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** 整条链路（生成 + 合成 + 朗读）是否还在进行中。 */
    private val busy: Boolean
        get() = speaking || generationJob?.isActive == true || synthesisJob?.isActive == true

    override fun onCreate() {
        super.onCreate()
        container = (application as BriefWidgetApp).container
        audioManager = getSystemService(AudioManager::class.java)
        cloudPlayer = CloudTtsPlayer(this)

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_preparing)))

        speech = TextToSpeech(this) { status -> onSpeechInitialized(status) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish()
            // 正在朗读、正在合成、或正在生成，都视为"再点即打断"。
            // 少了后面两半，用户在等待时连点两下就会并发发起两次模型请求 / 两次合成。
            else -> if (busy) finish() else startBriefGeneration()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        // 被系统回收 / 被用户从最近任务划掉时，如果还停在"生成中"或"播报中"，
        // 桌面上的小组件会一直转下去（因为没人再推新状态了）。这里补一次复位。
        val wasBusy = busy

        scope.cancel()
        runCatching {
            speech?.stop()
            speech?.shutdown()
        }
        speech = null
        cloudPlayer?.stop()
        abandonAudioFocus()

        if (wasBusy) {
            val app = application as? BriefWidgetApp ?: return
            app.appScope.launch {
                runCatching { container.runtimeStateStore.setState(WidgetState.IDLE) }
                BriefWidgetProvider.applyState(app, WidgetState.IDLE, false)
            }
        }
    }

    // ------------------------------------------------------------------
    // 流程
    // ------------------------------------------------------------------

    private fun startBriefGeneration() {
        generationJob = scope.launch {
            val store = container.runtimeStateStore
            store.setState(WidgetState.GENERATING)
            BriefWidgetProvider.applyState(this@BriefPlaybackService, WidgetState.GENERATING, false)
            updateNotification(getString(R.string.notif_generating))

            // 进程刚被小组件点起来时，Application 里那次迁移可能还在飞。
            // 这里补一次（幂等）再读配置，保证本次请求用的就是修正后的超时值。
            runCatching { container.settingsRepository.ensureMigrations() }

            val settings = container.settingsRepository.snapshot()

            // 取数或模型调用抛出未捕获异常时，必须自己兜住：
            // 否则协程直接死掉，桌面会永远停在"正在生成…"，用户只能重装应用。
            val outcome = try {
                container.generateBrief.execute()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                BriefOutcome.Failure("生成简报时出错：${error.javaClass.simpleName}")
            }

            val text = when (outcome) {
                is BriefOutcome.Success -> outcome.text
                is BriefOutcome.Failure -> {
                    reportError(outcome.message)
                    return@launch
                }
            }

            // 生成期间可能已经被用户打断（coroutine 被 cancel），这里再确认一次
            if (finishing) return@launch

            store.saveBrief(text)
            speak(text, settings.tts)
        }
    }

    // ------------------------------------------------------------------
    // 朗读：按引擎分流
    // ------------------------------------------------------------------

    /**
     * 两条完全不同的路径：
     * - [TtsProvider.SYSTEM] 把文本交给系统 TextToSpeech，它自己念，进度靠回调；
     * - 云端引擎先合成出一段 mp3，再用 MediaPlayer 播。
     *
     * 对外表现必须一致：都在**真正出声那一刻**把桌面切到 PLAYING，
     * 都在念完后回 IDLE。否则用户会看到"动效开始了但没声音"或反之。
     */
    private fun speak(text: String, tts: TtsConfig) {
        when (tts.provider) {
            TtsProvider.SYSTEM -> speakWithSystem(text, tts.speechRate, tts.pitch)
            TtsProvider.DOUBAO -> speakWithCloud(text, tts)
        }
    }

    private fun speakWithCloud(text: String, tts: TtsConfig) {
        synthesisJob = scope.launch {
            updateNotification(getString(R.string.notif_synthesizing))

            val result = try {
                container.doubaoTtsClient.synthesize(tts, text)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                SpeechResult.Failure("语音合成出错：${error.javaClass.simpleName}")
            }

            if (finishing) return@launch

            val audio = when (result) {
                is SpeechResult.Success -> result.audio
                is SpeechResult.Failure -> {
                    reportError(result.message)
                    return@launch
                }
            }

            requestAudioFocus()
            cloudPlayer?.play(
                audio,
                object : CloudTtsPlayer.Callback {
                    override fun onStart() {
                        if (finishing) return
                        onPlaybackStarted()
                    }

                    override fun onCompletion() = finish()

                    override fun onError(message: String) = reportError(message)
                },
            )
        }
    }

    private fun speakWithSystem(text: String, rate: Float, pitch: Float) {
        val engine = speech
        if (engine == null || !speechReady) {
            // TTS 初始化是异步的，先排队，等 onInit 回来再补播
            pendingUtterance = PendingUtterance(text, rate, pitch)
            return
        }

        requestAudioFocus()
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            reportError("语音引擎拒绝了这次朗读")
        }
    }

    private fun onSpeechInitialized(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            reportError("系统语音引擎初始化失败")
            return
        }

        val engine = speech
        if (engine == null) {
            reportError("系统语音引擎不可用")
            return
        }

        // setLanguage 的返回值得单独判：
        // 不支持中文时 speak() 照样返回 SUCCESS 但一声不吭，用户会以为"点了没反应"。
        when (engine.setLanguage(Locale.SIMPLIFIED_CHINESE)) {
            TextToSpeech.LANG_MISSING_DATA -> {
                reportError("系统缺少中文语音数据，去「设置 → 语言与输入 → 文字转语音」下载中文语音包")
                return
            }

            TextToSpeech.LANG_NOT_SUPPORTED -> {
                reportError("当前语音引擎不支持中文，建议在系统里换成 Google 或三星的语音服务")
                return
            }
        }

        engine.setOnUtteranceProgressListener(progressListener)
        speechReady = true

        pendingUtterance?.let { request ->
            pendingUtterance = null
            // 队列里只可能是系统引擎的请求：云端引擎不等 TTS 初始化，自己合成
            speakWithSystem(request.text, request.rate, request.pitch)
        }
    }

    /**
     * 真正开口那一刻该做的两件事：把桌面切到 PLAYING，并记下"今天已经报过日期"。
     *
     * 两条朗读路径（系统 TTS 的 [UtteranceProgressListener.onStart] 与云端播放器的
     * `onStart`）都走这里，保证"动效开始"和"日期已报"这两个判断不会各写一遍、
     * 也不会出现一条路径记得而另一条忘了。
     *
     * 为什么"日期已报"要等到这一刻才落盘（而不是生成成功时）：见
     * [com.briefwidget.data.settings.RuntimeStateStore.markGreeted]。
     */
    private fun onPlaybackStarted() {
        speaking = true
        scope.launch {
            runCatching { container.runtimeStateStore.markGreeted(LocalDate.now().toEpochDay()) }
            runCatching { container.runtimeStateStore.setSpeaking(true) }
            BriefWidgetProvider.applyState(
                this@BriefPlaybackService,
                WidgetState.PLAYING,
                speaking = true,
            )
            updateNotification(getString(R.string.notif_playing_text))
        }
    }

    /** 正常收尾：回 IDLE、放掉音频焦点、撤掉前台通知、结束服务。 */
    private fun finish() {
        if (finishing) return
        finishing = true

        // 生成中 / 合成中被打断：把在飞的取数与模型请求一起掐掉
        generationJob?.cancel()
        synthesisJob?.cancel()

        speaking = false
        speech?.stop()
        cloudPlayer?.stop()
        abandonAudioFocus()

        scope.launch {
            // 「听过了」：记下这一刻，桌面点阵随之转灰。
            // 手动打断也算 —— 用户已经跟这条内容打过照面并决定不听了，
            // 让点阵继续亮着只会制造"还有新消息"的假象。
            // 出错走的是 reportError()，不会走到这里，所以失败的内容仍然保持彩色。
            runCatching { container.runtimeStateStore.markHeard() }
            runCatching { container.runtimeStateStore.setSpeaking(false) }
            BriefWidgetProvider.applyState(
                this@BriefPlaybackService,
                WidgetState.IDLE,
                speaking = false,
                unheard = false,
            )
            ServiceCompat.stopForeground(this@BriefPlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun reportError(message: String) {
        if (finishing) return
        finishing = true

        synthesisJob?.cancel()
        speaking = false
        speech?.stop()
        cloudPlayer?.stop()
        abandonAudioFocus()

        scope.launch {
            runCatching { container.runtimeStateStore.setError(message) }
            BriefWidgetProvider.applyState(this@BriefPlaybackService, WidgetState.ERROR, false)
            ServiceCompat.stopForeground(this@BriefPlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------------
    // 语音回调：这里决定桌面上动效什么时候开始、什么时候停
    // ------------------------------------------------------------------

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) = onPlaybackStarted()

        override fun onDone(utteranceId: String?) = finish()

        @Deprecated("Android 14 及以下仍会回调这个旧签名")
        override fun onError(utteranceId: String?) = reportError("播报被中断")

        override fun onError(utteranceId: String?, errorCode: Int) =
            reportError("播报失败（错误码 $errorCode）")
    }

    // ------------------------------------------------------------------
    // 音频焦点
    // ------------------------------------------------------------------

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        val request = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    finish()
                }
            }
            .build()
        focusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // ------------------------------------------------------------------
    // 前台通知
    // ------------------------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
                enableVibration(false)
            },
        )
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_brief)
            .setContentTitle(getString(R.string.notif_playing_title))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_TOGGLE = "com.briefwidget.action.TOGGLE_BRIEF"
        const val ACTION_STOP = "com.briefwidget.action.STOP_BRIEF"

        private const val CHANNEL_ID = "brief_playback"
        private const val NOTIFICATION_ID = 0x2101
        private const val UTTERANCE_ID = "brief-playback"
    }
}
