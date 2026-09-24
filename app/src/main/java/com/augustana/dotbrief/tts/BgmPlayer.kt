package com.augustana.dotbrief.tts

import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.augustana.dotbrief.R

/**
 * 播报用的背景音乐（`res/raw/bgm.mp3`）。
 *
 * ## 它要解决的两个问题
 *
 * 1. **等待的那几秒不是静默的**：点下小组件到真正出声之间，要取数、要问模型、要合成语音，
 *    短则一两秒、长则十来秒。这段时间原来**一点声音都没有**，用户只能靠桌面上那枚
 *    转圈的点阵判断"它在干活"，点了没反应就再点一次 —— 而第二下正好是"打断"。
 *    所以这一段**放开音量**播：它既是"我在响应你"的回执，也把这段等待垫过去了。
 *
 *    而且这**不是"等得久才有"的副产品**：命中缓存时从点击到出声只要几百毫秒，
 *    音乐刚冒头就会被压低。所以开场有一个固定的节拍 —— 见 [DRUM_DROP_MS]，
 *    由调用方用 [introRemainingMs] 等满。
 * 2. **人声底下有一层垫音**：真正开口时把音乐压到 [DUCKED_VOLUME]，让它退到背景里。
 *    念完之后不立刻掐掉，先再陪 [TAIL_HOLD_MS]，然后才收掉 ——
 *    人声一落音乐就断，听上去像"播报被砍了尾巴"。
 *
 * ## 三档音量，各对应一段明确的听感
 *
 * 缓冲段是**唯一没有人声**的一段，音乐是全部信息，所以给到满音量；
 * 人声一进来，音乐只剩"垫底"这一个职责，再大声就是抢注意力；
 * 人声退场之后又回到 [TAIL_VOLUME] —— 最后这几秒是要让人**听见**的，
 * 不能继续用垫底那档（否则听到的就是"跟着人声一起没了"）。
 * 每一次切换都必须是渐变（见 [fadeTo]），直接切会有塌陷感。
 *
 * ## 为什么是"压低"而不是"停掉"
 *
 * 播报期间音乐突然消失是一种**中断感**，听上去像出了故障；压低则是"退到后面去"，
 * 是同一段体验的延续。这也是 `requestAudioFocus` 用 `MAY_DUCK` 而不是 `GAIN` 的同一个理由。
 *
 * ## ⚠️ 收尾不能靠定时器（真机踩过，这是本类的核心约束）
 *
 * 上一版是"念完之后 `postDelayed` 2.5 秒再 [stop]"。真机上根本停不下来：
 * 日志里 09:19:06 已经打出"播报结束，回到待机"，而 `MediaPlayer` 直到 09:19:24
 * 用户打开设置页才 disconnect —— 中间那 18 秒音乐一直在循环。
 *
 * 原因：**声音是系统媒体服务在放，Java 层的定时器却要等本进程被调度。**
 * 屏幕熄灭 / CPU 休眠 / 进程被系统冻结时，`Handler.postDelayed` 和协程 `delay`
 * 都不会执行，而音频早已交给 DSP 照样在响。用"应用里的闹钟"去关"系统里的声音"，
 * 本来就关不掉 —— 用户看到的正是"只有打开应用它才停"。
 *
 * 所以收尾改成**让音频自己到点播完**：停掉循环、把播放位置挪到"距曲尾
 * [TAIL_HOLD_MS] 处"，剩下这几秒由系统放完，播完自然静音，
 * 全程不需要进程醒着。收尾**不再做音量淡出**（改成浮到 [TAIL_VOLUME]）——
 * 淡不淡只是观感，"到点没声"这条底线由音频自己保证。
 *
 * ## ⚠️ 音量也不能只靠动画（同一个坑摔了第二次）
 *
 * 起播时把音量设成 0、再靠 `ValueAnimator` 淡入上来 —— 这和上面那条定时器是
 * **同一类错误**：默认应用能一直拿到执行机会。息屏 / 应用没有可见窗口时，
 * 系统不再派发 VSYNC，动画一帧都不走，音量就永远停在 0 ——
 * 用户听到的是"人声正常，但一点 BGM 都没有"（人声不走动画设音量，所以毫发无伤）。
 *
 * 真机证据在 [fadeTo] 的注释里。规矩只有一句：**音量是结果，动画只是观感。**
 * 所以 [fadeTo] 总是先把音量落到目标值、再让动画从旧值重放一遍渐变，
 * 起播也从 [FADE_FLOOR] 而不是 0 起步 —— 没有例外。
 *
 * ## 循环
 *
 * 音频只有约 62 秒，而"缓冲 + 播报"常常超过它，所以缓冲/播报期间 `isLooping = true`。
 * 循环点上的接缝是这个方案唯一的妥协，但比起念到一半音乐戛然而止要好得多。
 * 走尾巴时会先把循环关掉，否则"挪到曲尾"这一招会被循环吃掉。
 *
 * ## 线程
 *
 * 播报完成的回调（`UtteranceProgressListener.onDone` / `MediaPlayer.OnCompletionListener`）
 * **不保证在主线程**。`ValueAnimator` 没有 Looper 就跑不起来，所以所有对外方法都先
 * post 到主线程再干活。
 */
class BgmPlayer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * 这一轮开场是**什么时候响起来的**（`SystemClock.elapsedRealtime()`）。
     *
     * 0 表示没在播（BGM 关掉了、或起播失败）—— 那时 [introRemainingMs] 返回 0，
     * 调用方就不必为一段并不存在的开场白等下去。
     */
    @Volatile
    private var introAtMs = 0L

    private var player: MediaPlayer? = null

    /** 正在淡出的那个播放器：还在响，但已经不归 [player] 管了，只等渐变结束就释放。 */
    private var releasing: MediaPlayer? = null

    private var animator: ValueAnimator? = null

    /** 当前音量，渐变动画的中间值也写在这里，[fadeTo] 提前返回时用得上。 */
    private var volume = 0f

    /** 看门狗：任何一条收尾路径漏掉时，到点强制静音。 */
    private var watchdog: Runnable? = null

    /** 走尾巴期间挂在这里的收尾回调（服务用它撤前台、结束自己）。 */
    private var onTailDone: (() -> Unit)? = null

    /** 已经在走尾巴：循环已关、播放位置已挪到曲尾附近。 */
    private var tailing = false

    /**
     * 开始播放（缓冲阶段）：从 [FADE_FLOOR] 淡入到 [FULL_VOLUME]。
     *
     * 已经在播就只把音量拉回 [FULL_VOLUME] —— 那是"命中缓存、几乎立刻开口"的情形，
     * 音乐刚起来就压低，中间不该再有一次从头起播的接缝。
     */
    fun start() {
        // 起播时刻在这里就记下，不等到主线程：`MediaPlayer.create` 回来之前
        // [introRemainingMs] 只能按墙钟估，而调用方是轮询着等的（见 awaitBgmIntro）——
        // 主线程队列排的那点时间不该被当成"音乐已经响过了"。
        //
        // 顺带解决一个竞态：命中缓存时会**立刻**来问 [introRemainingMs]（那一刻
        // startInternal 可能还排在主线程队列里没跑），这里先乐观记上，
        // "还没起播"就不会被误判成"不用等"。
        introAtMs = SystemClock.elapsedRealtime()
        main.post { startInternal() }
    }

    /**
     * 距"人声该开口的那一刻"还差多少毫秒；没在播时返回 0。
     *
     * 判据分两段，顺序不能反：
     *
     * 1. **已经真正起播** —— 以音频自己的播放位置为准（`currentPosition`）。
     *    墙钟在这里不够用：[start] 里记时刻那一下排在 `MediaPlayer.create` **之前**，
     *    而音乐是从 create 回来之后才开始走的。拿墙钟算，人声会稳定地早到
     *    "create 耗时"那么多 —— 对"卡鼓点"来说，那就是没卡上。
     * 2. **还没起播**（create 还在路上）—— 退回墙钟估。调用方是轮询着等的
     *    （见 `BriefPlaybackService.awaitBgmIntro`），一旦起播就自动切到上一条。
     *
     * 两段都减 [VOICE_LEAD_MS]，所以切换那一刻不会有跳变。
     * 调用方拿到正数就等这么久再开口 —— 这对**所有**路径一视同仁：
     * 现场生成时模型已经花掉十来秒，这里自然是 0，播报不会因此变慢。
     */
    fun introRemainingMs(): Long {
        player?.let { media ->
            val position = runCatching { media.currentPosition.toLong() }.getOrDefault(-1L)
            if (position >= 0L) {
                return (DRUM_DROP_MS - VOICE_LEAD_MS - position).coerceAtLeast(0L)
            }
        }
        val at = introAtMs
        if (at == 0L) return 0L
        val elapsed = SystemClock.elapsedRealtime() - at
        return (DRUM_DROP_MS - VOICE_LEAD_MS - elapsed).coerceAtLeast(0L)
    }

    /**
     * 音乐现在播到哪儿了（毫秒）；没在播返回 -1。
     *
     * 只给日志用：**人声开口的那一刻打一次**，读到的数就是"卡鼓点差了多少"。
     * 期望值是 [DRUM_DROP_MS]；比它小多少，就把 [VOICE_LEAD_MS] 加多少。
     */
    fun positionMs(): Long {
        val media = player ?: return -1L
        return runCatching { media.currentPosition.toLong() }.getOrDefault(-1L)
    }

    /** 压到垫底音量（人声开口那一刻）。 */
    fun duck() {
        main.post {
            if (player == null) return@post
            fadeTo(DUCKED_VOLUME, DUCK_MS)
        }
    }

    /**
     * 暂停。用户点暂停时音乐**必须跟着停**。
     *
     * 漏掉这个的话，语音一停、只剩垫底音量的人声没了、音乐还在循环 ——
     * 听起来就是"暂停没生效"（真机日志里抓到过：09:26:43 暂停播报，
     * 语音停了而 BGM 还在放）。
     */
    fun pause() {
        main.post {
            if (player == null) return@post
            runCatching { player?.pause() }
        }
    }

    /** 从暂停处继续（音量保持暂停前的值，不重新淡入）。 */
    fun resume() {
        main.post {
            val media = player ?: return@post
            runCatching { media.start() }
        }
    }

    /**
     * 立即静音并释放。出错、服务销毁走这条。
     *
     * 刻意**不做淡出**：这两条路径要的是"现在就安静"，而淡出要靠动画帧驱动 ——
     * 一旦进程随后被冻住，动画停在半路、音量停在半路，音乐就继续响下去了。
     * 可靠性优先于平滑。
     */
    fun stop() {
        main.post { stopInternal() }
    }

    /**
     * 人声念完之后的收尾：再陪 [TAIL_HOLD_MS]，然后**由音频自己播完**停住。
     *
     * 这是与"延迟调用 [stop]"的本质区别：这里不在应用里计时，而是把音频挪到
     * 曲尾前 [TAIL_HOLD_MS] 处，让系统把它放完。见类注释里那段踩坑说明。
     *
     * @param onDone 已经没声音之后才回调。**没在播时会立即回调** ——
     *   调用方（服务）不必自己判断 BGM 开关有没有开。
     */
    fun tailOut(onDone: () -> Unit) {
        main.post { tailOutInternal(onDone) }
    }

    // ------------------------------------------------------------------
    // 内部实现：以下都跑在主线程
    // ------------------------------------------------------------------

    private fun startInternal() {
        // 上一次的淡出还没走完就又要点播：先把那个播放器彻底收掉，
        // 否则它会一直占着一路音频、直到动画结束才释放。
        releasePending()
        animator?.cancel()

        // 上一轮的尾巴还没走完就又点播报：把它立刻收掉，下面重建一个。
        // （走尾巴的播放器已经 seek 到曲尾、循环也关了，接着用只会立刻没声。）
        if (tailing) stopInternal()

        val existing = player
        if (existing != null) {
            fadeTo(FULL_VOLUME)
            return
        }

        val media = try {
            // 显式声明用途与内容类型。`MediaPlayer.create(context, resId)` 用的是默认属性
            // （USAGE_UNKNOWN / CONTENT_TYPE_UNKNOWN）—— 在 dumpsys audio 里那就是一个
            // 来路不明的播放器，音量归哪条流管、该不该被系统 duck 都无从推理。
            MediaPlayer.create(
                context,
                R.raw.bgm,
                BGM_ATTRIBUTES,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        } catch (error: Exception) {
            Log.w(TAG, "背景音乐创建失败，本次不垫乐", error)
            null
        } ?: run {
            // 起播失败：把开场计时一并撤掉。留着的话调用方会为一段**并不存在**的
            // 开场白等满 [DRUM_DROP_MS] —— 音乐根本没响，人声却要 11 秒之后才来。
            introAtMs = 0L
            return
        }

        media.isLooping = true
        // 起播取 [FADE_FLOOR] 而不是 0：淡入是"锦上添花"，"能不能听见"不是。
        // 从 0 起播的话，淡入一旦不跑（息屏没有 VSYNC，见 [fadeTo]），
        // 整段缓冲音乐就是**静音** —— 而缓冲音乐的全部意义就是"我在响应你"。
        media.setVolume(FADE_FLOOR, FADE_FLOOR)
        player = media
        volume = FADE_FLOOR
        // 真正的起播时刻以这里为准，而不是 [start] 里那次乐观记时：
        // 上面 `tailing` 那条分支会走一次 [stopInternal]，它把开场计时清成了 0。
        introAtMs = SystemClock.elapsedRealtime()
        media.start()
        fadeTo(FULL_VOLUME, FADE_IN_MS)
        scheduleWatchdog(MAX_PLAY_MS)
    }

    private fun stopInternal() {
        clearWatchdog()
        onTailDone = null
        tailing = false
        animator?.cancel()

        val media = player ?: releasing
        player = null
        releasing = null
        volume = 0f
        // 没在播了，开场计时跟着清零 —— 留着的话，下一轮"还没起播"的那个窗口里
        // [introRemainingMs] 会拿上一轮的时刻算出一个正数，让新一轮白等一场
        // 并不存在的开场。
        introAtMs = 0L
        if (media == null) return

        // 先把音量打到 0 再停：即使 release 这一步被拖住，耳朵里已经没声了。
        runCatching { media.setVolume(0f, 0f) }
        runCatching {
            media.stop()
            media.release()
        }
    }

    /**
     * 走尾巴：关掉循环、把播放位置挪到"距曲尾 [TAIL_HOLD_MS] + [TRACK_OUTRO_MS] 处"。
     *
     * 挪位置而不是从当前位置继续放，是因为曲子已经循环了不知道多少轮，
     * 剩下的长度不可控 —— 只有"距末尾固定多少秒"才能保证到点就停。
     * 这一跳发生在垫底音量（[DUCKED_VOLUME]）下，听不出来。
     *
     * 为什么多挪 [TRACK_OUTRO_MS] 那么远：**素材自己的收尾（淡出 + 静音）不算"陪伴"**，
     * 那段时间里音乐其实已经没了。见 [TRACK_OUTRO_MS] 里量出来的数据。
     */
    private fun tailOutInternal(onDone: () -> Unit) {
        val media = player
        if (media == null) {
            // 没在播（用户关了 BGM 开关，或启动失败）：立刻交差。
            onDone()
            return
        }

        // 幂等：已经走过一次尾巴就只换回调，别再 seek 一遍。
        if (tailing) {
            onTailDone = onDone
            return
        }

        val duration = runCatching { media.duration }.getOrDefault(-1)
        // 尾巴要从"素材还在响的地方"开始，所以得把素材自带的收尾也一起往前让 ——
        // 见 [TRACK_OUTRO_MS]。这就是"实际播放时长"与"听得见的时长"的区别。
        val tailSpanMs = TAIL_HOLD_MS + TRACK_OUTRO_MS
        if (duration <= tailSpanMs) {
            // 拿不到时长（个别编码会返回 -1），或曲子比尾巴还短：退回立即停。
            stopInternal()
            onDone()
            return
        }

        tailing = true
        onTailDone = onDone
        // 先挂完成回调再动播放器：seekTo 万一失败，音频播到曲尾时这条回调
        // 仍然会来（`isLooping = false` 在那时也已生效），不至于没人收尾。
        media.setOnCompletionListener {
            // 播完了。这一回调由系统媒体服务驱动，走的是另一条路 ——
            // 不在我们的定时器上。即便进程当时被冻住、回调迟到，
            // **声音已经在播完那一刻停了**，用户听到的就是"到点收住"。
            main.post {
                val callback = onTailDone
                onTailDone = null
                stopInternal()
                callback?.invoke()
            }
        }
        runCatching {
            media.isLooping = false
            media.seekTo((duration - tailSpanMs).toInt())
        }

        // ⚠️ 尾巴这一段**不是**"从垫底一路弱到 0"，而是先**浮上来**。
        //
        // 上一版就是淡出到 0，主人听完的反馈是"最后还是 BGM 跟着人声停了"：
        // 那两秒半确实在放，但它一边放一边变轻，起点又只有垫底的 0.3 ——
        // 耳朵收到的是"人声一落，音乐也跟着没了"，完全没听出"还陪你一会儿"。
        // 现在人声一退场，音乐就回到 [TAIL_VOLUME] 这个清楚的收尾音量，把话说完。
        //
        // 末段不做淡出：音频已被挪到曲尾前、会自己播完，曲尾的收束是素材自带的
        // （那段淡出正是 [TRACK_OUTRO_MS]）。而"到点一定没声"这条底线本来就由它保证，
        // 不该再指望动画（见上面那条 VSYNC 的坑）。
        fadeTo(TAIL_VOLUME, TAIL_RAMP_MS)
        // 兜底：completion 回调万一不来（音频异常），也不能让音乐继续循环。
        // 和上面一样，它在休眠时不执行，但那时的风险已经由"音频播完"兜住了。
        scheduleWatchdog(tailSpanMs + FALLBACK_MS)
    }

    /**
     * 兜底：到点无条件静音。
     *
     * 音乐漏关的代价远大于多播一会儿 —— 它会一直循环到用户手动杀进程。
     * 正常路径走"音频播完"，这个只是"万一收尾回调没来"的保险。
     */
    private fun scheduleWatchdog(timeoutMs: Long) {
        clearWatchdog()
        val task = Runnable { stopInternal() }
        watchdog = task
        main.postDelayed(task, timeoutMs)
    }

    private fun clearWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    // ------------------------------------------------------------------
    // 音量渐变
    // ------------------------------------------------------------------

    /**
     * 把音量渐变到 [target]。
     *
     * 动画的每一帧都去真正调 `setVolume`，而不是只在结束时设一次 ——
     * 人耳对音量**变化过程**敏感，只在头尾设值等于没渐变。
     *
     * 不收"只走动画、不管结果"的开关，也没有结束回调 —— 这两样当初都是为
     * "淡出到静音"准备的，而淡出已经不做了（尾巴改成浮上来，见 [tailOutInternal]）。
     */
    private fun fadeTo(
        target: Float,
        durationMs: Long = DUCK_MS,
    ) {
        val from = volume
        if (from == target) {
            return
        }
        val media = player ?: releasing
        animator?.cancel()

        // ⚠️ 先把音量**直接落到目标值**，再让动画从旧值重放一遍渐变。
        //
        // 反过来的写法（留旧值、只靠动画推到目标）有一个致命缺口：`ValueAnimator`
        // 靠 VSYNC 驱动，而**息屏 / 应用没有可见窗口时系统不再派发 VSYNC** ——
        // 那时动画一帧都不会走，音量就永远停在起播时设的那个值上。
        //
        // 真机证据（09:34:22 那次播报）：起播后 7.3 秒里 `setVolume` 只出现过一帧
        // `0.000000`，直到人声开口都没再变过 —— 用户听到的就是"没有 BGM 的声音"。
        // 人声不受影响是因为它根本不需要动画去设音量，问题只出在 BGM。
        //
        // 所以：**音量是"结果"，动画只是"观感"；结果不许托付给动画。**
        // 动画正常时，它的第一帧会把音量拉回 [from]，再逐帧逼近 target ——
        // 那一下 16ms 的回跳听不出来，而它换来了"任何情况下音量都是对的"。
        //
        // 这一段没有例外了：所有调用点都走"先落地、再放动画"。
        volume = target
        runCatching { media?.setVolume(target, target) }

        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = durationMs
            addUpdateListener { frame ->
                val value = frame.animatedValue as Float
                volume = value
                runCatching { media?.setVolume(value, value) }
            }
            start()
        }
    }

    private fun releasePending() {
        val pending = releasing ?: return
        releasing = null
        runCatching {
            pending.stop()
            pending.release()
        }
    }

    companion object {
        private const val TAG = "BgmPlayer"

        /**
         * 显式声明用途与内容类型（见 [startInternal]）。
         */
        private val BGM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        /**
         * 缓冲阶段的音量：满幅。
         *
         * 这一段里音乐是**唯一的声音**，它的职责就是"我在响应你" —— 没有任何理由再压它。
         * （原先取 0.9，理由是"给淡入留余量、避免和系统音量叠加时削顶"。但 BGM 实测峰值
         * -0.1 dBFS，满幅并不削顶；而在媒体音量偏低时，少掉的这 10% 就是"听不见"。）
         */
        private const val FULL_VOLUME = 1.0f

        /**
         * 起播时的最低可闻音量：淡入的起点，也是"动画一帧都没走"时的兜底。
         *
         * 取 [FULL_VOLUME] 的六成 —— 起播听上去仍是从轻到响，但即便淡入完全没发生，
         * 音乐也已经清清楚楚在那儿了（见 [fadeTo] 里那条 VSYNC 的坑）。
         *
         * ⚠️ 必须声明在 [FULL_VOLUME] **之后**：`const val` 的初始化表达式按声明顺序求值，
         * 写在前面会报 "Variable 'FULL_VOLUME' must be initialized"（真编译抓到的）。
         */
        private const val FADE_FLOOR = FULL_VOLUME * 0.6f

        /**
         * 播报垫底时的音量：约为人声（满幅）的三成。
         *
         * ⚠️ 这个值从 0.12 调上来过（主人反馈"有 BGM 但太低，几乎听不到"）。
         * 0.12 是照"背景音乐该比人声低 18 dB"的教条定的，但那套数值默认**音乐本身
         * 已经归一化到满幅**；我们这段 BGM 实测 RMS 只有 -14.3 dBFS，再乘 0.12、
         * 再乘系统媒体音量，落到耳朵里就只剩"若有若无"。
         * 而且语音是窄带、音乐是宽带，同样电平下音乐本来就更容易被忽略 ——
         * 垫底要给得比纸面数字更足一点。
         */
        private const val DUCKED_VOLUME = 0.3f

        /** 起播淡入：比压低短一点，用户点下去之后越早听见越好。 */
        private const val FADE_IN_MS = 350L

        /** 压低 / 拉回的时长。 */
        private const val DUCK_MS = 400L

        /**
         * 开场节拍：人声要在 BGM 的**第 11 秒**进来。
         *
         * 这个数不是随手定的一个时长，是**音频自己给的点**：`bgm.mp3` 前 11 秒是
         * 一层环境垫子，第 11.00 秒底鼓才落地 —— 实测 10.90s 的低频还在 -46 dB，
         * 11.00s 一步跳到 -8.7 dB（38 dB 的跃升），之后每 0.4~0.5 秒一个稳定的踢点。
         * 所以"等满 11 秒再开口"和"人声落在鼓点那一刻"本来就是同一件事。
         *
         * 这是产品上刻意固定的一个节拍，对所有路径生效 —— **不靠"等模型那几秒"顺带实现**。
         * 原因：命中缓存时正文和语音都在缓存里，从点击到出声只要几百毫秒，
         * 靠模型耗时是凑不出开场的。
         *
         * ⚠️ 判据是**音频的播放位置**而不是墙钟，见 [introRemainingMs]。
         * 改这个值之前先确认 `bgm.mp3` 没换过 —— 换素材等于换鼓点。
         */
        const val DRUM_DROP_MS = 11_000L

        /**
         * 提前放行的量。
         *
         * 从"我们决定开口"到"耳朵听见第一个字"中间还有一段流水线耗时：音频要落盘、
         * `prepareAsync` 要把播放器准备好、`start()` 之后音频设备还要起振 ——
         * 几十到一百多毫秒，因设备和引擎而异。这段时间里音乐照常在走。
         *
         * 所以等待的终点定在 `DRUM_DROP_MS - VOICE_LEAD_MS`：让**第一个字**落在 11.00s，
         * 而不是让"我们决定开口"落在 11.00s、人声晚到一步。
         *
         * 这个数是估的量级，**真机验一次就能调准**：人声开口那一刻会打一行日志
         * （见 [positionMs]），读到的位置比 [DRUM_DROP_MS] 小多少，就把它加多少；
         * 大了就减。只动这一个常量，别去动音频位置那套判据。
         */
        const val VOICE_LEAD_MS = 120L

        /**
         * 念完之后音乐该**听得见**多久。
         *
         * 4 秒：够让人声的最后一个字"落下去"，也够耳朵**听出来**音乐还在陪你 ——
         * 又不至于长到让人以为"播报还没结束"。
         *
         * 最初是 2.5 秒，主人听完的反馈是"音乐还是停得有点短"。**那不只是时长问题** ——
         * "从曲尾往前数 N 秒"从来都不等于"能听到 N 秒音乐"，见 [TRACK_OUTRO_MS]。
         *
         * ⚠️ 它**不等于**"音频从曲尾前多远开始放"：实际是
         * `曲尾 - TAIL_HOLD_MS - TRACK_OUTRO_MS`（见 [tailOutInternal]）。两个都要看。
         *
         * ⚠️ 两者之和必须显著小于 `bgm.mp3` 的时长（实测 61.99 秒）：[tailOutInternal]
         * 里有一条保护 —— 曲子比尾巴还短时退回"立即停"，那时"挪到曲尾前"就没法用了。
         */
        const val TAIL_HOLD_MS = 4000L

        /**
         * `bgm.mp3` 自带的收尾时长：淡出 + 纯静音。
         *
         * **实测**（`afconvert -f WAVE` 转 wav 后按 0.25 秒一帧算 rms）：
         *
         * | 距曲尾 | rms |
         * |---|---|
         * | 5.50s | -15.7 dB | ← 还满着
         * | 4.00s | -15.6 dB |
         * | 3.00s | -27.9 dB | ← 已经开始往下走
         * | 2.50s | -57.9 dB |
         * | 2.00s | -92.5 dB | ← 等于没了
         * | ≤1.75s | **-99 dB**（数字静音，一直静到曲尾） |
         *
         * 所以"从曲尾往前数 2.5 秒"这个窗口里，**两秒是死的**，只有 fade 的尾巴尖露出来 ——
         * 主人说"音乐还是停得有点短"，那是**客观**的短，不是音量问题。
         * 多让 [TRACK_OUTRO_MS] 这么远，[TAIL_HOLD_MS] 才是真的 4 秒。
         *
         * ⚠️ **换 `bgm.mp3` 就要重新量这个值** —— 它和 [DRUM_DROP_MS] 一样绑在素材上，
         * 只是那条管开场、这条管收尾。
         */
        private const val TRACK_OUTRO_MS = 2500L

        /**
         * 人声退场后的收尾音量。
         *
         * 取 [FULL_VOLUME] 的六成：比垫底（[DUCKED_VOLUME]，0.3）明显高一档，
         * 让人**听得见**音乐还在陪你 —— 那正是尾巴存在的理由；又不至于像开头那样饱满，
         * 免得听上去像"又开了第二段"。
         */
        private const val TAIL_VOLUME = 0.6f

        /** 从垫底浮到 [TAIL_VOLUME] 的时长：短促一点，别拖成"渐强"。 */
        private const val TAIL_RAMP_MS = 250L

        /** 兜底看门狗相对尾巴的宽限：留够 completion 回调的路程。 */
        private const val FALLBACK_MS = 8000L

        /** 最长播放时限：兜底，防止任何一条收尾路径漏掉导致音乐一直循环。 */
        private const val MAX_PLAY_MS = 5 * 60 * 1000L
    }
}
