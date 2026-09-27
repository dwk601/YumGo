@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.roundToInt
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
  val editor = remember { EditorBringIntoView() }
  // The room the editor has to fit in, read from the list itself. The keyboard animates its inset
  // over several frames, and a reveal that runs while that is still moving is measured against a
  // room the keyboard is about to take away, which in landscape left Save under the keyboard.
  // Watching the room means the last reveal is the one that counts.
  val room = remember { mutableIntStateOf(0) }
  val listState = rememberLazyListState()
  // The room the list leaves at the bottom: the keyboard and the navigation bar, whichever is
  // taller, so the list's bottom edge is somewhere a person can see and a finger can reach. The
  // Scaffold hands out the safe drawing insets, so this is the one place the navigation bar is asked
  // for again, and it is asked for before those insets are consumed.
  val bottomRoom = WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
  val shortWindow = LocalConfiguration.current.screenHeightDp <= ShortWindowHeightDp

  // Bring the open editor into the room the keyboard leaves. Runs when a field takes focus and
  // whenever that room changes, all after composition, so the requesters are attached before they
  // are used. The reveal that belongs to opening the editor waits for the card to stop growing,
  // in [PresetCard], because the card is not laid out yet at this point.
  LaunchedEffect(editor.focusedField, room.intValue) {
    if (state.editing == null) return@LaunchedEffect
    // The room is read from the list as it was laid out, so a reveal taken in the same frame is
    // measured against the room before this one. A keyboard moving at the end of its animation
    // changes the room by about ten pixels a frame, and in landscape that is the difference
    // between the Save button clear of the keyboard and ten pixels of it under: the last reveal
    // has to be the one that waits for the frame the new room is measured in.
    awaitFrames()
    editor.reveal()
  }

  // Nothing is in focus once the editor is closed, so the next one starts on its name field.
  LaunchedEffect(state.editing?.id) {
    if (state.editing == null) editor.focusedField = null
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
    // The keyboard is handled by the list below, not here, so the room is never counted twice.
    contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
    topBar = {
      val title = stringResource(R.string.settings_title)
      val back = stringResource(R.string.settings_back)
      TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
          IconButton(onClick = callbacks.onBack) {
            Icon(SettingsBack, contentDescription = back)
          }
        },
        // The stock bar, in less of it. A phone on its side gives the keyboard half the window,
        // and a 64dp title bar is a fifth of what is left, which is the difference between the
        // field being typed into and the button that saves it fitting together above the keyboard
        // and Save sitting under it. The bar still carries its own insets and its 48dp back
        // target, which is what makes the control a finger can reach with a bar on either side.
        expandedHeight = if (shortWindow) CompactBarHeight else TopAppBarDefaults.TopAppBarExpandedHeight,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
      )
    },
  ) { innerPadding ->
    LazyColumn(
      state = listState,
      // The room the list leaves at the bottom, and its two edges, are padding on the list rather
      // than content padding, so the scrollable viewport is the room a person can see. At the top
      // that means below the app bar: scrolling a card to the edge of a viewport that reaches up
      // under the bar parks it behind the bar, which is what a person saw in landscape, where the
      // field being typed into disappeared under the title. At the bottom it means above the
      // navigation bar as well as above the keyboard, whichever is taller: the reveal scrolls Save
      // to the bottom edge of the viewport, and an edge at the bottom of the screen is a Save
      // button under the navigation bar, which a tap there cannot reach.
      modifier =
        Modifier
          .fillMaxSize()
          .padding(top = innerPadding.calculateTopPadding())
          .windowInsetsPadding(bottomRoom)
          // Last, because consuming the Scaffold's insets would take the navigation bar away from
          // the padding above it, and the rest of the room has to stop here anyway.
          .consumeWindowInsets(innerPadding)
          // In innermost, so it reads the size inside the padding: the room, not the window.
          .onSizeChanged { room.intValue = it.height },
      contentPadding =
        PaddingValues(
          start = innerPadding.calculateStartPadding(layoutDirection) + 20.dp,
          end = innerPadding.calculateEndPadding(layoutDirection) + 20.dp,
          top = 4.dp,
          // Room for the open card to travel into view. A list cannot scroll past its own end, so
          // a card at the end of the list could not be brought up at all: the reveal asked for the
          // scroll and the list said no. The extra room is the card's own height, which is as much
          // as a reveal can ever need, and it goes when the editor closes.
          bottom = 32.dp + editor.openCardHeight.dp,
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
          editor = editor,
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
private fun PresetCard(
  preset: FoodPreset,
  edit: PresetEdit?,
  saved: Boolean,
  editor: EditorBringIntoView,
  callbacks: SettingsCallbacks,
) {
  // The card is still growing while the editor expands, and a list asked to scroll to a card that
  // is still growing scrolls to where the card is going to be, not to where it is. That is how the
  // editor of a preset near the bottom of the list used to open with its fields and Save below the
  // bottom of the screen. Wait for the card to hold its size across a few frames, give the list the
  // room to bring the card up, and only then ask.
  val cardHeight = remember { mutableIntStateOf(0) }
  val density = LocalDensity.current.density
  LaunchedEffect(edit?.id) {
    if (edit == null) {
      editor.openCardHeight = 0
      return@LaunchedEffect
    }
    awaitStillSize { cardHeight.intValue }
    // In dp, because that is what the content padding is written in.
    editor.openCardHeight = (cardHeight.intValue / density).roundToInt()
    // The room just given to the list reaches its layout on the next frame, and a reveal measured
    // before then asks for a scroll the list cannot make yet.
    awaitFrames()
    editor.reveal()
  }
  Surface(
    modifier =
      Modifier.fillMaxWidth().padding(bottom = 10.dp).animateContentSize(
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
      ).onSizeChanged { cardHeight.intValue = it.height },
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
        if (edit != null) PresetEditFields(edit = edit, editor = editor, callbacks = callbacks)
      }
    }
  }
}

@Composable
private fun PresetEditFields(edit: PresetEdit, editor: EditorBringIntoView, callbacks: SettingsCallbacks) {
  val saveFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
  // A phone on its side with the keyboard up has room for the field being typed into and the
  // button that saves it, and not for the gaps around them and the hint under the days field as
  // well. The hint explains the number before it is typed, and the gaps are only spacing, so both
  // are the first things to go when keeping them would push Save off the screen. An error is never
  // dropped: it is the reason the edit did not go through.
  val shortWindow = LocalConfiguration.current.screenHeightDp <= ShortWindowHeightDp
  val gap = if (shortWindow) 0.dp else 8.dp
  Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
    OutlinedTextField(
      value = edit.name,
      onValueChange = callbacks.onPresetNameChange,
      modifier =
        Modifier.fillMaxWidth().bringIntoViewWhen(editor.requestFor(PresetField.Name))
          .onFocusChanged { focus ->
            if (focus.isFocused) editor.focusedField = PresetField.Name
          },
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
      modifier =
        Modifier.fillMaxWidth().padding(top = gap).bringIntoViewWhen(editor.requestFor(PresetField.Days))
          .onFocusChanged { focus ->
            if (focus.isFocused) editor.focusedField = PresetField.Days
          },
      enabled = !edit.saving,
      singleLine = true,
      isError = edit.error == PresetEditError.DaysOutOfRange,
      label = { Text(stringResource(R.string.settings_preset_days_label)) },
      supportingText =
        when {
          edit.error == PresetEditError.DaysOutOfRange -> ({ Text(stringResource(R.string.settings_error_days)) })
          shortWindow -> null
          else -> ({ Text(stringResource(R.string.settings_preset_days_hint)) })
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
      modifier = Modifier.fillMaxWidth().padding(top = gap).bringIntoViewRequester(editor.actions),
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

/** Which editor field the user is in. */
private enum class PresetField { Name, Days }

/**
 * Bring-into-view handles for the open preset editor.
 *
 * The Save row and the field the user is in each get a requester while the editor is open, and
 * [reveal] asks the list to scroll to them: the actions row first, then the field, so that when
 * the card is taller than the room left, the field is what ends up on screen and Save stays above
 * the keyboard as long as the two of them fit there together. With no field in focus, the field is
 * the name field, which is where a person starts.
 */
@Stable
private class EditorBringIntoView {
  var focusedField: PresetField? by mutableStateOf(null)

  /** The open card's height, which the list is given as room to scroll it into view. */
  var openCardHeight: Int by mutableIntStateOf(0)

  private val fieldRequest = BringIntoViewRequester()
  private val actionsRequest = BringIntoViewRequester()

  /** The Save row's requester, attached by the editor's actions row. */
  val actions: BringIntoViewRequester get() = actionsRequest

  /** A field's requester, attached only to the field a person is in. */
  fun requestFor(field: PresetField): BringIntoViewRequester? =
    if (field == (focusedField ?: PresetField.Name)) fieldRequest else null

  suspend fun reveal() {
    actionsRequest.bringIntoView()
    fieldRequest.bringIntoView()
  }
}

/**
 * Waits until [read] has held the same non-zero size across [StillFrames] frames, which is how a
 * growing card settles. Reading once is not enough: the first frames after the editor opens still
 * show the card at its closed size, and scrolling to that is the bug this waits out. The wait gives
 * up after [SettleFrames] so that a card which never settles is still revealed, measured against
 * nearly the layout it is going to have.
 */
private suspend fun awaitStillSize(read: () -> Int) {
  var previous = 0
  var still = 0
  var frames = 0
  while (still < StillFrames && frames < SettleFrames) {
    withFrameNanos { }
    val next = read()
    still = if (next > 0 && next == previous) still + 1 else 0
    previous = next
    frames++
  }
}

/** How many frames a growing card has to hold its size before it is measured as settled. */
private const val StillFrames = 3

/** How long to wait for a card to settle, in frames, before revealing it anyway. */
private const val SettleFrames = 120

/** Waits for the frames a change made to the composition to reach the layout it changes. */
private suspend fun awaitFrames(frames: Int = 3) {
  repeat(frames) { withFrameNanos { } }
}

/**
 * The compact-height boundary, the same 480dp the Material window size classes use. Below it the
 * window is a phone on its side, where the editor tightens itself and the title bar is a compact
 * one to keep Save on the screen.
 */
private const val ShortWindowHeightDp = 480

/** How tall the title bar's content is in a short window, where a fifth of the screen cannot be a bar. */
private val CompactBarHeight = 48.dp

/** Attaches a requester when there is one, so an unfocused field is left alone. */
private fun Modifier.bringIntoViewWhen(request: BringIntoViewRequester?): Modifier =
  if (request != null) bringIntoViewRequester(request) else this
