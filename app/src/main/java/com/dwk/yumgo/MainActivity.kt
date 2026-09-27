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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dwk.yumgo.data.AppPreferencesRepository
import com.dwk.yumgo.data.SettingsServices
import com.dwk.yumgo.data.ThemeMode
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
      val darkTheme = themeMode.resolvesDark(isSystemInDarkTheme())
      YumgoTheme(darkTheme = darkTheme) {
        SystemBarsFollowTheme(darkTheme)
        val background by
          animateColorAsState(
            targetValue = MaterialTheme.colorScheme.background,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "app-background",
          )
        Surface(modifier = Modifier.fillMaxSize(), color = background) { MainNavigation() }
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
 * Both bars get the same style. Leaving the navigation bar on the default makes it follow the
 * device instead, which on 3-button navigation paints a grey scrim with pale icons under a
 * light app on a dark device.
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

private fun isSystemDark(activity: ComponentActivity): Boolean =
  (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private fun ThemeMode.resolvesDark(systemDark: Boolean): Boolean =
  when (this) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> systemDark
  }
