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
 * 把"下一个更新时刻"排成一个一次性的后台任务。
 *
 * ## 为什么是"一次性的，跑完自己再排下一次"，而不是周期任务
 *
 * WorkManager 的 `PeriodicWorkRequest` 只支持固定间隔（每 6 小时那种），
 * **表达不了"每天 10:00 / 14:00 / 19:00 这三个具体时刻"** —— 而这三个点正是
 * 用户自己定的。所以这里用一次性任务 + `setInitialDelay(到下一个时刻的间隔)` 串起来：
 * 每次跑完再算一次下一个时刻。用户在设置页改时刻，链条就按新表重排。
 *
 * ## 为什么用 WorkManager 而不是 AlarmManager
 *
 * 到点刷新的**晚几分钟完全没关系**（内容本来就是"隔几小时看一次"的量级），
 * 而 WorkManager 自带三件我们得自己写的东西：重启后恢复（不需要自己实现
 * `BOOT_COMPLETED` 接收器）、应用被覆盖安装后恢复、以及 Doze 下的统一调度窗口。
 * 用 `AlarmManager.setExactAndAllowWhileIdle` 不但要在 Android 12+ 申请精确闹钟权限
 * （用户多半会拒），还得自己处理开机恢复 —— 为"晚几分钟没关系"的需求付这个代价不值。
 *
 * ## 唯一任务名与两种策略的区别
 *
 * - [ensureScheduled]（应用启动时）：`KEEP`。队列里已经有下一次了就别动它 ——
 *   否则每次冷启动都重算一遍延迟，而小组件被点一下就是一个新进程。
 * - [reschedule]（改完配置 / 一轮跑完）：`REPLACE`。这两处的语义都是"按最新时刻表重来"。
 *
 * 关掉自动更新、或时刻被清空时，直接取消任务并把下一次排期**一并清掉** ——
 * 不能只标记"不生成"而让任务继续空转。
 */
class BriefUpdateScheduler(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 应用启动时补位。
     *
     * 覆盖安装、开机、被小组件点起来……都会走到 Application.onCreate，
     * 所以在这里补一次最省事：任何"任务因为某种原因不在了"的情况都会被自愈。
     * 已经排好时不打断（`KEEP`）。
     */
    suspend fun ensureScheduled() = enqueue(ExistingWorkPolicy.KEEP)

    /**
     * 按最新的时刻表重排下一次。
     *
     * 三个调用时机：用户在设置页保存了配置、一轮定时任务跑完了、以及上面那个启动补位。
     *
     * ⚠️ 在 [BriefUpdateWorker.doWork] 的最后调用它时，用的是 `REPLACE`，
     * 而"当前的这一次"正占用着同一个唯一任务名 —— WorkManager 会把它标记成
     * `CANCELLED`（虽然活已经干完了）。这是这套"自己排自己"写法的已知代价，
     * 不影响下一次照常执行；如果哪天要按状态统计任务成功数，记得把它算进去。
     */
    suspend fun reschedule() = enqueue(ExistingWorkPolicy.REPLACE)

    /**
     * 排期这件事**不允许往外抛异常**。
     *
     * 三个调用方分别跑在 Application 的 appScope、设置页的 viewModelScope 和
     * Worker 里 —— 都是 `launch` 出来的协程。协程里未被捕获的异常会直接走到
     * 线程的默认处理器，**等于应用崩溃**（SupervisorJob 只保证兄弟协程不受影响）。
     * 而排期失败最坏的结果只是"这次自动刷新没排上"，与崩一次完全不成比例。
     * 所以这里把所有失败都咽掉，只留日志。
     */
    private suspend fun enqueue(policy: ExistingWorkPolicy) {
        try {
            enqueueOrThrow(policy)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "排期失败，自动更新这次没排上（不影响手动点击播报）", error)
        }
    }

    private suspend fun enqueueOrThrow(policy: ExistingWorkPolicy) {
        val manager = WorkManager.getInstance(context)

        val settings = try {
            settingsRepository.snapshot()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // 读不到配置就不动现在的排期：取消它反而会让自动更新永久停摆
            Log.w(TAG, "读配置失败，保持现有排期", error)
            return
        }

        if (!settings.brief.updateEnabled || settings.brief.updateTimes.isEmpty()) {
            manager.cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "自动更新已关闭，取消排期")
            return
        }

        val now = ZonedDateTime.now()
        val next = BriefSchedule.nextSlot(now, settings.brief.updateTimes) ?: return
        val delayMinutes = Duration.between(now, next).toMinutes().coerceAtLeast(0L)

        val request = OneTimeWorkRequestBuilder<BriefUpdateWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()

        manager.enqueueUniqueWork(WORK_NAME, policy, request)
        Log.i(TAG, "下一次自动更新：$next（${delayMinutes} 分钟后）")
    }

    private companion object {
        const val TAG = "BriefUpdate"

        /**
         * 唯一任务名。
         *
         * 换成任何别的名字都会让旧版本排下的任务变成孤儿（照旧执行、却又没人能取消它）,
         * 所以这个名字定下来就别动。
         */
        const val WORK_NAME = "dotbrief.scheduledUpdate"
    }
}
