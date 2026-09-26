package com.augustana.dotbrief.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.augustana.dotbrief.BriefWidgetApp
import kotlinx.coroutines.launch

/**
 * 开机 / 覆盖安装后，把两套排期重新补上。
 *
 * ## 为什么换成 AlarmManager 之后必须有它
 *
 * WorkManager 的任务存在系统数据库里，重启后自己恢复 —— 那正是当初选它的理由之一
 * （见 [BriefUpdateScheduler] 的类注释）。而 AlarmManager 的闹钟**只活在内存里**，
 * 系统一重启就全清空。
 *
 * 所以这一棒得自己接：不接的话，用户重启一次手机，定时播报就永久失效 ——
 * 直到下次打开设置页才会重新排上。而"重启后闹钟不响"这件事，
 * 用户几乎不可能联想到是排期没恢复，只会觉得这应用不靠谱。
 *
 * ⚠️ 它不能靠 `Application.onCreate` 里那次补位顶替：开机后应用不会自己启动，
 * 而这里要处理的恰恰是"用户没打开应用"的那些天。必须是静态注册的接收器。
 *
 * ## 为什么两个排期器都补
 *
 * 自动刷新那条仍然走 WorkManager（它有自己的恢复机制），这里用
 * `ensureScheduled`（KEEP 语义）补一次是**幂等**的：已经排好的不会被打断。
 * 补它的价值在于兜底 —— 万一 WorkManager 的恢复这次没发生
 * （任务被系统清过、或用户装完就没打开过），这一次也把它接上了。
 *
 * 也顺带给桌面小组件推一次刷新：覆盖安装后 system_server 里缓存的 RemoteViews
 * 会失效，[android.appwidget.AppWidgetProvider] 那边也接了同一个广播，
 * 两边各管各的那份状态，谁先到都不影响谁。
 */
class SchedulerRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val app = context.applicationContext as? BriefWidgetApp ?: return

        // 读配置是挂起操作，得靠 goAsync 把它挂在 onReceive 之外 ——
        // 否则 onReceive 一返回进程就可能被回收，排期做一半。
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.container.briefAlarmScheduler.ensureScheduled()
                app.container.briefUpdateScheduler.ensureScheduled()
                Log.i(TAG, "已重排定时播报与自动刷新（$action）")
            } catch (error: Exception) {
                Log.w(TAG, "重排失败（$action）", error)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BriefAlarm"
    }
}
