package com.dwk.yumgo.widget

import android.content.Context
import android.content.res.Resources
import com.dwk.yumgo.R
import com.dwk.yumgo.data.FridgeItem
import com.dwk.yumgo.data.FridgeRepository
import com.dwk.yumgo.data.OfflineFridgeRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Everything one render needs. The widget never holds a flow open between updates. */
internal sealed interface FridgeWidgetSnapshot {
  /** Visible rows in fridge order, plus how many are due within the next two days. */
  data class Items(val rows: List<FridgeWidgetRow>, val useSoon: Int) : FridgeWidgetSnapshot

  /** Nothing saved yet. */
  data object Empty : FridgeWidgetSnapshot

  /** The local copy could not be read. Tapping the widget opens the app, which retries. */
  data object Failed : FridgeWidgetSnapshot
}

/** One line of the widget. [label] is the date-relative text, [tone] colours its badge. */
internal data class FridgeWidgetRow(
  val name: String,
  val label: String,
  val tone: FridgeWidgetTone,
)

/**
 * Reads the fridge for the widget.
 *
 * The widget keeps its own [OfflineFridgeRepository] instead of borrowing the screen's, so a widget
 * update never depends on an activity being alive. One repository is shared per process: each
 * update only takes a fresh snapshot through [FridgeRepository.items], and a new helper per update
 * would leave a database connection behind.
 */
internal object FridgeWidgetData {
  @Volatile private var repository: FridgeRepository? = null

  fun repository(context: Context): FridgeRepository =
    repository
      ?: synchronized(this) {
        repository ?: OfflineFridgeRepository(context.applicationContext).also { repository = it }
      }

  suspend fun snapshot(context: Context): FridgeWidgetSnapshot =
    try {
      val today = LocalDate.now().toEpochDay()
      val items = repository(context).items.first()
      if (items.isEmpty()) {
        FridgeWidgetSnapshot.Empty
      } else {
        FridgeWidgetSnapshot.Items(
          rows = items.map { it.toRow(context, today) },
          useSoon = items.count { isUseSoon(it, today) },
        )
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      FridgeWidgetSnapshot.Failed
    }

  private fun FridgeItem.toRow(context: Context, today: Long): FridgeWidgetRow {
    val resources = context.resources
    val title = if (quantity > 1) resources.getString(R.string.widget_item_quantity, name, quantity) else name
    val delta = expiresOn?.let { it.toEpochDay() - today }
    return FridgeWidgetRow(
      name = title,
      label = expiryLabel(resources, delta),
      tone = toneOf(delta),
    )
  }

  /**
   * Mirrors the fridge screen's "use soon" bucket, expired items included, so the widget summary
   * counts the same items the app highlights.
   */
  private fun isUseSoon(item: FridgeItem, today: Long): Boolean {
    val expiresOn = item.expiresOn ?: return false
    return expiresOn.toEpochDay() - today <= UseSoonDays
  }

  private fun toneOf(delta: Long?): FridgeWidgetTone =
    when {
      delta == null -> FridgeWidgetTone.Unknown
      delta < 0L -> FridgeWidgetTone.Overdue
      delta <= UseSoonDays -> FridgeWidgetTone.Soon
      else -> FridgeWidgetTone.Upcoming
    }

  private fun expiryLabel(
    resources: Resources,
    delta: Long?,
  ): String {
    if (delta == null) return resources.getString(R.string.widget_expiry_no_date)
    return when {
      delta == 0L -> resources.getString(R.string.widget_expiry_today)
      delta == 1L -> resources.getString(R.string.widget_expiry_tomorrow)
      delta in 2L..7L ->
        resources.getQuantityString(R.plurals.widget_expiry_in_days, delta.toInt(), delta.toInt())
      delta < 0L ->
        resources.getQuantityString(
          R.plurals.widget_expiry_overdue,
          (-delta).toInt(),
          (-delta).toInt(),
        )
      else ->
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
          .withLocale(Locale.getDefault())
          .format(LocalDate.now().plusDays(delta))
    }
  }

  private const val UseSoonDays = 2L
}
