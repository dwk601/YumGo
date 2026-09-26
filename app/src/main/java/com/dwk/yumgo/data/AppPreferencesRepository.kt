package com.dwk.yumgo.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** How the app picks its palette. [Light] is the default, even on a dark device. */
enum class ThemeMode {
  Light,
  Dark,
  System,
}

/** App preferences that are not fridge data. */
interface AppPreferencesRepository {
  val themeMode: StateFlow<ThemeMode>

  /** Saves [mode] and publishes it, so the app repaints without a restart. */
  suspend fun setThemeMode(mode: ThemeMode): Result<Unit>
}

/** Preference write that did not reach storage. The previous selection stays in place. */
class SettingsStoreException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/** Theme selection in its own preference file, separate from the fridge database and its schema. */
internal class StoredAppPreferences(context: Context) : AppPreferencesRepository {
  private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
  private val state = MutableStateFlow(read())

  override val themeMode: StateFlow<ThemeMode> = state.asStateFlow()

  override suspend fun setThemeMode(mode: ThemeMode): Result<Unit> =
    try {
      val saved = withContext(Dispatchers.IO) { preferences.edit().putString(KEY_THEME_MODE, mode.name).commit() }
      if (saved) {
        state.value = mode
        Result.success(Unit)
      } else {
        Result.failure(SettingsStoreException("Couldn't save the theme"))
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      Result.failure(if (error is SettingsStoreException) error else SettingsStoreException(error.message ?: "Couldn't save the theme", error))
    }

  /** Restores the light default. Test-only. */
  fun clearForTests() {
    preferences.edit().clear().commit()
    state.value = ThemeMode.Light
  }

  private fun read(): ThemeMode {
    val stored = runCatching { preferences.getString(KEY_THEME_MODE, null) }.getOrNull()
    return stored?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.Light
  }

  companion object {
    /** Preference file the settings repositories own. Not the fridge database. */
    const val FILE_NAME = "yumgo_preferences"

    private const val KEY_THEME_MODE = "theme_mode"
  }
}
