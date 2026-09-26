package com.dwk.yumgo.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Private photo files for fridge items. No network and no repository.
 *
 * A staged ref looks like `staged/<uuid>.jpg`. [promote] turns it into `saved/<uuid>.jpg`.
 * Both are relative to the app's `filesDir/fridge-photos` directory.
 *
 * [cleanup] deletes unreferenced staged files and abandoned capture scratch files.
 * It keeps every ref in the caller's protected set, the active review ref from [activeReviewRef],
 * and staged files newer than [STAGED_GRACE_MS]. [cleanupSaved] is the only way to delete saved
 * files, and it also keeps every protected ref. Do not call either while capture or the picker is
 * on screen. Nothing is uploaded.
 */
class PhotoStore(context: Context) {
  private val resolver = context.applicationContext.contentResolver
  private val root = File(context.applicationContext.filesDir, ROOT_DIRECTORY)
  private val stagedDir = File(root, STAGED_DIRECTORY)
  private val savedDir = File(root, SAVED_DIRECTORY)
  private val reviewMarker = File(root, REVIEW_MARKER)
  private val lock = Any()
  private val partialsInUse = mutableSetOf<String>()

  /** Copy a picker URI into a new staged file. The content URI is not retained. */
  suspend fun stageFromUri(uri: Uri): Result<String> {
    val job = coroutineContext[kotlinx.coroutines.Job]
    return io {
      val storageFailure = ensureDirs()
      if (storageFailure != null) return@io Result.failure(storageFailure)
      val id = UUID.randomUUID().toString()
      val destination = File(stagedDir, "$id$JPEG_EXTENSION")
      try {
        val input = resolver.openInputStream(uri)
        if (input == null) {
          return@io Result.failure(PhotoStoreException("Couldn't read the photo"))
        }
        input.use { source ->
          destination.outputStream().use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var copied = 0L
            while (true) {
              if (job?.isActive == false) throw CancellationException("Photo staging cancelled")
              val read = source.read(buffer)
              if (read < 0) break
              if (copied + read > MAX_PICK_BYTES) throw PhotoRejectedException()
              output.write(buffer, 0, read)
              copied += read
            }
            output.fd.sync()
          }
        }
        if (job?.isActive == false) throw CancellationException("Photo staging cancelled")
        if (destination.length() <= 0L || destination.length() > MAX_PICK_BYTES || !hasImageBounds(destination)) {
          destination.delete()
          Result.failure(PhotoStoreException("Couldn't read the photo"))
        } else {
          Result.success(stagedRef(id))
        }
      } catch (cancelled: CancellationException) {
        destination.delete()
        throw cancelled
      } catch (_: PhotoRejectedException) {
        destination.delete()
        Result.failure(PhotoStoreException("Couldn't read the photo"))
      } catch (error: Exception) {
        destination.delete()
        Result.failure(PhotoStoreException("Couldn't read the photo", error))
      }
    }
  }

  /** Empty scratch file for [androidx.camera.core.ImageCapture]. Not a ref until [commitCaptureFile]. */
  suspend fun allocateCaptureFile(): Result<File> =
    io {
      val storageFailure = ensureDirs()
      if (storageFailure != null) {
        Result.failure(storageFailure)
      } else {
        val partial = File(stagedDir, "${UUID.randomUUID()}$JPEG_EXTENSION$PARTIAL_SUFFIX")
        if (partial.createNewFile() || partial.isFile) {
          partialsInUse.add(partial.name)
          Result.success(partial)
        } else {
          Result.failure(PhotoStoreException("Couldn't prepare the photo"))
        }
      }
    }

  /** Publish a captured scratch file as a staged ref. Deletes the scratch file if it is empty or not an image. */
  suspend fun commitCaptureFile(file: File): Result<String> =
    io {
      val partial = managedPartial(file)
      if (partial == null) {
        Result.failure(PhotoStoreException("Couldn't prepare the photo"))
      } else if (partial.length() <= 0L || !hasImageBounds(partial)) {
        partialsInUse.remove(partial.name)
        partial.delete()
        Result.failure(PhotoStoreException("Couldn't save the photo"))
      } else {
        val finalName = partial.name.removeSuffix(PARTIAL_SUFFIX)
        val destination = File(stagedDir, finalName)
        partialsInUse.remove(partial.name)
        if (!partial.renameTo(destination)) {
          partial.delete()
          Result.failure(PhotoStoreException("Couldn't save the photo"))
        } else {
          Result.success("$STAGED_DIRECTORY/$finalName")
        }
      }
    }

  /** Delete a capture scratch file. Finished and saved files are not touched. */
  suspend fun abandonCaptureFile(file: File) {
    io { releasePartialLocked(file) }
  }

  /** Same as [abandonCaptureFile], safe to call while composition is going away. */
  internal fun releasePartialNow(file: File) {
    synchronized(lock) { releasePartialLocked(file) }
  }

  /**
   * Remember the staged ref on the review screen. Pass null when review ends.
   * [cleanup] keeps this ref even if the caller does not list it. Survives process death.
   */
  suspend fun setActiveReview(ref: String?) {
    io {
      ensureDirs()
      if (ref == null) {
        reviewMarker.delete()
        return@io
      }
      val normalized = normalize(ref) ?: return@io
      reviewMarker.writeText(normalized)
    }
  }

  /** Clears the review marker without waiting. Call it only after the caller has stored the ref. */
  fun clearActiveReviewNow() {
    synchronized(lock) { reviewMarker.delete() }
  }

  /** Review ref recorded by [setActiveReview], or null when there is none or its file is gone. */
  suspend fun activeReviewRef(): String? = io { readActiveReviewLocked() }

  /**
   * Move a staged ref into saved storage.
   * Already-saved refs return themselves. The previous saved file, if any, is not deleted.
   * On failure the staged file is left in place.
   */
  suspend fun promote(ref: String): Result<String> =
    io {
      val normalized = normalize(ref) ?: return@io Result.failure(PhotoStoreException("Photo isn't staged"))
      if (normalized.startsWith("$SAVED_DIRECTORY/")) {
        val saved = File(root, normalized)
        return@io if (saved.isFile && isInside(saved, savedDir)) {
          Result.success(normalized)
        } else {
          Result.failure(PhotoStoreException("Saved photo is missing"))
        }
      }
      val source = File(root, normalized)
      if (!source.isFile || !isInside(source, stagedDir)) {
        return@io Result.failure(PhotoStoreException("Photo isn't staged"))
      }
      val storageFailure = ensureDirs()
      if (storageFailure != null) return@io Result.failure(storageFailure)
      val destination = File(savedDir, "${UUID.randomUUID()}$JPEG_EXTENSION")
      if (destination.exists()) {
        return@io Result.failure(PhotoStoreException("Couldn't save the photo"))
      }
      if (source.renameTo(destination)) {
        Result.success("$SAVED_DIRECTORY/${destination.name}")
      } else if (copyInto(source, destination)) {
        if (source.delete()) {
          Result.success("$SAVED_DIRECTORY/${destination.name}")
        } else {
          destination.delete()
          Result.failure(PhotoStoreException("Couldn't save the photo"))
        }
      } else {
        destination.delete()
        Result.failure(PhotoStoreException("Couldn't save the photo"))
      }
    }

  /**
   * Delete one staged ref. Saved refs and anything in [protected] are kept.
   * Unknown refs are ignored so a missing draft cannot wipe other photos.
   */
  suspend fun discard(ref: String, protected: Set<String> = emptySet()): Result<Unit> =
    io {
      if (matchesProtected(ref, protected)) return@io Result.success(Unit)
      val normalized = normalize(ref) ?: return@io Result.success(Unit)
      if (!normalized.startsWith("$STAGED_DIRECTORY/")) return@io Result.success(Unit)
      val file = File(root, normalized)
      if (file.isFile && isInside(file, stagedDir) && !file.delete()) {
        Result.failure(PhotoStoreException("Couldn't discard the photo"))
      } else {
        Result.success(Unit)
      }
    }

  /**
   * Delete staged files whose refs are not in [protected], except the active review ref and
   * staged files newer than [STAGED_GRACE_MS]. Also deletes capture scratch files that are not
   * in use, and scratch files older than [PARTIAL_STALE_MS] even if a dead session still marks them.
   * Saved files are not deleted. Nothing outside this store is deleted.
   */
  suspend fun cleanup(protected: Set<String>): Result<Unit> =
    io {
      if (!stagedDir.isDirectory) return@io Result.success(Unit)
      val keep = protected.mapNotNull { normalize(it) }.toMutableSet()
      readActiveReviewLocked()?.let(keep::add)
      val now = System.currentTimeMillis()
      var failed = false
      stagedDir.listFiles()?.forEach { file ->
        if (!file.isFile || !isInside(file, stagedDir)) return@forEach
        if (PARTIAL_NAME.matches(file.name)) {
          val stale = now - file.lastModified() >= PARTIAL_STALE_MS
          if (file.name !in partialsInUse || stale) {
            if (file.delete()) partialsInUse.remove(file.name) else failed = true
          }
          return@forEach
        }
        val ref = "$STAGED_DIRECTORY/${file.name}"
        if (!REF.matches(ref) || ref in keep) return@forEach
        if (now - file.lastModified() < STAGED_GRACE_MS) return@forEach
        if (!file.delete()) failed = true
      }
      if (failed) Result.failure(PhotoStoreException("Couldn't clean up photos")) else Result.success(Unit)
    }

  /**
   * Delete saved files that are not in [protected].
   * Include every repository photo ref (hidden deletes too), the current draft, and undo refs.
   * Staged files, scratch files, and every protected ref are kept. An empty set deletes every saved photo.
   */
  suspend fun cleanupSaved(protected: Set<String>): Result<Unit> =
    io {
      if (!savedDir.isDirectory) return@io Result.success(Unit)
      val keep = protected.mapNotNull { normalize(it) }.toSet()
      var failed = false
      savedDir.listFiles()?.forEach { file ->
        val ref = "$SAVED_DIRECTORY/${file.name}"
        if (!REF.matches(ref) || ref in keep || !file.isFile || !isInside(file, savedDir)) return@forEach
        if (!file.delete()) failed = true
      }
      if (failed) Result.failure(PhotoStoreException("Couldn't clean up saved photos")) else Result.success(Unit)
    }

  /** File for a staged or saved ref, or null when the ref is invalid, outside this store, or missing. */
  suspend fun existingFile(ref: String): File? =
    io {
      val normalized = normalize(ref) ?: return@io null
      val file = File(root, normalized)
      val parent = if (normalized.startsWith("$SAVED_DIRECTORY/")) savedDir else stagedDir
      if (file.isFile && isInside(file, parent)) file else null
    }

  private fun ensureDirs(): PhotoStoreException? {
    if (!stagedDir.isDirectory && !stagedDir.mkdirs()) {
      return PhotoStoreException("Couldn't prepare photo storage")
    }
    if (!savedDir.isDirectory && !savedDir.mkdirs()) {
      return PhotoStoreException("Couldn't prepare photo storage")
    }
    return null
  }

  private fun releasePartialLocked(file: File) {
    partialsInUse.remove(file.name)
    managedPartial(file)?.delete()
  }

  private fun readActiveReviewLocked(): String? {
    if (!reviewMarker.isFile) return null
    val normalized =
      try {
        normalize(reviewMarker.readText())
      } catch (_: IOException) {
        null
      } ?: return null
    val file = File(root, normalized)
    return if (file.isFile && isInside(file, stagedDir)) normalized else null
  }

  private fun hasImageBounds(file: File): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    return try {
      BitmapFactory.decodeFile(file.absolutePath, options)
      options.outWidth > 0 && options.outHeight > 0
    } catch (_: Exception) {
      false
    }
  }

  private fun managedPartial(file: File): File? {
    if (!PARTIAL_NAME.matches(file.name)) return null
    return try {
      val canonical = file.canonicalFile
      if (canonical.parentFile != stagedDir.canonicalFile) null else canonical
    } catch (_: IOException) {
      null
    }
  }

  private fun normalize(ref: String): String? {
    val trimmed = ref.trim()
    if (REF.matches(trimmed)) return trimmed
    if (!File(trimmed).isAbsolute) return null
    return try {
      val rootPath = root.canonicalFile.toPath()
      val filePath = File(trimmed).canonicalFile.toPath()
      if (!filePath.startsWith(rootPath)) return null
      rootPath.relativize(filePath).toString().replace('\\', '/').takeIf { REF.matches(it) }
    } catch (_: IOException) {
      null
    }
  }

  private fun matchesProtected(ref: String, protected: Set<String>): Boolean {
    val normalized = normalize(ref) ?: return false
    return protected.any { normalize(it) == normalized }
  }

  private fun isInside(file: File, directory: File): Boolean =
    try {
      file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())
    } catch (_: IOException) {
      false
    }

  private fun copyInto(source: File, destination: File): Boolean =
    try {
      source.inputStream().use { input ->
        destination.outputStream().use { output ->
          input.copyTo(output)
          output.fd.sync()
        }
      }
      destination.isFile && destination.length() == source.length()
    } catch (_: IOException) {
      false
    }

  private suspend fun <T> io(block: () -> T): T =
    withContext(Dispatchers.IO) {
      ensureActive()
      synchronized(lock) { block() }
    }

  private class PhotoRejectedException : Exception()

  companion object {
    /** Picker copies stop once this many bytes have been read. */
    const val MAX_PICK_BYTES = 25L * 1024L * 1024L

    /** Staged files younger than this are kept by [cleanup] so a restored review is not deleted. */
    const val STAGED_GRACE_MS = 120_000L

    /** Scratch files older than this are deleted even if a capture session never released them. */
    const val PARTIAL_STALE_MS = 120_000L

    private const val ROOT_DIRECTORY = "fridge-photos"
    private const val STAGED_DIRECTORY = "staged"
    private const val SAVED_DIRECTORY = "saved"
    private const val REVIEW_MARKER = "active-review.txt"
    private const val JPEG_EXTENSION = ".jpg"
    private const val PARTIAL_SUFFIX = ".partial"
    private const val COPY_BUFFER_BYTES = 16 * 1024
    private val REF =
      Regex("""^(staged|saved)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.jpg$""")
    private val PARTIAL_NAME =
      Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.jpg\.partial$""")

    private fun stagedRef(id: String): String = "$STAGED_DIRECTORY/$id$JPEG_EXTENSION"
  }
}

/** Storage failure that did not delete protected photos. */
class PhotoStoreException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)
