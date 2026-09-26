package com.augustana.dotbrief.data.ingest

/**
 * 把日历里"一天一行"的节日事件合并成"一次假期"。
 *
 * ## 为什么需要合并
 *
 * 节假日在系统日历里是**一天一个全天事件**：中秋连休三天，就是三行，标题还都带"中秋"。
 * 不合并的话读数是三个独立的节日，念出来就是"今天是中秋节，明天是中秋节，
 * 后天也是中秋节" —— 同一件事被当成三件事说了三遍，而且听不出哪天才是正日子。
 * 合并之后它是一条：**哪天过节、连着放几天**。
 *
 * ## 判据是"相邻天 + 同源标题"
 *
 * 同源 = [normalize] 之后相等，所以"中秋节"与"中秋节假期"会被认成同一件事。
 * 同时要求**日期相邻**：隔了一周的同名事件必然是另一场假期，不能并进来。
 *
 * ## "哪天是正日子"要单独找
 *
 * 假期的第一天**不一定**是节日当天 —— 中秋可能落在三天连休的中间。
 * 所以组里如果存在"没带假期修饰词"的那一行，就以它作节日当天；一行都没有
 * （整个假期都标着"××假期"）才退回第一天。不确定就不瞎猜：
 * 说"今天开始放假"永远是对的，把"假期第二天"说成"今天是中秋"就错了。
 *
 * ## 为什么单独一个文件
 *
 * 这是一段**纯计算**：输入是几行（标题、日期），输出是几个假期，不碰 ContentResolver。
 * 分出来是为了能直接写单元测试 —— "连续多天如何归并"的边界（跨月、隔天、同源不同名）
 * 靠真机日历是试不全的。
 */
internal object FestivalMerge {

    /** 合并前的一行：某一天的一个候选节日（来自日历的一条全天事件）。 */
    data class Row(val title: String, val epochDay: Long)

    /**
     * 像不像节日。
     *
     * 判据是**白名单**：归一化之后必须正好是 [FESTIVAL_NAMES] 里的一个，
     * 或者"名单里的 + 末尾一个'节'"（"中秋节""国庆节""劳动节"）。
     *
     * ## 为什么不用"含'节'字"这种宽松规则
     *
     * 因为误判的**代价不对称**。[CalendarSource] 会把认成节日的全天事件从"安排"里
     * 摘掉（那正是"全天 中秋节"这句错话的修法），所以一次误判 = **用户自己的日程
     * 凭空消失**，比漏报一个冷门节日严重得多 —— 前者用户根本查不出来。
     *
     * 而宽松规则确实会误判，且都是家常日程：
     * - `音乐节`、`美食节` —— 含"节"且只有三个字，一场活动而已；
     * - `中秋聚餐`、`中秋加班` —— 含"中秋"且四个字，一顿饭、一天班。
     *
     * （早先还有一条"长度 ≤4"的兜底，也是为这个。白名单一上，它就成了废话：
     * 既然要求**逐字相等**，长名字自然进不来。）
     *
     * 白名单的代价是冷门节日要手动加一行 —— 一分钟的事，划算。
     *
     * ⚠️ 名单里不必同时收"中秋"和"中秋节"：末尾那个"节"已经被判据允许了，
     * 写"中秋"就够（"中秋节""中秋节假期""中秋佳节"都归到它）。
     */
    fun looksLikeFestival(title: String): Boolean {
        val name = normalize(title)
        if (name.isEmpty()) return false
        if (name in FESTIVAL_NAMES) return true
        return name.endsWith("节") && name.dropLast(1) in FESTIVAL_NAMES
    }

    /**
     * 合并成假期，按"节日当天"从近到远排序，取前 [limit] 条。
     *
     * [todayEpochDay] 与 [Row.epochDay] 都是 `LocalDate.toEpochDay()`（纯数字），
     * 日期比较因此不需要时区 —— 这正是不在这里用 `LocalDate` 的原因：
     * 纯函数里少一份"哪个时区"的隐含上下文，测试也就少一份要摆的桩。
     */
    fun merge(rows: List<Row>, todayEpochDay: Long, limit: Int): List<Festival> {
        if (rows.isEmpty() || limit <= 0) return emptyList()

        // 第一步：按日期归组。只有"紧挨着上一天"且"同源"才并进上一组。
        val groups = mutableListOf<MutableList<Row>>()
        rows.sortedBy { it.epochDay }.forEach { row ->
            if (!looksLikeFestival(row.title)) return@forEach
            val last = groups.lastOrNull()
            val sameRun = last != null &&
                last.last().epochDay + 1 == row.epochDay &&
                normalize(last.first().title) == normalize(row.title)
            if (sameRun) last!! += row else groups += mutableListOf(row)
        }

        // 第二步：每组算成一个假期。
        return groups
            .map { group ->
                val start = group.first().epochDay
                // 正日子：组里标题"原样就等于归一化结果"的那行（即没带"假期/放假"这类修饰词）。
                // 这样的行可能有好几行（整个连休都标着节日名），取最早的一天。
                val exact = group.firstOrNull { it.title.trim() == normalize(it.title) }
                Festival(
                    // 显示名 = 组里**最短**那条标题的归一化结果。
                    // 取最短：它最可能就是那个干净的节日名（"中秋节"优于"中秋节假期"）；
                    // 再归一化一次：订阅源常带尾巴（"中秋节（9月25日）"），
                    // 不归一化的话这串括号会被原样念出来。
                    title = normalize(group.minByOrNull { it.title.length }!!.title),
                    daysFromNow = ((exact ?: group.first()).epochDay - todayEpochDay).toInt(),
                    spanDays = group.size,
                    startDaysFromNow = (start - todayEpochDay).toInt(),
                )
            }
            // 已经放完的假期整条不要（末日在今天之前）。注意判据用**假期末日**而不是节日当天：
            // 中秋在连休中间时，节日过去之后假还在放，那时仍值得提一句。
            .filter { it.startDaysFromNow + it.spanDays > 0 }
            .sortedBy { it.daysFromNow }
            .take(limit)
    }

    /**
     * 归一化：把"同一天的同一个节日的不同写法"收敛成同一个字符串。
     *
     * 依次做四件事：
     * 1. **去掉括号及其内容** —— 订阅源常写"中秋节（9月25日）""春节（休）"；
     * 2. 去掉空白（含全角空格）；
     * 3. 多个节日并列时只取第一个（"国庆节、中秋节"是同一个连休的两天，
     *    取第一个既不会漏掉整个假期，也不会把它拆成两个节日分别报）；
     * 4. 去掉假期类修饰词（[DECORATIONS]）。
     *
     * ⚠️ 修饰词必须**先长后短**：若先删"假期"，"小长假"就会剩下一个孤零零的"小长"。
     * 表已按长度降序写死，改动时保持这个顺序。
     *
     * ⚠️ 有意**不去掉末尾的"节"**：那样"劳动节"会变成"劳动"、"教师节"变成"教师"，
     * 播报时就成了"今天是劳动"。表里都是"假期"这类多字词，不含"节"。
     */
    fun normalize(raw: String): String {
        var name = raw.trim()
        name = BRACKETS.replace(name, "")
        name = WHITESPACE.replace(name, "")
        name = name.substringBefore('、').substringBefore('／').substringBefore('/')
        DECORATIONS.forEach { name = name.replace(it, "") }
        return name
    }

    /**
     * 认得出的节日名（归一化之后的形态）。
     *
     * 与"日历里到底有哪些节日"是两件事：这里只需要把"看起来是节假日订阅"的行认出来。
     * 加一行 = 认一个新节日，不该有任何副作用；所以判据写成逐字相等而不是模糊匹配。
     */
    private val FESTIVAL_NAMES = setOf(
        "元旦", "新年", "春节", "除夕", "元宵", "清明", "端午", "中秋", "重阳",
        "七夕", "腊八", "圣诞", "国庆", "五一", "十一",
        "情人节", "儿童节", "妇女节", "劳动节", "教师节", "建军节", "万圣节",
    )

    private val BRACKETS = Regex("[（(][^）)]*[）)]")

    private val WHITESPACE = Regex("[\\s　]")

    /**
     * 假期类修饰词，**必须按长度降序**（见 [normalize] 的说明）。
     *
     * ⚠️ 表里**只能放多字词**，不能放"休""班""假"这种单字。
     * 单字会被从名字**中间**也删掉，把长名字缩成短的：早先表里有"班"，
     * 于是"中秋假期值班表"被删成"中秋值表"（四个字），正好骗过当时那条长度兜底，
     * 被当成节日并进连休 —— 用户的一条私事就这么没了。
     */
    private val DECORATIONS = listOf(
        "小长假", "黄金周", "假期", "假日", "放假", "连休", "公休", "调休",
    )
}
