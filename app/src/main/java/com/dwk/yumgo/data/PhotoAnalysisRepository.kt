package com.dwk.yumgo.data

/**
 * Cloud guess for one staged photo.
 *
 * Implementations must not write to the fridge, must not upload when no API key is configured,
 * and must never log keys or photo bytes.
 */
interface PhotoAnalysisRepository {
  /** [photoRef] is the staged photo reference used by [PhotoStore] / [FridgeItem]. */
  suspend fun analyze(photoRef: String): PhotoAnalysisResult
}
