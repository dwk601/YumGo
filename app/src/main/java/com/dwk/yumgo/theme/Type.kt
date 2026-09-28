@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.dwk.yumgo.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun yumgoStyle(size: Int, lineHeight: Int, weight: FontWeight, tracking: Float): TextStyle =
  TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
  )

/** Expressive scale, tightened slightly so fridge names and quantities stay quiet. */
val Typography =
  Typography(
    displayLarge = yumgoStyle(57, 64, FontWeight.Medium, -0.2f),
    displayMedium = yumgoStyle(45, 52, FontWeight.Medium, 0f),
    displaySmall = yumgoStyle(36, 44, FontWeight.Medium, 0f),
    headlineLarge = yumgoStyle(32, 40, FontWeight.Medium, 0f),
    headlineMedium = yumgoStyle(28, 36, FontWeight.Medium, 0f),
    headlineSmall = yumgoStyle(24, 32, FontWeight.Medium, 0f),
    titleLarge = yumgoStyle(22, 28, FontWeight.Medium, 0f),
    titleMedium = yumgoStyle(16, 24, FontWeight.Medium, 0.15f),
    titleSmall = yumgoStyle(14, 20, FontWeight.Medium, 0.1f),
    bodyLarge = yumgoStyle(16, 24, FontWeight.Normal, 0.15f),
    bodyMedium = yumgoStyle(14, 20, FontWeight.Normal, 0.15f),
    bodySmall = yumgoStyle(12, 16, FontWeight.Normal, 0.2f),
    labelLarge = yumgoStyle(14, 20, FontWeight.Medium, 0.1f),
    labelMedium = yumgoStyle(12, 16, FontWeight.Medium, 0.3f),
    labelSmall = yumgoStyle(11, 16, FontWeight.Medium, 0.3f),
    displayLargeEmphasized = yumgoStyle(57, 64, FontWeight.SemiBold, 0f),
    displayMediumEmphasized = yumgoStyle(45, 52, FontWeight.SemiBold, 0f),
    displaySmallEmphasized = yumgoStyle(36, 44, FontWeight.SemiBold, 0f),
    headlineLargeEmphasized = yumgoStyle(32, 40, FontWeight.SemiBold, 0f),
    headlineMediumEmphasized = yumgoStyle(28, 36, FontWeight.SemiBold, 0f),
    headlineSmallEmphasized = yumgoStyle(24, 32, FontWeight.SemiBold, 0f),
    titleLargeEmphasized = yumgoStyle(22, 28, FontWeight.SemiBold, 0f),
    titleMediumEmphasized = yumgoStyle(16, 24, FontWeight.Bold, 0.1f),
    titleSmallEmphasized = yumgoStyle(14, 20, FontWeight.Bold, 0.1f),
    bodyLargeEmphasized = yumgoStyle(16, 24, FontWeight.Medium, 0.1f),
    bodyMediumEmphasized = yumgoStyle(14, 20, FontWeight.Medium, 0.15f),
    bodySmallEmphasized = yumgoStyle(12, 16, FontWeight.Medium, 0.2f),
    labelLargeEmphasized = yumgoStyle(14, 20, FontWeight.Bold, 0.1f),
    labelMediumEmphasized = yumgoStyle(12, 16, FontWeight.Bold, 0.3f),
    labelSmallEmphasized = yumgoStyle(11, 16, FontWeight.Bold, 0.3f),
  )
