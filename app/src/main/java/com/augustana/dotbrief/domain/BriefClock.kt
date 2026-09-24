package com.augustana.dotbrief.domain

import com.augustana.dotbrief.data.ingest.SpokenTime
import java.time.LocalDateTime

/**
 * 播报**定时缓存**时，把正文开头那句过时的时间换成此刻。
 *
 * ## 为什么必须有这一步
 *
 * 简报正文是以"现在是……"或一句问候语开场的（系统提示词要求"时间与问候"先说，
 * `LocalBriefComposer` 也是这么拼的）。而在新的用法里，这段文字是**十点生成、
 * 十一点才被念出来**的 —— 原样念就是"上午十点"配下午两点的窗外。
 *
 * 所以定时缓存这条路必须做一次"补开场"：正文照旧，只把开场那句换成此刻。
 *
 * ## 两条路线（[isAlarm]）
 *
 * - **闹钟**：开场说具体时间（"现在是上午7点"），像闹钟报时 —— 用户拿它当起床铃，
 *   得知道几点了；
 * - **其余**（点小组件、设置页"立即播报"）：只说时段问候语（"早上好"），
 *   不说具体几点几分 —— 这种场景用户要的是"今天怎么样"，报"上午10点52分"是多余的精确。
 *
 * ## 为什么是替换而不是重新生成
 *
 * 重新生成就等于又请求一次模型，正是这一版要消掉的东西。而开场时间是**唯一**
 * 与"生成时刻"绑定的部分：日程、天气、取件码、快讯都在各自素材里带着自己的时间。
 *
 * ## 与 `includeDate` 的联动
 *
 * [includeDate] 走的是与现场生成同一套规则（今天第一次开口才报日期）。
 * 因为这里是**整段替换**，日期与问候会跟着一起变：缓存生成时可能带了"9月23日 星期三"，
 * 而播的时候用户今天已经听过一遍了，这次就该只剩"下午好"——否则一天里的第二次播报
 * 会再报一遍日期。
 *
 * ## 深夜结尾补一句（[withLateNightCare]）
 *
 * 缓存可能是白天生成的，结尾没有"夜深了，早点休息"这句话。而深夜播放时结尾该有它 ——
 * 它和开场一样，都是"按此刻补"而不是"按生成时刻"。所以 refresh 除了换开场，
 * 还在深夜（23 点–次日 5 点前）给结尾补一句关心休息的话。原文已有类似话则不重复。
 *
 * ## 认不出来就原样播
 *
 * 模型偶尔不按格式来（正文不以"现在是"或问候语开头）。这时**不动**正文：
 * 时间可能说旧了，但内容一个字都不丢。反过来按位置硬切，切掉的可能是天气或日程 ——
 * 那才是真的糟糕。深夜结尾的贴心话在这种情况也会补上（不硬切开场 ≠ 不补结尾）。
 */
object BriefClock {

    private const val PREFIX = "现在是"

    /**
     * 开场句的跨度：从"现在是"或一句问候语起，到第一个句读或换行前。
     *
     * 同时认"现在是"和各个问候语开头：缓存正文的开场可能是任一种
     * （生成时的格式取决于来源是闹钟还是普通，见 [SpokenTime.greetingOpen]）。
     * 定 40 字上限，是因为它只该覆盖"9月23日 星期三 上午10点"或"早上好"这种量级。
     * 上限的用法见 [refresh] —— **匹配吃满上限时宁可不动**，
     * 否则会把一句没有标点的长句切成"新时间 + 旧残句"两半。
     */
    private val LEADING_OPEN = Regex("^(现在是|早上好|上午好|中午好|下午好|晚上好|早安|晚安)[^，。！？\\n]{0,40}")

    /** 时间句的合法收尾。匹配停在这些字符之前，才算"认出了时间句"。 */
    private val STOPPERS = charArrayOf('，', '。', '！', '？', '\n')

    /**
     * 紧跟开场的时间问候，会跟着开场时间一起走。
     *
     * 只认这几个词，且只在正文**开头一小段**里替换：问候语本来就只出现在开场，
     * 但正文里（比如快讯里提一句"早安"）也可能出现，那处不该被我们改。
     */
    private val GREETING = Regex("早上好|上午好|中午好|下午好|晚上好|早安|晚安")

    /** 只在正文前 16 个字内找问候语，够覆盖"现在是……，早上好"这种写法。 */
    private const val GREETING_WINDOW = 16

    /**
     * 原文开场可能带"，早上好"这样的尾巴问候（"现在是上午10点，早上好"）。
     *
     * 非[isAlarm]刷新后新开场已含正确问候语（"下午好"），再留着尾巴里的旧问候就成了
     * "下午好，早上好"——重复且时段矛盾。所以非闹钟时把这种尾巴问候去掉。
     * 闹钟场景留着它是对的（"现在是上午7点，早上好" = 报时 + 问候，合理），
     * 只交由 [fixGreeting] 把时段改对。
     */
    private val STALE_TAIL_GREETING = Regex("^[，,]\\s*(早上好|上午好|中午好|下午好|晚上好|早安|晚安)")

    /**
     * 开场句的收尾标点。
     *
     * 与 [STOPPERS] 的区别是**不认逗号**：开场句内部本来就可能带逗号
     * （"9月23日 星期三，下午好"），拿逗号当收尾会把开场句切成两半。
     */
    private val SENTENCE_END_CHARS = charArrayOf('。', '！', '？', '\n')

    /**
     * 能被认成"开场句"的开头。
     *
     * 三种形态（见 [SpokenTime.nowText] / [SpokenTime.greetingOpen]）：
     * - 闹钟的"现在是…"；
     * - 纯问候"早上好"等；
     * - 带日期的问候"9月23日 星期三，早上好"—— 以数字开头，所以要单独认出来。
     */
    private val OPENING_HEAD = Regex("^(现在是|早上好|上午好|中午好|下午好|晚上好|早安|晚安|\\d{1,2}月\\d{1,2}日)")

    /**
     * 开场句的长度上限。
     *
     * 最长的形态是闹钟那句："现在是9月23日 星期三 上午10点52分。"，22 字。
     * 定 30 是留一点余量；真越过它说明认出来的压根不是开场句，宁可不切。
     */
    private const val OPENING_MAX_CHARS = 30

    /**
     * 关心休息类的话：原文已包含任一就不重复加（见 [withLateNightCare]）。
     */
    private val CARE_HINT = Regex("早点休息|注意休息|早点睡|晚安|好梦|别熬夜|别熬")

    /**
     * @param text 缓存里的正文
     * @param includeDate 今天还没报过日期时为真（与现场生成同一判据）
     * @param at 此刻
     * @param isAlarm 定时播报（闹钟）为真：开场说具体时间；否则只说问候语
     */
    fun refresh(text: String, includeDate: Boolean, at: LocalDateTime, isAlarm: Boolean = false): String {
        val match = LEADING_OPEN.find(text) ?: return withLateNightCare(text, at.hour)
        // 匹配结束的位置必须是个句读（或正好是结尾）。
        // 不是的话说明这开头压根没有"时间句"的形状（比如一句没有标点的长句子），
        // 这时**原样返回**：时间可能说旧了，但内容一个字都不丢 ——
        // 而按位置硬切，切出来的半句是真的没法听。
        val following = text.getOrNull(match.range.last + 1)
        if (following != null && following !in STOPPERS) return withLateNightCare(text, at.hour)

        // 按匹配区间直接拼接，而不是用 replaceFirst 的替换串：
        // 替换串里的 $ 和 \ 会被当成分组引用，而这段内容来自时间文本 ——
        // 虽然现在只有中文数字，但不值得留这个隐患。
        // （顺带一提，Kotlin 的 Regex.replaceFirst 只有字符串重载，没有 lambda 版。）
        var tail = text.substring(match.range.last + 1)
        val newOpen = if (isAlarm) {
            // 闹钟：说具体时间（"现在是上午7点"），像闹钟报时。
            PREFIX + SpokenTime.nowText(includeDate, at)
        } else {
            // 非闹钟：去掉尾巴里的旧问候（"现在是10点，早上好" → "下午好"，
            // 不去就变成"下午好，早上好"），再拼上新问候语。
            tail = STALE_TAIL_GREETING.replaceFirst(tail, "")
            SpokenTime.greetingOpen(includeDate, at)
        }
        val replaced = newOpen + tail
        val fixed = fixGreeting(replaced, at.hour)
        return withLateNightCare(fixed, at.hour)
    }

    /**
     * 把正文切成「开场句」与「其余」两段，给语音合成分别用；认不出开场时返回 null。
     *
     * ## 为什么要切
     *
     * 两段的新鲜度完全不同：开场说的是**此刻**（"现在是上午11点" / "上午好"），
     * 其余部分讲今天发生的事，与"什么时候念"无关。合成上却一直是整段一起送 ——
     * 开场那句一变，整段正文的语音缓存就全废了。这正是语音缓存当年必须挂一个
     * 15 分钟 TTL 的唯一理由（见 [com.augustana.dotbrief.tts.SpeechCache]）。
     *
     * 切开之后各按各的内容缓存：正文段写一次可以一直用；开场段里那些固定问候语
     * （"早上好"这类一共五种）也只在第一次请求一次。
     *
     * ## 切在哪
     *
     * 切在开场句**结束的那个句末标点之后**（标点留在开场段里）。绝不能切在句子中间 ——
     * 一段音频内部的语调是连续的，从中间断开会让前后两段各自"收尾 / 起头"，
     * 念出来是明显的断句错误（与 `DoubaoTtsClient` 的分段是同一条规矩）。
     *
     * 开场句必须**认得出**（[OPENING_HEAD]）且**够短**（[OPENING_MAX_CHARS]）才切。
     * 认不出就返回 null、调用方整段一起处理：模型偶尔不按格式来，那时宁可缓存粗一点
     * 也不要在句子里下刀 —— 时间可能说旧了，但内容一个字都不丢。
     */
    fun splitOpening(text: String): Pair<String, String>? {
        if (OPENING_HEAD.find(text)?.range?.first != 0) return null
        // 第一个**句末**标点的位置；这场没有句末标点（模型写了流水句）就不切。
        val end = text.indexOfFirst { it in SENTENCE_END_CHARS }
        if (end < 0 || end >= OPENING_MAX_CHARS) return null

        val body = text.substring(end + 1)
        // 正文只剩空白（整篇就是一句开场）不切：切了只会多一次请求，
        // 拼回来的还是同一段音频。
        if (body.isBlank()) return null
        return text.substring(0, end + 1) to body
    }

    /**
     * 深夜在结尾补一句关心休息的话。
     *
     * 缓存可能是白天生成的（结尾没有"早点休息"），而深夜播放时结尾该有这句话 ——
     * 它和开场一样，都是"按此刻补"而不是"按生成时刻"。所以 refresh 除了换开场，
     * 还要看看此刻是不是深夜，是的话在结尾补一句。
     *
     * 原文已经包含关心休息类的话（[CARE_HINT]）就不重复 ——
     * 白天生成的缓存如果恰好带了这类词，深夜播放时也不会再加一遍。
     */
    private fun withLateNightCare(text: String, hour: Int): String {
        // ⚠️ 先摘掉"上一次按深夜补进去的那句" —— 这是补的**反面**，缺了它照样串：
        // 凌晨 1 点生成（或刷新）的缓存带着"夜深了，早点休息。"，第二天上午 9 点被念出来，
        // 开场已经换成"早上好"，结尾却还挂着这句，一段话里早晚打架。
        val base = text.stripStaleCare()
        val care = SpokenTime.lateNightCare(hour) ?: return base
        if (base.contains(CARE_HINT)) return base
        val trimmed = base.trimEnd()
        val last = trimmed.lastOrNull()
        // 原文以句读结尾就直接接上，否则补一个句号再接 ——
        // 贴心话本身带句号（"夜深了，早点休息。"）。
        return if (last != null && last in PAUSE_CHARS) {
            "$trimmed$care"
        } else {
            "$trimmed。$care"
        }
    }

    /**
     * 摘掉结尾那句"按旧时刻补进去"的深夜告别。
     *
     * 只认**逐字**的 [SpokenTime.LATE_NIGHT_CARE]（我们自己拼的那句）。模型自己写的变体
     * （"夜里了，早点休息"）摘不了 —— 那种情况交给缓存新鲜度挡：深夜生成的内容本来就
     * 很少活到白天。宁可漏摘，也不要在结尾乱删一句可能是正文的话。
     */
    private fun String.stripStaleCare(): String {
        val trimmed = trimEnd()
        if (!trimmed.endsWith(SpokenTime.LATE_NIGHT_CARE)) return this
        return trimmed.dropLast(SpokenTime.LATE_NIGHT_CARE.length)
    }

    /** 中英文句读都认，避免缓存正文以英文句号收尾时多出一个空格。 */
    private val PAUSE_CHARS = charArrayOf('。', '！', '？', '；', '，', '!', '?')

    /** 把开场附近的"早上好"换成与此刻相符的那一句。 */
    private fun fixGreeting(text: String, hour: Int): String {
        val match = GREETING.find(text) ?: return text
        if (match.range.first >= GREETING_WINDOW) return text
        return text.replaceRange(match.range, SpokenTime.greetingFor(hour))
    }

    /** 与 [SpokenTime.greetingFor] 同一套分档，保留给测试与历史调用方用。 */
    fun greetingFor(hour: Int): String = SpokenTime.greetingFor(hour)
}
