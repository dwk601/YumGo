package com.dwk.yumgo.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Private SQLite copy of the fridge.
 *
 * Schema changes go through [FridgeOpenHelper] one version at a time. Migrations may only add
 * structure. They must not drop tables, delete rows, or recreate the file.
 *
 * Database work is serialized and runs on [Dispatchers.IO]. [items] re-reads after each
 * successful mutation on this store. Hidden rows stay in the table with [COL_DELETED_AT] set.
 */
internal class LocalFridgeStore(context: Context) {
  private val appContext = context.applicationContext
  private val gate = ReentrantLock()
  private val generation = MutableStateFlow(0L)
  private var helper: FridgeOpenHelper? = null

  val items: Flow<List<FridgeItem>> =
    flow {
      generation.collect {
        emit(readVisible())
      }
    }.distinctUntilChanged()

  suspend fun insert(item: NewFridgeItem): FridgeItem {
    val stored =
      onIo {
        val name = normalizeName(item.name)
        val quantity = normalizeQuantity(item.quantity)
        val photoRef = normalizePhoto(item.photoRef)
        val created =
          FridgeItem(
            id = UUID.randomUUID().toString(),
            name = name,
            quantity = quantity,
            expiresOn = item.expiresOn,
            photoRef = photoRef,
          )
        inTransaction {
          insertItem(created, updatedAt = now())
        }
        created
      }
    notifyChanged()
    return stored
  }

  suspend fun update(item: FridgeItem): FridgeItem {
    val stored =
      onIo {
        val id = requireId(item.id)
        val normalized =
          item.copy(
            id = id,
            name = normalizeName(item.name),
            quantity = normalizeQuantity(item.quantity),
            photoRef = normalizePhoto(item.photoRef),
          )
        inTransaction {
          val values =
            itemValues(
              name = normalized.name,
              quantity = normalized.quantity,
              expiresOn = normalized.expiresOn,
              photoRef = normalized.photoRef,
              updatedAt = now(),
            )
          if (updateVisible(id, values) != 1) missingItem()
          normalized
        }
      }
    notifyChanged()
    return stored
  }

  suspend fun setQuantity(id: String, quantity: Int): FridgeItem {
    val stored =
      onIo {
        val key = requireId(id)
        val qty = normalizeQuantity(quantity)
        inTransaction {
          val current = findById(key, visibleOnly = true) ?: missingItem()
          if (current.quantity != qty) {
            val values =
              ContentValues().apply {
                put(COL_QUANTITY, qty)
                put(COL_PENDING, PENDING)
                put(COL_UPDATED_AT, now())
              }
            if (updateVisible(key, values) != 1) missingItem()
          }
          current.copy(quantity = qty)
        }
      }
    notifyChanged()
    return stored
  }

  suspend fun softDelete(id: String) {
    onIo {
      val key = requireId(id)
      inTransaction {
        val now = now()
        val values =
          ContentValues().apply {
            put(COL_DELETED_AT, now)
            put(COL_PENDING, PENDING)
            put(COL_UPDATED_AT, now)
          }
        if (updateVisible(key, values) == 0 && findById(key, visibleOnly = false) == null) {
          missingItem()
        }
      }
    }
    notifyChanged()
  }

  suspend fun undoDelete(id: String): FridgeItem {
    val stored =
      onIo {
        val key = requireId(id)
        inTransaction {
          val now = now()
          val values =
            ContentValues().apply {
              putNull(COL_DELETED_AT)
              put(COL_PENDING, PENDING)
              put(COL_UPDATED_AT, now)
            }
          val restored = update(TABLE, values, "$COL_ID = ? AND $COL_DELETED_AT IS NOT NULL", arrayOf(key))
          if (restored == 0 && findById(key, visibleOnly = false) == null) missingItem()
          findById(key, visibleOnly = true) ?: missingItem()
        }
      }
    notifyChanged()
    return stored
  }

  suspend fun get(id: String): FridgeItem? =
    onIo {
      findById(requireId(id), visibleOnly = true)
    }

  suspend fun readVisible(): List<FridgeItem> =
    onIo {
      database()
        .query(TABLE, ITEM_COLUMNS, "$COL_DELETED_AT IS NULL", null, null, null, ORDER_VISIBLE)
        .use { cursor ->
          buildList {
            while (cursor.moveToNext()) add(cursor.toItem())
          }
        }
    }

  suspend fun retainedPhotoRefs(): Set<String> =
    onIo {
      database()
        .rawQuery("SELECT $COL_PHOTO FROM $TABLE WHERE $COL_PHOTO IS NOT NULL", null)
        .use { cursor ->
          buildSet {
            val index = cursor.getColumnIndexOrThrow(COL_PHOTO)
            while (cursor.moveToNext()) {
              if (!cursor.isNull(index)) add(cursor.getString(index))
            }
          }
        }
    }

  suspend fun pendingChanges(): List<PendingFridgeChange> =
    onIo {
      database()
        .query(TABLE, PENDING_COLUMNS, "$COL_PENDING = $PENDING", null, null, null, ORDER_PENDING)
        .use { cursor ->
          buildList {
            val deletedIndex = cursor.getColumnIndexOrThrow(COL_DELETED_AT)
            val updatedIndex = cursor.getColumnIndexOrThrow(COL_UPDATED_AT)
            while (cursor.moveToNext()) {
              add(
                PendingFridgeChange(
                  item = cursor.toItem(),
                  deletedAtMillis = if (cursor.isNull(deletedIndex)) null else cursor.getLong(deletedIndex),
                  updatedAtMillis = cursor.getLong(updatedIndex),
                ),
              )
            }
          }
        }
    }

  private suspend fun <T> onIo(block: SQLiteDatabase.() -> T): T =
    withContext(Dispatchers.IO) {
      exclusive {
        block(database())
      }
    }

  /**
   * Notifies observers only after [onIo] has released the database lock and committed.
   * Doing it inside the lock lets a synchronous collector re-enter and observe a half-written row.
   */
  private fun notifyChanged() {
    generation.update { it + 1 }
  }

  private fun <T> exclusive(block: () -> T): T {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Fridge database work must not run on the main thread" }
    gate.lock()
    try {
      return block()
    } catch (error: Throwable) {
      if (error is Error) throw error
      throw error.asFridgeFailure()
    } finally {
      gate.unlock()
    }
  }

  private fun database(): SQLiteDatabase {
    val open = helper ?: FridgeOpenHelper(appContext).also { helper = it }
    return open.writableDatabase
  }

  private fun <T> inTransaction(block: SQLiteDatabase.() -> T): T {
    val db = database()
    db.beginTransaction()
    try {
      val value = block(db)
      db.setTransactionSuccessful()
      return value
    } finally {
      db.endTransaction()
    }
  }

  private fun SQLiteDatabase.insertItem(item: FridgeItem, updatedAt: Long) {
    val values =
      itemValues(
        name = item.name,
        quantity = item.quantity,
        expiresOn = item.expiresOn,
        photoRef = item.photoRef,
        updatedAt = updatedAt,
      ).apply {
        put(COL_ID, item.id)
        putNull(COL_DELETED_AT)
      }
    insertOrThrow(TABLE, null, values)
  }

  private fun SQLiteDatabase.updateVisible(id: String, values: ContentValues): Int =
    update(TABLE, values, "$COL_ID = ? AND $COL_DELETED_AT IS NULL", arrayOf(id))

  private fun SQLiteDatabase.findById(id: String, visibleOnly: Boolean): FridgeItem? {
    val selection = if (visibleOnly) "$COL_ID = ? AND $COL_DELETED_AT IS NULL" else "$COL_ID = ?"
    return query(TABLE, ITEM_COLUMNS, selection, arrayOf(id), null, null, null).use { cursor ->
      if (!cursor.moveToFirst()) null else cursor.toItem()
    }
  }

  private fun itemValues(
    name: String,
    quantity: Int,
    expiresOn: LocalDate?,
    photoRef: String?,
    updatedAt: Long,
  ): ContentValues =
    ContentValues().apply {
      put(COL_NAME, name)
      put(COL_QUANTITY, quantity)
      if (expiresOn == null) putNull(COL_EXPIRY) else put(COL_EXPIRY, expiresOn.toEpochDay())
      if (photoRef == null) putNull(COL_PHOTO) else put(COL_PHOTO, photoRef)
      put(COL_PENDING, PENDING)
      put(COL_UPDATED_AT, updatedAt)
    }

  private fun Cursor.toItem(): FridgeItem {
    val expiryIndex = getColumnIndexOrThrow(COL_EXPIRY)
    val photoIndex = getColumnIndexOrThrow(COL_PHOTO)
    return FridgeItem(
      id = getString(getColumnIndexOrThrow(COL_ID)),
      name = getString(getColumnIndexOrThrow(COL_NAME)),
      quantity = getInt(getColumnIndexOrThrow(COL_QUANTITY)),
      expiresOn = if (isNull(expiryIndex)) null else LocalDate.ofEpochDay(getLong(expiryIndex)),
      photoRef = if (isNull(photoIndex)) null else getString(photoIndex),
    )
  }

  private fun missingItem(): Nothing = throw FridgeStoreException("Item not found")

  private fun now(): Long = System.currentTimeMillis()

  /**
   * Opens [DATABASE_NAME] in the app-private databases directory.
   * A corrupt file is closed and left on disk. Upgrade and downgrade never delete it.
   */
  private class FridgeOpenHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, SCHEMA_VERSION, KeepDatabaseCorruptionHandler) {
    init {
      setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
      db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL(CREATE_ITEMS)
      db.execSQL(CREATE_VISIBLE_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
      var version = oldVersion
      while (version < newVersion) {
        val step =
          UPGRADES[version]
            ?: throw SQLiteException(
              "No non-destructive migration from fridge schema $version. Refusing to recreate the database.",
            )
        step(db)
        version += 1
      }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
      throw SQLiteException(
        "Refusing to downgrade fridge schema from $oldVersion to $newVersion without deleting data.",
      )
    }
  }

  private object KeepDatabaseCorruptionHandler : DatabaseErrorHandler {
    override fun onCorruption(dbObj: SQLiteDatabase) {
      // The platform default deletes the file. Leave the user's rows on disk.
      try {
        dbObj.close()
      } catch (_: SQLException) {
        // Already closing. The file stays where it is.
      } catch (_: IllegalStateException) {
        // Already closed.
      }
    }
  }

  companion object {
    const val DATABASE_NAME = "yumgo_fridge.db"
    const val SCHEMA_VERSION = 1

    private const val TABLE = "fridge_item"
    private const val COL_ID = "id"
    private const val COL_NAME = "name"
    private const val COL_QUANTITY = "quantity"
    private const val COL_EXPIRY = "expiry_epoch_day"
    private const val COL_PHOTO = "photo_ref"
    private const val COL_PENDING = "pending"
    private const val COL_DELETED_AT = "deleted_at"
    private const val COL_UPDATED_AT = "updated_at"
    private const val PENDING = 1

    private val ITEM_COLUMNS = arrayOf(COL_ID, COL_NAME, COL_QUANTITY, COL_EXPIRY, COL_PHOTO)
    private val PENDING_COLUMNS = arrayOf(COL_ID, COL_NAME, COL_QUANTITY, COL_EXPIRY, COL_PHOTO, COL_DELETED_AT, COL_UPDATED_AT)
    private const val ORDER_VISIBLE =
      "CASE WHEN $COL_EXPIRY IS NULL THEN 1 ELSE 0 END, $COL_EXPIRY ASC, $COL_NAME COLLATE NOCASE ASC, $COL_ID ASC"
    private const val ORDER_PENDING = "$COL_UPDATED_AT ASC, $COL_ID ASC"

    private const val CREATE_ITEMS =
      """
      CREATE TABLE $TABLE (
        $COL_ID TEXT NOT NULL PRIMARY KEY,
        $COL_NAME TEXT NOT NULL CHECK (length(trim($COL_NAME)) > 0),
        $COL_QUANTITY INTEGER NOT NULL CHECK ($COL_QUANTITY >= 1),
        $COL_EXPIRY INTEGER,
        $COL_PHOTO TEXT,
        $COL_PENDING INTEGER NOT NULL CHECK ($COL_PENDING IN (0, 1)) DEFAULT $PENDING,
        $COL_DELETED_AT INTEGER,
        $COL_UPDATED_AT INTEGER NOT NULL
      )
      """

    private const val CREATE_VISIBLE_INDEX =
      "CREATE INDEX idx_fridge_item_visible ON $TABLE ($COL_DELETED_AT, $COL_EXPIRY, $COL_NAME)"

    /**
     * Keyed by the schema version being left. Each step may add tables, columns, or indexes only.
     */
    private val UPGRADES: Map<Int, (SQLiteDatabase) -> Unit> = emptyMap()
  }
}

private fun normalizeName(name: String): String {
  val trimmed = name.trim()
  if (trimmed.isEmpty()) throw FridgeStoreException("Item name is empty")
  return trimmed
}

private fun normalizeQuantity(quantity: Int): Int {
  if (quantity < 1) throw FridgeStoreException("Quantity must be at least 1")
  return quantity
}

private fun normalizePhoto(photoRef: String?): String? {
  val trimmed = photoRef?.trim()
  return if (trimmed.isNullOrEmpty()) null else trimmed
}

private fun requireId(id: String): String {
  if (id.isEmpty()) throw FridgeStoreException("Item id is empty")
  return id
}
