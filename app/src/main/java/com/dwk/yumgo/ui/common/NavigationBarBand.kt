package com.dwk.yumgo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.dwk.yumgo.theme.DarkColorScheme
import com.dwk.yumgo.theme.LightColorScheme

/**
 * The surface under the platform's navigation buttons, for every window that has to carry one.
 *
 * With the gesture handle the platform draws a pill and takes its colour from the window, so the
 * bar is left to the app's own paper. The buttons are drawn by the platform instead, and it takes
 * their colour from the *device*: on API 35 a light app on a dark device got pale buttons (#C5C6D0)
 * on its own light paper at 1.6:1, and a dark app on a light device got dark ones (#44464F) on its
 * dark paper at 2.0:1. The app cannot change those icons. The window appearance reaches SystemUI
 * and is ignored there, the activity's own uiMode never reaches it at all, and
 * `window.navigationBarColor` is a no-op for an app targeting API 35. The bar is the one thing
 * left, so it takes the polarity the device's buttons need, in a tone of the app's own palette: the
 * `surfaceContainer` of the palette the app is not painting with. Both tones clear 7:1 against the
 * buttons, and a slab of pure black or white would not sit with the paper.
 *
 * Two cases get nothing, because the app's own paper already carries the buttons: a bar no taller
 * than the gesture handle, which is how the two are told apart, and a palette that already matches
 * the device. A band there would only show as a seam.
 *
 * The bar is read from the insets of the window this is composed in, so a phone turned sideways, and
 * a device switching between the handle and the buttons while the app is open, bring it back. Add
 * it in a `Box` that fills that window, above the content: it covers what scrolls under a bar, as a
 * system bar would, and takes no touches of its own.
 */
@Composable
fun BoxScope.NavigationBarBand() {
  val deviceDark = isSystemInDarkTheme()
  if (MaterialTheme.colorScheme.surface.isDark() == deviceDark) return
  val insets = WindowInsets.navigationBars.asPaddingValues()
  val direction = LocalLayoutDirection.current
  val left = insets.calculateLeftPadding(direction)
  val right = insets.calculateRightPadding(direction)
  val bottom = insets.calculateBottomPadding()
  if (maxOf(bottom, right, left) < ButtonNavigationBarHeight) return
  // The insets are absolute, so the band follows the edge they name rather than the edge the layout
  // reads: the bar keeps its own side however the phone is turned.
  val ltr = direction == LayoutDirection.Ltr
  val band =
    when {
      bottom >= right && bottom >= left -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom)
      right >= left -> Modifier.align(if (ltr) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().width(right)
      else -> Modifier.align(if (ltr) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().width(left)
    }
  Box(
    modifier =
      band.background(if (deviceDark) DarkColorScheme.surfaceContainer else LightColorScheme.surfaceContainer),
  )
}

/**
 * The navigation buttons take 48dp of bar, the gesture handle 24dp or less. A bar over 36dp is
 * read as buttons, which is the same call the platform makes for the 2-button layout.
 */
private val ButtonNavigationBarHeight = 36.dp

/** Whether a colour reads as dark, by the relative luminance the contrast rules use. */
private fun Color.isDark(): Boolean = luminance() < 0.5f
