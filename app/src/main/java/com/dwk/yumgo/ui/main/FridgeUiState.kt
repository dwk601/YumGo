package com.dwk.yumgo.ui.main

import com.dwk.yumgo.data.FoodPreset

/**
 * Presentation state for the fridge. The caller owns persistence and photo files;
 * this model is only what the screen renders.
 *
 * [items] is the full visible set, including rows that do not match [query].
 * The screen filters and orders them expiry-first. Do not pass hidden or
 * deleted rows.
 */
data class FridgeUiState(
  val load: FridgeLoad = FridgeLoad.Loading,
  /** Shown when [load] is [FridgeLoad.Error]. Null uses the generic copy. */
  val errorMessage: String? = null,
  val query: String = "",
  val items: List<FridgeItemUi> = emptyList(),
  /**
   * In-place add/edit draft. Remains set while [cameraOpen] is true so Back
   * can restore the same name, quantity, expiry, and photo.
   * Null id creates an item; a non-null id edits that item.
   */
  val draft: ItemDraft? = null,
  /** When true the fridge and editor are replaced by the camera slot. */
  val cameraOpen: Boolean = false,
  /** When true the compact photo source menu is shown. */
  val photoSourceOpen: Boolean = false,
)

enum class FridgeLoad {
  Loading,
  Ready,
  Error,
}

/** One fridge row. [id] is stable across edits and quantity changes. */
data class FridgeItemUi(
  val id: String,
  val name: String,
  val quantity: Int,
  /** Local epoch day (`LocalDate.toEpochDay()`), or null when there is no expiry. */
  val expiryEpochDay: Long?,
  /**
   * Private photo reference, or null. A readable file path or `content:` / `file:`
   * URI is shown as a thumbnail. Anything else falls back to a monogram.
   */
  val photoReference: String?,
)

/**
 * Whether taking this shortcut would finish a partly typed [name] rather than replace it. The
 * editor narrows its lane to the shortcuts that would, because a lane narrowed to something a tap
 * cannot finish reads as a promise it does not keep; a name typed whole is the user's own, and a
 * shortcut's own name is already there, so neither gains anything from a tap.
 */
fun FoodPreset.completes(typed: String): Boolean =
  typed.isNotEmpty() && name.length > typed.length && name.startsWith(typed, ignoreCase = true)

/**
 * The shortcuts a tap would finish [typed] with, which is what the lane narrows to and the one case
 * where a tap replaces a name the user typed. None while [typed] is already a shortcut's whole name,
 * even when another shortcut's name starts with it (a preset renamed in Settings can do that): the
 * lane then says a tap only sets the date, and the tap has to keep that promise.
 */
fun List<FoodPreset>.completing(typed: String): List<FoodPreset> {
  val name = typed.trim()
  if (any { it.name.equals(name, ignoreCase = true) }) return emptyList()
  return filter { it.completes(name) }
}

/** Where an expiry suggestion came from, when an AI suggestion was applied. */
enum class ExpiryProvenance {
  Detected,
  Estimated,
}

/** Photo analysis state for a draft. Success lands on the draft fields with [ItemDraft.expiryProvenance] set. */
sealed interface PhotoAnalysisUi {
  data object Idle : PhotoAnalysisUi
  data object Analyzing : PhotoAnalysisUi
  data class Failed(val message: String? = null) : PhotoAnalysisUi
  data object Unavailable : PhotoAnalysisUi
}

/** Controlled editor fields. Photo changes go through [FridgeCallbacks], not a copied draft. */
data class ItemDraft(
  val id: String?,
  val name: String,
  val quantity: Int,
  val expiryEpochDay: Long?,
  val photoReference: String?,
  val saving: Boolean = false,
  /** Recoverable save problem. Shown on Name, not under Photo. */
  val errorMessage: String? = null,
  /** Neutral photo problem. Shown under Photo, separate from [errorMessage]. */
  val photoMessage: String? = null,
  /**
   * Preset that filled this draft, or null. Set when a preset is applied and cleared as soon as
   * the user edits the name or the date, so a filled draft is never silently reset.
   */
  val presetId: String? = null,
  /**
   * True while the name in this draft is the preset's own rather than something the user wrote. A
   * tap on another preset may replace that name, which is what the shortcut hint says it does.
   */
  val nameFromPreset: Boolean = false,
  /** Photo AI state. Stays [PhotoAnalysisUi.Idle] for typed drafts without a photo. */
  val analysis: PhotoAnalysisUi = PhotoAnalysisUi.Idle,
  /** Set when an AI suggestion filled the expiry. Cleared when the user edits it. */
  val expiryProvenance: ExpiryProvenance? = null,
  /** Assumptions behind an estimate, shown with the provenance when present. */
  val analysisNote: String? = null,
)

/**
 * Callbacks the fridge screen invokes. Inline quantity is for a committed row.
 * Draft quantity, name, and expiry go through [onDraftChange] and are not saved
 * until [onSave]. Photo actions are separate so the caller can stage, pick, or
 * discard a file without the screen touching storage.
 */
data class FridgeCallbacks(
  val onQueryChange: (String) -> Unit,
  val onRetry: () -> Unit,
  val onAdd: () -> Unit,
  val onEdit: (id: String) -> Unit,
  val onQuantityChange: (id: String, quantity: Int) -> Unit,
  val onDelete: (id: String) -> Unit,
  val onDraftChange: (ItemDraft) -> Unit,
  val onDismissEditor: () -> Unit,
  val onSave: () -> Unit,
  val onTakePhoto: () -> Unit,
  val onPickPhoto: () -> Unit,
  val onRemovePhoto: () -> Unit,
  /** Opens the compact photo source menu. */
  val onAddPhoto: () -> Unit = {},
  /** Closes the photo source menu. */
  val onDismissPhotoSource: () -> Unit = {},
  /** Runs cloud photo analysis on the draft photo. Suggestions stay editable until Save. */
  val onAnalyzePhoto: () -> Unit = {},
  /** Header action that pushes the settings destination. */
  val onOpenSettings: () -> Unit = {},
  /** Fills the new draft from a premade food. Saves nothing by itself. */
  val onPresetSelected: (preset: FoodPreset) -> Unit = {},
)
