package com.augustana.dotbrief.data.settings

import java.util.UUID

/** 出厂默认值集中在此处，便于一键恢复默认与单元测试断言。 */
object Defaults {

    // ---------- LLM ----------
    /** 默认指向 DeepSeek，兼容 OpenAI 协议；换成任意 OpenAI 兼容端点只需改 Base URL。 */
    const val LLM_BASE_URL: String = "https://api.deepseek.com/v1"
    const val LLM_MODEL: String = "deepseek-chat"

    /**
     * 读取响应的超时。
     *
     * 定成 90 秒而不是 30 秒，因为现在主流模型普遍带"思维链"：
     * Gemini 3.x、DeepSeek-R1 这类在给出正文前会先生成一大段推理 token，
     * 30 秒对它们经常不够 —— 表现就是"偶尔成功、经常超时"，非常难排查。
     * 宁可让用户多等一会儿，也不要给一个随机失败的接口。
     */
    const val LLM_TIMEOUT_SECONDS: Int = 90
    const val LLM_TEMPERATURE: Float = 0.6f

    /**
     * 上一版把超时默认写成了 30 秒。
     *
     * 存量用户的 DataStore 里存的就是这个 30，它**不是用户主动选的**，只是旧默认值。
     * 所以启动时要做一次性迁移升到 [LLM_TIMEOUT_SECONDS]，否则"改大了默认值"对老用户完全无效
     * —— 他们打开设置看到 30 秒，还以为是自己配的。
     * 迁移带标记位，只跑一次，因此用户日后真的想调回 30 秒不会被反复改回去。
     */
    const val LEGACY_LLM_TIMEOUT_SECONDS: Int = 30

    /**
     * 系统提示词：面向「听」而不是「读」优化。
     * 五个关键约束：
     * 1. 纯文本，禁 Markdown 符号（TTS 会把 * 读成「星号」）；
     * 2. 固定的信息顺序，保证每天听到的结构稳定；
     * 3. 口语化时间表达（九点半，而不是 09:30）；
     * 4. 字数区间由 {{MIN_CHARS}} / {{MAX_CHARS}} 运行时注入，改配置不必改 prompt；
     * 5. 日期只在素材给了的时候才说 —— 每天第一次播报会带上日期，之后不带，
     *    模型必须跟着素材走，而不是自己按"今天"脑补（它算日期经常差一天）。
     */
    val SYSTEM_PROMPT: String = """
        你是用户的私人贴心管家，负责把今天的碎片信息浓缩成一段可以直接念出来的口语晨报。

        这段话是要「听」的，不是要「读」的，所以必须遵守：
        1. 只输出纯文本。禁止出现任何 Markdown 符号（#、*、-、>、`）、编号列表、括号补充说明和网址。
        2. 像真人说话一样自然连贯，不用书面语、不堆形容词、不出现"日历事件""数据来源"这类字段名。
        3. 严格按这个顺序讲：时间与问候 -> 纪念日（今天有生日／纪念日就第一时间单独说一句，它比任何安排都重要）-> 天气（先报温度和天气，再按素材给的提示说要不要带伞、要不要防晒、要不要戴口罩）-> 紧急行程（航班／高铁，必须念清时间与提醒）-> 待办与快递包裹（取件码要一字一顿念准）-> 三条核心快讯。
        4. 快讯每条只说一句，要带一点你的判断或影响，不要只念标题。
        5. 某个板块今天没有内容就整段跳过，绝对不要说"暂无""无相关信息"。
        6. 时间一律用口语表达，例如"今天上午九点半""下午两点前"，不要用 09:30 这种写法。
        7. 全文控制在 {{MIN_CHARS}} 到 {{MAX_CHARS}} 个汉字之间，读出来大约 40 到 60 秒。
        8. 直接输出正文，不要任何开场白、结尾语或自我说明。
        9. 开场只说一次"今天是几月几号"。素材里的"现在是……"如果没有日期，就绝对不要报日期和星期，也不要猜今天几号 —— 用户一天里会听好几次，第二次再报日期是废话。
    """.trimIndent()

    /** 运行时注入的占位符，供 PromptBuilder 替换。 */
    const val PLACEHOLDER_MIN_CHARS: String = "{{MIN_CHARS}}"
    const val PLACEHOLDER_MAX_CHARS: String = "{{MAX_CHARS}}"

    // ---------- RSS ----------
    const val RSS_MAX_ITEMS_PER_FEED: Int = 5
    const val RSS_NEWS_COUNT: Int = 3

    val RSS_FEEDS: List<RssFeed> = listOf(
        RssFeed(id = "preset-ithome", name = "IT之家", url = "https://www.ithome.com/rss/"),
        RssFeed(id = "preset-sspai", name = "少数派", url = "https://sspai.com/feed"),
    )

    // ---------- TTS ----------
    const val TTS_SPEECH_RATE: Float = 1.0f
    const val TTS_PITCH: Float = 1.0f
    const val TTS_LOCALE_TAG: String = "zh-CN"

    // ---------- 豆包（火山引擎）语音合成 ----------
    /**
     * 语音合成 v3 的 unidirectional 接口。
     *
     * 这套接口只有一条路：单 `X-Api-Key` 鉴权，响应是 NDJSON 流
     * （每行一个 `{"code":0,"data":"…"}`，末行 `code` 为 20000000 表示结束）。
     * 老的三件套鉴权（App ID + Access Token + Cluster）已经去掉了 ——
     * 留着就得同时维护两套鉴权、两套响应解析，而 Key 和音色还互不通用。
     */
    const val DOUBAO_ENDPOINT: String = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"

    /** 请求体里的命名空间，这套协议固定 BidirectionalTTS。 */
    const val DOUBAO_NAMESPACE: String = "BidirectionalTTS"

    /** 资源 ID：决定"你这把 Key 能调哪一代模型"，必须和所选音色的代次匹配。 */
    const val DOUBAO_RESOURCE_ID: String = "seed-tts-2.0"

    /** 通用中文女声 2.0，音色自然、不挑场景，适合当默认值。 */
    const val DOUBAO_SPEAKER: String = "zh_female_vv_uranus_bigtts"

    /** 服务端要求请求体里带一个 uid，仅用于日志追溯，随便填。 */
    const val DOUBAO_UID: String = "dotbrief"

    // ---------- 简报 ----------
    const val BRIEF_MIN_CHARS: Int = 150
    const val BRIEF_MAX_CHARS: Int = 250

    val BRIEF_SOURCES: Set<BriefSource> = BriefSource.values().toSet()

    // ---------- 抓取 ----------
    const val LOOKAHEAD_HOURS: Int = 24
    const val RETENTION_DAYS: Int = 7

    /** 命中任一关键字即视为「行程类」通知。 */
    val TRIP_KEYWORDS: List<String> = listOf(
        "车票", "车次", "列车", "航班", "值机", "登机", "安检", "候车", "检票",
        "出票", "改签", "退票", "行程", "起飞", "到达",
    )

    /** 命中任一关键字即视为「快递类」通知。 */
    val PACKAGE_KEYWORDS: List<String> = listOf(
        "取件码", "取货码", "派件", "快递", "已签收", "待取", "驿站", "丰巢",
        "菜鸟", "快递柜", "包裹",
    )

    // ---------- 小组件外观 ----------
    /**
     * 点阵主色相。64° 正好是原来那版黄绿（#F2FF3C），所以默认值与改动前完全一致，
     * 老用户升级上来不会发现颜色变了。
     */
    const val WIDGET_ACCENT_HUE: Float = 64f

    /** 1.0 = 原来的鲜艳度；调到 0 会一路褪成灰白。 */
    const val WIDGET_ACCENT_SATURATION: Float = 1f

    /**
     * 霓虹轮播。
     *
     * 默认关闭：它是一个"常驻动画"（下面有说明），开着会一直让桌面在切帧，
     * 属于用户主动选择的代价，不该由默认值替他决定。
     */
    const val WIDGET_ACCENT_CAROUSEL: Boolean = false

    /** 轮播节奏默认值：42 帧一圈，约 11 秒走完色环。 */
    val WIDGET_ACCENT_CAROUSEL_PACE: CarouselPace = CarouselPace.NORMAL

    /** 生成一个稳定唯一的订阅源 id。 */
    fun newFeedId(): String = UUID.randomUUID().toString()
}
