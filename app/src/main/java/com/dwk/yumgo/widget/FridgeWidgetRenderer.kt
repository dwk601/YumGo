package com.dwk.yumgo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import com.dwk.yumgo.MainActivity
import com.dwk.yumgo.R

/** Urgency colours for one item's date badge. Fixed light values, not the app theme. */
internal enum class FridgeWidgetTone(val background: Int, val text: Int) {
  Overdue(R.drawable.fridge_widget_badge_overdue, 0xFF410002.toInt()),
  Soon(R.drawable.fridge_widget_badge_soon, 0xFF2D1600.toInt()),
  Upcoming(R.drawable.fridge_widget_badge_upcoming, 0xFF002114.toInt()),
  Unknown(R.drawable.fridge_widget_badge_unknown, 0xFF444842.toInt()),
}

/**
 * Paints every placed instance from one snapshot.
 *
 * The card always uses the light palette: it reads the app's light colours from resources and
 * ignores the device's darkness and any saved app theme. Rows come from the repository's order, so
 * the widget lists the same items, in the same order, as the fridge screen.
 *
 * The row count is worked out here, from the height the host reported and the size of the text at
 * this moment, and a card wide enough for two draws its rows in two columns, which is the only
 * thing the extra width buys. Both are then handed to the card as fixed pixel sizes, so the
 * launcher's own layout can only reproduce the card as it was budgeted: a system font-scale change
 * in between neither clips a row nor leaves a gap, and the new scale arrives with the next refresh.
 */
internal object FridgeWidgetRenderer {
  private const val DefaultRows = 3

  /** The width from which a second column is worth it: two names side by side still read. */
  private const val TwoColumnMinWidthDp = 380f
  private const val IconDp = 18f
  private const val DividerDp = 17f
  private const val RootPaddingDp = 20f
  private const val FooterMarginDp = 4f
  private const val RowPaddingDp = 6f

  /** Rounding slack, so a row that only just fits is not clipped by a fraction of a pixel. */
  private const val RowSafetyDp = 1f
  private const val RowTextSp = 13f
  private const val TitleSp = 13f
  private const val SummarySp = 11f
  private const val BadgeSp = 11f
  private const val FooterTextSp = 11f
  private const val MessageSp = 12f

  /** The box the card sits in, in dp. */
  private data class WidgetSize(val widthDp: Float, val heightDp: Float)

  /** How many items the card shows, and how many that leaves off the list. */
  private data class RowWindow(val rows: Int, val hidden: Int)

  /**
   * Text sizes in px, resolved once per card and set on the views as fixed pixel sizes.
   *
   * These are the same values [textHeightDp] measures, so the rows the host lays out are the rows
   * the budget counted. Freezing them is what keeps a later font-scale change from clipping a row
   * or opening a gap under a "more" line that still counts the items the user cannot see.
   */
  private data class TextPx(
    val title: Float,
    val summary: Float,
    val row: Float,
    val badge: Float,
    val more: Float,
    val message: Float,
  )

  suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
    val snapshot = FridgeWidgetData.snapshot(context)
    ids.forEach { id -> renderOne(context, manager, id, snapshot) }
  }

  private fun renderOne(
    context: Context,
    manager: AppWidgetManager,
    id: Int,
    snapshot: FridgeWidgetSnapshot,
  ) {
    val options = manager.getAppWidgetOptions(id)
    manager.updateAppWidget(id, cardFor(context, options, snapshot))
  }

  /**
   * The views for this instance, one card per size the host reports on API 31 and newer.
   *
   * Handing the host a size-keyed map is what makes the card independent of the app's own
   * rotation: the app can be in landscape while the home screen stays portrait, and guessing from
   * the app's configuration then built a portrait card with landscape's two columns. The host picks
   * the closest size to the box it lays the widget out in, and re-picks it whenever the widget is
   * resized or re-laid out.
   *
   * Before API 31 there is no list to map, so the single card falls back to the platform's min/max
   * options. [legacySize] reads those without asking the app which way round it is.
   */
  private fun cardFor(
    context: Context,
    options: Bundle,
    snapshot: FridgeWidgetSnapshot,
  ): RemoteViews {
    val openFridge = openFridge(context)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val bySize =
        reportedSizes(options).distinct().associateWith { size ->
          cardViews(context, WidgetSize(size.width, size.height), snapshot, openFridge)
        }
      if (bySize.isNotEmpty()) return RemoteViews(bySize)
    }
    return cardViews(context, legacySize(options), snapshot, openFridge)
  }

  private fun cardViews(
    context: Context,
    size: WidgetSize?,
    snapshot: FridgeWidgetSnapshot,
    openFridge: PendingIntent,
  ): RemoteViews {
    val text = textPx(context)
    val views = RemoteViews(context.packageName, R.layout.fridge_widget)
    // A redraw replays these actions on the views already on the home screen, so the previous rows
    // have to go first. Without this they pile up, and rows hidden by an empty or failed render
    // come back on the next successful one.
    views.removeAllViews(R.id.widget_column_start)
    views.removeAllViews(R.id.widget_column_end)
    views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_title))
    views.setOnClickPendingIntent(R.id.fridge_widget_root, openFridge)
    setTextSizes(views, text, card = true)

    when (snapshot) {
      FridgeWidgetSnapshot.Empty -> {
        views.setViewVisibility(R.id.widget_columns, View.GONE)
        views.setViewVisibility(R.id.widget_message, View.VISIBLE)
        views.setTextViewText(R.id.widget_message, context.getString(R.string.widget_empty_title))
        views.setTextViewText(R.id.widget_summary, context.getString(R.string.widget_summary_empty))
        views.setViewVisibility(R.id.widget_more, View.GONE)
      }
      FridgeWidgetSnapshot.Failed -> {
        views.setViewVisibility(R.id.widget_columns, View.GONE)
        views.setViewVisibility(R.id.widget_message, View.VISIBLE)
        views.setTextViewText(R.id.widget_message, context.getString(R.string.widget_error))
        views.setTextViewText(R.id.widget_summary, "")
        views.setViewVisibility(R.id.widget_more, View.GONE)
      }
      is FridgeWidgetSnapshot.Items -> renderItems(context, views, size, snapshot, text)
    }
    return views
  }

  private fun renderItems(
    context: Context,
    views: RemoteViews,
    size: WidgetSize?,
    snapshot: FridgeWidgetSnapshot.Items,
    text: TextPx,
  ) {
    val total = snapshot.rows.size
    // A second column stands the full height of the card, so a card wide enough for two fits twice
    // the rows in the same box. The rows are then dealt out between the columns in fridge order.
    val twoColumns = size != null && size.widthDp >= TwoColumnMinWidthDp
    val window = rowWindow(context, size?.heightDp, total, if (twoColumns) 2 else 1)
    val rows = window.rows
    val hidden = window.hidden

    views.setViewVisibility(R.id.widget_message, View.GONE)
    views.setViewVisibility(R.id.widget_columns, View.VISIBLE)
    views.setViewVisibility(R.id.widget_column_end, if (twoColumns) View.VISIBLE else View.GONE)
    views.setTextViewText(
      R.id.widget_summary,
      if (snapshot.useSoon > 0) {
        context.resources.getQuantityString(
          R.plurals.widget_summary_use_soon,
          snapshot.useSoon,
          snapshot.useSoon,
        )
      } else {
        context.getString(R.string.widget_summary_calm)
      },
    )

    snapshot.rows.take(rows).forEachIndexed { index, row ->
      // The first half goes down the left column, so an odd count leaves the extra row on the right
      // and the reading order is still the fridge's own.
      val column =
        if (twoColumns && index >= (rows + 1) / 2) R.id.widget_column_end else R.id.widget_column_start
      views.addView(column, rowView(context, row, text))
    }

    if (hidden > 0) {
      views.setViewVisibility(R.id.widget_more, View.VISIBLE)
      views.setTextViewText(R.id.widget_more, context.getString(R.string.widget_more_items, hidden))
    } else {
      views.setViewVisibility(R.id.widget_more, View.GONE)
    }
  }

  private fun rowView(context: Context, row: FridgeWidgetRow, text: TextPx): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.fridge_widget_row)
    views.setTextViewText(R.id.widget_row_name, row.name)
    views.setTextViewText(R.id.widget_row_expiry, row.label)
    views.setTextColor(R.id.widget_row_expiry, row.tone.text)
    views.setInt(R.id.widget_row_expiry, "setBackgroundResource", row.tone.background)
    setTextSizes(views, text, card = false)
    return views
  }

  /**
   * Pins the card's text to the pixel sizes the budget was measured against. Without this the
   * layout would scale the text again at the host, and a font-scale change between a refresh and
   * the next one would move the rows out from under the count the card was built with.
   */
  private fun setTextSizes(views: RemoteViews, text: TextPx, card: Boolean) {
    fun size(viewId: Int, px: Float) =
      views.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_PX, px)
    if (card) {
      size(R.id.widget_title, text.title)
      size(R.id.widget_summary, text.summary)
      size(R.id.widget_more, text.more)
      size(R.id.widget_message, text.message)
    } else {
      size(R.id.widget_row_name, text.row)
      size(R.id.widget_row_expiry, text.badge)
    }
  }

  private fun textPx(context: Context): TextPx {
    fun px(sizeSp: Float) =
      TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
    return TextPx(
      title = px(TitleSp),
      summary = px(SummarySp),
      row = px(RowTextSp),
      badge = px(BadgeSp),
      more = px(FooterTextSp),
      message = px(MessageSp),
    )
  }

  /**
   * How many items a card this tall shows, and how many that leaves off the list.
   *
   * [columns] columns each stand the full height, so what fits is the rows one column holds times
   * the columns. There is no cap: the card is a summary only because the size it was placed at is
   * finite, and a tall card should use the room it has.
   *
   * The "more" line is only paid for when something is actually left over, so a fridge that fits
   * gets the whole card. If it does not fit, the line is inside the budget, which is what keeps the
   * last row from being cut in half.
   */
  private fun rowWindow(context: Context, heightDp: Float?, total: Int, columns: Int): RowWindow {
    val height = heightDp ?: return RowWindow(DefaultRows, (total - DefaultRows).coerceAtLeast(0))
    val row = rowHeightDp(context)
    val all = columns * fit(height, chromeDp(context, withFooter = false), row)
    if (total <= all) return RowWindow(total, 0)
    val rows = (columns * fit(height, chromeDp(context, withFooter = true), row)).coerceAtMost(total)
    return RowWindow(rows, total - rows)
  }

  /** Whole rows in [heightDp] once the chrome and the "more" line are paid for. */
  private fun fit(heightDp: Float, chromeDp: Float, rowDp: Float): Int =
    ((heightDp - chromeDp) / rowDp).toInt().coerceAtLeast(1)

  /**
   * Chrome above and below the rows, in dp: the header, the rule under it, the "more" line, and the
   * card's own padding. The header and the "more" line hold text, so their heights are measured
   * rather than guessed: at a large font scale a fixed guess lets one row too many through and the
   * host clips the last one.
   */
  private fun chromeDp(context: Context, withFooter: Boolean): Float =
    maxOf(IconDp, textHeightDp(context, TitleSp)) +
      DividerDp +
      (if (withFooter) textHeightDp(context, FooterTextSp) + FooterMarginDp else 0f) +
      RootPaddingDp

  /**
   * The size to build for when the host reports no list of configurations, that is before API 31.
   *
   * Every number here is the launcher's own, in dp. The platform reports the smallest and the
   * largest box it will give this instance, for the orientation the home screen is in, and the two
   * largest are bounded by the launcher's own screen: whichever of them is the taller side says
   * which way round the home screen is. The app's configuration cannot say it, because the app can
   * be in landscape while the home screen stays in portrait, and the card is drawn by the launcher
   * in the launcher's configuration, not in the app's.
   *
   * A placement grows along the screen's short axis, which is what pairs the narrow width with the
   * tall height in portrait and the wide one with the short height in landscape. A host that
   * reports no maximum at all has said nothing about its orientation, so the card is built for the
   * smallest box it did report: the one size every box it may be given can hold without clipping.
   */
  private fun legacySize(options: Bundle): WidgetSize? {
    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
    val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
    if (minWidth <= 0 || minHeight <= 0) return null
    if (maxWidth <= 0 || maxHeight <= 0) return WidgetSize(minWidth.toFloat(), minHeight.toFloat())
    val portrait = maxWidth <= maxHeight
    val width = if (portrait) minWidth else maxWidth
    val height = if (portrait) maxHeight else minHeight
    if (width <= 0 || height <= 0) return null
    return WidgetSize(widthDp = width.toFloat(), heightDp = height.toFloat())
  }

  /**
   * The sizes the host offers on API 31 and newer: one per configuration, in dp.
   *
   * [AppWidgetManager.OPTION_APPWIDGET_SIZES] is a String constant, so it is inlined and reading it
   * on an older release is harmless: the key simply is not in the bundle there, and the caller falls
   * back to the min/max options.
   */
  @Suppress("InlinedApi")
  private fun reportedSizes(options: Bundle): List<SizeF> =
    runCatching {
      BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        ?.filterIsInstance<SizeF>()
        .orEmpty()
    }.getOrDefault(emptyList())

  private fun rowHeightDp(context: Context): Float =
    textHeightDp(context, RowTextSp) + RowPaddingDp + RowSafetyDp

  /**
   * Height of one line of text, in dp. [android.text.TextPaint.FontMetrics.top] and bottom are the
   * ones a wrap_content TextView measures itself with, font padding included; ascent and descent
   * leave that out and under-report by a few dp, which is enough to clip the last row.
   */
  private fun textHeightDp(context: Context, sizeSp: Float): Float {
    val metrics = context.resources.displayMetrics
    val paint = TextPaint()
    paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics)
    val fontMetrics = paint.fontMetrics
    return (fontMetrics.bottom - fontMetrics.top) / metrics.density
  }

  /**
   * Explicit and immutable, so no other app can retarget the tap.
   *
   * The flags are the launcher's own: they bring the running task forward and, because
   * [Intent.FLAG_ACTIVITY_SINGLE_TOP] is set, hand the tap to the activity that is already showing.
   * A tap must never restart [MainActivity] and throw away an open sheet or an unsaved draft.
   */
  private fun openFridge(context: Context): PendingIntent {
    val intent =
      Intent(context, MainActivity::class.java).apply {
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
      }
    return PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}
