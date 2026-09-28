package com.dwk.yumgo

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.dwk.yumgo.data.FridgeRepository
import com.dwk.yumgo.data.NewFridgeItem
import com.dwk.yumgo.data.SettingsServices
import com.dwk.yumgo.data.ThemeMode
import com.dwk.yumgo.ui.main.PhotoStoreHolder
import com.dwk.yumgo.widget.FridgeWidgetData
import com.dwk.yumgo.widget.FridgeWidgetProvider
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher

/**
 * The home-screen widget, held for real.
 *
 * The test holds a placed instance with its own [AppWidgetHost] and reads the views the *system*
 * rendered for it, so every assertion is about what a launcher would really draw. Placement needs
 * the same bind grant a launcher already has, which is what the `appwidget grantbind` shell
 * command sets up; without it the system silently refuses to bind a non-launcher package.
 *
 * The fridge is changed through the app's own repository and UI, because a refresh follows a
 * committed write rather than the widget.
 */
@RunWith(AndroidJUnit4::class)
class WidgetWorkflowTest {
  private val composeRule = createAndroidComposeRule<MainActivity>()

  @get:Rule val rule: TestRule = RuleChain.outerRule(ClearFridgeForWidgetRule()).around(composeRule)

  private lateinit var host: AppWidgetHost
  private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
  private var widget: AppWidgetHostView? = null

  @Before
  fun bind() {
    val context = context()
    val provider = ComponentName(context, FridgeWidgetProvider::class.java)
    val manager = AppWidgetManager.getInstance(context)
    // The picker lists installed home-screen providers; this is the check that we are on that list.
    assertTrue(
      "The widget is not in the launcher's provider list",
      manager.installedProviders.any { it.provider == provider },
    )
    // Only a launcher may bind a widget out of the box. This is the grant a launcher already has.
    shell("appwidget grantbind --package ${context.packageName} --user 0")
    composeRule.runOnUiThread { host = AppWidgetHost(context, HostId) }
    composeRule.runOnUiThread { host.startListening() }
    composeRule.runOnUiThread { widgetId = host.allocateAppWidgetId() }
    val bound = manager.bindAppWidgetIdIfAllowed(widgetId, Process.myUserHandle(), provider, null)
    assertTrue("The system refused to bind the widget, so nothing after this is real", bound)
    val info = manager.getAppWidgetInfo(widgetId)
    assertNotNull("A bound widget must resolve to provider info", info)
    composeRule.runOnUiThread { widget = host.createView(context, widgetId, info!!) }
    // Each test starts from a size the test chose. The system hands a re-bound host whatever it
    // had last, so a test that resized the card would otherwise decide the next one's row count.
    resize(250, 200)
    awaitWidget(15_000) { startColumn().childCount >= 0 }
    // The app must see its own instance, or a saved write would never redraw the card.
    awaitWidget(10_000) { FridgeWidgetProvider.placedIds(context()).contains(widgetId) }
  }

  @After
  fun release() {
    widget = null
    runCatching { composeRule.runOnUiThread { host.deleteAppWidgetId(widgetId) } }
    runCatching { composeRule.runOnUiThread { host.stopListening() } }
  }

  /**
   * The whole round trip: an empty fridge says so, every committed write redraws the card, the rows
   * read nearest-expiry first, and repeated mutations never stack rows on top of each other.
   */
  @Test
  fun placedWidget_emptyThenEveryMutationRefreshesInPlace() {
    awaitWidget { messageText() == "The fridge is quiet" }
    assertEquals("Nothing in here yet", summaryText())
    assertEquals("An empty fridge must not keep the sample rows", 0, rows().size)

    add("Later Rice", days = 40)
    awaitWidget { rows().size == 1 }
    assertEquals("Later Rice", rowName(0))
    assertEquals(dateLabel(40), rowLabel(0))
    assertEquals("Nothing expiring soon", summaryText())

    // A write that only changes the quantity still redraws, and the row shows the new count.
    add("Use Soon Yoghurt", days = 1)
    awaitWidget { rows().size == 2 }
    assertEquals("Nearest expiry first", "Use Soon Yoghurt", rowName(0))
    assertEquals("Tomorrow", rowLabel(0))
    assertEquals("1 to use soon", summaryText())
    increase("Later Rice")
    awaitWidget { rowName(1) == "Later Rice · 2" }
    assertEquals(2, rows().size)

    // Repeated mutations: the card is redrawn, never appended to.
    repeat(3) { step ->
      increase("Later Rice")
      awaitWidget { rowName(1) == "Later Rice · ${step + 2}" }
      assertEquals("Rows piled up after ${step + 1} quantity changes", 2, rows().size)
    }

    // A delete drops exactly one row and Undo brings that same row back.
    deleteThroughTheApp("Later Rice")
    awaitWidget { rows().size == 1 }
    assertEquals("Use Soon Yoghurt", rowName(0))
    composeRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
    awaitWidget { rows().size == 2 }
    assertEquals("Later Rice · 5", rowName(1))
    assertEquals(2, rows().size)

    // One more add after all of that: still three rows, no leftovers from earlier renders.
    add("No Date Rice", days = null)
    awaitWidget { rows().size == 3 }
    assertEquals("No date", rowLabel(2))
    assertEquals("Three items in three rows leaves nothing to hide", "", moreText())
  }

  /**
   * The width of the card decides how many columns it has, and the height decides how many rows
   * each of them holds, so a wider card is not a taller card with fewer rows in it: the extra
   * width doubles what fits.
   *
   * Nothing here is asserted about how many rows a height really holds, because that is a
   * measurement of the text on this device rather than a promise. What has to hold is that a bigger
   * card never shows fewer items, and that every item is either drawn or counted in the overflow
   * line.
   */
  @Test
  fun placedWidget_resizeGivesMoreRowsInTwoColumns() {
    repeat(6) { add("Bulk Item $it", days = it.toLong() + 1) }
    awaitWidget { rows().isNotEmpty() }

    // Narrow and short: one column, a row or two, and the rest behind the overflow line.
    resize(250, 110)
    awaitWidget { rows().isNotEmpty() && endColumn().visibility == View.GONE }
    val small = rows().size
    assertTrue("A 250x110 card should not fit many rows, got $small", small in 1..3)
    assertEverythingAccountedFor(6)
    assertEquals("A narrow card must not open a second column", View.GONE, endColumn().visibility)

    // The same height, twice the width: two columns, so twice the items in the same box.
    resize(430, 110)
    awaitWidget { twoEvenColumns() }
    val wide = startColumn().childCount + endColumn().childCount
    assertTrue("A wide card should fit more than the small one did, $wide against $small", wide > small)
    assertEverythingAccountedFor(6)
    withCardLaidOutAt(430, 110) { assertNothingClipped(110) }

    // The same width, with room for the whole list: all of it, over two even columns.
    resize(430, 180)
    awaitWidget { rows().size == 6 && twoEvenColumns() }
    assertEquals("A 430x180 card holds all six, so nothing is hidden", "", moreText())
    withCardLaidOutAt(430, 180) { assertNothingClipped(180) }

    // And one narrow column, as tall as the card may be, still shows all of them.
    resize(250, 360)
    awaitWidget { rows().size == 6 && endColumn().visibility == View.GONE }
    assertEquals("A 250x360 card holds all six, so nothing is hidden", "", moreText())
    withCardLaidOutAt(250, 360) { assertNothingClipped(360) }
  }

  /**
   * Every item the card knows about is either drawn on it or counted in the overflow line. This is
   * the one thing that has to be true at any size: a card that quietly drops an item is a card
   * lying about the fridge.
   */
  private fun assertEverythingAccountedFor(total: Int) {
    assertEquals(
      "The card drew ${rows().size} rows and counted ${hiddenCount()} behind the overflow line",
      total,
      rows().size + hiddenCount(),
    )
  }

  /** The items the overflow line says are not on the card, or none when it is not showing. */
  private fun hiddenCount(): Int = moreText().removePrefix("+").removeSuffix(" more").toIntOrNull() ?: 0

  /**
   * The card is always the light palette. The app is switched to its dark theme and the card is
   * redrawn: the same fixed ink on the same fixed card colour.
   */
  @Test
  fun placedWidget_staysLightWhenTheAppIsDark() {
    add("Dark Mode Peas", days = 3)
    awaitWidget { rows().size == 1 }
    val lightInk = rowInk()
    val lightCard = cardColour()

    runBlocking { SettingsServices.preferences(context()).setThemeMode(ThemeMode.Dark) }
    add("Dark Mode Beans", days = 2)
    awaitWidget { rows().size == 2 }
    assertEquals("The row ink followed the app into dark", lightInk, rowInk())
    assertEquals("The card followed the app into dark", lightCard, cardColour())
    assertEquals("The card is a fixed light surface, not a themed one", 0xFFF7F6F2.toInt(), lightCard)
    assertTrue("Dark ink on a dark card is unreadable", luminance(lightInk) < 0.5)
    runBlocking { SettingsServices.preferences(context()).setThemeMode(ThemeMode.Light) }
  }

  /**
   * A tap brings the running app forward instead of starting a new one, so a draft the user was
   * in the middle of typing is still there.
   */
  @Test
  fun widgetTap_keepsTheRunningAppAndItsOpenDraft() {
    add("Tap Target Bread", days = 2)
    awaitWidget { rows().size == 1 }
    composeRule.onNode(hasClickAction() and hasAnyDescendant(hasText("Add")), useUnmergedTree = true).performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput(DraftName)
    composeRule.waitUntil(5_000) { draftName() == DraftName }
    val activity = composeRule.activity
    val before = System.identityHashCode(activity)

    val root = requireNotNull(widget).findViewById<View>(R.id.fridge_widget_root)
    composeRule.runOnUiThread { root.performClick() }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    composeRule.waitForIdle()

    // Same activity instance: no CLEAR_TOP, no second task, no lost draft.
    assertEquals("Tapping the widget restarted the activity", before, System.identityHashCode(composeRule.activity))
    assertTrue("Tapping the widget finished the running activity", !composeRule.activity.isFinishing)
    composeRule.waitUntil(5_000) { draftName() == DraftName }
    composeRule.onNode(hasSetTextAction() and hasText(DraftName, substring = true)).assertIsDisplayed()
  }

  /** A card that is not on any home screen must not make a save fail or throw. */
  @Test
  fun saveWithNoPlacedWidget_stillSucceeds() {
    composeRule.runOnUiThread { host.deleteAppWidgetId(widgetId) }
    widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    val context = context()
    assertEquals("Nothing is placed any more", 0, FridgeWidgetProvider.placedIds(context).size)
    val saved = runBlocking {
      PhotoStoreHolder.repository(context)
        .add(NewFridgeItem(name = "No Widget Yet", quantity = 1, expiresOn = LocalDate.now().plusDays(4)))
    }
    assertTrue("A missing widget turned a committed save into a failure", saved.isSuccess)
    composeRule.waitUntil(10_000) {
      composeRule.onAllNodes(hasText("No Widget Yet")).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /**
   * Puts the rendered card on screen at the sizes the picker offers, on a light device and on a
   * dark one, and leaves a frame of each on the device so a person can look at them. The card is
   * the system drawing our own views, so what is captured is what a launcher would show.
   */
  @Test
  fun placedWidget_rendersAtSupportedSizesOnLightAndDarkDevices() {
    add("Spinach", days = -3)
    add("Whole Milk", days = 0)
    add("Greek Yoghurt", days = 1)
    add("Sourdough", days = 2)
    add("Basmati Rice", days = 40)
    add("Frozen Peas", days = 120)
    awaitWidget { rows().isNotEmpty() }

    // Small: one column, a row or two, and a count of what is hidden.
    resize(250, 110)
    awaitWidget { endColumn().visibility == View.GONE && rows().isNotEmpty() }
    val small = rows().size
    assertTrue("A 250x110 card should not fit many rows, got $small", small in 1..3)
    captureCard("t5-widget-small", 250, 110)

    // Wide: two even columns, and twice the rows of the same height in one. The system rounds the
    // option it hands back, so this clears the card's own 380dp threshold instead of sitting on it,
    // and it still fits this screen.
    resize(400, 180)
    awaitWidget { twoEvenColumns() }
    val wide = startColumn().childCount + endColumn().childCount
    assertTrue("The wide card should hold more rows than the small one, $wide against $small", wide > small)
    assertEverythingAccountedFor(6)
    captureCard("t5-widget-wide", 400, 180)

    // Tall: one column, and the whole list, because the room is there.
    resize(250, 260)
    awaitWidget { singleColumnOf(6) }
    assertEquals("A 250x260 card holds all six, so nothing is hidden", "", moreText())
    captureCard("t5-widget-tall", 250, 260)

    // The same card on a dark device with the app in its dark theme: still the light palette.
    setDeviceNightMode(true)
    try {
      runBlocking { SettingsServices.preferences(context()).setThemeMode(ThemeMode.Dark) }
      add("Dark Device Plums", days = 4)
      awaitWidget { singleColumnOf(7) }
      captureCard("t5-widget-dark", 250, 260)
      assertEquals("A dark device turned the card dark", 0xFFF7F6F2.toInt(), cardColour())
    } finally {
      runBlocking { SettingsServices.preferences(context()).setThemeMode(ThemeMode.Light) }
      setDeviceNightMode(null)
    }
  }

  /**
   * A widget at the size a launcher gives its default placement, with a few things in the fridge.
   *
   * The card used to work its row count out of the smallest height a launcher might use, so at the
   * default size it drew one row and pushed the rest behind "+1 more" with space to spare. All
   * three have to be on the card here, because that is the placement every user starts from, and the
   * box is the one the Pixel launcher of this device was measured giving, not a guess: 360x224dp,
   * which is one column.
   */
  @Test
  fun placedWidget_atTheDefaultThreeByTwoSize_showsEveryItem() {
    add("Spinach", days = -1)
    add("Oat Milk", days = 5)
    add("Sourdough", days = 2)
    awaitWidget { rows().isNotEmpty() }

    // Start from a box that has to leave items off the list, so the check below cannot pass
    // because the card was big enough to hold all of them all along.
    resize(250, 110)
    awaitWidget { moreText().isNotEmpty() }
    assertTrue("Three items fit in a 250x110 card, so the rest of this proves nothing", rows().size < 3)

    // What the system hands a host for the launcher's default 4x2 placement: the box the Pixel
    // launcher measured in each orientation, and the per-size list a launcher sends from API 31.
    setLauncherDefaultSize()
    awaitWidget(20_000) { visibleNames().size >= 3 }

    val names = visibleNames()
    assertEquals("The default card should hold all three", 3, names.size)
    assertTrue("Spinach is missing from the default card: $names", names.any { it.startsWith("Spinach") })
    assertTrue("Oat Milk is missing from the default card: $names", names.any { it.startsWith("Oat Milk") })
    assertTrue("Sourdough is missing from the default card: $names", names.any { it.startsWith("Sourdough") })
    // Nothing is left hiding behind the overflow line while there is room on the card.
    assertEquals("Nothing should be left in the overflow at this size", "", moreText())
    // The default card is 360dp wide, which is one column, and it has to be drawn as one.
    assertEquals("The launcher's default card is one column", View.GONE, endColumn().visibility)
    assertEquals(
      "The default card is one column, so every row is in it",
      3,
      startColumn().childCount,
    )
    // A frame of the card at exactly the launcher's default box, for a person to look at.
    captureCard("t5-widget-default", LauncherDefaultWidthDp, LauncherDefaultHeightDp)
  }

  private fun singleColumnOf(rows: Int): Boolean =
    endColumn().visibility == View.GONE && startColumn().childCount == rows

  private fun twoEvenColumns(): Boolean {
    if (endColumn().visibility != View.VISIBLE) return false
    val start = startColumn().childCount
    return start > 0 && start == endColumn().childCount
  }

  /**
   * Attaches the rendered card to the activity, photographs it, and puts the app back. The card is
   * given the screen to lay out in, because a view nothing hosts measures itself to nothing.
   */
  private fun captureCard(tag: String, widthDp: Int, heightDp: Int) =
    withCardLaidOutAt(widthDp, heightDp) {
      assertNothingClipped(heightDp)
      Thread.sleep(500)
      shell("screencap -p /data/local/tmp/$tag.png")
    }

  /**
   * Lays the card out in a box of this size on the real screen, runs [block], and takes the card
   * off again. A card that no window hosts measures itself to nothing, so this is the only way to
   * read back what the host would really draw at a size, and laying it out in the very box the host
   * was told about is what makes it pick that box's card.
   */
  private fun withCardLaidOutAt(widthDp: Int, heightDp: Int, block: () -> Unit) {
    val context = context()
    val card = requireNotNull(widget)
    val holder = FrameLayout(context)
    composeRule.runOnUiThread {
      holder.addView(
        card,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
      composeRule.activity.addContentView(
        holder,
        ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          dp(context, heightDp),
        ).apply { width = dp(context, widthDp) },
      )
    }
    composeRule.waitForIdle()
    try {
      block()
    } finally {
      // The holder goes too: left in the content view it would push the app's own rows down for
      // every later tap the test makes.
      composeRule.runOnUiThread {
        (card.parent as? ViewGroup)?.removeView(card)
        (holder.parent as? ViewGroup)?.removeView(holder)
      }
      composeRule.waitForIdle()
    }
  }

  /**
   * Nothing the card drew may fall outside the area it draws into.
   *
   * The row count is worked out when the card is built and the text is measured when the host lays
   * it out, so a budget one row too generous is only visible here: the host clips the tail of the
   * list. The card has to be on screen and laid out before this can be read, so it runs from
   * inside [withCardLaidOutAt].
   */
  private fun assertNothingClipped(heightDp: Int) {
    val card = requireNotNull(widget)
    val area = card.findViewById<View>(R.id.widget_columns) ?: return
    if (area.visibility != View.VISIBLE || area.height == 0) return
    val drawn = rows()
    if (drawn.isEmpty()) return
    val over = drawn.maxBy { it.bottom }.bottom - area.height
    assertTrue(
      "The card at ${heightDp}dp drew a row the host had to cut off, by ${-over}px",
      over <= ClipTolerancePx,
    )
    drawn.forEach { row ->
      assertTrue("A row at ${heightDp}dp was drawn above the area it belongs in", row.top >= -ClipTolerancePx)
    }
  }

  private fun setDeviceNightMode(night: Boolean?) {
    shell("cmd uimode night ${if (night == null) "auto" else if (night) "yes" else "no"}")
    Thread.sleep(1_500)
    composeRule.waitForIdle()
  }

  /**
   * A tap from a cold app: the activity is closed first, so the widget has to start it, and what
   * comes back is a new activity showing the saved fridge.
   */
  @Test
  fun widgetTap_opensTheAppWhenItIsNotRunning() {
    add("Cold Tap Jam", days = 1)
    awaitWidget { rows().isNotEmpty() }
    val before = System.identityHashCode(composeRule.activity)
    composeRule.activityRule.scenario.close()
    Thread.sleep(1_000)
    assertTrue("The app should not be on screen any more", resumedActivities().none { it is MainActivity })

    val root = requireNotNull(widget).findViewById<View>(R.id.fridge_widget_root)
    composeRule.runOnUiThread { root.performClick() }

    val opened = awaitMainActivity(20_000)
    assertTrue("The widget handed back the activity it was holding", System.identityHashCode(opened) != before)
    assertTrue("The new activity is not a finished one", !opened.isFinishing)
    // The new instance is on the fridge, with the item the card was showing.
    assertTrue("The reopened app did not show the fridge", awaitComposeText("Cold Tap Jam", 15_000))
    opened.finish()
  }

  /**
   * Waits for a piece of text to be on screen, wherever its window came from. The reopened
   * activity is started by the widget, not by the test rule, so it has no scenario of its own.
   */
  private fun awaitComposeText(text: String, timeoutMillis: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      val found =
        runCatching { composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
      if (found) return true
      Thread.sleep(500)
    }
    return false
  }

  /** A restart of the app does not lose the card: a later save still reaches it. */
  @Test
  fun widgetRefresh_continuesAfterTheAppRestarts() {
    add("First Load Rice", days = 5)
    awaitWidget { rows().isNotEmpty() }
    assertEquals("First Load Rice", rowName(0))

    // Close the app and open it again, so the screen runs on a brand new repository.
    composeRule.activityRule.scenario.close()
    PhotoStoreHolder.resetForTests()
    ActivityScenario.launch(MainActivity::class.java).use {
      composeRule.waitForIdle()
      composeRule.waitUntil(15_000) {
        composeRule.onAllNodes(hasText("First Load Rice")).fetchSemanticsNodes().isNotEmpty()
      }
    }

    add("Second Load Rice", days = 2)
    awaitWidget { rows().size == 2 }
    assertEquals("The new save is nearest first", "Second Load Rice", rowName(0))
    assertEquals("The card forgot the item from before the restart", "First Load Rice", rowName(1))
  }

  /**
   * The update the system schedules while nothing of the app is on screen: the card is redrawn from
   * the local copy, and where the device lets the clock move, the labels follow the new day.
   */
  @Test
  fun scheduledUpdate_redrawsTheCardWhileTheAppIsClosed() {
    add("Use Me Soon", days = 1)
    awaitWidget { rows().size == 1 }
    assertEquals("Tomorrow", rowLabel(0))

    composeRule.activityRule.scenario.close()
    Thread.sleep(500)
    assertTrue("The app should be closed for this check", resumedActivities().none { it is MainActivity })

    val epoch = deviceEpoch()
    val autoTime = deviceSetting("global", "auto_time")
    val wanted = LocalDate.now().plusDays(2).toEpochDay()
    deviceSetting("global", "auto_time", "0")
    val clockMoved = shiftClock(epoch + TwoDaysSeconds, wanted)
    try {
      // The broadcast the system's periodic update delivers, with nothing of the app showing.
      sendScheduledUpdate()
      if (clockMoved) {
        awaitWidget(20_000) { rowLabel(0) == "1 day overdue" }
        assertEquals("The closed-app update kept the label from before the clock moved", "1 day overdue", rowLabel(0))
      } else {
        // A read-only emulator refuses to move the clock, so the same update is checked as it is.
        awaitWidget(10_000) { rowLabel(0) == "Tomorrow" }
        assertEquals("Tomorrow", rowLabel(0))
      }
      assertEquals("1 to use soon", summaryText())
    } finally {
      shiftClock(epoch)
      deviceSetting("global", "auto_time", autoTime)
    }
  }

  // ------------------------------------------------------------- rendering --

  /** Every drawn row, in the order the card shows them. */
  private fun rows(): List<View> {
    val card = requireNotNull(widget)
    val columns =
      listOf(R.id.widget_column_start, R.id.widget_column_end)
        .mapNotNull { id -> card.findViewById<ViewGroup>(id) }
        .filter { it.visibility == View.VISIBLE }
    return columns.flatMap { column -> (0 until column.childCount).map { column.getChildAt(it) } }
  }

  private fun rowName(index: Int): String? = rowText(index, R.id.widget_row_name)

  private fun rowLabel(index: Int): String? = rowText(index, R.id.widget_row_expiry)

  private fun rowText(index: Int, id: Int): String? = rows().getOrNull(index)?.findViewById<TextView>(id)?.text?.toString()

  private fun startColumn(): LinearLayout = requireNotNull(widget).findViewById(R.id.widget_column_start)

  private fun endColumn(): LinearLayout = requireNotNull(widget).findViewById(R.id.widget_column_end)

  private fun messageText(): String? = requireNotNull(widget).textOf(R.id.widget_message)

  private fun summaryText(): String? = requireNotNull(widget).textOf(R.id.widget_summary)

  private fun moreText(): String {
    val more = requireNotNull(widget).findViewById<TextView>(R.id.widget_more) ?: return ""
    return if (more.visibility == View.VISIBLE) more.text?.toString().orEmpty() else ""
  }

  private fun AppWidgetHostView.textOf(id: Int): String? = findViewById<TextView>(id)?.text?.toString()

  private fun rowInk(): Int = rows().first().findViewById<TextView>(R.id.widget_row_name).currentTextColor

  /** The card's own painted surface, read back from the rendered pixels rather than its resource. */
  private fun cardColour(): Int {
    // Draw the card's own root, which carries the fixed background. Nothing hosts it while the
    // test runs, so it needs a size of its own first.
    val card = requireNotNull(requireNotNull(widget).findViewById<View>(R.id.fridge_widget_root))
    card.measure(
      View.MeasureSpec.makeMeasureSpec(CardProbeWidth, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(CardProbeHeight, View.MeasureSpec.EXACTLY),
    )
    card.layout(0, 0, CardProbeWidth, CardProbeHeight)
    val bitmap = Bitmap.createBitmap(CardProbeWidth, CardProbeHeight, Bitmap.Config.ARGB_8888)
    card.draw(Canvas(bitmap))
    // Inside the card, past its 1dp outline, in the padding above the header.
    return bitmap.getPixel(CardProbeWidth / 2, (CardProbeHeight * 0.05f).toInt())
  }

  private fun awaitWidget(timeoutMillis: Long = 20_000, condition: () -> Boolean) {
    composeRule.waitUntil(timeoutMillis) { condition() }
    composeRule.waitForIdle()
  }

  // ------------------------------------------------------------- mutations --

  private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

  private fun today(): Long = LocalDate.now().toEpochDay()

  /** Activity state is the framework's to answer, and it only answers on the main thread. */
  private fun onMainThread(block: () -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
  }

  private fun resumedActivities(): List<Activity> {
    var activities: List<Activity> = emptyList()
    onMainThread { activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList() }
    return activities
  }

  private fun awaitMainActivity(timeoutMillis: Long): MainActivity {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      resumedActivities().filterIsInstance<MainActivity>().firstOrNull()?.let { return it }
      Thread.sleep(250)
    }
    throw AssertionError("The widget never brought the app back; resumed: ${resumedActivities()}")
  }

  /** The device clock and its own settings, read the way a person would change them. */
  private fun deviceSetting(namespace: String, key: String): String =
    shell("settings get $namespace $key").trim()

  private fun deviceSetting(namespace: String, key: String, value: String) {
    shell("settings put $namespace $key $value")
  }

  private fun deviceEpoch(): Long = shell("date +%s").trim().toLong()

  /**
   * Moves the device clock and waits for the process to be living in the new day. Reports whether
   * the day actually changed, because a read-only emulator refuses the request.
   */
  private fun shiftClock(epochSeconds: Long, wantedEpochDay: Long? = null): Boolean {
    shell("date -s @$epochSeconds")
    if (wantedEpochDay == null) return false
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
      if (today() == wantedEpochDay) return true
      Thread.sleep(500)
    }
    return false
  }

  /** The system widget update, delivered to the provider the way a launcher delivers it. */
  private fun sendScheduledUpdate() {
    val context = context()
    context.sendBroadcast(
      Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
        component = ComponentName(context, FridgeWidgetProvider::class.java)
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
      },
    )
  }

  private fun add(name: String, days: Long?) {
    runBlocking {
      PhotoStoreHolder.repository(context())
        .add(NewFridgeItem(name = name, quantity = 1, expiresOn = days?.let { LocalDate.now().plusDays(it) }))
        .getOrThrow()
    }
    composeRule.waitForIdle()
  }

  /** The stepper is a real tap, so the app goes through the same path a person does. */
  private fun increase(name: String) {
    val stepper =
      requireNotNull(
        composeRule
          .onAllNodes(hasContentDescription("Increase quantity of $name"), useUnmergedTree = true)
          .fetchSemanticsNodes()
          .firstOrNull(),
      ) { "No quantity stepper for $name" }
    val x = (stepper.positionOnScreen.x + stepper.size.width / 2f).toInt()
    val y = (stepper.positionOnScreen.y + stepper.size.height / 2f).toInt()
    shell("input tap $x $y")
  }

  private fun deleteThroughTheApp(name: String) {
    composeRule.onNodeWithText(name).performClick()
    composeRule.onNodeWithText("Remove from fridge").assertIsDisplayed().performClick()
    composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(name)).fetchSemanticsNodes().isEmpty() }
  }

  /**
   * Puts the placed instance in a box of this size, the way a launcher does.
   *
   * A launcher reports the box for *both* orientations at once, and from API 31 the list of the
   * sizes it is drawing. Before API 31 the platform reads the four options as two boxes: the
   * portrait placement is the minimum width with the maximum height, and the landscape one is the
   * maximum width with the minimum height. Sending only one of the four, as this harness used to,
   * is a box no launcher sends, and a card that reads the list a real launcher sends had nothing to
   * budget from: it fell back to a size built out of a number that was never set.
   *
   * Every option is in dp, which is what the platform documents and what the launcher on this
   * device sends. The landscape box defaults to the full width of the screen at the same height,
   * which is what a placement spanning the screen does when the home screen turns.
   *
   * Reporting the box is only half of it. A host picks the card for the size the widget is actually
   * laid out in, so the card is put in a window of that size as well: a card that is never laid
   * out is a card no host would ever choose, and the row count under test would be the one left
   * over from the size before.
   */
  private fun resize(
    widthDp: Int,
    heightDp: Int,
    landscapeWidthDp: Int = screenWidthDp(),
    landscapeHeightDp: Int = heightDp,
  ) {
    val context = context()
    val options =
      Bundle().apply {
        // The platform reads these as two boxes: the portrait placement and the landscape one.
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, landscapeWidthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, landscapeHeightDp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          // The sizes the launcher is drawing, one per orientation, in dp.
          putParcelableArrayList(
            AppWidgetManager.OPTION_APPWIDGET_SIZES,
            arrayListOf(
              SizeF(widthDp.toFloat(), heightDp.toFloat()),
              SizeF(landscapeWidthDp.toFloat(), landscapeHeightDp.toFloat()),
            ),
          )
        }
      }
    composeRule.runOnUiThread {
      AppWidgetManager.getInstance(context).updateAppWidgetOptions(widgetId, options)
    }
    withCardLaidOutAt(widthDp, heightDp) {}
  }

  /**
   * The box the Pixel launcher gives the default 4x2 placement on this emulator, in both
   * orientations, measured on the device rather than worked out from the provider's own defaults.
   * A 360dp card is one column, which is the whole point of using the real numbers.
   */
  private fun setLauncherDefaultSize() =
    resize(
      widthDp = LauncherDefaultWidthDp,
      heightDp = LauncherDefaultHeightDp,
      landscapeWidthDp = LauncherDefaultLandscapeWidthDp,
      landscapeHeightDp = LauncherDefaultLandscapeHeightDp,
    )

  /** The width of the screen in dp, which is what a full-width placement is given. */
  private fun screenWidthDp(): Int {
    val reported =
      shell("wm size")
        .lines()
        .firstOrNull { it.contains("Physical size") }
        ?.substringAfter("Physical size:")
        ?.split("x")
        ?.firstOrNull()
        ?.trim()
        ?.toIntOrNull()
    // The density is 2.625 on this screen: truncating it to an Int would report 540dp, not 411.
    return ((reported ?: 1080) / density()).toInt()
  }

  private fun density(): Float = context().resources.displayMetrics.density

  /** The names the card is actually drawing, in the order it draws them. */
  private fun visibleNames(): List<String> =
    rows().indices.mapNotNull { index -> rowName(index)?.takeIf { it.isNotBlank() } }

  private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

  private fun draftName(): String? =
    composeRule
      .onAllNodes(hasSetTextAction() and hasText(DraftName, substring = true), useUnmergedTree = true)
      .fetchSemanticsNodes()
      .firstOrNull()
      ?.let { node -> node.config[SemanticsProperties.EditableText].text }

  // ---------------------------------------------------------------- dates --

  /** The same wording the widget builds, so a mismatch points at the data, not the formatter. */
  private fun dateLabel(days: Long): String = when {
    days == 0L -> "Today"
    days == 1L -> "Tomorrow"
    days in 2L..7L -> "In $days days"
    days < 0L -> "${-days} days overdue"
    else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(LocalDate.now().plusDays(days))
  }

  private fun luminance(colour: Int): Double {
    val red = (colour shr 16) and 0xFF
    val green = (colour shr 8) and 0xFF
    val blue = colour and 0xFF
    return (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
  }

  private companion object {
    /** Stable for the app's own uid, which is what identifies a widget host. */
    const val HostId = 21
    const val DraftName = "Half typed"
    const val CardProbeWidth = 420
    const val CardProbeHeight = 240
    const val TwoDaysSeconds = 2 * 24 * 60 * 60L
    /**
     * The default 4x2 placement on the Pixel launcher of emulator-5554, measured on the device:
     * 360x224dp on a portrait home screen and 627x210dp on a landscape one. These are the numbers
     * the review checked the card against, so a harness that reports anything else is testing a
     * box no launcher on this device produces.
     */
    const val LauncherDefaultWidthDp = 360
    const val LauncherDefaultHeightDp = 224
    const val LauncherDefaultLandscapeWidthDp = 627
    const val LauncherDefaultLandscapeHeightDp = 210
    /** Rounding slack, in px, for a row that ends right on the edge of its area. */
    const val ClipTolerancePx = 2
  }
}

/**
 * Starts each widget test with an empty fridge.
 *
 * The card and the screen read the local copy through two repositories that are cached for the
 * life of the process, so rows are cleared through both of them instead of deleting the file,
 * which would leave one of them reading a copy the other cannot see.
 */
internal class ClearFridgeForWidgetRule : TestWatcher() {
  override fun starting(description: Description) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    PhotoStoreHolder.resetForTests()
    File(context.filesDir, "fridge-photos").deleteRecursively()
    clear(PhotoStoreHolder.repository(context))
    clear(FridgeWidgetData.repository(context))
  }

  private fun clear(repository: FridgeRepository) = runBlocking {
    repository.items.first().forEach { repository.delete(it.id) }
  }
}
