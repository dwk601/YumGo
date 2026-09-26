package com.dwk.yumgo.data

import android.content.Context
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
 * [discard] and [cleanup] delete staged files only. Saved files and any ref in the protected
 * set passed by the caller are left on disk, including undo and in-progress draft refs.
 * This store does not read the fridge database; the caller decides what is protected.
 */
class PhotoStore(context: Context) {
  private val resolver = context.applicationContext.contentResolver
  private val root = File(context.applicationContext.filesDir, ROOT_DIRECTORY)
  private val stagedDir = File(root, STAGED_DIRECTORY)
  private val savedDir = File(root, SAVED_DIRECTORY)
  private val lock = Any()

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
            source.copyTo(output)
            output.fd.sync()
          }
        }
        if (job?.isActive == false) {
          destination.delete()
          throw CancellationException("Photo staging cancelled")
        }
        if (destination.length() <= 0L) {
          destination.delete()
          Result.failure(PhotoStoreException("Couldn't read the photo"))
        } else {
          Result.success(stagedRef(id))
        }
      } catch (cancelled: CancellationException) {
        destination.delete()
        throw cancelled
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
          Result.success(partial)
        } else {
          Result.failure(PhotoStoreException("Couldn't prepare the photo"))
        }
      }
    }

  /** Publish a captured scratch file as a staged ref. Deletes the scratch file if it is empty. */
  suspend fun commitCaptureFile(file: File): Result<String> =
    io {
      val partial = managedPartial(file)
      if (partial == null) {
        Result.failure(PhotoStoreException("Couldn't prepare the photo"))
      } else if (partial.length() <= 0L) {
        partial.delete()
        Result.failure(PhotoStoreException("Couldn't save the photo"))
      } else {
        val finalName = partial.name.removeSuffix(PARTIAL_SUFFIX)
        val destination = File(stagedDir, finalName)
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
    io {
      managedPartial(file)?.delete()
    }
  }

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
   * Delete staged files whose refs are not in [protected].
   * Saved files are never deleted. Nothing outside this store is deleted.
   */
  suspend fun cleanup(protected: Set<String>): Result<Unit> =
    io {
      if (!stagedDir.isDirectory) return@io Result.success(Unit)
      val keep = protected.mapNotNull { normalize(it) }.toSet()
      var failed = false
      stagedDir.listFiles()?.forEach { file ->
        val ref = "$STAGED_DIRECTORY/${file.name}"
        if (!REF.matches(ref) || ref in keep || !file.isFile || !isInside(file, stagedDir)) return@forEach
        if (!file.delete()) failed = true
      }
      if (failed) Result.failure(PhotoStoreException("Couldn't clean up photos")) else Result.success(Unit)
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

  private companion object {
    const val ROOT_DIRECTORY = "fridge-photos"
    const val STAGED_DIRECTORY = "staged"
    const val SAVED_DIRECTORY = "saved"
    const val JPEG_EXTENSION = ".jpg"
    const val PARTIAL_SUFFIX = ".partial"
    val REF = Regex("""^(staged|saved)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.jpg$""")
    val PARTIAL_NAME =
      Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.jpg\.partial$""")

    fun stagedRef(id: String): String = "$STAGED_DIRECTORY/$id$JPEG_EXTENSION"
  }
}

/** Storage failure that did not delete saved or protected photos. */
class PhotoStoreException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)
