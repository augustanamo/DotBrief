package com.augustana.dotbrief.data.llm

import android.util.Log
import com.augustana.dotbrief.data.settings.LlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 调用结果。失败一律带着**能直接念给用户听**的中文说明，不抛异常。 */
sealed interface LlmResult {
    data class Success(val text: String) : LlmResult
    data class Failure(val message: String) : LlmResult
}

/** 连通性自检结果。 */
sealed interface LlmProbeResult {
    /** [latencyMillis] 是这一趟往返的耗时，用它判断网络质量。 */
    data class Reachable(val latencyMillis: Long) : LlmProbeResult
    data class Unreachable(val message: String) : LlmProbeResult
}

/**
 * OpenAI 兼容协议的对话补全客户端。
 *
 * 只要端点遵守 `POST {baseUrl}/chat/completions` 这一条约定，
 * DeepSeek / OpenAI / 通义 / 月之暗面 / Gemini 的 `/v1beta/openai/` / 本地 Ollama 都能直接用，
 * 换服务商只需要在设置页改 Base URL + 模型名，不用改代码。
 *
 * 之所以不用 Retrofit：这里只有一个 POST，一个 JSON body，
 * 手写 OkHttp 比引一套注解 + 转换器更少黑盒（出错时能直接看到原始响应体）。
 */
class LlmClient {

    /** newBuilder() 是浅拷贝，复用连接池与线程池，不会每调用一次就漏一个 client。 */
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(config: LlmConfig, systemPrompt: String, userPrompt: String): LlmResult =
        withContext(Dispatchers.IO) {
            if (!config.isReady) {
                return@withContext LlmResult.Failure("还没填大模型接口，先去设置页配上 Base URL、API Key 和模型名")
            }

            val payload = buildJsonObject {
                put("model", config.model)
                put("temperature", config.temperature)
                put("stream", false)
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", systemPrompt)
                            },
                        )
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", userPrompt)
                            },
                        )
                    },
                )
            }.toString()

            perform(config, payload).fold(
                onSuccess = { raw ->
                    val content = extractContent(raw)
                    if (content.isNullOrBlank()) {
                        LlmResult.Failure("模型返回了空内容，检查一下模型名是否正确")
                    } else {
                        LlmResult.Success(content)
                    }
                },
                onFailure = { LlmResult.Failure(it.message ?: "请求失败") },
            )
        }

    /**
     * 连通性自检：只发一句"你好"，并限制 `max_tokens`，成本约等于零。
     *
     * 单独做这个入口的理由很实际——大模型接口最常见的失败是**网络到不了**，
     * 而它在应用里的表现是"点了半天没反应然后报超时"，很难自己定位。
     * 自检会把「地址拼错 / 域名被墙 / Key 无效 / 模型名不存在」分开讲清楚。
     */
    suspend fun probe(config: LlmConfig): LlmProbeResult = withContext(Dispatchers.IO) {
        if (!config.isReady) {
            return@withContext LlmProbeResult.Unreachable("Base URL / API Key / 模型名还没填全")
        }

        val payload = buildJsonObject {
            put("model", config.model)
            put("max_tokens", 1)
            put("temperature", 0)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "你好")
                        },
                    )
                },
            )
        }.toString()

        val startedAt = System.currentTimeMillis()
        perform(config, payload).fold(
            onSuccess = { LlmProbeResult.Reachable(System.currentTimeMillis() - startedAt) },
            onFailure = { LlmProbeResult.Unreachable(it.message ?: "自检失败") },
        )
    }

    /** 真正发请求。成功给原始响应体，失败给已经译成人话的说明。 */
    private fun perform(config: LlmConfig, payload: String): Result<String> {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        Log.i(TAG, "POST $url model=${config.model}")

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val client = baseClient.newBuilder()
            // 用户配置的超时以"读取响应"为准，另给一段余量作为整体硬上限。
            // 之前这里把 readTimeout 写死成 30s，导致配置里的超时值其实管不到读取阶段 ——
            // 带思维链的模型（Gemini 3.x、DeepSeek-R1 等）经常要 30s 以上，
            // 表现就是"设置里调大了超时也还是失败"。
            .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(config.timeoutSeconds.toLong() + CALL_TIMEOUT_MARGIN_SECONDS, TimeUnit.SECONDS)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(raw)
                } else {
                    Result.failure(LlmException(describeHttpError(response.code, raw)))
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // 用户中途点了打断 -> 协程被取消。这不能当成"请求失败"报给用户，
            // 否则每次打断都会在设置页留下一条假错误。
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(LlmException(describeNetworkError(error)))
        }
    }

    private fun extractContent(raw: String): String? = runCatching {
        json.parseToJsonElement(raw)
            .jsonObject["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
    }.getOrNull()

    /** 把服务商返回的错误体裁短，塞进一条能播报的说明里。 */
    private fun describeHttpError(code: Int, raw: String): String {
        // 原始响应只进日志，不进播报——里面可能带额度、账号信息，不该被念出来
        Log.w(TAG, "LLM HTTP $code: ${raw.take(500)}")

        val hint = when (code) {
            400 -> "请求被拒绝，多半是模型名写错了"
            401, 403 -> "API Key 无效或没有该模型的权限"
            404 -> "接口地址或模型名不存在"
            429 -> "请求太频繁或额度用完了"
            402 -> "账户余额不足"
            in 500..599 -> "服务商这边出错了"
            else -> "请求被拒绝"
        }

        // 服务商自己给的说明通常比我们的猜测具体得多。
        // 例如 Gemini 会直说"这个模型已对新用户下线，请改用 xxx"，这句话才是用户真正需要的。
        val detail = extractServerMessage(raw)
        return if (detail == null) {
            "$hint（HTTP $code）"
        } else {
            "$hint（HTTP $code）：$detail"
        }
    }

    /**
     * 从错误响应里抠出人话版的 message。
     *
     * 必须同时兼容两种形状，因为它们是真实存在差异的：
     *   {"error": {"message": "..."}}          —— OpenAI / DeepSeek 等
     *   [{"error": {"message": "..."}}]        —— Gemini 的 OpenAI 兼容层
     */
    private fun extractServerMessage(raw: String): String? = runCatching {
        val root = json.parseToJsonElement(raw)
        val element = if (root is JsonArray) root.firstOrNull() else root

        element
            ?.jsonObject
            ?.get("error")
            ?.jsonObject
            ?.get("message")
            ?.jsonPrimitive
            ?.content
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(SERVER_MESSAGE_MAX_CHARS)
    }.getOrNull()

    /**
     * 把异常翻译成普通话说清楚。
     *
     * 特别注意 [java.io.InterruptedIOException]：它是 OkHttp 的 callTimeout 打满时抛的，
     * 表现为"所有超时都到点了也没连上"。这在大陆网络访问境外 API 时极其常见，
     * 所以这里必须给出"这个地址可能到不了"的明确指引，而不是丢一个类名给用户。
     */
    private fun describeNetworkError(error: Throwable): String = when (error) {
        is java.net.UnknownHostException ->
            "域名解析不了，检查 Base URL 有没有拼错"

        // SocketTimeoutException 是 InterruptedIOException 的子类，必须排在它前面。
        // 它自己还分两种：连接阶段超时（多半是被屏蔽）和等待响应超时（多半是慢/排队）。
        is java.net.SocketTimeoutException ->
            if (error.message.orEmpty().contains("connect", ignoreCase = true)) {
                "连不上接口地址（连接超时）。这个地址在你的网络下很可能被屏蔽了，" +
                    "可换用国内可直连的服务商（如 DeepSeek、通义、智谱）"
            } else {
                "接口响应超时，网络慢或服务商在排队，稍后再试"
            }

        is java.io.InterruptedIOException ->
            "接口一直没响应（整体超时）。这个地址在你的网络下很可能根本连不上，" +
                "可换用国内可直连的服务商（如 DeepSeek、通义、智谱），或确认手机走了代理"

        is java.net.ConnectException, is java.net.SocketException ->
            "连不上接口地址，端口被挡或地址写错了"

        is javax.net.ssl.SSLException ->
            "HTTPS 握手失败，检查 Base URL 是否该用 https"

        else -> "请求失败：${error.javaClass.simpleName}"
    }

    private companion object {
        const val TAG = "BriefLlm"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val CALL_TIMEOUT_MARGIN_SECONDS = 15L
        const val SERVER_MESSAGE_MAX_CHARS = 220
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** 内部用它把"已译成人话的错误"沿着 Result 往上带。 */
private class LlmException(message: String) : Exception(message)
