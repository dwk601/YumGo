package com.dwk.yumgo

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Camera and picker workflows on the dedicated emulator. Permission is granted only for the
 * capture test; denial must leave typing available.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PhotoWorkflowTest {
  private val composeRule = createAndroidComposeRule<MainActivity>()

  @get:Rule val rule: TestRule = RuleChain.outerRule(WipeFridgeRule()).around(composeRule)

  @Before
  fun watchSystemWindows() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.serviceInfo =
      automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
  }

  @Test
  fun captureReview_persistsSavedPhotoAcrossRelaunch() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Camera Berry")
    composeRule.onNodeWithText("Take photo").performClick()
    composeRule.waitUntil(25_000) {
      if (nodeCount(hasTestTag("photo_permission_continue")) > 0) {
        composeRule.onNodeWithTag("photo_permission_continue").performClick()
      }
      clickSystemLabel(listOf("While using the app", "Only this time", "Allow"))
      nodeCount(hasTestTag("photo_shutter") and isEnabled()) > 0
    }
    composeRule.onNodeWithTag("photo_shutter").performClick()
    composeRule.waitUntil(20_000) {
      composeRule.onAllNodes(hasTestTag("photo_use") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("photo_use").performClick()
    composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Save")).fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Camera Berry")).fetchSemanticsNodes().isNotEmpty() }

    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Camera Berry")).fetchSemanticsNodes().isNotEmpty() }
    val ref = readPhotoRef("Camera Berry")
    assertNotNull("Saved photo reference missing after relaunch", ref)
    assertTrue(ref, ref!!.startsWith("saved/"))
    val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "fridge-photos/$ref")
    assertTrue("Saved photo file missing: ${file.path}", file.isFile && file.length() > 0L)
  }

  @Test
  fun pickerCancel_keepsTypedName() {
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Picker Peas")
    composeRule.onNodeWithText("Choose photo").performClick()
    composeRule.waitForIdle()
    Thread.sleep(1_000)
    shell("input keyevent 4")
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) {
      composeRule.onAllNodes(hasSetTextAction() and hasText("Picker Peas", substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNode(hasSetTextAction() and hasText("Picker Peas", substring = true)).assertIsDisplayed()
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Picker Peas")).fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun cameraDenial_returnsToTyping() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val alreadyGranted =
      ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    assertTrue("Camera permission is already granted, so this would not be a real denial", !alreadyGranted)
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Denied ")
    composeRule.onNodeWithText("Take photo").performClick()
    var deniedOnSystemDialog = false
    composeRule.waitUntil(20_000) {
      if (nodeCount(hasTestTag("photo_permission_continue")) > 0) {
        composeRule.onNodeWithTag("photo_permission_continue").performClick()
      }
      if (clickSystemLabel(listOf("Don't allow", "Don’t allow", "Deny"))) deniedOnSystemDialog = true
      deniedOnSystemDialog && nodeCount(hasText("Add to the fridge")) > 0 && nodeCount(hasTestTag("photo_shutter")) == 0
    }
    assertTrue("The system permission dialog was never denied", deniedOnSystemDialog)
    composeRule.waitUntil(10_000) {
      composeRule.onAllNodes(hasText("Add to the fridge")).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNode(hasSetTextAction() and hasText("Denied", substring = true)).assertIsDisplayed()
    // The denial is a neutral photo message under Photo, not an error on the typed name.
    composeRule.onNodeWithText("Camera permission was not granted. You can type the item instead.").assertIsDisplayed()
    val name = composeRule.onNode(hasSetTextAction() and hasText("Denied", substring = true)).fetchSemanticsNode()
    assertTrue("Denial flagged Name as an error", !name.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Error))
    composeRule.onNode(hasSetTextAction() and hasText("Denied", substring = true)).performTextReplacement("Denied Oats")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(15_000) { composeRule.onAllNodes(hasText("Denied Oats")).fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithText("Denied Oats").assertIsDisplayed()
  }

  /**
   * Camera Back returns to the same typed draft; the open camera and a staged photo both survive
   * activity recreation; the photo is promoted into saved storage on Save.
   * Runs after [cameraDenial_returnsToTyping] and [captureReview_persistsSavedPhotoAcrossRelaunch] (name order), so granting here cannot fake a denial or skip the real Allow dialog.
   */
  @Test
  fun photoDraft_survivesCameraBackAndRecreation() {
    grantCamera()
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Back Kale")
    composeRule.onNodeWithText("Take photo").performClick()
    awaitShutter()
    shell("input keyevent 4")
    composeRule.waitUntil(10_000) {
      nodeCount(hasTestTag("photo_capture")) == 0 && nodeCount(hasSetTextAction() and hasText("Back Kale", substring = true)) > 0
    }
    composeRule.onNodeWithText("Add to the fridge").assertIsDisplayed()

    composeRule.onNodeWithText("Take photo").performClick()
    awaitShutter()
    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    awaitShutter()
    composeRule.onNodeWithTag("photo_shutter").performClick()
    composeRule.waitUntil(20_000) { nodeCount(hasTestTag("photo_use") and isEnabled()) > 0 }
    composeRule.onNodeWithTag("photo_use").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Remove photo")) > 0 }

    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) {
      nodeCount(hasSetTextAction() and hasText("Back Kale", substring = true)) > 0 && nodeCount(hasText("Remove photo")) > 0
    }
    val staged = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "fridge-photos/staged")
    assertTrue("Staged photo missing after recreation", staged.listFiles().orEmpty().any { it.name.endsWith(".jpg") })

    // A recreated new draft reopens its sheet, focuses Name, and raises the keyboard, which moves
    // Save. Tap Save once that real keyboard layout has settled, as a user would.
    awaitImeSettled()
    composeRule.onNodeWithText("Save").assertIsDisplayed().performClick()
    composeRule.waitUntil(10_000) { readPhotoRef("Back Kale") != null }
    val ref = readPhotoRef("Back Kale")!!
    assertTrue(ref, ref.startsWith("saved/"))
    assertTrue(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "fridge-photos/$ref").isFile)
  }

  /**
   * A deleted item's saved photo is still protected while Undo is offered, even when another save
   * runs photo cleanup, and Undo brings back the same photo.
   */
  @Test
  fun deletedPhotoItem_keepsPhotoThroughCleanupUntilUndo() {
    grantCamera()
    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Undo Plum")
    composeRule.onNodeWithText("Take photo").performClick()
    awaitShutter()
    composeRule.onNodeWithTag("photo_shutter").performClick()
    composeRule.waitUntil(20_000) { nodeCount(hasTestTag("photo_use") and isEnabled()) > 0 }
    composeRule.onNodeWithTag("photo_use").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Remove photo")) > 0 }
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { readPhotoRef("Undo Plum") != null }
    val ref = readPhotoRef("Undo Plum")!!
    val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "fridge-photos/$ref")
    assertTrue(file.isFile)

    composeRule.onNodeWithText("Undo Plum").performClick()
    composeRule.onNodeWithText("Remove from fridge").assertIsDisplayed().performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Undo")) > 0 && nodeCount(hasText("Undo Plum")) == 0 }

    addButton().performClick()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Cleanup Rice")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Cleanup Rice")) > 0 }
    composeRule.waitForIdle()
    Thread.sleep(1_000)
    assertTrue("Cleanup deleted the photo of an item that can still be undone", file.isFile)

    composeRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Undo Plum")) > 0 }
    assertTrue(readPhotoRef("Undo Plum") == ref)
    assertTrue("Restored item's photo is gone", file.isFile)
  }

  private fun awaitImeSettled() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    fun imeTop(): Int? =
      automation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { window -> Rect().also(window::getBoundsInScreen).takeIf { it.height() > 0 }?.top }
    composeRule.waitUntil(10_000) { imeTop() != null }
    var previous: Int? = null
    composeRule.waitUntil(5_000) {
      Thread.sleep(150)
      val top = imeTop()
      (top != null && top == previous).also { previous = top }
    }
    composeRule.waitForIdle()
  }

  private fun grantCamera() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
  }

  private fun awaitShutter() {
    composeRule.waitUntil(25_000) { nodeCount(hasTestTag("photo_shutter") and isEnabled()) > 0 }
  }

  private fun nodeCount(matcher: androidx.compose.ui.test.SemanticsMatcher): Int =
    try {
      composeRule.onAllNodes(matcher).fetchSemanticsNodes().size
    } catch (_: IllegalStateException) {
      0
    }

  private fun addButton() =
    composeRule.onNode(hasClickAction() and hasAnyDescendant(hasText("Add")), useUnmergedTree = true)

  private fun clickSystemLabel(labels: List<String>): Boolean {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (window in automation.windows) {
      val root = window.root ?: continue
      val node = findClickable(root, labels)
      if (node != null) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        return true
      }
    }
    return false
  }

  private fun findClickable(node: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
    val text = node.text?.toString().orEmpty()
    val description = node.contentDescription?.toString().orEmpty()
    if (labels.any { text.equals(it, ignoreCase = true) || description.equals(it, ignoreCase = true) }) return node
    for (index in 0 until node.childCount) {
      val child = node.getChild(index) ?: continue
      val found = findClickable(child, labels)
      if (found != null) return found
    }
    return null
  }
}
