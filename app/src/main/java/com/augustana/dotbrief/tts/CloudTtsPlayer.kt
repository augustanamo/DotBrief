package com.augustana.dotbrief.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 云端 TTS 的播放器。
 *
 * 系统 TextToSpeech 是「给文本、它自己去念」，进度由 `UtteranceProgressListener` 回调；
 * 云端合成拿到的是**一坨 mp3 字节**，所以这里必须先落盘成文件再交给 [MediaPlayer] ——
 * MediaPlayer 不接受内存里的音频，喂它也只有一个 FileDescriptor / 路径两种方式。
 *
 * 落盘目录选 `cacheDir`：这是纯中间产物，系统清缓存时顺手带走正合适，
 * 不该占用用户的存储配额。
 *
 * 线程约定：`play` 是挂起函数，内部自己切线程。
 * 构造 MediaPlayer 与调用 `prepareAsync` 都放在主线程 ——
 * MediaPlayer 的事件回调绑定在**构造时所在线程的 Looper** 上，
 * 在无 Looper 的 IO 线程上 new 出来，回调会悄悄跑到主线程去，行为不好推理。
 */
class CloudTtsPlayer(private val context: Context) {

    /** 播放回调。全部在主线程触发。 */
    interface Callback {
        /** 真正开始出声了（不是"开始准备"）。桌面动效应该挂在这个点。 */
        fun onStart()

        fun onCompletion()

        fun onError(message: String)
    }

    private var player: MediaPlayer? = null

    /** 是否已经有一个播放器在跑（用于「再点即打断」的判断）。 */
    val isActive: Boolean get() = player != null

    suspend fun play(audio: ByteArray, callback: Callback) {
        // 写文件在 IO 线程，避免卡主线程
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, CACHE_FILE_NAME).apply { writeBytes(audio) }
        }

        withContext(Dispatchers.Main) {
            releasePlayer()

            val media = MediaPlayer()
            player = media
            try {
                media.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                media.setDataSource(file.absolutePath)
                media.setOnPreparedListener { it.start(); callback.onStart() }
                media.setOnCompletionListener { callback.onCompletion() }
                media.setOnErrorListener { _, what, extra ->
                    callback.onError("播放失败（$what/$extra）")
                    true
                }
                media.prepareAsync()
            } catch (error: Throwable) {
                Log.w(TAG, "MediaPlayer 准备失败", error)
                releasePlayer()
                callback.onError("语音文件播放不了：${error.javaClass.simpleName}")
            }
        }
    }

    /**
     * 暂停：从当前播放位置停住，之后可 [resume] 从同一处继续。
     *
     * 只对"真正在播"的状态有效；准备中（`prepareAsync` 未完成）或已释放时是空操作，
     * 因为那时 `isPlaying` 本来就是 false，`pause()` 也无从谈起。
     */
    fun pause() {
        val media = player ?: return
        runCatching { if (media.isPlaying) media.pause() }
    }

    /**
     * 从暂停处继续。只有 [pause] 过（`isPaused == true`）才有效；
     * 正常播放中调用是空操作，不会从头重播。
     */
    fun resume() {
        val media = player ?: return
        runCatching { if (!media.isPlaying) media.start() }
    }

    /** 打断/收尾时调用。可重复调用。 */
    fun stop() {
        releasePlayer()
    }

    /**
     * 当前播放进度（0..1），给桌面点阵的"逐渐灰掉"用。
     *
     * 云端这条路比系统 TTS 幸运：拿到的是一个真实音频文件，`currentPosition / duration`
     * 就是精确进度，不需要像系统引擎那样靠字数估算。但**准备中/已释放时必须能返回 0**：
     * `duration` 在 `prepareAsync` 完成前是 -1，直接拿去做分母会得到负数或 NaN，
     * 一路传下去会把点阵的灰盘半径算成 NaN —— 整块点阵画不出来（Canvas 遇 NaN 是静默不画）。
     * 所以这里 runCatching + coerceIn 两道都留着。
     */
    fun progress(): Float {
        val media = player ?: return 0f
        return runCatching {
            val duration = media.duration
            if (duration <= 0) 0f else media.currentPosition.toFloat() / duration
        }.getOrDefault(0f).coerceIn(0f, 1f)
    }

    private fun releasePlayer() {
        val media = player ?: return
        player = null
        runCatching { if (media.isPlaying) media.stop() }
        runCatching { media.reset() }
        runCatching { media.release() }
    }

    private companion object {
        const val TAG = "BriefCloudTts"

        /** 固定文件名即可：同一时刻只会播一段简报，不需要多份缓存。 */
        const val CACHE_FILE_NAME = "brief_speech.mp3"
    }
}
