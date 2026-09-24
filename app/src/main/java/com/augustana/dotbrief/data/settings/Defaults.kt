package com.augustana.dotbrief.data.settings

import java.time.LocalTime
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
     * 4. 篇幅由 {{MIN_CHARS}} / {{MAX_CHARS}} 运行时注入（来自设置页的「播报篇幅」档位），
     *    改配置不必改 prompt。上限是硬的，下限只是"素材够的时候大约写到这儿" ——
     *    否则素材少的日子模型会为了凑字数往里面注水；
     * 5. 日期只在素材给了的时候才说 —— 每天第一次播报会带上日期，之后不带，
     *    模型必须跟着素材走，而不是自己按"今天"脑补（它算日期经常差一天）。
     */
    val SYSTEM_PROMPT: String = """
        你是用户的私人贴心管家，负责把今天的碎片信息浓缩成一段可以直接念出来的口语晨报。

        这段话是要「听」的，不是要「读」的，所以必须遵守：
        1. 只输出纯文本。禁止出现任何 Markdown 符号（#、*、-、>、`）、编号列表、括号补充说明和网址。
        2. 像真人说话一样自然连贯，不用书面语、不堆形容词、不出现"日历事件""数据来源"这类字段名。
        3. 严格按这个顺序讲：时间与问候 -> 纪念日（今天有生日／纪念日就第一时间单独说一句，它比任何安排都重要）-> 天气 -> 紧急行程（航班／高铁，必须念清时间与提醒）-> 待办与快递包裹（取件码要一字一顿念准）-> 三条核心快讯。
        4. 天气那一句跟着素材的时段走：素材写"明天"就说成明天，写今天的就说今天。带伞、防晒、口罩这些提醒**素材给了才提**，没给就一个字都不要补 —— 素材里没写紫外线，晚上突然来一句"记得防晒"是最典型的穿帮。
        5. 快讯每条只说一句，要带一点你的判断或影响，不要只念标题。
        6. 某个板块今天没有内容就整段跳过，绝对不要说"暂无""无相关信息"。
        7. 时间一律用口语表达，例如"今天上午九点半""下午两点前"，不要用 09:30 这种写法。
        8. 篇幅：全文不超过 {{MAX_CHARS}} 个字，素材充足时大约写到 {{MIN_CHARS}} 字。这是**上限而不是配额** —— 今天没什么可说的就三五句说完，绝不为了凑长度重复、铺陈或加客套话。
        9. 直接输出正文，不要任何开场白、结尾语或自我说明。
        10. 开场只说一次"今天是几月几号"。素材里的"现在是……"如果没有日期，就绝对不要报日期和星期，也不要猜今天几号 —— 用户一天里会听好几次，第二次再报日期是废话。
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

    /**
     * 推荐源目录见 [RSS_CATALOG_GROUPS]：93 条（只收机构、不要个人），单独放在 `RssCatalog.kt`。
     */

    // ---------- TTS ----------
    // 语速与音调不再是设置项：一个滑块换来的收益，远小于它留下的困惑 ——
    // 调过的人（比如顺手拉到 2 倍）过几天只会觉得"怎么念这么快"，却想不起是自己调的。
    // 语音一律按原速、原调合成。
    const val TTS_LOCALE_TAG: String = "zh-CN"

    /**
     * 背景音乐（`res/raw/bgm.mp3`）。默认**开启** —— 它是用户主动放进来的，
     * 而且"点了有回应"这件事本身就是它要解决的问题。
     *
     * 但要留开关：垫底音乐是很个人化的口味，觉得吵的人得能一键关掉，
     * 而不是等下一次发版。两个用途见 `BgmPlayer` 的说明：
     * 等待模型的那几秒当缓冲音，开口之后压到垫底音量。
     */
    const val TTS_BGM_ENABLED: Boolean = true

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
    /**
     * 默认篇幅：约 1 分钟（150–250 字），与改动前那两个字数滑杆的默认值一致 ——
     * 老用户升级上来不会发现简报忽然变长或变短。
     */
    val BRIEF_LENGTH: BriefLength = BriefLength.STANDARD

    val BRIEF_SOURCES: Set<BriefSource> = BriefSource.values().toSet()

    // ---------- 自动更新 ----------
    /**
     * 到点自动把内容刷成新的（只生成、不出声）。
     *
     * 默认开启。理由：不自动刷的话，"点一下就有话说"这件事每次都要等模型几秒 ——
     * 而简报里的日程 / 天气 / 快递本来就是**隔一段时间看一次**就够的信息，
     * 没必要每点一次都去问一次模型。
     */
    const val BRIEF_UPDATE_ENABLED: Boolean = true

    /**
     * 默认每天刷三次：上午十点、下午两点、晚上七点。
     *
     * 这三个时刻对应"一天里真正会想知道外面发生了什么"的三个节点：上班坐定、
     * 下午开工、下班之前。用户点小组件时，落在**最近一个已过时刻之后**的那份内容
     * 会被直接念出来，不再请求模型；判定规则见
     * [com.augustana.dotbrief.domain.BriefSchedule]。
     */
    val BRIEF_UPDATE_TIMES: List<LocalTime> = listOf(
        LocalTime.of(10, 0),
        LocalTime.of(14, 0),
        LocalTime.of(19, 0),
    )

    // ---------- 定时自动播报 ----------
    /**
     * 定时出声播报。默认**关闭**：出声是打扰性动作（像闹钟），不该替用户默认打开。
     */
    const val BRIEF_ALARM_ENABLED: Boolean = false

    /**
     * 默认**空**时刻表 —— 用户得主动加一个（如 07:00）才出声。
     * 与 [BRIEF_UPDATE_TIMES] 的"默认就有三档"刻意相反：刷新是无感的、可以默认给，
     * 出声是有感的、必须用户自己要。
     */
    val BRIEF_ALARM_TIMES: List<LocalTime> = emptyList()

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

    /** 生成一份 AI 配置的稳定 id。 */
    fun newProfileId(): String = UUID.randomUUID().toString()
}
