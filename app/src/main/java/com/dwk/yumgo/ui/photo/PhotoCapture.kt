package com.dwk.yumgo.ui.photo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.dwk.yumgo.R
import com.dwk.yumgo.data.PhotoStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What photo acquisition produced. The caller keeps the typed draft in every case.
 * [Staged.ref] is a [PhotoStore] ref. Call [PhotoStore.promote] before saving it on an item.
 */
sealed interface PhotoAcquisition {
  data class Staged(val ref: String) : PhotoAcquisition

  data object Cancelled : PhotoAcquisition

  data class Failed(val message: String) : PhotoAcquisition

  data object PermissionDenied : PhotoAcquisition
}

/**
 * Camera shown after the user taps capture. Permission is requested here, not when the editor opens.
 * Back, denial, cancel, and errors only report an outcome. A missing camera reports [PhotoAcquisition.Failed].
 *
 * [onReviewRef] receives the staged ref while it is on the review screen, including after process
 * restoration, and null otherwise. [PhotoStore.activeReviewRef] persists the same ref. Do not call
 * [PhotoStore.cleanup] or [PhotoStore.cleanupSaved] while this UI is up.
 *
 * Test tags: `photo_capture`, `photo_shutter`, `photo_use`, `photo_retake`, `photo_cancel`,
 * `photo_type_instead`, `photo_permission_continue`, `photo_preview`, `photo_review_error`.
 */
@Composable
fun PhotoCapture(
  photoStore: PhotoStore,
  onResult: (PhotoAcquisition) -> Unit,
  modifier: Modifier = Modifier,
  onReviewRef: (String?) -> Unit = {},
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val currentOnResult by rememberUpdatedState(onResult)
  val delivered = remember { AtomicBoolean(false) }
  var phase by rememberSaveable { mutableStateOf(PHASE_STARTING) }
  var reviewRef by rememberSaveable { mutableStateOf<String?>(null) }
  var permissionRequest by remember { mutableIntStateOf(0) }
  var capturing by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }
  var boundCapture by remember { mutableStateOf<ImageCapture?>(null) }
  var scratchFile by remember { mutableStateOf<File?>(null) }
  var captureGeneration by remember { mutableIntStateOf(0) }
  var reviewReady by remember { mutableStateOf(false) }
  val currentReviewRef by rememberUpdatedState(onReviewRef)
  // Read once per composition so the messages follow configuration changes, not the Context.
  val cameraUnavailable = stringResource(R.string.photo_camera_unavailable)
  val captureFailed = stringResource(R.string.photo_capture_failed)
  val reviewFailed = stringResource(R.string.photo_review_failed)

  fun finish(result: PhotoAcquisition) {
    if (delivered.compareAndSet(false, true)) currentOnResult(result)
  }

  fun cancelCapture() {
    if (!delivered.compareAndSet(false, true)) return
    captureGeneration += 1
    val review = reviewRef
    val scratch = scratchFile
    reviewRef = null
    scratchFile = null
    capturing = false
    scope.launch {
      withContext(NonCancellable) {
        photoStore.setActiveReview(null)
        if (review != null) photoStore.discard(review)
        if (scratch != null) photoStore.abandonCaptureFile(scratch)
      }
      currentOnResult(PhotoAcquisition.Cancelled)
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      val scratch = scratchFile
      if (scratch != null) photoStore.releasePartialNow(scratch)
    }
  }

  val navigationState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
  NavigationBackHandler(state = navigationState, isBackEnabled = true, onBackCompleted = { cancelCapture() })

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (delivered.get()) return@rememberLauncherForActivityResult
      when {
        granted -> phase = PHASE_CAMERA
        context.shouldShowCameraRationale() -> phase = PHASE_RATIONALE
        else -> finish(PhotoAcquisition.PermissionDenied)
      }
    }

  LaunchedEffect(phase) {
    if (phase != PHASE_STARTING) return@LaunchedEffect
    when {
      !context.hasAnyCamera() -> finish(PhotoAcquisition.Failed(cameraUnavailable))
      context.hasCameraPermission() -> phase = PHASE_CAMERA
      context.shouldShowCameraRationale() -> phase = PHASE_RATIONALE
      else -> {
        phase = PHASE_WAITING
        permissionRequest += 1
      }
    }
  }

  LaunchedEffect(permissionRequest) {
    if (permissionRequest == 0 || phase != PHASE_WAITING || delivered.get()) return@LaunchedEffect
    if (context.hasCameraPermission()) phase = PHASE_CAMERA else permissionLauncher.launch(Manifest.permission.CAMERA)
  }

  LaunchedEffect(phase, reviewRef) {
    if (phase == PHASE_REVIEW && reviewRef == null) phase = PHASE_CAMERA
  }

  LaunchedEffect(reviewRef) { reviewReady = false }

  LaunchedEffect(reviewRef, phase) {
    if (phase == PHASE_REVIEW && reviewRef != null) {
      photoStore.setActiveReview(reviewRef)
      currentReviewRef(reviewRef)
    } else {
      currentReviewRef(null)
    }
  }

  val rootColor =
    if (phase == PHASE_CAMERA || phase == PHASE_REVIEW) {
      MaterialTheme.colorScheme.inverseSurface
    } else {
      MaterialTheme.colorScheme.background
    }

  Box(modifier.fillMaxSize().background(rootColor).testTag("photo_capture")) {
    when (phase) {
      PHASE_RATIONALE ->
        Explanation(
          message = stringResource(R.string.photo_permission_rationale),
          primary = stringResource(R.string.photo_permission_continue),
          primaryTag = "photo_permission_continue",
          onPrimary = {
            phase = PHASE_WAITING
            permissionRequest += 1
          },
          onTypeInstead = { finish(PhotoAcquisition.PermissionDenied) },
        )
      PHASE_CAMERA -> {
        CameraPreview(
          onBound = { boundCapture = it },
          onUnbound = { boundCapture = null },
          onFailed = { failure ->
            boundCapture = null
            finish(failure)
          },
        )
        CaptureBar(
          errorText = errorText,
          primaryEnabled = !capturing && boundCapture != null,
          primaryLabel = stringResource(R.string.photo_take),
          primaryTag = "photo_shutter",
          onCancel = { cancelCapture() },
          onPrimary = {
            val capture = boundCapture ?: return@CaptureBar
            if (capturing) return@CaptureBar
            capturing = true
            errorText = null
            val generation = captureGeneration
            scope.launch {
              val file =
                photoStore.allocateCaptureFile().getOrElse {
                  capturing = false
                  errorText = captureFailed
                  return@launch
                }
              if (generation != captureGeneration) {
                photoStore.abandonCaptureFile(file)
                return@launch
              }
              scratchFile = file
              try {
                // CameraX requires the main thread. The capture file is allocated by a
                // suspending call above, so this coroutine can resume somewhere else, such as
                // a test instrumentation thread, and the shot would throw instead of being taken.
                withContext(Dispatchers.Main.immediate) {
                  capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                      override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        scope.launch {
                          if (generation != captureGeneration) {
                            photoStore.abandonCaptureFile(file)
                            return@launch
                          }
                          photoStore.commitCaptureFile(file).fold(
                            onSuccess = { ref ->
                              photoStore.setActiveReview(ref)
                              scratchFile = null
                              capturing = false
                              reviewRef = ref
                              errorText = null
                              reviewReady = false
                              phase = PHASE_REVIEW
                            },
                            onFailure = {
                              photoStore.abandonCaptureFile(file)
                              scratchFile = null
                              capturing = false
                              errorText = captureFailed
                            },
                          )
                        }
                      }

                      override fun onError(exception: ImageCaptureException) {
                        scope.launch {
                          photoStore.abandonCaptureFile(file)
                          if (generation != captureGeneration) return@launch
                          scratchFile = null
                          capturing = false
                          errorText = captureFailed
                        }
                      }
                    },
                  )
                }
              } catch (_: SecurityException) {
                photoStore.abandonCaptureFile(file)
                scratchFile = null
                finish(PhotoAcquisition.PermissionDenied)
              }
            }
          },
        )
      }
      PHASE_REVIEW -> {
        val ref = reviewRef
        if (ref != null) {
          ReviewPhoto(
            photoStore = photoStore,
            ref = ref,
            onDecoded = { ok ->
              reviewReady = ok
              errorText = if (ok) null else reviewFailed
            },
          )
          CaptureBar(
            errorText = errorText,
            primaryEnabled = reviewReady,
            primaryLabel = stringResource(R.string.photo_use),
            primaryTag = "photo_use",
            secondaryLabel = stringResource(R.string.photo_retake),
            secondaryTag = "photo_retake",
            onSecondary = {
              scope.launch {
                photoStore.setActiveReview(null)
                photoStore.discard(ref)
                reviewRef = null
                errorText = null
                reviewReady = false
                phase = PHASE_CAMERA
              }
            },
            onCancel = { cancelCapture() },
            onPrimary = {
              finish(PhotoAcquisition.Staged(ref))
              photoStore.clearActiveReviewNow()
            },
          )
        }
      }
      else ->
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
          Text(
            text = stringResource(R.string.photo_permission_waiting),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
          )
        }
    }
  }
}

/**
 * System photo picker with no storage permission. Call this while the editor is composed, then
 * invoke the returned lambda when the user picks an existing photo. A dismissed picker is
 * [PhotoAcquisition.Cancelled]. The image is copied into a private staged ref.
 */
@Composable
fun rememberPhotoPicker(
  photoStore: PhotoStore,
  onResult: (PhotoAcquisition) -> Unit,
): () -> Unit {
  val currentOnResult by rememberUpdatedState(onResult)
  val scope = rememberCoroutineScope()
  val failed = stringResource(R.string.photo_picker_failed)
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri == null) {
        currentOnResult(PhotoAcquisition.Cancelled)
      } else {
        scope.launch {
          photoStore.stageFromUri(uri).fold(
            onSuccess = { currentOnResult(PhotoAcquisition.Staged(it)) },
            onFailure = { currentOnResult(PhotoAcquisition.Failed(failed)) },
          )
        }
      }
    }
  return {
    launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
  }
}

@Composable
private fun CameraPreview(
  onBound: (ImageCapture) -> Unit,
  onUnbound: () -> Unit,
  onFailed: (PhotoAcquisition) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val view = LocalView.current
  LocalConfiguration.current.orientation
  val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
  var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
  val preview = remember { Preview.Builder().build() }
  val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
  val failedOnce = remember { AtomicBoolean(false) }
  val previewDescription = stringResource(R.string.photo_preview_description)

  fun applyRotation() {
    val rotation = view.display?.rotation ?: Surface.ROTATION_0
    preview.setTargetRotation(rotation)
    imageCapture.setTargetRotation(rotation)
  }

  SideEffect { applyRotation() }

  DisposableEffect(lifecycleOwner) {
    val cancelled = AtomicBoolean(false)
    val displayManager = context.getSystemService(DisplayManager::class.java)
    val displayListener =
      object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
          applyRotation()
        }
      }
    displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
      {
        if (cancelled.get()) return@addListener
        bindCamera(future, lifecycleOwner, preview, imageCapture, context, failedOnce, onBound, onFailed) {
          surfaceRequest = it
        }
      },
      mainExecutor,
    )
    onDispose {
      cancelled.set(true)
      displayManager?.unregisterDisplayListener(displayListener)
      surfaceRequest = null
      preview.setSurfaceProvider(null)
      onUnbound()
      if (future.isDone) {
        try {
          future.get().unbindAll()
        } catch (_: Exception) {
          // The provider never opened.
        }
      }
    }
  }

  val request = surfaceRequest
  if (request == null) {
    Box(Modifier.fillMaxSize().semantics { contentDescription = previewDescription }.testTag("photo_preview"))
  } else {
    CameraXViewfinder(
      surfaceRequest = request,
      modifier = Modifier.fillMaxSize().semantics { contentDescription = previewDescription }.testTag("photo_preview"),
    )
  }
}

private fun bindCamera(
  future: com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider>,
  lifecycleOwner: LifecycleOwner,
  preview: Preview,
  imageCapture: ImageCapture,
  context: Context,
  failedOnce: AtomicBoolean,
  onBound: (ImageCapture) -> Unit,
  onFailed: (PhotoAcquisition) -> Unit,
  onSurface: (SurfaceRequest) -> Unit,
) {
  try {
    val provider = future.get()
    val selector = provider.chooseSelector()
    if (selector == null) {
      if (failedOnce.compareAndSet(false, true)) {
        onFailed(PhotoAcquisition.Failed(context.getString(R.string.photo_camera_unavailable)))
      }
      return
    }
    preview.setSurfaceProvider { request -> onSurface(request) }
    provider.unbindAll()
    provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
    onBound(imageCapture)
  } catch (_: SecurityException) {
    if (failedOnce.compareAndSet(false, true)) onFailed(PhotoAcquisition.PermissionDenied)
  } catch (_: Exception) {
    if (failedOnce.compareAndSet(false, true)) {
      onFailed(PhotoAcquisition.Failed(context.getString(R.string.photo_camera_failed)))
    }
  }
}

@Composable
private fun ReviewPhoto(photoStore: PhotoStore, ref: String, onDecoded: (Boolean) -> Unit) {
  var bitmap by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
  var failed by remember(ref) { mutableStateOf(false) }
  val currentOnDecoded by rememberUpdatedState(onDecoded)
  LaunchedEffect(ref) {
    val decoded =
      withContext(Dispatchers.IO) {
        val file = photoStore.existingFile(ref) ?: return@withContext null
        decodeSampled(file)?.asImageBitmap()
      }
    if (decoded == null) {
      failed = true
      bitmap = null
      currentOnDecoded(false)
    } else {
      failed = false
      bitmap = decoded
      currentOnDecoded(true)
    }
  }
  val description = stringResource(R.string.photo_review_description)
  val current = bitmap
  when {
    current != null ->
      Image(
        bitmap = current,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().testTag("photo_preview"),
      )
    failed ->
      Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
          text = stringResource(R.string.photo_review_failed),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.testTag("photo_review_error"),
        )
      }
    else -> Box(Modifier.fillMaxSize().semantics { contentDescription = description }.testTag("photo_preview"))
  }
}

@Composable
private fun BoxScope.CaptureBar(
  errorText: String?,
  primaryEnabled: Boolean,
  primaryLabel: String,
  primaryTag: String,
  onCancel: () -> Unit,
  onPrimary: () -> Unit,
  secondaryLabel: String? = null,
  secondaryTag: String? = null,
  onSecondary: (() -> Unit)? = null,
) {
  Surface(
    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
  ) {
    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 12.dp)) {
      if (errorText != null) {
        Text(
          text = errorText,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        IconButton(onClick = onCancel, modifier = Modifier.testTag("photo_cancel")) {
          Icon(PhotoClose, contentDescription = stringResource(R.string.photo_cancel))
        }
        if (secondaryLabel != null && onSecondary != null) {
          TextButton(
            onClick = onSecondary,
            modifier = Modifier.heightIn(min = 48.dp).testTag(secondaryTag.orEmpty()),
          ) {
            Text(secondaryLabel)
          }
        }
        Button(
          onClick = onPrimary,
          enabled = primaryEnabled,
          modifier = Modifier.weight(1f).padding(start = 8.dp).heightIn(min = 52.dp).testTag(primaryTag),
          shape = MaterialTheme.shapes.extraLarge,
        ) {
          Text(primaryLabel, textAlign = TextAlign.Center)
        }
      }
    }
  }
}

@Composable
private fun Explanation(
  message: String,
  primary: String,
  primaryTag: String,
  onPrimary: () -> Unit,
  onTypeInstead: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text(text = message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    Button(
      onClick = onPrimary,
      modifier = Modifier.padding(top = 20.dp).heightIn(min = 52.dp).fillMaxWidth().testTag(primaryTag),
      shape = MaterialTheme.shapes.extraLarge,
    ) {
      Text(primary, textAlign = TextAlign.Center)
    }
    TextButton(
      onClick = onTypeInstead,
      modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp).fillMaxWidth().testTag("photo_type_instead"),
    ) {
      Text(stringResource(R.string.photo_type_instead), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
  }
}

private const val PHASE_STARTING = "starting"
private const val PHASE_RATIONALE = "rationale"
private const val PHASE_WAITING = "waiting"
private const val PHASE_CAMERA = "camera"
private const val PHASE_REVIEW = "review"

private fun Context.hasCameraPermission(): Boolean =
  ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.shouldShowCameraRationale(): Boolean =
  findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true

private fun Context.hasAnyCamera(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

private fun ProcessCameraProvider.chooseSelector(): CameraSelector? {
  val back = CameraSelector.DEFAULT_BACK_CAMERA
  val front = CameraSelector.DEFAULT_FRONT_CAMERA
  return when {
    hasCameraSafe(back) -> back
    hasCameraSafe(front) -> front
    else -> null
  }
}

private fun ProcessCameraProvider.hasCameraSafe(selector: CameraSelector): Boolean =
  try {
    hasCamera(selector)
  } catch (_: CameraInfoUnavailableException) {
    false
  }

private fun decodeSampled(file: File): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(file.absolutePath, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (bounds.outWidth / sample > MAX_REVIEW_EDGE || bounds.outHeight / sample > MAX_REVIEW_EDGE) {
    if (sample > Int.MAX_VALUE / 2) break
    sample *= 2
  }
  val decoded =
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
  return applyExifOrientation(decoded, file)
}

private fun applyExifOrientation(bitmap: Bitmap, file: File): Bitmap {
  val orientation =
    try {
      file.inputStream().use { stream ->
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      }
    } catch (_: Exception) {
      return bitmap
    }
  val matrix =
    Matrix().apply {
      when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
          postRotate(90f)
          postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
          postRotate(270f)
          postScale(-1f, 1f)
        }
        else -> return bitmap
      }
    }
  return try {
    val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (oriented != bitmap) bitmap.recycle()
    oriented
  } catch (_: Exception) {
    bitmap
  }
}

private const val MAX_REVIEW_EDGE = 1600

/** Cancel glyph. Kept here so the photo package does not depend on the fridge package. */
private val PhotoClose: ImageVector by lazy {
  ImageVector.Builder(name = "Close", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .apply {
      path(fill = SolidColor(Color.Black)) {
        moveTo(19f, 6.41f)
        lineTo(17.59f, 5f)
        lineTo(12f, 10.59f)
        lineTo(6.41f, 5f)
        lineTo(5f, 6.41f)
        lineTo(10.59f, 12f)
        lineTo(5f, 17.59f)
        lineTo(6.41f, 19f)
        lineTo(12f, 13.41f)
        lineTo(17.59f, 19f)
        lineTo(19f, 17.59f)
        lineTo(13.41f, 12f)
        close()
      }
    }
    .build()
}
