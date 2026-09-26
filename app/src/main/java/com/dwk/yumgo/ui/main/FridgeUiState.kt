package com.dwk.yumgo.ui.main

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
  /** Header action that pushes the settings destination. */
  val onOpenSettings: () -> Unit = {},
)
