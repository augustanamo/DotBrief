package com.augustana.dotbrief.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.augustana.dotbrief.BriefWidgetApp
import com.augustana.dotbrief.R
import com.augustana.dotbrief.data.settings.AccentColor
import com.augustana.dotbrief.data.settings.WidgetState
import kotlinx.coroutines.runBlocking

/**
 * 动画帧的供给方。
 *
 * 为什么不是 Glance：Glance 是 RemoteViews 的声明式封装，**表达不了逐帧动画**。
 * 桌面小组件里唯一能"自己动"的机制是 AdapterViewFlipper + RemoteViewsService：
 * 我们一次性交出 N 帧位图，桌面侧自己按 flipInterval 循环切换，播放期间不需要 App 常驻。
 *
 * 帧在 getViewAt 里现画（361 个圆的 Canvas 绘制，几毫秒），不落盘、不缓存，
 * 避免图片文件的生命周期和失效问题。
 */
class BriefWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = BriefFrameFactory(
        context = applicationContext,
        appWidgetId = intent.getIntExtra(
            BriefWidgetRenderer.EXTRA_APP_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ),
    )
}

private class BriefFrameFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var playing: Boolean = false

    /**
     * 灰阶静止态：安静待机 + 没有没听过的新内容。
     *
     * 这是「颜色是稀缺资源」的落地 —— 没有新东西时点阵就该是一枚安静的灰点阵，
     * 有新消息才亮起来。判定口径与 Provider 完全一致（都走 [BriefFreshness]），
     * 否则会出现"布局选的是彩色版、帧画出来却是灰的"这种自相矛盾。
     */
    private var muted: Boolean = false
    private var artSizePx: Int = DotMatrixArt.MAX_ART_PX
    private var accent: AccentColor = AccentColor()

    override fun onCreate() = Unit

    /** 每次状态变化/尺寸变化后系统会回调这里，是唯一允许读磁盘的地方。 */
    override fun onDataSetChanged() {
        val app = context.applicationContext as? BriefWidgetApp
        val runtime = app?.let {
            runBlocking { it.container.runtimeStateStore.snapshot() }
        }
        playing = runtime?.let { it.state == WidgetState.PLAYING || it.speaking } ?: false

        // "有没有新内容"要查一次通知库才算得出来，帧工厂里允许阻塞（系统在子线程回调），
        // 所以这里当场算，而不是等谁把它塞进 DataStore。
        // 两个时间戳都传：定时刷出来的正文同样意味着"有新内容"。
        val unheard = if (app == null || runtime == null) {
            false
        } else {
            runBlocking {
                BriefFreshness.resolve(
                    dao = app.container.capturedNotificationDao,
                    lastHeardAtEpochSeconds = runtime.lastHeardAtEpochSeconds,
                    lastBriefAtEpochSeconds = runtime.lastBriefAtEpochSeconds,
                )
            }
        }
        muted = runtime?.copy(unheard = unheard)?.muted ?: false

        // 配色每次都读。帧工厂与 Provider 是两条独立的取数路径，
        // 不能指望"Provider 刚读过、这边也就有新值了"，用 runCatching 兜住：
        // 配色读失败不该让整段动画变成空白。
        accent = app?.let {
            runCatching { runBlocking { it.container.settingsRepository.snapshot().accent } }
                .getOrDefault(accent)
        } ?: accent

        artSizePx = if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            DotMatrixArt.MAX_ART_PX
        } else {
            BriefWidgetRenderer.artSizePx(context, appWidgetId)
        }
    }

    /**
     * 一个动画序列有多少帧。
     *
     * 「要不要动」在 Provider 与这里都只有一条判据：**在不在播报**。
     * 所以 `animated` 直接跟着 `playing` 走 —— 这里唯二会用到帧工厂的时刻，
     * 就是播报开始（要开始换帧）与播报结束（要换回静止布局）。
     *
     * 播报中开轮播时序列长度 = 色环步数（28 / 42 / 56，见 [CarouselPace]），
     * 而呼吸周期固定 14 帧 —— 14 能整除这三个数，所以两个循环在序列末尾同相，
     * 转完一圈色环时亮度刚好也回到起点。
     */
    private val animated: Boolean get() = playing

    private val sequenceFrames: Int
        get() = if (accent.carousel) {
            accent.carouselPace.stepsPerCycle
        } else {
            DotMatrixArt.FRAME_COUNT
        }

    override fun getCount(): Int = if (animated) sequenceFrames else 1

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_brief_frame)
        val glow = if (playing) {
            DotMatrixArt.glowForFrame(position)
        } else {
            DotMatrixArt.IDLE_GLOW
        }
        // 霓虹轮播：播报时"呼吸 + 换色"同时走（待机时根本不会走到这里 —— 没有 flipper）。
        // 灰阶不会出现在这里（灰阶的前提是"安静待机"，与 playing 互斥），
        // 所以不用再判一次 muted，避免同一个事实在两处各写一遍。
        val hueShift = if (accent.carousel) {
            DotMatrixArt.carouselHueShift(position, accent.carouselPace.stepsPerCycle)
        } else {
            0f
        }

        views.setImageViewBitmap(
            R.id.frame_image,
            DotMatrixArt.render(
                artSizePx,
                glow,
                accent,
                hueShift,
                muted = muted,
                // 只有播报中才有进度可言（这个序列本身也只在播报中存在）。
                // 多这一道判断是为了防止"布局还停在 playing、进度已复位"的瞬间画出全彩的一帧：
                // 复位与切布局之间有一段异步窗口，宁可这半秒不上进度，也不要它闪一下。
                progress = if (playing) BriefProgress.value else 0f,
            ),
        )
        return views
    }

    /**
     * 数据加载期间显示什么 —— **什么都不显示**。
     *
     * 这里返回 null 会让系统自己造一份默认的 loading view（一个 ProgressBar，
     * 有的 ROM 还会附一行提示文字），而它偏偏出现在**点阵正中间**的那一瞬间。
     * 本组件的承诺是"一个字都不显示"，所以给一份全透明的空布局把它顶掉。
     *
     * 为什么切布局那一刻一定会走 loading：换布局 = 递给桌面一份新的 RemoteViews，
     * 里面那个 `widget_flipper` 要重新绑一次 RemoteViewsService，而
     * `RemoteViewsAdapter.getCount()` 在数据就绪之前返回 0 —— 空集合的那几帧就是它。
     *
     * 布局根只能用白名单里的类（[R.layout.widget_brief_loading] 用的 FrameLayout）；
     * 不能图省事写 `<View>`，理由见 widget_brief.xml 里那段踩坑说明。
     */
    override fun getLoadingView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_brief_loading)

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        if (animated) (position % sequenceFrames).toLong() else 0L

    override fun hasStableIds(): Boolean = false

    override fun onDestroy() = Unit
}
