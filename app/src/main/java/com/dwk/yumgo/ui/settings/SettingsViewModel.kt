package com.dwk.yumgo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dwk.yumgo.data.AppPreferencesRepository
import com.dwk.yumgo.data.FoodPreset
import com.dwk.yumgo.data.MaxPresetExpiryDays
import com.dwk.yumgo.data.MaxPresetNameLength
import com.dwk.yumgo.data.PresetRepository
import com.dwk.yumgo.data.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings for the two things a user can change: the premade food list and the palette.
 *
 * Both repositories are the process's single instances, created with the first ViewModel and
 * kept across rotation, so an edit in settings and a change made here never disagree.
 * Preset edits touch the preset list only; fridge items keep whatever expiry they were saved with.
 */
class SettingsViewModel(
  private val presetRepository: PresetRepository,
  private val appPreferences: AppPreferencesRepository,
) : ViewModel() {
  private val editing = MutableStateFlow<PresetEdit?>(null)
  private val savedId = MutableStateFlow<String?>(null)
  private val message = MutableStateFlow<String?>(null)
  private var savedFeedback: Job? = null

  val uiState: StateFlow<SettingsUiState> =
    combine(presetRepository.presets, appPreferences.themeMode, editing, savedId, message) { presets, themeMode, edit, saved, notice ->
      SettingsUiState(presets = presets, themeMode = themeMode, editing = edit, savedId = saved, message = notice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

  fun onThemeModeChange(mode: ThemeMode) {
    if (appPreferences.themeMode.value == mode) return
    message.value = null
    viewModelScope.launch {
      appPreferences.setThemeMode(mode).onFailure { error ->
        message.value = error.message ?: "Couldn't save the theme"
      }
    }
  }

  fun onEditPreset(preset: FoodPreset) {
    if (editing.value?.saving == true) return
    if (editing.value?.id == preset.id) return
    message.value = null
    editing.value = PresetEdit(id = preset.id, name = preset.name, days = preset.expiryDays.toString())
  }

  fun onPresetNameChange(value: String) {
    val current = editing.value ?: return
    if (current.saving) return
    editing.value = current.copy(name = value.take(MaxPresetNameLength), error = null)
  }

  fun onPresetDaysChange(value: String) {
    val current = editing.value ?: return
    if (current.saving) return
    val digits = value.filter(Char::isDigit).take(MaxDaysDigits)
    editing.value = current.copy(days = digits, error = null)
  }

  fun onCancelPreset() {
    if (editing.value?.saving == true) return
    editing.value = null
  }

  fun onSavePreset() {
    val current = editing.value ?: return
    if (current.saving) return
    val name = current.name.trim()
    if (name.isEmpty()) {
      editing.value = current.copy(error = PresetEditError.BlankName)
      return
    }
    val days = current.days.toIntOrNull()
    if (days == null || days !in 0..MaxPresetExpiryDays) {
      editing.value = current.copy(error = PresetEditError.DaysOutOfRange)
      return
    }
    editing.value = current.copy(saving = true, error = null)
    viewModelScope.launch {
      presetRepository.update(FoodPreset(id = current.id, name = name, expiryDays = days)).fold(
        onSuccess = {
          editing.value = null
          showSaved(current.id)
        },
        onFailure = { error ->
          editing.value = editing.value?.copy(saving = false)
          message.value = error.message ?: "Couldn't save the preset"
        },
      )
    }
  }

  fun onDismissMessage() {
    message.value = null
  }

  private fun showSaved(id: String) {
    savedFeedback?.cancel()
    savedId.value = id
    savedFeedback =
      viewModelScope.launch {
        delay(SavedFeedbackMillis)
        savedId.value = null
      }
  }

  private fun initialState(): SettingsUiState =
    SettingsUiState(
      presets = presetRepository.presets.value,
      themeMode = appPreferences.themeMode.value,
      editing = editing.value,
    )

  private companion object {
    const val SavedFeedbackMillis = 1_600L
    const val MaxDaysDigits = 4
  }
}

data class SettingsUiState(
  val presets: List<FoodPreset> = emptyList(),
  val themeMode: ThemeMode = ThemeMode.Light,
  /** Open editor, or null when no preset is being edited. */
  val editing: PresetEdit? = null,
  /** Preset that just saved, so the row can confirm it. */
  val savedId: String? = null,
  /** Message for a write that failed, e.g. the theme could not be stored. */
  val message: String? = null,
)

/** Editor draft. [days] is text so the field stays editable while the user types. */
data class PresetEdit(
  val id: String,
  val name: String,
  val days: String,
  val error: PresetEditError? = null,
  val saving: Boolean = false,
)

enum class PresetEditError {
  BlankName,
  DaysOutOfRange,
}
