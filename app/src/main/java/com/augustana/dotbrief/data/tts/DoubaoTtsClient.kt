package com.augustana.dotbrief.data.tts

import android.util.Log
import com.augustana.dotbrief.data.settings.Defaults
import com.augustana.dotbrief.data.settings.TtsConfig
import kotlinx.coroutines.Dispatchers
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

    suspend fun synthesize(config: TtsConfig, text: String): SpeechResult = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext SpeechResult.Failure("要朗读的内容是空的")
        }
        if (config.doubaoApiKey.isBlank()) {
            return@withContext SpeechResult.Failure("还没填豆包的 API Key，先去设置页补上")
        }
        if (config.doubaoSpeaker.isBlank()) {
            return@withContext SpeechResult.Failure("还没填音色 ID")
        }

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
                            // 服务端吃整数百分比 [-50, 100]，而我们的配置是倍率（1.0 = 原速）
                            put("speech_rate", rateToPercent(config.speechRate))
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

        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 45L
        const val CALL_TIMEOUT_SECONDS = 90L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 倍率 -> 百分比整数。1.0 -> 0，1.5 -> 50，0.5 -> -50。 */
        fun rateToPercent(rate: Float): Int = ((rate - 1f) * 100f).toInt().coerceIn(-50, 100)

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
