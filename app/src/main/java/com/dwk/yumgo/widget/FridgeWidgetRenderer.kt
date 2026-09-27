package com.dwk.yumgo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
 * Failing safe matters here because the row count is worked out when the card is built, while the
 * text is measured when the host lays it out. A system font-scale change therefore leaves a card
 * that was budgeted for the old text until the next update. The layout absorbs that: the rows sit
 * in a container of their own that the host clips, and the "more" line sits outside it, so a stale
 * budget cuts the tail of the list and never the summary.
 */
internal object FridgeWidgetRenderer {
  private const val DefaultRows = 3
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
  private const val FooterTextSp = 11f

  /** The box the card sits in, in dp. */
  private data class WidgetSize(val widthDp: Float, val heightDp: Float)

  /** How many items the card shows, and how many that leaves off the list. */
  private data class RowWindow(val rows: Int, val hidden: Int)

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
   * the closest size and re-picks it whenever the widget is resized or re-laid out.
   */
  private fun cardFor(
    context: Context,
    options: Bundle,
    snapshot: FridgeWidgetSnapshot,
  ): RemoteViews {
    val openFridge = openFridge(context)
    val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) reportedSizes(options).distinct() else emptyList()
    if (sizes.isNotEmpty()) {
      val bySize =
        sizes.associateWith { size ->
          cardViews(context, WidgetSize(size.width, size.height), snapshot, openFridge)
        }
      return RemoteViews(bySize)
    }
    return cardViews(context, legacySize(context, options), snapshot, openFridge)
  }

  private fun cardViews(
    context: Context,
    size: WidgetSize?,
    snapshot: FridgeWidgetSnapshot,
    openFridge: PendingIntent,
  ): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.fridge_widget)
    // A redraw replays these actions on the views already on the home screen, so the previous rows
    // have to go first. Without this they pile up, and rows hidden by an empty or failed render
    // come back on the next successful one.
    views.removeAllViews(R.id.widget_column_start)
    views.removeAllViews(R.id.widget_column_end)
    views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_title))
    views.setOnClickPendingIntent(R.id.fridge_widget_root, openFridge)

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
      is FridgeWidgetSnapshot.Items -> renderItems(context, views, size, snapshot)
    }
    return views
  }

  private fun renderItems(
    context: Context,
    views: RemoteViews,
    size: WidgetSize?,
    snapshot: FridgeWidgetSnapshot.Items,
  ) {
    val total = snapshot.rows.size
    val window = rowWindow(context, size, total)
    // Two columns only when a whole pair still fits, so the second column is never half empty and
    // rows are never clipped to make room for it.
    val twoColumns = size != null && size.widthDp >= TwoColumnMinWidthDp && window.rows >= 2
    val rows = if (twoColumns) window.rows - window.rows % 2 else window.rows
    val hidden = total - rows

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
      val column = if (twoColumns && index % 2 == 1) R.id.widget_column_end else R.id.widget_column_start
      views.addView(column, rowView(context, row))
    }

    if (hidden > 0) {
      views.setViewVisibility(R.id.widget_more, View.VISIBLE)
      views.setTextViewText(R.id.widget_more, context.getString(R.string.widget_more_items, hidden))
    } else {
      views.setViewVisibility(R.id.widget_more, View.GONE)
    }
  }

  private fun rowView(context: Context, row: FridgeWidgetRow): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.fridge_widget_row)
    views.setTextViewText(R.id.widget_row_name, row.name)
    views.setTextViewText(R.id.widget_row_expiry, row.label)
    views.setTextColor(R.id.widget_row_expiry, row.tone.text)
    views.setInt(R.id.widget_row_expiry, "setBackgroundResource", row.tone.background)
    return views
  }

  /**
   * Rows for this height, and the items that leaves off the list. There is no cap: the card is a
   * summary only because the size it was placed at is finite, and a tall card should use the room
   * it has.
   *
   * The "more" line is only paid for when something is actually left over, so a fridge that fits
   * gets the whole card. If it does not fit, the line is inside the budget, which is what keeps the
   * last row from being cut in half.
   */
  private fun rowWindow(context: Context, size: WidgetSize?, total: Int): RowWindow {
    val heightDp = size?.heightDp
      ?: return RowWindow(DefaultRows, (total - DefaultRows).coerceAtLeast(0))
    val row = rowHeightDp(context)
    val all = ((heightDp - chromeDp(context, withFooter = false)) / row).toInt().coerceAtLeast(1)
    if (total <= all) return RowWindow(total, 0)
    val truncated = ((heightDp - chromeDp(context, withFooter = true)) / row).toInt().coerceAtLeast(1)
    return RowWindow(truncated.coerceAtMost(total), (total - truncated).coerceAtLeast(0))
  }

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
   * The size to build for when the host does not report a list, that is before API 31. The
   * platform pairs the narrow width with the tall height: portrait is minWidth by maxHeight and
   * landscape is maxWidth by minHeight. Every option is in dp.
   */
  private fun legacySize(context: Context, options: Bundle): WidgetSize? {
    val portrait = isPortrait(context)
    val width = options.getInt(
      if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
      0,
    )
    val height = options.getInt(
      if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
      0,
    )
    if (width <= 0 || height <= 0) return null
    return WidgetSize(widthDp = width.toFloat(), heightDp = height.toFloat())
  }

  /** The sizes the host offers on API 31 and newer: one per configuration, in dp. */
  private fun reportedSizes(options: Bundle): List<SizeF> =
    runCatching {
      BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        ?.filterIsInstance<SizeF>()
        .orEmpty()
    }.getOrDefault(emptyList())

  /** Only the pre-API-31 path needs this; the host picks the size otherwise. */
  private fun isPortrait(context: Context): Boolean {
    when (context.resources.configuration.orientation) {
      Configuration.ORIENTATION_PORTRAIT -> return true
      Configuration.ORIENTATION_LANDSCAPE -> return false
    }
    val metrics = context.resources.displayMetrics
    return metrics.heightPixels >= metrics.widthPixels
  }

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
