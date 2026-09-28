package com.dwk.yumgo

import com.dwk.yumgo.data.PhotoAnalysisRepository
import com.dwk.yumgo.data.PhotoAnalysisResult
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Deterministic [PhotoAnalysisRepository] for workflow tests. It never touches the network and
 * never calls OpenRouter: every answer comes from [result].
 *
 * Install it before tapping Analyze with
 * `PhotoStoreHolder.analyzerForTests = fake`, and clear it after the test. Recorded refs let a
 * test assert the analyzer received the staged (`staged/…`) reference, not a display path.
 * Set [gate] to hold an analysis in flight (progress, stale results, recreation); complete the
 * gate to let it finish.
 */
class FakePhotoAnalyzer(
  @Volatile var result: PhotoAnalysisResult,
  @Volatile var delayMs: Long = 0L,
) : PhotoAnalysisRepository {
  private val seen = Collections.synchronizedList(mutableListOf<String>())

  /** Refs passed to [analyze], in call order. */
  val seenRefs: List<String>
    get() = seen.toList()

  /** Number of [analyze] calls so far. */
  val calls: Int
    get() = seen.size

  /** When non-null, [analyze] suspends here until the test completes it. */
  @Volatile var gate: CompletableDeferred<Unit>? = null

  override suspend fun analyze(photoRef: String): PhotoAnalysisResult {
    seen.add(photoRef)
    gate?.await()
    if (delayMs > 0L) delay(delayMs)
    return result
  }
}
