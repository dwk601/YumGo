package com.dwk.yumgo.ui.main

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.lifecycleScope
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dwk.yumgo.theme.YumgoTheme
import java.io.FileInputStream
import java.time.LocalDate
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-keyboard workflows on the fridge surface: search and Add stay reachable above the IME,
 * the editor keeps Save visible while typing, the camera slot returns to the same draft, and
 * the snackbar slot sits above Add and the keyboard. Run on the dedicated emulator only.
 */
@RunWith(AndroidJUnit4::class)
class FridgeContentWorkflowTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private var state by mutableStateOf(FridgeUiState(load = FridgeLoad.Ready))
  private val events = mutableListOf<String>()
  private val snackbar = SnackbarHostState()

  private val callbacks =
    FridgeCallbacks(
      onQueryChange = { state = state.copy(query = it) },
      onRetry = { events += "retry" },
      onAdd = {
        events += "add"
        state = state.copy(draft = ItemDraft(null, "", 1, null, null))
      },
      onEdit = { events += "edit:$it" },
      onQuantityChange = { id, q -> events += "qty:$id:$q" },
      onDelete = { events += "delete:$it" },
      onDraftChange = { state = state.copy(draft = it) },
      onDismissEditor = {
        events += "dismiss"
        state = state.copy(draft = null)
      },
      onSave = { events += "save:${state.draft?.name}" },
      onTakePhoto = {
        events += "camera"
        state = state.copy(cameraOpen = true)
      },
      onPickPhoto = { events += "pick" },
      onRemovePhoto = { events += "remove-photo" },
    )

  @Before
  fun setUp() {
    rule.runOnUiThread {
      rule.activity.enableEdgeToEdge()
      @Suppress("DEPRECATION")
      rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.serviceInfo =
      automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    rule.setContent {
      YumgoTheme {
        FridgeContent(
          state = state,
          callbacks = callbacks,
          snackbarHost = { SnackbarHost(snackbar) },
          cameraContent = {
            BackHandler { state = state.copy(cameraOpen = false) }
            Text(CAMERA_SLOT)
          },
        )
      }
    }
  }

  @Test
  fun searchKeyboard_keepsAddAndLastResultReachable() {
    val today = LocalDate.now().toEpochDay()
    state = state.copy(items = (1..14).map { FridgeItemUi("id$it", "Apple $it", 1, today + it, null) })

    rule.onNode(hasSetTextAction() and hasText("Search")).performClick()
    val imeTop = awaitImeTop()
    rule.onNode(hasSetTextAction() and hasText("Search")).performTextInput("apple")
    rule.waitForIdle()

    rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Apple 14"))
    scrollListToEnd()
    screenshot("search-ime")
    assertTrue("Keyboard closed while scrolling", imeTopOrNull() != null)
    val add = addButton().assertIsDisplayed().screenBounds()
    val last = rule.onNodeWithText("Apple 14").assertIsDisplayed().screenBounds()
    assertTrue("Add bottom ${add.bottom} is under keyboard top $imeTop", add.bottom <= imeTop)
    assertTrue("Last result bottom ${last.bottom} is under keyboard top $imeTop", last.bottom <= imeTop)
    assertTrue("Last result bottom ${last.bottom} is under Add top ${add.top}", last.bottom <= add.top)

    assertTrue("Keyboard closed before tapping", imeTopOrNull() != null)
    rule.onNodeWithText("Apple 14").performClick()
    addButton().performClick()
    rule.waitForIdle()
    assertEquals(listOf("edit:id14", "add"), events.take(2))
  }

  @Test
  fun addEditorKeyboard_keepsSaveVisibleAndTappable() {
    state = state.copy(items = listOf(FridgeItemUi("milk", "Milk", 1, null, null)))
    addButton().performClick()
    rule.waitForIdle()
    screenshot("add-editor-opened")
    rule.waitUntil(5_000) {
      rule.onAllNodes(hasSetTextAction() and hasText("Name") and isFocused()).fetchSemanticsNodes().isNotEmpty()
    }
    val imeTop = awaitImeTop()
    rule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Eggs")
    rule.waitForIdle()
    screenshot("add-editor-ime")

    val save = rule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
    val bounds = save.screenBounds()
    assertTrue("Save bottom ${bounds.bottom} is under keyboard top $imeTop", bounds.bottom <= imeTop)
    assertTrue("Save top ${bounds.top} is off screen", bounds.top >= 0f)
    save.performClick()
    rule.waitForIdle()
    assertTrue(events.toString(), "save:Eggs" in events)
  }

  @Test
  fun editEditorKeyboard_keepsSaveVisibleWithDelete() {
    state =
      state.copy(
        items = listOf(FridgeItemUi("milk", "Milk", 2, LocalDate.now().toEpochDay() + 3, null)),
        draft = ItemDraft("milk", "Milk", 2, LocalDate.now().toEpochDay() + 3, null),
      )
    rule.waitForIdle()
    Thread.sleep(1_500)
    assertTrue(
      "Editing focused Name before it was tapped",
      rule.onAllNodes(hasSetTextAction() and hasText("Name") and isFocused()).fetchSemanticsNodes().isEmpty(),
    )
    assertTrue("Editing opened the keyboard before Name was tapped", imeTopOrNull() == null)
    rule.onNode(hasSetTextAction() and hasText("Name")).performClick()
    val imeTop = awaitImeTop()
    screenshot("edit-editor-ime")
    rule.onNodeWithText("Remove from fridge").assertExists()
    val save = rule.onNodeWithText("Save").assertIsDisplayed()
    val bounds = save.screenBounds()
    assertTrue("Save bottom ${bounds.bottom} is under keyboard top $imeTop", bounds.bottom <= imeTop)
    save.performClick()
    rule.waitForIdle()
    assertTrue(events.toString(), "save:Milk" in events)
  }

  @Test
  fun cameraBack_restoresSameDraftThenBackClosesEditor() {
    state =
      state.copy(
        items = listOf(FridgeItemUi("milk", "Milk", 1, null, null)),
        draft = ItemDraft(null, "Oat milk", 3, null, null),
      )
    rule.waitForIdle()
    rule.onNodeWithText("Take photo").performClick()
    rule.waitForIdle()
    rule.onNodeWithText(CAMERA_SLOT).assertIsDisplayed()
    rule.onNodeWithText("Add to the fridge").assertDoesNotExist()
    rule.onNodeWithText("Search").assertDoesNotExist()

    Espresso.pressBack()
    rule.waitForIdle()
    screenshot("camera-restored")
    rule.onNodeWithText(CAMERA_SLOT).assertDoesNotExist()
    rule.onNodeWithText("Add to the fridge").assertIsDisplayed()
    rule.onNode(hasSetTextAction() and hasText("Name")).assertTextContains("Oat milk")
    rule.onNode(hasContentDescription("Quantity 3"), useUnmergedTree = true).assertExists()

    rule.onNode(hasSetTextAction() and hasText("Name")).performClick()
    val imeTop = awaitImeTop()
    screenshot("camera-restored-ime")
    val save = rule.onNodeWithText("Save").assertIsDisplayed().screenBounds()
    assertTrue("Restored Save bottom ${save.bottom} is under keyboard top $imeTop", save.bottom <= imeTop)

    val activity = rule.activity
    Espresso.closeSoftKeyboard()
    Espresso.pressBackUnconditionally()
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    assertTrue(
      "Back on the restored editor did not dismiss it (finishing=${activity.isFinishing}, destroyed=${activity.isDestroyed}): $events",
      "dismiss" in events,
    )
    assertFalse("Back on the restored editor left the screen", activity.isFinishing || activity.isDestroyed)
  }

  @Test
  fun snackbarSlot_sitsAboveAddAndKeyboardAndUndoWorks() {
    state = state.copy(items = listOf(FridgeItemUi("milk", "Milk", 1, null, null)))
    rule.onNode(hasSetTextAction() and hasText("Search")).performClick()
    val imeTop = awaitImeTop()
    var result: SnackbarResult? = null
    rule.runOnUiThread {
      rule.activity.lifecycleScope.launch { result = snackbar.showSnackbar("Milk removed", actionLabel = "Undo") }
    }
    rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Undo") > 0 }
    rule.waitForIdle()
    screenshot("snackbar-ime")
    val undo = rule.onNodeWithText("Undo").assertIsDisplayed().screenBounds()
    val add = addButton().screenBounds()
    assertTrue("Undo bottom ${undo.bottom} is under keyboard top $imeTop", undo.bottom <= imeTop)
    assertTrue("Undo bottom ${undo.bottom} overlaps Add top ${add.top}", undo.bottom <= add.top)
    rule.onNodeWithText("Undo").performClick()
    rule.waitUntil(5_000) { result != null }
    assertEquals(SnackbarResult.ActionPerformed, result)
  }

  @Test
  fun addButton_hasAccessibleName() {
    state = state.copy(items = listOf(FridgeItemUi("milk", "Milk", 1, null, null)))
    rule.waitForIdle()
    val fab = addButton().assertIsDisplayed().fetchSemanticsNode()
    val merged = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().first { it.id == fab.id }
    val spoken =
      merged.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } +
        merged.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
    assertTrue("Add button is announced without a label: $spoken", spoken.any { it == "Add" })
  }

  /** Add FAB located through its visible label, independent of how its semantics are merged. */
  private fun addButton(): SemanticsNodeInteraction =
    rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Add")), useUnmergedTree = true)

  private fun scrollListToEnd() {
    var guard = 0
    while (guard++ < 12) {
      val range = rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
      if (range.value() >= range.maxValue()) break
      rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
      rule.waitForIdle()
    }
  }

  private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(hasText(text)).fetchSemanticsNodes().size

  private fun SemanticsNodeInteraction.screenBounds(): androidx.compose.ui.geometry.Rect {
    val node = fetchSemanticsNode()
    val origin: Offset = node.positionOnScreen
    return androidx.compose.ui.geometry.Rect(
      origin.x,
      origin.y,
      origin.x + node.size.width,
      origin.y + node.size.height,
    )
  }

  private fun imeTopOrNull(): Float? {
    val window =
      InstrumentationRegistry.getInstrumentation().uiAutomation.windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
      } ?: return null
    val bounds = Rect()
    window.getBoundsInScreen(bounds)
    return if (bounds.height() > 0) bounds.top.toFloat() else null
  }

  /** Waits for the real IME window, then for insets animations to settle. */
  private fun awaitImeTop(): Float {
    rule.waitUntil(10_000) { imeTopOrNull() != null }
    var previous = -1f
    rule.waitUntil(5_000) {
      Thread.sleep(150)
      val top = imeTopOrNull() ?: return@waitUntil false
      val stable = top == previous
      previous = top
      stable
    }
    rule.waitForIdle()
    return imeTopOrNull()!!
  }

  private fun screenshot(name: String) {
    val output =
      InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("screencap -p /data/local/tmp/yumgo-t3-$name.png")
    FileInputStream(output.fileDescriptor).use { it.readBytes() }
    output.close()
  }

  private companion object {
    const val CAMERA_SLOT = "Camera slot"
  }
}
