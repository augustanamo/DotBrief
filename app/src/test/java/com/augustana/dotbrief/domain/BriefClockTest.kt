package com.augustana.dotbrief.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * 播报定时缓存时"补开场 + 深夜补结尾"的回归测试。
 *
 * 这一段是全流程里唯一会**改写模型输出**的地方，所以两条底线都得有测试兜着：
 * 改对的时候开场与问候一起走；认不出来的时候一个字都不许动。
 *
 * 两条路线（[BriefClock.refresh] 的 `isAlarm`）：
 * - 闹钟：开场说具体时间（"现在是下午2点52分"）；
 * - 其余：只说问候语（"下午好"），并把旧开场尾巴里的问候清掉，避免"下午好，早上好"。
 *
 * 开场**不含日期**（见 `SpokenTime` 开头）。但升级前生成的缓存里还留着"9月23日 星期三"，
 * 所以这里有一组用例专门盯着"旧日期能被换掉" —— 那是不必为存量缓存单独写迁移的依据。
 *
 * 深夜（23 点–次日 5 点前）还会在结尾补一句"夜深了，早点休息。"——
 * 白天生成的缓存在深夜播放时，结尾该有这句话；原文已有类似话则不重复。
 */
class BriefClockTest {

    /** 2026-09-23 是星期三，下午 14:52。 */
    private val at = LocalDateTime.of(2026, 9, 23, 14, 52)

    /** 2026-09-24 凌晨 0:30 —— 深夜。 */
    private val atNight = LocalDateTime.of(2026, 9, 24, 0, 30)

    // ---- 非闹钟（点小组件 / 立即播报）：只说问候语 ----

    @Test
    fun `非闹钟 - 问候语开场换成此刻时段`() {
        val cached = "早上好。今天有两件事。"

        assertEquals(
            "下午好。今天有两件事。",
            BriefClock.refresh(cached, at = at),
        )
    }

    @Test
    fun `非闹钟 - 旧格式时间句加尾巴问候被清理`() {
        // 原文是"现在是10点，早上好"（旧生成格式），刷新后该是"下午好"，
        // 不去尾巴就成了"下午好，早上好"——重复且时段矛盾。
        val cached = "现在是9月23日 星期三 上午10点，早上好。今天有两件事。"

        assertEquals(
            "下午好。今天有两件事。",
            BriefClock.refresh(cached, at = at),
        )
    }

    @Test
    fun `非闹钟 - 正文里的同名词问候不动`() {
        val cached = "早上好。今天要记得跟客户说早上好。"

        assertEquals(
            "下午好。今天要记得跟客户说早上好。",
            BriefClock.refresh(cached, at = at),
        )
    }

    @Test
    fun `非闹钟 - 不以时间或问候开头时原样返回`() {
        val cached = "今天天气不错，有两件事。"

        assertEquals(cached, BriefClock.refresh(cached, at = at))
    }

    @Test
    fun `非闹钟 - 开头是一句没有标点的长句时不硬切`() {
        // 前 40 个字里一个句读都没有 —— 说明这不是"现在……"这种时间句。
        // 宁可把旧时间留在里面，也不能切成"新时间 + 旧残句"。
        val cached = "现在是上午十点" + "先把今天的事情说清楚".repeat(4)

        assertEquals(cached, BriefClock.refresh(cached, at = at))
    }

    // ---- 闹钟（定时播报）：说具体时间，像闹钟报时 ----

    @Test
    fun `闹钟 - 把过时的开场时间换成此刻`() {
        val cached = "现在是上午10点。今天有两件事。"

        assertEquals(
            "现在是下午2点52分。今天有两件事。",
            BriefClock.refresh(cached, at = at, isAlarm = true),
        )
    }

    @Test
    fun `闹钟 - 旧缓存里的日期会被换掉`() {
        // 开场已不再产出日期（见 SpokenTime），但升级前生成的缓存里还有 ——
        // 那些缓存照样会被念到，所以这里必须认得出、换得掉，
        // 而不是把一句"9月23日 星期三"念到十月份。
        val cached = "现在是9月23日 星期三 上午10点。今天有两件事。"

        assertEquals(
            "现在是下午2点52分。今天有两件事。",
            BriefClock.refresh(cached, at = at, isAlarm = true),
        )
    }

    @Test
    fun `非闹钟 - 旧缓存里带日期的问候也会退掉日期`() {
        val cached = "9月23日 星期三，早上好。今天有两件事。"

        assertEquals(
            "下午好。今天有两件事。",
            BriefClock.refresh(cached, at = at),
        )
    }

    @Test
    fun `闹钟 - 问候语开场转成报时`() {
        // 缓存是非闹钟生成的"早上好"，闹钟触发时得换成"现在是下午2点52分"。
        val cached = "早上好。今天有两件事。"

        assertEquals(
            "现在是下午2点52分。今天有两件事。",
            BriefClock.refresh(cached, at = at, isAlarm = true),
        )
    }

    @Test
    fun `闹钟 - 正文里的同名词问候不动`() {
        val cached = "现在是上午十点。今天要记得跟客户说早上好。"

        assertEquals(
            "现在是下午2点52分。今天要记得跟客户说早上好。",
            BriefClock.refresh(cached, at = at, isAlarm = true),
        )
    }

    // ---- 深夜结尾贴心话 ----

    @Test
    fun `深夜非闹钟 - 换问候语并在结尾补贴心话`() {
        val cached = "早上好。今天有两件事。"

        assertEquals(
            "晚上好。今天有两件事。夜深了，早点休息。",
            BriefClock.refresh(cached, at = atNight),
        )
    }

    @Test
    fun `深夜闹钟 - 换报时并在结尾补贴心话`() {
        val cached = "现在是上午10点。今天有两件事。"

        assertEquals(
            "现在是凌晨12点30分。今天有两件事。夜深了，早点休息。",
            BriefClock.refresh(cached, at = atNight, isAlarm = true),
        )
    }

    @Test
    fun `深夜 - 原文已含关心休息类话时不重复`() {
        val cached = "早上好。今天有两件事。早点休息。"

        assertEquals(
            "晚上好。今天有两件事。早点休息。",
            BriefClock.refresh(cached, at = atNight),
        )
    }

    @Test
    fun `白天 - 摘掉缓存里残留的深夜留言`() {
        // ⚠️ 这是"补"的反面，缺了它照样串：凌晨生成（或深夜刷新过）的缓存结尾挂着
        // "夜深了，早点休息。"，白天被念出来时开场已经换成"下午好"，
        // 结尾却还在说"夜深了"—— 同一段话里早晚打架。
        val cached = "早上好。今天有两件事。夜深了，早点休息。"

        assertEquals(
            "下午好。今天有两件事。",
            BriefClock.refresh(cached, at = at),
        )
    }

    @Test
    fun `深夜 - 带旧贴心话刷新不会叠成两句`() {
        val cached = "早上好。今天有两件事。夜深了，早点休息。"

        assertEquals(
            "晚上好。今天有两件事。夜深了，早点休息。",
            BriefClock.refresh(cached, at = atNight),
        )
    }

    @Test
    fun `深夜 - 认不出开场时仍补结尾贴心话`() {
        val cached = "今天天气不错，有两件事。"

        assertEquals(
            "今天天气不错，有两件事。夜深了，早点休息。",
            BriefClock.refresh(cached, at = atNight),
        )
    }

    // ---- 问候选词分档 ----

    @Test
    fun `问时候选词按小时分档`() {
        assertEquals("早上好", BriefClock.greetingFor(7))
        assertEquals("上午好", BriefClock.greetingFor(10))
        assertEquals("中午好", BriefClock.greetingFor(12))
        assertEquals("下午好", BriefClock.greetingFor(15))
        assertEquals("晚上好", BriefClock.greetingFor(21))
        // 0 点是深夜，不是早上
        assertEquals("晚上好", BriefClock.greetingFor(0))
        assertEquals("晚上好", BriefClock.greetingFor(23))
    }

    // ---- 开场句 / 正文 的切分（语音分段缓存靠它）----

    @Test
    fun `切分 - 纯问候开场被切开`() {
        val split = BriefClock.splitOpening("上午好。今天有两件事。")!!

        assertEquals("上午好。", split.first)
        assertEquals("今天有两件事。", split.second)
    }

    @Test
    fun `切分 - 闹钟的报时句被切开`() {
        val split = BriefClock.splitOpening("现在是下午2点52分。今天有两件事。")!!

        assertEquals("现在是下午2点52分。", split.first)
        assertEquals("今天有两件事。", split.second)
    }

    @Test
    fun `切分 - 带日期的问候也算开场`() {
        // 以数字开头，但只要它确实是开场句，就该被认出来。
        val split = BriefClock.splitOpening("9月23日 星期三，下午好。今天有两件事。")!!

        assertEquals("9月23日 星期三，下午好。", split.first)
        assertEquals("今天有两件事。", split.second)
    }

    @Test
    fun `切分 - 开场句内部的逗号不算收尾`() {
        // 拿逗号当收尾会切成"9月23日 星期三，"+"下午好。今天……"——
        // 开场音频被拦腰截断，念出来是明显的断句错误。
        val split = BriefClock.splitOpening("9月23日 星期三，下午好。今天有两件事。")!!

        assertEquals("9月23日 星期三，下午好。", split.first)
    }

    @Test
    fun `切分 - 认不出开场就不切`() {
        assertNull(BriefClock.splitOpening("今天天气不错，有两件事。"))
    }

    @Test
    fun `切分 - 没有句末标点就不切`() {
        assertNull(BriefClock.splitOpening("现在是上午十点" + "先把今天的事情说清楚".repeat(3)))
    }

    @Test
    fun `切分 - 开场句过长就不切`() {
        // 认出来的"头"后面跟了 40 个字才收句 —— 那多半不是开场句。
        assertNull(BriefClock.splitOpening("上午好" + "啊".repeat(40) + "。"))
    }

    @Test
    fun `切分 - 整篇只有一句开场就不切`() {
        assertNull(BriefClock.splitOpening("上午好。"))
    }

    @Test
    fun `切分 - 换过开场之后正文段一个字都不变`() {
        // 🔴 语音缓存能"写一次一直用"全靠这一条：refresh 只动开场，
        // 正文段的 hash 因此不变、缓存天然命中。它一旦被破坏（比如 refresh
        // 顺手改了个标点），缓存就会静默失效、每次都重新请求。
        val cached = "早上好。今天有两件事。"
        val refreshed = BriefClock.refresh(cached, at = at)

        val before = BriefClock.splitOpening(cached)!!
        val after = BriefClock.splitOpening(refreshed)!!

        assertEquals("正文段必须原样", before.second, after.second)
        assertEquals("开场句本来就该换", "下午好。", after.first)
    }

    @Test
    fun `切分 - 闹钟换开场后正文段也不变`() {
        val cached = "现在是9月23日 星期三 上午10点。今天有两件事。"
        val refreshed = BriefClock.refresh(cached, at = at, isAlarm = true)

        assertEquals(
            BriefClock.splitOpening(cached)!!.second,
            BriefClock.splitOpening(refreshed)!!.second,
        )
    }
}
