package com.dwk.yumgo.data

import android.content.Context
import com.dwk.yumgo.widget.FridgeWidgetUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

/**
 * Offline fridge CRUD.
 *
 * [items] emits non-deleted rows, soonest expiry first and undated items last.
 * Collect it off the caller that mutates only if you use a different instance: share one
 * [OfflineFridgeRepository] so the list updates after that instance writes.
 *
 * Deletes are hidden, not destroyed, until [undoDelete]. Photo references on hidden rows stay
 * in [retainedPhotoRefs] so undo can still show the picture.
 *
 * Failures return [Result] and leave existing rows in place. [items] can throw
 * [FridgeStoreException] if a read fails; it does not replace the database.
 *
 * Every successful mutation also redraws the placed home-screen widgets. That is a side effect of a
 * committed write, never a condition for it, so a widget that cannot be reached leaves the [Result]
 * exactly as the store returned it.
 */
interface FridgeRepository {
  val items: Flow<List<FridgeItem>>

  suspend fun add(item: NewFridgeItem): Result<FridgeItem>

  suspend fun update(item: FridgeItem): Result<FridgeItem>

  /** Inline quantity edit. Quantity must be at least 1. Does not revive a deleted item. */
  suspend fun setQuantity(id: String, quantity: Int): Result<FridgeItem>

  /** Hides [id] from [items] and keeps the row for undo. Idempotent if it is already hidden. */
  suspend fun delete(id: String): Result<Unit>

  /** Shows a hidden item again. Idempotent if it is already visible. */
  suspend fun undoDelete(id: String): Result<FridgeItem>

  /** Visible item, or null when the id is unknown or currently hidden. */
  suspend fun get(id: String): Result<FridgeItem?>

  /**
   * Photo references that still belong to a row, including hidden deletes.
   * Cleanup must not remove these; draft files that were never saved are not included.
   */
  suspend fun retainedPhotoRefs(): Result<Set<String>>

  /** Local changes waiting for a future sync, including hidden deletes. Not a sync client. */
  suspend fun pendingChanges(): Result<List<PendingFridgeChange>>
}

/** Persistence failure that did not delete or recreate the database. */
class FridgeStoreException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/**
 * App-scoped repository. Construct once and pass the same instance into the fridge ViewModel.
 */
class OfflineFridgeRepository(context: Context) : FridgeRepository {
  private val appContext = context.applicationContext
  private val store = LocalFridgeStore(appContext)

  override val items: Flow<List<FridgeItem>> = store.items.flowOn(Dispatchers.IO)

  override suspend fun add(item: NewFridgeItem): Result<FridgeItem> =
    runStore { store.insert(item) }.also { notifyWidgetsOnCommit(it) }

  override suspend fun update(item: FridgeItem): Result<FridgeItem> =
    runStore { store.update(item) }.also { notifyWidgetsOnCommit(it) }

  override suspend fun setQuantity(id: String, quantity: Int): Result<FridgeItem> =
    runStore { store.setQuantity(id, quantity) }.also { notifyWidgetsOnCommit(it) }

  override suspend fun delete(id: String): Result<Unit> =
    runStore { store.softDelete(id) }.also { notifyWidgetsOnCommit(it) }

  override suspend fun undoDelete(id: String): Result<FridgeItem> =
    runStore { store.undoDelete(id) }.also { notifyWidgetsOnCommit(it) }

  override suspend fun get(id: String): Result<FridgeItem?> = runStore { store.get(id) }

  override suspend fun retainedPhotoRefs(): Result<Set<String>> = runStore { store.retainedPhotoRefs() }

  override suspend fun pendingChanges(): Result<List<PendingFridgeChange>> = runStore { store.pendingChanges() }

  /** Redraws the widget after a write, without letting the widget decide the write's fate. */
  private fun notifyWidgetsOnCommit(result: Result<*>) {
    if (result.isSuccess) FridgeWidgetUpdates.itemsChanged(appContext)
  }
}

private suspend fun <T> runStore(block: suspend () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Throwable) {
    Result.failure(error.asFridgeFailure())
  }

internal fun Throwable.asFridgeFailure(): FridgeStoreException =
  when (this) {
    is FridgeStoreException -> this
    is CancellationException -> throw this
    else -> FridgeStoreException(message ?: "Fridge database failed", this)
  }
