package com.dwk.yumgo

import com.dwk.yumgo.data.ExpirySource
import com.dwk.yumgo.data.PhotoAnalysisResult
import java.time.LocalDate

/**
 * Canned analyzer answers for photo AI workflow tests. Dates stay clear of the relative labels
 * the UI renders (today, tomorrow, in N days), so assertions key off the provenance strings and
 * the expiry row instead of locale-dependent date text.
 */
object PhotoAiTestFixtures {
  /** A clearly printed expiry, far enough out to render as a plain date. */
  val detectedExpiry: LocalDate = LocalDate.now().plusDays(23)

  /** A shelf-life estimate with its assumptions attached. */
  val estimatedExpiry: LocalDate = LocalDate.now().plusDays(14)

  const val ESTIMATE_NOTE = "Unopened yogurt typically lasts about two weeks chilled."

  fun detected(): PhotoAnalysisResult =
    PhotoAnalysisResult.Success(
      name = "Whole Milk",
      expiry = detectedExpiry,
      expirySource = ExpirySource.Detected,
      note = null,
    )

  fun estimated(): PhotoAnalysisResult =
    PhotoAnalysisResult.Success(
      name = "Greek Yogurt",
      expiry = estimatedExpiry,
      expirySource = ExpirySource.Estimated,
      note = ESTIMATE_NOTE,
    )

  /** Only a packing date is visible: no expiry and no provenance, with an explanation. */
  fun ambiguous(): PhotoAnalysisResult =
    PhotoAnalysisResult.Success(
      name = "Canned Beans",
      expiry = null,
      expirySource = ExpirySource.None,
      note = "Only a packing date is visible.",
    )

  /** Stands in for offline and server failures; the UI shows its own recoverable message. */
  fun failure(): PhotoAnalysisResult = PhotoAnalysisResult.Failure("network down")

  /** Stands in for a build without an API key. */
  fun unavailable(): PhotoAnalysisResult = PhotoAnalysisResult.Unavailable
}
