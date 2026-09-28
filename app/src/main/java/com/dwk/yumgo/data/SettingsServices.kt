package com.dwk.yumgo.data

import android.content.Context

/**
 * Process-wide settings services. Both repositories keep the application context and are created
 * once, so the settings screen and the fridge screen read the same presets and the same theme
 * without passing them through navigation.
 */
object SettingsServices {
  @Volatile private var presetRepository: PresetRepository? = null
  @Volatile private var appPreferences: AppPreferencesRepository? = null

  fun presets(context: Context): PresetRepository =
    presetRepository ?: synchronized(this) {
      presetRepository ?: StoredPresetRepository(context.applicationContext).also { presetRepository = it }
    }

  fun preferences(context: Context): AppPreferencesRepository =
    appPreferences ?: synchronized(this) {
      appPreferences ?: StoredAppPreferences(context.applicationContext).also { appPreferences = it }
    }

  /**
   * Clears saved presets and the theme selection, then keeps serving the same instances so
   * open screens see the reset values. Test-only.
   */
  fun resetForTests() {
    synchronized(this) {
      (presetRepository as? StoredPresetRepository)?.clearForTests()
      (appPreferences as? StoredAppPreferences)?.clearForTests()
    }
  }
}
