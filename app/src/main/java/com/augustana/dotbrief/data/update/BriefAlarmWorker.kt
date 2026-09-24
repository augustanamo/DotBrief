package com.augustana.dotbrief.data.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.augustana.dotbrief.BriefWidgetApp
import com.augustana.dotbrief.tts.BriefPlaybackService

/**
 * 到点**出声**播报（当闹钟用）。
 *
 * 它自己不做生成、不碰 TTS —— 那整条链路都在 [BriefPlaybackService] 里（前台服务、
 * 音频焦点、点阵进度），这里只做定时任务独有的一件事：到点把它拉起来，让它带上
 * [BriefPlaybackService.EXTRA_ALARM] 标记走"≤4 小时缓存优先、否则现场生成"的播报路径。
 *
 * 与 [BriefUpdateWorker] 的区别一句话：那个"只刷不出声"，这个"出声"。两者时刻表独立。
 *
 * 为什么能直接 `startForegroundService`：WorkManager 在 Android 12+ 的后台启动限制里
 * 属于豁免场景之一（定时任务），而且播报服务本身就是前台服务，起来即出声。
 * 真遇到极端情况启动失败，也只是"这次闹钟没响"，重排下一次照旧 —— 不值得为此申请
 * 精确闹钟权限（AlarmManager.setExactAndAllowWhileIdle），理由见 [BriefUpdateScheduler]。
 */
class BriefAlarmWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? BriefWidgetApp ?: return Result.success()

        // 定时播报开关 / 时刻可能在上一次排期后被改过，这里复查一次（和自动刷新同一个理由）。
        val settings = runCatching { app.container.settingsRepository.snapshot() }.getOrNull()
        val shouldPlay = settings?.brief?.alarmEnabled == true &&
            settings.brief.alarmTimes.isNotEmpty()

        if (shouldPlay) {
            val intent = Intent(applicationContext, BriefPlaybackService::class.java)
                .setAction(BriefPlaybackService.ACTION_TOGGLE)
                .putExtra(BriefPlaybackService.EXTRA_ALARM, true)
            runCatching { applicationContext.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "拉起播报服务失败（这次闹钟没响）", it) }
            Log.i(TAG, "到点定时播报")
        } else {
            Log.i(TAG, "定时播报已关闭或没设时刻，本次不出声")
        }

        // 排下一次放在最后：即使这次出声失败，明天的闹钟照样会响。
        app.container.briefAlarmScheduler.reschedule()
        return Result.success()
    }

    private companion object {
        const val TAG = "BriefAlarm"
    }
}
