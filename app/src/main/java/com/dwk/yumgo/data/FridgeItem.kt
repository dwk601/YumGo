package com.dwk.yumgo.data

import java.time.LocalDate

/**
 * One fridge item.
 *
 * [id] is stable for the life of the row, including while it is hidden after delete.
 * [photoRef] is an opaque private-file reference owned by photo storage, not bytes and not a remote URL.
 * Sync bookkeeping stays out of this type so the UI can edit it without dropping that metadata.
 */
data class FridgeItem(
  val id: String,
  val name: String,
  val quantity: Int,
  val expiresOn: LocalDate?,
  val photoRef: String?,
)

/** Fields the user can choose before an id exists. */
data class NewFridgeItem(
  val name: String,
  val quantity: Int,
  val expiresOn: LocalDate? = null,
  val photoRef: String? = null,
)

/**
 * A local create, edit, or delete that a future sync could push.
 * This is stored metadata only: nothing here contacts a server.
 *
 * [deletedAtMillis] is non-null when the change is a hide/delete. The item stays readable so undo
 * and a later sync can still see its name, quantity, expiry, and photo reference.
 */
data class PendingFridgeChange(
  val item: FridgeItem,
  val deletedAtMillis: Long?,
  val updatedAtMillis: Long,
)
