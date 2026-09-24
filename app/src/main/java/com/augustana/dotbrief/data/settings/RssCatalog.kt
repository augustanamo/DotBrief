package com.augustana.dotbrief.data.settings


/**
 * 推荐源目录：设置页「新闻订阅源」二级页里可勾选的预设清单。
 *
 * 与 [Defaults.RSS_FEEDS] 的区别：那是"开箱即用、默认启用"的两条；
 * 这是"可勾选的全部候选"。目录本身只是一份候选清单，不带"启用"语义 ——
 * 勾选状态由用户的 feeds 列表决定（见 `RssSection`）。
 *
 * ## 收录标准（从 top-rss-list 原表 340 条筛到 93 条）
 *
 * 1. **只要机构，不要个人。** 个人博客、独立博主、个人自媒体号一律不收 ——
 *    它们更新不稳定、常常断更，而且"某某今天写了什么"念进简报里意义不大。
 *    留下的是**有编辑部的媒体、有署名的团队博客、平台官方榜单**：
 *    新华社、财新、虎嗅、美团技术团队、知乎热榜、维基百科优良条目。
 *    （阮一峰、酷壳这类个人名站因此被排除了 —— 想看仍可手动添加。）
 * 2. **知名度优先。** 同一领域里只留"报得出名字"的那几家，长尾一律不要。
 * 3. **去重。** 同一家有多个入口的只留一个、优先官方原生 feed
 *    （少数派 4 个入口、36氪 4 个、虎嗅 4 个、小众软件 6 个，全是同一份内容）。
 * 4. **只留中文内容**，英文原站不在"中文区"范围。
 * 5. **不适于播报的不收**：壁纸、网盘、影视资源、代理羊毛这类更新没有念出来的价值。
 *
 * ## 分组
 *
 * 原表是一张没有任何小标题的平表，这里按主题分成 8 组，再配一个搜索框 ——
 * 近百条靠滑还是不容易找到目标，搜"知乎""财新"这类关键词更快。
 *
 * ## 关于地址
 *
 * 少数源给出的是第三方公共实例（rsshub / plink），那是原表里唯一的入口，
 * 稳定性不如官方 feed。所以抓取失败时应当**静默跳过而不是报错**。
 */
val RSS_CATALOG_GROUPS: List<RssCatalogGroup> = listOf(
    RssCatalogGroup(
        title = "科技与数码",
        feeds = listOf(
            RssFeed(id = "p001", name = "IT之家", url = "https://www.ithome.com/rss/"),
            RssFeed(id = "p002", name = "少数派", url = "https://sspai.com/feed"),
            RssFeed(id = "p003", name = "爱范儿", url = "https://www.ifanr.com/feed"),
            RssFeed(id = "p004", name = "36氪", url = "https://36kr.com/feed"),
            RssFeed(id = "p005", name = "虎嗅网", url = "https://www.huxiu.com/rss/0.xml"),
            RssFeed(id = "p006", name = "极客公园", url = "http://www.geekpark.net/rss"),
            RssFeed(id = "p007", name = "钛媒体", url = "https://www.tmtpost.com/feed"),
            RssFeed(id = "p008", name = "cnBeta", url = "https://plink.anyfeeder.com/cnbeta"),
            RssFeed(id = "p009", name = "超能网", url = "https://plink.anyfeeder.com/expreview"),
            RssFeed(id = "p010", name = "雷峰网", url = "https://rsshub.app/leiphone/newsflash"),
            RssFeed(id = "p011", name = "品玩", url = "https://plink.anyfeeder.com/pingwest"),
            RssFeed(id = "p012", name = "量子位", url = "http://plink.anyfeeder.com/weixin/almosthuman2014"),
            RssFeed(id = "p013", name = "机核", url = "https://www.gcores.com/rss"),
            RssFeed(id = "p014", name = "触乐", url = "http://www.chuapp.com/feed"),
            RssFeed(id = "p015", name = "小众软件", url = "https://www.appinn.com/feed/"),
            RssFeed(id = "p016", name = "异次元软件世界", url = "https://feed.iplaysoft.com"),
            RssFeed(id = "p017", name = "HelloGitHub 月刊", url = "http://hellogithub.com/rss"),
            RssFeed(id = "p018", name = "Linux 中国", url = "https://plink.anyfeeder.com/linux.cn"),
        ),
    ),
    RssCatalogGroup(
        title = "新闻与时事",
        feeds = listOf(
            RssFeed(id = "p019", name = "新华社新闻", url = "https://plink.anyfeeder.com/newscn/whxw"),
            RssFeed(id = "p020", name = "人民日报", url = "https://plink.anyfeeder.com/people-daily"),
            RssFeed(id = "p021", name = "央视新闻", url = "https://plink.anyfeeder.com/weixin/cctvnewscenter"),
            RssFeed(id = "p022", name = "澎湃新闻", url = "https://plink.anyfeeder.com/thepaper"),
            RssFeed(id = "p023", name = "新京报", url = "https://plink.anyfeeder.com/bjnews"),
            RssFeed(id = "p024", name = "南方周末", url = "https://rsshub.app/infzm/2"),
            RssFeed(id = "p025", name = "三联生活周刊", url = "https://plink.anyfeeder.com/weixin/lifeweek"),
            RssFeed(id = "p026", name = "环球时报", url = "https://plink.anyfeeder.com/weixin/hqsbwx"),
            RssFeed(id = "p027", name = "参考消息", url = "https://plink.anyfeeder.com/weixin/ckxxwx"),
            RssFeed(id = "p028", name = "联合早报 中港台", url = "https://plink.anyfeeder.com/zaobao/realtime/china"),
            RssFeed(id = "p029", name = "中国日报 时政", url = "https://plink.anyfeeder.com/chinadaily/china"),
            RssFeed(id = "p030", name = "界面新闻", url = "https://plink.anyfeeder.com/jiemian/news"),
            RssFeed(id = "p031", name = "财新网", url = "https://plink.anyfeeder.com/weixin/caixinwang"),
            RssFeed(id = "p032", name = "端传媒", url = "https://plink.anyfeeder.com/initium/latest"),
            RssFeed(id = "p033", name = "纽约时报中文网", url = "http://cn.nytimes.com/rss/news.xml"),
            RssFeed(id = "p034", name = "BBC 中文", url = "https://plink.anyfeeder.com/bbc/cn"),
            RssFeed(id = "p035", name = "路透中文", url = "https://plink.anyfeeder.com/reuters/cn"),
            RssFeed(id = "p036", name = "光明日报", url = "https://plink.anyfeeder.com/guangmingribao"),
        ),
    ),
    RssCatalogGroup(
        title = "财经与商业",
        feeds = listOf(
            RssFeed(id = "p037", name = "华尔街见闻", url = "https://plink.anyfeeder.com/weixin/wallstreetcn"),
            RssFeed(id = "p038", name = "华尔街日报", url = "https://plink.anyfeeder.com/wsj/cn"),
            RssFeed(id = "p039", name = "雪球 今日话题", url = "https://xueqiu.com/hots/topic/rss"),
            RssFeed(id = "p040", name = "财富中文网", url = "https://plink.anyfeeder.com/fortunechina"),
            RssFeed(id = "p041", name = "第一财经周刊", url = "https://plink.anyfeeder.com/weixin/CBNweekly2008"),
            RssFeed(id = "p042", name = "经济观察网", url = "https://plink.anyfeeder.com/eeo"),
            RssFeed(id = "p043", name = "21世纪经济报道", url = "https://plink.anyfeeder.com/weixin/jjbd21"),
            RssFeed(id = "p044", name = "福布斯中国", url = "https://plink.anyfeeder.com/weixin/forbes_china"),
            RssFeed(id = "p045", name = "中国企业家杂志", url = "https://plink.anyfeeder.com/weixin/iceo-com-cn"),
            RssFeed(id = "p046", name = "猎云网", url = "https://plink.anyfeeder.com/lieyunwang"),
            RssFeed(id = "p047", name = "哈佛商业评论", url = "https://plink.anyfeeder.com/weixin/hbrchinese"),
            RssFeed(id = "p048", name = "MIT 科技评论", url = "https://plink.anyfeeder.com/mittrchina/hot"),
        ),
    ),
    RssCatalogGroup(
        title = "开发者与技术",
        feeds = listOf(
            RssFeed(id = "p049", name = "美团技术团队", url = "https://tech.meituan.com/feed"),
            RssFeed(id = "p050", name = "有赞技术团队", url = "https://tech.youzan.com/rss/"),
            RssFeed(id = "p051", name = "掘金 前端", url = "https://rsshub.app/juejin/category/frontend"),
            RssFeed(id = "p052", name = "掘金 字节跳动技术团队", url = "https://rsshub.app/juejin/posts/1838039172387262"),
            RssFeed(id = "p053", name = "360 核心安全技术博客", url = "https://blogs.360.net/rss.html"),
            RssFeed(id = "p054", name = "FreeBuf 网络安全", url = "https://plink.anyfeeder.com/freebuf"),
            RssFeed(id = "p055", name = "开发者头条", url = "https://plink.anyfeeder.com/toutiao.io"),
            RssFeed(id = "p056", name = "Readhub 开发者资讯", url = "https://plink.anyfeeder.com/readhub/technews"),
            RssFeed(id = "p057", name = "码农周刊", url = "https://rsshub.app/manong-weekly"),
        ),
    ),
    RssCatalogGroup(
        title = "社区与热榜",
        feeds = listOf(
            RssFeed(id = "p058", name = "V2EX", url = "https://v2ex.com/index.xml"),
            RssFeed(id = "p059", name = "知乎热榜", url = "https://rsshub.app/zhihu/hotlist"),
            RssFeed(id = "p060", name = "知乎日报", url = "https://rsshub.app/zhihu/daily"),
            RssFeed(id = "p061", name = "微博热搜榜", url = "https://rsshub.app/weibo/search/hot"),
            RssFeed(id = "p062", name = "煎蛋", url = "https://plink.anyfeeder.com/jiandan"),
            RssFeed(id = "p063", name = "抽屉新热榜", url = "https://plink.anyfeeder.com/chouti/hot"),
            RssFeed(id = "p064", name = "Readhub 热门话题", url = "https://plink.anyfeeder.com/readhub/topic"),
            RssFeed(id = "p065", name = "Readhub 每日早报", url = "https://plink.anyfeeder.com/readhub/daily"),
            RssFeed(id = "p066", name = "简书首页", url = "https://plink.anyfeeder.com/jianshu/home"),
            RssFeed(id = "p067", name = "豆瓣最受欢迎的书评", url = "https://www.douban.com/feed/review/book"),
            RssFeed(id = "p068", name = "ONE 一个", url = "https://rsshub.app/one"),
            RssFeed(id = "p069", name = "维基百科优良条目", url = "https://zh.wikipedia.org/w/api.php?action=featuredfeed&feed=good&feedformat=atom"),
        ),
    ),
    RssCatalogGroup(
        title = "设计与产品",
        feeds = listOf(
            RssFeed(id = "p070", name = "人人都是产品经理", url = "https://www.woshipm.com/feed"),
            RssFeed(id = "p071", name = "优设 UISDC", url = "http://www.uisdc.com/feed"),
            RssFeed(id = "p072", name = "三节课", url = "https://plink.anyfeeder.com/weixin/sanjieke01"),
            RssFeed(id = "p073", name = "LinkedIn 领英", url = "https://plink.anyfeeder.com/weixin/LinkedIn-China"),
        ),
    ),
    RssCatalogGroup(
        title = "生活与文化",
        feeds = listOf(
            RssFeed(id = "p074", name = "十点读书", url = "https://plink.anyfeeder.com/weixin/duhaoshu"),
            RssFeed(id = "p075", name = "罗辑思维", url = "https://plink.anyfeeder.com/weixin/luojisw"),
            RssFeed(id = "p076", name = "读库小报", url = "https://plink.anyfeeder.com/weixin/dukuxiaobao"),
            RssFeed(id = "p077", name = "青年文摘", url = "https://plink.anyfeeder.com/weixin/qnwzwx"),
            RssFeed(id = "p078", name = "真实故事计划", url = "https://plink.anyfeeder.com/weixin/zhenshigushi1"),
            RssFeed(id = "p079", name = "果壳网", url = "https://plink.anyfeeder.com/weixin/Guokr42"),
            RssFeed(id = "p080", name = "环球科学", url = "https://plink.anyfeeder.com/weixin/ScientificAmerican"),
            RssFeed(id = "p081", name = "国家人文历史", url = "https://plink.anyfeeder.com/weixin/gjrwls"),
            RssFeed(id = "p082", name = "中国国家地理", url = "https://plink.anyfeeder.com/weixin/dili360"),
            RssFeed(id = "p083", name = "地球知识局", url = "https://plink.anyfeeder.com/weixin/diqiuzhishiju"),
            RssFeed(id = "p084", name = "人物", url = "https://plink.anyfeeder.com/weixin/renwumag1980"),
            RssFeed(id = "p085", name = "看理想", url = "https://plink.anyfeeder.com/weixin/ikanlixiang"),
            RssFeed(id = "p086", name = "丁香医生", url = "https://plink.anyfeeder.com/weixin/DingXiangYiSheng"),
            RssFeed(id = "p087", name = "Vista看天下", url = "https://plink.anyfeeder.com/weixin/vistaweek"),
        ),
    ),
    RssCatalogGroup(
        title = "播客",
        feeds = listOf(
            RssFeed(id = "p088", name = "晚点聊 LateTalk", url = "https://feeds.fireside.fm/latetalk/rss"),
            RssFeed(id = "p089", name = "硅谷101", url = "https://feeds.fireside.fm/sv101/rss"),
            RssFeed(id = "p090", name = "张小珺 商业访谈录", url = "https://feed.xyzfm.space/dk4yh3pkpjp3"),
            RssFeed(id = "p091", name = "42章经", url = "https://feed.xyzfm.space/evgg6xle9rdc"),
            RssFeed(id = "p092", name = "得意忘形", url = "https://feed.xyzfm.space/klaak6nmc3ux"),
            RssFeed(id = "p093", name = "Anyway.FM 设计杂谈", url = "https://anyway.fm/rss.xml"),
        ),
    ),
)
