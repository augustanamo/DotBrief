package com.briefwidget.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 设置页的"试听"能力：用系统 TTS 念一句话，让用户立刻听到当前语速/音调的效果。
 *
 * 与 Phase 3 的 BriefTtsService 的分工：
 * - 这里是**短命的一次性引擎**，只服务设置页，ViewModel 销毁时释放；
 * - 不做音频焦点与前台上报（设置页本身就在前台，用户正看着屏幕）。
 * 但两者共享同一套参数来源：[com.briefwidget.data.settings.TtsConfig]。
 */
class TtsPreviewPlayer(private val context: Context) {

    private data class Request(val text: String, val rate: Float, val pitch: Float)

    private var engine: TextToSpeech? = null
    private var ready: Boolean = false
    private var pending: Request? = null

    /** 引擎是否已初始化完成（初始化是异步的）。 */
    val isReady: Boolean get() = ready

    /**
     * 播报一段文本。
     * 引擎首次创建是异步的，所以这里做了排队：第一次点试听时引擎可能还没 onInit，
     * 等初始化完成后会自动补播，避免"第一次点没反应"。
     */
    fun speak(text: String, rate: Float, pitch: Float) {
        if (text.isBlank()) return

        val tts = ensureEngine()
        if (tts == null || !ready) {
            pending = Request(text, rate, pitch)
            return
        }
        speakInternal(tts, text, rate, pitch)
    }

    /**
     * 掐掉正在念的那一句，但保留引擎。
     *
     * 与 [shutdown] 的区别：用户在设置页把引擎从「系统」切到「豆包」时，
     * 系统 TTS 那句试听可能还在念 —— 这时需要的是"停下"而不是"把引擎销毁重建"。
     */
    fun stop() {
        pending = null
        runCatching { engine?.stop() }
    }

    /** 释放引擎。必须调用，否则会一直占着 TTS 服务连接。 */
    fun shutdown() {
        pending = null
        ready = false
        engine?.let { tts ->
            runCatching {
                tts.stop()
                tts.shutdown()
            }
        }
        engine = null
    }

    private fun ensureEngine(): TextToSpeech? {
        engine?.let { return it }

        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech

            ready = true
            val tts = created ?: return@TextToSpeech
            tts.setLanguage(Locale.SIMPLIFIED_CHINESE)

            pending?.let { request ->
                speakInternal(tts, request.text, request.rate, request.pitch)
                pending = null
            }
        }
        engine = created
        return created
    }

    private fun speakInternal(tts: TextToSpeech, text: String, rate: Float, pitch: Float) {
        tts.setSpeechRate(rate.coerceIn(MIN_RATE, MAX_RATE))
        tts.setPitch(pitch.coerceIn(MIN_PITCH, MAX_PITCH))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    companion object {
        private const val UTTERANCE_ID = "brief-settings-preview"

        // 系统 TTS 的合理区间：超出这个范围要么听不清要么明显失真
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
    }
}
