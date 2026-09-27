package com.dwk.yumgo.ui.common

import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.dwk.yumgo.theme.DarkColorScheme
import com.dwk.yumgo.theme.LightColorScheme

/**
 * The surface under the platform's navigation buttons, for every window that can end up behind the
 * bar: the activity's, and the add sheet's, which is a window of its own.
 *
 * With the gesture handle the platform draws a pill and takes its colour from this window, so the
 * bar is left to the app's own paper. The buttons are drawn by the platform instead, and it takes
 * their colour from the *device*: on API 35 a light app on a dark device got pale buttons (#C5C6D0)
 * on its own light paper at 1.6:1, and a dark app on a light device got dark ones (#44464F) on its
 * dark paper at 2.0:1. The app cannot change those icons. The window appearance reaches SystemUI and
 * is ignored there, the activity's own uiMode never reaches it at all, and `window.navigationBarColor`
 * is a no-op for an app targeting API 35. The bar is the one thing left, so it takes the polarity the
 * device's buttons need, in a tone of the app's own palette: the `surfaceContainer` of the palette
 * the app is not painting with. Both tones clear 7:1 against the buttons, where a slab of pure
 * black or white would not sit with the paper.
 *
 * Three cases get nothing, because the buttons are already carried or hidden. A bar no taller than
 * the gesture handle, which is how the two are told apart, and a palette that already matches the
 * device: the app's own paper carries the buttons there, and a band would only show as a seam. The
 * keyboard, while it is up: it has the bar's room, and a band then is a stripe above it.
 *
 * Where it is drawn depends on the window. The activity's content reaches the screen's edge on the
 * bar's side, so the band is drawn on the edge of the box it is composed in, and so is the add
 * sheet's in portrait, whose caller has given it the bar's room. Turned sideways the sheet is a card
 * in the middle of a window that covers the whole screen to dim the app, so the bar under it belongs
 * to that window and is painted there instead, over the dim: [reachesTheScreenEdge] says that the
 * content does not reach the edge.
 *
 * The bar is read from the insets of the window this is composed in, so a phone turned sideways, and
 * a device switching between the handle and the buttons while the app is open, bring it back.
 *
 * Add it in a `Box` above the content: it covers what scrolls under a bar, as a system bar would,
 * and takes no touches of its own.
 *
 * @param reachesTheScreenEdge whether the content reaches the screen's edge on the bar's side.
 */
@Composable
fun BoxScope.NavigationBarBand(reachesTheScreenEdge: Boolean = true) {
  val deviceDark = isSystemInDarkTheme()
  if (MaterialTheme.colorScheme.surface.isDark() == deviceDark) return
  if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) return
  val insets = WindowInsets.navigationBars.asPaddingValues()
  val direction = LocalLayoutDirection.current
  val left = insets.calculateLeftPadding(direction)
  val right = insets.calculateRightPadding(direction)
  val bottom = insets.calculateBottomPadding()
  val bar = maxOf(bottom, right, left)
  if (bar < ButtonNavigationBarHeight) return
  val colour = if (deviceDark) DarkColorScheme.surfaceContainer else LightColorScheme.surfaceContainer

  // The platform lays a veil of white over the bar of any window that asks for its contrast scrim,
  // and a veil of that colour leaves the buttons short of contrast whatever the app paints under
  // them. The activity's window has had its scrim off since edge to edge; a dialog's window, which
  // the add sheet is, has not. Before API 29 there is no scrim to turn off, and the platform takes
  // the button colour from the window's own appearance there.
  val window = (LocalView.current.parent as? DialogWindowProvider)?.window
  val veil = remember(window) { if (window != null) ScrimlessBar(window) else null }
  SideEffect { veil?.apply() }
  DisposableEffect(window) { onDispose { veil?.restore() } }

  // The insets are absolute, so the band follows the edge they name rather than the edge the layout
  // reads: the bar keeps its own side however the phone is turned.
  val ltr = direction == LayoutDirection.Ltr
  val onTheBottom = bottom >= right && bottom >= left
  val onTheRight = right >= left
  val density = LocalDensity.current
  val width = with(density) { (if (onTheBottom) bottom else if (onTheRight) right else left).roundToPx() }
  if (!reachesTheScreenEdge) {
    // The content stops short of the bar, which is the add sheet turned sideways: its window covers
    // the whole screen to dim the app, so the bar under it belongs to that window. A plain view in
    // the window's decor is the only place a band can go on the bar's side there, above the dim,
    // because the sheet's own content is a card in the middle of the screen and anything drawn
    // outside that card is clipped away.
    BarEdge(window, colour, width, onTheBottom, onTheRight, ltr)
    return
  }
  val band =
    when {
      onTheBottom -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom)
      onTheRight -> Modifier.align(if (ltr) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().width(right)
      else -> Modifier.align(if (ltr) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().width(left)
    }
  Box(modifier = band.background(colour))
}

/**
 * The bar's own surface, painted by a view on top of the window's content: the last thing that
 * window draws, so it is over the sheet and over the dim the sheet puts on the app.
 *
 * The view goes in once per window and is never taken out again: the window owns its content view
 * and pulls a child out from under it while the window is detaching, which is a crash. After that it
 * is only painted, shown, hidden and measured, which is all a bar does when the phone is turned.
 */
@Composable
private fun BarEdge(
  window: Window?,
  colour: Color,
  width: Int,
  onTheBottom: Boolean,
  onTheRight: Boolean,
  ltr: Boolean,
) {
  val gravity =
    when {
      onTheBottom -> Gravity.BOTTOM
      onTheRight -> if (ltr) Gravity.END else Gravity.START
      else -> if (ltr) Gravity.START else Gravity.END
    }
  val band = remember { mutableStateOf<View?>(null) }
  DisposableEffect(window) {
    band.value = window?.let { bandOn(it) }
    onDispose { band.value?.visibility = View.GONE }
  }
  DisposableEffect(band.value, width, gravity, colour) {
    val view = band.value
    if (view == null || width <= 0) {
      onDispose {}
    } else {
      view.setBackgroundColor(colour.toArgb())
      view.layoutParams =
        FrameLayout.LayoutParams(
          if (onTheBottom) FrameLayout.LayoutParams.MATCH_PARENT else width,
          if (onTheBottom) width else FrameLayout.LayoutParams.MATCH_PARENT,
          gravity,
        )
      view.visibility = View.VISIBLE
      view.requestLayout()
      onDispose { view.visibility = View.GONE }
    }
  }
}

/** A view of the bar's own size and colour, on the last layer of the window's content. */
private fun bandOn(window: Window): View? {
  val content = window.findViewById(android.R.id.content) as? ViewGroup ?: return null
  val band = View(window.context)
  content.addView(band, FrameLayout.LayoutParams(0, 0))
  return band
}

/**
 * The navigation buttons take 48dp of bar, the gesture handle 24dp or less. A bar over 36dp is
 * read as buttons, which is the same call the platform makes for the 2-button layout.
 */
private val ButtonNavigationBarHeight = 36.dp

/**
 * One window's navigation bar contrast scrim, off, and whatever it was put back to on the way out.
 * A window sets its own scrim when it is laid out, so [apply] is safe to call again after that. There
 * is no scrim to turn off before API 29, where the platform takes the button colour from the
 * window's own appearance.
 */
private class ScrimlessBar(private val window: Window?) {
  private val enforced: Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && window?.isNavigationBarContrastEnforced == true) {
      window.isNavigationBarContrastEnforced = false
      true
    } else {
      false
    }

  fun apply() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && window?.isNavigationBarContrastEnforced == true) {
      window.isNavigationBarContrastEnforced = false
    }
  }

  fun restore() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && enforced && window?.isNavigationBarContrastEnforced == false) {
      window.isNavigationBarContrastEnforced = true
    }
  }
}

/** Whether a colour reads as dark, by the relative luminance the contrast rules use. */
private fun Color.isDark(): Boolean = luminance() < 0.5f
