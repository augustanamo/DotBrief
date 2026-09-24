package com.augustana.dotbrief.data.tts

import android.util.Log
import com.augustana.dotbrief.data.settings.Defaults
import com.augustana.dotbrief.data.settings.TtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 合成结果。失败一律带**能直接念给用户听**的中文说明，不抛异常。 */
sealed interface SpeechResult {
    /** [audio] 是一段完整可播的音频（mp3）。 */
    data class Success(val audio: ByteArray) : SpeechResult

    data class Failure(val message: String) : SpeechResult
}

/**
 * 豆包（火山引擎）语音合成客户端。
 *
 * ## 只有一套协议
 *
 * 走 `POST /api/v3/tts/unidirectional`：
 *
 * - **鉴权**：单个 `X-Api-Key`。注意它的取值是**语音控制台**「API Key 管理」里
 *   生成的 UUID 形式字符串；方舟（Ark）那套 `ark-…` / `api-key-…` 的 Key
 *   语音接口不认，会返回 `45000010 Invalid X-Api-Key`（实测踩过）。
 * - **请求体**：`user` + `namespace` + `req_params`。
 * - **响应**：NDJSON，一行一个 JSON，音频分片 base64 在顶层 `data`。
 *
 * 曾经还有一条老接口的路（App ID + Access Token + Cluster 三件套）。
 * 它已经删掉了：**两套的 Key 与音色都互不通用**，留着一个用户手上多半没有凭据的分支，
 * 只会让设置页多出「该填哪个」这种只能由用户自己猜的问题。
 *
 * ## 为什么整段读下来再解，而不是边收边解
 *
 * OkHttp 的 chunk 边界和 JSON 行边界**没有任何关系** —— 一个 `{…}` 完全可能被劈成
 * 两个 chunk。所以先把 body 整个读成字符串（服务端发完会自己收流），再按行切，
 * 这样"跨 chunk 的半截 JSON"这个问题根本不存在。
 * 代价是要等音频全部生成完才出声，几秒的简报体量下可以接受。
 */
class DoubaoTtsClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun synthesize(config: TtsConfig, text: String): SpeechResult {
        if (text.isBlank()) return SpeechResult.Failure("要朗读的内容是空的")
        if (config.doubaoApiKey.isBlank()) {
            return SpeechResult.Failure("还没填豆包的 API Key，先去设置页补上")
        }
        if (config.doubaoSpeaker.isBlank()) return SpeechResult.Failure("还没填音色 ID")

        val chunks = splitForSpeech(text)
        // 短文本（绝大多数情况：试听一句、三五句的本地简报）走原来的单次路径 ——
        // 那是已经在真机上验证过的链路，没必要为它加一层分段。
        if (chunks.size == 1) return synthesizeOnce(config, text)

        Log.i(TAG, "正文 ${text.length} 字，按 $CHUNK_MAX_CHARS 字上限切成 ${chunks.size} 段并行合成")
        return synthesizeChunked(config, chunks)
    }

    /**
     * 一次请求合成一段文本。段长由 [splitForSpeech] 保证在 [CHUNK_MAX_CHARS] 以内。
     *
     * 多段时是**并行**调用的：协程各自跑在 IO 上，互不相干。
     */
    private suspend fun synthesizeOnce(config: TtsConfig, text: String): SpeechResult =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("user", buildJsonObject { put("uid", UID) })
                put("namespace", NAMESPACE)
                put(
                    "req_params",
                    buildJsonObject {
                        put("text", text)
                        put("speaker", config.doubaoSpeaker)
                        put(
                            "audio_params",
                            buildJsonObject {
                                put("format", "mp3")
                                put("sample_rate", SAMPLE_RATE)
                                // 一律原速：服务端吃整数百分比，0 = 不加速也不减速。
                                // （倍率 -> 百分比的换算随 speechRate 字段一起删了。）
                                put("speech_rate", 0)
                            },
                        )
                    },
                )
            }.toString()

            val request = Request.Builder()
                .url(Defaults.DOUBAO_ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .addHeader("Connection", "keep-alive")
                .addHeader("X-Api-Key", config.doubaoApiKey)
                .addHeader("X-Api-Resource-Id", config.doubaoResourceId.ifBlank { Defaults.DOUBAO_RESOURCE_ID })
                .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            execute(request, ::parseStream)
        }

    /**
     * 长文本：**并行**合成各段，再拼成一个连续音频。
     *
     * ## 为什么不能整段一次请求
     *
     * 两件事都会在五分钟的播报上暴露：
     * 1. **长度上限**：接口对单次请求的文本长度有限制，而火山引擎文档对这套接口的口径
     *    说法不一（"非流式 1000 / 流式 2000 个 utf-8 字符"，第三方对 v3 单向流式的描述
     *    又是"1000 字符 ≈ 330 汉字"）—— 到底是字节还是码点说不准。1350 字正落在这个
     *    说不准的区间里，与其赌，不如每段都远低于任何口径；
     * 2. **首字延迟**：整段一次合成必须等全部生成完才出声。1350 字的合成时间是单段的
     *    数倍，用户点一下要干等十几秒；并行之后等待只等于**最慢的那一段**。
     *
     * ## 为什么任一段失败就整条失败
     *
     * 只播成功的部分比报一个错更糟：用户听到的是"话说到一半没了"，而且从听感上
     * 根本看不出是坏了 —— 他会以为今天的简报就这么短。宁可不出声，也要把原因说清楚。
     */
    private suspend fun synthesizeChunked(
        config: TtsConfig,
        chunks: List<String>,
    ): SpeechResult = coroutineScope {
        val results = chunks.map { chunk -> async { synthesizeOnce(config, chunk) } }.awaitAll()

        val failure = results.filterIsInstance<SpeechResult.Failure>().firstOrNull()
        if (failure != null) {
            Log.w(TAG, "分段合成有段失败，整条作废：${failure.message}")
            SpeechResult.Failure(failure.message)
        } else {
            SpeechResult.Success(
                joinAudio(results.filterIsInstance<SpeechResult.Success>().map { it.audio }),
            )
        }
    }

    /**
     * 把正文切成可以分别送合成的段。
     *
     * **切在句读之后**（[SENTENCE_END]），绝不在句子中间断开：一段音频内部的语调是
     * 连续的，从中间切开会让前后两段各自"收尾 / 起头"，念出来是明显的断句错误。
     *
     * 单段长度上限见 [CHUNK_MAX_CHARS]。累积到装不下下一句就切一段，
     * 所以每段都在上限以内、且尽可能长（段数越少，并发开销与拼接接缝都越少）。
     */
    internal fun splitForSpeech(text: String): List<String> {
        if (text.length <= CHUNK_MAX_CHARS) return listOf(text)

        val chunks = mutableListOf<String>()
        val pending = StringBuilder()
        SENTENCE_END.split(text).forEach { sentence ->
            if (sentence.isEmpty()) return@forEach
            // 单句本身就超长（模型偶尔写出没有句号的流水句）：先按逗号再切一层，
            // 仍不行就按字数硬切。宁可牺牲一点断句，也不能让某段超长把整次合成拖垮。
            val pieces =
                if (sentence.length <= CHUNK_MAX_CHARS) listOf(sentence)
                else hardSplit(sentence)

            pieces.forEach { piece ->
                if (pending.isNotEmpty() && pending.length + piece.length > CHUNK_MAX_CHARS) {
                    chunks += pending.toString()
                    pending.clear()
                }
                pending.append(piece)
            }
        }
        if (pending.isNotEmpty()) chunks += pending.toString()
        return chunks
    }

    /** 超长单句的兜底切分：优先断在逗号 / 顿号 / 冒号后，实在没有标点才按字数硬切。 */
    private fun hardSplit(sentence: String): List<String> =
        sentence.split(CLAUSE_END)
            .flatMap { it.chunked(CHUNK_MAX_CHARS) }
            .filter { it.isNotEmpty() }

    /**
     * 把多段 mp3 拼成一个连续音频。
     *
     * 每段都是独立的一次合成，各自带自己的 ID3 标签。直接把字节连起来，第二段往后的
     * ID3 会被解码器当成音频帧去同步 —— 轻则开头一声杂音，重则整段跳帧，所以除第一段
     * 外都要先 [stripId3]。第一段原样保留：播放器靠它读元信息，剥了没好处。
     *
     * 各段的音色 / 采样率 / 码率完全一致，帧格式因此相同，拼接后就是合法的连续帧流 ——
     * **不需要重编码**（那会额外引入一次有损转码，还多几秒）。
     *
     * 段间会自然带出一点停顿：每次合成在段首尾都会留极短的静音，拼起来正好是
     * 正常的句间留白，不会听起来像"两段拼接"。
     */
    internal fun joinAudio(parts: List<ByteArray>): ByteArray {
        if (parts.size == 1) return parts[0]
        val out = ByteArrayOutputStream()
        parts.forEachIndexed { index, part ->
            out.write(if (index == 0) part else stripId3(part))
        }
        return out.toByteArray()
    }

    /**
     * 剥掉 mp3 的 ID3 标签：头部的 ID3v2 与尾部的 ID3v1。
     *
     * 两者形状完全不同，所以是两段独立判断：
     * - **ID3v2** 在文件头，前 3 字节是 `ID3`，第 4~5 字节版本、第 6 字节标志，
     *   第 7~10 字节是长度 —— 而这 4 个字节是 **synchsafe** 的（每字节只用低 7 位，
     *   最高位恒为 0），不能按普通大端整数读，否则长度会算大一倍；
     * - **ID3v1** 固定 128 字节挂在文件末尾，以 `TAG` 开头。
     */
    internal fun stripId3(audio: ByteArray): ByteArray {
        var start = 0
        var end = audio.size

        if (end - start >= ID3V2_HEADER && audio.matchesAscii(0, "ID3")) {
            val size = (0..3).fold(0) { acc, i -> (acc shl 7) or (audio[6 + i].toInt() and 0x7f) }
            start = (ID3V2_HEADER + size).coerceAtMost(end)
        }

        if (end - start >= ID3V1_SIZE && audio.matchesAscii(end - ID3V1_SIZE, "TAG")) {
            end -= ID3V1_SIZE
        }

        return if (start == 0 && end == audio.size) audio else audio.copyOfRange(start, end)
    }

    /** 在 [at] 处是否正好是这段 ASCII。用它而不是先转字符串：只要比前几个字节，不必复制整段音频。 */
    private fun ByteArray.matchesAscii(at: Int, expect: String): Boolean {
        if (at < 0 || size - at < expect.length) return false
        return expect.indices.all { this[at + it] == expect[it].code.toByte() }
    }

    /**
     * 解析 NDJSON 响应流。
     *
     * 实测抓到的报文长这样（每行一个 JSON，共 15 行对应一句话）：
     *
     * ```
     * {"code":0,"message":"","data":"SUQzBAAA…"}           // 音频分片，base64 mp3
     * {"code":0,"message":"","data":null,"sentence":{…}}    // 句尾行，带音素等元信息，没音频
     * {"code":20000000,"message":"OK","data":null}          // 结束哨兵
     * ```
     *
     * 所以判据只有一条 `code`，但**有三个值要分清**：
     * `0` 是成功、`20000000` 是正常收尾、其余才是错误。
     * 把结束哨兵当错误是最容易犯的错 —— 那样每次都会拿着半段音频报失败。
     */
    internal fun parseStream(raw: String): SpeechResult {
        val audio = ByteArrayOutputStream()
        var failure: String? = null
        var lineCount = 0

        raw.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("{") }
            .forEach { line ->
                lineCount++
                val element = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@forEach

                when (val code = element.codeOrNull()) {
                    END_OF_STREAM_CODE -> Unit                       // 正常收尾
                    // 没有 code 的行（网关心跳之类）不该判成错误：
                    // 只当它有音频就顺手收下，没有就跳过。
                    null, SUCCESS_CODE -> element.audioData()?.let { audio.write(it) }
                    else -> failure = element.errorMessageOrNull() ?: "豆包返回错误码 $code"
                }
            }

        return when {
            failure != null -> SpeechResult.Failure(failure!!)
            audio.size() == 0 -> SpeechResult.Failure(
                if (lineCount == 0) {
                    "豆包没返回内容，检查 API Key 是否有效"
                } else {
                    "豆包没返回音频，检查音色 ID 与 Resource ID 是否配套（2.0 音色必须配 seed-tts-2.0）"
                },
            )
            else -> SpeechResult.Success(audio.toByteArray())
        }
    }

    /** 发请求 -> 解 HTTP 错误 -> 交给 [parse] 解析业务响应。 */
    private fun execute(request: Request, parse: (String) -> SpeechResult): SpeechResult {
        Log.i(TAG, "POST ${request.url}（鉴权头与文本已省略）")
        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    parse(raw)
                } else {
                    Log.w(TAG, "TTS HTTP ${response.code}: ${raw.take(400)}")
                    SpeechResult.Failure(describeHttpError(response.code, raw))
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // 用户点了打断 -> 协程被取消，这不是"合成失败"，不能报给用户
            throw cancellation
        } catch (error: Throwable) {
            SpeechResult.Failure(describeNetworkError(error))
        }
    }

    internal fun describeHttpError(code: Int, raw: String): String {
        val hint = when (code) {
            401, 403 -> "API Key 不对，或者这个账号没开通语音合成"
            404 -> "接口地址不存在"
            429 -> "请求太频繁或额度用完了"
            in 500..599 -> "豆包服务端出错了，稍后再试"
            else -> "豆包拒绝了这次请求"
        }
        // 服务端自己给的 message 通常比我们的猜测具体得多，优先带出去。
        val detail = runCatching {
            json.parseToJsonElement(raw).jsonObject.errorMessageOrNull()
        }.getOrNull()?.replace(Regex("\\s+"), " ")?.trim()?.take(SERVER_MESSAGE_MAX_CHARS)

        return if (detail.isNullOrBlank()) "$hint（HTTP $code）" else "$hint（HTTP $code）：$detail"
    }

    private fun describeNetworkError(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "域名解析不了，检查网络"
        is java.net.SocketTimeoutException -> "连豆包超时了，检查网络后重试"
        is java.net.ConnectException -> "连不上豆包服务器，检查网络"
        else -> "语音合成失败：${error.javaClass.simpleName}"
    }

    private companion object {
        const val TAG = "BriefDoubao"

        const val NAMESPACE = Defaults.DOUBAO_NAMESPACE
        const val UID = Defaults.DOUBAO_UID

        /** 成功码。**不是** 3000（那是另一条老接口的）。 */
        const val SUCCESS_CODE = 0

        /**
         * 流结束哨兵。它**不是错误** —— 按"非 0 即失败"处理会把每次正常结束都当成失败。
         * 这个名字值得留着，因为它是最容易被误删的一个分支。
         */
        const val END_OF_STREAM_CODE = 20000000

        const val SAMPLE_RATE = 24000
        const val SERVER_MESSAGE_MAX_CHARS = 200

        /**
         * 单次请求的文本上限（字符数）。
         *
         * 取 300 是**远低于任何已知口径**的安全值 —— 见 `synthesizeChunked` 的说明：
         * 官方文档自己就有"1000 / 2000 个 utf-8 字符"两种说法，第三方描述是
         * "1000 字符 ≈ 330 汉字"，字节与码点分不清。无论按哪种解释，300 字都不会越界。
         *
         * 同时也别取得更小：段数一多，并发的连接开销和拼接接缝都会跟着涨。
         */
        const val CHUNK_MAX_CHARS = 300

        /** ID3v2 的固定头长度（`ID3` + 版本 2 字节 + 标志 1 字节 + synchsafe 长度 4 字节）。 */
        const val ID3V2_HEADER = 10

        /** ID3v1 的固定长度，挂在文件末尾。 */
        const val ID3V1_SIZE = 128

        /** 句尾标点。切分时**保留**在上一段里，所以用零宽断言（`(?<=...)`）而不是字符类。 */
        val SENTENCE_END = Regex("(?<=[。！？；!?;])")

        /** 从句标点：只在单句超长时才用得上（见 [hardSplit]）。 */
        val CLAUSE_END = Regex("(?<=[，、：,])")

        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 45L
        const val CALL_TIMEOUT_SECONDS = 90L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 倍率 -> 百分比整数。1.0 -> 0，1.5 -> 50，0.5 -> -50。 */
        // rateToPercent 已删除：语音一律原速（请求里固定 speech_rate = 0），
        // 客户端不再有"倍率"这个概念，留着换算函数只会让人以为语速还能配。

        fun JsonObject.codeOrNull(): Int? =
            this["code"]?.jsonPrimitive?.content?.toIntOrNull()

        /**
         * 取错误说明。
         *
         * 两种外形都认：
         * - 流内错误是平铺的 `{"code":…,"message":"…"}`；
         * - HTTP 层错误是包在 header 里的 `{"header":{"code":…,"message":"…"}}`
         *   （实测 401 就是这个形状，`Invalid X-Api-Key` 藏在里面）。
         *
         * 两种都试，免得把服务端好端端给的原文丢掉、只剩我们自己猜的提示 ——
         * 「API Key 不对」和「Invalid X-Api-Key」给用户的排查价值差得远。
         */
        fun JsonObject.errorMessageOrNull(): String? {
            val flat = this["message"]?.jsonPrimitive?.contentOrNull
            if (!flat.isNullOrBlank()) return flat
            val header = this["header"] as? JsonObject
            val inHeader = header?.get("message")?.jsonPrimitive?.contentOrNull
            if (!inHeader.isNullOrBlank()) return inHeader
            val nested = (this["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            return nested?.takeIf { it.isNotBlank() }
        }

        /**
         * 取音频分片 —— 实测就在顶层 `data`（base64 的 mp3 分片）。
         * `data` 为 null 的行是句尾元信息行（带 `sentence` 字段），不算错。
         *
         * ⚠️ 这里必须用 `contentOrNull` 而**不是** `content`：
         * 对 `"data": null` 这种 JSON null，`content` 会给出字符串 `"null"`，
         * 而 `"null"` 恰好是 4 个合法 base64 字符 —— 于是每遇到一条句尾行，
         * 就会凭空多解出 3 个字节喂进音频里（实测被单测逮到：5037 变 5040）。
         *
         * 用 `java.util.Base64` 而不是 `android.util.Base64`：两者行为一样，
         * 但前者在 JVM 单测里也能跑 —— 于是"解析真实抓到的报文"这件事可以脱离手机验证。
         */
        fun JsonObject.audioData(): ByteArray? {
            val raw = this["data"]?.jsonPrimitive?.contentOrNull
            if (raw.isNullOrBlank()) return null
            return runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
        }
    }
}
