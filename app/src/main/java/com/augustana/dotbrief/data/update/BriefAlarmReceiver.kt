package com.augustana.dotbrief.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.augustana.dotbrief.BriefWidgetApp
import com.augustana.dotbrief.tts.BriefPlaybackService
import kotlinx.coroutines.launch

/**
 * 定时播报到点的触发点：拉起播报服务，再把下一次排上。
 *
 * ## 为什么必须是静态注册
 *
 * 闹钟到点时（典型场景：手机锁屏在床头放了一整夜）应用进程多半根本没在跑。
 * 动态注册的接收器只活在进程里，那种时刻没人收得到广播 —— 连一行代码都跑不起来。
 * 所以它写在 AndroidManifest 里，由系统在 `setAlarmClock` 的 PendingIntent 到点时
 * 现拉起进程。
 *
 * ## 为什么这条通道能拉起前台服务
 *
 * Android 12+ 限制后台启动前台服务，但**精确闹钟的投递是官方明列的豁免场景**
 * （"exact alarms aren't affected by foreground service launch restrictions"）。
 * 这也正是定时播报必须走 [BriefAlarmScheduler] 那条精确通道的原因之一：
 * WorkManager 唤起的任务没有这个豁免，锁屏久了它可能压根不跑 ——
 * 那就是"锁屏时定时播报不响"的根因。
 *
 * ## 两件事的顺序是有意的
 *
 * ① 拉起服务**先做、且同步做**：出声是用户唯一能感知的结果，而精确闹钟给出的启动豁免
 * 只在秒级窗口内有效。② 重排下一次要读 DataStore（挂起操作），放进协程，
 * 并且用 [goAsync] 让系统知道这段收尾还没结束 —— 不调用它的话，`onReceive`
 * 一返回进程就可能被回收，闹钟响过一次就再也不来了。
 *
 * ## 这里为什么不复查"定时播报还开着吗"
 *
 * 关掉开关时 [BriefAlarmScheduler] 会**立刻取消**系统里的闹钟，所以能走到这里的
 * 必然是用户还开着的。真正需要复查的是降级通道那条（Worker 可能在被推迟期间
 * 用户关掉了开关），那边也确实是复查的。这里不复查是为了不在出声前多等一次读盘。
 */
class BriefAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BriefAlarmScheduler.ACTION_FIRE) return

        val app = context.applicationContext as? BriefWidgetApp ?: return

        val play = Intent(context, BriefPlaybackService::class.java)
            .setAction(BriefPlaybackService.ACTION_TOGGLE)
            .putExtra(BriefPlaybackService.EXTRA_ALARM, true)
        runCatching { context.startForegroundService(play) }
            .onFailure { Log.w(TAG, "拉起播报服务失败（这次闹钟没响）", it) }
        Log.i(TAG, "到点定时播报，已拉起播报服务")

        val pending = goAsync()
        app.appScope.launch {
            try {
                app.container.briefAlarmScheduler.reschedule()
            } catch (error: Exception) {
                // 排期失败只影响"明天还响不响"，不该让这次已经发出的声音受影响，
                // 所以只记日志。真正需要用户知道的情况会以别的形式暴露（下次打开设置页）。
                Log.w(TAG, "重排下一次定时播报失败", error)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BriefAlarm"
    }
}
