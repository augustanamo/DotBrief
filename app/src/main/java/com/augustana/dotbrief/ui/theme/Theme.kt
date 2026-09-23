package com.augustana.dotbrief.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 圆角全部归零。
 *
 * Nothing 的界面里唯一允许的圆是「状态指示点」和「圆形单选」——它们圆是因为
 * 语义就是"点/灯"，不是因为好看。卡片、输入框、按钮、色条一律直角。
 * 把 M3 的圆角令牌整体压平之后，所有现成的 Material 组件（TextField / AlertDialog）
 * 就自动跟着变方，不用逐个覆写 shape。
 */
private val NothingShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

/**
 * 亮色 = 纸白底 + 纯黑字。
 *
 * 注意 [lightColorScheme.outline] 直接承担了"发丝线"的角色：
 * 整套界面不画卡片描边、不画 elevation，分区感全部由这条 1px 线提供，
 * 所以任何一个新组件只要用 outline 画边，就自动跟版式对齐。
 */
private val LightColors = lightColorScheme(
    primary = PureBlack,
    onPrimary = PureWhite,
    secondary = MutedLight,
    onSecondary = PureWhite,
    tertiary = NRed,
    onTertiary = PureWhite,
    background = PureWhite,
    onBackground = PureBlack,
    surface = PureWhite,
    onSurface = PureBlack,
    surfaceVariant = PureWhite,
    onSurfaceVariant = MutedLight,
    outline = HairlineLight,
    outlineVariant = HairlineLightFaint,
    error = NRed,
    onError = PureWhite,
)

/**
 * 暗色 = 纯黑底 + 纯白字。
 *
 * surface 与 background 同色是刻意的：Nothing OS 里没有"卡片浮起来"这回事，
 * 层级靠留白和发光点阵表达，不靠亮度差。
 */
private val DarkColors = darkColorScheme(
    primary = PureWhite,
    onPrimary = PureBlack,
    secondary = MutedDark,
    onSecondary = PureBlack,
    tertiary = SignalRedLift,
    onTertiary = PureBlack,
    background = PureBlack,
    onBackground = PureWhite,
    surface = PureBlack,
    onSurface = PureWhite,
    surfaceVariant = PureBlack,
    onSurfaceVariant = MutedDark,
    outline = HairlineDark,
    outlineVariant = HairlineDarkFaint,
    error = SignalRedLift,
    onError = PureBlack,
)

@Composable
fun BriefWidgetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NothingTypography,
        shapes = NothingShapes,
        content = content,
    )
}
