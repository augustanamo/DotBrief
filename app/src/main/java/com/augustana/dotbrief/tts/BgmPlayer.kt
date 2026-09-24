package com.augustana.dotbrief.tts

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
 * 2. **人声底下有一层垫音**：真正开口时把音乐压到 [DUCKED_VOLUME]，让它退到背景里。
 *    念完之后不立刻掐掉，先再陪 [TAIL_HOLD_MS]，然后才收掉 ——
 *    人声一落音乐就断，听上去像"播报被砍了尾巴"。
 *
 * ## 两档音量为什么差这么多
 *
 * 缓冲段是**唯一没有人声**的一段，音乐是全部信息，所以给到接近满音量；
 * 人声一进来，音乐就只剩"垫底"这一个职责，再大声就是抢注意力。
 * 中间那一下压低必须是渐变（见 [fadeTo]），直接切会有塌陷感。
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
 * [TAIL_HOLD_MS] 处"，剩下这两秒多由系统放完，播完自然静音，
 * 全程不需要进程醒着。音量淡出照做，但它是锦上添花 ——
 * 就算进程在淡出途中被冻住，声音也已经到点没了。
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
 * 起播也从 [FADE_FLOOR] 而不是 0 起步；唯一例外是走尾巴那条
 * （`landFirst = false`），因为它另有"音频自己播完"这层兜底。
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
     * 开始播放（缓冲阶段）：从静音淡入到 [FULL_VOLUME]。
     *
     * 已经在播就只把音量拉回 [FULL_VOLUME] —— 那是"命中缓存、几乎立刻开口"的情形，
     * 音乐刚起来就压低，中间不该再有一次从头起播的接缝。
     */
    fun start() {
        main.post { startInternal() }
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
        } ?: return

        media.isLooping = true
        // 起播取 [FADE_FLOOR] 而不是 0：淡入是"锦上添花"，"能不能听见"不是。
        // 从 0 起播的话，淡入一旦不跑（息屏没有 VSYNC，见 [fadeTo]），
        // 整段缓冲音乐就是**静音** —— 而缓冲音乐的全部意义就是"我在响应你"。
        media.setVolume(FADE_FLOOR, FADE_FLOOR)
        player = media
        volume = FADE_FLOOR
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
        if (media == null) return

        // 先把音量打到 0 再停：即使 release 这一步被拖住，耳朵里已经没声了。
        runCatching { media.setVolume(0f, 0f) }
        runCatching {
            media.stop()
            media.release()
        }
    }

    /**
     * 走尾巴：关掉循环、把播放位置挪到"距曲尾 [TAIL_HOLD_MS] 处"。
     *
     * 挪位置而不是从当前位置继续放，是因为曲子已经循环了不知道多少轮，
     * 剩下的长度不可控 —— 只有"距末尾固定 2.5 秒"才能保证到点就停。
     * 这一跳发生在垫底音量（[DUCKED_VOLUME]）下，听不出来。
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
        if (duration <= TAIL_HOLD_MS) {
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
            media.seekTo(duration - TAIL_HOLD_MS.toInt())
        }

        // 淡出正好铺满剩下的那两秒多。进程被冻住时它跑不完也无所谓：到点自然静音。
        // 这里传 landFirst = false —— 要的就是"慢慢弱下去"这个过程，
        // 而"最后一定会没声"由 seekTo 到曲尾那条路保证，不靠动画。
        fadeTo(0f, TAIL_HOLD_MS, landFirst = false)
        // 兜底：completion 回调万一不来（音频异常），也不能让音乐继续循环。
        // 和上面一样，它在休眠时不执行，但那时的风险已经由"音频播完"兜住了。
        scheduleWatchdog(TAIL_HOLD_MS + FALLBACK_MS)
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
     * @param landFirst 是否先把音量直接落到 [target]、再让动画从旧值重放渐变。
     *   默认 true：见下面那段 VSYNC 的说明 —— 音量的"结果"不能托付给动画。
     *   只有走尾巴时传 false（那里要的就是渐弱本身）。
     * @param onEnd 渐变结束后才做的事：目前只有"淡出完成后释放播放器"这一处需要。
     */
    private fun fadeTo(
        target: Float,
        durationMs: Long = DUCK_MS,
        landFirst: Boolean = true,
        onEnd: (() -> Unit)? = null,
    ) {
        val from = volume
        if (from == target) {
            onEnd?.invoke()
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
        // [landFirst] = false 只给"走尾巴"用：那一段要的恰恰是**渐弱**，
        // 先落地就等于咔嚓静音 —— 用户要的"念完再陪两秒"全白留了。
        // 它敢不要这层兜底，是因为另有保障：音频已被挪到曲尾附近，
        // **会自己播完**；音量就算降不下来，到点也一样静音。
        if (landFirst) {
            volume = target
            runCatching { media?.setVolume(target, target) }
        }

        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = durationMs
            addUpdateListener { frame ->
                val value = frame.animatedValue as Float
                volume = value
                runCatching { media?.setVolume(value, value) }
            }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = onEnd()
                })
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
         * 念完之后音乐再陪多久。
         *
         * 2–3 秒：够让人声的最后一个字"落下去"，又不至于让人觉得播报没结束。
         *
         * 这个值同时是"音频从曲尾前多远开始放"，所以它必须是**实际播完的时长** ——
         * 改它会同时改掉两件事，不要只当成一个显示用的时长。
         */
        const val TAIL_HOLD_MS = 2500L

        /** 兜底看门狗相对尾巴的宽限：留够 completion 回调的路程。 */
        private const val FALLBACK_MS = 8000L

        /** 最长播放时限：兜底，防止任何一条收尾路径漏掉导致音乐一直循环。 */
        private const val MAX_PLAY_MS = 5 * 60 * 1000L
    }
}
