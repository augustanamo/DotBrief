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
 * 所以这份缓存**只在 `TtsProvider.DOUBAO` 路径上查和写**，系统引擎碰都不碰。
 *
 * ## 以文本为 key，而且是**按段**存的
 *
 * key 是那段文本的 SHA-256 前 8 字节，与"第几次播报""什么时候播"无关 —— 同样的文字
 * 永远对应同一个文件。调用方（`BriefPlaybackService.synthesizeSegment`）把一次播报
 * 拆成「开场句」和「其余」两段分别来查，于是：
 *
 * - **正文段里不含任何与时刻绑定的内容**（开场句已被 `BriefClock.splitOpening` 切走），
 *   写一次可以一直用 —— 主人要的"除了第一次不先请求，后面就好了"就是它；
 * - **开场段里的固定问候语**（"早上好"这类一共五种）第一次之后也永远命中，
 *   主人说的"早上好上午好这些经常用到的，请求一次就够了"就是它。
 *
 * ## 为什么没有了 15 分钟的 TTL（2026-09-24 删）
 *
 * 曾经有过，存在的**唯一**理由是"缓存里的音频含开场时间句"：key 是整段正文的哈希，
 * 而音频里的开场是合成那一刻的时间，于是只能靠一个短 TTL 兜住"现在是下午两点"
 * 配三点半窗外的偏差。
 *
 * 现在开场句被单独切出来合成，正文段里没有任何会过期的内容 —— TTL 要防的东西
 * 已经不存在了。而开场段是每次现合成的，内容一变 key 就变，**旧文件自然成垃圾**，
 * 不需要靠"过期"去纠正它念错时间。所以改为按天数清理（[MAX_AGE_MS]）。
 *
 * ⚠️ 别把 TTL 加回来"以防万一"：加回来就等于恢复了"预合成的语音放一会儿就没了"，
 * 而这正是上一版每次点播都要重新请求的原因。
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
        return runCatching { file.readBytes() }
            .onFailure { Log.w(TAG, "读取语音缓存失败，忽略并重新合成", it) }
            .getOrNull()
    }

    /** 写入一份合成结果。覆盖同名旧文件（同一段文本的旧缓存本就不该留两份）。 */
    fun put(text: String, audio: ByteArray) {
        runCatching { fileFor(text).writeBytes(audio) }
            .onFailure { Log.w(TAG, "写语音缓存失败（不影响本次播报）", it) }
        trimOldEntries()
    }

    /** 清空全部语音缓存。 */
    fun clear() {
        runCatching { dir.deleteRecursively() }
    }

    /**
     * 清掉太老的条目。
     *
     * 没有 TTL 之后，缓存会随着"每次重新生成正文"慢慢攒 —— 一天三次刷新、
     * 一条正文两三百 KB，一个月就是二三十兆。文件反正躺在 `cacheDir` 里
     * （系统缺空间时会自己回收），但主动收一下更稳妥。
     *
     * 在**写入时**顺手做，不另起定时任务：播报路径本来就不该依赖"进程醒着"
     * （见 [BgmPlayer] 里那条 VSYNC / 定时器的教训），而每次写缓存时都已经在
     * 文件系统里了，扫一个只有几十个文件的目录可以忽略不计。
     */
    private fun trimOldEntries() {
        runCatching {
            val deadline = System.currentTimeMillis() - MAX_AGE_MS
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < deadline) file.delete()
            }
        }
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
         * 缓存保留天数。
         *
         * 这不是"有效期"而是"清理线"：命中与否只由**内容**决定（见类注释），
         * 这个值只决定一个再也不会被用到的文件什么时候被删掉。所以定得宽松些 ——
         * 七天足以覆盖"周末没听、周一回来还能听到上周五那条"这种情形。
         */
        const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
