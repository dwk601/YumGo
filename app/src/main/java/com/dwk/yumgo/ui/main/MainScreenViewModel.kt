package com.dwk.yumgo.ui.main

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dwk.yumgo.R
import com.dwk.yumgo.data.FoodPreset
import com.dwk.yumgo.data.FridgeItem
import com.dwk.yumgo.data.FridgeRepository
import com.dwk.yumgo.data.NewFridgeItem
import com.dwk.yumgo.data.PhotoStore
import com.dwk.yumgo.data.PresetRepository
import com.dwk.yumgo.data.SettingsServices
import com.dwk.yumgo.ui.photo.PhotoAcquisition
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One fridge screen. [repository] and [photos] are the process's single instances,
 * created with the first ViewModel and kept across rotation. [appContext] is the
 * application context, read only for the user-facing copy this screen shows.
 *
 * Draft fields saved here use the raw photo ref (`staged/…` or `saved/…`).
 * [uiState] maps those refs through [PhotoStore.existingFile] so the UI can draw them.
 */
class MainScreenViewModel(
  private val repository: FridgeRepository,
  private val photos: PhotoStore,
  presets: PresetRepository,
  private val appContext: Context,
  private val savedState: SavedStateHandle,
) : ViewModel() {
  private val load = MutableStateFlow(FridgeLoad.Loading)
  private val errorMessage = MutableStateFlow<String?>(null)
  private val query = MutableStateFlow(savedState.get<String>(KEY_QUERY).orEmpty())
  private val items = MutableStateFlow<List<FridgeItem>>(emptyList())
  private val draft = MutableStateFlow(readDraft())
  private val cameraOpen = MutableStateFlow(savedState.get<Boolean>(KEY_CAMERA) == true)
  private val displayPaths = MutableStateFlow<Map<String, String>>(emptyMap())
  private val acquisitionActive = AtomicBoolean(cameraOpen.value)
  private var reviewRef: String? = null
  private var observeJob: Job? = null
  private val pendingUndo = MutableStateFlow(readPendingUndo())

  /** Latest delete that can still be undone. Survives recreation until Undo or dismiss. */
  val pendingUndoState: StateFlow<PendingUndo?> = pendingUndo

  /**
   * Premade foods offered to a new draft. Read from the process-wide repository, so a change
   * made in settings reaches the next draft and never rewrites one that is already filled.
   */
  val presets: StateFlow<List<FoodPreset>> = presets.presets

  val notices = MutableSharedFlow<FridgeNotice>(extraBufferCapacity = 4)

  val uiState: StateFlow<FridgeUiState> =
    combine(load, errorMessage, query, items, draft, cameraOpen, displayPaths) { values ->
      val currentLoad = values[0] as FridgeLoad
      val currentError = values[1] as String?
      val currentQuery = values[2] as String
      @Suppress("UNCHECKED_CAST") val currentItems = values[3] as List<FridgeItem>
      val currentDraft = values[4] as Editable?
      val currentCamera = values[5] as Boolean
      @Suppress("UNCHECKED_CAST") val paths = values[6] as Map<String, String>
      FridgeUiState(
        load = currentLoad,
        errorMessage = currentError,
        query = currentQuery,
        items = currentItems.map { it.toUi(paths) },
        draft = currentDraft?.toUi(paths),
        cameraOpen = currentCamera,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

  init {
    viewModelScope.launch {
      combine(items, draft) { rows, editing ->
        buildSet {
          rows.mapNotNullTo(this) { it.photoRef }
          editing?.photoRef?.let(::add)
        }
      }.distinctUntilChanged().collect { refs -> displayPaths.value = resolvePaths(refs) }
    }
    observeItems()
    if (!cameraOpen.value) viewModelScope.launch { cleanupIfIdle() }
  }

  fun onQueryChange(value: String) {
    query.value = value
    savedState[KEY_QUERY] = value
  }

  fun onRetry() {
    observeItems()
  }

  fun onAdd() {
    if (draft.value?.saving == true) return
    draft.value = Editable(id = null, name = "", quantity = 1, expiryEpochDay = null, photoRef = null)
    persistDraft()
  }

  /**
   * Fills the new draft from a premade food: its name and today's date plus its suggested days.
   * Each field is filled only while the user has not decided it, so a typed name or a chosen date
   * survives a later tap. A name the user has only started typing is the one exception: the
   * shortcut finishes what they wrote rather than replacing it, which is what narrowing the lane
   * to their typing promised them. Nothing is written to the fridge until Save.
   */
  fun onPresetSelected(preset: FoodPreset) {
    val current = draft.value ?: return
    if (current.id != null || current.saving) return
    val days = preset.expiryDays.coerceIn(0, MaxPresetDays).toLong()
    val suggested = LocalDate.now().plusDays(days).toEpochDay()
    // Fill each field only while the user has not decided it: a name they typed and a date they
    // picked are theirs, and a tap must not take either back.
    val typed = current.name.trim()
    val keepName =
      typed.isNotEmpty() && !current.nameFromPreset && presets.value.completing(typed).none { it.id == preset.id }
    val keepDate = current.expiryEpochDay != null && current.presetEpochDay != current.expiryEpochDay
    draft.value =
      current.copy(
        name = if (keepName) current.name else preset.name,
        expiryEpochDay = if (keepDate) current.expiryEpochDay else suggested,
        errorMessage = null,
        nameFromPreset = !keepName,
        presetId = preset.id,
        presetEpochDay = suggested,
      )
    persistDraft()
  }

  fun onEdit(id: String) {
    if (draft.value?.saving == true) return
    val item = items.value.firstOrNull { it.id == id } ?: return
    draft.value =
      Editable(
        id = item.id,
        name = item.name,
        quantity = item.quantity,
        expiryEpochDay = item.expiresOn?.toEpochDay(),
        photoRef = item.photoRef,
      )
    persistDraft()
  }

  fun onDraftChange(updated: ItemDraft) {
    val current = draft.value ?: return
    if (current.saving) return
    val nameChanged = updated.name != current.name
    val dateChanged = updated.expiryEpochDay != current.expiryEpochDay
    val nameFromPreset = if (nameChanged) false else current.nameFromPreset
    val presetEpochDay = if (dateChanged) null else current.presetEpochDay
    draft.value =
      current.copy(
        name = updated.name,
        quantity = updated.quantity.coerceIn(1, 99),
        expiryEpochDay = updated.expiryEpochDay,
        errorMessage = null,
        nameFromPreset = nameFromPreset,
        // Nothing of the preset is left once the user has taken over both fields.
        presetId = if (nameFromPreset || presetEpochDay != null) current.presetId else null,
        presetEpochDay = presetEpochDay,
      )
    persistDraft()
  }

  fun onDismissEditor() {
    val current = draft.value ?: return
    if (current.saving) return
    val abandoned = current.photoRef
    val committed = current.id?.let { id -> items.value.firstOrNull { it.id == id }?.photoRef }
    draft.value = null
    persistDraft()
    if (abandoned != null && abandoned != committed) {
      viewModelScope.launch { discardIfUnprotected(abandoned) }
    }
  }

  fun onQuantityChange(id: String, quantity: Int) {
    viewModelScope.launch {
      repository.setQuantity(id, quantity).fold(
        onSuccess = { updated ->
          if (draft.value?.id == id) {
            draft.value = draft.value?.copy(quantity = updated.quantity)
            persistDraft()
          }
        },
        onFailure = { error -> notices.emit(FridgeNotice(text(R.string.fridge_notice_quantity, error))) },
      )
    }
  }

  fun onDelete(id: String) {
    val name =
      items.value.firstOrNull { it.id == id }?.name
        ?: draft.value?.name
        ?: appContext.getString(R.string.fridge_unnamed_item)
    viewModelScope.launch {
      repository.delete(id).fold(
        onSuccess = {
          if (draft.value?.id == id) {
            draft.value = null
            persistDraft()
          }
          setPendingUndo(id, name)
        },
        onFailure = { error -> notices.emit(FridgeNotice(text(R.string.fridge_notice_remove, error))) },
      )
    }
  }

  /** User chose Undo. Ignores a stale id after a newer delete replaced the snackbar. */
  fun confirmUndo(id: String) {
    if (pendingUndo.value?.id != id) return
    clearPendingUndo()
    viewModelScope.launch {
      repository.undoDelete(id).onFailure { error ->
        notices.emit(FridgeNotice(text(R.string.fridge_notice_undo, error)))
      }
    }
  }

  /** User dismissed the snackbar. A cancelled composition must not call this. */
  fun dismissUndo(id: String) {
    if (pendingUndo.value?.id != id) return
    clearPendingUndo()
  }

  fun onSave() {
    val current = draft.value ?: return
    if (current.saving) return
    val name = current.name.trim()
    if (name.isEmpty()) {
      draft.value = current.copy(errorMessage = appContext.getString(R.string.editor_name_required))
      persistDraft()
      return
    }
    draft.value = current.copy(name = name, saving = true, errorMessage = null)
    persistDraft()
    viewModelScope.launch {
      val photo = promoteDraftPhoto()
      if (photo == null && draft.value?.photoRef != null && draft.value?.errorMessage != null) return@launch
      val expiry = draft.value?.expiryEpochDay?.let(LocalDate::ofEpochDay)
      val quantity = draft.value?.quantity ?: current.quantity
      val editingId = draft.value?.id
      val saved =
        if (editingId == null) {
          repository.add(NewFridgeItem(name = name, quantity = quantity, expiresOn = expiry, photoRef = photo))
        } else {
          repository.update(FridgeItem(id = editingId, name = name, quantity = quantity, expiresOn = expiry, photoRef = photo))
        }
      saved.fold(
        onSuccess = {
          draft.value = null
          persistDraft()
          cleanupIfIdle()
        },
        onFailure = { error ->
          draft.value = draft.value?.copy(saving = false, errorMessage = text(R.string.fridge_notice_save, error))
          persistDraft()
        },
      )
    }
  }

  fun onRemovePhoto() {
    val current = draft.value ?: return
    if (current.saving) return
    val previous = current.photoRef
    draft.value = current.copy(photoRef = null, photoMessage = null)
    persistDraft()
    if (previous != null) viewModelScope.launch { discardIfUnprotected(previous) }
  }

  fun onTakePhoto() {
    if (draft.value == null || draft.value?.saving == true) return
    acquisitionActive.set(true)
    cameraOpen.value = true
    savedState[KEY_CAMERA] = true
  }

  fun onPickStarted() {
    acquisitionActive.set(true)
  }

  fun onReviewRef(ref: String?) {
    reviewRef = ref
  }

  fun onAcquisition(result: PhotoAcquisition) {
    acquisitionActive.set(false)
    if (cameraOpen.value) {
      cameraOpen.value = false
      savedState[KEY_CAMERA] = false
    }
    val current = draft.value ?: return
    when (result) {
      is PhotoAcquisition.Staged -> {
        val previous = current.photoRef
        draft.value = current.copy(photoRef = result.ref, errorMessage = null, photoMessage = null, saving = false)
        persistDraft()
        if (previous != null && previous != result.ref) {
          viewModelScope.launch { discardIfUnprotected(previous) }
        }
      }
      PhotoAcquisition.Cancelled -> Unit
      PhotoAcquisition.PermissionDenied ->
        draft.value =
          current.copy(
            photoMessage = appContext.getString(R.string.photo_permission_denied),
            saving = false,
          )
      is PhotoAcquisition.Failed -> draft.value = current.copy(photoMessage = result.message, saving = false)
    }
    if (result !is PhotoAcquisition.Staged) persistDraft()
    if (!cameraOpen.value) viewModelScope.launch { cleanupIfIdle() }
  }

  private fun observeItems() {
    observeJob?.cancel()
    observeJob =
      viewModelScope.launch {
        if (items.value.isEmpty()) load.value = FridgeLoad.Loading
        try {
          repository.items.collect { rows ->
            items.value = rows
            load.value = FridgeLoad.Ready
            errorMessage.value = null
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          load.value = FridgeLoad.Error
          errorMessage.value = error.message
        }
      }
  }

  private suspend fun promoteDraftPhoto(): String? {
    val current = draft.value ?: return null
    val ref = current.photoRef ?: return null
    if (!ref.startsWith("staged/")) return ref
    return photos.promote(ref).fold(
      onSuccess = { promoted ->
        draft.value = draft.value?.copy(photoRef = promoted)
        persistDraft()
        promoted
      },
      onFailure = { error ->
        draft.value = draft.value?.copy(saving = false, errorMessage = text(R.string.fridge_notice_photo, error))
        persistDraft()
        null
      },
    )
  }

  private suspend fun discardIfUnprotected(ref: String) {
    if (!ref.startsWith("staged/")) return
    if (acquisitionActive.get() || cameraOpen.value) return
    val retained = repository.retainedPhotoRefs().getOrElse { return }
    if (acquisitionActive.get() || cameraOpen.value) return
    val protected = retained + setOfNotNull(draft.value?.photoRef, reviewRef, photos.activeReviewRef())
    if (ref in protected) return
    photos.discard(ref, protected)
  }

  /**
   * Deletes unreferenced photos only after a complete protected-set read.
   * Skips entirely while the camera or picker is active, or if the repository read fails.
   */
  private suspend fun cleanupIfIdle() {
    if (acquisitionActive.get() || cameraOpen.value) return
    val retained = repository.retainedPhotoRefs().getOrElse { return }
    if (acquisitionActive.get() || cameraOpen.value) return
    val activeReview = try {
      photos.activeReviewRef()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      return
    }
    if (acquisitionActive.get() || cameraOpen.value) return
    val protected = retained + setOfNotNull(draft.value?.photoRef, reviewRef, activeReview)
    if (acquisitionActive.get() || cameraOpen.value) return
    photos.cleanup(protected)
    if (acquisitionActive.get() || cameraOpen.value) return
    photos.cleanupSaved(protected)
  }

  private suspend fun resolvePaths(refs: Set<String>): Map<String, String> =
    buildMap {
      refs.forEach { ref ->
        val file = try {
          photos.existingFile(ref)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          null
        }
        if (file != null) put(ref, file.absolutePath)
      }
    }

  /** Reader copy first, then the failure detail, so a message is never blank. */
  private fun text(id: Int, error: Throwable? = null): String {
    val fallback = appContext.getString(id)
    val detail = error?.message?.takeIf { it.isNotBlank() } ?: return fallback
    return "$fallback: $detail"
  }

  private fun persistDraft() {
    val current = draft.value
    savedState[KEY_DRAFT_OPEN] = current != null
    if (current == null) {
      savedState.remove<String>(KEY_DRAFT_ID)
      savedState.remove<String>(KEY_DRAFT_NAME)
      savedState.remove<Int>(KEY_DRAFT_QTY)
      savedState.remove<Long>(KEY_DRAFT_EXPIRY)
      savedState.remove<String>(KEY_DRAFT_PHOTO)
      savedState.remove<String>(KEY_DRAFT_PHOTO_MESSAGE)
      savedState.remove<String>(KEY_DRAFT_PRESET)
      savedState.remove<Long>(KEY_DRAFT_PRESET_DATE)
      savedState[KEY_DRAFT_PRESET_NAME] = false
      return
    }
    if (current.id == null) savedState.remove<String>(KEY_DRAFT_ID) else savedState[KEY_DRAFT_ID] = current.id
    savedState[KEY_DRAFT_NAME] = current.name
    savedState[KEY_DRAFT_QTY] = current.quantity
    if (current.expiryEpochDay == null) savedState.remove<Long>(KEY_DRAFT_EXPIRY) else savedState[KEY_DRAFT_EXPIRY] = current.expiryEpochDay
    savedState[KEY_DRAFT_PHOTO] = current.photoRef
    if (current.photoMessage == null) savedState.remove<String>(KEY_DRAFT_PHOTO_MESSAGE) else savedState[KEY_DRAFT_PHOTO_MESSAGE] = current.photoMessage
    if (current.presetId == null) savedState.remove<String>(KEY_DRAFT_PRESET) else savedState[KEY_DRAFT_PRESET] = current.presetId
    if (current.presetEpochDay == null) savedState.remove<Long>(KEY_DRAFT_PRESET_DATE) else savedState[KEY_DRAFT_PRESET_DATE] = current.presetEpochDay
    savedState[KEY_DRAFT_PRESET_NAME] = current.nameFromPreset
  }

  private fun readDraft(): Editable? {
    if (savedState.get<Boolean>(KEY_DRAFT_OPEN) != true) return null
    return Editable(
      id = savedState.get<String>(KEY_DRAFT_ID),
      name = savedState.get<String>(KEY_DRAFT_NAME).orEmpty(),
      quantity = savedState.get<Int>(KEY_DRAFT_QTY) ?: 1,
      expiryEpochDay = savedState.get<Long>(KEY_DRAFT_EXPIRY),
      photoRef = savedState.get<String>(KEY_DRAFT_PHOTO),
      photoMessage = savedState.get<String>(KEY_DRAFT_PHOTO_MESSAGE),
      presetId = savedState.get<String>(KEY_DRAFT_PRESET),
      presetEpochDay = savedState.get<Long>(KEY_DRAFT_PRESET_DATE),
      nameFromPreset = savedState[KEY_DRAFT_PRESET_NAME] ?: false,
    )
  }

  private fun readPendingUndo(): PendingUndo? {
    val id = savedState.get<String>(KEY_UNDO_ID) ?: return null
    val name = savedState.get<String>(KEY_UNDO_NAME) ?: return null
    return PendingUndo(id, name)
  }

  private fun setPendingUndo(id: String, name: String) {
    savedState[KEY_UNDO_ID] = id
    savedState[KEY_UNDO_NAME] = name
    pendingUndo.value = PendingUndo(id, name)
  }

  private fun clearPendingUndo() {
    savedState.remove<String>(KEY_UNDO_ID)
    savedState.remove<String>(KEY_UNDO_NAME)
    pendingUndo.value = null
  }

  private fun initialState(): FridgeUiState {
    val editing = draft.value
    return FridgeUiState(
      load = FridgeLoad.Loading,
      query = query.value,
      draft = editing?.toUi(emptyMap()),
      cameraOpen = cameraOpen.value,
    )
  }

  private fun FridgeItem.toUi(paths: Map<String, String>) =
    FridgeItemUi(
      id = id,
      name = name,
      quantity = quantity,
      expiryEpochDay = expiresOn?.toEpochDay(),
      photoReference = photoRef?.let { paths[it] },
    )

  private fun Editable.toUi(paths: Map<String, String>) =
    ItemDraft(
      id = id,
      name = name,
      quantity = quantity,
      expiryEpochDay = expiryEpochDay,
      photoReference = photoRef?.let { paths[it] },
      saving = saving,
      errorMessage = errorMessage,
      photoMessage = photoMessage,
      // The chip is only lit while something in the draft still came from a preset.
      presetId = if (nameFromPreset || presetEpochDay != null) presetId else null,
      // The editor's hint has to tell the truth about what a tap would do to the name, and only
      // this flag knows whether a name that reads like a preset's own is the user's.
      nameFromPreset = nameFromPreset,
    )

  private data class Editable(
    val id: String?,
    val name: String,
    val quantity: Int,
    val expiryEpochDay: Long?,
    val photoRef: String?,
    val saving: Boolean = false,
    val errorMessage: String? = null,
    val photoMessage: String? = null,
    val presetId: String? = null,
    /** Date [presetId] suggested, kept so a name edit does not make a changed date look untouched. */
    val presetEpochDay: Long? = null,
    /** True while the draft still carries the preset's own name, so a tap may replace it. */
    val nameFromPreset: Boolean = false,
  )

  private companion object {
    const val KEY_QUERY = "fridge_query"
    const val KEY_CAMERA = "fridge_camera"
    const val KEY_DRAFT_OPEN = "draft_open"
    const val KEY_DRAFT_ID = "draft_id"
    const val KEY_DRAFT_NAME = "draft_name"
    const val KEY_DRAFT_QTY = "draft_qty"
    const val KEY_DRAFT_EXPIRY = "draft_expiry"
    const val KEY_DRAFT_PHOTO = "draft_photo"
    const val KEY_DRAFT_PHOTO_MESSAGE = "draft_photo_message"
    const val KEY_DRAFT_PRESET = "draft_preset"
    const val KEY_DRAFT_PRESET_DATE = "draft_preset_date"
    const val KEY_DRAFT_PRESET_NAME = "draft_preset_name"
    const val KEY_UNDO_ID = "undo_id"
    const val KEY_UNDO_NAME = "undo_name"
  }
}

data class PendingUndo(
  val id: String,
  val name: String,
)

data class FridgeNotice(
  val text: String,
)

/** Longest suggested shelf life a preset may contribute, matching the settings limit. */
private const val MaxPresetDays = 365
