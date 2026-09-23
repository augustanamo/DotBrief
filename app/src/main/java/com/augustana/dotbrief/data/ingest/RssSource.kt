package com.augustana.dotbrief.data.ingest

import com.augustana.dotbrief.data.settings.RssFeed
import com.prof18.rssparser.RssParserBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 新闻取数：并行拉取所有启用的 RSS / Atom 源。
 *
 * 三个刻意的取舍：
 * 1. **并行 + 单个源失败不影响整体**：任何一个源挂了只丢它自己，其余照常参与总结；
 * 2. **每源 8 秒超时**：一个卡住的源不能拖到用户以为"点了没反应"；
 * 3. **只用标题 + 摘要前 120 字**：正文全文塞进 prompt 既贵又会让模型抓不住重点，
 *    而晨报的新闻部分本来只要"一句话快讯"。
 */
class RssSource {

    private val parser by lazy { RssParserBuilder().build() }

    /** 返回「拿到的新闻」与「降级说明」。 */
    suspend fun fetch(
        feeds: List<RssFeed>,
        maxItemsPerFeed: Int,
    ): Pair<List<NewsItem>, List<String>> = coroutineScope {
        val enabled = feeds.filter { it.enabled && it.url.isNotBlank() }
        if (enabled.isEmpty()) return@coroutineScope emptyList<NewsItem>() to emptyList()

        val results: List<Pair<List<NewsItem>?, String?>> = enabled.map { feed ->
            async(Dispatchers.IO) {
                val channel = try {
                    withTimeoutOrNull(PER_FEED_TIMEOUT_MS) { parser.getRssChannel(feed.url) }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    // 用户打断 / 整体超时取消：必须原样抛出，不能当成"这个源拉取失败"
                    throw cancellation
                } catch (error: Exception) {
                    null
                }

                if (channel == null) {
                    null to "${feed.name} 没拉到内容"
                } else {
                    val items = channel.items
                        .asSequence()
                        .take(maxItemsPerFeed)
                        .map { item ->
                            NewsItem(
                                source = feed.name,
                                title = item.title.orEmpty().trim(),
                                summary = cleanSummary(item.description ?: item.content),
                            )
                        }
                        .filter { it.title.isNotBlank() }
                        .toList()
                    items to null
                }
            }
        }.awaitAll()

        val news = results.flatMap { it.first.orEmpty() }
        val warnings = results.mapNotNull { it.second }
        news to warnings
    }

    /** 摘要把 HTML 标签和实体去掉，避免"尖括号"被 TTS 念出来。 */
    private fun cleanSummary(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.replace(HTML_TAG, " ")
            .replace(HTML_ENTITY, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .take(SUMMARY_MAX_CHARS)
    }

    private companion object {
        const val PER_FEED_TIMEOUT_MS = 8_000L
        const val SUMMARY_MAX_CHARS = 120

        val HTML_TAG = Regex("<[^>]*>")
        val HTML_ENTITY = Regex("&[a-zA-Z#0-9]+;")
        val WHITESPACE = Regex("\\s+")
    }
}
