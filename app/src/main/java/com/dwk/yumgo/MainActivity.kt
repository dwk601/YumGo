@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.dwk.yumgo

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dwk.yumgo.data.AppPreferencesRepository
import com.dwk.yumgo.data.SettingsServices
import com.dwk.yumgo.data.ThemeMode
import com.dwk.yumgo.theme.YumgoTheme

/**
 * Resolves the saved palette before the first frame, so a returning user gets the theme they
 * picked and a new one gets the light default. The choice is read again on every change, which
 * repaints the app and keeps the system-bar icons legible without a restart.
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      val context = LocalContext.current.applicationContext
      val preferences: AppPreferencesRepository = remember(context) { SettingsServices.preferences(context) }
      val themeMode by preferences.themeMode.collectAsStateWithLifecycle()
      val darkTheme = themeMode.resolvesDark(isSystemInDarkTheme())
      YumgoTheme(darkTheme = darkTheme) {
        LegibleSystemBars(darkTheme = darkTheme)
        val background by
          animateColorAsState(
            targetValue = MaterialTheme.colorScheme.background,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "app-background",
          )
        Surface(modifier = Modifier.fillMaxSize(), color = background) { MainNavigation() }
      }
    }
  }
}

/** Light palette asks for dark icons, dark palette for light icons. Runs on every change. */
@Composable
private fun LegibleSystemBars(darkTheme: Boolean) {
  val window = LocalActivity.current?.window ?: return
  val view = LocalView.current
  SideEffect {
    WindowCompat.getInsetsController(window, view).apply {
      isAppearanceLightStatusBars = !darkTheme
      isAppearanceLightNavigationBars = !darkTheme
    }
  }
}

private fun ThemeMode.resolvesDark(systemDark: Boolean): Boolean =
  when (this) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> systemDark
  }
