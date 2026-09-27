@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.dwk.yumgo

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dwk.yumgo.data.AppPreferencesRepository
import com.dwk.yumgo.data.SettingsServices
import com.dwk.yumgo.data.ThemeMode
import com.dwk.yumgo.theme.DarkColorScheme
import com.dwk.yumgo.theme.LightColorScheme
import com.dwk.yumgo.theme.YumgoTheme

/**
 * Resolves the saved palette before the first frame, so a returning user gets the theme they
 * picked and a new one gets the light default. The choice is read again on every change, which
 * repaints the app and re-applies the system bars without a restart.
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val preferences: AppPreferencesRepository = SettingsServices.preferences(this)
    // The default styles follow the device, so a light app on a dark device would start with a
    // dark scrim and light icons on API 26-28. Ask for the saved choice instead.
    applySystemBars(this, preferences.themeMode.value.resolvesDark(isSystemDark(this)))

    setContent {
      val themeMode by preferences.themeMode.collectAsStateWithLifecycle()
      val deviceDark = isSystemInDarkTheme()
      val darkTheme = themeMode.resolvesDark(deviceDark)
      YumgoTheme(darkTheme = darkTheme) {
        SystemBarsFollowTheme(darkTheme)
        val background by
          animateColorAsState(
            targetValue = MaterialTheme.colorScheme.background,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "app-background",
          )
        Surface(modifier = Modifier.fillMaxSize(), color = background) {
          Box(modifier = Modifier.fillMaxSize()) {
            MainNavigation()
            ButtonBarScrim(darkTheme = darkTheme, deviceDark = deviceDark)
          }
        }
      }
    }
  }
}

/**
 * Re-applies the bars on every palette change, so the icons and the API 26-28 scrim follow the
 * app theme instead of the device. Re-applying is the same work the platform extension does in
 * `onCreate`, and it is idempotent.
 */
@Composable
private fun SystemBarsFollowTheme(darkTheme: Boolean) {
  val activity = LocalActivity.current as? ComponentActivity ?: return
  SideEffect { applySystemBars(activity, darkTheme) }
}

/**
 * The surface under the platform's navigation buttons, drawn only when the app's own paper cannot
 * carry them.
 *
 * With the gesture handle the platform draws a pill and takes its colour from this window, so the
 * bar stays the app's own paper. The buttons are drawn by the platform instead, and it takes
 * their colour from the *device*: on API 35 a light app on a dark device got pale icons (#C5C6D0)
 * on its own light paper at 1.2:1, and a dark app on a light device got dark ones (#44464F) on its
 * dark paper at 2.3:1. Neither is usable, and the app cannot change the icons: the window
 * appearance is ignored, so is the uiMode the activity reports, which is what the platform reads,
 * and `window.navigationBarColor` is a no-op from API 35. The bar is the one thing left, so it
 * takes the polarity the device's icons need, and an app that already matches the device keeps a
 * clear bar, so no seam ever shows.
 *
 * The bar's insets are read here, so switching the device between buttons and the handle while the
 * app is open brings this back, and so does turning the phone, which moves the bar from the bottom
 * to a side.
 */
@Composable
private fun BoxScope.ButtonBarScrim(darkTheme: Boolean, deviceDark: Boolean) {
  if (deviceDark == darkTheme) return
  val insets = WindowInsets.navigationBars.asPaddingValues()
  val direction = LocalLayoutDirection.current
  val ltr = direction == LayoutDirection.Ltr
  val left = insets.calculateLeftPadding(direction)
  val right = insets.calculateRightPadding(direction)
  val bottom = insets.calculateBottomPadding()
  if (maxOf(bottom, right, left) < ButtonNavigationBarHeight) return
  // The insets are absolute, so the placement follows the edge they name rather than the edge the
  // layout reads: the bar keeps its own side however the phone is turned.
  val bar =
    when {
      bottom >= right && bottom >= left -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom)
      right >= left -> Modifier.align(if (ltr) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().width(right)
      else -> Modifier.align(if (ltr) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().width(left)
    }
  Box(
    modifier =
      bar.background(
        if (deviceDark) DarkColorScheme.surfaceContainerLowest else LightColorScheme.surfaceContainerLowest,
      ),
  )
}

/**
 * Both bars get the same style. Leaving the navigation bar's colour to the window leaves it with
 * the device, which is what [ButtonBarScrim] corrects; the default styles are also what the
 * platform would use on API 26-28, where a light app on a dark device would start with a dark scrim
 * and light icons, so the saved choice is asked for instead.
 */
private fun applySystemBars(activity: ComponentActivity, darkTheme: Boolean) {
  val style = systemBarStyle(darkTheme)
  activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

/** A light palette wants dark icons on a clear bar; a dark palette wants the opposite. */
private fun systemBarStyle(darkTheme: Boolean): SystemBarStyle =
  if (darkTheme) {
    SystemBarStyle.dark(Color.TRANSPARENT)
  } else {
    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
  }

/**
 * The navigation buttons take 48dp of bar, the gesture handle 24dp or less. A bar over 36dp is
 * read as buttons, which is the same call the platform makes for the 2-button layout.
 */
private val ButtonNavigationBarHeight = 36.dp

private fun isSystemDark(activity: ComponentActivity): Boolean =
  (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private fun ThemeMode.resolvesDark(systemDark: Boolean): Boolean =
  when (this) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> systemDark
  }
