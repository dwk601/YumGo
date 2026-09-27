package com.dwk.yumgo

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.dwk.yumgo.ui.main.MainScreen
import com.dwk.yumgo.ui.settings.SettingsScreen

/**
 * Two destinations: the fridge and settings. Settings is pushed on top of the fridge, so Back
 * pops that entry and the fridge keeps its items, query, and open draft.
 *
 * Motion comes from the theme's motion scheme through [NavDisplay]. Back is Navigation 3's own,
 * so its predictive-back gesture keeps working.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)
  val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
  val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider = entryProvider {
      entry<Main> {
        MainScreen(
          modifier = Modifier.fillMaxSize(),
          onOpenSettings = { backStack.add(Settings) },
        )
      }
      entry<Settings> {
        SettingsScreen(
          onBack = { backStack.removeLastOrNull() },
          modifier = Modifier.fillMaxSize(),
        )
      }
    },
    transitionSpec = { panTowards(back = targetState.key == Settings, spatial = spatial, effects = effects) },
    popTransitionSpec = { panTowards(back = targetState.key == Main, spatial = spatial, effects = effects) },
  )
}

/** Push slides in from the end, pop slides back from the start. */
private fun AnimatedContentTransitionScope<*>.panTowards(
  back: Boolean,
  spatial: FiniteAnimationSpec<IntOffset>,
  effects: FiniteAnimationSpec<Float>,
): ContentTransform {
  val direction = if (back) SlideDirection.Right else SlideDirection.Left
  val enter = slideIntoContainer(direction, animationSpec = spatial) + fadeIn(effects)
  val exit = slideOutOfContainer(direction, animationSpec = spatial) + fadeOut(effects)
  return enter togetherWith exit
}
