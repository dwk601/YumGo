package com.dwk.yumgo.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Encodes one staged [PhotoStore] photo for the cloud analyzer.
 *
 * The staged file is decoded with a size bound, rotated per its EXIF orientation, scaled down,
 * and re-encoded as a fresh JPEG. Re-encoding drops EXIF and other metadata (location, device),
 * so only pixels leave the device. Callers must still upload only after the user asks for
 * analysis.
 */
internal object PhotoAnalysisImage {
  /** Longest side of the uploaded image: small enough for fast uploads, large enough to read labels. */
  const val MAX_DIMENSION_PX = 1024

  /** Retry bound when the first encoding is still too large. */
  const val FALLBACK_DIMENSION_PX = 768

  /** Refuse to build an upload larger than this. Resized label photos land far below it. */
  const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024

  private const val FIRST_QUALITY = 85
  private const val FALLBACK_QUALITY = 70

  /** `data:image/jpeg;base64,...` upload body, or a failure when the staged photo is unreadable. */
  suspend fun prepare(photos: PhotoStore, photoRef: String): Result<String> =
    withContext(Dispatchers.IO) {
      try {
        ensureActive()
        val file = photos.existingFile(photoRef)
          ?: return@withContext Result.failure(PhotoStoreException("Couldn't read the photo"))
        ensureActive()
        val bitmap = decodeOriented(file.absolutePath)
          ?: return@withContext Result.failure(PhotoStoreException("Couldn't read the photo"))
        try {
          ensureActive()
          val bytes = encodeBounded(bitmap)
            ?: return@withContext Result.failure(PhotoStoreException("Couldn't prepare the photo"))
          Result.success("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
        } finally {
          if (!bitmap.isRecycled) bitmap.recycle()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: OutOfMemoryError) {
        Result.failure(PhotoStoreException("Couldn't prepare the photo"))
      } catch (_: Exception) {
        Result.failure(PhotoStoreException("Couldn't read the photo"))
      }
    }

  /** Bounded decode plus EXIF orientation correction. Null when the file is not an image. */
  private fun decodeOriented(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > MAX_DIMENSION_PX ||
      bounds.outHeight / sampleSize > MAX_DIMENSION_PX
    ) {
      sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = BitmapFactory.decodeFile(path, options) ?: return null
    return applyExifOrientation(path, decoded)
  }

  /** Rotates/flips per EXIF so the upload matches what the camera saw. Recycles the input when replaced. */
  private fun applyExifOrientation(path: String, bitmap: Bitmap): Bitmap {
    val orientation =
      try {
        java.io.FileInputStream(path).use { input ->
          ExifInterface(input).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
          )
        }
      } catch (_: Exception) {
        return bitmap
      }
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      else -> return bitmap
    }
    return try {
      val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (rotated !== bitmap) bitmap.recycle()
      rotated
    } catch (_: OutOfMemoryError) {
      bitmap
    } catch (_: Exception) {
      bitmap
    }
  }

  /** Scales to the upload bound and compresses metadata-free, retrying smaller once when too large. */
  private fun encodeBounded(bitmap: Bitmap): ByteArray? {
    val scaled = scaleToBound(bitmap, MAX_DIMENSION_PX)
    try {
      encodeJpeg(scaled, FIRST_QUALITY)
        ?.takeIf { it.isNotEmpty() && it.size <= MAX_UPLOAD_BYTES }
        ?.let { return it }
      val fallback = scaleToBound(bitmap, FALLBACK_DIMENSION_PX)
      try {
        return encodeJpeg(fallback, FALLBACK_QUALITY)
          ?.takeIf { it.isNotEmpty() && it.size <= MAX_UPLOAD_BYTES }
      } finally {
        if (fallback !== scaled && fallback !== bitmap && !fallback.isRecycled) fallback.recycle()
      }
    } finally {
      if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
    }
  }

  private fun scaleToBound(bitmap: Bitmap, bound: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= bound || longest <= 0) return bitmap
    val scale = bound.toFloat() / longest
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
  }

  private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray? =
    try {
      val out = ByteArrayOutputStream(bitmap.width * bitmap.height)
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) null else out.toByteArray()
    } catch (_: OutOfMemoryError) {
      null
    } catch (_: Exception) {
      null
    }
}
