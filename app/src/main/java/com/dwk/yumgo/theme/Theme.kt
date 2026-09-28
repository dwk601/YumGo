@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.dwk.yumgo.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Soft corners for cards, sheets, and the add editor. */
internal val YumgoShapes =
  Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    largeIncreased = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
  )

/** Spring motion for list, sheet, photo, and save transitions. */
internal val YumgoMotion: MotionScheme = MotionScheme.expressive()

/**
 * Yumgo is light-first: the app opens in [LightColorScheme] on a dark device too.
 * Previews and tests call this with no argument and get the same light palette.
 * MainActivity reads the saved selection and passes the resolved choice.
 */
@Composable
fun YumgoTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
  MaterialExpressiveTheme(
    colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
    motionScheme = YumgoMotion,
    shapes = YumgoShapes,
    typography = Typography,
    content = content,
  )
}
