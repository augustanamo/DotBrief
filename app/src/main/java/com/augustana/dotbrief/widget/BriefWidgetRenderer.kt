package com.augustana.dotbrief.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.augustana.dotbrief.R
import com.augustana.dotbrief.data.settings.AccentColor
import com.augustana.dotbrief.data.settings.WidgetRuntimeState
import com.augustana.dotbrief.data.settings.WidgetState
import com.augustana.dotbrief.tts.BriefPlaybackService

/**
 * 组装小组件的 RemoteViews。
 *
 * ## 为什么是"两套布局"而不是一套布局改属性
 *
 * RemoteViews 出于安全考虑，只允许调用被 `@android.view.RemotableViewMethod`
 * 标注过的方法（见 [android.widget.RemoteViews.MethodAction] 的校验）。
 * 而 `AdapterViewFlipper` 上：
 *
 * | 方法 | 可远程调用 |
 * |---|---|
 * | `showNext()` / `showPrevious()` | ✔ |
 * | `setDisplayedChild(int)` | ✔ |
 * | **`setAutoStart(boolean)`** | ✘ |
 * | **`setFlipInterval(int)`** | ✘ |
 * | `startFlipping()` / `stopFlipping()` | ✘ |
 *
 * 之前这里写的是 `setBoolean(id, "setAutoStart", …)` + `setInt(id, "setFlipInterval", …)`，
 * 两者都会在桌面端抛 `ActionException`，导致整份 RemoteViews 应用失败、小组件空白。
 *
 * 现在的做法：**动画开关 = 换布局资源**。
 * 待机用 [R.layout.widget_brief]（根本没有 flipper，完全静止、零耗电），
 * 播报时换成 [R.layout.widget_brief_playing]（呼吸）或 [R.layout.widget_brief_carousel]（霓虹），
 * flipper 的 `autoStart` / `flipInterval` 由 XML 属性在构造函数里解析，
 * 早于 attach，天然没有时序问题。
 *
 * ## 三份布局里都没有文字
 *
 * 小组件只有那枚点阵，没有字幕。状态由点阵自己表达：灰=没新东西、彩色=有新内容、
 * 流动=正在念，而**念过的部分会沿途褪灰**（进度）—— 四个信息各占一条通道，互不重叠。
 * 加一行字是用第二套语言重复同一件事，还占着点阵下沿的位置。
 */
object BriefWidgetRenderer {

    const val EXTRA_APP_WIDGET_ID = "extra_app_widget_id"

    /**
     * 标记「这次播报是点桌面小组件触发的」。
     *
     * 只用来决定要不要震一下。桌面上点完之后要等一两秒才出声，这中间没有任何反馈，
     * 用户会怀疑没点到而再点一次 —— 而第二下正好是「打断」。一次短震就能把这个来回消掉。
     * 设置页里的按钮不带这个标记：那里的人正盯着屏幕，震动纯属多余。
     */
    const val EXTRA_FROM_WIDGET = "extra_from_widget"

    private const val REQUEST_TOGGLE = 0x1001
    private const val DEFAULT_SIZE_DP = 110

    /**
     * 当前是否进入"动效"布局。
     *
     * 判据是**有没有声音在响**（[WidgetRuntimeState.audioActive]），不是"有没有人声"：
     * 点下去 BGM 一起播就该流动起来 —— 那 11 秒里用户已经知道"它在为我干活了"，
     * 让它静止着等，等于把最该有反馈的一段做成了没有反馈。
     */
    fun isAudible(runtime: WidgetRuntimeState): Boolean =
        runtime.state == WidgetState.PLAYING || runtime.audioActive

    /**
     * 当前是否真的需要 `AdapterViewFlipper` 驱动逐帧切换。
     *
     * **只有在出声的时候才动，其余一律静止。** 这条规则把三件事归到了一处：
     *
     * - 桌面点阵是个"安静的东西"，静止才是它的常态；一天里绝大多数时间它只是待着，
     *   没有任何理由让桌面一直在切帧（耗电、也和"桌面要静"的气质相反）；
     * - 动 = 有声音在响。这是一个**动作**的信号，而不是**内容**的信号 ——
     *   有没有新内容是靠颜色说的（见 [WidgetRuntimeState.muted]），不需要再动一遍；
     * - 于是三种状态各用一种通道表达，互不重叠：
     *   灰 = 没新东西，彩色 = 有新东西，流动 = 正在出声。
     *
     * ⚠️ "出声"从 **BGM 起播那一刻**算起，不只是人声 —— 见 [isAudible]。
     * 暂停与收尾之后自然回到静止（两者都会把 `audioActive` 落回 false）。
     *
     * 代价是"霓虹轮播"从"待机也流动"缩成了"只在出声时流动"。这是有意的：
     * 一个需要靠常驻动画来提醒存在的装饰，本身就是噪音。
     */
    fun isAnimated(runtime: WidgetRuntimeState): Boolean = isAudible(runtime)

    /** 按小组件实际占位算绘制尺寸（返回物理像素），并压在 Binder 事务上限内。 */
    fun artSizePx(context: Context, appWidgetId: Int): Int {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_SIZE_DP)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, DEFAULT_SIZE_DP)
        val density = context.resources.displayMetrics.density
        val sideDp = minOf(minWidth, maxHeight).coerceAtLeast(1)
        return (sideDp * density).toInt().coerceIn(DotMatrixArt.MIN_ART_PX, DotMatrixArt.MAX_ART_PX)
    }

    fun build(
        context: Context,
        appWidgetId: Int,
        runtime: WidgetRuntimeState,
        accent: AccentColor,
    ): RemoteViews {
        val audible = isAudible(runtime)
        val muted = runtime.muted
        // 布局三选一，判断依据只有一个：**要不要动**。
        //
        // 待机（占绝大多数时间）→ widget_brief：里面根本没有 AdapterViewFlipper，
        //   一张静态位图，零开销、零耗电，也不可能"偷偷动一下"。
        // 播报中 → 两份会动的布局，按用户选没选霓虹轮播分流：
        //   选了就沿色环流转（260ms/帧），没选就单纯呼吸（170ms/帧）。
        //   呼吸要快、轮播要慢，一套节奏满足不了两个目的，详见布局文件里的注释。
        val layout = when {
            !audible -> R.layout.widget_brief
            accent.carousel -> R.layout.widget_brief_carousel
            else -> R.layout.widget_brief_playing
        }
        val views = RemoteViews(context.packageName, layout)
        val size = artSizePx(context, appWidgetId)

        views.setImageViewBitmap(
            R.id.widget_static,
            DotMatrixArt.render(size, DotMatrixArt.IDLE_GLOW, accent, muted = muted),
        )

        // 点击目标放在最上层那层透明 View 上（见 widget_brief.xml / widget_brief_playing.xml 的注释）。
        // 根布局和静态图也挂一份，作为冗余兜底 —— 三个目标都是普通 View/VG，不会像 AdapterView 那样报错。
        val toggle = togglePendingIntent(context)
        views.setOnClickPendingIntent(R.id.widget_root, toggle)
        views.setOnClickPendingIntent(R.id.widget_static, toggle)
        views.setOnClickPendingIntent(R.id.widget_tap_target, toggle)

        if (audible) {
            val framesIntent = Intent(context, BriefWidgetService::class.java).apply {
                // 每个 widget 的 data 必须唯一，否则多个小组件会共用同一个 adapter
                data = Uri.parse("briefwidget://frames/$appWidgetId")
                putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_flipper, framesIntent)
        }
        return views
    }

    /**
     * 点击 = 让播报服务自己决定「开始」还是「打断」。
     *
     * 走 getForegroundService 是安全的：系统对前台服务启动限制的豁免清单里明确包含
     * "用户与小组件交互"这一条，所以息屏/后台状态下点击也能立刻起服务。
     */
    private fun togglePendingIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
        context,
        REQUEST_TOGGLE,
        Intent(context, BriefPlaybackService::class.java)
            .setAction(BriefPlaybackService.ACTION_TOGGLE)
            .putExtra(EXTRA_FROM_WIDGET, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

}
