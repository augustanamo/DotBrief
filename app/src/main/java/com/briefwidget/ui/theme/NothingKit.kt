package com.briefwidget.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

// ===========================================================================
// Nothing 设计语言的构件库
//
// 这一层是"版式语言"而不是"通用控件"：每个组件都对应 Nothing 界面里
// 一个明确角色的零件（分区、发丝线、状态灯、读数行、点阵 logotype），
// 不追求可配置，**追求默认值就长对**。设置页只负责把它们摆到正确的位置。
// ===========================================================================

/**
 * 内容最大宽度。
 *
 * 品牌规范里版式是"栅格 + 边距"（column margin = 格式宽度的 2.3%），
 * 没有"内容无限拉伸"这一说。这条约束在手机上无感，但在折叠屏展开、平板上
 * 很关键 —— 否则滑杆会横跨 20cm、文字行长到眼睛没法跟行。
 * 超宽时内容居中，发丝线仍然通栏：这样"切分区"的横向节奏不会被破坏。
 */
internal val ContentMaxWidth = 640.dp

// ---------------------------------------------------------------------------
// 1. NDot 55 的点阵实现
// ---------------------------------------------------------------------------

/**
 * 5 x 7 点阵字库。
 *
 * Nothing 的 logotype 用的是 NDot 55 —— 一款工业点阵字。它属于授权字体，不能打包，
 * 但"点阵"这件事本身**不是字体问题，是画法问题**：与其找一个相似的曲线字去凑，
 * 不如直接把 5x7 的位图铺成圆点阵，得到的正是对方字形来源的那套机械栅格。
 *
 * 只做 A-Z / 0-9 / 少量标点：它按规范只允许出现在 logotype 与产品名上，
 * 覆盖面越小越不容易被误用到正文里（中文无论如何都不可能点阵化，正好形成边界）。
 */
private object DotFont {

    const val COLS = 5
    const val ROWS = 7

    private val glyphs: Map<Char, List<String>> = mapOf(
        'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to listOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."),
        'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"),
        'J' to listOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to listOf("#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#"),
        'N' to listOf("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
        'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to listOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to listOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to listOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
        'X' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to listOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        ' ' to listOf(".....", ".....", ".....", ".....", ".....", ".....", "....."),
        '.' to listOf(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
        '-' to listOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        '/' to listOf("....#", "....#", "...#.", "..#..", ".#...", "#....", "#...."),
        '(' to listOf("..##.", ".#...", ".#...", ".#...", ".#...", ".#...", "..##."),
        ')' to listOf(".##..", "...#.", "...#.", "...#.", "...#.", "...#.", ".##.."),
        '+' to listOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        ':' to listOf(".....", ".##..", ".##..", ".....", ".##..", ".##..", "....."),
    )

    fun glyph(char: Char): List<String> = glyphs[char] ?: glyphs.getValue(' ')
}

/**
 * 点阵 logotype。
 *
 * 不做缩放适配、不做自动换行：logotype 按规范只出现在**固定位置**（版面顶部、对齐某一列），
 * 尺寸是设计时就定下来的。所以这里只按 dt 值把点铺出来，宽度由字形数算得，调用方控制字号。
 */
@Composable
fun DotMatrixText(
    text: String,
    modifier: Modifier = Modifier,
    dotSize: Dp = 4.5.dp,
    dotGap: Dp = 1.6.dp,
    charGap: Dp = 3.6.dp,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
    val glyphs = remember(text) { text.uppercase().map(DotFont::glyph) }
    if (glyphs.isEmpty()) return

    val cols = glyphs.size * DotFont.COLS
    val widthDp = dotSize * cols.toFloat() +
        dotGap * (cols - 1).toFloat() +
        charGap * (glyphs.size - 1).toFloat()
    val heightDp = dotSize * DotFont.ROWS + dotGap * (DotFont.ROWS - 1)

    Canvas(modifier.size(widthDp, heightDp)) {
        val d = dotSize.toPx()
        val g = dotGap.toPx()
        val cg = charGap.toPx()
        val cell = d + g
        val glyphWidth = DotFont.COLS * d + (DotFont.COLS - 1) * g
        val radius = d / 2f

        glyphs.forEachIndexed { glyphIndex, rows ->
            val originX = glyphIndex * (glyphWidth + cg)
            rows.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { columnIndex, cellChar ->
                    if (cellChar == '#') {
                        drawCircle(
                            color = color,
                            radius = radius,
                            center = Offset(
                                originX + columnIndex * cell + radius,
                                rowIndex * cell + radius,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 点阵网格底纹。
 *
 * 用来铺在黑色 logotype 区块上——Nothing 的大色块从来不是纯平面的，
 * 底下永远压着一层等距点阵，让黑看起来有"材质"。点要极小、极疏，
 * 目标是退到一个不打扰文字的灰度上。
 */
@Composable
fun DotGridBackdrop(
    modifier: Modifier = Modifier,
    color: Color,
    pitch: Dp = 11.dp,
    dotRadius: Dp = 0.6.dp,
) {
    Canvas(modifier) {
        val p = pitch.toPx()
        val r = dotRadius.toPx()
        var y = p / 2f
        while (y < size.height) {
            var x = p / 2f
            while (x < size.width) {
                drawCircle(color, r, Offset(x, y))
                x += p
            }
            y += p
        }
    }
}

// ---------------------------------------------------------------------------
// 2. 版式骨架
// ---------------------------------------------------------------------------

/** 发丝线：整套界面的分区只靠它，不用卡片、不用阴影。 */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outline,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/**
 * 一个分区。
 *
 * 结构与 Nothing OS 的设置页一致：**顶部一条通栏发丝线** → 编号 + 标题 + 英文眉标 →
 * 内容。发丝线通栏（贴屏幕左右边），内容缩进 20dp，这样滚起来是一条条被"切"开的条带，
 * 而不是一堆浮起来的卡片。
 */
@Composable
fun NothingSection(
    index: String,
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    desc: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        // widthIn 在外层收窄约束、fillMaxWidth 在内层填满收窄后的宽度，
        // 顺序不能反：反过来 fillMaxWidth 会先把宽度锁死在父容器上，widthIn 就没得限制了。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionHeader(index = index, title = title, eyebrow = eyebrow, desc = desc)
                content()
            }
        }
    }
}

@Composable
fun SectionHeader(
    index: String,
    title: String,
    eyebrow: String? = null,
    desc: String? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (eyebrow != null) {
                Text(
                    text = eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (desc != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 技术读数行：左边标签、右边等宽值。状态区专用（"机器输出"的版式）。 */
@Composable
fun TechRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
        )
    }
}

// ---------------------------------------------------------------------------
// 3. 状态
// ---------------------------------------------------------------------------

/**
 * 状态灯。
 *
 * 亮 = 实心红点，灭 = 空心描边圈。全屏只有这一处会用红，
 * 所以用户扫一眼就知道"现在是不是在干活"。
 */
@Composable
fun StatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val ring = if (active) SignalRed else MaterialTheme.colorScheme.outline
    Box(
        modifier
            .size(size)
            .background(if (active) SignalRed else Color.Transparent, CircleShape)
            .border(1.dp, ring, CircleShape),
    )
}

/** 带边框的小标签，用来显示"已授权"这类二元状态。 */
@Composable
fun NothingTag(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val color = if (active) SignalRed else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .border(1.dp, color)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(active = active, size = 6.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * 分段选择器（一排等宽的方角格子）。
 *
 * 用来表达"三选一"这种**档位**：比三个单选项紧凑得多，一行就能读完，
 * 而且"当前选中哪一档"在视觉上是一块实心方块，跟这套黑白版式天然合拍。
 * 选中 = 实底（反白），未选中 = 描边 —— 与按钮用的是同一套对比层级。
 */
@Composable
fun NothingSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(if (selected) scheme.primary else Color.Transparent)
                    .border(1.dp, if (selected) scheme.primary else scheme.outline)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. 选择控件
//
// 全部自绘，不用 Material 的 RadioButton / Checkbox / Slider：
// 它们的形状令牌压在 0 圆角的主题里会得到一堆半方不圆的怪东西，
// 而且尺寸（48dp 触摸区 + 20dp 视觉）很难压到 Nothing 那种克制的密度。
// ---------------------------------------------------------------------------

/** 单选项：细描边圆环，选中时内部填一个实心红点。 */
@Composable
fun NothingRadio(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onClick)
                .border(
                    width = 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(SignalRed, CircleShape),
                )
            }
        }
    }
}

/** 复选项：方框，选中时内部填一个实心红方块 —— 直角是默认，圆才是例外。 */
@Composable
fun NothingCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clickable { onCheckedChange(!checked) }
            .border(
                width = 1.dp,
                color = if (checked) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline,
                shape = RectangleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(SignalRed),
            )
        }
    }
}

/** 开关。Material 的 Switch 保留（滑动手势很值钱），只把配色掰成黑白 + 红。 */
@Composable
fun NothingSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = null,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.onPrimary,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = scheme.primary,
            uncheckedThumbColor = scheme.onSurfaceVariant,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = scheme.outline,
        ),
    )
}

private val SLIDER_HEIGHT = 30.dp
private val SLIDER_THUMB_RADIUS = 5.dp

/**
 * 滑杆：一根 2px 细线 + 一个小圆点。
 *
 * 没复用 Material 的 `Slider`：它的轨道厚度、thumb 尺寸、两端的留白都是按
 * 48dp 触摸区设计的，压不到这种密度；而且 valueRange 与 steps 的内部取整
 * 规则和这里需要的"端点必须精确可到"不完全一致。自绘之后换算逻辑一目了然。
 *
 * ## 手势：为什么不是"按下即取值"
 *
 * Material 的 Slider 一按下就跳到按下的位置。放在设置页这种**可滚动列表**里，
 * 这会变成一个真实的体验事故：用户想往上翻页，手指刚落到滑杆上，
 * 值就已经被改了 —— 而且改完自己还不知道（翻页动作会继续执行）。
 *
 * 所以这里的判定是：**先看手势往哪走**。
 * - 横向位移超过 touchSlop，且横向大于纵向 → 判定为"调值"，接管事件并吃掉，
 *   列表不会再跟着滚；
 * - 纵向位移超过 touchSlop → 判定为"翻页"，直接放手，一次值都不改；
 * - 整个过程中位移都没超过 touchSlop 就松手 → 视为轻点，这时才允许直接跳值
 *   （保留 Material 那种"点哪跳哪"的顺手感）。
 */
@Composable
fun NothingSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    trackColor: Color = MaterialTheme.colorScheme.outline,
) {
    val span = (valueRange.endInclusive - valueRange.start).let { if (it > 0f) it else 1f }
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SLIDER_HEIGHT)
            // awaitEachGesture：按下与拖动必须放在**同一个**手势循环里。
            // 拆成 detectTapGestures + detectHorizontalDragGestures 两个 pointerInput 会互相抢——
            // 前者一消费 down，后者的拖动就再也起不来。
            .pointerInput(valueRange, steps) {
                val thumb = SLIDER_THUMB_RADIUS.toPx()
                val slop = viewConfiguration.touchSlop

                fun valueAt(x: Float): Float {
                    val usable = (size.width - thumb * 2f).coerceAtLeast(1f)
                    val t = ((x - thumb) / usable).coerceIn(0f, 1f)
                    val raw = valueRange.start + t * span
                    return if (steps > 0) snapToStep(raw, valueRange, steps) else raw
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val startY = down.position.y
                    var owned = false
                    var abandoned = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        val x = change.position.x
                        val dx = abs(x - startX)
                        val dy = abs(change.position.y - startY)

                        if (!owned && !abandoned) {
                            when {
                                dx > slop && dx >= dy -> owned = true
                                dy > slop -> abandoned = true
                            }
                        }

                        if (owned) {
                            onValueChange(valueAt(x))
                            if (change.pressed) change.consume()
                        }

                        if (!change.pressed) {
                            if (owned) {
                                change.consume()
                            } else if (!abandoned) {
                                onValueChange(valueAt(x))
                                change.consume()
                            }
                            break
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            val thumb = SLIDER_THUMB_RADIUS.toPx()
            val usable = (size.width - thumb * 2f).coerceAtLeast(1f)
            val centerX = thumb + usable * fraction
            val stroke = 2.dp.toPx()

            drawLine(trackColor, Offset(thumb, centerY), Offset(size.width - thumb, centerY), stroke)
            drawLine(accent, Offset(thumb, centerY), Offset(centerX, centerY), stroke)
            drawCircle(accent, thumb, Offset(centerX, centerY))
        }
    }
}

/** 滑杆 + 一行读数（标签在左、等宽数值在右）。 */
@Composable
fun NothingSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    accent: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        NothingSlider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            steps = steps,
            accent = accent,
        )
    }
}

/** steps 表示"中间有几档"（与 Material Slider 同义）：3 档 → 均分 4 段。 */
private fun snapToStep(
    raw: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f || steps <= 0) return raw
    val step = span / (steps + 1)
    val index = ((raw - range.start) / step).roundToInt()
    return (range.start + index * step).coerceIn(range.start, range.endInclusive)
}

// ---------------------------------------------------------------------------
// 5. 输入与按钮
// ---------------------------------------------------------------------------

/**
 * 输入框。
 *
 * 标签刻意放在框**上方**而不是做成 floating label：floating label 靠动画表达状态，
 * 与这套"静态读板"的语言不搭。方角 + 1px 描边 + 上方等宽小标，
 * 整行读起来像设备铭牌上的一条参数。
 */
@Composable
fun NothingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxLines,
            shape = RectangleShape,
            textStyle = MaterialTheme.typography.bodyMedium,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            placeholder = hint?.let {
                {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingIcon = trailing,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 按钮。
 *
 * 两种形态就够：描边（次级）与实底（主操作）。实底在白底上是纯黑块、
 * 在黑底上是纯白块 —— 永远是最高的那一档对比，一屏最多一个。
 */
@Composable
fun NothingButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.35f

    Box(
        modifier = modifier
            .height(46.dp)
            .background(
                if (filled) scheme.primary.copy(alpha = alpha) else Color.Transparent,
            )
            .border(
                width = 1.dp,
                color = if (filled) scheme.primary.copy(alpha = alpha)
                else scheme.outline.copy(alpha = alpha),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) scheme.onPrimary.copy(alpha = alpha)
            else scheme.onSurface.copy(alpha = alpha),
            maxLines = 1,
        )
    }
}
