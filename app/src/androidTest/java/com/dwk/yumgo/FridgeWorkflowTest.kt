package com.dwk.yumgo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Real fridge workflows on the dedicated emulator: add, edit, quantity, undo, and relaunch.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FridgeWorkflowTest {
  private val composeRule = createAndroidComposeRule<MainActivity>()

  @get:Rule val rule: TestRule = RuleChain.outerRule(WipeFridgeRule()).around(composeRule)

  @Before
  fun watchIme() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.serviceInfo =
      automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
  }

  @Test
  fun addEditQuantityAndRelaunch_persists() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Relaunch Milk")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Relaunch Milk") > 0 }
    composeRule.onNodeWithText("Relaunch Milk").assertIsDisplayed()

    val increase =
      composeRule.onAllNodes(hasContentDescription("Increase quantity of Relaunch Milk"), useUnmergedTree = true).fetchSemanticsNodes().first()
    val increaseX = (increase.positionOnScreen.x + increase.size.width / 2f).toInt()
    val increaseY = (increase.positionOnScreen.y + increase.size.height / 2f).toInt()
    shell("input tap $increaseX $increaseY")
    composeRule.waitUntil(8_000) {
      composeRule.onAllNodes(hasText("2") or hasContentDescription("Quantity 2"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText("Relaunch Milk").performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name", substring = true)).performTextReplacement("Relaunch Milk 2")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Relaunch Milk 2") > 0 }

    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) { nodeCount("Relaunch Milk 2") > 0 }
    composeRule.onNodeWithText("Relaunch Milk 2").assertIsDisplayed()
    composeRule.onNode(hasContentDescription("Quantity 2"), useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun delete_snackbarUndo_restoresItem() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Undo Yogurt")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Undo Yogurt") > 0 }

    composeRule.onNodeWithText("Undo Yogurt").performClick()
    composeRule.onNodeWithText("Remove from fridge").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Undo Yogurt") == 0 }
    composeRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
    composeRule.waitUntil(10_000) { nodeCount("Undo Yogurt") > 0 }
    composeRule.onNodeWithText("Undo Yogurt").assertIsDisplayed()
  }

  @Test
  fun delete_recreate_undoRestoresItem() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Rotate Beans")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Rotate Beans") > 0 }

    composeRule.onNodeWithText("Rotate Beans").performClick()
    composeRule.onNodeWithText("Remove from fridge").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Undo") > 0 && nodeCount("Rotate Beans") == 0 }

    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
    composeRule.waitUntil(10_000) { nodeCount("Rotate Beans") > 0 }
    composeRule.onNodeWithText("Rotate Beans").assertIsDisplayed()
  }

  /**
   * Two quick deletes: only the latest delete is offered for Undo (one snackbar), Undo restores
   * that item, and the earlier item stays removed. The offer survives recreation and is gone
   * after Undo, including after another recreation.
   */
  @Test
  fun rapidSecondDelete_undoRestoresLatestOnly() {
    for (name in listOf("First Figs", "Second Grapes")) {
      addButton().performClick()
      composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput(name)
      composeRule.onNodeWithText("Save").performClick()
      composeRule.waitUntil(10_000) { nodeCount(name) > 0 }
    }
    for (name in listOf("First Figs", "Second Grapes")) {
      composeRule.onNodeWithText(name).performClick()
      composeRule.onNodeWithText("Remove from fridge").performClick()
      composeRule.waitUntil(10_000) { nodeCount(name) == 0 && nodeCount("$name removed") > 0 }
    }
    composeRule.onNodeWithText("First Figs removed").assertDoesNotExist()
    org.junit.Assert.assertEquals(1, nodeCount("Undo"))

    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) { nodeCount("Second Grapes removed") > 0 }
    composeRule.onNodeWithText("Undo").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Second Grapes") > 0 }
    org.junit.Assert.assertEquals(0, nodeCount("First Figs"))
    composeRule.waitUntil(5_000) { nodeCount("Undo") == 0 }

    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) { nodeCount("Second Grapes") > 0 }
    org.junit.Assert.assertEquals("Undo came back after it was used", 0, nodeCount("Undo"))
  }

  @Test
  fun draftSurvivesRecreation_andSaveStaysAboveKeyboard() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Draft Cheese")
    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) {
      composeRule.onAllNodes(hasSetTextAction() and hasText("Name", substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNode(hasSetTextAction() and hasText("Draft Cheese", substring = true)).performClick()

    val imeTop = awaitImeTop()
    val save = composeRule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
    val node = save.fetchSemanticsNode()
    val bottom = node.positionOnScreen.y + node.size.height
    org.junit.Assert.assertTrue("Save bottom $bottom is under the keyboard $imeTop", bottom <= imeTop)
  }

  /**
   * Cold relaunch: the first activity and its ViewModels are destroyed, the process-wide
   * repository is dropped, and a new activity must read the item back from the SQLite file.
   */
  @Test
  fun coldRelaunch_readsSavedItemFromDisk() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Cold Butter")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount("Cold Butter") > 0 }
    val increase =
      composeRule.onAllNodes(hasContentDescription("Increase quantity of Cold Butter"), useUnmergedTree = true)
        .fetchSemanticsNodes().first()
    shell(
      "input tap ${(increase.positionOnScreen.x + increase.size.width / 2f).toInt()} " +
        "${(increase.positionOnScreen.y + increase.size.height / 2f).toInt()}",
    )
    composeRule.waitUntil(8_000) { readQuantity("Cold Butter") == 2 }

    composeRule.activityRule.scenario.close()
    com.dwk.yumgo.ui.main.PhotoStoreHolder.resetForTests()
    androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
      composeRule.waitUntil(10_000) { nodeCount("Cold Butter") > 0 }
      composeRule.onNodeWithText("Cold Butter").assertIsDisplayed()
      composeRule.onNode(hasContentDescription("Quantity 2"), useUnmergedTree = true).assertIsDisplayed()
    }
  }

  /**
   * The caret belongs to the user, not to the field: an activity that is built again has to bring
   * back the text and the place in it they were at, and the next keystroke has to land there.
   */
  @Test
  fun nameCaret_comesBackWhereItWasAfterRecreation() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Milk")
    composeRule.waitUntil(10_000) { nodeCount("Milk") > 0 }
    assertEquals("The caret did not follow the typing", 4, caret())

    // Put the caret between the M and the ilk, the way someone fixing a typo would.
    composeRule
      .onNode(hasSetTextAction() and hasText("Milk", substring = true))
      .performTextInputSelection(TextRange(1))
    composeRule.waitUntil(5_000) { caret() == 1 }

    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.waitUntil(15_000) { nodeCount("Milk") > 0 && nodeCount("Save") > 0 }
    assertEquals("The caret did not come back where it was", 1, caret())

    // And typing goes where the caret is, rather than at the start of the text.
    composeRule.onNode(hasSetTextAction() and hasText("Milk", substring = true)).performTextInput("o")
    composeRule.waitUntil(10_000) { nodeCount("Moilk") > 0 }
    assertEquals("The text was not typed at the caret", 2, caret())
  }

  /**
   * A shortcut fills what the user has not decided and nothing they have. A name they have only
   * started is finished, because narrowing the lane to their typing promised a tap would complete
   * it; a name they typed whole is left alone, and the shortcut's date is filled under it.
   */
  @Test
  fun shortcut_finishesAPartTypedNameAndLeavesAWholeOneAlone() {
    addButton().performClick()
    composeRule.waitUntil(15_000) { nodeCount("Premade items") > 0 }
    val name = composeRule.onNode(hasSetTextAction() and hasText("Name", substring = true))

    // "Mi" narrows the lane to the one shortcut it can finish, and the tap finishes it.
    name.performTextInput("Mi")
    composeRule.waitUntil(10_000) { nodeCount("Milk") > 0 }
    composeRule.onNodeWithText("Milk").performClick()
    composeRule.waitUntil(10_000) { nodeCount("In 7 days") > 0 }
    name.assertTextContains("Milk")

    // A name of their own is not replaced by a shortcut, and the date is filled under it.
    name.performTextReplacement("Kale")
    composeRule.onNodeWithText("Clear expiry date").performClick()
    composeRule.waitUntil(10_000) { nodeCount("No expiry") > 0 }
    composeRule.onNodeWithText("Milk").performClick()
    composeRule.waitUntil(10_000) { nodeCount("In 7 days") > 0 }
    val spoken = name.spoken()
    assertTrue("The shortcut took a name the user typed whole: $spoken", "Kale" in spoken)
    assertTrue("The shortcut took a name the user typed whole: $spoken", "Milk" !in spoken)
    assertTrue("The shortcut took a name the user typed whole: $spoken", "Milk" !in spoken)
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(15_000) { nodeCount("Kale") > 0 }
    composeRule.onNodeWithText("Kale").assertIsDisplayed()
    composeRule.onNodeWithText("In 7 days").assertIsDisplayed()

    // An empty field takes the shortcut's name as well as its date.
    addButton().performClick()
    composeRule.waitUntil(15_000) { nodeCount("Premade items") > 0 }
    composeRule.onNodeWithText("Milk").performClick()
    composeRule.waitUntil(10_000) { nodeCount("In 7 days") > 0 }
    composeRule
      .onNode(hasSetTextAction() and hasText("Name", substring = true))
      .assertTextContains("Milk")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(15_000) { nodeCount("Milk") > 0 }
    composeRule.onNodeWithText("Milk").assertIsDisplayed()
  }

  /**
   * A configuration change, which is the path a rotation takes: the activity is destroyed and
   * built again, so the open sheet, the typed name, and a reachable Save all have to come back, and
   * a saved item has to still be there afterwards.
   */
  @Test
  fun configurationChange_keepsTheOpenEditorAndTheFridge() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Rotate Eggs")
    composeRule.waitUntil(5_000) { nodeCount("Rotate Eggs") > 0 }

    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.waitUntil(15_000) { nodeCount("Rotate Eggs") > 0 && nodeCount("Add to the fridge") > 0 }
    // Save stays usable, and the draft is still the one being typed.
    composeRule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
    composeRule.onNode(hasSetTextAction() and hasText("Rotate Eggs", substring = true)).assertExists()
    composeRule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().performClick()
    composeRule.waitUntil(15_000) { nodeCount("Rotate Eggs") > 0 }
    composeRule.onNodeWithText("Rotate Eggs").assertIsDisplayed()
  }

  /**
   * A real rotation, taken the way a person takes it: auto-rotate off and the display turned. The
   * activity is rebuilt for the new shape, and the draft being typed and a usable Save come back
   * with it. Both device settings are put back.
   */
  @Test
  fun realRotation_keepsTheOpenEditorAndTheFridge() {
    val autoRotate = deviceSetting("system", "accelerometer_rotation")
    val rotation = deviceSetting("system", "user_rotation")
    try {
      deviceSetting("system", "accelerometer_rotation", "0")
      addButton().performClick()
      composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Turned Eggs")
      composeRule.waitUntil(5_000) { nodeCount("Turned Eggs") > 0 }

      rotateDisplay(1)
      composeRule.waitUntil(20_000) { nodeCount("Turned Eggs") > 0 && orientation() == ORIENTATION_LANDSCAPE }
      assertEquals("The app did not follow the display", ORIENTATION_LANDSCAPE, orientation())
      composeRule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
      composeRule.onNode(hasSetTextAction() and hasText("Turned Eggs", substring = true)).assertExists()
      composeRule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().performClick()
      composeRule.waitUntil(20_000) { nodeCount("Turned Eggs") > 0 }

      rotateDisplay(0)
      composeRule.waitUntil(20_000) { nodeCount("Turned Eggs") > 0 && orientation() == ORIENTATION_PORTRAIT }
      assertEquals("The app did not follow the display back", ORIENTATION_PORTRAIT, orientation())
      composeRule.onNodeWithText("Turned Eggs").assertIsDisplayed()
      // One more turn each way, then a quiet period: a screen that was being rebuilt over and over
      // would come back as a different instance with nothing left to do.
      rotateDisplay(1)
      rotateDisplay(0)
      composeRule.waitUntil(20_000) { orientation() == ORIENTATION_PORTRAIT }
      val settled = composeRule.activity
      Thread.sleep(4_000)
      composeRule.waitForIdle()
      assertEquals(
        "The activity was rebuilt again while nothing was happening",
        System.identityHashCode(settled),
        System.identityHashCode(composeRule.activity),
      )
    } finally {
      deviceSetting("system", "user_rotation", rotation)
      deviceSetting("system", "accelerometer_rotation", autoRotate)
    }
  }

  /**
   * Landscape with the keyboard up, which is the shape that used to leave only the sheet's handle
   * and Save on screen. The field being typed into keeps focus and its text, the keyboard stays,
   * what was typed sits above the keyboard, and a finger can reach Save.
   */
  @Test
  fun landscapeWithTheKeyboard_keepsTheFieldTypedIntoAndSaveReachable() {
    val autoRotate = deviceSetting("system", "accelerometer_rotation")
    val rotation = deviceSetting("system", "user_rotation")
    try {
      deviceSetting("system", "accelerometer_rotation", "0")
      addButton().performClick()
      composeRule.waitUntil(15_000) { nodeCount("Add to the fridge") > 0 }
      // Put the keyboard away first, so it is the turn below and then the typing that have to
      // cope with a short window. A keyboard that was already up would hide the problem.
      shell("input keyevent 4")
      composeRule.waitUntil(10_000) { imeTopOrNull() == null }
      composeRule.onNodeWithText("Add to the fridge").assertIsDisplayed()

      rotateDisplay(1)
      composeRule.waitUntil(20_000) { orientation() == ORIENTATION_LANDSCAPE && nodeCount("Add to the fridge") > 0 }
      val name = composeRule.onNode(hasSetTextAction() and hasText("Name"))

      // Typing is what raises the keyboard in this shape, and the field has to survive it.
      name.performClick()
      val imeTop = awaitImeTop()
      composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Keyboard Eggs")
      composeRule.waitUntil(10_000) { nodeCount("Keyboard Eggs") > 0 }
      assertTrue(
        "The name field lost focus when the display turned",
        composeRule
          .onAllNodes(hasSetTextAction() and hasText("Name") and isFocused())
          .fetchSemanticsNodes()
          .isNotEmpty(),
      )
      // The keyboard is still there: typing only works while it is.
      assertNotNull("The keyboard closed itself in landscape", imeTopOrNull())

      // What was typed, and the button that saves it, are both above the keyboard.
      val field =
        composeRule
          .onNode(hasSetTextAction() and hasText("Keyboard Eggs", substring = true))
          .assertIsDisplayed()
          .fetchSemanticsNode()
      val fieldBottom = field.positionOnScreen.y + field.size.height
      assertTrue("The text is behind the keyboard at $fieldBottom against $imeTop", fieldBottom <= imeTop)

      // The field has to be whole. A short editor that pushed it up clipped it at the top of the
      // sheet, which leaves a field that is still "displayed" and still above the keyboard, so
      // the bounds the field is drawn in are compared with the bounds it was given.
      val fieldBounds = field.boundsInRoot
      assertEquals(
        "The name field is clipped to $fieldBounds of ${field.size}",
        field.size.height.toFloat(),
        fieldBounds.height,
        HalfPixel,
      )
      // And it starts below the sheet's own header, which in a short window is the row that
      // carries the title. A field can be inside the window and still be under the sheet's
      // chrome, which no keyboard check can see.
      val header = composeRule.onAllNodes(hasText(EditorTitle)).fetchSemanticsNodes().firstOrNull()
      assertNotNull("The sheet's header is missing, so the top of the sheet is unknown", header)
      assertTrue(
        "The name field starts above the sheet's header: field $fieldBounds, header ${header!!.boundsInRoot}",
        fieldBounds.top >= header.boundsInRoot.bottom,
      )
      // Finally, the text the user typed has to be painted where the field says it is, rather
      // than hidden behind something drawn over the field.
      val drawn = darkPixelsIn(fieldBounds, fromFraction = 0.5f)
      assertTrue("Nothing is drawn in the lower half of the name field $fieldBounds, so the text is covered", drawn >= MinimumGlyphPixels)
      val save = composeRule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
      val saveBottom = save.positionOnScreen.y + save.size.height
      assertTrue("Save is behind the keyboard at $saveBottom against $imeTop", saveBottom <= imeTop)

      // And a finger can press it, which is the whole point of the compact layout.
      shell(
        "input tap ${(save.positionOnScreen.x + save.size.width / 2).toInt()} ${(save.positionOnScreen.y + save.size.height / 2).toInt()}",
      )
      composeRule.waitUntil(20_000) { nodeCount("Add to the fridge") == 0 && nodeCount("Keyboard Eggs") > 0 }
      composeRule.onNodeWithText("Keyboard Eggs").assertIsDisplayed()

      rotateDisplay(0)
      composeRule.waitUntil(20_000) { orientation() == ORIENTATION_PORTRAIT }
      composeRule.onNodeWithText("Keyboard Eggs").assertIsDisplayed()
    } finally {
      deviceSetting("system", "user_rotation", rotation)
      deviceSetting("system", "accelerometer_rotation", autoRotate)
    }
  }

  /**
   * Dark pixels inside the lower part of a rect on the real screen. A field showing the text the
   * user typed has glyphs in it; a field covered by the sheet's own chrome has none.
   */
  private fun darkPixelsIn(bounds: androidx.compose.ui.geometry.Rect, fromFraction: Float): Int {
    val bytes = screencapBytes()
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Could not read the screen")
    val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
    val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
    val top = (bounds.top + bounds.height * fromFraction).toInt().coerceIn(0, bitmap.height - 1)
    val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
    var dark = 0
    for (y in top until bottom) {
      for (x in left until right) {
        val pixel = bitmap.getPixel(x, y)
        val luminance = 0.2126 * ((pixel shr 16) and 0xFF) + 0.7152 * ((pixel shr 8) and 0xFF) + 0.0722 * (pixel and 0xFF)
        if (luminance < DarkPixelLimit) dark++
      }
    }
    return dark
  }

  private fun screencapBytes(): ByteArray {
    val process = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p")
    return FileInputStream(process.fileDescriptor).use { it.readBytes() }.also { process.close() }
  }

  /** Turns the display and waits for the app to be built for the new shape. */
  private fun rotateDisplay(rotation: Int) {
    deviceSetting("system", "user_rotation", rotation.toString())
    composeRule.waitForIdle()
  }

  private fun orientation(): Int? =
    runCatching { composeRule.activity.resources.configuration.orientation }.getOrNull()

  private fun deviceSetting(namespace: String, key: String): String =
    shell("settings get $namespace $key").trim()

  private fun deviceSetting(namespace: String, key: String, value: String) {
    shell("settings put $namespace $key $value")
    Thread.sleep(500)
  }

  private fun readQuantity(name: String): Int? {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val path = context.getDatabasePath("yumgo_fridge.db").path
    return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      db.rawQuery("SELECT quantity FROM fridge_item WHERE name = ? AND deleted_at IS NULL", arrayOf(name)).use {
        if (it.moveToFirst()) it.getInt(0) else null
      }
    }
  }

  private fun addButton() =
    composeRule.onNode(hasClickAction() and hasAnyDescendant(hasText("Add")), useUnmergedTree = true)

  private fun nodeCount(text: String): Int = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

  /**
   * Where the caret sits in the name field, read from what the field publishes. A field that
   * reports its selection at the start of the text has thrown away the place the user was at.
   */
  /**
   * Everything the name field puts in the tree, label and value alike: a text field's value lives
   * beside its label in the semantics rather than inside it.
   */
  private fun SemanticsNodeInteraction.spoken(): List<String> {
    val config = fetchSemanticsNode().config
    return buildList {
      addAll(config[SemanticsProperties.Text].orEmpty().map { it.text })
      config[SemanticsProperties.InputText]?.let { add(it.text) }
      config[SemanticsProperties.EditableText]?.let { add(it.text) }
    }
  }

  private fun caret(): Int? {
    val range =
      composeRule
        .onNode(hasSetTextAction() and hasText("Name", substring = true))
        .fetchSemanticsNode()
        .config[SemanticsProperties.TextSelectionRange]
    return if (range != null && range.collapsed) range.start else null
  }

  private companion object {
    /** A glyph stroke well under this, and anything brighter than this is not ink. */
    const val MinimumGlyphPixels = 40
    const val DarkPixelLimit = 120
    /** Rounding slack, because bounds are measured in pixels and land on halves. */
    const val HalfPixel = 0.5f
    /** The sheet's own header, which carries the title in a short window. */
    const val EditorTitle = "Add to the fridge"
  }

  private fun awaitImeTop(): Float {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    composeRule.waitUntil(10_000) { imeTop(automation) != null }
    return imeTop(automation)!!
  }

  private fun imeTopOrNull(): Float? =
    imeTop(InstrumentationRegistry.getInstrumentation().uiAutomation)

  private fun imeTop(automation: android.app.UiAutomation): Float? {
    val window =
      automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
    val bounds = Rect()
    window.getBoundsInScreen(bounds)
    return if (bounds.height() > 0) bounds.top.toFloat() else null
  }
}

internal class WipeFridgeRule : TestRule {
  override fun apply(base: Statement, description: Description): Statement =
    object : Statement() {
      override fun evaluate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.dwk.yumgo.ui.main.PhotoStoreHolder.resetForTests()
        context.deleteDatabase("yumgo_fridge.db")
        File(context.filesDir, "fridge-photos").deleteRecursively()
        base.evaluate()
      }
    }
}

internal fun readPhotoRef(name: String): String? {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val path = context.getDatabasePath("yumgo_fridge.db").path
  val database = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
  database.use {
    it.rawQuery(
      "SELECT photo_ref FROM fridge_item WHERE name = ? AND deleted_at IS NULL",
      arrayOf(name),
    ).use { cursor ->
      if (!cursor.moveToFirst() || cursor.isNull(0)) return null
      return cursor.getString(0)
    }
  }
}

internal fun shell(command: String): String {
  val process = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
  return FileInputStream(process.fileDescriptor).use { it.readBytes().decodeToString() }.also { process.close() }
}
