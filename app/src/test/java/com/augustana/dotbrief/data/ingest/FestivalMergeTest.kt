package com.augustana.dotbrief.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 节日合并的回归测试。
 *
 * 要挡住的是一句具体的错话 —— 主人原话："中秋放三天，他说今天是中秋节，
 * 明天是中秋节，后天是中秋节。" 根因是节假日在日历里**一天一行**，
 * 三天就是三条独立记录。这个文件盯的就是"三天能变回一条"，
 * 以及几个容易想歪的边界：节日不在假期第一天、假期跨在昨天、私人日程被误认。
 *
 * 用 [LocalDate.ofEpochDay] 换算日期而不是写死 epochDay 数字：
 * 数字看不出是哪天，出问题时连"这条用例在测什么日期"都要现算。
 */
class FestivalMergeTest {

    /** 2026-09-25（中秋节当天）的 epochDay。 */
    private val today = LocalDate.of(2026, 9, 25).toEpochDay()

    private fun row(title: String, dayOffset: Long) =
        FestivalMerge.Row(title = title, epochDay = today + dayOffset)

    // ---- 核心：连休三天要合成一条 ----

    @Test
    fun `中秋连休三天合成一条`() {
        val merged = FestivalMerge.merge(
            rows = listOf(row("中秋节", 0), row("中秋节", 1), row("中秋节", 2)),
            todayEpochDay = today,
            limit = 3,
        )

        assertEquals(1, merged.size)
        val festival = merged.single()
        assertEquals("中秋节", festival.title)
        assertEquals(0, festival.daysFromNow)
        assertEquals(3, festival.spanDays)
        assertEquals(0, festival.startDaysFromNow)
    }

    @Test
    fun `同源不同名也能合并 - 假期后缀被归一化`() {
        // 订阅源常这么写：节日当天是"中秋节"，其余几天是"中秋节假期"。
        // 不归一化就是两条，念出来还是重复。
        val merged = FestivalMerge.merge(
            rows = listOf(row("中秋节", 0), row("中秋节假期", 1), row("中秋节假期", 2)),
            todayEpochDay = today,
            limit = 3,
        )

        assertEquals(1, merged.size)
        assertEquals("中秋节", merged.single().title)
        assertEquals(3, merged.single().spanDays)
    }

    @Test
    fun `标题带括号时显示名不含括号`() {
        // 订阅源里"中秋节（9月25日）"这种写法很常见，括号内容不该被念出来。
        val merged = FestivalMerge.merge(
            rows = listOf(row("中秋节（9月25日）", 0)),
            todayEpochDay = today,
            limit = 3,
        )

        assertEquals("中秋节", merged.single().title)
    }

    // ---- 边界：节日不一定是假期第一天 ----

    @Test
    fun `节日落在连休中间 - 今天开始放假但今天不过节`() {
        // 今天 9/25 是假期第一天，中秋在 9/26。两件事各有各的日子，
        // 数据这边必须分得开，否则播报要么说错"今天是中秋"，要么少说一天假。
        val merged = FestivalMerge.merge(
            rows = listOf(row("中秋节假期", 0), row("中秋节", 1), row("中秋节假期", 2)),
            todayEpochDay = today,
            limit = 3,
        )

        val festival = merged.single()
        assertEquals(1, festival.daysFromNow)
        assertEquals(0, festival.startDaysFromNow)
        assertEquals(3, festival.spanDays)
    }

    @Test
    fun `假期中段且节日已过 - 还在放假但不能再说过节`() {
        // 中秋是昨天（9/24），今天 9/25 是假期第二天，明天还放一天。
        // 三天在日历里是三行，而"昨天那行"必须也读进来 —— 只读今天和明天的话，
        // 会算成"假期从今天开始"（见 CalendarSource 往前多取几天的理由）。
        val merged = FestivalMerge.merge(
            rows = listOf(
                row("中秋节", -1),
                row("中秋节假期", 0),
                row("中秋节假期", 1),
            ),
            todayEpochDay = today,
            limit = 3,
        )

        val festival = merged.single()
        assertEquals(-1, festival.daysFromNow)
        assertEquals(3, festival.spanDays)
        assertTrue("假期还没放完", festival.isHolidayNow)
    }

    @Test
    fun `往前的行会被算进 spanDays - 今天不该被说成假期第一天`() {
        // ⚠️ 这条是 CalendarSource 查询要往前多取几天的原因：
        // 只往后查的话，假期前几行看不到，于是把"假期第 3 天"算成"今天开始放假"。
        val merged = FestivalMerge.merge(
            rows = listOf(
                row("中秋节假期", -2),
                row("中秋节假期", -1),
                row("中秋节", 0),
                row("中秋节假期", 1),
            ),
            todayEpochDay = today,
            limit = 3,
        )

        val festival = merged.single()
        assertEquals(4, festival.spanDays)
        assertEquals(-2, festival.startDaysFromNow)
        assertEquals(0, festival.daysFromNow)
    }

    // ---- 不该合并的 ----

    @Test
    fun `隔了一周的同名事件不合并`() {
        // 中秋只有一个，但用户的日历里可能既有节假日订阅、又有自己建的日程。
        // 靠"日期相邻"这道门把它们分开 —— 隔一周显然不是同一次连休。
        val merged = FestivalMerge.merge(
            rows = listOf(row("中秋节", 0), row("中秋节", 7)),
            todayEpochDay = today,
            limit = 3,
        )

        assertEquals(2, merged.size)
        assertEquals(1, merged[0].spanDays)
        assertEquals(1, merged[1].spanDays)
    }

    @Test
    fun `相邻但不同节日不合并`() {
        val merged = FestivalMerge.merge(
            rows = listOf(row("中秋节", 0), row("国庆节", 1)),
            todayEpochDay = today,
            limit = 3,
        )

        assertEquals(2, merged.size)
        assertEquals(listOf("中秋节", "国庆节"), merged.map { it.title })
    }

    @Test
    fun `已经放完的假期整条不要`() {
        // 昨天就结束了。查询往前多取会捞到它，但要滤掉 ——
        // 不然每天早上都会听到一句上周的假期。
        val merged = FestivalMerge.merge(
            rows = listOf(row("中秋节", -3), row("中秋节", -2), row("中秋节", -1)),
            todayEpochDay = today,
            limit = 3,
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `节日已过但假期今天还放 - 保留`() {
        // 中秋在前天，假期最后一天是今天。判据用**假期末日**而不是节日当天：
        // 人还在放假，这事就该提。
        val merged = FestivalMerge.merge(
            rows = listOf(row("中秋节", -2), row("中秋节假期", -1), row("中秋节假期", 0)),
            todayEpochDay = today,
            limit = 3,
        )

        assertEquals(1, merged.size)
        assertTrue(merged.single().isHolidayNow)
    }

    // ---- 别把私事当节日 ----

    @Test
    fun `私人日程不被认成节日`() {
        // 含"中秋"两个字，但它是自己的安排，不是节假日订阅。
        assertFalse(FestivalMerge.looksLikeFestival("中秋家庭聚会"))
        assertFalse(FestivalMerge.looksLikeFestival("中秋假期值班表"))
        assertFalse(FestivalMerge.looksLikeFestival("出差"))
        assertFalse(FestivalMerge.looksLikeFestival("年假"))

        // ⚠️ 下面两条是"放宽判据"最容易踩的坑，而后果比说错话重得多：
        // 认错了 [CalendarSource] 会把这条全天日程从"安排"里摘掉，用户的一天就没了。
        // 所以判据是白名单（逐字相等）而不是"含'节'字""含'中秋'"这类模糊匹配。
        assertFalse("一场活动而已，不是节日", FestivalMerge.looksLikeFestival("音乐节"))
        assertFalse("一顿饭而已，不是节日", FestivalMerge.looksLikeFestival("中秋聚餐"))
        assertFalse("一场活动而已，不是节日", FestivalMerge.looksLikeFestival("美食节"))
        // 单字修饰词会把名字从中间删掉、缩成短的 —— "中秋加班"曾经因此变成"中秋加"。
        assertFalse("一天班而已，不是节日", FestivalMerge.looksLikeFestival("中秋加班"))

        assertTrue(FestivalMerge.looksLikeFestival("中秋节"))
        assertTrue(FestivalMerge.looksLikeFestival("中秋节假期"))
        assertTrue(FestivalMerge.looksLikeFestival("国庆节"))
        assertTrue(FestivalMerge.looksLikeFestival("中秋"))
        assertTrue(FestivalMerge.looksLikeFestival("元旦"))
        assertTrue("名单里的名字后面挂一个'节'也认", FestivalMerge.looksLikeFestival("劳动节放假"))
    }

    @Test
    fun `私事混在连休中间不会被打断`() {
        // 用户在假期中间加了一条"中秋家庭聚会"（全天）：那一天日历里**同时**有
        // 订阅源的那行，私事只是多出来的一行 —— 它不该被并进假期，也不该把连休顶断。
        val merged = FestivalMerge.merge(
            rows = listOf(
                row("中秋节", -1),
                row("中秋家庭聚会", 0),
                row("中秋节假期", 0),
                row("中秋节假期", 1),
            ),
            todayEpochDay = today,
            limit = 3,
        )

        assertEquals(1, merged.size)
        assertEquals(3, merged.single().spanDays)
    }

    // ---- 归一化本身 ----

    @Test
    fun `归一化去掉括号 空白 并列与假期后缀`() {
        assertEquals("中秋节", FestivalMerge.normalize("中秋节（9月25日）"))
        assertEquals("中秋节", FestivalMerge.normalize("  中秋节  "))
        assertEquals("中秋节", FestivalMerge.normalize("中秋节假期"))
        assertEquals("国庆节", FestivalMerge.normalize("国庆节、中秋节"))
        assertEquals("劳动节", FestivalMerge.normalize("劳动节"))
        // ⚠️ 修饰词表里不能有"节"：有的话"劳动节"会变成"劳动"。
        assertEquals("劳动节", FestivalMerge.normalize("劳动节放假"))
    }

    // ---- 排序与截断 ----

    @Test
    fun `乱序输入按日期归组 结果按节日当天排序`() {
        val merged = FestivalMerge.merge(
            rows = listOf(
                row("国庆节", 6),
                row("中秋节", 2),
                row("中秋节", 1),
                row("国庆节", 5),
                row("元旦", 20),
            ),
            todayEpochDay = today,
            limit = 3,
        )

        assertEquals(listOf("中秋节", "国庆节", "元旦"), merged.map { it.title })
        // 中秋两天（+1、+2），国庆两行（+5、+6）是紧挨着的另一段假期。
        assertEquals(2, merged[0].spanDays)
        assertEquals(1, merged[0].startDaysFromNow)
        assertEquals(2, merged[1].spanDays)
        assertEquals(5, merged[1].startDaysFromNow)
        assertEquals(20, merged[2].daysFromNow)
    }

    @Test
    fun `超过上限时只留最近的几个`() {
        val merged = FestivalMerge.merge(
            rows = listOf(row("中秋节", 1), row("国庆节", 5), row("元旦", 20)),
            todayEpochDay = today,
            limit = 2,
        )

        assertEquals(2, merged.size)
        assertEquals(listOf("中秋节", "国庆节"), merged.map { it.title })
    }

    @Test
    fun `空输入与零上限返回空表`() {
        assertTrue(FestivalMerge.merge(emptyList(), today, 3).isEmpty())
        assertTrue(
            FestivalMerge.merge(listOf(row("中秋节", 0)), today, limit = 0).isEmpty(),
        )
    }

    // ---- 派生属性 ----

    @Test
    fun `isHolidayNow 覆盖假期首末两端`() {
        // 假期 9/25-9/27，节日当天 9/26。逐天看这三个派生值的组合。
        val rows = listOf(
            row("中秋节假期", 0),
            row("中秋节", 1),
            row("中秋节假期", 2),
        )

        (0L..2L).forEach { offset ->
            val festival = FestivalMerge.merge(rows, today + offset, limit = 3).single()
            assertTrue("第 ${offset + 1} 天仍在假期内", festival.isHolidayNow)
            assertEquals("假期长度不随今天是哪天变", 3, festival.spanDays)
            assertEquals("假期起点不随今天是哪天变", -(offset.toInt()), festival.startDaysFromNow)
        }

        // 假期结束后：这一天既不该留着假期，也不该残余什么。
        assertTrue(FestivalMerge.merge(rows, today + 3, limit = 3).isEmpty())
    }
}
