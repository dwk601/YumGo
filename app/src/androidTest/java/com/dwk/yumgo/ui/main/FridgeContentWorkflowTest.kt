package com.dwk.yumgo.ui.main

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
  /** 0 leaves the surface full width; a value narrows the window the way a small phone would. */
  private var frameWidthDp by mutableIntStateOf(0)
  /** 1 is the device font scale; 2 stands in for the 200% text setting. */
  private var frameFontScale by mutableFloatStateOf(1f)

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
      val base = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(base.density, frameFontScale)) {
        Box(
          Modifier
            .fillMaxSize()
            .then(if (frameWidthDp > 0) Modifier.width(frameWidthDp.dp) else Modifier),
        ) {
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
  fun photoMessage_showsUnderPhotoWithoutFlaggingName() {
    val message = "Camera permission was not granted. You can type the item instead."
    state =
      state.copy(
        items = listOf(FridgeItemUi("milk", "Milk", 1, null, null)),
        draft = ItemDraft(null, "Kale", 1, null, null, photoMessage = message),
      )
    rule.waitForIdle()
    val name = rule.onNode(hasSetTextAction() and hasText("Name")).fetchSemanticsNode()
    assertFalse("Photo message flagged Name as an error", name.config.contains(SemanticsProperties.Error))
    val photoLabel = rule.onNodeWithText("Photo").fetchSemanticsNode()
    val shown = rule.onNodeWithText(message).assertIsDisplayed().fetchSemanticsNode()
    assertTrue("Photo message is not under Photo", shown.positionOnScreen.y > photoLabel.positionOnScreen.y)
    rule.onNodeWithText("Save").assertIsEnabled()
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
    assertEquals("Add button label is announced more than once: $spoken", 1, spoken.count { it == "Add" })
  }

  /** Add FAB located through its visible label, independent of how its semantics are merged. */
  private fun addButton(): SemanticsNodeInteraction =
    rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Add")), useUnmergedTree = true)

  /**
   * A 320dp window at 200% text: the expiry label, its date, and both actions have to stack and
   * stay readable, Save has to stay reachable, and the stepper stays usable at that text size.
   */
  @Test
  fun narrowWindowAtLargeText_keepsExpiryActionsAndSaveUsable() {
    val today = LocalDate.now().toEpochDay()
    frameWidthDp = 320
    frameFontScale = 2f
    state =
      state.copy(
        items = listOf(FridgeItemUi("milk", "Whole Milk", 2, today + 1, null)),
        draft = ItemDraft(null, "Yoghurt", 2, today + 3, null, presetId = "yogurt"),
      )
    rule.waitForIdle()

    // The editor's expiry block reads top to bottom, with nothing overlapping.
    rule.onNodeWithText("Expiry").assertIsDisplayed()
    rule.onNodeWithText("In 3 days").assertIsDisplayed()
    val date = rule.onNodeWithText("In 3 days").screenBounds()
    val choose = rule.onNodeWithText("Choose expiry date").assertIsDisplayed().screenBounds()
    val clear = rule.onNodeWithText("Clear expiry date").assertIsDisplayed().screenBounds()
    assertTrue("The choose action overlaps the date at $date and $choose", choose.top >= date.bottom)
    assertTrue("The clear action overlaps the date at $date and $clear", clear.top >= date.bottom)
    assertTrue("The expiry actions run off the 320dp window: $choose and $clear", clear.right <= screenWidth())

    // Save is still there, still inside the window, and still works.
    val save = rule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().screenBounds()
    assertTrue("Save runs off the 320dp window: $save", save.right <= screenWidth())
    rule.onNodeWithText("Save").performClick()
    rule.waitForIdle()
    assertTrue(events.toString(), "save:Yoghurt" in events)

    // The quantity stepper is still reachable at this text size.
    rule.onNode(hasContentDescription("Increase quantity of Yoghurt"), useUnmergedTree = true).assertExists()
  }

  /** The expiry actions do the work: clearing drops the date, and the picker sets a new one. */
  @Test
  fun expiryActions_clearAndRechooseTheDate() {
    val today = LocalDate.now().toEpochDay()
    state = state.copy(draft = ItemDraft(null, "Cheese", 1, today + 5, null))
    rule.waitForIdle()
    rule.onNodeWithText("In 5 days").assertIsDisplayed()
    rule.onNodeWithText("Clear expiry date").assertIsDisplayed().performClick()
    rule.waitForIdle()
    rule.onNodeWithText("No expiry").assertIsDisplayed()
    rule.onNodeWithText("Clear expiry date").assertDoesNotExist()
    rule.onNodeWithText("Choose expiry date").assertIsDisplayed().performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Set date").assertIsDisplayed()
    rule.onNode(hasText("Today", substring = true)).assertIsDisplayed().performClick()
    rule.onNodeWithText("Set date").assertIsDisplayed().performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Today").assertIsDisplayed()
    assertEquals(today, state.draft?.expiryEpochDay)
  }

  /** A 200% card keeps its name, its date, and its stepper apart on a 320dp window. */
  @Test
  fun narrowWindowAtLargeText_keepsTheCardReadable() {
    val today = LocalDate.now().toEpochDay()
    frameWidthDp = 320
    frameFontScale = 2f
    state =
      state.copy(
        items =
          listOf(
            FridgeItemUi("milk", "Semi Skimmed Whole Milk", 12, today + 1, null),
            FridgeItemUi("rice", "Basmati Rice", 2, null, null),
          ),
      )
    rule.waitForIdle()
    // The card is one clickable surface, so the text bounds come from the unmerged tree.
    val name =
      rule
        .onNode(hasText("Semi Skimmed Whole Milk"), useUnmergedTree = true)
        .assertExists()
        .screenBounds()
    val date = rule.onNode(hasText("Tomorrow"), useUnmergedTree = true).assertExists().screenBounds()
    val plus =
      rule
        .onNode(hasContentDescription("Increase quantity of Semi Skimmed Whole Milk"), useUnmergedTree = true)
        .assertExists()
        .screenBounds()
    assertTrue("The date ran into the name: $name and $date", date.top >= name.bottom)
    assertTrue("The stepper sat on the text: $date and $plus", plus.top >= date.bottom)
    assertTrue("The card runs off the 320dp window: $plus", plus.right <= screenWidth())
    rule.onNodeWithText("No expiry").assertIsDisplayed()
  }

  private fun screenWidth(): Float = rule.activity.window.decorView.width.toFloat()

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
