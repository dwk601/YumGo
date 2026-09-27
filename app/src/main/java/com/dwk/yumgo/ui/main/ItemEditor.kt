@file:OptIn(
  ExperimentalLayoutApi::class,
  ExperimentalMaterial3Api::class,
  ExperimentalMaterial3ExpressiveApi::class,
)

package com.dwk.yumgo.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import android.view.ViewTreeObserver
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dwk.yumgo.R
import com.dwk.yumgo.data.FoodPreset
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * In-place add/edit sheet. The name field takes focus. Save stays pinned above
 * the keyboard. Photo buttons only report intent; the caller stages the file
 * and writes it back onto [draft].
 */
@Composable
fun ItemEditor(
  draft: ItemDraft,
  callbacks: FridgeCallbacks,
  presets: List<FoodPreset> = emptyList(),
  restored: Boolean = false,
  backEnabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  if (restored) {
    RestoredEditor(draft = draft, presets = presets, callbacks = callbacks, backEnabled = backEnabled, modifier = modifier)
  } else {
    ModalBottomSheet(
      onDismissRequest = { if (!draft.saving) callbacks.onDismissEditor() },
      modifier = modifier,
      sheetState = sheetState,
      sheetGesturesEnabled = !draft.saving,
      shape = MaterialTheme.shapes.extraLarge,
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
      EditorBody(
        draft = draft,
        callbacks = callbacks,
        presets = presets,
        requestFocus = draft.id == null,
        sheetState = sheetState,
      )
    }
  }
}

@Composable
private fun RestoredEditor(
  draft: ItemDraft,
  presets: List<FoodPreset>,
  callbacks: FridgeCallbacks,
  backEnabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val focusManager = LocalFocusManager.current
  LaunchedEffect(Unit) { focusManager.clearFocus(force = true) }
  val navigationState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
  NavigationBackHandler(
    state = navigationState,
    isBackEnabled = backEnabled && !draft.saving,
    onBackCompleted = { callbacks.onDismissEditor() },
  )
  Box(modifier.fillMaxSize()) {
    Box(
      Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)).clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = { if (!draft.saving) callbacks.onDismissEditor() },
      ),
    )
    Surface(
      modifier =
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(
          WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        ),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
      Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
        EditorBody(draft = draft, callbacks = callbacks, presets = presets, requestFocus = false, sheetState = null)
      }
    }
  }
}

@Composable
private fun EditorBody(
  draft: ItemDraft,
  callbacks: FridgeCallbacks,
  presets: List<FoodPreset>,
  requestFocus: Boolean,
  sheetState: SheetState? = null,
) {
  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  val view = LocalView.current
  var windowFocused by remember { mutableStateOf(view.hasWindowFocus()) }
  DisposableEffect(view) {
    val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> windowFocused = hasFocus }
    view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    windowFocused = view.hasWindowFocus()
    onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
  }
  val sheetReady =
    sheetState != null && sheetState.currentValue == SheetValue.Expanded && !sheetState.isAnimationRunning
  val scroll = rememberScrollState()
  val scrollSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
  var viewportBottom by remember { mutableStateOf(0f) }
  var messageBottom by remember { mutableStateOf(0f) }
  var pickingDate by rememberSaveable { mutableStateOf(false) }
  var didAutofocus by remember(draft.id) { mutableStateOf(false) }
  val canSave = draft.name.isNotBlank() && !draft.saving
  LaunchedEffect(draft.id, requestFocus, windowFocused, sheetReady) {
    if (requestFocus && draft.id == null && !didAutofocus && windowFocused && sheetReady) {
      focus.requestFocus()
      keyboard?.show()
      didAutofocus = true
    }
  }
  // A photo problem is the last thing telling the user they can still type, and the keyboard can
  // leave the photo section below the fold, so the editor scrolls it into view. The scroll
  // container is a plain verticalScroll, which drops bring-into-view requests, so the scroll is
  // driven here and the sheet is given a moment to finish measuring first.
  LaunchedEffect(draft.photoMessage, windowFocused) {
    if (draft.photoMessage.isNullOrBlank() || !windowFocused) return@LaunchedEffect
    var previous = -1
    var settled = 0
    withTimeoutOrNull(RevealWaitMillis) {
      while (settled < SettledFrames) {
        withFrameNanos {}
        val range = scroll.maxValue
        settled = if (range == previous && range > 0) settled + 1 else 0
        previous = range
      }
    }
    val target = (scroll.value + (messageBottom - viewportBottom)).roundToInt().coerceIn(0, scroll.maxValue)
    scroll.animateScrollTo(target, scrollSpec)
  }

  BoxWithConstraints(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
      Column(
        Modifier
          .weight(1f, fill = false)
          .verticalScroll(scroll)
          .padding(horizontal = 20.dp)
          .onGloballyPositioned { viewportBottom = it.boundsInWindow().bottom },
      ) {
        Text(
          text = stringResource(if (draft.id == null) R.string.editor_add_title else R.string.editor_edit_title),
          style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
          value = draft.name,
          onValueChange = { callbacks.onDraftChange(draft.copy(name = it, errorMessage = null)) },
          modifier = Modifier.fillMaxWidth().focusRequester(focus),
          enabled = !draft.saving,
          singleLine = true,
          isError = !draft.errorMessage.isNullOrBlank(),
          label = { Text(stringResource(R.string.editor_name_label)) },
          supportingText =
            draft.errorMessage?.let { message ->
              { Text(message) }
            },
          shape = MaterialTheme.shapes.medium,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { if (canSave) callbacks.onSave() }),
        )
        Spacer(Modifier.height(8.dp))
        // The shortcuts are the fast path for the first choice. Once the name is the user's own
        // they get out of the way, which also keeps the photo section reachable above the keyboard.
        if (draft.id == null && presets.isNotEmpty() && (draft.name.isBlank() || draft.presetId != null)) {
          PresetRow(
            presets = presets,
            selectedId = draft.presetId,
            enabled = !draft.saving,
            onPreset = callbacks.onPresetSelected,
          )
          Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.editor_quantity_label), style = MaterialTheme.typography.labelLarge)
        QuantityStepper(
          quantity = draft.quantity,
          name = draft.name.ifBlank { stringResource(R.string.editor_name_label) },
          onQuantityChange = { callbacks.onDraftChange(draft.copy(quantity = it, errorMessage = null)) },
          enabled = !draft.saving,
          modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        ExpiryRow(draft = draft, enabled = !draft.saving, onPick = { pickingDate = true }, onClear = {
          callbacks.onDraftChange(draft.copy(expiryEpochDay = null, errorMessage = null))
        })
        Spacer(Modifier.height(16.dp))
        PhotoRow(draft = draft, callbacks = callbacks, onMessageBounds = { messageBottom = it })
        Spacer(Modifier.height(8.dp))
        if (draft.id != null) {
          TextButton(
            onClick = { callbacks.onDelete(draft.id) },
            enabled = !draft.saving,
            modifier = Modifier.heightIn(min = 48.dp),
          ) {
            Icon(
              FridgeDelete,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error)
          }
        }
      }
      val saveFade = motionFade<Float>()
      Button(
        onClick = callbacks.onSave,
        enabled = canSave,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        AnimatedContent(
          targetState = draft.saving,
          transitionSpec = { fadeIn(saveFade) togetherWith fadeOut(saveFade) },
          label = "save",
        ) { saving ->
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saving) {
              LoadingIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
            }
            Text(stringResource(if (saving) R.string.editor_saving else R.string.editor_save))
          }
        }
      }
    }
  }

  if (pickingDate) {
    ExpiryDialog(
      epochDay = draft.expiryEpochDay,
      onConfirm = { day ->
        callbacks.onDraftChange(draft.copy(expiryEpochDay = day, errorMessage = null))
        pickingDate = false
      },
      onDismiss = { pickingDate = false },
    )
  }
}

/**
 * Premade foods as one scrolling row of fills, shown only while adding. A tap writes the name
 * and a suggested date into the draft; Save is still the only thing that adds the item.
 */
@Composable
private fun PresetRow(
  presets: List<FoodPreset>,
  selectedId: String?,
  enabled: Boolean,
  onPreset: (FoodPreset) -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Text(text = stringResource(R.string.editor_presets_label), style = MaterialTheme.typography.labelLarge)
    Text(
      text = stringResource(R.string.editor_presets_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp),
    )
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      presets.forEach { preset ->
        val selected = preset.id == selectedId
        OutlinedButton(
          onClick = { onPreset(preset) },
          enabled = enabled,
          modifier = Modifier.heightIn(min = 48.dp),
          shape = MaterialTheme.shapes.extraLarge,
          colors =
            if (selected) {
              ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            } else {
              ButtonDefaults.outlinedButtonColors()
            },
        ) {
          if (selected) {
            Icon(FridgeCheck, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
          }
          Text(preset.name, maxLines = 1)
        }
      }
    }
  }
}

/**
 * Expiry label, value, and actions stack instead of sharing one line, so a long date and two
 * labelled actions reflow on a narrow window or at large text instead of squeezing each other.
 */
@Composable
private fun ExpiryRow(draft: ItemDraft, enabled: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
  val epochDay = draft.expiryEpochDay
  val fade = motionFade<Float>()
  Column(Modifier.fillMaxWidth()) {
    Text(text = stringResource(R.string.editor_expiry_label), style = MaterialTheme.typography.labelLarge)
    AnimatedContent(
      targetState = epochDay,
      transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
      label = "expiry-value",
    ) { day ->
      Text(
        text = fridgeExpiryLabel(day),
        style = MaterialTheme.typography.titleMedium,
        color = fridgeExpiryColor(day),
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
      )
    }
    FlowRow(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      TextButton(onClick = onPick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(FridgeCalendar, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.editor_expiry_choose))
      }
      if (epochDay != null) {
        TextButton(onClick = onClear, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
          Icon(FridgeClose, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(ButtonDefaults.IconSpacing))
          Text(stringResource(R.string.editor_expiry_clear))
        }
      }
    }
  }
}

/**
 * Photo is optional, so it stays last. A problem message sits directly under the heading, where
 * the keyboard cannot push it out of reach, and [onMessageBounds] lets the editor scroll to it.
 */
@Composable
private fun PhotoRow(draft: ItemDraft, callbacks: FridgeCallbacks, onMessageBounds: (Float) -> Unit) {
  val take = stringResource(R.string.editor_take_photo)
  val pick = stringResource(R.string.editor_pick_photo)
  val message = draft.photoMessage
  Text(text = stringResource(R.string.editor_photo_label), style = MaterialTheme.typography.labelLarge)
  if (!message.isNullOrBlank()) {
    Surface(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(top = 6.dp)
          .onGloballyPositioned { onMessageBounds(it.boundsInWindow().bottom) },
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      )
    }
  }
  if (draft.photoReference != null) {
    FridgeThumbnail(
      name = draft.name.ifBlank { stringResource(R.string.editor_photo_label) },
      photoReference = draft.photoReference,
      modifier = Modifier.padding(top = 8.dp),
    )
    TextButton(
      onClick = callbacks.onRemovePhoto,
      enabled = !draft.saving,
      modifier = Modifier.heightIn(min = 48.dp),
    ) {
      Icon(
        FridgeDelete,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(18.dp),
      )
      Spacer(Modifier.width(ButtonDefaults.IconSpacing))
      Text(stringResource(R.string.editor_remove_photo), color = MaterialTheme.colorScheme.error)
    }
  } else if (message.isNullOrBlank()) {
    Text(
      text = stringResource(R.string.editor_photo_empty),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
  }
  // Whole actions wrap instead of sharing a line: a connected group narrows each item below its
  // longest word at 200% text and breaks "Choose photo" in the middle.
  FlowRow(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedButton(
      onClick = callbacks.onTakePhoto,
      enabled = !draft.saving,
      modifier = Modifier.heightIn(min = 48.dp),
    ) {
      Text(take)
    }
    OutlinedButton(
      onClick = callbacks.onPickPhoto,
      enabled = !draft.saving,
      modifier = Modifier.heightIn(min = 48.dp),
    ) {
      Text(pick)
    }
  }
}

@Composable
private fun ExpiryDialog(epochDay: Long?, onConfirm: (Long?) -> Unit, onDismiss: () -> Unit) {
  val initial = epochDay?.let(::epochDayToUtcMillis)
  val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
  DatePickerDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = { onConfirm(picker.selectedDateMillis?.let(::utcMillisToEpochDay)) }, enabled = picker.selectedDateMillis != null) {
        Text(stringResource(R.string.editor_date_confirm))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_date_cancel)) } },
  ) {
    DatePicker(state = picker)
  }
}

@Composable
private fun <T> motionFade(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()

/** How long the editor waits for the sheet to be measured before revealing a photo problem. */
private const val RevealWaitMillis = 1_500L

/** Frames the scroll range must hold still before the editor scrolls to a photo problem. */
private const val SettledFrames = 3

private fun epochDayToUtcMillis(epochDay: Long): Long =
  LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcMillisToEpochDay(millis: Long): Long =
  Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
