package com.augustana.dotbrief.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 盯着 [Defaults] 里那组**互相牵连的数字**。
 *
 * 篇幅相关的三个常量是一根链：语速 → 时长预算 → 字数天花板，而字数天花板在代码里
 * 只能写成字面值（`const val` 不能用运行时表达式初始化，且它要被另一个 `const val` 引用）。
 * 于是"改了预算忘了改字数"成了一个纯粹的静默错误 —— 编译过、测试过，
 * 只有用户某天听到一段被提前砍掉的播报时才暴露。
 *
 * 这个测试就是那道闸：改动其中任何一个，它都会红。
 */
class DefaultsTest {

    @Test
    fun `字数天花板与语速和时长预算对得上`() {
        val expected = (Defaults.BRIEF_MAX_SECONDS * Defaults.SPEECH_CHARS_PER_SECOND * 0.9f).toInt()

        assertEquals(
            "改了 SPEECH_CHARS_PER_SECOND 或 BRIEF_MAX_SECONDS，" +
                "就要同步改 BRIEF_MAX_CHARS 的字面值（当前应为 $expected）",
            expected,
            Defaults.BRIEF_MAX_CHARS,
        )
    }

    @Test
    fun `老提示词的占位符兜底值就是新的字数天花板`() {
        // 两者必须是同一个数：一个是代码里的硬截断，一个是老用户自定义提示词里
        // {{MAX_CHARS}} 的替换值。不一致时，模型会按一个数写、代码按另一个数砍。
        assertEquals(Defaults.BRIEF_MAX_CHARS, Defaults.LEGACY_MAX_CHARS)
    }

    @Test
    fun `五分钟的播报不超过接口单次请求上限`() {
        // 只做量级校验，真正的约束在 DoubaoTtsClient 的 CHUNK_MAX_CHARS（300 字/段）。
        // 这里防的是"预算调到十分钟以上，却没想起来分段"。
        assertEquals(300, Defaults.BRIEF_MAX_SECONDS)
    }
}
