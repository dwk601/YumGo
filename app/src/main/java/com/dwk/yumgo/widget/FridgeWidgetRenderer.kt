package com.dwk.yumgo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
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
  private const val MaxRows = 4
  private const val DefaultRows = 3
  private const val TwoColumnMinWidthDp = 380f

  /** Chrome above and below the rows: header, divider, root padding, and the "more" line. */
  private const val HeaderDp = 20f
  private const val DividerDp = 17f
  private const val FooterReserveDp = 18f
  private const val RootPaddingDp = 20f
  private const val RowPaddingDp = 6f
  private const val RowTextSp = 13f

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
    val twoColumns = isTwoColumns(context, options)
    val rowBudget = rowBudget(context, options, twoColumns)
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

  private fun isTwoColumns(context: Context, options: Bundle): Boolean {
    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    if (minWidth <= 0) return false
    return minWidth >= dp(context, TwoColumnMinWidthDp)
  }

  /**
   * Rows that fit the placed height. Row height is measured from the text itself so a large font
   * scale shows fewer rows instead of clipping the last one.
   */
  private fun rowBudget(context: Context, options: Bundle, twoColumns: Boolean): Int {
    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
    if (minHeight <= 0) return DefaultRows
    val rowHeight = textHeightPx(context, RowTextSp) + dp(context, RowPaddingDp)
    val chrome =
      dp(context, HeaderDp) +
        dp(context, DividerDp) +
        dp(context, FooterReserveDp) +
        dp(context, RootPaddingDp)
    val fitting = ((minHeight - chrome) / rowHeight).toInt().coerceAtLeast(1)
    if (!twoColumns) return fitting.coerceAtMost(MaxRows)
    // Two equal columns: never show a half-filled second column.
    return (fitting - fitting % 2).coerceIn(2, MaxRows)
  }

  private fun textHeightPx(context: Context, sizeSp: Float): Float {
    val paint = TextPaint()
    paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
    val metrics = paint.fontMetrics
    return metrics.descent - metrics.ascent
  }

  private fun dp(context: Context, value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

  /** Explicit and immutable, so no other app can retarget the tap. */
  private fun openFridge(context: Context): PendingIntent {
    val intent =
      Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
    return PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}
