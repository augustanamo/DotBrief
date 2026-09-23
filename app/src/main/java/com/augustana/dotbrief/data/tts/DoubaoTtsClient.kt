package com.augustana.dotbrief.data.tts

import android.util.Base64
import android.util.Log
import com.augustana.dotbrief.data.settings.DoubaoApiVersion
import com.augustana.dotbrief.data.settings.TtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
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
 * ## 为什么同时支持两代接口
 *
 * 火山引擎的语音合成有两套并行存在的 HTTP 接口，**鉴权头、请求体、响应体全都不同**，
 * 而且音色不通用 —— 大模型音色（`*_bigtts` 系列）只能走 v3，
 * 标准音色（`BV***_streaming`、`zh_female_qingxin` 等）只能走 v1。
 * 用户手上是哪一档取决于他在控制台开通了哪个服务，我们没法替他判断，
 * 所以直接给一个「接口版本」单选，默认 v3（音色最全，也是"豆包语音"当前主推的形态）。
 *
 * | | v1 | v3 |
 * |---|---|---|
 * | 鉴权 | `Authorization: Bearer;{token}` | `X-Api-App-Id` + `X-Api-Access-Key` + `X-Api-Resource-Id` |
 * | 响应 | 单个 JSON，base64 在 `data` | NDJSON，每行一个 `{"code":0,"data":"…"}` |
 * | 成功码 | `code == 3000` | `code == 0`；`code == 20000000` 表示流结束 |
 *
 * v1 的鉴权头是 `Bearer;` —— **分号**不是空格，这是最容易配错的一处。
 */
class DoubaoTtsClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun synthesize(config: TtsConfig, text: String): SpeechResult = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext SpeechResult.Failure("要朗读的内容是空的")
        }
        if (config.doubaoAppId.isBlank() || config.doubaoAccessToken.isBlank()) {
            return@withContext SpeechResult.Failure(
                "还没填豆包的 App ID 和 Access Token，先去设置页补上",
            )
        }
        if (config.doubaoSpeaker.isBlank()) {
            return@withContext SpeechResult.Failure("还没填音色 ID")
        }

        when (config.doubaoApiVersion) {
            DoubaoApiVersion.V3 -> synthesizeV3(config, text)
            DoubaoApiVersion.V1 -> synthesizeV1(config, text)
        }
    }

    // ------------------------------------------------------------------
    // v3：大模型接口
    // ------------------------------------------------------------------

    private fun synthesizeV3(config: TtsConfig, text: String): SpeechResult {
        val payload = buildJsonObject {
            put("user", buildJsonObject { put("uid", UID) })
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
                            // v3 吃整数百分比 [-50, 100]，而我们的配置是倍率（1.0 = 原速）
                            put("speech_rate", rateToPercent(config.speechRate))
                        },
                    )
                },
            )
        }.toString()

        val request = Request.Builder()
            .url(ENDPOINT_V3)
            .addHeader("Content-Type", "application/json")
            .addHeader("Connection", "keep-alive")
            .addHeader("X-Api-App-Id", config.doubaoAppId)
            .addHeader("X-Api-Access-Key", config.doubaoAccessToken)
            .addHeader("X-Api-Resource-Id", config.doubaoResourceId.ifBlank { DEFAULT_RESOURCE_ID })
            .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return execute(request) { raw ->
            // 响应是 NDJSON，音频被切成多个分片各自 base64。
            val audio = ByteArrayOutputStream()
            var serverMessage: String? = null

            raw.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("{") }
                .forEach { line ->
                    val element = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    if (element == null) return@forEach

                    when (val code = element.codeOrNull()) {
                        // 流结束哨兵。注意它**不是 0 而是 20000000**：
                        // 想当然按"非 0 即错误"处理，会把正常结束当成失败。
                        END_OF_STREAM_CODE, null -> Unit
                        0 -> element.base64Data()?.let { audio.write(it) }
                        else -> serverMessage = element.messageOrNull() ?: "豆包返回错误码 $code"
                    }
                }

            when {
                serverMessage != null -> SpeechResult.Failure(serverMessage!!)
                audio.size() == 0 -> SpeechResult.Failure(
                    "豆包没返回音频，检查音色 ID 与 Resource ID 是否配套（2.0 音色必须配 seed-tts-2.0）",
                )
                else -> SpeechResult.Success(audio.toByteArray())
            }
        }
    }

    // ------------------------------------------------------------------
    // v1：小模型接口
    // ------------------------------------------------------------------

    private fun synthesizeV1(config: TtsConfig, text: String): SpeechResult {
        // v1 对单次请求的文本有 1024 字节（UTF-8）硬限制，而中文一个字 3 字节 ——
        // 也就是说 300 多个汉字就会超，简报正文正好在这个量级上，必须主动切段，
        // 否则长文一到就直接被服务端拒掉。切段后顺序拼接 mp3 分片即可（mp3 帧天然可拼接）。
        val chunks = splitByBytes(text, V1_MAX_TEXT_BYTES)
        val audio = ByteArrayOutputStream()

        for (chunk in chunks) {
            val payload = buildJsonObject {
                put(
                    "app",
                    buildJsonObject {
                        put("appid", config.doubaoAppId)
                        // 这个 token 字段在 v1 里是占位性质（官方说明可传任意非空字符串），
                        // 真正的鉴权走 Authorization 头；但传空会被判成参数错误，所以补一个。
                        put("token", config.doubaoAccessToken)
                        put("cluster", config.doubaoCluster.ifBlank { DEFAULT_CLUSTER })
                    },
                )
                put("user", buildJsonObject { put("uid", UID) })
                put(
                    "audio",
                    buildJsonObject {
                        put("voice_type", config.doubaoSpeaker)
                        put("encoding", "mp3")
                        put("speed_ratio", config.speechRate)
                    },
                )
                put(
                    "request",
                    buildJsonObject {
                        put("reqid", UUID.randomUUID().toString())
                        put("text", chunk)
                        put("text_type", "plain")
                        put("operation", "query")
                    },
                )
            }.toString()

            val request = Request.Builder()
                .url(ENDPOINT_V1)
                // 注意是分号，不是空格。写成空格会一路返回鉴权失败。
                .addHeader("Authorization", "Bearer;${config.doubaoAccessToken}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val result = execute(request) { raw ->
                val element = runCatching {
                    json.parseToJsonElement(raw).jsonObject
                }.getOrNull()
                    ?: return@execute SpeechResult.Failure("豆包返回了无法解析的内容")

                if (element.codeOrNull() == V1_SUCCESS_CODE) {
                    element.base64Data()?.let { SpeechResult.Success(it) }
                        ?: SpeechResult.Failure("豆包返回成功但没带音频数据")
                } else {
                    val code = element.codeOrNull()
                    val detail = element.messageOrNull()
                    SpeechResult.Failure(
                        detail?.let { "豆包报错：$it" } ?: "豆包返回错误码 ${code ?: "未知"}",
                    )
                }
            }

            when (result) {
                is SpeechResult.Success -> audio.write(result.audio)
                is SpeechResult.Failure -> return result
            }
        }

        return SpeechResult.Success(audio.toByteArray())
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

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

    private fun describeHttpError(code: Int, raw: String): String {
        val hint = when (code) {
            401, 403 -> "App ID / Access Token 不对，或者这个账号没开通语音合成"
            404 -> "接口地址不存在，检查接口版本选的是 v3 还是 v1"
            429 -> "请求太频繁或额度用完了"
            in 500..599 -> "豆包服务端出错了，稍后再试"
            else -> "豆包拒绝了这次请求"
        }
        // 服务端自己给的 message 通常比我们的猜测具体得多，优先带出去。
        val detail = runCatching {
            json.parseToJsonElement(raw).jsonObject.messageOrNull()
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

        const val ENDPOINT_V3 = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
        const val ENDPOINT_V1 = "https://openspeech.bytedance.com/api/v1/tts"

        /** v1 的成功码是 3000（不是 0），v3 才是 0。 */
        const val V1_SUCCESS_CODE = 3000

        /** v3 用它表示"流结束了"，不是错误。 */
        const val END_OF_STREAM_CODE = 20000000

        const val V1_MAX_TEXT_BYTES = 900
        const val SAMPLE_RATE = 24000
        const val UID = "briefwidget"
        const val DEFAULT_RESOURCE_ID = "seed-tts-2.0"
        const val DEFAULT_CLUSTER = "volcano_tts"
        const val SERVER_MESSAGE_MAX_CHARS = 200

        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 45L
        const val CALL_TIMEOUT_SECONDS = 90L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 句子边界。v1 切段时优先切在这些符号后面，避免把一句话劈成两半。 */
        const val PUNCTUATION = "。！？；!?;\n"

        /** 倍率 -> 百分比整数。1.0 -> 0，1.5 -> 50，0.5 -> -50。 */
        fun rateToPercent(rate: Float): Int = ((rate - 1f) * 100f).toInt().coerceIn(-50, 100)

        fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

        /**
         * 按 UTF-8 字节数切段。
         *
         * 先按句读切句，再贪心地把句子拼到 [maxBytes] 以内；
         * 万一存在单句就超长的情况（没有标点的长串），只能对它硬切。
         * 三级处理都是为了同一个目标：**别把一句话从中间劈开**，
         * 否则拼出来的音频会有"上一条念到半个词、下一条从头接"的割裂感。
         */
        fun splitByBytes(text: String, maxBytes: Int): List<String> {
            if (utf8Size(text) <= maxBytes) return listOf(text)

            val sentences = mutableListOf<String>()
            val sentence = StringBuilder()
            for (char in text) {
                sentence.append(char)
                if (PUNCTUATION.contains(char)) {
                    sentences += sentence.toString()
                    sentence.clear()
                }
            }
            if (sentence.isNotEmpty()) sentences += sentence.toString()

            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            fun flush() {
                if (current.isNotBlank()) {
                    chunks += current.toString().trim()
                    current.clear()
                }
            }

            for (item in sentences) {
                if (utf8Size(item) > maxBytes) {
                    // 单句就超限：只能硬切
                    flush()
                    val piece = StringBuilder()
                    for (char in item) {
                        if (utf8Size(piece.toString()) + utf8Size(char.toString()) > maxBytes) {
                            chunks += piece.toString()
                            piece.clear()
                        }
                        piece.append(char)
                    }
                    current.append(piece)
                    continue
                }
                if (utf8Size(current.toString()) + utf8Size(item) > maxBytes) flush()
                current.append(item)
            }
            flush()
            return chunks.filter { it.isNotBlank() }
        }

        fun JsonObject.codeOrNull(): Int? =
            this["code"]?.jsonPrimitive?.content?.toIntOrNull()

        fun JsonObject.messageOrNull(): String? =
            this["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        fun JsonObject.base64Data(): ByteArray? {
            val raw = this["data"]?.jsonPrimitive?.content
            if (raw.isNullOrBlank()) return null
            return runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()
        }
    }
}
