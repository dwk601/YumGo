@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.dwk.yumgo.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dwk.yumgo.R
import com.dwk.yumgo.data.FoodPreset
import com.dwk.yumgo.data.SettingsServices
import com.dwk.yumgo.data.ThemeMode
import com.dwk.yumgo.theme.YumgoTheme

/**
 * Settings for premade foods and the palette. Nothing else lives here.
 *
 * Owns status, cutout, navigation, and keyboard insets; expects no ancestor safe-drawing padding.
 * Wiring is manual: one [SettingsViewModel] over the process-wide repositories from
 * [SettingsServices], so this screen and the fridge never disagree about the theme.
 */
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current.applicationContext
  val viewModel: SettingsViewModel =
    viewModel {
      SettingsViewModel(
        presetRepository = SettingsServices.presets(context),
        appPreferences = SettingsServices.preferences(context),
      )
    }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  SettingsContent(
    state = state,
    callbacks =
      SettingsCallbacks(
        onBack = onBack,
        onThemeModeChange = viewModel::onThemeModeChange,
        onEditPreset = viewModel::onEditPreset,
        onPresetNameChange = viewModel::onPresetNameChange,
        onPresetDaysChange = viewModel::onPresetDaysChange,
        onSavePreset = viewModel::onSavePreset,
        onCancelPreset = viewModel::onCancelPreset,
        onDismissThemeError = viewModel::onDismissThemeError,
      ),
    modifier = modifier,
  )
}

@Composable
private fun SettingsContent(
  state: SettingsUiState,
  callbacks: SettingsCallbacks,
  modifier: Modifier = Modifier,
) {
  val layoutDirection = LocalLayoutDirection.current
  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
          IconButton(onClick = callbacks.onBack) {
            Icon(SettingsBack, contentDescription = stringResource(R.string.settings_back))
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
      )
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
      contentPadding =
        PaddingValues(
          start = innerPadding.calculateStartPadding(layoutDirection) + 20.dp,
          end = innerPadding.calculateEndPadding(layoutDirection) + 20.dp,
          top = innerPadding.calculateTopPadding() + 4.dp,
          bottom = innerPadding.calculateBottomPadding() + 32.dp,
        ),
    ) {
      item(key = "appearance-header", contentType = "header") { SectionTitle(stringResource(R.string.settings_appearance_title)) }
      item(key = "appearance-body", contentType = "body") { SectionBody(stringResource(R.string.settings_appearance_body)) }
      item(key = "appearance-options", contentType = "options") {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
          ThemeChoice.entries.forEach { choice ->
            ThemeRow(
              choice = choice,
              selected = state.themeMode == choice.mode,
              onSelect = { callbacks.onThemeModeChange(choice.mode) },
            )
          }
        }
      }
      state.themeError?.let { themeError ->
        item(key = "theme-error", contentType = "error") {
          ThemeErrorBanner(text = stringResource(themeError), onDismiss = callbacks.onDismissThemeError)
        }
      }

      item(key = "presets-header", contentType = "header") { SectionTitle(stringResource(R.string.settings_presets_title)) }
      item(key = "presets-body", contentType = "body") { SectionBody(stringResource(R.string.settings_presets_body)) }
      items(count = state.presets.size, key = { index -> state.presets[index].id }, contentType = { "preset" }) { index ->
        val preset = state.presets[index]
        PresetCard(
          preset = preset,
          edit = state.editing?.takeIf { it.id == preset.id },
          saved = state.savedId == preset.id,
          callbacks = callbacks,
        )
      }
      item(key = "presets-note", contentType = "body") { SectionBody(stringResource(R.string.settings_presets_note)) }
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 20.dp, bottom = 2.dp).semantics { heading() },
  )
}

@Composable
private fun SectionBody(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = 12.dp),
  )
}

/** One palette choice. The selected card fills in, which is the only feedback a tap needs. */
@Composable
private fun ThemeRow(choice: ThemeChoice, selected: Boolean, onSelect: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val container by
    animateColorAsState(
      targetValue = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLowest,
      animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
      label = "theme-container",
    )
  val content by
    animateColorAsState(
      targetValue = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
      animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
      label = "theme-content",
    )
  Surface(
    modifier =
      Modifier.fillMaxWidth().padding(bottom = 10.dp).selectable(
        selected = selected,
        role = Role.RadioButton,
        onClick = onSelect,
      ),
    shape = MaterialTheme.shapes.large,
    color = container,
    contentColor = content,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).heightIn(min = 56.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      RadioButton(selected = selected, onClick = null)
      Column(Modifier.weight(1f).padding(start = 12.dp)) {
        Text(text = stringResource(choice.label), style = MaterialTheme.typography.titleMedium)
        Text(
          text = stringResource(choice.body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
  }
}

@Composable
private fun PresetCard(preset: FoodPreset, edit: PresetEdit?, saved: Boolean, callbacks: SettingsCallbacks) {
  Surface(
    modifier =
      Modifier.fillMaxWidth().padding(bottom = 10.dp).animateContentSize(
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
      ),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
      Row(
        modifier =
          Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(
            enabled = edit == null,
            onClickLabel = stringResource(R.string.settings_edit_preset, preset.name),
            onClick = { callbacks.onEditPreset(preset) },
          ),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(text = preset.name, style = MaterialTheme.typography.titleMedium)
          Text(
            text = pluralStringResource(R.plurals.settings_preset_days, preset.expiryDays, preset.expiryDays),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
        AnimatedVisibility(
          visible = saved,
          enter = fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
          exit = fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
          Icon(
            imageVector = SettingsCheck,
            contentDescription = stringResource(R.string.settings_preset_saved),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp).size(20.dp),
          )
        }
        Icon(
          imageVector = SettingsNext,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 8.dp).size(20.dp),
        )
      }
      AnimatedVisibility(
        visible = edit != null,
        enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
        exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
      ) {
        if (edit != null) PresetEditFields(edit = edit, callbacks = callbacks)
      }
    }
  }
}

@Composable
private fun PresetEditFields(edit: PresetEdit, callbacks: SettingsCallbacks) {
  val saveFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
  Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
    OutlinedTextField(
      value = edit.name,
      onValueChange = callbacks.onPresetNameChange,
      modifier = Modifier.fillMaxWidth(),
      enabled = !edit.saving,
      singleLine = true,
      isError = edit.error == PresetEditError.BlankName,
      label = { Text(stringResource(R.string.settings_preset_name_label)) },
      supportingText =
        if (edit.error == PresetEditError.BlankName) {
          { Text(stringResource(R.string.settings_error_name)) }
        } else {
          null
        },
      shape = MaterialTheme.shapes.medium,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    OutlinedTextField(
      value = edit.days,
      onValueChange = callbacks.onPresetDaysChange,
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      enabled = !edit.saving,
      singleLine = true,
      isError = edit.error == PresetEditError.DaysOutOfRange,
      label = { Text(stringResource(R.string.settings_preset_days_label)) },
      supportingText =
        if (edit.error == PresetEditError.DaysOutOfRange) {
          { Text(stringResource(R.string.settings_error_days)) }
        } else {
          { Text(stringResource(R.string.settings_preset_days_hint)) }
        },
      shape = MaterialTheme.shapes.medium,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { callbacks.onSavePreset() }),
    )
    if (edit.error == PresetEditError.SaveFailed) {
      // The editor stays open on failure, so the reason shows where the tap happened.
      Text(
        text = stringResource(R.string.settings_error_save),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = callbacks.onCancelPreset, enabled = !edit.saving) { Text(stringResource(R.string.settings_cancel)) }
      Button(onClick = callbacks.onSavePreset, enabled = !edit.saving, modifier = Modifier.heightIn(min = 48.dp)) {
        AnimatedContent(
          targetState = edit.saving,
          transitionSpec = { fadeIn(saveFade) togetherWith fadeOut(saveFade) },
          label = "preset-save",
        ) { saving ->
          Text(stringResource(if (saving) R.string.settings_saving else R.string.settings_save))
        }
      }
    }
  }
}

/** Reports a palette write that failed, right under the palette that did not save. */
@Composable
private fun ThemeErrorBanner(text: String, onDismiss: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
  ) {
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dismiss)) }
    }
  }
}

/** Palette options shown in order. Light is first because it is the default. */
private enum class ThemeChoice(val mode: ThemeMode, val label: Int, val body: Int) {
  Light(ThemeMode.Light, R.string.settings_theme_light, R.string.settings_theme_light_body),
  Dark(ThemeMode.Dark, R.string.settings_theme_dark, R.string.settings_theme_dark_body),
  System(ThemeMode.System, R.string.settings_theme_system, R.string.settings_theme_system_body),
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SettingsPreview() {
  YumgoTheme {
    SettingsContent(
      state =
        SettingsUiState(
          presets = listOf(FoodPreset("milk", "Milk", 7), FoodPreset("chicken", "Chicken", 3), FoodPreset("bread", "Sourdough loaf", 5)),
          themeMode = ThemeMode.Light,
          editing = PresetEdit(id = "milk", name = "Milk", days = "9", error = PresetEditError.DaysOutOfRange),
          savedId = "chicken",
          themeError = R.string.settings_error_theme,
        ),
      callbacks = IdleSettingsCallbacks,
    )
  }
}

private val IdleSettingsCallbacks =
  SettingsCallbacks(
    onBack = {},
    onThemeModeChange = {},
    onEditPreset = {},
    onPresetNameChange = {},
    onPresetDaysChange = {},
    onSavePreset = {},
    onCancelPreset = {},
    onDismissThemeError = {},
  )

private val SettingsBack: ImageVector by lazy { settingsIcon("ArrowBack", autoMirror = true) { arrowBack() } }
private val SettingsCheck: ImageVector by lazy { settingsIcon("Check") { check() } }
private val SettingsNext: ImageVector by lazy { settingsIcon("Next") { next() } }

private fun settingsIcon(name: String, autoMirror: Boolean = false, draw: PathBuilder.() -> Unit): ImageVector =
  ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
  ).apply { path(fill = SolidColor(Color.Black), pathBuilder = draw) }
    .build()

private fun PathBuilder.arrowBack() {
  moveTo(20f, 11f)
  horizontalLineTo(7.83f)
  lineTo(13.41f, 5.41f)
  lineTo(12f, 4f)
  lineTo(4f, 12f)
  lineTo(12f, 20f)
  lineTo(13.41f, 18.59f)
  lineTo(7.83f, 13f)
  horizontalLineTo(20f)
  close()
}

private fun PathBuilder.check() {
  moveTo(9f, 16.17f)
  lineTo(4.83f, 12f)
  lineTo(3.41f, 13.41f)
  lineTo(9f, 19f)
  lineTo(21f, 7f)
  lineTo(19.59f, 5.59f)
  close()
}

private fun PathBuilder.next() {
  moveTo(10.02f, 6f)
  lineTo(8.61f, 7.41f)
  lineTo(13.19f, 12f)
  lineTo(8.61f, 16.59f)
  lineTo(10.02f, 18f)
  lineTo(16.02f, 12f)
  close()
}
