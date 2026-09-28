package com.dwk.yumgo.ui.settings

import com.dwk.yumgo.data.FoodPreset
import com.dwk.yumgo.data.ThemeMode

/** What the settings screen can ask for. One instance per screen, built from the ViewModel. */
data class SettingsCallbacks(
  val onBack: () -> Unit,
  val onThemeModeChange: (ThemeMode) -> Unit,
  val onEditPreset: (FoodPreset) -> Unit,
  val onPresetNameChange: (String) -> Unit,
  val onPresetDaysChange: (String) -> Unit,
  val onSavePreset: () -> Unit,
  val onCancelPreset: () -> Unit,
  val onDismissThemeError: () -> Unit,
)
