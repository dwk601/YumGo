@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.dwk.yumgo.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dwk.yumgo.R
import com.dwk.yumgo.theme.YumgoTheme
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fridge screen. Owns status, cutout, and navigation insets. Expects no ancestor
 * safe-drawing padding. [cameraContent] is supplied by the caller and replaces
 * this surface, including the editor sheet, until [FridgeUiState.cameraOpen] is
 * false again. The draft itself is left untouched.
 */
@Composable
fun FridgeContent(
  state: FridgeUiState,
  callbacks: FridgeCallbacks,
  modifier: Modifier = Modifier,
  cameraContent: @Composable () -> Unit = {},
) {
  val transition = updateTransition(targetState = state.cameraOpen, label = "camera")
  val cameraOnScreen = transition.currentState || transition.targetState
  val cameraFade = motionEffects<Float>()
  val cameraSlide = motionSpatial<IntOffset>()
  Box(modifier.fillMaxSize()) {
    if (!cameraOnScreen) {
      FridgeScaffold(state = state, callbacks = callbacks)
      state.draft?.let { draft -> ItemEditor(draft = draft, callbacks = callbacks) }
    }
    transition.AnimatedVisibility(
      visible = { open -> open },
      enter = fadeIn(cameraFade) + slideInVertically(cameraSlide) { it / 8 },
      exit = fadeOut(cameraFade) + slideOutVertically(cameraSlide) { it / 8 },
    ) {
      Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { cameraContent() }
    }
  }
}

@Composable
private fun FridgeScaffold(state: FridgeUiState, callbacks: FridgeCallbacks) {
  val screenInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
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
    topBar = { FridgeHeader(state = state, useSoon = useSoon, callbacks = callbacks) },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = callbacks.onAdd,
        icon = { Icon(FridgePlus, contentDescription = null) },
        text = { Text(stringResource(R.string.fridge_add)) },
      )
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
}

@Composable
private fun FridgeHeader(state: FridgeUiState, useSoon: Int, callbacks: FridgeCallbacks) {
  val headerInsets =
    WindowInsets.statusBars
      .union(WindowInsets.displayCutout)
      .union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
      .exclude(WindowInsets.ime)
      .exclude(WindowInsets.navigationBars)
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
    Text(text = stringResource(R.string.fridge_title), style = MaterialTheme.typography.headlineLarge)
    Text(
      text = subtitle,
      style = MaterialTheme.typography.bodyMedium,
      color = if (useSoon > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
    )
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
      Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 32.dp).padding(bottom = 72.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (showLoading) {
      LoadingIndicator(Modifier.padding(bottom = 20.dp).size(72.dp))
    }
    Text(text = title, style = MaterialTheme.typography.headlineSmall)
    if (!body.isNullOrBlank()) {
      Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
    Box(Modifier.padding(top = 8.dp)) { action() }
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
      TextButton(onClick = onRetry) { Text(stringResource(R.string.fridge_retry)) }
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

@Composable
private fun FridgeCard(item: FridgeItemUi, callbacks: FridgeCallbacks, modifier: Modifier = Modifier) {
  Surface(
    onClick = { callbacks.onEdit(item.id) },
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
  ) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      FridgeThumbnail(name = item.name, photoReference = item.photoReference)
      Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
        Text(text = item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          text = fridgeExpiryLabel(item.expiryEpochDay),
          style = MaterialTheme.typography.bodySmall,
          color = fridgeExpiryColor(item.expiryEpochDay),
          modifier = Modifier.padding(top = 2.dp),
        )
      }
      QuantityStepper(
        quantity = item.quantity,
        name = item.name,
        onQuantityChange = { callbacks.onQuantityChange(item.id, it) },
      )
    }
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
  Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { onQuantityChange(quantity - 1) }, enabled = enabled && quantity > 1) {
        Icon(FridgeMinus, contentDescription = stringResource(R.string.fridge_decrease_quantity, name))
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
        Icon(FridgePlus, contentDescription = stringResource(R.string.fridge_increase_quantity, name))
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
        Text(
          text = monogram(name),
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.clearAndSetSemantics {},
        )
      }
    }
  }
}

@Composable
private fun rememberThumbnail(reference: String?): ImageBitmap? {
  val context = LocalContext.current
  val bitmap by
    produceState<ImageBitmap?>(initialValue = null, reference) {
      value =
        if (reference.isNullOrBlank()) {
          null
        } else {
          withContext(Dispatchers.IO) { runCatching { decodeThumbnail(context, reference) }.getOrNull() }
        }
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

internal val FridgePlus: ImageVector by lazy { fridgeIcon("Plus") { plus() } }
internal val FridgeMinus: ImageVector by lazy { fridgeIcon("Minus") { minus() } }
private val FridgeSearch: ImageVector by lazy { fridgeIcon("Search") { search() } }
private val FridgeClose: ImageVector by lazy { fridgeIcon("Close") { dismiss() } }

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

private fun decodeThumbnail(context: Context, reference: String): ImageBitmap? {
  val bytes = readPhotoBytes(context, reference) ?: return null
  if (bytes.isEmpty() || bytes.size > MaxPhotoBytes) return null
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  val decoded =
    BitmapFactory.decodeByteArray(
      bytes,
      0,
      bytes.size,
      BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 256) },
    ) ?: return null
  val rotation =
    runCatching {
      ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
  return rotate(decoded, rotationDegrees(rotation)).asImageBitmap()
}

private fun readPhotoBytes(context: Context, reference: String): ByteArray? {
  val uri =
    when {
      reference.startsWith("content:") || reference.startsWith("file:") -> Uri.parse(reference)
      else -> null
    }
  return if (uri != null) {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
  } else {
    val file = File(reference)
    if (file.isFile) file.readBytes() else null
  }
}

private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
  var size = 1
  while (width / size > maxEdge || height / size > maxEdge) size *= 2
  return size
}

private fun rotationDegrees(orientation: Int): Int =
  when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_TRANSPOSE -> 90
    ExifInterface.ORIENTATION_ROTATE_180,
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
    ExifInterface.ORIENTATION_ROTATE_270,
    ExifInterface.ORIENTATION_TRANSVERSE -> 270
    else -> 0
  }

private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
  if (degrees == 0) return bitmap
  val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
  val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  if (rotated != bitmap) bitmap.recycle()
  return rotated
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
