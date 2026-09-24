package com.augustana.dotbrief.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.augustana.dotbrief.BriefWidgetApp
import com.augustana.dotbrief.R
import com.augustana.dotbrief.data.settings.TtsConfig
import com.augustana.dotbrief.data.settings.TtsProvider
import com.augustana.dotbrief.data.settings.WidgetState
import com.augustana.dotbrief.data.tts.SpeechResult
import com.augustana.dotbrief.di.AppContainer
import com.augustana.dotbrief.domain.BriefClock
import com.augustana.dotbrief.domain.BriefOutcome
import com.augustana.dotbrief.domain.BriefSchedule
import com.augustana.dotbrief.domain.GenerateBriefUseCase
import com.augustana.dotbrief.toastIssue
import com.augustana.dotbrief.ui.settings.SettingsActivity
import com.augustana.dotbrief.widget.BriefWidgetProvider
import com.augustana.dotbrief.widget.BriefWidgetRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale

/**
 * 简报播报服务（前台服务）。
 *
 * 完整链路：小组件点击 -> 本服务 -> **命中缓存则直接念** / 否则置 GENERATING（桌面开始转圈）
 *          -> 生成正文 -> 置 PLAYING（桌面开始呼吸）-> TTS 朗读 -> 读完回 IDLE。
 *
 * 六个关键设计：
 * 1. **前台服务**：TTS 朗读可能持续几十秒，普通后台服务会被系统随时回收；
 * 2. **音频焦点用 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK**：让系统把其他媒体自动压低音量，
 *    而不是直接掐断对方——用户想要的是"我说话的时候音乐小声点"，不是"音乐停掉"；
 * 3. **再点即打断**：onStartCommand 里如果正在朗读，直接走 finish()，
 *    这样"点一下开始、再点一下停"是同一个入口，不需要额外判断；
 * 4. **缓存优先**：定时任务刷出来的内容还新鲜时**直接念**，不再请求模型 ——
 *    见 [BriefSchedule] 的判据。设置页里那个「立即播报」走的也是这一条，
 *    所以桌面和设置页两个入口不会有"点哪个更费额度"的差别；
 * 5. **点下去先给个回执**：来自小组件的点击会带 [BriefWidgetRenderer.EXTRA_FROM_WIDGET]，
 *    服务收到后震一下（见 [buzz]）—— 从点下去到出声有一两秒的静默期，没有回执的话
 *    用户会以为没点到而再点一次，而那一下正好是"打断"；
 * 6. **点阵本身就是进度条**：播报期间每隔 [PROGRESS_TICK_MS] 把进度推给桌面，
 *    念过的部分沿途褪成灰（见 [publishProgress] 与 `DotMatrixArt` 的 progress 参数）。
 *
 * 正文来自 [com.augustana.dotbrief.domain.GenerateBriefUseCase]：
 * 并行聚合日历 / 行程 / 快递 / 新闻 -> 拼 prompt -> 调大模型 -> 清洗 Markdown。
 *
 * ## 日志
 *
 * 这条链路的每一站都打点（`BriefPlayback` 标签）。原因是它**几乎没有可视反馈**：
 * 桌面只有一枚会变色的小点阵，用户听见/听不见之间差别巨大，而"点了没反应"这件事
 * 从界面上完全看不出卡在哪一步 —— 只有日志答得了"到底播没播"。
 */
class BriefPlaybackService : Service() {

    private data class PendingUtterance(val text: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer

    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var pendingUtterance: PendingUtterance? = null
    private var speaking = false
    private var finishing = false

    /**
     * 尾巴走完之后的收尾只做一次：撤前台通知、结束服务。
     *
     * 两条路径都会来碰它（[BgmPlayer.tailOut] 的播完回调 / [finish] 里的兜底 delay），
     * 所以靠这个标志保证幂等。
     */
    private var tailEnded = false

    /** 取数 + 调模型这段在飞。用它防连点：生成中再点一次应当**打断**，而不是再发一次请求。 */
    private var generationJob: Job? = null

    /**
     * 云端引擎是否处于「暂停」。
     *
     * 只有豆包（MediaPlayer）路径支持真正的暂停/续播 —— 系统 TextToSpeech 没有 pause API，
     * 只能彻底停。所以这个标记只在 [TtsProvider.DOUBAO] 时被置位/清除。
     * 它不落盘：暂停是服务内存里的瞬态，进程被回收后本就该从头再来。
     */
    private var paused = false

    /**
     * 云端引擎（豆包）的合成任务。
     *
     * 单独一个 Job 而不是并进 [generationJob]，是因为两段是可分离的：
     * 正文已经生成好了、正在合成语音时打断，只需要掐合成，不必重跑模型。
     * 但**防连点的判断必须把两者都算上**，否则用户在"正在合成"的空档连点两下会并发合成两次。
     */
    private var synthesisJob: Job? = null

    private var cloudPlayer: CloudTtsPlayer? = null

    /**
     * 背景音乐（`res/raw/bgm.mp3`）。
     *
     * 两件事：等待模型的那几秒先放它当缓冲音（那段原来完全静默），
     * 真正开口之后压到垫底音量。详细理由见 [BgmPlayer]。
     * 用户在设置里关掉开关则整段都不放。
     */
    private var bgmPlayer: BgmPlayer? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    // ---- 播报进度：桌面点阵靠它把"念过的部分"褪成灰 ----

    /** 本次走的是哪条朗读路径。进度取数方式按它分流，见 [currentProgress]。 */
    private var engine: TtsProvider = TtsProvider.SYSTEM

    /**
     * 本次正文的字数：没有分段回调时按它估算总时长。
     *
     * `@Volatile` 的理由与 [rangeProgress] 一样 —— 它是在主线程写的，
     * 却会被引擎的进度回调线程读（`onRangeStart` 里拿它当分母）。
     */
    @Volatile
    private var speechChars = 0

    /** 真正开口的时刻（`SystemClock.elapsedRealtime()`）。用单调时钟，不受改系统时间影响。 */
    private var speechStartedAt = 0L

    /**
     * 系统 TTS 通过 `onRangeStart` 报上来的精确进度。
     *
     * 写它的是**引擎的进度回调线程**，读它的是主线程上的 ticker，所以必须是 `@Volatile`。
     * 回调只更新这个数字，绝不直接去推桌面 —— 逐字回调意味着每秒几十次，
     * 那样会把桌面刷爆（每刷一次要重画一整轮动画帧）。
     */
    @Volatile
    private var rangeProgress = 0f

    private var progressJob: Job? = null

    /** 整条链路（生成 + 合成 + 朗读）是否还在进行中。 */
    private val busy: Boolean
        get() = speaking || generationJob?.isActive == true || synthesisJob?.isActive == true

    override fun onCreate() {
        super.onCreate()
        container = (application as BriefWidgetApp).container
        audioManager = getSystemService(AudioManager::class.java)
        cloudPlayer = CloudTtsPlayer(this)
        bgmPlayer = BgmPlayer(this)

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_preparing)))

        speech = TextToSpeech(this) { status -> onSpeechInitialized(status) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action busy=$busy")

        // 从桌面点进来的先震一下当"收到了"的回执（见 [buzz]）。
        if (intent?.getBooleanExtra(BriefWidgetRenderer.EXTRA_FROM_WIDGET, false) == true) {
            buzz()
        }

        when (action) {
            // 用户按了停止：要的是"现在就安静"，不能让 BGM 浮上来陪两秒
            // （见 [finish] 的 naturalEnd）。
            ACTION_STOP -> finish()
            // 正在朗读、正在合成、或正在生成，都视为"再点即打断"。
            // 少了后面两半，用户在等待时连点两下就会并发发起两次模型请求 / 两次合成。
            //
            // 开始播报只有这一条路径，而且它**缓存优先**：定时任务刷好的内容还新鲜就直接念。
            // 以前这里还有一条 ACTION_REFRESH（绕开缓存、当场重问一次模型），
            // 现在两个入口合并成了一个 —— 理由见 SettingsViewModel.playBriefNow。
            else -> {
                // 定时播报（闹钟）走同一条出声链路，但缓存新鲜度判据不同：
                // 它不看"最近一个刷新时刻"，而是"缓存是否 ≤4 小时"。见 startBrief 的 isAlarm。
                val isAlarm = intent?.getBooleanExtra(EXTRA_ALARM, false) == true
                when {
                    // 豆包正在播：再点 = 暂停/续播（从暂停处继续，不重新合成、不从头念）。
                    // 只有这条路支持暂停 —— 系统 TTS 没有 pause API，见 [paused] 的说明。
                    speaking && engine == TtsProvider.DOUBAO && cloudPlayer?.isActive == true ->
                        togglePause()

                    // 其余 busy（生成中 / 合成中 / 系统引擎播放中）：保持"再点即打断"。
                    busy -> finish()

                    else -> startBrief(isAlarm)
                }
            }
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
        bgmPlayer?.stop()
        abandonAudioFocus()

        if (wasBusy) {
            val app = application as? BriefWidgetApp ?: return
            app.appScope.launch {
                runCatching { container.runtimeStateStore.setState(WidgetState.IDLE) }
                BriefWidgetProvider.applyState(app, WidgetState.IDLE, false)
                // 被回收/划掉时进度还停在半路，不清掉的话下一次播报会从半灰起播
                BriefWidgetProvider.resetProgress()
            }
        }
    }

    // ------------------------------------------------------------------
    // 流程
    // ------------------------------------------------------------------

    private fun startBrief(isAlarm: Boolean = false) {
        // 新一轮开始，上一轮的收尾标志必须全部清掉。
        //
        // `finishing` 不清会**静默吃掉这一轮**：它只在 finish/reportError 里置 true，
        // 而生成完成后有一处 `if (finishing) return@launch` 的检查 —— 于是上一轮播完
        // 之后的第二次现场生成会走到那里直接返回，用户点了没声音。
        finishing = false
        tailEnded = false
        paused = false
        generationJob = scope.launch {
            val store = container.runtimeStateStore

            // 进程刚被小组件点起来时，Application 里那次迁移可能还在飞。
            // 这里补一次（幂等）再读配置，保证本次请求用的就是修正后的超时值。
            runCatching { container.settingsRepository.ensureMigrations() }

            val settings = container.settingsRepository.snapshot()

            // 从点下去到真正出声之间有几秒（取数 + 问模型 + 合成语音），
            // 原先这段时间完全静默。先放背景音乐把它垫过去：它既是
            // “我在响应你”的回执，也免得用户以为没点到而再点一次 ——
            // 那第二下正好是“打断”。开口之后由 [onPlaybackStarted] 压到垫底音量。
            if (settings.tts.bgmEnabled) bgmPlayer?.start()

            val cached = store.snapshot()

            // ---- 先看有没有现成的可以念 ----
            //
            // 这是"一天最多请求三次模型"的落点：定时任务（10:00 / 14:00 / 19:00，
            // 用户可改）已经把内容刷好了，用户点一下就是**立刻出声**，不再等模型几秒。
            //
            // 判断"还算不算新鲜"整体交给 [BriefSchedule]：它与后台排期用的是同一条
            // 锚点规则，所以不会出现"后台按三点刷、点击却认为两点那份还新鲜"这种错位。
            //
            // 定时播报（isAlarm）用另一套判据：不看刷新锚点，只看缓存年龄 ——
            // ≤ ALARM_FRESH_WINDOW_SECONDS（4 小时）就直接念，更旧就现场生成。
            // 否则用户 7 点设的闹钟会念到昨天 19:00 那份（如果 updateTimes 是 10/14/19）。
            if (cached.lastBriefText.isNotBlank()) {
                val now = ZonedDateTime.now()
                val fresh = if (isAlarm) {
                    val ageSeconds = java.time.Instant.now().epochSecond - cached.lastBriefAtEpochSeconds
                    ageSeconds <= ALARM_FRESH_WINDOW_SECONDS
                } else {
                    BriefSchedule.isCacheFresh(
                        cachedAtEpochSeconds = cached.lastBriefAtEpochSeconds,
                        now = now,
                        times = settings.brief.updateTimes,
                    )
                }
                if (fresh) {
                    Log.i(TAG, "命中缓存（生成于 ${cached.lastBriefAtEpochSeconds}），直接播报，不请求模型")
                    // 缓存是上一个时刻生成的，正文开头那句"现在是……"必须换成此刻，
                    // 否则十点刷出来的内容十一点播，会念成"现在是上午十点"。
                    // includeDate 用与现场生成同一判据：今天还没报过日期才带日期。
                    val includeDate = cached.lastGreetedDay != LocalDate.now().toEpochDay()
                    val text = BriefClock.refresh(cached.lastBriefText, includeDate, LocalDateTime.now(), isAlarm = isAlarm)
                    // 语音缓存 key 用**原文**（refresh 前的 lastBriefText）而不是 text：
                    // 这样同一条正文的语音能被复用（见 speakWithCloud 里 cacheKey 的说明）。
                    // 内容早就备好了，从点击到现在可能只过了几百毫秒 ——
                    // 开场音乐按固定时长补齐再开口（见 awaitBgmIntro）。
                    awaitBgmIntro()
                    speak(text, settings.tts, cacheKey = cached.lastBriefText)
                    return@launch
                }
                Log.i(TAG, "缓存已过期（生成于 ${cached.lastBriefAtEpochSeconds}），重新生成")
            }

            // ---- 没有可复用的，现场生成 ----
            store.setState(WidgetState.GENERATING)
            BriefWidgetProvider.applyState(this@BriefPlaybackService, WidgetState.GENERATING, false)
            updateNotification(getString(R.string.notif_generating))

            val outcome = try {
                container.generateBrief.execute(
                    if (isAlarm) GenerateBriefUseCase.SOURCE_ALARM else GenerateBriefUseCase.SOURCE_WIDGET,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "生成简报时抛出未捕获异常", error)
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

            // 降级交付（模型限流/断网，退了本地简报）：这是**已经出了声**的一次，
            // 所以不进 ERROR 状态，但原因要留下 —— 用户会奇怪"今天怎么没有快讯"。
            val notice = (outcome as? BriefOutcome.Success)?.notice
            if (notice != null) {
                Log.w(TAG, "本次为降级内容：$notice")
                runCatching { store.recordIssue(notice) }
                // 这是一条"已经出了声"的路径，用户不会以为自己失败了，
                // 但"今天怎么没有快讯"只有这句话解释得清 —— 当场说一句，别让他去设置页翻。
                toastIssue(this@BriefPlaybackService, notice)
            }

            Log.i(TAG, "生成完成，正文 ${text.length} 字")
            store.saveBrief(text)
            // 取数 + 问模型早把开场音乐响过去了（这里通常是 0，不用等）；
            // 但模型秒回、或退到本地简报时，也要保证那个开场节拍。
            awaitBgmIntro()
            speak(text, settings.tts)
        }
    }

    /**
     * 把 BGM 的开场补足到 [BgmPlayer.INTRO_MS]（没开 BGM / 没在播时立刻返回）。
     *
     * 为什么要有这一道闸：命中缓存时正文和语音**都在缓存里**，从点击到出声只要几百毫秒，
     * 音乐刚冒头就被 [BgmPlayer.duck] 压低 —— 听上去像被掐了一下，也丢掉了
     * "我在响应你"这个回执。所以开场时长不能靠"等模型那几秒"顺带实现，
     * 它得是所有出声路径开口前都要过的一道闸。
     *
     * 只等差额：现场生成时模型已经花掉几秒，这里自然是 0，播报不会变慢。
     */
    private suspend fun awaitBgmIntro() {
        val wait = bgmPlayer?.introRemainingMs() ?: 0L
        if (wait <= 0L) return
        Log.i(TAG, "开场未满，BGM 再响 ${wait}ms 才进人声")
        delay(wait)
    }

    // ------------------------------------------------------------------
    // 朗读：按引擎分流
    // ------------------------------------------------------------------

    /**
     * 两条完全不同的路径：
     * - [TtsProvider.SYSTEM] 把文本交给系统 TextToSpeech，它自己念，进度靠 `onRangeStart` 回调
     *   （引擎不给这个回调时按字数估算，见 [estimatedProgress]）；
     * - 云端引擎先合成出一段 mp3，再用 MediaPlayer 播，进度读播放位置。
     *
     * 对外表现必须一致：都在**真正出声那一刻**把桌面切到 PLAYING，
     * 都在念完后回 IDLE。否则用户会看到"动效开始了但没声音"或反之。
     */
    private fun speak(text: String, tts: TtsConfig, cacheKey: String? = null) {
        Log.i(TAG, "开始朗读，引擎=${tts.provider}，正文 ${text.length} 字")
        // 进度取数要按引擎分流，所以这两样必须记在字段上（ticker 是另一条协程）。
        engine = tts.provider
        speechChars = text.length
        when (tts.provider) {
            TtsProvider.SYSTEM -> speakWithSystem(text)
            TtsProvider.DOUBAO -> speakWithCloud(text, tts, cacheKey)
        }
    }

    private fun speakWithCloud(text: String, tts: TtsConfig, cacheKey: String?) {
        synthesisJob = scope.launch {
            updateNotification(getString(R.string.notif_synthesizing))

            // ---- 先查语音缓存 ----
            // 缓存的 key 用"这条内容的原文"（cacheKey，通常就是 [RuntimeStateStore] 里的
            // lastBriefText），而不是合成输入 text —— 因为命中正文缓存时 text 已被
            // BriefClock.refresh 换过开场时间句，拿它当 key 每次都不一样，缓存永远命不中。
            val key = cacheKey ?: text
            val cached = container.speechCache.get(key)
            val audio: ByteArray
            if (cached != null) {
                Log.i(TAG, "命中语音缓存（${cached.size} 字节），跳过合成")
                updateNotification(getString(R.string.notif_playing_text))
                audio = cached
            } else {
                val result = try {
                    container.doubaoTtsClient.synthesize(tts, text)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Log.w(TAG, "语音合成时抛出未捕获异常", error)
                    SpeechResult.Failure("语音合成出错：${error.javaClass.simpleName}")
                }

                if (finishing) return@launch

                audio = when (result) {
                    is SpeechResult.Success -> {
                        // 合成成功顺手写缓存，下次同一条内容（key 相同）就直接播
                        container.speechCache.put(key, result.audio)
                        result.audio
                    }

                    is SpeechResult.Failure -> {
                        reportError(result.message)
                        return@launch
                    }
                }

                Log.i(TAG, "合成完成，${audio.size} 字节，交给播放器")
            }

            requestAudioFocus()
            cloudPlayer?.play(
                audio,
                object : CloudTtsPlayer.Callback {
                    override fun onStart() {
                        if (finishing) return
                        onPlaybackStarted()
                    }

                    // 人声自己念完了 —— 属于"自然结束"，让 BGM 浮上来交代结尾。
                    override fun onCompletion() = finish(naturalEnd = true)

                    override fun onError(message: String) = reportError(message)
                },
            )
        }
    }

    private fun speakWithSystem(text: String) {
        val engine = speech
        if (engine == null || !speechReady) {
            // TTS 初始化是异步的，先排队，等 onInit 回来再补播
            Log.i(TAG, "系统语音引擎还没就绪，先把这一段排进待播队列")
            pendingUtterance = PendingUtterance(text)
            return
        }

        requestAudioFocus()
        // 有意不调 setSpeechRate / setPitch：语速音调已不是设置项，一律用引擎默认值。
        // 曾经这里有 `setSpeechRate(tts.speechRate)`，而存量用户的 DataStore 里
        // 可能存着 2.0（滑块拉到底留下的），表现为"念得飞快"却找不到原因 ——
        // 现在这两个值根本读不到，也就不会再出现那种情况。
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
            speakWithSystem(request.text)
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
     * [com.augustana.dotbrief.data.settings.RuntimeStateStore.markGreeted]。
     */
    private fun onPlaybackStarted() {
        speaking = true
        // 人声出来了，背景音乐退到垫底音量（见 [BgmPlayer.duck]）。
        bgmPlayer?.duck()
        Log.i(TAG, "已经开口（onStart 回调），桌面切到 PLAYING")

        // 进度必须在切布局**之前**复位：桌面一拿到 playing 布局就会去绑 adapter 取帧，
        // 那一刻读到的进度必须已经是 0，否则第一帧画的是上一轮的残留进度。
        BriefWidgetProvider.resetProgress()
        rangeProgress = 0f
        speechStartedAt = SystemClock.elapsedRealtime()

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

        startProgressTicker()
    }

    // ------------------------------------------------------------------
    // 播报进度：桌面上那枚点阵"念到哪儿就灰到哪儿"
    // ------------------------------------------------------------------

    /**
     * 播报期间定时把进度推给桌面。
     *
     * 节奏由这里统一管，不让两条引擎路径各自去推：系统 TTS 的 `onRangeStart` 可能**逐字**
     * 回调（每秒几十次），而 MediaPlayer 那条只能轮询 —— 两边频率差两个数量级，
     * 放任它们各自通知桌面，桌面重画的次数就会随引擎而变，行为没法推理。
     *
     * 取 [PROGRESS_TICK_MS] 这个量级（约一秒一档）是拿观感换开销：简报通常几十秒，
     * 会走二三十档，看上去是连续褪色；而每档都要让桌面重画一轮动画帧
     * （14 或 56 张位图），再密就不划算了。
     */
    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (speaking) {
                delay(PROGRESS_TICK_MS)
                if (!speaking) break
                publishProgress(currentProgress())
            }
        }
    }

    /** 当前进度（0..1）。两条路径的取数方式完全不同，所以按引擎分流。 */
    private fun currentProgress(): Float = when (engine) {
        // 引擎给了分段回调就用它的精确值，没给就按字数估算。
        // 取两者较大值而不是二选一：估算只做兜底，但它能保证进度**单调前进** ——
        // 万一某个引擎的回调又慢又稀（甚至中途断掉），点阵不会卡在半路不动。
        TtsProvider.SYSTEM -> maxOf(rangeProgress, estimatedProgress())
        TtsProvider.DOUBAO -> cloudPlayer?.progress() ?: 0f
    }

    /**
     * 没有分段回调时的兜底：按"已经念了多久 / 大约要念多久"估。
     *
     * [CHARS_PER_SECOND] 有意取得比实际语速慢一点：估慢了只是点阵走得比声音慢半拍，
     * 而估快了会在声音还没停时就把整块点阵褪成灰 —— 那是"已经播完了"的样子，
     * 比慢半拍糟糕得多。
     */
    private fun estimatedProgress(): Float {
        if (speechChars <= 0) return 0f
        val elapsedMs = SystemClock.elapsedRealtime() - speechStartedAt
        val totalMs = speechChars * 1000f / CHARS_PER_SECOND
        return elapsedMs / totalMs
    }

    private fun publishProgress(raw: Float) {
        // 封顶 [MAX_VISIBLE_PROGRESS]，把最后那一截灰留给"播报结束、回待机"那一帧。
        // 否则进度先跑到 1，声音还在响点阵就已经全灰，看起来像提前结束了。
        val value = raw.coerceIn(0f, MAX_VISIBLE_PROGRESS)
        BriefWidgetProvider.applyProgress(this, value)
    }

    /**
     * 从桌面点进来时那一下短震。
     *
     * 只有带 [BriefWidgetRenderer.EXTRA_FROM_WIDGET] 的请求才震 —— 也就是只有小组件。
     * 设置页里的按钮不震：那边的人正看着屏幕，点完立刻有状态变化可看。
     *
     * 不需要任何运行时授权（`VIBRATE` 是 normal 权限），也不受系统"触感反馈"开关约束 ——
     * 那个开关管的是 View 的 haptic feedback，这里是直调 Vibrator。
     * 所有异常一律咽掉：震动失败绝不该影响播报。
     */
    private fun buzz() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }
    }

    /**
     * 暂停 ↔ 续播切换（只对豆包云端引擎生效，见 [paused]）。
     *
     * 暂停不是结束：服务继续跑前台、播放器保持 [CloudTtsPlayer] 的 player 字段不动，
     * 只是把音频停住；续播时从同一位置接着念。所以这里**绝不**调 [finish] ——
     * finish 会释放播放器，那"从暂停处继续"就无从谈起。
     *
     * 桌面视觉：暂停时点阵停在当前进度、静止（彩色，因为还没听完，`unheard` 仍是 true）；
     * 续播时恢复呼吸。这两次都走 [BriefWidgetProvider.applyState] 直推桌面，
     * **不落 DataStore** —— 暂停是内存瞬态，进程被杀后本就该从头再来。
     */
    private fun togglePause() {
        val player = cloudPlayer ?: return
        if (paused) {
            paused = false
            player.resume()
            // 音乐也跟着回来：暂停时它一起停住了，不接着放会前后不一致。
            bgmPlayer?.resume()
            Log.i(TAG, "续播：从暂停处继续")
            BriefWidgetProvider.applyState(this, WidgetState.PLAYING, speaking = true)
            updateNotification(getString(R.string.notif_playing_text))
            startProgressTicker()
        } else {
            paused = true
            player.pause()
            // 音乐必须一起停：只停人声的话，垫底音量的人声没了、音乐还在循环 ——
            // 用户听到的是"暂停没生效"（真机抓过）。
            bgmPlayer?.pause()
            Log.i(TAG, "暂停播报")
            progressJob?.cancel()
            BriefWidgetProvider.applyState(this, WidgetState.IDLE, speaking = false)
            updateNotification(getString(R.string.notif_paused_text))
        }
    }

    /**
     * 收尾：回 IDLE、放掉音频焦点、撤掉前台通知、结束服务。
     *
     * @param naturalEnd 是不是"人声自己念完了"（而不是用户叫停 / 再点一次打断）。
     *   这个区别**只**影响 BGM 的尾巴：念完了，音乐该浮上来把最后两秒交代完
     *   （见 [BgmPlayer.tailOut]）；而用户按了停止，他要的是"现在就安静" ——
     *   那时绝不能走同一条路，那会把音乐从垫底**提上来**再响两秒，等于跟用户对着干。
     */
    private fun finish(naturalEnd: Boolean = false) {
        if (finishing) return
        finishing = true
        Log.i(TAG, "播报结束，回到待机")

        // 生成中 / 合成中被打断：把在飞的取数与模型请求一起掐掉
        generationJob?.cancel()
        synthesisJob?.cancel()
        progressJob?.cancel()

        speaking = false
        speech?.stop()
        cloudPlayer?.stop()
        if (naturalEnd) {
            // 念完了：音乐浮上来再陪 2.5 秒才收（见 BgmPlayer.tailOut）。
            //
            // ⚠️ 这里**不能**改成"延迟两秒再 stop"：那个延迟在息屏 / 进程冻结时不执行，
            // 而音频在系统媒体服务里照放 —— 用户看到的就是"音乐一直循环，只有打开应用才停"
            // （真机复现过）。tailOut 把"什么时候没声音"交给了音频自己。
            // 没开 BGM 开关时它会立刻回调，服务照常收尾。
            bgmPlayer?.tailOut { endAfterTail() }
        } else {
            // 用户叫停（或再点一次打断）：立即静音，然后马上收尾。
            // stop() 是"立刻静音 + release"，没有"播完"这个事件可等，所以这里直接收。
            bgmPlayer?.stop()
            endAfterTail()
        }
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
            // 切回静态布局**之后**才复位：那时 flipper 已经不在任何一份 RemoteViews 里，
            // 再对它调 data-changed 只会让系统白跑一趟并打 warning
            // （同样的判断见 BriefWidgetProvider.push）。
            BriefWidgetProvider.resetProgress()
            // 收尾由 BGM 播完的回调驱动，这里只是兜底（音频异常、回调没来）。
            // 期间用户又点了播报（busy）就别收 —— 那是新的一轮。
            delay(BgmPlayer.TAIL_HOLD_MS + 1000)
            if (!busy) endAfterTail()
        }
    }

    /**
     * 尾巴走完之后才做的事：撤掉前台通知、结束服务。
     *
     * 抽出来是因为它有**两个触发点**：BGM 播完的完成回调（正常路径）和
     * [finish] 里的兜底 delay。上一版只有后者，而那个 delay 在息屏 /
     * 进程被冻结时不会执行，服务就一直挂着、前台通知也一直在。
     */
    private fun endAfterTail() {
        if (tailEnded) return
        tailEnded = true
        scope.launch {
            runCatching {
                ServiceCompat.stopForeground(
                    this@BriefPlaybackService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                stopSelf()
            }
        }
    }

    private fun reportError(message: String) {
        if (finishing) return
        finishing = true
        // 这一行是"点了没声音"的第一现场。以前失败只写进桌面的一个小红点，
        // logcat 里一个字都没有，排查时只能靠猜。
        Log.w(TAG, "播报失败：$message")

        // 全应用最该"当场说话"的一条失败：用户点了一下，什么都没听见。
        // 原来原因只落在设置页最底下那行红字里，等于没有 —— 现在直接弹在屏幕上。
        toastIssue(this, message)

        synthesisJob?.cancel()
        progressJob?.cancel()
        speaking = false
        speech?.stop()
        cloudPlayer?.stop()
        bgmPlayer?.stop()
        abandonAudioFocus()

        scope.launch {
            runCatching { container.runtimeStateStore.setError(message) }
            BriefWidgetProvider.applyState(this@BriefPlaybackService, WidgetState.ERROR, false)
            // 失败要让下一次点击从头来过：进度留着的话，第二次播报会从半灰起播
            BriefWidgetProvider.resetProgress()
            ServiceCompat.stopForeground(this@BriefPlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------------
    // 语音回调：这里决定桌面上动效什么时候开始、什么时候停
    // ------------------------------------------------------------------

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) = onPlaybackStarted()

        /**
         * 引擎念到第几个字了。
         *
         * 这里**只记数字、不推桌面** —— 这个回调可能逐字触发，每次都去通知桌面重画，
         * 等于播报期间一直有跨进程写入。真正的推送节奏交给 [startProgressTicker]。
         *
         * 注意这不是抽象方法，部分引擎根本不实现它，所以它只用来"提精度"，
         * 不承担兜底（兜底是 [estimatedProgress]）。
         */
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (speechChars <= 0) return
            rangeProgress = end.toFloat() / speechChars
        }

        /** 系统 TTS 念完了 —— 与云端那条路一样，属于"自然结束"。 */
        override fun onDone(utteranceId: String?) = finish(naturalEnd = true)

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
        const val ACTION_TOGGLE = "com.augustana.dotbrief.action.TOGGLE_BRIEF"

        const val ACTION_STOP = "com.augustana.dotbrief.action.STOP_BRIEF"

        /**
         * 定时播报（闹钟）标记。带上它时，缓存新鲜度不看刷新锚点、只看"是否 ≤4 小时"，
         * 见 [startBrief] 的 isAlarm 分支。
         */
        const val EXTRA_ALARM = "com.augustana.dotbrief.extra.ALARM"

        /**
         * 定时播报的缓存新鲜窗口：≤4 小时直接念现成的，更旧就现场重新生成。
         * 4 小时足够覆盖"闹钟时刻紧跟在一次自动刷新之后"的常见情况，
         * 又不会让用户早上 7 点听到昨晚 19 点的旧闻。
         */
        private const val ALARM_FRESH_WINDOW_SECONDS = 4L * 60L * 60L

        private const val TAG = "BriefPlayback"
        private const val CHANNEL_ID = "brief_playback"
        private const val NOTIFICATION_ID = 0x2101
        private const val UTTERANCE_ID = "brief-playback"

        /** 点小组件时那一下短震的时长。再长就从"回执"变成"打扰"了。 */
        private const val BUZZ_MS = 25L

        /** 进度的采样间隔。为什么是这个量级见 [startProgressTicker]。 */
        private const val PROGRESS_TICK_MS = 1200L

        /** 进度展示的上限：最后那一截灰交给"回待机"那一帧。见 [publishProgress]。 */
        private const val MAX_VISIBLE_PROGRESS = 0.92f

        /**
         * 系统 TTS 中文的默认语速（字/秒），用于没有分段回调时估算进度。
         *
         * 3.5 是**故意偏低**的：真实默认语速大约 4~5 字/秒，估慢一点点阵只是走得比声音
         * 慢半拍，而估快了会把点阵提前褪成"念完了"的样子 —— 宁可慢，不可快。
         */
        private const val CHARS_PER_SECOND = 3.5f
    }
}
