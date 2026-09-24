package com.augustana.dotbrief.widget

/**
 * 播报进度（0..1）的**进程内**载体。
 *
 * 为什么不落 DataStore：这条链路是"每秒变一点"的高频数据，
 * 而读写它的两端（[BriefPlaybackService] 写、[BriefWidgetService] 的帧工厂读）
 * 恰好**在同一个进程里** —— 桌面小组件的 RemoteViewsService 与播报服务同属 App 进程。
 * 为一份每次都过期的中间值去写磁盘，会白白把播报中的每一秒变成一次事务。
 *
 * 也不放进 [com.augustana.dotbrief.data.settings.WidgetRuntimeState]：
 * 那份状态是"用户能看见的事实"（听没听过、上次生成于何时），要跨进程重启后仍然成立；
 * 进度是**一次性动画**的中间帧，进程死了它就该消失。混在一起会让"要不要落盘"这个判断
 * 在每次加字段时都要重新做一遍，迟早会错。
 *
 * 线程约定：写了 `@Volatile`。写入方是播报服务的协程（主线程）与 TTS 引擎的进度回调线程，
 * 读取方是系统的帧工厂回调线程 —— 三方都只做"读一个 float / 写一个 float"，
 * 不需要锁，值本身也只用于决定画面好看与否。
 */
internal object BriefProgress {

    @Volatile
    var value: Float = 0f
        private set

    fun set(progress: Float) {
        value = progress.coerceIn(0f, 1f)
    }

    /**
     * 复位到 0，**不碰桌面**。
     *
     * 专给"播报结束、布局已经切回静态"那一刻用：此时 `widget_flipper` 已经不在任何一份
     * RemoteViews 里，再对着它调 `notifyAppWidgetViewDataChanged` 只会让系统白跑一趟
     * 并打一条 warning（同样的理由见 [BriefWidgetProvider.push] 里的判断）。
     */
    fun reset() {
        value = 0f
    }
}
