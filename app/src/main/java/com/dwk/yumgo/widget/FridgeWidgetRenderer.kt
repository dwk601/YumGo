package com.dwk.yumgo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
 */
internal object FridgeWidgetRenderer {
  private const val MaxRows = 5
  private const val DefaultRows = 3
  private const val TwoColumnMinWidthDp = 380f
  private const val IconDp = 18f
  private const val DividerDp = 17f
  private const val RootPaddingDp = 20f
  private const val FooterMarginDp = 4f
  private const val RowPaddingDp = 6f
  private const val RowTextSp = 13f
  private const val TitleSp = 13f
  private const val FooterTextSp = 11f

  /**
   * Chrome above and below the rows, in dp: the header, the rule under it, the "more" line, and the
   * card's own padding. The header and the "more" line hold text, so their heights are measured
   * rather than guessed: at a large font scale a fixed guess lets one row too many through and the
   * launcher clips the last one.
   */
  private fun chromeDp(context: Context): Float =
    maxOf(IconDp, textHeightDp(context, TitleSp)) +
      DividerDp +
      textHeightDp(context, FooterTextSp) +
      FooterMarginDp +
      RootPaddingDp

  /** The box the card is on screen at, in dp. */
  private data class WidgetSize(val widthDp: Float, val heightDp: Float)

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
    val views = RemoteViews(context.packageName, R.layout.fridge_widget)
    // A redraw replays these actions on the views already on the home screen, so the previous rows
    // have to go first. Without this they pile up, and rows hidden by an empty or failed render
    // come back on the next successful one.
    views.removeAllViews(R.id.widget_column_start)
    views.removeAllViews(R.id.widget_column_end)
    views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_title))
    views.setOnClickPendingIntent(R.id.fridge_widget_root, openFridge(context))

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
      is FridgeWidgetSnapshot.Items -> renderItems(context, views, options, snapshot)
    }

    manager.updateAppWidget(id, views)
  }

  private fun renderItems(
    context: Context,
    views: RemoteViews,
    options: Bundle,
    snapshot: FridgeWidgetSnapshot.Items,
  ) {
    val size = currentSize(context, options)
    val twoColumns = isTwoColumns(size)
    val rowBudget = rowBudget(context, size, twoColumns)
    val rows = snapshot.rows.take(rowBudget)
    val hidden = snapshot.rows.size - rows.size

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

    rows.forEachIndexed { index, row ->
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

  private fun isTwoColumns(size: WidgetSize?): Boolean =
    size != null && size.widthDp >= TwoColumnMinWidthDp

  /**
   * Rows that fit the height the card is on screen at. Row height is measured from the text itself
   * so a large font scale shows fewer rows instead of clipping the last one.
   */
  private fun rowBudget(context: Context, size: WidgetSize?, twoColumns: Boolean): Int {
    val heightDp = size?.heightDp ?: return DefaultRows
    val fitting = ((heightDp - chromeDp(context)) / rowHeightDp(context)).toInt().coerceAtLeast(1)
    if (!twoColumns) return fitting.coerceAtMost(MaxRows)
    // Two equal columns: never show a half-filled second column.
    return (fitting - fitting % 2).coerceIn(2, MaxRows)
  }

  /**
   * The size the card is on screen at, in dp, or null when the host reports none.
   *
   * The min and max options bracket the smallest and largest box the launcher may hand the widget,
   * so budgeting from the minimum leaves most of a normally sized card empty. API 31 and newer
   * report the size of each configuration the widget can take instead, as a list of [SizeF] in dp;
   * the one in use is the tall entry in portrait and the wide entry in landscape, whatever order
   * the host listed them in. Before that, the minimum is the portrait size and the maximum is the
   * landscape one. Every option here is in dp, not pixels.
   */
  private fun currentSize(context: Context, options: Bundle): WidgetSize? {
    val portrait = isPortrait(context)
    val reported = reportedSizes(options)
    val inUse = reported.maxByOrNull { if (portrait) it.height else it.width }
    if (inUse != null) return WidgetSize(widthDp = inUse.width, heightDp = inUse.height)

    val width =
      options.getInt(
        if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
        0,
      )
    val height =
      options.getInt(
        if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
        0,
      )
    if (width <= 0 || height <= 0) return null
    return WidgetSize(widthDp = width.toFloat(), heightDp = height.toFloat())
  }

  private fun reportedSizes(options: Bundle): List<SizeF> =
    runCatching {
      BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        ?.filterIsInstance<SizeF>()
        .orEmpty()
    }.getOrDefault(emptyList())

  /** True for portrait and for the square-ish shapes a watch or an unfolded foldable reports. */
  private fun isPortrait(context: Context): Boolean {
    when (context.resources.configuration.orientation) {
      Configuration.ORIENTATION_PORTRAIT -> return true
      Configuration.ORIENTATION_LANDSCAPE -> return false
    }
    val metrics = context.resources.displayMetrics
    return metrics.heightPixels >= metrics.widthPixels
  }

  private fun rowHeightDp(context: Context): Float = textHeightDp(context, RowTextSp) + RowPaddingDp

  private fun textHeightDp(context: Context, sizeSp: Float): Float {
    val metrics = context.resources.displayMetrics
    val paint = TextPaint()
    paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics)
    val fontMetrics = paint.fontMetrics
    return (fontMetrics.descent - fontMetrics.ascent) / metrics.density
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
