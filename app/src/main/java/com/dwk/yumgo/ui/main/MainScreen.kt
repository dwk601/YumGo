package com.dwk.yumgo.ui.main

import android.content.Context
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dwk.yumgo.R
import com.dwk.yumgo.data.OfflineFridgeRepository
import com.dwk.yumgo.data.PhotoAnalysisRepository
import com.dwk.yumgo.data.PhotoStore
import com.dwk.yumgo.data.SettingsServices
import com.dwk.yumgo.ui.photo.PhotoCapture
import com.dwk.yumgo.ui.photo.rememberPhotoPicker

/**
 * Fridge destination. One [OfflineFridgeRepository] and one [PhotoStore] are created
 * with the first ViewModel and reused for the life of that screen, including rotation
 * and the trip to Settings and back.
 */
@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  onOpenSettings: () -> Unit = {},
  viewModel: MainScreenViewModel = fridgeViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val pendingUndo by viewModel.pendingUndoState.collectAsStateWithLifecycle()
  val presets by viewModel.presets.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current.applicationContext
  val photos = remember(viewModel) { PhotoStoreHolder.photos(context) }
  val undoLabel = stringResource(R.string.fridge_undo)
  val pending = pendingUndo
  val pendingId = pending?.id
  val removed = pending?.let { stringResource(R.string.fridge_removed, it.name) }
  val pickPhoto =
    rememberPhotoPicker(photoStore = photos) { result ->
      viewModel.onAcquisition(result)
    }
  LaunchedEffect(pendingId, removed) {
    if (pendingId == null) return@LaunchedEffect
    val result = snackbar.showSnackbar(message = removed.orEmpty(), actionLabel = undoLabel)
    when (result) {
      SnackbarResult.ActionPerformed -> viewModel.confirmUndo(pendingId)
      SnackbarResult.Dismissed -> viewModel.dismissUndo(pendingId)
    }
  }
  LaunchedEffect(viewModel) {
    viewModel.notices.collect { notice -> snackbar.showSnackbar(message = notice.text) }
  }
  val callbacks =
    FridgeCallbacks(
      onQueryChange = viewModel::onQueryChange,
      onRetry = viewModel::onRetry,
      onAdd = viewModel::onAdd,
      onEdit = viewModel::onEdit,
      onQuantityChange = viewModel::onQuantityChange,
      onDelete = viewModel::onDelete,
      onDraftChange = viewModel::onDraftChange,
      onDismissEditor = viewModel::onDismissEditor,
      onSave = viewModel::onSave,
      onTakePhoto = viewModel::onTakePhoto,
      onPickPhoto = {
        viewModel.onPickStarted()
        pickPhoto()
      },
      onRemovePhoto = viewModel::onRemovePhoto,
      onAddPhoto = viewModel::onAddPhoto,
      onDismissPhotoSource = viewModel::onDismissPhotoSource,
      onAnalyzePhoto = viewModel::onAnalyzePhoto,
      onOpenSettings = onOpenSettings,
      onPresetSelected = viewModel::onPresetSelected,
    )
  FridgeContent(
    state = state,
    callbacks = callbacks,
    modifier = modifier,
    presets = presets,
    snackbarHost = { SnackbarHost(snackbar) },
    cameraContent = {
      PhotoCapture(
        photoStore = photos,
        onResult = viewModel::onAcquisition,
        onReviewRef = viewModel::onReviewRef,
      )
    },
  )
}

@Composable
private fun fridgeViewModel(): MainScreenViewModel {
  val appContext = LocalContext.current.applicationContext
  return viewModel {
    MainScreenViewModel(
      repository = PhotoStoreHolder.repository(appContext),
      photos = PhotoStoreHolder.photos(appContext),
      presets = SettingsServices.presets(appContext),
      appContext = appContext,
      savedState = createSavedStateHandle(),
    )
  }
}

/**
 * Process-wide fridge services. The screen ViewModel is created once per navigation entry,
 * and this holder keeps that same repository and photo store if the activity is recreated
 * before the ViewModel is.
 */
internal object PhotoStoreHolder {
  @Volatile private var repository: OfflineFridgeRepository? = null
  @Volatile private var photos: PhotoStore? = null
  /** Test-only analyzer override, installed before activity launch. Null uses the real one. */
  @Volatile internal var analyzerForTests: PhotoAnalysisRepository? = null

  fun repository(context: Context): OfflineFridgeRepository =
    repository ?: synchronized(this) {
      repository ?: OfflineFridgeRepository(context.applicationContext).also { repository = it }
    }

  fun photos(context: Context): PhotoStore =
    photos ?: synchronized(this) {
      photos ?: PhotoStore(context.applicationContext).also { photos = it }
    }

  /** Drops the cached services so the next screen opens a fresh database. Test-only. */
  fun resetForTests() {
    synchronized(this) {
      repository = null
      photos = null
      analyzerForTests = null
    }
  }
}
