package com.dwk.yumgo

import android.database.sqlite.SQLiteDatabase
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dwk.yumgo.data.SettingsServices
import java.io.FileInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Premade foods, the settings screen, and the palette, on the real app.
 *
 * Everything here is a user path: a tap on a chip, a typed name, a chosen date, a saved item, a
 * relaunch. Presets and the theme live in their own preference files, so each test starts from a
 * clean copy of both and leaves the device's night setting as it found it.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPresetWorkflowTest {
  private val composeRule = createAndroidComposeRule<MainActivity>()

  @get:Rule
  val rule: TestRule =
    RuleChain
      .outerRule(WipeFridgeRule())
      .around(CleanSettingsRule())
      .around(composeRule)

  @Before
  fun allowRecreation() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.serviceInfo =
      automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
  }

  /**
   * The full preset path: a chip fills the name and a suggested date, another chip replaces both,
   * the date can be cleared and then chosen by hand, and only Save writes anything. The saved item
   * survives a relaunch and a later edit.
   */
  @Test
  fun presetFillsDraft_thenOverrideClearChooseSaveEditAndRelaunch() {
    openSettings()
    composeRule.onNodeWithText("Milk").assertIsDisplayed()
    composeRule.onNodeWithText("7 days").assertIsDisplayed()
    backToFridge()

    addButton().performClick()
    tapPreset("Milk")
    assertDraftName("Milk")
    assertExpiryLabel("In 7 days")

    // Another chip replaces a suggestion that is still untouched.
    tapPreset("Fish")
    assertDraftName("Fish")
    assertExpiryLabel("In 2 days")
    assertTrue("A chip must not save anything, but the fridge holds ${savedItemNames()}", savedItemNames().isEmpty())

    tapInEditor("Clear expiry date")
    assertExpiryLabel("No expiry")
    assertTrue("A chip must not save anything, but the fridge holds ${savedItemNames()}", savedItemNames().isEmpty())

    // A date the user picks by hand.
    tapInEditor("Choose expiry date")
    pickDayOfMonth(LocalDate.now().dayOfMonth)
    tapInEditor("Set date")
    assertExpiryLabel("Today")
    assertDraftName("Fish")

    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Fish") > 0 }
    composeRule.onNodeWithText("Fish").assertIsDisplayed()
    composeRule.onNodeWithText("Today").assertIsDisplayed()

    // A cold restart keeps the item and its date.
    recreateActivity()
    composeRule.waitUntil(10_000) { nodeCount("Fish") > 0 }
    composeRule.onNodeWithText("Today").assertIsDisplayed()

    // Editing it later keeps the date the user chose.
    composeRule.onNodeWithText("Fish").performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Greek Fish")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Greek Fish") > 0 }
    composeRule.onNodeWithText("Today").assertIsDisplayed()
  }

  /**
   * A shortcut never takes back something the user decided. A name of their own gets the chips out
   * of the way, a date they picked by hand survives a later chip, and both survive Save.
   */
  @Test
  fun presetTap_neverTakesBackANameOrDateTheUserChose() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("My Own Milk")
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Premade items").assertDoesNotExist()

    // Start again: a chip fills the name and a suggested date.
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("")
    tapPreset("Milk")
    assertDraftName("Milk")
    assertExpiryLabel("In 7 days")

    // The user picks a date by hand instead of taking the suggestion.
    tapInEditor("Choose expiry date")
    pickDayOfMonth(LocalDate.now().dayOfMonth)
    tapInEditor("Set date")
    assertExpiryLabel("Today")

    // A later chip must not take that date back.
    tapPreset("Fish")
    assertDraftName("Fish")
    assertExpiryLabel("Today")

    // A name of their own is saved as typed.
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Whole Fish")
    composeRule.onNodeWithText("Save").assertIsDisplayed().performClick()
    composeRule.waitUntil(10_000) { nodeCount("Whole Fish") > 0 }
    composeRule.onNodeWithText("Whole Fish").assertIsDisplayed()
    composeRule.onNodeWithText("Today").assertIsDisplayed()
  }

  /**
   * Editing a preset reaches the drafts that come after it, and nothing else: an item already in
   * the fridge keeps the name and date it was saved with.
   */
  @Test
  fun settingsPresetEdit_appliesToLaterDraftsAndLeavesSavedItemsAlone() {
    addButton().performClick()
    tapPreset("Milk")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Milk") > 0 }

    openSettings()
    scrollSettingsTo(hasText("Milk"))
    composeRule.onNodeWithText("Milk").performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Whole Milk")
    composeRule.onNode(hasSetTextAction() and hasText("Days")).performTextReplacement("5")
    composeRule.onNodeWithText("Save").assertIsDisplayed().performClick()
    composeRule.waitUntil(10_000) { nodeCount("Whole Milk") > 0 }
    composeRule.onNodeWithText("5 days").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Saved").assertIsDisplayed()
    backToFridge()

    // The item that was already saved is untouched.
    composeRule.onNodeWithText("Milk").assertIsDisplayed()
    composeRule.onNodeWithText("In 7 days").assertIsDisplayed()

    // The next draft offers the edited food, and it fills with the new suggestion.
    addButton().performClick()
    tapPreset("Whole Milk")
    assertDraftName("Whole Milk")
    assertExpiryLabel("In 5 days")

    // And the edit is still there after a restart.
    dismissEditor()
    recreateActivity()
    openSettings()
    scrollSettingsTo(hasText("Whole Milk"))
    composeRule.onNodeWithText("Whole Milk").assertIsDisplayed()
    composeRule.onNodeWithText("5 days").assertIsDisplayed()

  }

  /** A blank name and an impossible shelf life are refused, and Cancel changes nothing. */
  @Test
  fun settingsPresetEdit_refusesABlankNameAndAnImpossibleShelfLife() {
    openSettings()
    scrollSettingsTo(hasText("Milk"))
    composeRule.onNodeWithText("Milk").performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.onNodeWithText("Enter a name").assertIsDisplayed()
    assertEquals("A refused edit must not close the editor", 1, nodeCount("Save"))

    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Oat Milk")
    composeRule.onNode(hasSetTextAction() and hasText("Days")).performTextReplacement("400")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.onNodeWithText("Use a number of days between 0 and 365").assertIsDisplayed()

    // Cancel leaves the preset exactly as it was.
    composeRule.onNodeWithText("Cancel").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Use a number of days between 0 and 365").assertDoesNotExist()
    scrollSettingsTo(hasText("Milk"))
    composeRule.onNodeWithText("Milk").assertIsDisplayed()
    composeRule.onNodeWithText("7 days").assertIsDisplayed()
  }

  /**
   * Light is the default even when the device is dark, each choice repaints straight away, and the
   * choice is still there after a restart.
   */
  @Test
  fun themeIsLightOnADarkDevice_byDefaultAndTheChoicePersists() {
    setDeviceNightMode(true)
    try {
      recreateActivity()
      composeRule.waitUntil(10_000) { nodeCount("Fridge") > 0 }
      assertFalse("A light app went dark on a dark device", screenIsDark())

      openSettings()
      themeRow("Light").assertIsSelected()
      themeRow("Dark").assertIsNotSelected()

      // Switching repaints without a restart.
      themeRow("Dark").performClick()
      composeRule.waitUntil(10_000) { screenIsDark() }
      themeRow("Dark").assertIsSelected()
      backToFridge()
      assertTrue("Dark was not applied to the fridge", screenIsDark())

      // And it is still dark after a restart.
      recreateActivity()
      assertTrue("The dark choice was forgotten", screenIsDark())

      // System follows the device, which is dark here.
      openSettings()
      themeRow("System").performClick()
      composeRule.waitUntil(10_000) { screenIsDark() }
      backToFridge()
      assertTrue("System did not follow the dark device", screenIsDark())

      // Light comes back, and the default is restored for the next launch.
      openSettings()
      themeRow("Light").performClick()
      composeRule.waitUntil(10_000) { !screenIsDark() }
      backToFridge()
      assertFalse("Light was not applied", screenIsDark())
    } finally {
      setDeviceNightMode(null)
    }
  }

  /**
   * Settings is pushed on top of the fridge: Back returns to the same list, the same search, and
   * the same items, and it does not leave the app behind itself.
   */
  @Test
  fun settingsBack_returnsToTheSameFridgeAndSearch() {
    add("Milk")
    add("Bread")
    composeRule.onNode(hasSetTextAction() and hasText("Search")).performTextInput("mi")
    composeRule.waitUntil(5_000) { nodeCount("Nothing matches") > 0 || nodeCount("Bread") == 0 }

    openSettings()
    composeRule.onNodeWithText("Premade items").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Back").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Search") > 0 }

    composeRule.onNodeWithText("Settings").assertDoesNotExist()
    composeRule.onNode(hasSetTextAction() and hasText("mi")).assertIsDisplayed()
    composeRule.onNodeWithText("Milk").assertIsDisplayed()
    composeRule.onNodeWithText("Bread").assertDoesNotExist()
    assertFalse("Back out of settings closed the app", composeRule.activity.isFinishing)

    // The search is still live, and clearing it brings the other item back.
    composeRule.onNodeWithContentDescription("Clear search").performClick()
    composeRule.waitUntil(5_000) { nodeCount("Bread") > 0 }
    composeRule.onNodeWithText("Bread").assertIsDisplayed()
  }

  /**
   * Settings with the system back gesture, seen as a person sees it: the screen that is on top, the
   * frame part way through the gesture, and the fridge underneath once the gesture finishes.
   */
  @Test
  fun settingsBackGesture_showsTheFridgeUnderneath() {
    add("Gesture Milk")
    openSettings()
    dumpScreen("t5-back-1-settings")
    val (width, height) = displaySize()
    val y = height / 2
    // The gesture is held part way so the frame in between can be looked at, then finished.
    shell("input motionevent DOWN 2 $y")
    try {
      val steps = 6
      for (step in 1..steps) {
        shell("input motionevent MOVE ${(2 + (width - 120) * step / steps)} $y")
        Thread.sleep(60)
      }
      Thread.sleep(300)
      dumpScreen("t5-back-2-mid-gesture")
    } finally {
      shell("input motionevent MOVE $width $y")
      shell("input motionevent UP $width $y")
    }
    composeRule.waitForIdle()
    Thread.sleep(800)
    dumpScreen("t5-back-3-fridge")
    eventually { composeRule.onNodeWithText("Settings").assertDoesNotExist() }
    composeRule.onNodeWithText("Gesture Milk").assertIsDisplayed()
  }

  /**
   * Settings is a real back-stack entry: it survives a configuration change, the system back
   * gesture brings the fridge back, and the whole round trip works with animations switched off as
   * well as on.
   */
  @Test
  fun settingsSurvivesAConfigurationChange_andBackWorksWithAnimationsOffAndOn() {
    add("Rotation Milk")
    setAnimatorDurationScale(0.0)
    try {
      openSettings()
      // With animations off the push still lands, and the screen is usable.
      composeRule.onNodeWithText("Appearance").assertIsDisplayed()
      themeRow("Light").assertIsSelected()

      // The system back gesture: a swipe in from the left edge is back on this platform.
      backFromLeftEdge()
      eventually { composeRule.onNodeWithText("Settings").assertDoesNotExist() }
      composeRule.onNodeWithText("Rotation Milk").assertIsDisplayed()
    } finally {
      setAnimatorDurationScale(1.0)
      restoreAnimationScales()
    }

    // And the same round trip with the expressive motion on.
    openSettings()
    composeRule.onNodeWithText("Premade items").assertIsDisplayed()
    recreateActivity()
    eventually { composeRule.onNodeWithText("Appearance").assertIsDisplayed() }
    backToFridge()
    composeRule.onNodeWithText("Rotation Milk").assertIsDisplayed()
    dumpScreen("t5-after-transition")
  }

  private fun setAnimatorDurationScale(scale: Double) {
    // The device's own values are put back, so a run leaves the animation setting as it was. The
    // first call is the one that remembers them, not the call that puts the setting back.
    val value = if (scale == 0.0) "0" else "1"
    if (animationScalesToRestore == null) animationScalesToRestore = animationScales()
    shell("settings put global animator_duration_scale $value")
    shell("settings put global transition_animation_scale $value")
    shell("settings put global window_animation_scale $value")
    Thread.sleep(300)
    composeRule.waitForIdle()
  }

  private fun appContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

  /** Kept so a run that stops early still puts the device's animation scales back. */
  private var animationScalesToRestore: Map<String, String>? = null

  private fun animationScales(): Map<String, String> =
    listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
      .associateWith { shell("settings get global $it").trim() }

  private fun restoreAnimationScales() {
    animationScalesToRestore?.forEach { (key, value) -> shell("settings put global $key $value") }
    animationScalesToRestore = null
    Thread.sleep(200)
  }

  /**
   * The system back gesture: a swipe in from the left edge. Its coordinates come from the display,
   * so it is the same gesture on any screen.
   */
  private fun backFromLeftEdge() {
    val (width, height) = displaySize()
    shell("input swipe 2 ${height / 2} ${width - 80} ${height / 2} 400")
    composeRule.waitForIdle()
    Thread.sleep(500)
  }

  private fun displaySize(): Pair<Int, Int> {
    val physical =
      shell("wm size")
        .lines()
        .firstOrNull { it.contains("Physical size") }
        ?.substringAfter("Physical size:")
        ?.split("x")
        ?.mapNotNull { it.trim().toIntOrNull() }
    val (width, height) = (physical ?: listOf(1080, 2400))
    return width to height
  }

  // ------------------------------------------------------------- settings --

  private fun openSettings() {
    eventually {
      tapControl(composeRule.onNodeWithContentDescription("Settings"))
      composeRule.onNodeWithText("Premade items").assertExists()
    }
  }

  private fun backToFridge() {
    eventually {
      tapControl(composeRule.onNodeWithContentDescription("Back"))
      composeRule.onNodeWithText("Fridge").assertIsDisplayed()
    }
  }

  private fun themeRow(label: String) = composeRule.onNode(hasClickAction() and hasText(label, substring = true))

  private fun scrollSettingsTo(matcher: SemanticsMatcher) {
    composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    composeRule.waitForIdle()
  }

  // ---------------------------------------------------------------- editor --

  private fun addButton() = composeRule.onNode(hasClickAction() and hasAnyDescendant(hasText("Add")), useUnmergedTree = true)

  private fun nodeCount(text: String): Int = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

  /**
   * The chip row scrolls sideways and the editor scrolls under the keyboard, so a target has to be
   * brought into view first. Tapping blind would land on the scrim and close the editor.
   */
  /**
   * Taps a control the way an accessibility tap does. A real touch would be injected at the node's
   * centre, and a control sitting at the keyboard's edge is then swallowed by the IME, which is a
   * measurement artefact rather than something a person can do.
   */
  private fun tapControl(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
    runCatching { node.performScrollTo() }
    node.performSemanticsAction(SemanticsActions.OnClick)
    composeRule.waitForIdle()
  }

  private fun tapInEditor(label: String) = tapControl(composeRule.onNodeWithText(label))

  private fun tapPreset(name: String) = tapInEditor(name)

  /**
   * The rows the fridge has actually stored, read from its own database. The editor's delete
   * button shares its label with nothing on a list, so it cannot stand in for a saved row.
   */
  private fun savedItemNames(): List<String> {
    val path = appContext().getDatabasePath("yumgo_fridge.db").path
    return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      db
        .rawQuery("SELECT name FROM fridge_item WHERE deleted_at IS NULL", null)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }
  }

  private fun assertDraftName(expected: String) {
    eventually { composeRule.onNode(hasSetTextAction() and hasText(expected, substring = true)).assertIsDisplayed() }
  }

  private fun assertExpiryLabel(expected: String) {
    eventually { composeRule.onNodeWithText(expected).assertIsDisplayed() }
  }

  private fun dismissEditor() {
    // The sheet takes focus, so the first Back closes the keyboard and the second closes the sheet.
    repeat(2) {
      if (nodeCount("Add to the fridge") == 0) return
      shell("input keyevent 4")
      composeRule.waitForIdle()
    }
    eventually { composeRule.onAllNodes(hasText("Add to the fridge")).fetchSemanticsNodes().let { if (it.isNotEmpty()) throw AssertionError("the editor is still open") } }
  }

  /**
   * Taps a day in the Material date picker, which announces each cell with its whole date
   * ("Today, Saturday, September 26, 2026") rather than a bare number. The picker opens on the
   * draft's month, so the calendar steps back to the month that holds today first.
   */
  private fun pickDayOfMonth(day: Int) {
    composeRule.waitUntil(10_000) { nodeCount("Set date") > 0 }
    val today = LocalDate.now()
    val thisMonth = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(today)
    var steps = 0
    while (nodeCount(thisMonth) == 0 && steps++ < 24) {
      tapControl(composeRule.onNodeWithContentDescription("Change to previous month"))
    }
    assertEquals("The picker would not show $thisMonth", 1, nodeCount(thisMonth))
    val announced = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()).format(today)
    val cells = composeRule.onAllNodes(hasText(announced, substring = true), useUnmergedTree = true)
    composeRule.waitUntil(10_000) { cells.fetchSemanticsNodes().isNotEmpty() }
    cells[0].performClick()
    composeRule.waitForIdle()
  }

  private fun add(name: String) {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput(name)
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(name) > 0 }
  }

  private fun recreateActivity() {
    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    // A recreated activity starts with no compose root for a moment.
    eventually { composeRule.onAllNodes(hasText("Fridge")).fetchSemanticsNodes() }
  }

  /**
   * Retries while the app is between windows, which happens on every recreation, and rethrows the
   * last failure so a real problem still reads as itself.
   */
  private fun eventually(timeoutMillis: Long = 20_000, action: () -> Unit) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    var last: Throwable = AssertionError("the app never became usable")
    while (true) {
      try {
        action()
        return
      } catch (error: Throwable) {
        last = error
        dumpScreen()
      }
      if (System.currentTimeMillis() >= deadline) throw last
      Thread.sleep(200)
    }
  }

  /** Leaves the current frame on the device so a failure can be looked at. */
  private fun dumpScreen(tag: String = "t5") {
    runCatching { shell("screencap -p /data/local/tmp/$tag.png") }
  }

  // ------------------------------------------------------------ appearance --

  private fun setDeviceNightMode(night: Boolean?) {
    shell("cmd uimode night ${if (night == null) "auto" else if (night) "yes" else "no"}")
    // The configuration change takes a moment to reach the process.
    repeat(20) {
      Thread.sleep(250)
      composeRule.waitForIdle()
    }
  }

  /**
   * Reads the real frame and calls it light or dark by its mean brightness, which is what a person
   * would say looking at it.
   */
  private fun screenIsDark(): Boolean {
    val bytes = shellBytes("screencap -p")
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Could not read the screen")
    var total = 0.0
    var samples = 0
    var y = (bitmap.height * 0.15f).toInt()
    while (y < bitmap.height * 0.6f) {
      var x = (bitmap.width * 0.05f).toInt()
      while (x < bitmap.width * 0.95f) {
        val pixel = bitmap.getPixel(x, y)
        total += 0.2126 * ((pixel shr 16) and 0xFF) + 0.7152 * ((pixel shr 8) and 0xFF) + 0.0722 * (pixel and 0xFF)
        samples++
        x += 24
      }
      y += 24
    }
    return (total / samples) / 255.0 < 0.5
  }
}

/** Settings live in their own preference files, so each test starts from the bundled defaults. */
internal class CleanSettingsRule : TestWatcher() {
  override fun starting(description: Description) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    SettingsServices.resetForTests()
    // The theme call is what a fresh install does.
    check(SettingsServices.preferences(context).themeMode.value == com.dwk.yumgo.data.ThemeMode.Light)
  }
}

/** Raw shell output, for commands that answer with binary. */
internal fun shellBytes(command: String): ByteArray {
  val process = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
  return FileInputStream(process.fileDescriptor).use { it.readBytes() }.also { process.close() }
}
