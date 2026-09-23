package com.briefwidget.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Nothing 品牌色板
//
// 依据 nothing.wiki/nothing/brand_reference：
//   Foundation — Pure Black #000000 / Window Grey #B1B3B3 / N-Grey #DCD7D2 / Pure White #FFFFFF
//   Primary    — N-Red #C8102E / N-Blue #002F6C / N-Yellow #FFC700
//
// 品牌规则里最要紧的两条，直接决定了这个文件的结构：
//   1. 图形元素不加渐变、不加投影、不做彩色填充 —— 所以整套界面没有 elevation，
//      也没有任何"渐变卡片"，唯一的彩色是状态信号那一抹红。
//   2. 黑与白只服务版式与文字，颜色只用来"指出重点"—— 所以强调色是稀缺资源，
//      一屏之内基本只有一处（live 指示点 / 选中态）。
// ---------------------------------------------------------------------------

/** Foundation：四个基色，撑起整个版式。 */
val PureBlack = Color(0xFF000000)
val PureWhite = Color(0xFFFFFFFF)
val WindowGrey = Color(0xFFB1B3B3)
val NGrey = Color(0xFFDCD7D2)

/** Primary：品牌强调色。 */
val NRed = Color(0xFFC8102E)
val NBlue = Color(0xFF002F6C)
val NYellow = Color(0xFFFFC700)

/** Nothing OS 小组件红（品牌指南之外的常用替代色）。 */
val WidgetRed = Color(0xFFD71920)

// ---------------------------------------------------------------------------
// 语义色
// ---------------------------------------------------------------------------

/**
 * "有东西在动"的信号色。
 *
 * Nothing OS 用那一抹红表示 live：录音、通话、指示灯。这里沿用同一个语义 ——
 * 只出现在「正在播报 / 已授权 / 单选选中」这三种"当前生效"的状态上。
 */
val SignalRed = NRed

/** 纯黑底上的提亮红：N-Red 直接在黑上做正文色对比度不够，同色相提一档。 */
internal val SignalRedLift = Color(0xFFE03A50)

/** 1px 分隔线（"发丝线"）。整套界面靠它切分区，而不是靠卡片圆角。 */
internal val HairlineDark = Color(0xFF262626)
internal val HairlineLight = NGrey
internal val HairlineLightFaint = Color(0xFFEDEAE6)

/** 纯黑底上的次级文字用 Window Grey —— 这是品牌里唯一被允许"降级"的灰。 */
internal val MutedDark = WindowGrey
internal val MutedLight = Color(0xFF6E6E6A)

/** 纯黑底上比发丝线更轻的一档，用于点阵网格、禁用态。 */
internal val HairlineDarkFaint = Color(0xFF171717)
