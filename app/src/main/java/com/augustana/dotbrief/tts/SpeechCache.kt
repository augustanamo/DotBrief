package com.augustana.dotbrief.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 云端语音合成结果的缓存。
 *
 * ## 为什么需要它
 *
 * 正文有缓存（[com.augustana.dotbrief.data.settings.RuntimeStateStore] 里的 `lastBriefText`），
 * 但语音没有 —— 每次"命中正文缓存直接播报"时，豆包那条路还是要去云端合成一遍，
 * 平白多一次网络请求和几秒等待。既然正文没变，合成出来的语音也不该变，
 * 那就该连语音一起缓存。
 *
 * ## 只对云端引擎有意义
 *
 * 系统 TextToSpeech 是"给文本、它自己念"，不产文件、不发请求，本来就没有可缓存的产物。
 * 所以这份缓存**只在 [TtsProvider.DOUBAO] 路径上查和写**，系统引擎碰都不碰。
 *
 * ## 以正文为 key
 *
 * 缓存必须跟着正文走：正文变了（哪怕只改一个标点），语音就该失效重合成。
 * 所以 key 是正文的 SHA-256 前 16 个十六进制字符，而不是"最近一次"这种位置概念 ——
 * 位置概念会漏掉"正文回退到旧版本"这种边角，哈希则天然一一对应。
 *
 * ## 落盘目录
 *
 * 与 [CloudTtsPlayer] 的中间文件一样放 `cacheDir` 下的子目录：这是纯中间产物，
 * 系统清缓存时顺手带走正合适，不占用户存储配额。固定前缀 `speech/`，
 * 单独一个子目录方便"清空语音缓存"时只删它、不误伤别的缓存文件。
 */
class SpeechCache(private val context: Context) {

    private val dir: File
        get() = File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    /** 命中则返回缓存音频字节，否则 null。 */
    fun get(text: String): ByteArray? {
        val file = fileFor(text)
        if (!file.exists()) return null
        // 超过 TTL 的缓存视为失效：开场时间句精确到分钟，缓存太久会念出"现在是下午两点"
        // 这种过时的时间。TTL 取一个"重复播报通常发生在几分钟内"的量级，既省掉重复合成，
        // 又不让时间句错得离谱。见类注释里的取舍。
        if (System.currentTimeMillis() - file.lastModified() > TTL_MS) {
            runCatching { file.delete() }
            return null
        }
        return runCatching { file.readBytes() }
            .onFailure { Log.w(TAG, "读取语音缓存失败，忽略并重新合成", it) }
            .getOrNull()
    }

    /** 写入一份合成结果。覆盖同名旧文件（同一条正文的旧缓存本就不该留两份）。 */
    fun put(text: String, audio: ByteArray) {
        runCatching { fileFor(text).writeBytes(audio) }
            .onFailure { Log.w(TAG, "写语音缓存失败（不影响本次播报）", it) }
    }

    /** 清空全部语音缓存。 */
    fun clear() {
        runCatching { dir.deleteRecursively() }
    }

    private fun fileFor(text: String): File = File(dir, hashOf(text) + ".mp3")

    private fun hashOf(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "BriefSpeechCache"
        const val CACHE_DIR = "speech"

        /**
         * 缓存有效期。
         *
         * 开场时间句精确到分钟，缓存复用必须限制在"时间句还算新鲜"的窗口内，
         * 否则用户会听到"现在是下午两点"配三点半的窗外。15 分钟足够覆盖
         * "点一下没听清再点""闹钟响后手动补听一遍"这类重复播报场景，
         * 又不会让时间句错得离谱。
         */
        const val TTL_MS = 15L * 60L * 1000L
    }
}
