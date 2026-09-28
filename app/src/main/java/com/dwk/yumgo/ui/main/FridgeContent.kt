@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.dwk.yumgo.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dwk.yumgo.R
import com.dwk.yumgo.data.FoodPreset
import com.dwk.yumgo.theme.YumgoTheme
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fridge screen. Owns status, cutout, navigation, and keyboard insets. Expects no
 * ancestor safe-drawing padding. [snackbarHost] is placed above Add and the keyboard.
 * [cameraContent] cross-fades over this surface while [FridgeUiState.cameraOpen] is
 * true. The draft stays in [state] and the editor returns already open, without
 * sliding up again or opening the keyboard.
 */
@Composable
fun FridgeContent(
  state: FridgeUiState,
  callbacks: FridgeCallbacks,
  modifier: Modifier = Modifier,
  presets: List<FoodPreset> = emptyList(),
  snackbarHost: @Composable () -> Unit = {},
  cameraContent: @Composable () -> Unit = {},
) {
  var restoreEditor by remember { mutableStateOf(false) }
  SideEffect {
    if (state.cameraOpen && state.draft != null) restoreEditor = true
    if (state.draft == null) restoreEditor = false
  }
  val cameraFade = motionEffects<Float>()
  AnimatedContent(
    targetState = state.cameraOpen,
    modifier = modifier.fillMaxSize(),
    transitionSpec = { fadeIn(cameraFade) togetherWith fadeOut(cameraFade) },
    label = "camera",
  ) { camera ->
    if (camera) {
      Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { cameraContent() }
    } else {
      Box(Modifier.fillMaxSize()) {
        FridgeScaffold(state = state, callbacks = callbacks, snackbarHost = snackbarHost)
        state.draft?.let { draft ->
          ItemEditor(
            draft = draft,
            callbacks = callbacks,
            presets = presets,
            restored = restoreEditor || state.cameraOpen,
            backEnabled = !state.cameraOpen,
          )
        }
      }
    }
  }
}

@Composable
private fun FridgeScaffold(
  state: FridgeUiState,
  callbacks: FridgeCallbacks,
  snackbarHost: @Composable () -> Unit,
) {
  val density = LocalDensity.current
  val imeBottom = WindowInsets.ime.getBottom(density)
  val screenInsets =
    WindowInsets.safeDrawing.exclude(WindowInsets.ime).union(WindowInsets(bottom = imeBottom))
  val layoutDirection = LocalLayoutDirection.current
  val fade = motionEffects<Float>()
  val place = motionSpatial<IntOffset>()
  val paneFade = motionEffects<Float>()
  val today = LocalDate.now().toEpochDay()
  val sections = remember(state.items, state.query, today) { fridgeSections(state.items, state.query, today) }
  val useSoon = remember(state.items, today) { state.items.count { expiryGroup(it.expiryEpochDay, today) == ExpiryGroup.UseSoon } }
  val pane =
    when {
      state.load == FridgeLoad.Loading && state.items.isEmpty() -> FridgePane.Loading
      state.load == FridgeLoad.Error && state.items.isEmpty() -> FridgePane.Error
      state.items.isEmpty() -> FridgePane.Empty
      sections.isEmpty() -> FridgePane.NoResults
      else -> FridgePane.List
    }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
    contentWindowInsets = screenInsets,
    snackbarHost = snackbarHost,
    topBar = { FridgeHeader(state = state, useSoon = useSoon, callbacks = callbacks) },
    floatingActionButton = {
      val addLabel = stringResource(R.string.fridge_add)
      val addPhotoLabel = stringResource(R.string.photo_ai_add_photo)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        FloatingActionButton(
          onClick = callbacks.onAddPhoto,
          modifier = Modifier.testTag("addPhotoButton").semantics { contentDescription = addPhotoLabel },
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
          Icon(FridgePhoto, contentDescription = null)
        }
        ExtendedFloatingActionButton(
          onClick = callbacks.onAdd,
          modifier = Modifier.semantics { contentDescription = addLabel },
          icon = { Icon(FridgePlus, contentDescription = null) },
          text = { Text(addLabel) },
        )
      }
    },
  ) { innerPadding ->
    AnimatedContent(
      targetState = pane,
      modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
      transitionSpec = { fadeIn(paneFade) togetherWith fadeOut(paneFade) },
      label = "fridge-pane",
    ) { current ->
      when (current) {
        FridgePane.Loading ->
          FridgeMessage(innerPadding, title = stringResource(R.string.fridge_loading), showLoading = true)
        FridgePane.Error ->
          FridgeMessage(
            innerPadding,
            title = stringResource(R.string.fridge_error_title),
            body = state.errorMessage,
          ) {
            TextButton(onClick = callbacks.onRetry) { Text(stringResource(R.string.fridge_retry)) }
          }
        FridgePane.Empty ->
          FridgeMessage(
            innerPadding,
            title = stringResource(R.string.fridge_empty_title),
            body = stringResource(R.string.fridge_empty_body),
          )
        FridgePane.NoResults ->
          FridgeMessage(
            innerPadding,
            title = stringResource(R.string.fridge_no_results_title),
            body = stringResource(R.string.fridge_no_results_body, state.query.trim()),
          ) {
            TextButton(onClick = { callbacks.onQueryChange("") }) { Text(stringResource(R.string.fridge_search_clear)) }
          }
        FridgePane.List ->
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
              PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 20.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 20.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 96.dp,
              ),
          ) {
            if (state.load == FridgeLoad.Error) {
              item(key = "error", contentType = "error") {
                ErrorBanner(
                  message = state.errorMessage,
                  onRetry = callbacks.onRetry,
                  modifier =
                  Modifier.padding(bottom = 12.dp).animateItem(fadeInSpec = fade, placementSpec = place, fadeOutSpec = fade),
                )
              }
            }
            sections.forEach { section ->
              item(key = "section-${section.group}", contentType = "header") {
                SectionHeader(
                  section.group,
                  Modifier.animateItem(fadeInSpec = fade, placementSpec = place, fadeOutSpec = fade),
                )
              }
              items(items = section.items, key = { it.id }, contentType = { "item" }) { item ->
                FridgeCard(
                  item = item,
                  callbacks = callbacks,
                  modifier =
                    Modifier.padding(bottom = 10.dp).animateItem(fadeInSpec = fade, placementSpec = place, fadeOutSpec = fade),
                )
              }
            }
          }
      }
    }
  }
  if (state.photoSourceOpen) {
    PhotoSourceSheet(state = state, callbacks = callbacks)
  }
}

/**
 * Compact photo source menu. Two large targets that wrap at large text, with the
 * permission-free picker called out. Dismisses through [FridgeCallbacks.onDismissPhotoSource];
 * the camera and picker choices go through the existing photo callbacks so the caller stages
 * the file and opens a review draft.
 */
@Composable
private fun PhotoSourceSheet(state: FridgeUiState, callbacks: FridgeCallbacks) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = callbacks.onDismissPhotoSource,
    sheetState = sheetState,
    shape = MaterialTheme.shapes.extraLarge,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor = MaterialTheme.colorScheme.onSurface,
    dragHandle = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets = { BottomSheetDefaults.windowInsets.only(WindowInsetsSides.Top) },
  ) {
    Column(
      Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
    ) {
      Text(text = stringResource(R.string.photo_ai_source_title), style = MaterialTheme.typography.headlineSmall)
      Text(
        text = stringResource(R.string.photo_ai_source_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
      Spacer(Modifier.height(12.dp))
      OutlinedButton(
        onClick = callbacks.onTakePhoto,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("photoSourceCamera"),
      ) {
        Text(stringResource(R.string.photo_ai_source_camera))
      }
      Spacer(Modifier.height(8.dp))
      OutlinedButton(
        onClick = callbacks.onPickPhoto,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("photoSourcePicker"),
      ) {
        Text(stringResource(R.string.photo_ai_source_picker))
      }
      Text(
        text = stringResource(R.string.photo_ai_source_picker_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
      Spacer(Modifier.height(8.dp))
      TextButton(
        onClick = callbacks.onDismissPhotoSource,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      ) {
        Text(stringResource(R.string.photo_ai_source_dismiss))
      }
    }
  }
}

@Composable
private fun FridgeHeader(state: FridgeUiState, useSoon: Int, callbacks: FridgeCallbacks) {
  // The status bar, a cutout and the navigation bar all take a strip off the top or a side when
  // the phone is held sideways, and 3-button navigation puts its bar on the left or the right, so
  // the header keeps the horizontal ones too. In portrait they are all zero, so nothing moves.
  val headerInsets =
    WindowInsets.statusBars
      .union(WindowInsets.displayCutout)
      .union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
      .exclude(WindowInsets.ime)
  val subtitle =
    when {
      state.items.isEmpty() -> stringResource(R.string.fridge_subtitle_empty)
      useSoon > 0 -> pluralStringResource(R.plurals.fridge_subtitle_use_soon, useSoon, useSoon)
      else -> stringResource(R.string.fridge_subtitle_calm)
    }
  val focus = LocalFocusManager.current
  Column(
    Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.background)
      .windowInsetsPadding(headerInsets)
      .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(text = stringResource(R.string.fridge_title), style = MaterialTheme.typography.headlineLarge)
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (useSoon > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
        )
      }
      IconButton(onClick = callbacks.onOpenSettings) {
        Icon(FridgeSettings, contentDescription = stringResource(R.string.fridge_settings))
      }
    }
    OutlinedTextField(
      value = state.query,
      onValueChange = callbacks.onQueryChange,
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      shape = MaterialTheme.shapes.extraLarge,
      label = { Text(stringResource(R.string.fridge_search_label)) },
      placeholder = { Text(stringResource(R.string.fridge_search_placeholder)) },
      leadingIcon = { Icon(FridgeSearch, contentDescription = null) },
      trailingIcon = {
        if (state.query.isNotEmpty()) {
          IconButton(onClick = { callbacks.onQueryChange("") }) {
            Icon(FridgeClose, contentDescription = stringResource(R.string.fridge_search_clear))
          }
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
      colors =
        OutlinedTextFieldDefaults.colors(
          focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
          unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
          focusedBorderColor = MaterialTheme.colorScheme.primary,
          unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
  }
}

/**
 * Full-pane message for loading, error, empty, and no-results. Scrolls so the copy and the
 * action stay reachable on a short landscape window or at 200% text.
 */
@Composable
private fun FridgeMessage(
  innerPadding: PaddingValues,
  title: String,
  body: String? = null,
  showLoading: Boolean = false,
  action: @Composable () -> Unit = {},
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(rememberScrollState())
        .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 88.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (showLoading) {
      LoadingIndicator(Modifier.padding(bottom = 20.dp).size(72.dp))
    }
    Text(
      text = title,
      style = MaterialTheme.typography.headlineSmall,
      textAlign = TextAlign.Center,
    )
    if (!body.isNullOrBlank()) {
      Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
    Box(Modifier.padding(top = 12.dp).heightIn(min = 48.dp)) { action() }
  }
}

@Composable
private fun ErrorBanner(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.fridge_error_title),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (!message.isNullOrBlank()) {
          Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }
      TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.fridge_retry)) }
    }
  }
}

@Composable
private fun SectionHeader(group: ExpiryGroup, modifier: Modifier = Modifier) {
  val accent = group == ExpiryGroup.UseSoon
  val color = if (accent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
  Row(
    modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(width = 4.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(color))
    Text(
      text = stringResource(group.label),
      style = MaterialTheme.typography.labelLarge,
      color = if (accent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 8.dp),
    )
  }
}

/**
 * One item. The thumbnail, name, and expiry sit on the first line, and the stepper drops below
 * them when the card is too narrow or the text is too large to keep them beside each other, so
 * the name and the date stay readable instead of being squeezed to a few characters.
 */
@Composable
private fun FridgeCard(item: FridgeItemUi, callbacks: FridgeCallbacks, modifier: Modifier = Modifier) {
  val editLabel = stringResource(R.string.fridge_edit_item)
  Surface(
    modifier =
      modifier.fillMaxWidth().clickable(onClickLabel = editLabel) { callbacks.onEdit(item.id) },
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
  ) {
    BoxWithConstraints(Modifier.padding(12.dp)) {
      val stacked = maxWidth < SideBySideMinWidth || LocalDensity.current.fontScale > LargeTextScale
      val stepper = @Composable { extra: Modifier ->
        QuantityStepper(
          quantity = item.quantity,
          name = item.name,
          onQuantityChange = { callbacks.onQuantityChange(item.id, it) },
          modifier = extra,
        )
      }
      if (stacked) {
        Column(Modifier.fillMaxWidth()) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            FridgeThumbnail(name = item.name, photoReference = item.photoReference)
            CardText(item = item, modifier = Modifier.weight(1f).padding(start = 12.dp))
          }
          stepper(Modifier.padding(top = 10.dp, start = 4.dp))
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          FridgeThumbnail(name = item.name, photoReference = item.photoReference)
          CardText(item = item, modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp))
          stepper(Modifier)
        }
      }
    }
  }
}

@Composable
private fun CardText(item: FridgeItemUi, modifier: Modifier = Modifier) {
  Column(modifier) {
    Text(
      text = item.name,
      style = MaterialTheme.typography.titleMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = fridgeExpiryLabel(item.expiryEpochDay),
      style = MaterialTheme.typography.bodySmall,
      color = fridgeExpiryColor(item.expiryEpochDay),
      modifier = Modifier.padding(top = 2.dp),
    )
  }
}

@Composable
internal fun QuantityStepper(
  quantity: Int,
  name: String,
  onQuantityChange: (Int) -> Unit,
  enabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val quantityFade = motionEffects<Float>()
  val quantitySlide = motionSpatial<IntOffset>()
  // A draft that has not been named yet has nothing to name the buttons after, so it gets the
  // bare action instead of announcing the field's own label.
  val decreaseLabel =
    if (name.isBlank()) {
      stringResource(R.string.fridge_decrease_quantity_unnamed)
    } else {
      stringResource(R.string.fridge_decrease_quantity, name)
    }
  val increaseLabel =
    if (name.isBlank()) {
      stringResource(R.string.fridge_increase_quantity_unnamed)
    } else {
      stringResource(R.string.fridge_increase_quantity, name)
    }
  Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { onQuantityChange(quantity - 1) }, enabled = enabled && quantity > 1) {
        Icon(FridgeMinus, contentDescription = decreaseLabel)
      }
      AnimatedContent(
        targetState = quantity,
        transitionSpec = {
          val forward = targetState > initialState
          val enter =
            slideInVertically(quantitySlide) { full -> if (forward) full / 2 else -full / 2 } + fadeIn(quantityFade)
          val exit =
            slideOutVertically(quantitySlide) { full -> if (forward) -full / 2 else full / 2 } + fadeOut(quantityFade)
          enter togetherWith exit
        },
        label = "quantity",
      ) { value ->
        val quantityLabel = stringResource(R.string.fridge_quantity, value)
        Text(
          text = value.toString(),
          style = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
          modifier = Modifier.widthIn(min = 24.dp).semantics { contentDescription = quantityLabel },
        )
      }
      IconButton(onClick = { onQuantityChange(quantity + 1) }, enabled = enabled && quantity < MaxQuantity) {
        Icon(FridgePlus, contentDescription = increaseLabel)
      }
    }
  }
}

@Composable
internal fun FridgeThumbnail(name: String, photoReference: String?, modifier: Modifier = Modifier) {
  val bitmap = rememberThumbnail(photoReference)
  val photoFade = motionEffects<Float>()
  Box(
    modifier.size(64.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
    contentAlignment = Alignment.Center,
  ) {
    AnimatedContent(
      targetState = bitmap,
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
      transitionSpec = { fadeIn(photoFade) togetherWith fadeOut(photoFade) },
      label = "photo",
    ) { image ->
      if (image != null) {
        Image(
          bitmap = image,
          contentDescription = null,
          modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
          contentScale = ContentScale.Crop,
        )
      } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            text = monogram(name),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.wrapContentSize(Alignment.Center).clearAndSetSemantics {},
          )
        }
      }
    }
  }
}

@Composable
private fun rememberThumbnail(reference: String?): ImageBitmap? {
  val context = LocalContext.current
  val cached = if (reference.isNullOrBlank()) null else ThumbnailCache.get(reference)
  val bitmap by
    produceState(initialValue = cached, reference) {
      if (reference.isNullOrBlank()) {
        value = null
        return@produceState
      }
      ThumbnailCache.get(reference)?.let {
        value = it
        return@produceState
      }
      val decoded = withContext(Dispatchers.IO) { runCatching { decodeThumbnail(context, reference) }.getOrNull() }
      if (decoded != null) ThumbnailCache.put(reference, decoded)
      value = decoded
    }
  return bitmap
}

@Composable
internal fun fridgeExpiryLabel(epochDay: Long?): String {
  if (epochDay == null) return stringResource(R.string.fridge_item_no_date)
  val delta = epochDay - LocalDate.now().toEpochDay()
  val formatted =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(LocalDate.ofEpochDay(epochDay))
  return when {
    delta == 0L -> stringResource(R.string.fridge_item_today)
    delta == 1L -> stringResource(R.string.fridge_item_tomorrow)
    delta == -1L -> stringResource(R.string.fridge_item_yesterday)
    delta in 2L..7L -> stringResource(R.string.fridge_item_in_days, delta.toInt())
    delta < 0L -> stringResource(R.string.fridge_item_expired, formatted)
    else -> formatted
  }
}

@Composable
internal fun fridgeExpiryColor(epochDay: Long?): Color {
  val scheme = MaterialTheme.colorScheme
  if (epochDay == null) return scheme.onSurfaceVariant
  val delta = epochDay - LocalDate.now().toEpochDay()
  return when {
    delta < 0L -> scheme.error
    delta <= 2L -> scheme.tertiary
    else -> scheme.onSurfaceVariant
  }
}

@Composable
private fun <T> motionEffects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()

@Composable
private fun <T> motionSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

private enum class FridgePane {
  Loading,
  Error,
  Empty,
  NoResults,
  List,
}

private enum class ExpiryGroup(val label: Int) {
  UseSoon(R.string.fridge_section_use_soon),
  ThisWeek(R.string.fridge_section_this_week),
  Later(R.string.fridge_section_later),
  Undated(R.string.fridge_section_undated),
}

private data class FridgeSection(val group: ExpiryGroup, val items: List<FridgeItemUi>)

private fun fridgeSections(items: List<FridgeItemUi>, query: String, today: Long): List<FridgeSection> {
  val trimmed = query.trim()
  val filtered =
    if (trimmed.isEmpty()) items else items.filter { it.name.contains(trimmed, ignoreCase = true) }
  val grouped = filtered.groupBy { expiryGroup(it.expiryEpochDay, today) }
  return ExpiryGroup.entries.mapNotNull { group ->
    val rows =
      grouped[group]
        .orEmpty()
        .sortedWith(compareBy<FridgeItemUi> { it.expiryEpochDay ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() })
    rows.takeIf { it.isNotEmpty() }?.let { FridgeSection(group, it) }
  }
}

private fun expiryGroup(epochDay: Long?, today: Long): ExpiryGroup {
  if (epochDay == null) return ExpiryGroup.Undated
  val delta = epochDay - today
  return when {
    delta <= 2L -> ExpiryGroup.UseSoon
    delta <= 7L -> ExpiryGroup.ThisWeek
    else -> ExpiryGroup.Later
  }
}

private fun monogram(name: String): String =
  name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "·"

private const val MaxQuantity = 99

/**
 * Narrowest card that still holds the thumbnail, a readable name, and the stepper side by side.
 * Below it the stepper moves under the text instead of squeezing it.
 */
private val SideBySideMinWidth = 290.dp

/** Font scale above which the text needs the full card width to itself. */
private const val LargeTextScale = 1.3f

internal val FridgePlus: ImageVector by lazy { fridgeIcon("Plus") { plus() } }
internal val FridgeMinus: ImageVector by lazy { fridgeIcon("Minus") { minus() } }
internal val FridgeClose: ImageVector by lazy { fridgeIcon("Close") { dismiss() } }
internal val FridgeCalendar: ImageVector by lazy { fridgeIcon("Calendar") { calendar() } }
internal val FridgeDelete: ImageVector by lazy { fridgeIcon("Delete") { delete() } }
internal val FridgeCheck: ImageVector by lazy { fridgeIcon("Check") { check() } }
private val FridgeSearch: ImageVector by lazy { fridgeIcon("Search") { search() } }
private val FridgeSettings: ImageVector by lazy { fridgeIcon("Settings") { settings() } }
internal val FridgePhoto: ImageVector by lazy { fridgeIcon("Photo") { photo() } }

private fun fridgeIcon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
  ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .apply { path(fill = SolidColor(Color.Black), pathBuilder = draw) }
    .build()

private fun androidx.compose.ui.graphics.vector.PathBuilder.plus() {
  moveTo(19f, 13f)
  horizontalLineTo(13f)
  verticalLineTo(19f)
  horizontalLineTo(11f)
  verticalLineTo(13f)
  horizontalLineTo(5f)
  verticalLineTo(11f)
  horizontalLineTo(11f)
  verticalLineTo(5f)
  horizontalLineTo(13f)
  verticalLineTo(11f)
  horizontalLineTo(19f)
  close()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.minus() {
  moveTo(19f, 13f)
  horizontalLineTo(5f)
  verticalLineTo(11f)
  horizontalLineTo(19f)
  close()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.search() {
  moveTo(15.5f, 14f)
  horizontalLineToRelative(-0.79f)
  lineToRelative(-0.28f, -0.27f)
  arcTo(6.47f, 6.47f, 0f, false, false, 16f, 9.5f)
  arcTo(6.5f, 6.5f, 0f, true, false, 9.5f, 16f)
  curveToRelative(1.61f, 0f, 3.09f, -0.59f, 4.23f, -1.57f)
  lineToRelative(0.27f, 0.28f)
  verticalLineToRelative(0.79f)
  lineToRelative(5f, 4.99f)
  lineTo(20.49f, 19f)
  close()
  moveTo(9.5f, 14f)
  curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
  reflectiveCurveTo(7.01f, 5f, 9.5f, 5f)
  reflectiveCurveTo(14f, 7.01f, 14f, 9.5f)
  reflectiveCurveTo(11.99f, 14f, 9.5f, 14f)
  close()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.dismiss() {
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

private fun androidx.compose.ui.graphics.vector.PathBuilder.check() {
  moveTo(9f, 16.17f)
  lineTo(4.83f, 12f)
  lineTo(3.41f, 13.41f)
  lineTo(9f, 19f)
  lineTo(21f, 7f)
  lineTo(19.59f, 5.59f)
  close()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.calendar() {
  moveTo(19f, 3f)
  lineTo(18f, 3f)
  lineTo(18f, 1f)
  lineTo(16f, 1f)
  lineTo(16f, 3f)
  lineTo(8f, 3f)
  lineTo(8f, 1f)
  lineTo(6f, 1f)
  lineTo(6f, 3f)
  lineTo(5f, 3f)
  curveTo(3.89f, 3f, 3f, 3.9f, 3f, 5f)
  lineTo(3f, 19f)
  curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
  lineTo(19f, 21f)
  curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
  lineTo(21f, 5f)
  curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
  close()
  moveTo(19f, 8f)
  lineTo(5f, 8f)
  lineTo(5f, 19f)
  lineTo(19f, 19f)
  lineTo(19f, 8f)
  close()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.delete() {
  moveTo(6f, 19f)
  curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
  lineTo(16f, 21f)
  curveTo(17.1f, 21f, 18f, 20.1f, 18f, 19f)
  lineTo(18f, 7f)
  lineTo(6f, 7f)
  lineTo(6f, 19f)
  close()
  moveTo(19f, 4f)
  lineTo(15.5f, 4f)
  lineTo(14.5f, 3f)
  lineTo(9.5f, 3f)
  lineTo(8.5f, 4f)
  lineTo(5f, 4f)
  lineTo(5f, 6f)
  lineTo(19f, 6f)
  lineTo(19f, 4f)
  close()
}

/** Camera silhouette: a body with a viewfinder bump, solid like the other icons. */
private fun androidx.compose.ui.graphics.vector.PathBuilder.photo() {
  rectangle(3f, 7f, 21f, 17f)
  rectangle(8f, 4f, 16f, 7.5f)
}

/** Three labelled sliders. Same-direction rectangles merge under the non-zero fill rule. */
private fun androidx.compose.ui.graphics.vector.PathBuilder.settings() {
  rectangle(3f, 5f, 21f, 7f)
  rectangle(9f, 3f, 15f, 9f)
  rectangle(3f, 11f, 21f, 13f)
  rectangle(15f, 9f, 21f, 15f)
  rectangle(3f, 17f, 21f, 19f)
  rectangle(6f, 15f, 12f, 21f)
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.rectangle(
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
) {
  moveTo(left, top)
  lineTo(right, top)
  lineTo(right, bottom)
  lineTo(left, bottom)
  close()
}

private object ThumbnailCache {
  private val lru =
    object : LruCache<String, ImageBitmap>(4 * 1024) {
      override fun sizeOf(key: String, value: ImageBitmap): Int =
        (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }

  fun get(key: String): ImageBitmap? = lru.get(key)

  fun put(key: String, value: ImageBitmap) {
    lru.put(key, value)
  }
}

private fun decodeThumbnail(context: Context, reference: String): ImageBitmap? {
  val uri =
    when {
      reference.startsWith("content:") || reference.startsWith("file:") -> Uri.parse(reference)
      else -> null
    }
  return if (uri != null) decodeUri(context, uri) else decodeFile(File(reference))
}

private fun decodeFile(file: File): ImageBitmap? {
  if (!file.isFile) return null
  val length = file.length()
  if (length <= 0L || length > MaxPhotoBytes) return null
  return decodeSampled(
    bounds = {
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, options)
      options
    },
    decode = { sample ->
      BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    },
    orientation = {
      runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    },
  )
}

private fun decodeUri(context: Context, uri: Uri): ImageBitmap? {
  val resolver = context.contentResolver
  val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
  if (size > MaxPhotoBytes) return null
  return decodeSampled(
    bounds = {
      resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
        options
      } ?: BitmapFactory.Options()
    },
    decode = { sample ->
      resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        BitmapFactory.decodeFileDescriptor(
          descriptor.fileDescriptor,
          null,
          BitmapFactory.Options().apply { inSampleSize = sample },
        )
      }
    },
    orientation = {
      resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        runCatching {
          ExifInterface(descriptor.fileDescriptor)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
      } ?: ExifInterface.ORIENTATION_NORMAL
    },
  )
}

private fun decodeSampled(
  bounds: () -> BitmapFactory.Options,
  decode: (Int) -> Bitmap?,
  orientation: () -> Int,
): ImageBitmap? {
  val measured = bounds()
  if (measured.outWidth <= 0 || measured.outHeight <= 0) return null
  val decoded = decode(sampleSize(measured.outWidth, measured.outHeight, 256)) ?: return null
  return applyExifOrientation(decoded, orientation()).asImageBitmap()
}

private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
  var size = 1
  while (width / size > maxEdge || height / size > maxEdge) size *= 2
  return size
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
  val matrix = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
      matrix.setRotate(180f)
      matrix.postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_TRANSPOSE -> {
      matrix.setRotate(90f)
      matrix.postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
    ExifInterface.ORIENTATION_TRANSVERSE -> {
      matrix.setRotate(-90f)
      matrix.postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
    else -> return bitmap
  }
  val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  if (oriented != bitmap) bitmap.recycle()
  return oriented
}

private const val MaxPhotoBytes = 24 * 1024 * 1024

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FridgeContentPreview() {
  val today = LocalDate.now().toEpochDay()
  YumgoTheme {
    FridgeContent(
      state =
        FridgeUiState(
          load = FridgeLoad.Ready,
          items =
            listOf(
              FridgeItemUi("berries", "Strawberries", 1, today, null),
              FridgeItemUi("milk", "Milk", 2, today + 1, null),
              FridgeItemUi("yogurt", "Yogurt", 4, today + 5, null),
              FridgeItemUi("butter", "Butter", 1, today + 21, null),
              FridgeItemUi("rice", "Rice", 1, null, null),
            ),
        ),
      callbacks = IdleCallbacks,
      presets = listOf(FoodPreset("milk", "Milk", 7), FoodPreset("eggs", "Eggs", 14), FoodPreset("bread", "Bread", 5)),
    )
  }
}

private val IdleCallbacks =
  FridgeCallbacks(
    onQueryChange = {},
    onRetry = {},
    onAdd = {},
    onEdit = {},
    onQuantityChange = { _, _ -> },
    onDelete = {},
    onDraftChange = {},
    onDismissEditor = {},
    onSave = {},
    onTakePhoto = {},
    onPickPhoto = {},
    onRemovePhoto = {},
  )
