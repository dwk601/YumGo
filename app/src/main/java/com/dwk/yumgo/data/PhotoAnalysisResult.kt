package com.dwk.yumgo.data

import java.time.LocalDate

/**
 * Where a suggested expiry date came from. The review screen labels this provenance and never
 * presents estimates as guaranteed safe-use dates.
 */
enum class ExpirySource {
  /** A use-by / best-before / expires-on date clearly printed on the packaging in the photo. */
  Detected,

  /** No readable date; a typical shelf-life estimate whose assumptions ride in [PhotoAnalysisResult.Success.note]. */
  Estimated,

  /** No date suggestion at all. */
  None,
}

/**
 * Outcome of analyzing one staged fridge photo. Analysis only produces editable guesses;
 * it never writes to the fridge.
 */
sealed interface PhotoAnalysisResult {
  /**
   * Editable guesses for the review screen. Nulls mean the model could not tell.
   * [expiry] is non-null only with [ExpirySource.Detected] or [ExpirySource.Estimated];
   * [expirySource] is [ExpirySource.None] when [expiry] is null.
   */
  data class Success(
    val name: String?,
    val expiry: LocalDate?,
    val expirySource: ExpirySource,
    val note: String?,
  ) : PhotoAnalysisResult

  /** No API key was baked into this build, so nothing was uploaded. */
  data object Unavailable : PhotoAnalysisResult

  /** Recoverable failure: unreadable photo, timeout, network error, or malformed model response. */
  data class Failure(val reason: String) : PhotoAnalysisResult
}
