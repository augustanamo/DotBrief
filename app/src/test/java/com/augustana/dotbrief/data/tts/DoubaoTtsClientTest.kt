package com.augustana.dotbrief.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    // ------------------------------------------------------------------
    // 长文本分段：五分钟的播报约 1350 字，必须切成多段并行合成
    // ------------------------------------------------------------------

    /** 短文本原样返回 —— 绝不能为了"统一"把它也切一遍（试听一句、本地简报都走这里）。 */
    @Test
    fun `短文本不切段`() {
        val text = "早上好。今天晴。"

        assertEquals(listOf(text), client.splitForSpeech(text))
    }

    /**
     * 长文本按句切段：每段不超上限、**一个字都不能丢**、且段尾落在句读上。
     *
     * 「不丢字」是这里最要紧的断言：切分算法一旦在某处把标点吃掉，
     * 结果是"声音里少了一句话"，而日志、时长、缓存全都正常 —— 极难发现。
     */
    @Test
    fun `长文本按句切段且不丢字`() {
        // 每句 24 字，重复 30 次 = 720 字，必然切成多段
        val text = "这是一句测试用的完整话，用来把正文撑到需要分段。".repeat(30)

        val chunks = client.splitForSpeech(text)

        assertTrue("应当切成多段，实际 ${chunks.size} 段", chunks.size > 1)
        assertTrue("每段不得超过 300 字：${chunks.map { it.length }}", chunks.all { it.length <= CHUNK })
        assertEquals("切分不能丢字", text, chunks.joinToString(""))
        // 除最后一段外都必须止于句读：从句子中间断开会让前后两段各自起头收尾，
        // 念出来是明显的断句错误（这是"切在句读之后"这条规则的落点）。
        assertTrue(
            "段尾必须落在句读上：${chunks.dropLast(1).map { it.takeLast(1) }}",
            chunks.dropLast(1).all { it.endsWith("。") },
        )
    }

    /** 模型偶尔写出没有句号的流水句：退到逗号切，仍超长才按字数硬切。 */
    @Test
    fun `没有句号的超长文本也能切开`() {
        // 6 × 81 = 486 字，一个句号都没有
        val text = ("甲".repeat(80) + "，").repeat(6)

        val chunks = client.splitForSpeech(text)

        assertTrue("应当切成多段，实际 ${chunks.size} 段", chunks.size > 1)
        assertTrue("每段不得超过 300 字：${chunks.map { it.length }}", chunks.all { it.length <= CHUNK })
        assertEquals("切分不能丢字", text, chunks.joinToString(""))
    }

    /**
     * 剥 ID3：头部 ID3v2 + 尾部 ID3v1 都要去掉，中间音频一个字节不动。
     *
     * ID3v2 的长度字段是 **synchsafe** 的（每字节只用低 7 位），按普通大端读会算大一倍 ——
     * 那样剥完会连带啃掉一段真实音频，所以这里特意用一个超过 127 的长度来卡它：
     * 100 若按普通大端读仍是 100，于是用 200 才验得出高低位差异。
     */
    @Test
    fun `剥掉头部 ID3v2 与尾部 ID3v1`() {
        val audio = id3v2(200) + ByteArray(50) { 0x11 } + id3v1()

        val stripped = client.stripId3(audio)

        assertEquals("只剩中间 50 字节音频", 50, stripped.size)
        assertTrue("中间音频不能被改动", stripped.all { it == 0x11.toByte() })
    }

    /** 没有 ID3 的音频要原样返回（同一个对象最好，省一次复制）。 */
    @Test
    fun `没有标签时原样返回`() {
        val audio = ByteArray(64) { 0x22 }

        assertSame(audio, client.stripId3(audio))
    }

    /**
     * 拼接：第一段原样保留（含 ID3v2，播放器靠它读元信息），之后每段剥头再拼。
     *
     * 不剥的话，第二段的 ID3 会被解码器当成音频帧去同步 —— 接缝处一声杂音，
     * 严重时整段跳帧。
     */
    @Test
    fun `拼接时第一段保留头部其余剥掉`() {
        val first = id3v2(20) + ByteArray(10) { 0x01 }
        val second = id3v2(30) + ByteArray(10) { 0x02 }

        val joined = client.joinAudio(listOf(first, second))

        assertEquals("首段整段 + 次段剥头后的 10 字节", first.size + 10, joined.size)
        assertEquals("ID3", String(joined.copyOf(3), Charsets.US_ASCII))
        assertEquals("首段原样在前", first.toList(), joined.take(first.size))
    }

    /** 单段不拼接：必须原样返回，不能白复制一遍。 */
    @Test
    fun `只有一段时不拼接`() {
        val only = ByteArray(32) { 0x33 }

        assertSame(only, client.joinAudio(listOf(only)))
    }

    private companion object {
        /** 与 `DoubaoTtsClient.CHUNK_MAX_CHARS` 同值：测试夹具按它构造，改一处要改两处。 */
        const val CHUNK = 300

        /** 造一个 ID3v2 标签头 + [payloadSize] 字节的标签内容（长度按 synchsafe 编码）。 */
        fun id3v2(payloadSize: Int): ByteArray = ByteArray(10 + payloadSize).also { bytes ->
            bytes[0] = 'I'.code.toByte()
            bytes[1] = 'D'.code.toByte()
            bytes[2] = '3'.code.toByte()
            bytes[3] = 4 // 版本 4
            bytes[4] = 0
            bytes[5] = 0 // 标志位
            bytes[6] = ((payloadSize shr 21) and 0x7f).toByte()
            bytes[7] = ((payloadSize shr 14) and 0x7f).toByte()
            bytes[8] = ((payloadSize shr 7) and 0x7f).toByte()
            bytes[9] = (payloadSize and 0x7f).toByte()
        }

        /** 造一个 128 字节的 ID3v1 尾。 */
        fun id3v1(): ByteArray = ByteArray(128).also { bytes ->
            bytes[0] = 'T'.code.toByte()
            bytes[1] = 'A'.code.toByte()
            bytes[2] = 'G'.code.toByte()
        }
    }
}
