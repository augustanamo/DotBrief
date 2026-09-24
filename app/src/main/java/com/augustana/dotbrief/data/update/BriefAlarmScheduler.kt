package com.augustana.dotbrief.data.update

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.augustana.dotbrief.data.settings.SettingsRepository
import com.augustana.dotbrief.domain.BriefSchedule
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 定时**出声**播报（当闹钟用）的排期器。
 *
 * 与 [BriefUpdateScheduler]（到点只刷、不出声）是两套**独立**的排期：
 * 时刻表、开关、任务名都各走各的，一个开了定时播报不影响自动刷新，
 * 反过来也一样。复用同一套"一次性任务 + 自排下一次"的写法，理由见那边的类注释。
 */
class BriefAlarmScheduler(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    /** 应用启动时补位（KEEP：已经排好就不打断）。 */
    suspend fun ensureScheduled() = enqueue(ExistingWorkPolicy.KEEP)

    /** 改完配置 / 一轮跑完后重排（REPLACE：按最新时刻表重来）。 */
    suspend fun reschedule() = enqueue(ExistingWorkPolicy.REPLACE)

    private suspend fun enqueue(policy: ExistingWorkPolicy) {
        try {
            enqueueOrThrow(policy)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "定时播报排期失败，这次没排上（不影响手动点播）", error)
        }
    }

    private suspend fun enqueueOrThrow(policy: ExistingWorkPolicy) {
        val manager = WorkManager.getInstance(context)

        val settings = try {
            settingsRepository.snapshot()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "读配置失败，保持现有排期", error)
            return
        }

        if (!settings.brief.alarmEnabled || settings.brief.alarmTimes.isEmpty()) {
            manager.cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "定时播报已关闭或没设时刻，取消排期")
            return
        }

        val now = ZonedDateTime.now()
        val next = BriefSchedule.nextSlot(now, settings.brief.alarmTimes) ?: return
        val delayMinutes = Duration.between(now, next).toMinutes().coerceAtLeast(0L)

        val request = OneTimeWorkRequestBuilder<BriefAlarmWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()

        manager.enqueueUniqueWork(WORK_NAME, policy, request)
        Log.i(TAG, "下一次定时播报：$next（${delayMinutes} 分钟后）")
    }

    private companion object {
        const val TAG = "BriefAlarm"

        /** 唯一任务名，与「自动刷新」的 `dotbrief.scheduledUpdate` 区分开，定下来就别动。 */
        const val WORK_NAME = "dotbrief.scheduledAlarm"
    }
}
