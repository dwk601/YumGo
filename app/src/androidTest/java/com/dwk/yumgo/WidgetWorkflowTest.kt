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
import android.os.Bundle
import android.os.Process
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
    resize(minWidthDp = 250, minHeightDp = 200)
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

  /** A wider placement spreads the rows over two even columns; a taller one fits more of them. */
  @Test
  fun placedWidget_resizeGivesMoreRowsInTwoColumns() {
    repeat(6) { add("Bulk Item $it", days = it.toLong() + 1) }
    awaitWidget { rows().isNotEmpty() }

    resize(minWidthDp = 250, minHeightDp = 110)
    awaitWidget { rows().isNotEmpty() }
    val small = rows().size
    assertTrue("A 250x110 card should not fit many rows, got $small", small in 1..3)
    assertEquals("6 items in $small rows must all be counted", "+${6 - small} more", moreText())
    assertEquals("A narrow card must not open a second column", View.GONE, endColumn().visibility)

    resize(minWidthDp = 430, minHeightDp = 210)
    awaitWidget { endColumn().visibility == View.VISIBLE && endColumn().childCount > 0 }
    val start = startColumn().childCount
    val end = endColumn().childCount
    assertTrue("A wide card should split the rows, got $start and $end", end > 0)
    assertEquals("A half-filled second column looks broken", 0, start - end)
    assertTrue("A wide card should still cap the rows at four, got ${start + end}", start + end <= 4)
    assertTrue("A bigger card should fit more than the small one did", start + end > small)
    assertEquals("+${6 - (start + end)} more", moreText())
  }

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

    // Small: one row and a count of what is hidden.
    resize(minWidthDp = 250, minHeightDp = 110)
    awaitWidget { singleColumnOf(1) }
    captureCard("t5-widget-small", 110)

    // Wide: two even columns. The system rounds the option it hands back, so this clears the
    // card's own 380dp threshold instead of sitting on it, and it still fits this screen.
    resize(minWidthDp = 400, minHeightDp = 180)
    awaitWidget { twoEvenColumns() }
    captureCard("t5-widget-wide", 180)

    // Tall: one column with as many rows as fit.
    resize(minWidthDp = 250, minHeightDp = 260)
    awaitWidget { singleColumnOf(4) }
    captureCard("t5-widget-tall", 260)

    // The same card on a dark device with the app in its dark theme: still the light palette.
    setDeviceNightMode(true)
    try {
      runBlocking { SettingsServices.preferences(context()).setThemeMode(ThemeMode.Dark) }
      add("Dark Device Plums", days = 4)
      awaitWidget { singleColumnOf(4) }
      captureCard("t5-widget-dark", 260)
      assertEquals("A dark device turned the card dark", 0xFFF7F6F2.toInt(), cardColour())
    } finally {
      runBlocking { SettingsServices.preferences(context()).setThemeMode(ThemeMode.Light) }
      setDeviceNightMode(null)
    }
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
  private fun captureCard(tag: String, heightDp: Int) {
    val context = context()
    val card = requireNotNull(widget)
    composeRule.runOnUiThread {
      val holder = FrameLayout(context)
      holder.addView(
        card,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
      composeRule.activity.addContentView(
        holder,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)),
      )
    }
    composeRule.waitForIdle()
    Thread.sleep(500)
    shell("screencap -p /data/local/tmp/$tag.png")
    composeRule.runOnUiThread { (card.parent as? ViewGroup)?.removeView(card) }
    composeRule.waitForIdle()
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

  private fun resize(minWidthDp: Int, minHeightDp: Int) {
    val context = context()
    val options =
      Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, dp(context, minWidthDp))
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, dp(context, minHeightDp))
      }
    composeRule.runOnUiThread {
      AppWidgetManager.getInstance(context).updateAppWidgetOptions(widgetId, options)
    }
  }

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
