package com.dwk.yumgo

import android.accessibilityservice.AccessibilityServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
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

  private fun awaitImeTop(): Float {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    composeRule.waitUntil(10_000) { imeTop(automation) != null }
    return imeTop(automation)!!
  }

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
