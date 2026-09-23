package com.briefwidget.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.briefwidget.R
import com.briefwidget.data.settings.AccentColor
import com.briefwidget.widget.DotMatrixArt

/**
 * 小组件预览：**一张桌面原样的点阵 + 两枚状态对照**，就这些。
 *
 * ## 为什么只留这么点
 *
 * 这里曾经有三段技术小字（"19 × 19 圆点阵 · HSV 三段色阶"、"与桌面逐像素同源"）
 * 和一排 5 帧的"呼吸帧 / 霓虹帧"样张。它们都被删掉了，理由各不相同：
 *
 * - **技术小字是给开发者看的**：点阵多少颗圆、用什么色彩空间，是实现的副产品，
 *   用户既不需要知道，知道了也做不了什么。想了解的人看得到桌面本身。
 * - **一排静帧解释不了动画**：截 5 帧摆成一排，看到的是 5 张静止的图 ——
 *   它想表达的"会流动"恰恰是它表达不出来的东西。而"动效只在播报时才发生"之后，
 *   这排图更没有意义了：设置页里根本看不到桌面在播报时的样子。
 * - **"待机帧"这种标签是黑话**：用户看到的是一个点阵，不是"帧"。
 *   换成"桌面上的样子"才是在说他能看到的东西。
 *
 * 留下的两枚状态图是**唯一必须用看的**：灰和彩差在哪，用嘴讲不如直接摆出来。
 *
 * 版式上刻意**不套卡片**：点阵图自带圆角黑底（那是它作为桌面组件的真实形状），
 * 外面再包一层就成了"卡片里放卡片"，跟这套直角版式也冲突。
 */
@Composable
fun WidgetPreviewContent(accent: AccentColor) {
    val density = LocalDensity.current
    val heroSizePx = with(density) { 118.dp.roundToPx() }
    val chipSizePx = with(density) { 44.dp.roundToPx() }

    // accent 是 data class，配色一变 key 就不相等，位图会自动重画
    val desktopFrame = remember(heroSizePx, accent) {
        DotMatrixArt.render(heroSizePx, DotMatrixArt.IDLE_GLOW, accent)
    }
    // 状态对照的两枚：同一个渲染器、同一份配色，只差一个 muted 开关
    val liveChip = remember(chipSizePx, accent) {
        DotMatrixArt.render(chipSizePx, DotMatrixArt.IDLE_GLOW, accent)
    }
    val quietChip = remember(chipSizePx, accent) {
        DotMatrixArt.render(chipSizePx, DotMatrixArt.IDLE_GLOW, accent, muted = true)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                bitmap = desktopFrame.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(118.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
            Text(
                text = stringResource(R.string.preview_desktop_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StateChip(
                bitmap = liveChip,
                labelRes = R.string.preview_state_live,
                live = true,
            )
            StateChip(
                bitmap = quietChip,
                labelRes = R.string.preview_state_quiet,
                live = false,
            )
        }
    }
}

/**
 * 状态对照色块：一枚点阵 + 右侧一句等宽小字（横排才放得下两枚而不顶到行外）。
 *
 * 标签颜色跟着"亮 / 灰"走（亮的那枚用正文色，灰的那枚用次级色），
 * 这样即使有人分辨不出点阵的细微灰度，也能从字的对比上读出差别。
 */
@Composable
private fun StateChip(
    bitmap: Bitmap,
    labelRes: Int,
    live: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(11.dp)),
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = if (live) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
