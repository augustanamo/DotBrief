package com.augustana.dotbrief.data.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.augustana.dotbrief.BriefWidgetApp

/**
 * 到点把简报内容刷成新的。**只生成、不出声。**
 *
 * ## 为什么绝不出声
 *
 * 到点的那一刻用户多半没在看手机。后台自己开口说话（尤其在会议室里）是灾难级的体验，
 * 而且用户会觉得"这应用在乱说话"。定时任务只做一件事：把内容准备好、把桌面点阵点亮，
 * 让用户下一次点的时候**立刻**有话说。
 *
 * ## 它自己不干生成那件事
 *
 * 生成 + 落盘全在 [BriefUpdater] 里，和设置页的「刷新内容」是**同一个**入口。
 * 这里只负责两件定时任务独有的事：判断"该不该跑"（由 updater 的 `force = false` 体现），
 * 以及跑完把自己再排到下一个时刻。
 *
 * ## 续排下一次
 *
 * 每次跑完（无论成败）都调 [BriefUpdateScheduler.reschedule] 排下一次。
 * 少了这一步，任务就变成"只跑一次"——而且**看不出来**：桌面上什么都没有，
 * 只有一天过后才发现内容再没更新过。
 */
class BriefUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? BriefWidgetApp ?: return Result.success()

        // 成功 / 失败各自的日志都在 BriefUpdater 里打了（那边才知道是手动还是定时），
        // 这里只补一句"这次压根没跑"——那条只在定时路径上会出现，且有排查价值。
        when (app.container.briefUpdater.run(force = false)) {
            BriefUpdater.Outcome.Skipped -> Log.i(TAG, "自动更新已关闭，本次不生成")
            else -> Unit
        }

        // 排下一次放在最后一行：这样即使前面全部失败，明天该跑还是会跑。
        // 用容器里那一份调度器（同一个对象），免得两处各拿一份配置来源。
        app.container.briefUpdateScheduler.reschedule()
        return Result.success()
    }

    private companion object {
        const val TAG = "BriefUpdate"
    }
}
