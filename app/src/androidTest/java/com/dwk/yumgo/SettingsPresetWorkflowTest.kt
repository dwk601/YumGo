package com.dwk.yumgo

import android.database.sqlite.SQLiteDatabase
import android.content.Context
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import com.dwk.yumgo.data.FoodPreset
import com.dwk.yumgo.data.SettingsServices
import com.dwk.yumgo.data.ThemeMode
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
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

/** The first preset in the list, which sits high up in a long list. */
private const val TopPreset = "Milk"

/** The last preset in the list, which is the one the list runs out of room for. */
private const val BottomPreset = "Frozen peas"

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
   * A shortcut never takes back something the user decided. The premade lane stays on screen while
   * a name of their own is being typed and leaves that name alone, a name they have only started is
   * finished by the shortcut they press, a date they picked by hand survives a later shortcut, and
   * both survive Save.
   */
  @Test
  fun presetTap_neverTakesBackANameOrDateTheUserChose() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("My Own Milk")
    composeRule.waitForIdle()
    // The lane stays where it is, so nothing moves out from under the finger, and what they typed
    // is still the name in the field.
    composeRule.onNodeWithText("Premade items").assertIsDisplayed()
    assertDraftName("My Own Milk")

    // A name that is not the start of any food is left alone by a shortcut pressed after it; the
    // date it was still free to suggest, it does fill.
    tapPreset("Milk")
    assertDraftName("My Own Milk")
    assertExpiryLabel("In 7 days")

    // A name they have only started is finished by the shortcut they press: Mi plus Milk is Milk.
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Mi")
    assertDraftName("Mi")
    tapPreset("Milk")
    assertDraftName("Milk")
    assertExpiryLabel("In 7 days")

    // The user picks a date by hand instead of taking the suggestion.
    tapInEditor("Choose expiry date")
    pickDayOfMonth(LocalDate.now().dayOfMonth)
    tapInEditor("Set date")
    assertExpiryLabel("Today")

    // A later shortcut must not take that date back.
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
   * Editing a preset with the keyboard up: the field being typed into and the button that saves
   * the edit both have to be whole, above the keyboard, and reachable with a finger. Tapping where
   * Save is drawn used to land on the keyboard instead, which deleted a digit instead of saving.
   */
  @Test
  fun presetEditorWithTheKeyboard_keepsTheDaysFieldAndSaveReachable() {
    openSettings()
    openPreset(TopPreset)
    // Tap the Days field the way a person does, with a finger: that is what raises the keyboard.
    fingerTapOnField("Days")
    val imeTop = awaitImeTop()
    composeRule.onNode(hasSetTextAction() and hasText("Days")).performTextReplacement("9")

    assertFieldAndSaveAreReachable("Days", imeTop)

    // A finger on Save saves, rather than hitting the keyboard.
    saveWithAFinger()
    eventually {
      assertEquals("Milk is still ${storedPreset("milk")?.expiryDays} days", 9, storedPreset("milk")?.expiryDays)
      composeRule.onNodeWithText("9 days").assertIsDisplayed()
    }
    // Put the keyboard away again, so the next test starts from a quiet screen.
    dismissKeyboardIfUp()
  }

  /**
   * The editor of a preset at the end of the list is the one that used to open with its fields and
   * its Save below the bottom of the screen, so a person had to scroll by hand before they could
   * even put a finger in a field. Nothing is scrolled here after the tap: the card brings itself
   * into view once it has finished growing, and then a finger types and a finger saves.
   */
  @Test
  fun presetEditorAtTheEndOfTheList_opensInViewAndSavesWithAFinger() {
    openSettings()
    openPreset(BottomPreset)
    // Whatever the app does on its own, the whole editor has to end up on screen.
    eventually { assertEditorInView() }

    fingerTapOnField("Name")
    val imeTop = awaitImeTop()
    assertFieldAndSaveAreReachable("Name", imeTop)

    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Ratatouille")
    saveWithAFinger()
    eventually {
      assertEquals("The rename did not reach the preset", "Ratatouille", storedPreset("frozen_peas")?.name)
      composeRule.onNodeWithText("Ratatouille").assertIsDisplayed()
      composeRule.onNodeWithText("120 days").assertIsDisplayed()
    }
    dismissKeyboardIfUp()
  }

  /**
   * Landscape with the keyboard up is the shape that hid the field being typed into behind the
   * title: the list reached up under the app bar, so scrolling a card into view counted the space
   * behind the bar as room on screen. Turned on its side, the field stays below the bar, it and
   * Save both stay above the keyboard, and a finger on Save saves. The display is turned back.
   */
  @Test
  fun presetEditorInLandscape_keepsTheFieldBelowTheAppBarAndSaveAboveTheKeyboard() {
    val autoRotate = deviceSetting("system", "accelerometer_rotation")
    val rotation = deviceSetting("system", "user_rotation")
    try {
      deviceSetting("system", "accelerometer_rotation", "0")
      openSettings()

      // Turned on its side first, which is the shape a person edits a preset in: the window is
      // short and the keyboard takes half of what is left.
      rotateDisplay(1)
      composeRule.waitUntil(20_000) { orientation() == ORIENTATION_LANDSCAPE }
      assertEquals("The app did not follow the display", ORIENTATION_LANDSCAPE, orientation())

      openPreset(BottomPreset)
      eventually { assertEditorInView() }

      fingerTapOnField("Days")
      val imeTop = awaitImeTop()
      assertFieldAndSaveAreReachable("Days", imeTop)

      composeRule.onNode(hasSetTextAction() and hasText("Days")).performTextReplacement("30")
      saveWithAFinger()
      eventually {
        assertEquals("The shelf life did not change", 30, storedPreset("frozen_peas")?.expiryDays)
        composeRule.onNodeWithText("30 days").assertIsDisplayed()
      }

      rotateDisplay(0)
      composeRule.waitUntil(20_000) { orientation() == ORIENTATION_PORTRAIT }
    } finally {
      dismissKeyboardIfUp()
      deviceSetting("system", "user_rotation", rotation)
      deviceSetting("system", "accelerometer_rotation", autoRotate)
    }
  }

  /**
   * The settings bar in landscape, in the two shapes a phone is actually held in: the gesture bar
   * with the display turned one way, and three buttons with it turned the other, where the bar's
   * controls sit along the left-hand edge. The way back has to be a control a finger can reach
   * wherever the system bars are, so the check is the size of the control, that it is inside the
   * safe area, and a real tap that returns to the fridge.
   */
  @Test
  fun settingsBackControlInLandscape_isABigEnoughTargetInsideTheSafeAreaAndGoesBack() {
    withNavOverlay(GestureOverlay) { backControlReachesTheFridge(turn = 1) }
    withNavOverlay(ThreeButtonOverlay) { backControlReachesTheFridge(turn = 3) }
  }

  /** Turns the display [turn] quarter turns round, checks the way back, and turns it back. */
  private fun backControlReachesTheFridge(turn: Int) {
    val autoRotate = deviceSetting("system", "accelerometer_rotation")
    val rotation = deviceSetting("system", "user_rotation")
    try {
      deviceSetting("system", "accelerometer_rotation", "0")
      rotateDisplay(turn)
      composeRule.waitUntil(20_000) { orientation() == ORIENTATION_LANDSCAPE }
      openSettingsFromTheFridge()

      val back = composeRule.onNodeWithContentDescription("Back").assertIsDisplayed().fetchSemanticsNode()
      val bounds = back.boundsInWindow
      val density = composeRule.activity.resources.displayMetrics.density
      // A bar in a short window may draw the control at the size the same bar gives in portrait,
      // but it may not squeeze it: this is that size, and a bar that halves it is the bug.
      assertTrue(
        "The way back is ${back.size} at $bounds, squeezed by the bar",
        back.size.height >= StockControlHeightDp * density,
      )
      val safe = safeArea()
      assertTrue(
        "The way back at $bounds is outside the safe area $safe",
        safe.contains(bounds.topLeft) && safe.contains(bounds.bottomRight),
      )
      // A finger at the edge of a 48dp target, which is two pixels outside the 40dp the bar draws,
      // still goes back: that edge is the size a person feels the control is.
      tapAt(bounds.center.x - (22f * density), bounds.center.y)
      eventually { composeRule.onNodeWithText("Fridge").assertIsDisplayed() }
    } finally {
      deviceSetting("system", "user_rotation", rotation)
      deviceSetting("system", "accelerometer_rotation", autoRotate)
    }
  }

  /**
   * At twice the text size the editor is taller than the room the keyboard leaves, which is where
   * the fields used to end up under it. The field, Save and a finger on Save, all at that size.
   */
  @Test
  fun presetEditorAtDoubleTextSize_keepsTheFieldAndSaveReachable() {
    val fontScale = deviceSetting("system", "font_scale")
    try {
      deviceSetting("system", "font_scale", "2.0")
      recreateActivity()
      composeRule.waitUntil(20_000) { nodeCount("Fridge") > 0 }

      openSettings()
      openPreset(TopPreset)
      fingerTapOnField("Days")
      val imeTop = awaitImeTop()
      assertFieldAndSaveAreReachable("Days", imeTop)

      composeRule.onNode(hasSetTextAction() and hasText("Days")).performTextReplacement("12")
      saveWithAFinger()
      eventually {
        assertEquals("Milk is still ${storedPreset("milk")?.expiryDays} days", 12, storedPreset("milk")?.expiryDays)
        composeRule.onNodeWithText("12 days").assertIsDisplayed()
      }
    } finally {
      dismissKeyboardIfUp()
      deviceSetting("system", "font_scale", fontScale)
      recreateActivity()
    }
  }

  /**
   * Settings with the system back gesture, seen as a person sees it: the screen that is on top, the
   * frame part way through the gesture, and the fridge underneath once the gesture finishes. The
   * gesture is a gesture, so the device is put on the navigation that has one.
   */
  @Test
  fun settingsBackGesture_showsTheFridgeUnderneath() {
    withNavOverlay(GestureOverlay) {
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
  }

  /**
   * Settings is a real back-stack entry: it survives a configuration change, the system back
   * gesture brings the fridge back, and the whole round trip works with animations switched off as
   * well as on.
   */
  @Test
  fun settingsSurvivesAConfigurationChange_andBackWorksWithAnimationsOffAndOn() {
    withNavOverlay(GestureOverlay) {
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

  /** A real finger on a node, in the place the node is drawn. */
  private fun tapOnScreen(node: SemanticsNode) {
    tapAt(node.positionOnScreen.x + node.size.width / 2f, node.positionOnScreen.y + node.size.height / 2f)
  }

  /** A real finger at a point on the screen, in window coordinates. */
  private fun tapAt(x: Float, y: Float) {
    shell("input tap ${x.toInt()} ${y.toInt()}")
    composeRule.waitForIdle()
  }

  /** A real finger on the editor field with this label, which is what raises the keyboard. */
  private fun fingerTapOnField(label: String) {
    awaitEditorSettled()
    tapOnScreen(composeRule.onNode(hasSetTextAction() and hasText(label)).fetchSemanticsNode())
  }

  /** A real finger on Save, where it is drawn. */
  private fun saveWithAFinger() {
    awaitEditorSettled()
    tapOnScreen(composeRule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode())
  }

  /**
   * Waits until the editor has stopped moving. Opening one scrolls the card into view with an
   * animation, and a finger taken mid-scroll, or a reading taken then, says what happened to be
   * passing by rather than where the editor came to rest. A person waits for the screen to hold
   * still, so the test does too.
   */
  private fun awaitEditorSettled() {
    var previous: List<Rect> = emptyList()
    var still = 0
    val deadline = System.currentTimeMillis() + 20_000
    while (still < 3 && System.currentTimeMillis() < deadline) {
      Thread.sleep(100)
      composeRule.waitForIdle()
      val now = listOf("Name", "Days", "Save").map { drawnWhere(it) }
      still = if (now == previous) still + 1 else 0
      previous = now
    }
  }

  /** Where a part of the open editor is drawn right now: an editor field by its label, or Save. */
  private fun drawnWhere(part: String): Rect =
    if (part == "Save") {
      composeRule.onNodeWithText("Save").fetchSemanticsNode().boundsInWindow
    } else {
      composeRule.onNode(hasSetTextAction() and hasText(part)).fetchSemanticsNode().boundsInWindow
    }

  /**
   * What a person has to be able to see and reach, in the shape that used to lose it: the field
   * they are typing into is whole, sits inside the part of the list that is on screen (which starts
   * below the app bar, so a field cannot be parked behind the title), and both it and the Save
   * button are above the keyboard and above the navigation bar.
   */
  private fun assertFieldAndSaveAreReachable(label: String, imeTop: Float) {
    awaitEditorSettled()
    val viewport = listViewport()
    val visibleBottom = minOf(imeTop, navBarTop())
    val field = composeRule.onNode(hasSetTextAction() and hasText(label)).assertIsDisplayed().fetchSemanticsNode()
    val bounds = field.boundsInWindow
    assertEquals(
      "The $label field is clipped to $bounds of ${field.size}",
      field.size.height.toFloat(),
      bounds.height,
      HalfPixel,
    )
    assertTrue(
      "The $label field is above the top of the list on screen: $bounds against $viewport",
      bounds.top >= viewport.top - HalfPixel,
    )
    assertTrue(
      "The $label field is behind the keyboard or the navigation bar: $bounds against $visibleBottom",
      bounds.bottom <= visibleBottom + HalfPixel,
    )

    // The button, not the word on it: half a button is not something a finger can press. The
    // bounds are clipped to the list, so a button the list is cutting off reports a shorter box
    // than the button has, and a sliver of it under the keyboard would pass every check below.
    val save = saveButton().assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
    val saveBounds = save.boundsInWindow
    assertEquals(
      "The Save button is clipped to $saveBounds of ${save.size}",
      save.size.height.toFloat(),
      saveBounds.height,
      HalfPixel,
    )
    assertTrue(
      "Save is behind the keyboard or the navigation bar: $saveBounds against $visibleBottom",
      saveBounds.bottom <= visibleBottom + HalfPixel,
    )
    assertTrue(
      "Save is above the top of the list on screen: $saveBounds against $viewport",
      saveBounds.top >= viewport.top - HalfPixel,
    )
  }

  /** The Save button itself, rather than the text drawn inside it. */
  private fun saveButton() = composeRule.onNode(hasText("Save") and hasClickAction())

  /**
   * The part of the settings list a person can see. It is the list's own bounds rather than the
   * screen's, so a field the list has scrolled up under the app bar fails the check.
   */
  private fun listViewport(): Rect = composeRule.onNode(hasScrollToNodeAction()).fetchSemanticsNode().boundsInWindow

  /** Where the part of the screen the list may use ends: the navigation bar, if it has one. */
  private fun navBarTop(): Float {
    val insets = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
    val height = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
    return windowHeight() - height
  }

  /** The height of the app's own window, which is what the insets and the bounds are measured in. */
  private fun windowHeight(): Float {
    var height = 0
    InstrumentationRegistry.getInstrumentation().runOnMainSync { height = composeRule.activity.window.decorView.height }
    return height.toFloat()
  }

  /**
   * The part of the window a person can see and touch: the window without the system bars, read
   * from the window rather than guessed from the screen's size. A control outside it is under a
   * bar, which is where a tap goes to the system instead of the app.
   */
  private fun safeArea(): Rect {
    val insets = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
    val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
    val (width, height) = windowSize()
    return Rect(
      left = (bars?.left ?: 0).toFloat(),
      top = (bars?.top ?: 0).toFloat(),
      right = width - (bars?.right ?: 0),
      bottom = height - (bars?.bottom ?: 0),
    )
  }

  /** The app's own window, in the pixels the insets and the bounds are measured in. */
  private fun windowSize(): Pair<Float, Float> {
    var width = 0
    var height = 0
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      width = composeRule.activity.window.decorView.width
      height = composeRule.activity.window.decorView.height
    }
    return width.toFloat() to height.toFloat()
  }

  /**
   * The whole open editor, on screen, without anything having scrolled it: both fields and the Save
   * button inside the part of the list a person can see, and above the navigation bar.
   */
  private fun assertEditorInView() {
    awaitEditorSettled()
    val viewport = listViewport()
    val navBarTop = navBarTop()
    listOf("Name", "Days").forEach { label ->
      val bounds = composeRule.onNode(hasSetTextAction() and hasText(label)).fetchSemanticsNode().boundsInWindow
      assertTrue(
        "The $label field is above the list on screen: $bounds against $viewport",
        bounds.top >= viewport.top - HalfPixel,
      )
      assertTrue(
        "The $label field is below the list on screen: $bounds against $viewport",
        bounds.bottom <= viewport.bottom + HalfPixel,
      )
    }
    val save = saveButton().fetchSemanticsNode().boundsInWindow
    assertTrue("Save is above the list on screen: $save against $viewport", save.top >= viewport.top - HalfPixel)
    assertTrue("Save is below the list on screen: $save against $viewport", save.bottom <= viewport.bottom + HalfPixel)
    assertTrue("Save is behind the navigation bar: $save against $navBarTop", save.bottom <= navBarTop + HalfPixel)
  }

  /**
   * Opens a preset's editor the way a person does, from a row in the list, and leaves it open if
   * the screen already has it open: a turned display rebuilds the screen around the same draft.
   */
  private fun openPreset(name: String) {
    eventually {
      if (nodeCount("Save") == 0) {
        scrollSettingsTo(hasText(name))
        tapControl(composeRule.onNodeWithText(name))
      }
      composeRule.onNode(hasSetTextAction() and hasText("Name")).assertExists()
    }
  }

  /** The preset as it is stored now, read without waiting, so a missing save fails instead of hanging. */
  private fun storedPreset(id: String): FoodPreset? =
    SettingsServices.presets(appContext()).presets.value.firstOrNull { it.id == id }

  /** Puts the keyboard away, so the next test starts from a quiet screen. */
  private fun dismissKeyboardIfUp() {
    if (imeTop(InstrumentationRegistry.getInstrumentation().uiAutomation) == null) return
    shell("input keyevent 4")
    composeRule.waitForIdle()
  }

  /** Turns the display, the way a person does, and lets the app be built for the new shape. */
  private fun rotateDisplay(rotation: Int) {
    deviceSetting("system", "user_rotation", rotation.toString())
    composeRule.waitForIdle()
  }

  private fun orientation(): Int? = runCatching { composeRule.activity.resources.configuration.orientation }.getOrNull()

  private fun deviceSetting(namespace: String, key: String): String = shell("settings get $namespace $key").trim()

  private fun deviceSetting(namespace: String, key: String, value: String) {
    shell("settings put $namespace $key $value")
    Thread.sleep(500)
  }

  /** Where the keyboard starts, once it is up. */
  private fun awaitImeTop(): Float {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val deadline = System.currentTimeMillis() + 20_000
    while (System.currentTimeMillis() < deadline) {
      val top = imeTop(automation)
      if (top != null) return top
      Thread.sleep(250)
      composeRule.waitForIdle()
    }
    throw AssertionError("The keyboard never came up")
  }

  private fun imeTop(automation: android.app.UiAutomation): Float? {
    val window = automation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
      ?: return null
    val bounds = android.graphics.Rect()
    window.getBoundsInScreen(bounds)
    return if (bounds.height() > 0) bounds.top.toFloat() else null
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

  /**
   * Opens settings in a shape where the premade list is off the screen. The way in is still the
   * fridge's own control, and the way to know it worked is the title the bar changes to, because a
   * row further down a list that has not been scrolled to is not composed at all and never will be
   * in a window this short.
   */
  private fun openSettingsFromTheFridge() {
    eventually {
      if (nodeCount("Appearance") == 0) tapControl(composeRule.onNodeWithContentDescription("Settings"))
      composeRule.onNodeWithText("Settings").assertExists()
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
   * With 3-button navigation the bar belongs to the app, not the device. Checked both ways round,
   * because the scrim and the icon appearance are two separate things and either one could follow
   * the wrong side.
   *
   * Two different kinds of check live here, and they are not the same promise:
   * - the surface under the bar is read from real pixels, so a scrim the app did not ask for fails;
   * - the icon appearance is the flag the window requests
   *   (isAppearanceLightNavigationBars), not a sample of the drawn icons. It says the app asked for
   *   dark icons on a light bar; it cannot see a launcher or platform that draws them in another
   *   colour anyway. The name says so, so nobody reads it as a visual guarantee.
   */
  @Test
  fun threeButtonNavBar_followsTheAppAndNotTheDevice() {
    // The overlay the device has now is the one put back at the end; the check needs the
    // three-button navigation to exist at all, since that is the case with a real scrim.
    val overlay = enabledNavOverlay()
    assertTrue("This device has no three-button navigation to check", shell("cmd overlay list android").contains(ThreeButtonOverlay))
    try {
      enableNavOverlay(ThreeButtonOverlay)
      Thread.sleep(2_000)
      composeRule.waitForIdle()

      // A light app on a dark device: the app's own light surface, with dark icons on it.
      setDeviceNightMode(true)
      composeRule.waitForIdle()
      assertFalse("The light app followed the dark device", screenIsDark())
      assertTrue(
        "A light app still has to ask for dark navigation-bar icons on a dark device",
        settles { navBarAsksForDarkIcons() },
      )
      assertTrue(
        "The navigation bar is not showing the app's own background: ${barColours()}",
        navBarShowsAppBackground(),
      )

      // A dark app on a light device: the app's dark surface, with pale icons on it.
      openSettings()
      themeRow("Dark").performClick()
      composeRule.waitUntil(10_000) { screenIsDark() }
      backToFridge()
      setDeviceNightMode(false)
      composeRule.waitForIdle()
      assertTrue("The app did not go dark", screenIsDark())
      assertFalse(
        "A dark app has to ask for pale navigation-bar icons, not the device's light bar",
        settles { navBarAsksForDarkIcons() },
      )
      assertTrue(
        "The navigation bar is not showing the app's own background: ${barColours()}",
        navBarShowsAppBackground(),
      )
    } finally {
      runBlocking { SettingsServices.preferences(appContext()).setThemeMode(ThemeMode.Light) }
      setDeviceNightMode(null)
      enableNavOverlay(overlay)
    }
  }

  /**
   * With 3-button navigation the editor still has to be reachable. The same path as the gesture
   * check below, run against the bar that is there when a launcher offers three buttons.
   */
  @Test
  fun presetEditorWithThreeButtonNavigation_keepsTheFieldAndSaveReachable() {
    withNavOverlay(ThreeButtonOverlay) { presetEditorAtTheEndOfTheListSavesWithAFinger(BottomPreset, "Days") }
  }

  /** The same path with the gesture bar, which is thinner and sits at the very bottom. */
  @Test
  fun presetEditorWithGestureNavigation_keepsTheFieldAndSaveReachable() {
    withNavOverlay(GestureOverlay) { presetEditorAtTheEndOfTheListSavesWithAFinger(BottomPreset, "Days") }
  }

  /**
   * The whole editor path, which is the one that has to hold in every shape: open the preset, put a
   * finger in a field, and press Save with a finger where it is drawn. A tap that lands on the
   * keyboard or the navigation bar used to do something else entirely, and a Save that is only half
   * on screen is a Save that cannot be pressed.
   */
  private fun presetEditorAtTheEndOfTheListSavesWithAFinger(preset: String, field: String) {
    openSettings()
    openPreset(preset)
    // Whatever the app does on its own, the whole editor has to end up on screen.
    eventually { assertEditorInView() }

    fingerTapOnField(field)
    val imeTop = awaitImeTop()
    assertFieldAndSaveAreReachable(field, imeTop)

    composeRule.onNode(hasSetTextAction() and hasText(field)).performTextReplacement("30")
    saveWithAFinger()
    eventually {
      assertEquals("The shelf life did not change", 30, storedPreset("frozen_peas")?.expiryDays)
      // The row itself, not the words on it, and the row of the preset that was edited: Butter
      // also keeps things for thirty days, so the name is what makes this the right row.
      composeRule
        .onNode(hasClickAction() and hasText(preset) and hasText("30 days"))
        .assertIsDisplayed()
    }
    dismissKeyboardIfUp()
  }

  /** Runs [check] with the device's navigation switched to [overlay], and puts it back. */
  private fun withNavOverlay(overlay: String, check: () -> Unit) {
    val before = enabledNavOverlay()
    assertTrue("This device has no $overlay to switch to", shell("cmd overlay list android").contains(overlay))
    try {
      enableNavOverlay(overlay)
      composeRule.waitForIdle()
      check()
    } finally {
      enableNavOverlay(before)
      composeRule.waitForIdle()
    }
  }

  /** True once the check holds, or once the frames have had their chance to settle. */
  private fun settles(check: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
      if (check()) return true
      Thread.sleep(250)
    }
    return check()
  }

  /**
   * The appearance the window requests, not the icons that were drawn: true means the app asked for
   * dark icons on a light navigation bar. A sample of the drawn pixels is the only way to check
   * those, and the surface check above is the one that reads pixels here.
   */
  private fun navBarAsksForDarkIcons(): Boolean {
    var dark = false
    onMainThread {
      val window = composeRule.activity.window
      dark = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars
    }
    return dark
  }

  /** The bar, and the app just above it, as the screen actually paints them. */
  private fun barColours(): String =
    "bar ${hex(dominantColour(0.965f))} against app ${hex(dominantColour(0.90f))}"

  private fun navBarShowsAppBackground(): Boolean =
    colourDistance(dominantColour(0.965f), dominantColour(0.90f)) < 14

  /** The colour a person sees across a band of the screen, taken from a real frame. */
  private fun dominantColour(yFraction: Float): Int {
    val bitmap = frame()
    val counts = HashMap<Int, Int>()
    val y = (bitmap.height * yFraction).toInt()
    var x = (bitmap.width * 0.05f).toInt()
    while (x < bitmap.width * 0.95f) {
      val pixel = bitmap.getPixel(x, y) or (0xFF shl 24)
      counts[pixel] = (counts[pixel] ?: 0) + 1
      x += 12
    }
    return counts.maxByOrNull { it.value }?.key ?: error("could not read the screen")
  }

  private fun colourDistance(first: Int, second: Int): Int {
    fun channel(shift: Int) = Math.abs(((first shr shift) and 0xFF) - ((second shr shift) and 0xFF))
    return channel(16) + channel(8) + channel(0)
  }

  private fun hex(colour: Int): String = "#%06X".format(colour and 0xFFFFFF)

  private fun frame(): Bitmap {
    val bytes = shellBytes("screencap -p")
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Could not read the screen")
  }

  private fun enabledNavOverlay(): String =
    shell("cmd overlay list android")
      .lines()
      .firstOrNull { it.trimStart().startsWith("[x]") && "navbar" in it }
      ?.substringAfter("[x]")
      ?.trim()
      ?: ""

  private fun enableNavOverlay(overlay: String) {
    shell("cmd overlay enable-exclusive $overlay")
    Thread.sleep(1_500)
  }

  private fun onMainThread(block: () -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
  }

  /**
   * Reads the real frame and calls it light or dark by its mean brightness, which is what a person
   * would say looking at it.
   */
  private fun screenIsDark(): Boolean {
    val bitmap = frame()
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

  private companion object {
    /** Rounding slack, because bounds are measured in pixels and land on halves. */
    const val HalfPixel = 0.5f
  }
}

/** The navigation the device offers, switched through its own system overlay. */
private const val ThreeButtonOverlay = "com.android.internal.systemui.navbar.threebutton"

/** The gesture navigation the device offers, switched through its own system overlay. */
private const val GestureOverlay = "com.android.internal.systemui.navbar.gestural"

/** How big the bar draws its back control in portrait, which a shorter bar may not go below. */
private const val StockControlHeightDp = 40f

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
