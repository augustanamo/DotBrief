package com.augustana.dotbrief.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 设置页的"试听"能力：用系统 TTS 念一句话，让用户立刻听到这个引擎的声音。
 *
 * 它验证的是**配置**（语音包装没装、发音人能不能用、Key 对不对），
 * 不是"参数效果" —— 语速与音调已经不是设置项，这里固定按引擎默认值念。
 *
 * 与 BriefPlaybackService 的分工：
 * - 这里是**短命的一次性引擎**，只服务设置页，ViewModel 销毁时释放；
 * - 不做音频焦点与前台上报（设置页本身就在前台，用户正看着屏幕）。
 */
class TtsPreviewPlayer(private val context: Context) {

    private var engine: TextToSpeech? = null
    private var ready: Boolean = false

    /** 引擎还没就绪时先记下要念的这句，onInit 回来后补播。 */
    private var pending: String? = null

    /** 引擎是否已初始化完成（初始化是异步的）。 */
    val isReady: Boolean get() = ready

    /**
     * 念一段文本。
     * 引擎首次创建是异步的，所以这里做了排队：第一次点试听时引擎可能还没 onInit，
     * 等初始化完成后会自动补播，避免"第一次点没反应"。
     */
    fun speak(text: String) {
        if (text.isBlank()) return

        val tts = ensureEngine()
        if (tts == null || !ready) {
            pending = text
            return
        }
        speakInternal(tts, text)
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

            pending?.let { text ->
                speakInternal(tts, text)
                pending = null
            }
        }
        engine = created
        return created
    }

    private fun speakInternal(tts: TextToSpeech, text: String) {
        // 有意不调 setSpeechRate / setPitch：让引擎用默认值（原速、原调），
        // 与 BriefPlaybackService 里正式播报的做法完全一致 ——
        // 试听听到的就是实际播报的声音，否则这个按钮又变成骗人的。
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    companion object {
        private const val UTTERANCE_ID = "brief-settings-preview"
    }
}
