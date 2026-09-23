package com.briefwidget.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Nothing 字体系统的落地方式
//
// 品牌一共五款字：NType82 Headline / NType82 Regular / NType82 Mono / NDot55 / Lettera Mono LL。
// 它们都是商业授权字体，不能打进 APK，所以这里做的是**角色映射**而不是照搬字形：
//
//   NDot 55        → 点阵字，只用于 logotype 与产品名 …… 由 DotMatrixText 直接画点阵实现（见 NothingKit.kt）
//   NType82 Headline → display*  ：大字号、紧缩字距、80% 行高
//   NType82 Regular  → body*/title*：正文与短标题
//   NType82 Mono
//   Lettera Mono LL  → label*   ：等宽，只在"机器输出"上用 —— 编号、数值、单位、状态、英文眉标
//
// 关键约束来自品牌指南那句"built to behave like mechanical output"：
// 等宽字体不调字距（tracking 归零是它的设计前提），但这里给 label 加正向字距是为了
// 数位读数与全大写英文眉标在手机上更易读 —— 属于移动端的落地妥协，不是风格选择。
// ---------------------------------------------------------------------------

private val Tech = FontFamily.Monospace
private val Body = FontFamily.Default

val NothingTypography = Typography(
    // ---- Headline：mechanical，字距收紧、行高压到 0.82 左右 ----
    displayLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 56.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.6).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 42.sp,
        lineHeight = 35.sp,
        letterSpacing = (-1.2).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.8).sp,
    ),

    headlineLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 19.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.2).sp,
    ),

    titleLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    /** 分区标题：等宽 + 疏字距，读起来像设备铭牌。 */
    titleSmall = TextStyle(
        fontFamily = Tech,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 1.4.sp,
    ),

    bodyLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),

    /** label 系列 = 技术读数：编号、数值、单位、英文眉标、按钮文字。 */
    labelLarge = TextStyle(
        fontFamily = Tech,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Tech,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.9.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Tech,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.3.sp,
    ),
)
