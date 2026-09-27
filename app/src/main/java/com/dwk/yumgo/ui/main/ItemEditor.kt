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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
  val compact = compactEditor()
  if (restored) {
    RestoredEditor(
      draft = draft,
      presets = presets,
      callbacks = callbacks,
      compact = compact,
      backEnabled = backEnabled,
      modifier = modifier,
    )
  } else {
    ModalBottomSheet(
      onDismissRequest = { if (!draft.saving) callbacks.onDismissEditor() },
      modifier = modifier,
      sheetState = sheetState,
      sheetGesturesEnabled = !draft.saving,
      shape = MaterialTheme.shapes.extraLarge,
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      contentColor = MaterialTheme.colorScheme.onSurface,
      dragHandle = { SheetHeader(draft, compact) },
    ) {
      EditorBody(
        draft = draft,
        callbacks = callbacks,
        presets = presets,
        compact = compact,
        requestFocus = draft.id == null,
        sheetState = sheetState,
      )
    }
  }
}

/**
 * True when the window is too short for the tall editor, which is every phone in landscape.
 *
 * The decision is the window height alone and never the keyboard: a layout that changed when the
 * keyboard opened would move the focused field between two places, lose it, and close the
 * keyboard again. The sheet is asked to fit into the height the window already has.
 */
@Composable
private fun compactEditor(): Boolean {
  val screenHeight = LocalConfiguration.current.screenHeightDp
  return remember(screenHeight) { screenHeight < CompactWindowHeightDp }
}

/**
 * The sheet's own row, and nothing of the editor's goes in it. The slot's own tap closes the sheet
 * in this Material version, so anything a user might aim at inside it takes a tap the user meant
 * for the editor and throws the draft away. A tall window therefore gets the standard handle and
 * nothing else; a short window has no height to spare for one, so the title shares the row with a
 * slim handle, and the row takes the slot's tap itself.
 */
@Composable
private fun SheetHeader(draft: ItemDraft, compact: Boolean) {
  if (!compact) {
    // A Column starts its children at the start, so the handle keeps its own centring row here.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
    return
  }
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(32.dp)
        .padding(start = 24.dp, end = 16.dp)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = {},
        ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(editorTitle(draft)),
      style = MaterialTheme.typography.titleMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(12.dp))
    DragBar()
  }
}

/** The slim handle the short window's header carries, and the only part of it that is a handle. */
@Composable
private fun DragBar() {
  Box(
    Modifier
      .size(width = 32.dp, height = 4.dp)
      .clip(RoundedCornerShape(2.dp))
      .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
  )
}

private fun editorTitle(draft: ItemDraft): Int =
  if (draft.id == null) R.string.editor_add_title else R.string.editor_edit_title

@Composable
private fun RestoredEditor(
  draft: ItemDraft,
  presets: List<FoodPreset>,
  callbacks: FridgeCallbacks,
  compact: Boolean,
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
        SheetHeader(draft, compact)
        EditorBody(
          draft = draft,
          callbacks = callbacks,
          presets = presets,
          compact = compact,
          requestFocus = false,
          sheetState = null,
        )
      }
    }
  }
}

@Composable
private fun EditorBody(
  draft: ItemDraft,
  callbacks: FridgeCallbacks,
  presets: List<FoodPreset>,
  compact: Boolean,
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
  // The field holds its own value, so the caret is the user's, not the field's: a rotation or a
  // recreation restores the value with the rest of the sheet and the caret comes back where it
  // was. The draft is still the text of record, so a shortcut that fills the name behind the
  // field's back lands, and the caret follows the text it did not type.
  var nameValue by rememberSaveable(draft.id, stateSaver = NameValueSaver) {
    mutableStateOf(TextFieldValue(draft.name, selection = TextRange(draft.name.length)))
  }
  LaunchedEffect(draft.name) {
    if (nameValue.text != draft.name) {
      nameValue = TextFieldValue(draft.name, selection = TextRange(draft.name.length))
    }
  }
  val onNameTyped: (TextFieldValue) -> Unit = { typed ->
    nameValue = typed
    callbacks.onDraftChange(draft.copy(name = typed.text, errorMessage = null))
  }
  // One field, one composition. The name and Save sit in the same row in both layouts, but that
  // row stands in a different place in a short window, so the field is movable content: a layout
  // that changes under a live editor moves the field rather than building it again, and the field
  // keeps its focus, its text, and the keyboard the user opened.
  val nameFocused = remember { mutableStateOf(false) }
  val nameField = remember {
    // The draft and the value travel as parameters, not captured: a movable content keeps one
    // composition, so a captured value would stay at whatever it was when the content was made.
    movableContentOf {
        fieldDraft: ItemDraft,
        fieldName: TextFieldValue,
        fieldOnName: (TextFieldValue) -> Unit,
        fieldModifier: Modifier ->
      NameField(
        value = fieldName,
        onValueChange = fieldOnName,
        focus = focus,
        enabled = !fieldDraft.saving,
        canSave = fieldDraft.name.isNotBlank() && !fieldDraft.saving,
        errorMessage = fieldDraft.errorMessage,
        callbacks = callbacks,
        modifier = fieldModifier.onFocusChanged { nameFocused.value = it.isFocused },
      )
    }
  }
  LaunchedEffect(draft.id, requestFocus, windowFocused, sheetReady) {
    if (requestFocus && draft.id == null && !didAutofocus && windowFocused && sheetReady) {
      focus.requestFocus()
      keyboard?.show()
      didAutofocus = true
    }
  }
  // Belt and braces: if the layout moves while the field holds focus, take it back rather than
  // leave the user with a field they cannot type into.
  LaunchedEffect(compact) {
    if (nameFocused.value) focus.requestFocus()
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
      // A tall window has height to spare above the scrolling part, and it spends it on the title
      // and the shortcuts: pinned, the lane is the same size before the first keystroke and after
      // the last, so the sheet cannot jump, and the shortcuts stay the one tap they exist to be. A
      // short window has no room for a second row, so there the title shares the sheet's own row
      // and the shortcuts scroll with the rest, still always the same size.
      if (!compact) {
        Text(
          text = stringResource(editorTitle(draft)),
          style = MaterialTheme.typography.headlineSmall,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (draft.id == null && presets.isNotEmpty()) {
          PresetLane(
            presets = presets,
            typed = draft.name,
            selectedId = draft.presetId,
            enabled = !draft.saving,
            onPreset = callbacks.onPresetSelected,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
          )
          Spacer(Modifier.height(4.dp))
        }
      }
      // The name and Save share one row in both layouts: the field the user is typing into and the
      // button that saves it are the two things that must never scroll away from a keyboard.
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        nameField(draft, nameValue, onNameTyped, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        SaveButton(
          draft = draft,
          canSave = canSave,
          onSave = callbacks.onSave,
          modifier = Modifier.widthIn(min = 96.dp),
        )
      }
      EditorFields(
        draft = draft,
        callbacks = callbacks,
        // In a tall window the shortcuts are already pinned above, so the scrolling part starts at
        // the quantity; in a short one there was no room to pin them, so they scroll with the rest.
        presets = if (compact) presets else emptyList(),
        modifier = Modifier.weight(1f, fill = false).editorScroll(scroll) { viewportBottom = it },
        onPickDate = { pickingDate = true },
        onMessageBounds = { messageBottom = it },
      )
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

/** The editor's scrolling column, reported so the editor can scroll a photo notice into view. */
private fun Modifier.editorScroll(scroll: ScrollState, onViewportBottom: (Float) -> Unit): Modifier =
  verticalScroll(scroll)
    .padding(horizontal = 20.dp)
    .onGloballyPositioned { onViewportBottom(it.boundsInWindow().bottom) }

/**
 * The editor's scrolling part, from the shortcuts down to Remove. It never holds the title or the
 * name field: both are pinned, because a sheet that scrolls the field the user is typing into out
 * of sight is worse than one that scrolls the parts they only visit now and then.
 */
@Composable
private fun EditorFields(
  draft: ItemDraft,
  callbacks: FridgeCallbacks,
  presets: List<FoodPreset>,
  modifier: Modifier = Modifier,
  onPickDate: () -> Unit,
  onMessageBounds: (Float) -> Unit,
) {
  Column(modifier) {
    // A short window has no row to pin the shortcuts to, so they open this part instead. The lane
    // is the same size either way, so the sheet cannot jump under the user's first keystroke.
    if (draft.id == null && presets.isNotEmpty()) {
      PresetLane(
        presets = presets,
        typed = draft.name,
        selectedId = draft.presetId,
        enabled = !draft.saving,
        onPreset = callbacks.onPresetSelected,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      )
      Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text(text = stringResource(R.string.editor_quantity_label), style = MaterialTheme.typography.labelLarge)
    QuantityStepper(
      quantity = draft.quantity,
      // An unnamed draft falls back to the bare action, so it never announces "of Name".
      name = draft.name,
      onQuantityChange = { callbacks.onDraftChange(draft.copy(quantity = it, errorMessage = null)) },
      enabled = !draft.saving,
      modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(16.dp))
    ExpiryRow(draft = draft, enabled = !draft.saving, onPick = onPickDate, onClear = {
      callbacks.onDraftChange(draft.copy(expiryEpochDay = null, errorMessage = null))
    })
    Spacer(Modifier.height(16.dp))
    PhotoRow(draft = draft, callbacks = callbacks, onMessageBounds = onMessageBounds)
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
}

/** The one field the user types in. It is pinned in a short window and scrolls in a tall one. */
@Composable
private fun NameField(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  focus: FocusRequester,
  enabled: Boolean,
  canSave: Boolean,
  errorMessage: String?,
  callbacks: FridgeCallbacks,
  modifier: Modifier = Modifier,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.focusRequester(focus),
    enabled = enabled,
    singleLine = true,
    isError = !errorMessage.isNullOrBlank(),
    label = { Text(stringResource(R.string.editor_name_label)) },
    supportingText =
      errorMessage?.let { message ->
        { Text(message) }
      },
    shape = MaterialTheme.shapes.medium,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { if (canSave) callbacks.onSave() }),
  )
}

/** Save, beside the name in every window, and never anywhere a keyboard can reach it. */
@Composable
private fun SaveButton(
  draft: ItemDraft,
  canSave: Boolean,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val saveFade = motionFade<Float>()
  Button(
    onClick = onSave,
    enabled = canSave,
    modifier = modifier.heightIn(min = 48.dp),
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

/**
 * The shortcuts: what they are, what one tap of them does, and the taps themselves.
 *
 * The lane is the same size whatever it carries, so nothing around it moves and the sheet cannot
 * jump under a keystroke. The chips narrow to what has been typed, and only while that could finish
 * the name, because a lane narrowed to something a tap cannot complete reads as a promise it does
 * not keep. A name that is already a shortcut's own leaves the whole list standing, so one
 * shortcut can be swapped for another.
 */
@Composable
private fun PresetLane(
  presets: List<FoodPreset>,
  typed: String,
  selectedId: String?,
  enabled: Boolean,
  onPreset: (FoodPreset) -> Unit,
  modifier: Modifier = Modifier,
) {
  val name = typed.trim()
  val completions = presets.filter { it.completes(name) }
  val named = presets.any { it.name.equals(name, ignoreCase = true) }
  val shown = if (completions.isEmpty() || named) presets else completions
  Column(modifier) {
    Text(text = stringResource(R.string.editor_presets_label), style = MaterialTheme.typography.labelLarge)
    Text(
      text = stringResource(R.string.editor_presets_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp),
    )
    Spacer(Modifier.height(4.dp))
    // The chips scroll, and both ends fade into the sheet rather than slicing a chip in half. A
    // chip cut off by an edge reads as something broken, not as more to come.
    val chips = rememberScrollState()
    Row(
      modifier = Modifier.fillMaxWidth().scrollEdgeFade(chips, MaterialTheme.colorScheme.surfaceContainerLow)
        .horizontalScroll(chips)
        .heightIn(min = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      shown.forEach { preset ->
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
      // The fade needs something to fade into, and a last chip flush against it reads as cut off.
      Spacer(Modifier.width(EdgeFadeWidthDp))
    }
  }
}

/**
 * Fades the edges of a row that scrolls, on the sides that have something behind them. The reads
 * happen while drawing, so scrolling repaints the fade and nothing else.
 */
private fun Modifier.scrollEdgeFade(state: ScrollState, color: Color, width: Dp = EdgeFadeWidthDp): Modifier =
  drawWithContent {
    drawContent()
    val fade = width.toPx()
    if (state.value > 0f) {
      drawRect(
        brush = Brush.horizontalGradient(listOf(Color.Transparent, color), startX = 0f, endX = fade),
        size = Size(fade, size.height),
      )
    }
    if (state.value < state.maxValue) {
      drawRect(
        brush = Brush.horizontalGradient(
          colors = listOf(Color.Transparent, color),
          startX = size.width - fade,
          endX = size.width,
        ),
        topLeft = Offset(size.width - fade, 0f),
        size = Size(fade, size.height),
      )
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

/**
 * Window height below which the editor is the short one. It sits above every phone in landscape
 * (360 to 430dp) and below every phone in portrait (640dp and up), so a rotation is the only
 * thing that moves a live editor between the two layouts.
 */
private const val CompactWindowHeightDp = 480

/**
 * The name field's value, saved as its text and the two ends of its selection, in order: a
 * backwards selection has to come back backwards. A [Saver] has to hand the registry something a
 * bundle can hold, and a text value is not one of them.
 */
private val NameValueSaver: Saver<TextFieldValue, Any> =
  listSaver(
    save = { listOf(it.text, it.selection.start, it.selection.end) },
    restore = { saved ->
      TextFieldValue(text = saved[0] as String, selection = TextRange(saved[1] as Int, saved[2] as Int))
    },
  )

/** How long the editor waits for the sheet to be measured before revealing a photo problem. */
private const val RevealWaitMillis = 1_500L

/** How wide the fade at the ends of the shortcut lane is, and the gap it needs to fade into. */
private val EdgeFadeWidthDp = 16.dp

/** Frames the scroll range must hold still before the editor scrolls to a photo problem. */
private const val SettledFrames = 3

private fun epochDayToUtcMillis(epochDay: Long): Long =
  LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcMillisToEpochDay(millis: Long): Long =
  Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
