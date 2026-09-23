package com.augustana.dotbrief.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.augustana.dotbrief.BriefWidgetApp
import com.augustana.dotbrief.R
import com.augustana.dotbrief.data.settings.AccentColor
import com.augustana.dotbrief.data.settings.WidgetRuntimeState
import com.augustana.dotbrief.data.settings.WidgetState
import kotlinx.coroutines.launch

/**
 * 小组件广播入口。
 *
 * 两条必须守住的底线（否则会 ANR —— 报错形如
 * `ANR in com.augustana.dotbrief Reason: Broadcast of Intent { act=APPWIDGET_UPDATE }`）：
 * 1. `appWidgetIds` 为空直接 return，系统在安装/更新后一定会发一次空 id 的广播；
 * 2. 广播必须在 10 秒内返回，所以这里**先用内存缓存的状态立刻出图**，
 *    磁盘上的真实状态放到协程里异步补，绝不阻塞 onUpdate。
 */
class BriefWidgetProvider : AppWidgetProvider() {

    /**
     * 应用被覆盖安装。
     *
     * 必须在这里补一次回推：系统收到 `MY_PACKAGE_REPLACED` 后**不会**替我们刷新
     * system_server 里缓存的那份 RemoteViews（详见 [refreshAll] 的说明），
     * 新装完如果不推，桌面拿到的还是上一版的样子。
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            refreshAll(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return

        // 先用内存缓存立刻出图，保证广播快速返回
        push(context, appWidgetManager, appWidgetIds, cachedState, cachedAccent)

        val app = context.applicationContext as BriefWidgetApp
        app.appScope.launch {
            // 这份状态里多了一个"有没有新内容"，它得查一次通知库才算得出来
            val runtime = withFreshness(app, app.container.runtimeStateStore.snapshot())
            val accent = runCatching { app.container.settingsRepository.snapshot().accent }
                .getOrElse { cachedAccent }
            cachedState = runtime
            cachedAccent = accent
            push(context, appWidgetManager, appWidgetIds, runtime, accent)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // 用户拖动改了尺寸，点阵要按新尺寸重画，否则会被拉伸糊掉
        push(context, appWidgetManager, intArrayOf(appWidgetId), cachedState, cachedAccent)
    }

    companion object {

        /** 内存态缓存：让广播路径不必读磁盘就能出图。 */
        @Volatile
        private var cachedState: WidgetRuntimeState = WidgetRuntimeState()

        /**
         * 主色缓存。
         *
         * 配色存在 DataStore 里（读它是挂起操作），而 `applyState` 必须能在服务/广播里同步返回，
         * 所以这里跟 [cachedState] 一样留一份内存镜像，由 onUpdate 与 [refreshAll] 的异步分支刷新。
         * 首帧可能还是默认色，但 `refreshAll` 在 `Application.onCreate` 就会跑一遍，
         * 真正画到桌面时拿到的已经是磁盘里的值。
         */
        @Volatile
        private var cachedAccent: AccentColor = AccentColor()

        /**
         * 把状态渲染到桌面。
         *
         * `notifyAppWidgetViewDataChanged` **只在真的有动效时才调**：
         * 静态布局里根本没有 `widget_flipper` 这个 id，
         * 对一个不存在的 view 调 data-changed 只会让系统白跑一趟（并打一条 warning）。
         */
        private fun push(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            runtime: WidgetRuntimeState,
            accent: AccentColor,
        ) {
            for (id in ids) {
                manager.updateAppWidget(
                    id,
                    BriefWidgetRenderer.build(context, id, runtime, accent),
                )
            }
            if (BriefWidgetRenderer.isAnimated(runtime)) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_flipper)
            }
        }

        /**
         * 状态变化时把新状态推到桌面。
         * 调用方需保证**已经**把新状态写进 DataStore ——
         * 帧工厂那边是自己读 DataStore 决定帧数与灰阶的。
         *
         * [unheard] 默认沿用内存缓存里的值：生成中 / 播报中 / 出错这几次推送
         * 都不该改变"有没有新内容"，它们只是同一个事实的不同表情。
         * 唯一需要显式传的时候是**听完**那一下（见 [BriefPlaybackService.finish]）。
         */
        fun applyState(
            context: Context,
            state: WidgetState,
            speaking: Boolean,
            unheard: Boolean = cachedState.unheard,
        ) {
            val runtime = cachedState.copy(state = state, speaking = speaking, unheard = unheard)
            cachedState = runtime
            pushToHosts(context, runtime)
        }

        /**
         * 让桌面重新领一份 RemoteViews。
         *
         * ## 为什么必须有这个入口
         *
         * 每份 RemoteViews 都会被缓存在 **system_server 的 AppWidgetServiceImpl** 里
         * （`dumpsys appwidget` 里那个 `views=android.widget.RemoteViews@xxxx`）。
         * 关键坑在于：**应用被覆盖安装后，这份缓存不会自动失效**。
         *
         * 于是会出现这样一种极难自查的故障：旧版本生成的 RemoteViews 里调用了某个
         * 只有旧版本才有的方法（我们真实踩过：`AdapterViewFlipper.setAutoStart`
         * 根本没被 `@RemotableViewMethod` 标注），新版本代码早就改对了，
         * 但桌面重启后会直接拿**缓存里那份旧的**去 apply，抛 `ActionException`，
         * 小组件永久显示「无法添加微件」。此时无论怎么改代码、重装都没用，
         * 因为出问题的根本不是新代码。
         *
         * 唯一的解法是**由提供方主动推一份新的上去**，盖掉缓存里那份。
         * 三种时机都要触发：进程启动、应用被覆盖安装、状态变化。
         *
         * 代价是一次 `getAppWidgetIds` + 按实际尺寸渲染一张位图（几毫秒），
         * 相比"桌面永久空白且无法自愈"，这个代价可以忽略。
         */
        fun refreshAll(context: Context) {
            val app = context.applicationContext as? BriefWidgetApp ?: return
            app.appScope.launch {
                val runtime = runCatching {
                    withFreshness(app, app.container.runtimeStateStore.snapshot())
                }.onFailure {
                    // 沿用上一次状态是**有意的降级**（总比把桌面推成空白强），
                    // 但降级本身看不出来，所以留一条日志，别让它变成第二个"神秘故障"
                    Log.w(TAG, "刷新时读通知库/状态失败，沿用上一次缓存状态", it)
                }.getOrElse { cachedState }
                // 配色也要一起重读：用户在设置页拖完色相点保存，就靠这一次把它推到桌面
                val accent = runCatching { app.container.settingsRepository.snapshot().accent }
                    .getOrElse { cachedAccent }
                cachedState = runtime
                cachedAccent = accent
                pushToHosts(context, runtime)
            }
        }

        /**
         * 给 [WidgetRuntimeState.unheard] 补上真实取值。
         *
         * 这一步要查一次通知库，只有挂起环境里做得了，所以不能塞进 `toRuntimeState()`。
         * 查库失败会退回"没有新内容"（[BriefFreshness] 内部已经兜住），
         * 于是最坏情况是点阵安静着 —— 比顶着一个假的"有新消息"体面。
         */
        private suspend fun withFreshness(
            app: BriefWidgetApp,
            runtime: WidgetRuntimeState,
        ): WidgetRuntimeState = runtime.copy(
            unheard = BriefFreshness.resolve(
                dao = app.container.capturedNotificationDao,
                lastHeardAtEpochSeconds = runtime.lastHeardAtEpochSeconds,
            ),
        )

        /** 找出所有实例并逐份推送。id 为空（例如还没往桌面放过）时直接返回。 */
        private fun pushToHosts(context: Context, runtime: WidgetRuntimeState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, BriefWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            push(context, manager, ids, runtime, cachedAccent)
        }

        /** 供设置页「试听」后回归待机态使用。 */
        fun currentState(): WidgetRuntimeState = cachedState

        private const val TAG = "BriefWidgetProvider"
    }
}
