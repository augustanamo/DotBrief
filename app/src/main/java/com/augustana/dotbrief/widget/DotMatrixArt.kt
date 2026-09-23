package com.augustana.dotbrief.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.augustana.dotbrief.data.settings.AccentColor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 点阵光晕的画法。
 *
 * 桌面小组件画不了渐变、模糊和光晕（RemoteViews 只认 Bitmap），
 * 所以这里直接用 Canvas 把一整张图渲出来，再塞进 ImageView。
 *
 * 视觉规则（对齐设计稿）：
 * - 19 x 19 圆点阵，点距均匀；
 * - 亮部是**大平顶 + 平滑滚降**的光晕：中心一大片区域是满亮，再向边缘柔化，
 *   最外层留一层暗橄榄色，保证点阵网格在整个方块上都看得见；
 * - 每个点叠一点确定性噪声，避免亮区看起来"太数学"；
 * - 圆角黑底 15.5% 圆角，点阵裁在圆角内。
 *
 * 「呼吸」= 同一套点阵，只让整体亮度在 0.75 ~ 1.0 之间走一个正弦周期
 * （同时光晕半径轻微缩放，比单纯调亮度更像"吸气/呼气"）。
 * 帧序列由桌面侧的 AdapterViewFlipper 循环播放，因此**播放期间不需要 App 常驻**。
 */
object DotMatrixArt {

    /** 一个呼吸周期的帧数。170ms x 14 帧 ≈ 2.4s，接近人自然呼吸的节奏。 */
    const val FRAME_COUNT: Int = 14
    const val FRAME_INTERVAL_MS: Int = 170

    /** 待机光晕：就是设计稿里那个静止的样子（大块亮核，不闪）。 */
    const val IDLE_GLOW: Float = 0.78f

    private const val BREATH_MIN_GLOW = 0.35f

    /** 亮度 = B_min + B_range * glow。glow 从 0.35 走到 1.0，对应亮度 0.75 -> 1.0。 */
    private const val BRIGHTNESS_MIN = 0.62f
    private const val BRIGHTNESS_RANGE = 0.38f

    private const val GRID = 19
    private const val CORNER_RATIO = 0.155f
    private const val DOT_RADIUS_RATIO = 0.19f
    private const val GLOW_RADIUS_RATIO = 0.50f

    /** 平顶区结束、滚降区开始的归一化半径（越小平顶越大）。 */
    private const val FALLOFF_INNER = 0.10f
    private const val FALLOFF_OUTER = 0.72f

    /** 位图上限：ARGB_8888 每像素 4 字节，400 x 400 ≈ 640KB，留在 Binder 单次事务 1MB 红线内。 */
    const val MAX_ART_PX: Int = 400
    const val MIN_ART_PX: Int = 120

    /** 底色固定不动：不管主色换成什么，都要保持"黑底发光"的观感。 */
    private val COLOR_BACKGROUND = 0xFF0B0B0A.toInt()

    // ------------------------------------------------------------------
    // 主色 = 色相 + 饱和，三段色阶的 S/V 固定
    //
    // 原来那三个写死的颜色其实就是同一套 S/V 在 64° / 67° / 75° 三个色相上的取值：
    //   高光 #F2FF3C -> H64 S0.765 V1.000
    //   中间 #7E8C14 -> H67 S0.857 V0.549
    //   暗部 #202317 -> H75 S0.343 V0.137
    // 换色时只旋转 H，S/V 原样保留 —— 这样任何色相都还是"有体积的光晕"，
    // 不会因为用户随手一拖就把三段压成同一坨颜色。
    // 下面两个偏移量正是 67-64 与 75-64。
    // ------------------------------------------------------------------
    private const val HUE_OFFSET_MID = 3f
    private const val HUE_OFFSET_BASE = 11f

    private const val SAT_CORE = 0.765f
    private const val VAL_CORE = 1.0f
    private const val SAT_MID = 0.857f
    private const val VAL_MID = 0.549f
    private const val SAT_BASE = 0.343f
    private const val VAL_BASE = 0.137f

    // ------------------------------------------------------------------
    // 灰阶（"安静"）模式：没有新内容时的那枚点阵
    //
    // 用户原话是「没消息，或者听过后暂时没更新，就是灰色点阵，有新的才是彩色」。
    // 实现上不另画一套图，只是把同一套三段色阶**去色**：
    //
    // - 饱和度压到 0.045 而不是 0：纯中灰看起来像"图没渲染出来"，
    //   留一点点暖调（34°）更像 Nothing 那层纸灰（NGrey #DCD7D2）。
    // - 整体值乘 0.86：安静态不该像亮起来时一样抢注意力。
    // - 色相偏移直接失效：轮播是"活着"的表情，跟灰阶是互斥的两种气质。
    // ------------------------------------------------------------------
    private const val MUTED_HUE = 34f
    private const val MUTED_SATURATION = 0.045f
    private const val MUTED_VALUE_SCALE = 0.86f

    /** 第 [frame] 帧对应的光晕强度（正弦呼吸）。 */
    fun glowForFrame(frame: Int): Float {
        val t = (frame % FRAME_COUNT).toFloat() / FRAME_COUNT
        val wave = (sin(2.0 * Math.PI * t).toFloat() + 1f) / 2f
        return BREATH_MIN_GLOW + (1f - BREATH_MIN_GLOW) * wave
    }

    /**
     * 霓虹轮播的帧间隔（毫秒）。
     *
     * 与布局里的 `android:flipInterval` 必须一致 —— 改这里也要改
     * `layout/widget_brief_carousel.xml`，因为 `setFlipInterval()` 不是
     * `@RemotableViewMethod`，远程改不了，只能由 XML 提供。
     *
     * 260ms 是两个约束夹出来的：下界是 AdapterViewFlipper 自带的淡入淡出
     * （约 200ms，比它快的话每帧都来不及淡入到全不透明，整块点阵会一直发灰）；
     * 上界是观感，再慢就不像"流动"而像"跳变"。
     */
    const val CAROUSEL_FRAME_INTERVAL_MS: Int = 260

    /**
     * 霓虹轮播：[frame] 帧相对起始色相的偏移。
     *
     * 关键点是色相周期与呼吸周期**互相独立**：
     * - 呼吸固定 [FRAME_COUNT]（14）帧一圈，只管亮度；
     * - 色相固定 [stepsPerCycle] 帧走完 360°，只管颜色。
     *
     * [stepsPerCycle] 取 14 的整数倍，两个循环就能在序列末尾严丝合缝地对齐，
     * 不会出现"转到接缝时亮度跳一下"。开轮播时帧数就是 [stepsPerCycle]，
     * 不用轮播时帧数退回 14。
     */
    fun carouselHueShift(frame: Int, stepsPerCycle: Int): Float {
        val steps = stepsPerCycle.coerceAtLeast(FRAME_COUNT)
        return (frame % steps) * (360f / steps)
    }

    /** 一个色环周期需要多少毫秒（给设置页显示"约 N 秒一圈"用）。 */
    fun carouselCycleSeconds(stepsPerCycle: Int): Int =
        (stepsPerCycle.coerceAtLeast(FRAME_COUNT) * CAROUSEL_FRAME_INTERVAL_MS + 500) / 1000

    /**
     * 渲染一帧点阵图。
     *
     * [glow] 取值 0..1，越大越亮；[accent] 决定主色相与鲜艳度；
     * [hueShift] 在轮播模式下逐帧递增（见 [carouselHueShift]），静态时可省；
     * [muted] 为真时整块转灰阶（没有新内容），此时 [hueShift] 与 [accent] 的色相都会被忽略，
     * 只保留 [accent] 的鲜艳度之外的那套明暗体积 —— 灰点阵依旧是有立体感的，不是一坨平灰。
     */
    fun render(
        sizePx: Int,
        glow: Float,
        accent: AccentColor = AccentColor(),
        hueShift: Float = 0f,
        muted: Boolean = false,
    ): Bitmap {
        val size = sizePx.coerceIn(MIN_ART_PX, MAX_ART_PX)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val side = size.toFloat()
        val safeGlow = glow.coerceIn(0f, 1f)

        val saturation =
            if (muted) MUTED_SATURATION else accent.saturation.coerceIn(0f, 1f)
        val hue = if (muted) MUTED_HUE else accent.hue + hueShift
        val valueScale = if (muted) MUTED_VALUE_SCALE else 1f
        val coreColor = argb(hue, SAT_CORE * saturation, VAL_CORE * valueScale)
        val midColor = argb(hue + HUE_OFFSET_MID, SAT_MID * saturation, VAL_MID * valueScale)
        val baseColor = argb(hue + HUE_OFFSET_BASE, SAT_BASE * saturation, VAL_BASE * valueScale)

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BACKGROUND }
        val corner = side * CORNER_RATIO
        val bounds = RectF(0f, 0f, side, side)
        canvas.drawRoundRect(bounds, corner, corner, background)

        canvas.save()
        canvas.clipPath(
            Path().apply { addRoundRect(bounds, corner, corner, Path.Direction.CW) },
        )

        val pitch = side / (GRID + 0.6f)
        val dotRadius = pitch * DOT_RADIUS_RATIO
        val centerX = side / 2f
        val centerY = side / 2f

        // 呼吸时半径也跟着轻微缩放，比单纯调亮度更像"吸气/呼气"
        val glowRadius = side * GLOW_RADIUS_RATIO * (0.94f + 0.08f * safeGlow)
        val brightness = BRIGHTNESS_MIN + BRIGHTNESS_RANGE * safeGlow

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                val x = (column + 0.8f) * pitch
                val y = (row + 0.8f) * pitch

                val distance = hypot(x - centerX, y - centerY) / glowRadius
                val falloff = smoothstep(FALLOFF_INNER, FALLOFF_OUTER, 1f - distance)

                var intensity = falloff * brightness
                // 每个点固定的轻微起伏，破掉纯数学的规整感。
                // 幅度控制在 ±12%（原来 ±20% 会让亮核看起来像"雪花噪点"而不是"一整块在发光"）。
                intensity *= 0.88f + 0.24f * noise(column, row)
                intensity = intensity.coerceIn(0f, 1f)

                dotPaint.color = ramp(intensity, baseColor, midColor, coreColor)
                canvas.drawCircle(x, y, dotRadius * (0.86f + 0.28f * intensity), dotPaint)
            }
        }

        canvas.restore()
        return bitmap
    }

    /** 用 HSV 构造 ARGB。色相自动绕回 [0,360)，S/V 越界会被夹住。 */
    private fun argb(hue: Float, saturation: Float, value: Float, alpha: Int = 0xFF): Int =
        Color.HSVToColor(
            alpha,
            floatArrayOf(
                ((hue % 360f) + 360f) % 360f,
                saturation.coerceIn(0f, 1f),
                value.coerceIn(0f, 1f),
            ),
        )

    /** 暗底 -> 中间调 -> 高光 三段色阶。 */
    private fun ramp(intensity: Float, base: Int, mid: Int, core: Int): Int =
        if (intensity < 0.5f) {
            blend(base, mid, intensity / 0.5f)
        } else {
            blend(mid, core, (intensity - 0.5f) / 0.5f)
        }

    /** 标准 smoothstep：转场两端导数为 0，所以亮区边缘不会有生硬的硬边。 */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val t = (ratio.coerceIn(0f, 1f) * 255f).toInt()
        val inverse = 255 - t
        val red = (((from shr 16) and 0xFF) * inverse + ((to shr 16) and 0xFF) * t) / 255
        val green = (((from shr 8) and 0xFF) * inverse + ((to shr 8) and 0xFF) * t) / 255
        val blue = ((from and 0xFF) * inverse + (to and 0xFF) * t) / 255
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /** 每个点固定的伪随机值（0..1），保证同一帧重复渲染结果一致。 */
    private fun noise(column: Int, row: Int): Float {
        var hash = column * 374761393 + row * 668265263
        hash = (hash xor (hash shr 13)) * 1274126177
        hash = hash xor (hash shr 16)
        return (hash and 0x7FFFFFFF) / 2147483647f
    }
}
