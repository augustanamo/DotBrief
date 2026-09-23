package com.augustana.dotbrief.ui.settings

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.augustana.dotbrief.R
import com.augustana.dotbrief.data.settings.AccentColor
import com.augustana.dotbrief.widget.DotMatrixArt
import kotlinx.coroutines.delay

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
 *   它想表达的"会流动"恰恰是它表达不出来的东西。
 *   所以现在改成**真的让它转起来**：勾了霓虹轮播，预览就按桌面那一套节奏走色相，
 *   播报时的样子在设置页里直接看得见（见 [carouselFrame]）。
 * - **"待机帧"这种标签是黑话**：用户看到的是一个点阵，不是"帧"。
 *   换成"桌面上的样子"才是在说他能看到的东西。
 *
 * 留下的两枚状态图是**唯一必须用看的**：灰和彩差在哪，用嘴讲不如直接摆出来。
 * 注意只有"有新内容"那枚会跟着轮播转 —— 灰的那枚按定义就是静止的。
 *
 * 版式上刻意**不套卡片**：点阵图自带圆角黑底（那是它作为桌面组件的真实形状），
 * 外面再包一层就成了"卡片里放卡片"，跟这套直角版式也冲突。
 */
@Composable
fun WidgetPreviewContent(accent: AccentColor) {
    val density = LocalDensity.current
    val heroSizePx = with(density) { 118.dp.roundToPx() }
    val chipSizePx = with(density) { 44.dp.roundToPx() }

    // 轮播的帧：跟着 pace 走一整圈，没开就停在第一帧
    val stepsPerCycle = accent.carouselPace.stepsPerCycle
    val frame = carouselFrame(accent.carousel, stepsPerCycle)
    val hueShift = if (accent.carousel) DotMatrixArt.carouselHueShift(frame, stepsPerCycle) else 0f

    // accent 是 data class，配色一变 key 就不相等，位图会自动重画
    val desktopFrame = remember(heroSizePx, accent, hueShift) {
        DotMatrixArt.render(heroSizePx, DotMatrixArt.IDLE_GLOW, accent, hueShift)
    }
    // 状态对照的两枚：同一个渲染器、同一份配色，只差一个 muted 开关
    val liveChip = remember(chipSizePx, accent, hueShift) {
        DotMatrixArt.render(chipSizePx, DotMatrixArt.IDLE_GLOW, accent, hueShift)
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
 * 预览区的帧计数器。
 *
 * 勾了霓虹轮播就按**桌面那一套节奏**往前推 —— 帧间隔直接取
 * [DotMatrixArt.CAROUSEL_FRAME_INTERVAL_MS]，所以这里转一圈的秒数和桌面上
 * 是同一个数，用户看到的快慢就是他会得到的快慢。
 *
 * 没勾就停在第 0 帧（亮度/色相都固定），预览回到静止 —— 这也是桌面待机时的样子，
 * 两边仍然一致。
 */
@Composable
private fun carouselFrame(enabled: Boolean, stepsPerCycle: Int): Int {
    var frame by remember(enabled) { mutableIntStateOf(0) }

    LaunchedEffect(enabled, stepsPerCycle) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            delay(DotMatrixArt.CAROUSEL_FRAME_INTERVAL_MS.toLong())
            frame = (frame + 1) % stepsPerCycle
        }
    }

    return if (enabled) frame else 0
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
