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
     * 4. 篇幅**不设档位**：由素材量决定，素材多就多说、素材少就少说。
     *    这里曾经运行时注入 {{MIN_CHARS}} / {{MAX_CHARS}}（来自设置页那三档「播报篇幅」），
     *    但那两个数字两头都不讨好 —— 素材多的日子被硬砍，素材少的日子被逼注水；
     * 5. 日期只在素材给了的时候才说 —— 每天第一次播报会带上日期，之后不带，
     *    模型必须跟着素材走，而不是自己按"今天"脑补（它算日期经常差一天）。
     */
    val SYSTEM_PROMPT: String = """
        你是用户的私人贴心管家，负责把今天的碎片信息浓缩成一段可以直接念出来的口语晨报。

        这段话是要「听」的，不是要「读」的，所以必须遵守：
        1. 只输出纯文本。禁止出现任何 Markdown 符号（#、*、-、>、`）、编号列表、括号补充说明和网址。
        2. 像真人说话一样自然连贯，不用书面语、不堆形容词、不出现"日历事件""数据来源"这类字段名。
        3. 严格按这个顺序讲：时间与问候 -> 纪念日（今天有生日／纪念日就第一时间单独说一句，它比任何安排都重要）-> 天气 -> 紧急行程（航班／高铁，必须念清时间与提醒）-> 待办与快递包裹（取件码要一字一顿念准）-> 新闻快讯（素材里的快讯尽量都讲到，一条一句）。
        4. 天气那一句跟着素材的时段走：素材写"明天"就说成明天，写今天的就说今天。带伞、防晒、口罩这些提醒**素材给了才提**，没给就一个字都不要补 —— 素材里没写紫外线，晚上突然来一句"记得防晒"是最典型的穿帮。
        5. 快讯一条一句，要带一点你的判断或影响，不要只念标题；也别把两件不相干的事
           并成一句。素材里有多少条值得听就讲多少条，不要只挑两三条交差。
           同一件事（同一个事件、同一款产品、同一家公司）在素材里出现多次时，合并成一条讲：
           不要前面说一次、后面又补一句 —— 哪怕两次讲的不是同一个角度，听的人只会觉得你重复了。
           要合并的只是"同一件事"，不是"同类的事"：两件不同的事再像，也各讲各的。
        6. 某个板块今天没有内容就整段跳过，绝对不要说"暂无""无相关信息"。
        7. 时间一律用口语表达，例如"今天上午九点半""下午两点前"，不要用 09:30 这种写法。
        8. 篇幅跟着素材走，上限是五分钟：素材多就把该说的都说清楚（快讯尤其别漏），
           素材少就三五句收住。绝不为了凑长度重复、铺陈或加客套话，也不要为了显得短
           而丢掉素材里真正要紧的事。
        9. 直接输出正文，不要任何开场白、结尾语或自我说明。
        10. 开场只说一次"今天是几月几号"。素材里的"现在是……"如果没有日期，就绝对不要报日期和星期，也不要猜今天几号 —— 用户一天里会听好几次，第二次再报日期是废话。
    """.trimIndent()

    /**
     * 老自定义提示词里的占位符，仍由 `PromptBuilder.systemPrompt` 替换。
     *
     * 默认提示词（[SYSTEM_PROMPT]）**已经不含**它们了 —— 篇幅改由素材决定。
     * 留着这条替换只为存量用户：他们那份自定义提示词里可能还写着
     * "全文不超过 {{MAX_CHARS}} 个字"，不替换的话模型会直接读到一串花括号。
     *
     * 兜底值与 [BRIEF_MAX_CHARS] 是同一个数：占位符和代码里的天花板必须一致，
     * 否则老用户的自定义提示词会与"五分钟"打架 —— 而那个数早就没法在界面上改了。
     */
    const val PLACEHOLDER_MIN_CHARS: String = "{{MIN_CHARS}}"
    const val PLACEHOLDER_MAX_CHARS: String = "{{MAX_CHARS}}"
    const val LEGACY_MIN_CHARS: Int = 120

    /**
     * ⚠️ 必须与 [BRIEF_MAX_CHARS] 是同一个数（现在都是 1350）。
     *
     * 这里写成字面值而不是引用，是因为 [BRIEF_MAX_CHARS] 声明在本文件**靠后**的
     * 「简报」段里 —— `const val` 引用一个后面才声明的常量，Kotlin 会报
     * "must be initialized"（编译期常量不做前向解析）。
     * 两者的一致性由 `DefaultsTest` 盯着：改了一处忘了另一处，单元测试会红。
     */
    const val LEGACY_MAX_CHARS: Int = 1350

    // ---------- RSS ----------
    /**
     * 每个源取几条候选。
     *
     * 这是**实现细节，不是设置项**：用户能控的是"勾哪些源"，源越多候选越丰富；
     * 最终讲几条由模型按素材决定。从前这两个数字都摆在设置页上，
     * 结果是"勾了十个源也只讲三条"，多勾的源白勾。
     */
    const val RSS_ITEMS_PER_FEED: Int = 6

    /**
     * 候选总量上限：勾了很多源时的保护 —— 提示词长到一定程度，模型反而抓不住重点。
     *
     * 超出时**各源轮流取**（见 `RssSource.fetch`），不会出现"排在前面的几个源
     * 把名额占满、后面的源一条都进不来"。
     */
    const val RSS_MAX_CANDIDATES: Int = 48

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
    // 这里曾经有一个 BRIEF_LENGTH（篇幅三档），已移除：念多长由素材决定，
    // 规则写在 [SYSTEM_PROMPT] 第 8 条里。

    /**
     * 中文播报语速（字/秒）—— **实测标定值，别凭印象改**。
     *
     * 标定方法：从真机的语音缓存里量出来的一条真实数据 —— 222 字的正文合成了
     * 44.35 秒音频，222 / 44.35 ≈ 5.0。
     *
     * ⚠️ 它统计的是**含标点的字符数**：TTS 在标点处也要停顿，按"纯汉字数"算会低估时长。
     * 换音色、换语速（现在不可配）之后这个数要重新标，因为它同时决定
     * 「能讲多少」和「会不会超时」两件事。
     */
    const val SPEECH_CHARS_PER_SECOND: Float = 5f

    /** 整段播报的时长预算（秒）：五分钟。 */
    const val BRIEF_MAX_SECONDS: Int = 300

    /**
     * 正文字数天花板 —— 由「五分钟」换算而来，**不是配额，是防失控**。
     *
     * 篇幅本身跟着素材走（见 [SYSTEM_PROMPT] 第 8 条），这个数只负责一件事：
     * 模型偶尔跑偏吐出一大段时，别真的念上十分钟。
     *
     *     300 秒 × 5 字/秒 = 1500 字，乘 0.9 留 10% 余量 → 1350 字
     *
     * 乘 0.9 是因为 5 字/秒是平均水平：念数字、型号、专有名词（"真武 V900"这类）
     * 会比平时慢，留点余量让那些日子也不会越过五分钟。
     *
     * ⚠️ 1350 是**字面值**，不是上面两个常量的算式 —— `const val` 不能用运行时表达式初始化，
     * 而 [LEGACY_MAX_CHARS] 又必须引用它（两者是同一个数，不能各写一份）。
     * 所以换算过程写在注释里，由 `DefaultsTest` 盯着"字面值 == 算式结果"：
     * 改了时长预算却忘了改这里，单元测试会红。
     */
    const val BRIEF_MAX_CHARS: Int = 1350

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
