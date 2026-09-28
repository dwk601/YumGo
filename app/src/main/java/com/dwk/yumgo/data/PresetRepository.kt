package com.dwk.yumgo.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Premade food defaults the add sheet can offer.
 *
 * [presets] starts from [FoodPresetCatalog] with the user's saved edits applied, so a fresh
 * install and a returning one both open with food listed. Values are suggestions for future
 * drafts; saving one never rewrites items already in the fridge.
 *
 * Failures return [Result] and leave the last known list in place.
 */
interface PresetRepository {
  val presets: StateFlow<List<FoodPreset>>

  /** Replaces the preset with the same id, or appends it if the id is new. */
  suspend fun update(preset: FoodPreset): Result<Unit>
}

/** Rejected preset edit, or a write that did not reach preferences. */
class PresetStoreException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Presets in their own preference file, separate from the fridge database and its schema.
 *
 * Only the presets the user actually changed are stored, so a food added to [FoodPresetCatalog]
 * later shows up with its default name and duration, a catalog fix reaches everyone who left the
 * entry alone, and ids this build does not know are still listed.
 */
internal class StoredPresetRepository(context: Context) : PresetRepository {
  private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
  private val writes = Mutex()
  private val state = MutableStateFlow(read())

  override val presets: StateFlow<List<FoodPreset>> = state.asStateFlow()

  override suspend fun update(preset: FoodPreset): Result<Unit> =
    runSettings {
      val cleaned = cleanPreset(preset)
      // Read, write, and publish under one lock: two saves in a row must both survive.
      writes.withLock {
        val current = state.value
        val next = if (current.any { it.id == cleaned.id }) current.map { if (it.id == cleaned.id) cleaned else it } else current + cleaned
        write(editedOnly(next))
        state.value = next
      }
    }

  /** Clears saved edits and republishes the bundled catalog. Test-only. */
  fun clearForTests() {
    preferences.edit().clear().commit()
    state.value = FoodPresetCatalog.entries
  }

  private fun write(list: List<FoodPreset>) {
    val array = JSONArray()
    list.forEach { preset ->
      array.put(
        JSONObject().apply {
          put(KEY_ID, preset.id)
          put(KEY_NAME, preset.name)
          put(KEY_DAYS, preset.expiryDays)
        },
      )
    }
    if (!preferences.edit().putString(KEY_PRESETS, array.toString()).commit()) {
      throw PresetStoreException("Couldn't save the preset")
    }
  }

  private fun read(): List<FoodPreset> {
    val stored = decode(preferences.getString(KEY_PRESETS, null))
    if (stored.isEmpty()) return FoodPresetCatalog.entries
    val overrides = stored.associateBy(FoodPreset::id).toMutableMap()
    val merged = FoodPresetCatalog.entries.map { default -> overrides.remove(default.id) ?: default }
    val extras = overrides.values.sortedBy { it.name.lowercase() }
    return merged + extras
  }

  private fun decode(raw: String?): List<FoodPreset> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
      val array = JSONArray(raw)
      buildList {
        for (index in 0 until array.length()) {
          val entry = array.optJSONObject(index) ?: continue
          val id = entry.optString(KEY_ID).trim()
          if (id.isEmpty()) continue
          val name = entry.optString(KEY_NAME).trim()
          val days = entry.optInt(KEY_DAYS, -1)
          if (name.isEmpty() || days !in 0..MaxPresetExpiryDays) continue
          add(FoodPreset(id = id, name = name, expiryDays = days))
        }
      }
    } catch (_: Throwable) {
      // Unreadable preferences fall back to the bundled catalog instead of losing the screen.
      emptyList()
    }
  }

  private fun cleanPreset(preset: FoodPreset): FoodPreset {
    val id = preset.id.trim()
    if (id.isEmpty()) throw PresetStoreException("Preset id is empty")
    val name = preset.name.trim()
    if (name.isEmpty()) throw PresetStoreException("Preset name is empty")
    if (name.length > MaxPresetNameLength) throw PresetStoreException("Preset name is too long")
    if (preset.expiryDays !in 0..MaxPresetExpiryDays) throw PresetStoreException("Preset expiry is out of range")
    return FoodPreset(id = id, name = name, expiryDays = preset.expiryDays)
  }

  companion object {
    /** Preference file the settings repositories own. Not the fridge database. */
    const val FILE_NAME = "yumgo_presets"

    private const val KEY_PRESETS = "presets"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_DAYS = "days"
  }
}

private suspend fun <T> runSettings(block: suspend () -> T): Result<T> =
  try {
    withContext(Dispatchers.IO) { Result.success(block()) }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Throwable) {
    Result.failure(if (error is PresetStoreException) error else PresetStoreException(error.message ?: "Couldn't save the preset", error))
  }

/** Keeps only the entries that differ from the bundled catalog, so defaults can still change. */
private fun editedOnly(list: List<FoodPreset>): List<FoodPreset> {
  val defaults = FoodPresetCatalog.entries.associateBy(FoodPreset::id)
  return list.filter { candidate -> defaults[candidate.id] != candidate }
}
