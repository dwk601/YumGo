@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.dwk.yumgo.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dwk.yumgo.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * In-place add/edit sheet. The name field takes focus. Save stays pinned above
 * the keyboard. Photo buttons only report intent; the caller stages the file
 * and writes it back onto [draft].
 */
@Composable
fun ItemEditor(draft: ItemDraft, callbacks: FridgeCallbacks, modifier: Modifier = Modifier) {
  ModalBottomSheet(
    onDismissRequest = { if (!draft.saving) callbacks.onDismissEditor() },
    modifier = modifier,
    sheetGesturesEnabled = !draft.saving,
    shape = MaterialTheme.shapes.extraLarge,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    EditorBody(draft = draft, callbacks = callbacks)
  }
}

@Composable
private fun EditorBody(draft: ItemDraft, callbacks: FridgeCallbacks) {
  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  val scroll = rememberScrollState()
  var pickingDate by rememberSaveable { mutableStateOf(false) }
  val canSave = draft.name.isNotBlank() && !draft.saving
  LaunchedEffect(draft.id) {
    focus.requestFocus()
    keyboard?.show()
  }

  BoxWithConstraints(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
      Column(
        Modifier
          .weight(1f, fill = false)
          .verticalScroll(scroll)
          .padding(horizontal = 20.dp),
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
        PhotoRow(draft = draft, callbacks = callbacks)
        Spacer(Modifier.height(8.dp))
        if (draft.id != null) {
          TextButton(onClick = { callbacks.onDelete(draft.id) }, enabled = !draft.saving) {
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
          Text(stringResource(if (saving) R.string.editor_saving else R.string.editor_save))
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

@Composable
private fun ExpiryRow(draft: ItemDraft, enabled: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(text = stringResource(R.string.editor_expiry_label), style = MaterialTheme.typography.labelLarge)
      Text(
        text = fridgeExpiryLabel(draft.expiryEpochDay),
        style = MaterialTheme.typography.titleMedium,
        color = fridgeExpiryColor(draft.expiryEpochDay),
        modifier = Modifier.padding(top = 2.dp),
      )
    }
    if (draft.expiryEpochDay != null) {
      TextButton(onClick = onClear, enabled = enabled) { Text(stringResource(R.string.editor_expiry_clear)) }
    }
    TextButton(onClick = onPick, enabled = enabled) { Text(stringResource(R.string.editor_expiry_choose)) }
  }
}

@Composable
private fun PhotoRow(draft: ItemDraft, callbacks: FridgeCallbacks) {
  val take = stringResource(R.string.editor_take_photo)
  val pick = stringResource(R.string.editor_pick_photo)
  val more = stringResource(R.string.editor_more_photo_actions)
  Text(text = stringResource(R.string.editor_photo_label), style = MaterialTheme.typography.labelLarge)
  if (draft.photoReference != null) {
    FridgeThumbnail(
      name = draft.name.ifBlank { stringResource(R.string.editor_photo_label) },
      photoReference = draft.photoReference,
      modifier = Modifier.padding(top = 8.dp),
    )
    TextButton(onClick = callbacks.onRemovePhoto, enabled = !draft.saving) {
      Text(stringResource(R.string.editor_remove_photo))
    }
  } else {
    Text(
      text = stringResource(R.string.editor_photo_empty),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
  }
  ButtonGroup(overflowIndicator = { menu ->
    IconButton(onClick = { menu.show() }, enabled = !draft.saving) { Icon(FridgePlus, contentDescription = more) }
  }, modifier = Modifier.fillMaxWidth(), expandedRatio = 0f) {
    clickableItem(onClick = callbacks.onTakePhoto, label = take, weight = 1f, enabled = !draft.saving)
    clickableItem(onClick = callbacks.onPickPhoto, label = pick, weight = 1f, enabled = !draft.saving)
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

private fun epochDayToUtcMillis(epochDay: Long): Long =
  LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcMillisToEpochDay(millis: Long): Long =
  Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
