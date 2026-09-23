package com.augustana.dotbrief.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对**真实抓到的报文**做解析回归。
 *
 * 夹具 `tts_unidirectional_sample.ndjson` 是从 v3 接口的实际响应里原样截出来的四行：
 * 两条音频分片、一条句尾元信息行、一条结束哨兵。之所以用真报文而不是手写 JSON，
 * 是因为这里最容易错的恰恰是"报文长什么样"这件事 —— 实测之前，
 * 我们以为音频在 `event == "TTSResponse"` 的 `audio.data` 里，而实际在顶层 `data`，
 * 且结束哨兵是 `code == 20000000`（不是 0，也不是错误）。这些细节手写是编不出来的。
 */
class DoubaoTtsClientTest {

    private val client = DoubaoTtsClient()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "夹具 $name 不存在" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    /** 正常流：音频按行拼接，结束哨兵不能被当成失败。 */
    @Test
    fun `拼接音频分片并忽略结束哨兵`() {
        val result = client.parseStream(fixture("tts_unidirectional_sample.ndjson"))

        assertTrue("应当是成功，实际：$result", result is SpeechResult.Success)
        val audio = (result as SpeechResult.Success).audio
        // 5037 = 夹具里两条音频分片解出来之后的字节数之和（见生成夹具时的统计）
        assertEquals(5037, audio.size)
        // mp3 的魔数：ID3 标签头。拼错了（少一截/顺序反了）这里就对不上。
        assertEquals("ID3", String(audio.copyOf(3), Charsets.US_ASCII))
    }

    /** 只有结束哨兵、没有音频时，要说"没返回音频"，而不是"错误码 20000000"。 */
    @Test
    fun `只有结束哨兵时算没音频而不是报错`() {
        val result = client.parseStream("""{"code":20000000,"message":"OK","data":null}""")

        val failure = result as SpeechResult.Failure
        assertTrue("不该把它当错误码，实际：${failure.message}", "20000000" !in failure.message)
        assertTrue(failure.message.contains("没返回音频"))
    }

    /** 流内错误：原样带出服务端的 message。 */
    @Test
    fun `流内错误码带出服务端说明`() {
        val result = client.parseStream(
            """{"code":55000000,"message":"resource id 与音色不匹配","data":null}""",
        )

        assertEquals("resource id 与音色不匹配", (result as SpeechResult.Failure).message)
    }

    /** HTTP 401 的错误被包在 `header` 里 —— 这是实测形状，别只看顶层。 */
    @Test
    fun `header 包裹的 401 能带出原文`() {
        val raw =
            """{"header":{"reqid":"X","code":45000010,"message":"Invalid X-Api-Key"}}"""

        val message = client.describeHttpError(401, raw)

        assertTrue("应当带出服务端原文，实际：$message", message.contains("Invalid X-Api-Key"))
    }

    /** 网关心跳之类没有 code 的行不该被判成错误，有音频就顺手收下。 */
    @Test
    fun `没有 code 的行不判错`() {
        val result = client.parseStream(
            """
            {"seq":1}
            {"data":"SUQz"}
            """.trimIndent(),
        )

        assertTrue(result is SpeechResult.Success)
        assertEquals("ID3", String((result as SpeechResult.Success).audio, Charsets.US_ASCII))
    }

    /** 非 JSON 行（心跳、空行）直接跳过，不能让整次合成失败。 */
    @Test
    fun `非 JSON 行被跳过`() {
        val result = client.parseStream(
            """
            : keep-alive

            {"code":0,"message":"","data":"SUQz"}
            {"code":20000000,"message":"OK","data":null}
            """.trimIndent(),
        )

        assertTrue(result is SpeechResult.Success)
    }
}
