package com.augustana.dotbrief.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「什么时候算新鲜」这条判据的回归测试。
 *
 * 值得单独写测试的理由很直接：它同时决定**用户听到的内容有多旧**和
 * **一天会请求多少次模型**这两件事，而它的边界（跨天、早晨还没到过任何时刻、
 * 正好卡在时刻上）全都不容易靠肉眼验证 —— 真机上要把系统时间改来改去才试得出来。
 * 纯函数，所以这些边界在这里是零成本可测的。
 */
class BriefScheduleTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val times = listOf(LocalTime.of(10, 0), LocalTime.of(14, 0), LocalTime.of(19, 0))

    private fun at(day: Int, hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(2026, 9, day, hour, minute), zone)

    @Test
    fun `时刻之后生成的缓存是新鲜的`() {
        assertTrue(
            BriefSchedule.isCacheFresh(
                cachedAtEpochSeconds = at(23, 10, 5).toEpochSecond(),
                now = at(23, 11),
                times = times,
            ),
        )
    }

    @Test
    fun `上一个时刻之前生成的缓存已经不新鲜`() {
        // 9:00 生成的，而现在已过了 10:00 那一档 —— 这时点击应当重新生成。
        assertFalse(
            BriefSchedule.isCacheFresh(
                cachedAtEpochSeconds = at(23, 9).toEpochSecond(),
                now = at(23, 11),
                times = times,
            ),
        )
    }

    @Test
    fun `今天还没到过任何时刻时 只有今天的缓存才新鲜`() {
        // 08:00：今天一档都没过，锚点退回今天 00:00。
        val morning = at(23, 8)

        assertTrue(
            BriefSchedule.isCacheFresh(
                cachedAtEpochSeconds = at(23, 7).toEpochSecond(),
                now = morning,
                times = times,
            ),
        )
        // 昨天晚上七点那份：内容已经是隔夜的，必须重新生成。
        assertFalse(
            BriefSchedule.isCacheFresh(
                cachedAtEpochSeconds = at(22, 19).toEpochSecond(),
                now = morning,
                times = times,
            ),
        )
    }

    @Test
    fun `跨天自动失效`() {
        assertFalse(
            BriefSchedule.isCacheFresh(
                cachedAtEpochSeconds = at(23, 20).toEpochSecond(),
                now = at(24, 8),
                times = times,
            ),
        )
    }

    @Test
    fun `从没生成过时判不新鲜`() {
        assertFalse(BriefSchedule.isCacheFresh(0L, now = at(23, 11), times = times))
    }

    @Test
    fun `没有设置任何时刻时 当天生成的缓存一直算新鲜`() {
        // 锚点退回今天 00:00 —— 于是"只在点击时生成"也不会变成每点一次都生成一次。
        assertTrue(
            BriefSchedule.isCacheFresh(
                cachedAtEpochSeconds = at(23, 9).toEpochSecond(),
                now = at(23, 21),
                times = emptyList(),
            ),
        )
    }

    @Test
    fun `下一个时刻取今天最近的 之后顺延到明天`() {
        assertEquals(at(23, 14), BriefSchedule.nextSlot(at(23, 11), times))
        // 19:00 之后当天没有下一档了，顺延到明天最早的 10:00
        assertEquals(at(24, 10), BriefSchedule.nextSlot(at(23, 20), times))
    }

    @Test
    fun `正好卡在时刻上时不立刻再刷一次`() {
        assertEquals(at(23, 14), BriefSchedule.nextSlot(at(23, 10), times))
    }

    @Test
    fun `没有时刻就没有下一次`() {
        assertNull(BriefSchedule.nextSlot(at(23, 11), emptyList()))
    }

    @Test
    fun `时刻的解析与格式化往返一致`() {
        assertEquals(LocalTime.of(9, 30), BriefSchedule.parse("09:30"))
        assertEquals(LocalTime.of(9, 30), BriefSchedule.parse("9:30"))
        assertEquals(LocalTime.of(0, 0), BriefSchedule.parse("0:00"))
        assertEquals("09:30", BriefSchedule.format(LocalTime.of(9, 30)))

        assertNull(BriefSchedule.parse(""))
        assertNull(BriefSchedule.parse("0930"))
        assertNull(BriefSchedule.parse("24:00"))
        assertNull(BriefSchedule.parse("10:60"))
        assertNull(BriefSchedule.parse("上午十点"))
    }
}
