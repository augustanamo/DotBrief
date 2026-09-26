package com.augustana.dotbrief.data.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * 时刻表、开关、任务名都各走各的，一个开了定时播报不影响自动刷新，反过来也一样。
 *
 * ## 为什么它必须用 AlarmManager，而"自动刷新"可以继续用 WorkManager
 *
 * [BriefUpdateScheduler] 那条的理由是"**晚几分钟完全没关系**"（内容本来就是
 * 隔几小时看一次的量级），那句话对刷新成立，对这个**不成立** —— 这是闹钟。
 * 用户设了 7:00 出声，7:20 才响，那不是"略有延迟"，那是坏了。
 *
 * 而 WorkManager 的 `setInitialDelay` 只是"不早于此"，实际投递受 JobScheduler 调度：
 * 手机锁屏静置一夜正是 Doze 的触发条件，任务会被推迟到维护窗口 —— 几十分钟到几小时
 * 都可能。**这就是"锁屏时定时播报不响"的根因**，与权限、电量优化都无关。
 *
 * 所以改用 `AlarmManager.setAlarmClock()`：它是 AlarmManager 里保证最强的一档 ——
 * 系统会在闹钟前主动退出 Doze 来投递，且**不受前台服务启动限制**
 * （收到闹钟广播去 `startForegroundService` 是官方明列的豁免场景）。
 * 代价是它会在状态栏显示一个闹钟图标（系统行为，表示"这个应用定了闹钟"）、
 * 以及需要精确闹钟权限（见 [canUseExactAlarm]）。
 *
 * ## 两条通道，与为什么留一条降级的
 *
 * - **精确闹钟**（正常情况）：`setAlarmClock` + [BriefAlarmReceiver]；
 * - **后台任务**（降级）：没有精确闹钟权限时退回原来的 WorkManager 写法。
 *   它仍然会响，只是锁屏久了可能晚几分钟 —— 好过完全不响。
 *
 * 两条通道**必须互相取消**，否则同一个时刻会响两次。切换方向是自动的：
 * 降级期间用户给上了权限，下一次 [reschedule] 就会切回精确通道。
 *
 * ## 两个入口实现相同
 *
 * [ensureScheduled]（应用启动 / 开机补位）与 [reschedule]（改配置 / 跑完一轮）
 * 在精确通道下是同一份实现：`setAlarmClock` 用同一个 PendingIntent 时会先取消旧的，
 * 而"下一次时刻"由配置算出、同一时刻算出的结果相同，所以重设是幂等的。
 * 保留两个名字只为让调用点读起来是它自己的意思；降级通道那边 `KEEP`/`REPLACE`
 * 确实不同，参数因此留着。
 */
class BriefAlarmScheduler(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    /** 应用启动 / 开机时补位（不打断已经排好的那一份）。 */
    suspend fun ensureScheduled() = enqueue(keepExisting = true)

    /** 改完配置 / 一轮跑完后重排（按最新时刻表重来）。 */
    suspend fun reschedule() = enqueue(keepExisting = false)

    /**
     * 排期这件事**不允许往外抛异常**。
     *
     * 调用方分别跑在 Application 的 appScope、设置页的 viewModelScope、
     * 广播接收器的协程里 —— 都是 `launch` 出来的。协程里未被捕获的异常会直接走到
     * 线程的默认处理器，**等于应用崩溃**。而排期失败最坏的结果只是"这次没排上"。
     */
    private suspend fun enqueue(keepExisting: Boolean) {
        try {
            enqueueOrThrow(keepExisting)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "定时播报排期失败，这次没排上（不影响手动点播）", error)
        }
    }

    private suspend fun enqueueOrThrow(keepExisting: Boolean) {
        val settings = try {
            settingsRepository.snapshot()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // 读不到配置就不动现在的排期：取消它反而会让定时播报永久停摆
            Log.w(TAG, "读配置失败，保持现有排期", error)
            return
        }

        if (!settings.brief.alarmEnabled || settings.brief.alarmTimes.isEmpty()) {
            cancelEverything()
            Log.i(TAG, "定时播报已关闭或没设时刻，取消排期")
            return
        }

        val now = ZonedDateTime.now()
        val next = BriefSchedule.nextSlot(now, settings.brief.alarmTimes) ?: return
        val delayMinutes = Duration.between(now, next).toMinutes().coerceAtLeast(0L)

        if (canUseExactAlarm()) {
            // 切过来之前先把降级通道取消掉 —— 两条都留着的话，同一个时刻会响两次。
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            if (setExactAlarm(next.toInstant().toEpochMilli())) {
                Log.i(TAG, "下一次定时播报：$next（精确闹钟，${delayMinutes} 分钟后）")
                return
            }
            Log.w(TAG, "设置精确闹钟失败，改走后台任务通道")
        } else {
            // 走到这里说明"闹钟和提醒"这项特殊权限被关掉了（正常装机时
            // USE_EXACT_ALARM 会自动授予、用户撤不掉，所以这是少数情况）。
            cancelExactAlarm()
            Log.w(TAG, "没有精确闹钟权限，改走后台任务通道（锁屏久了可能晚几分钟）")
        }

        scheduleFallback(keepExisting, delayMinutes)
    }

    /**
     * 定一个"闹钟"。
     *
     * 用 `setAlarmClock` 而不是 `setExactAndAllowWhileIdle`：前者是官方定义里
     * **保证最强**的一档 —— 系统会在到点前主动退出 Doze 来投递，而且它的
     * `AlarmClockInfo` 会被系统拿来在状态栏显示"距离下个闹钟还有多久"。
     * 后者在 Doze 下有每次的配额（约每 15 分钟才放一次），对闹钟来说精度不够。
     *
     * @return 是否设置成功（拿不到 AlarmManager 或系统抛异常时为 false，由调用方降级）。
     */
    private fun setExactAlarm(triggerAtMillis: Long): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent()),
                fireIntent(),
            )
            true
        }.getOrElse {
            Log.w(TAG, "setAlarmClock 调用失败", it)
            false
        }
    }

    /** 降级通道：原来的后台任务写法（可能被 Doze 推迟，但总比不响强）。 */
    private fun scheduleFallback(keepExisting: Boolean, delayMinutes: Long) {
        val request = OneTimeWorkRequestBuilder<BriefAlarmWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
            request,
        )
        Log.i(TAG, "下一次定时播报：${delayMinutes} 分钟后（后台任务）")
    }

    private fun cancelEverything() {
        cancelExactAlarm()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun cancelExactAlarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // PendingIntent 的 filterEquals 是按 action / component 比的，
        // 所以这里重新取到的就是当初设置闹钟时用的那一个，能取消掉。
        runCatching { alarmManager.cancel(fireIntent()) }
    }

    /** 到点时投给 [BriefAlarmReceiver] 的广播。 */
    private fun fireIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE_FIRE,
        Intent(context, BriefAlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 状态栏那个闹钟图标被点时打开哪儿。
     *
     * `setAlarmClock` 要求给一个 showIntent，系统用它把"这个应用定了闹钟"告知用户。
     * 用启动器 Intent 而不是直接引用设置页那个类：一来 data 层不必知道界面类叫什么，
     * 二来桌面图标指向哪儿这里就指向哪儿 —— 它本来就是配置中心。
     * 拿不到启动器 Intent 时返回 null（这个参数允许为空，只是系统没法给出跳转）。
     */
    private fun showIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_SHOW,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 现在能不能用精确闹钟。
     *
     * Android 12（API 31）起要先拿到"闹钟和提醒"这项特殊权限。Android 14（API 34）
     * 起，新装应用**默认不给** `SCHEDULE_EXACT_ALARM` —— 但我们同时声明了
     * `USE_EXACT_ALARM`（装机即授予、用户撤不掉），所以正常情况下这里恒为真。
     *
     * [canScheduleExactAlarms] 是必须调的：用户在系统设置里可以手动关掉这项权限，
     * 而关掉之后 `setAlarmClock` 会直接被系统拒绝（不抛异常、静默不生效）——
     * 不查的话症状就是"闹钟凭空不响了"，最难查的那一类。
     */
    private fun canUseExactAlarm(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    companion object {
        private const val TAG = "BriefAlarm"

        /** 唯一任务名，与「自动刷新」的 `dotbrief.scheduledUpdate` 区分开，定下来就别动。 */
        private const val WORK_NAME = "dotbrief.scheduledAlarm"

        /**
         * 闹钟到底时发出的 action，[BriefAlarmReceiver] 认它。
         *
         * 这个**不能**跟着上面两条一起 private：companion 被标成 private 时，
         * 连 `BriefAlarmScheduler.ACTION_FIRE` 这种写法都会编译不过
         * （`Cannot access 'Companion': it is private`）—— 接收器在另一个文件里。
         */
        internal const val ACTION_FIRE = "com.augustana.dotbrief.action.ALARM_FIRE"

        /** 两个 PendingIntent 的 requestCode 必须不同，否则会互相覆盖。 */
        private const val REQUEST_CODE_FIRE = 7001
        private const val REQUEST_CODE_SHOW = 7002
    }
}
