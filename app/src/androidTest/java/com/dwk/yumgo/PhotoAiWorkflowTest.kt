package com.dwk.yumgo

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dwk.yumgo.ui.main.PhotoStoreHolder
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Photo-first entry plus cloud analysis, without live cloud requests. Every analysis answer
 * comes from a [FakePhotoAnalyzer] installed on [PhotoStoreHolder.analyzerForTests]; no test
 * here contacts OpenRouter.
 *
 * Photo acquisition reuses the proven emulator patterns: pre-granted camera capture, the
 * permission-free system picker, denial, and cancel. Each test sets its own camera permission
 * first, so no test depends on run order.
 */
@RunWith(AndroidJUnit4::class)
class PhotoAiWorkflowTest {
  private val composeRule = createAndroidComposeRule<MainActivity>()

  @get:Rule val rule: TestRule = RuleChain.outerRule(WipeFridgeRule()).around(composeRule)

  @Before
  fun watchSystemWindows() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.serviceInfo =
      automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
  }

  @After
  fun clearAnalyzerOverride() {
    PhotoStoreHolder.analyzerForTests = null
  }

  /**
   * Direct capture → detected suggestion → name correction → Save → relaunch. The analyzer
   * receives the staged ref, the printed date lands with Detected provenance, and the saved
   * item keeps the corrected name and a promoted photo.
   */
  @Test
  fun captureToSuggestion_detectedDateLandsAndSavesAcrossRelaunch() {
    grantCamera()
    val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.detected())
    capturePhotoFirstDraft()
    // The cloud disclosure is visible before anything is submitted.
    composeRule.onNodeWithText("Analysis sends this photo to cloud AI.", substring = true).assertIsDisplayed()

    PhotoStoreHolder.analyzerForTests = fake
    composeRule.onNodeWithTag("analyzePhotoButton").assertIsDisplayed().performClick()
    composeRule.waitUntil(15_000) { nodeCount(hasText("Detected expiry")) > 0 }
    composeRule.onNodeWithText("Detected expiry").assertIsDisplayed()
    // The suggestion filled the draft: name landed and a date is set (Clear is only offered then).
    composeRule.waitUntil(10_000) {
      nodeCount(hasSetTextAction() and hasText("Whole Milk", substring = true)) > 0
    }
    composeRule.onNodeWithText("Clear expiry date").assertIsDisplayed()

    // Corrections stay editable before the explicit Save.
    composeRule.onNode(hasSetTextAction() and hasText("Whole Milk", substring = true))
      .performTextReplacement("Whole Milk 2%")
    composeRule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Whole Milk 2%")) > 0 }
    composeRule.onNodeWithText("Whole Milk 2%").assertIsDisplayed()

    assertEquals(1, fake.calls)
    assertTrue("Analyzer got ${fake.seenRefs}", fake.seenRefs.single().startsWith("staged/"))

    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Whole Milk 2%")) > 0 }
    val ref = readPhotoRef("Whole Milk 2%")
    assertTrue("Saved photo ref missing after relaunch: $ref", ref?.startsWith("saved/") == true)
    val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "fridge-photos/$ref")
    assertTrue("Saved photo file missing: ${file.path}", file.isFile && file.length() > 0L)
  }

  /**
   * An estimate stays labelled as one: Estimated provenance, the estimate-only caveat, and the
   * assumptions note are all visible, and the item saves.
   */
  @Test
  fun captureToSuggestion_estimatedStaysLabelled() {
    grantCamera()
    val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.estimated())
    capturePhotoFirstDraft()

    PhotoStoreHolder.analyzerForTests = fake
    composeRule.onNodeWithTag("analyzePhotoButton").performClick()
    composeRule.waitUntil(15_000) { nodeCount(hasText("Estimated expiry")) > 0 }
    composeRule.onNodeWithText("Estimated expiry").assertIsDisplayed()
    composeRule.onNodeWithText("An estimate only — check before use.").assertIsDisplayed()
    composeRule.onNodeWithText(PhotoAiTestFixtures.ESTIMATE_NOTE).assertIsDisplayed()
    composeRule.waitUntil(10_000) {
      nodeCount(hasSetTextAction() and hasText("Greek Yogurt", substring = true)) > 0
    }

    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Greek Yogurt")) > 0 }
    composeRule.onNodeWithText("Greek Yogurt").assertIsDisplayed()
    assertEquals(1, fake.calls)
  }

  /**
   * An ambiguous date (only a packing date visible) never becomes an expiry: no provenance tag,
   * no date set, and the name suggestion still saves.
   */
  @Test
  fun ambiguousDate_showsNoProvenanceAndStillSaves() {
    grantCamera()
    val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.ambiguous())
    capturePhotoFirstDraft()

    PhotoStoreHolder.analyzerForTests = fake
    composeRule.onNodeWithTag("analyzePhotoButton").performClick()
    composeRule.waitUntil(15_000) {
      nodeCount(hasSetTextAction() and hasText("Canned Beans", substring = true)) > 0
    }
    composeRule.waitForIdle()
    assertEquals(0, nodeCount(hasTestTag("expiryProvenance")))
    assertEquals(0, nodeCount(hasText("Clear expiry date")))

    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Canned Beans")) > 0 }
  }

  /**
   * A failed analysis (offline, server error) is recoverable: the draft keeps its content, the
   * error message shows, and Try again reuses the same staged photo.
   */
  @Test
  fun analysisFailure_retryKeepsDraftAndSucceeds() {
    grantCamera()
    val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.failure())
    capturePhotoFirstDraft()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Retry Pear")

    PhotoStoreHolder.analyzerForTests = fake
    composeRule.onNodeWithTag("analyzePhotoButton").performClick()
    composeRule.waitUntil(15_000) { nodeCount(hasTestTag("analysisError")) > 0 }
    composeRule.onNodeWithText("Couldn’t analyze the photo. You can keep typing.").assertIsDisplayed()
    // The typed draft survived the failure.
    composeRule.onNode(hasSetTextAction() and hasText("Retry Pear", substring = true)).assertIsDisplayed()

    fake.result = PhotoAiTestFixtures.detected()
    composeRule.onNodeWithTag("analyzePhotoButton").performClick()
    composeRule.waitUntil(15_000) { nodeCount(hasText("Detected expiry")) > 0 }
    assertEquals(2, fake.calls)
    assertEquals(fake.seenRefs[0], fake.seenRefs[1])
    // The retry fills the still-empty date, but the typed name is the user's: it stays.
    composeRule.onNode(hasSetTextAction() and hasText("Retry Pear", substring = true)).assertIsDisplayed()

    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Retry Pear")) > 0 }
  }

  /**
   * Missing-key fallback: the unavailable message shows with no retry (nothing a retry could
   * do), and typing plus saving still work with no key and no permissions.
   */
  @Test
  fun missingKey_showsUnavailableAndTypingStillSaves() {
    grantCamera()
    val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.unavailable())
    capturePhotoFirstDraft()

    PhotoStoreHolder.analyzerForTests = fake
    composeRule.onNodeWithTag("analyzePhotoButton").performClick()
    composeRule.waitUntil(15_000) { nodeCount(hasTestTag("analysisError")) > 0 }
    composeRule.onNodeWithText("AI analysis isn’t available. You can keep typing.").assertIsDisplayed()
    composeRule.onNodeWithTag("analyzePhotoButton").assertDoesNotExist()

    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Offline Fig")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Offline Fig")) > 0 }
  }

  /**
   * Denying the camera from the photo-first sheet returns to a typable draft; nothing is
   * staged and typing still saves.
   */
  @Test
  fun cameraDenialFromPhotoButton_returnsToTyping() {
    revokeCamera()
    composeRule.onNodeWithTag("addPhotoButton").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasTestTag("photoSourceCamera")) > 0 }
    composeRule.onNodeWithTag("photoSourceCamera").performClick()
    var deniedOnSystemDialog = false
    composeRule.waitUntil(20_000) {
      if (nodeCount(hasTestTag("photo_permission_continue")) > 0) {
        composeRule.onNodeWithTag("photo_permission_continue").performClick()
      }
      if (clickSystemLabel(listOf("Don't allow", "Don’t allow", "Deny"))) deniedOnSystemDialog = true
      deniedOnSystemDialog &&
        nodeCount(hasText("Add to the fridge")) > 0 &&
        nodeCount(hasTestTag("photo_shutter")) == 0
    }
    assertTrue("The system permission dialog was never denied", deniedOnSystemDialog)
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Denied Quince")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Denied Quince")) > 0 }
  }

  /**
   * Cancelling the system picker from the photo-first sheet keeps the draft; with no photo
   * there is no analysis section, and typing still saves.
   */
  @Test
  fun pickerCancel_keepsPhotoFirstDraftWithoutAnalysis() {
    composeRule.onNodeWithTag("addPhotoButton").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasTestTag("photoSourcePicker")) > 0 }
    composeRule.onNodeWithTag("photoSourcePicker").performClick()
    composeRule.waitForIdle()
    Thread.sleep(1_000)
    shell("input keyevent 4")
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Add to the fridge")) > 0 }
    composeRule.onNodeWithTag("analyzePhotoButton").assertDoesNotExist()
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Cancelled Plum")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Cancelled Plum")) > 0 }
  }

  /**
   * Picker photo → suggestions → Save, with the camera revoked to prove the picker needs no
   * permission. The system picker UI is outside the app, so the tap on the seeded photo is an
   * assumption: the test verifies the full flow when the picker surfaces the photo and skips
   * otherwise.
   */
  @Test
  fun pickerPhoto_flowsToSuggestionsAndSave() {
    revokeCamera()
    val images =
      listOf(
        seedGalleryImage("photoai-apple.jpg", "photo_ai/apple.jpg"),
        seedGalleryImage("photoai-milk.jpg", "photo_ai/milk_carton.jpg"),
      )
    try {
      composeRule.onNodeWithTag("addPhotoButton").performClick()
      composeRule.waitUntil(10_000) { nodeCount(hasTestTag("photoSourcePicker")) > 0 }
      composeRule.onNodeWithTag("photoSourcePicker").performClick()
      // waitUntil throws on timeout, so fall back to skipping the test when the system
      // picker never surfaces the seeded photo.
      val visible =
        try {
          composeRule.waitUntil(20_000) { hasSystemNodeContaining("photoai") }
          true
        } catch (_: Throwable) {
          false
        }
      val tapped = visible && tapSystemNodeContaining("photoai")
      if (!tapped) shell("input keyevent 4")
      assumeTrue("System picker did not surface the seeded photo", tapped)

      val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.detected())
      composeRule.waitUntil(15_000) { nodeCount(hasText("Remove photo")) > 0 }
      PhotoStoreHolder.analyzerForTests = fake
      composeRule.onNodeWithTag("analyzePhotoButton").performClick()
      composeRule.waitUntil(15_000) { nodeCount(hasText("Detected expiry")) > 0 }
      assertTrue("Analyzer got ${fake.seenRefs}", fake.seenRefs.single().startsWith("staged/"))

      composeRule.onNodeWithText("Save").performClick()
      composeRule.waitUntil(10_000) { nodeCount(hasText("Whole Milk")) > 0 }
    } finally {
      val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
      images.forEach { resolver.delete(it, null, null) }
    }
  }

  /**
   * A late result must not overwrite the user's own edit: while analysis is gated, the user
   * renames the draft, and the name stays theirs after the gate opens.
   */
  @Test
  fun staleResult_doesNotOverwriteUserEdit() {
    grantCamera()
    val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.detected())
    fake.gate = CompletableDeferred()
    capturePhotoFirstDraft()

    PhotoStoreHolder.analyzerForTests = fake
    composeRule.onNodeWithTag("analyzePhotoButton").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasTestTag("analysisProgress")) > 0 }
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("User Oats")
    fake.gate?.complete(Unit)
    composeRule.waitUntil(10_000) { nodeCount(hasTestTag("analysisProgress")) == 0 }
    composeRule.waitForIdle()
    composeRule.onNode(hasSetTextAction() and hasText("User Oats", substring = true)).assertIsDisplayed()

    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("User Oats")) > 0 }
  }

  /**
   * Recreating during analysis never silently re-uploads: right after recreation the analyzer
   * still saw exactly one call and the staged photo is kept. A fresh ViewModel rests at Idle
   * (Analyze offered again); a retained one finishes the single in-flight call once the gate
   * opens. Either way the flow ends on the detected suggestion with one user-visible result.
   */
  @Test
  fun recreationDuringAnalysis_restoresIdleWithoutReupload() {
    grantCamera()
    val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.detected())
    fake.gate = CompletableDeferred()
    capturePhotoFirstDraft()

    PhotoStoreHolder.analyzerForTests = fake
    composeRule.onNodeWithTag("analyzePhotoButton").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasTestTag("analysisProgress")) > 0 }

    composeRule.activity.runOnUiThread { composeRule.activity.recreate() }
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Remove photo")) > 0 }
    assertEquals(1, fake.calls)

    fake.gate?.complete(Unit)
    composeRule.waitForIdle()
    // Fresh ViewModel: Idle offers Analyze again, so one explicit tap finishes the flow.
    // Retained ViewModel: the single gated call lands on its own; skip the tap then.
    if (nodeCount(hasTestTag("analyzePhotoButton")) > 0 &&
      nodeCount(hasText("Detected expiry")) == 0
    ) {
      composeRule.onNodeWithTag("analyzePhotoButton").performClick()
    }
    composeRule.waitUntil(15_000) { nodeCount(hasText("Detected expiry")) > 0 }
    assertTrue("Analyzer re-uploaded: ${fake.seenRefs}", fake.calls <= 2)
    assertTrue("Analyzer got ${fake.seenRefs}", fake.seenRefs.all { it.startsWith("staged/") })

    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Whole Milk")) > 0 }
  }

  /**
   * Inset/keyboard clearance on the photo review draft. A photo-first draft raises no IME on
   * its own; with the keyboard up the focused field, Analyze, and Save stay above the IME
   * top, and with the keyboard down they stay above the navigation-bar inset.
   */
  @Test
  fun reviewKeyboardClearance_actionsStayAboveImeAndNavBar() {
    grantCamera()
    capturePhotoFirstDraft()

    // Photo-first review does not force the keyboard: nothing focused it.
    composeRule.waitForIdle()
    Thread.sleep(1_500)
    assertEquals(null, imeTop())

    // Keyboard down: actions clear the navigation bar (or the screen bottom under gesture nav).
    val navTop = navBarTop()?.toFloat() ?: screenHeight().toFloat()
    assertTrue(
      "Save below nav inset",
      composeRule.onNodeWithText("Save").fetchSemanticsNode().boundsInWindow.bottom <= navTop,
    )
    assertTrue(
      "Analyze below nav inset",
      composeRule.onNodeWithTag("analyzePhotoButton").fetchSemanticsNode().boundsInWindow.bottom <= navTop,
    )

    // Keyboard up: tapping Name raises it; field, Analyze, and Save stay above the IME top.
    composeRule.onNode(hasSetTextAction() and hasText("Name")).performClick()
    awaitIme()
    val ime = imeTop()
    assertTrue("IME did not open", ime != null && ime > 0)
    assertTrue(
      "Name field under IME",
      composeRule.onNode(hasSetTextAction() and hasText("Name"))
        .fetchSemanticsNode().boundsInWindow.bottom <= ime!!.toFloat(),
    )
    composeRule.onNodeWithTag("analyzePhotoButton").performScrollTo()
    assertTrue(
      "Analyze under IME",
      composeRule.onNodeWithTag("analyzePhotoButton")
        .fetchSemanticsNode().boundsInWindow.bottom <= ime.toFloat(),
    )
    composeRule.onNodeWithText("Save").performScrollTo()
    assertTrue(
      "Save under IME",
      composeRule.onNodeWithText("Save").fetchSemanticsNode().boundsInWindow.bottom <= ime.toFloat(),
    )
  }

  /**
   * Removing the photo mid-analysis drops the late result: the analysis section goes away
   * with the photo, and the draft saves by typing.
   */
  @Test
  fun removePhotoDuringAnalysis_dropsLateResult() {
    grantCamera()
    val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.detected())
    fake.gate = CompletableDeferred()
    capturePhotoFirstDraft()

    PhotoStoreHolder.analyzerForTests = fake
    composeRule.onNodeWithTag("analyzePhotoButton").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasTestTag("analysisProgress")) > 0 }
    composeRule.onNodeWithText("Remove photo").performClick()
    fake.gate?.complete(Unit)
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000) { nodeCount(hasTestTag("analysisProgress")) == 0 }
    // The photo is gone, so the whole analysis section (button, error, provenance) is gone.
    assertEquals(0, nodeCount(hasTestTag("analyzePhotoButton")))
    assertEquals(0, nodeCount(hasTestTag("expiryProvenance")))
    assertEquals(0, nodeCount(hasText("Remove photo")))

    composeRule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Removed Plum")
    composeRule.onNodeWithText("Save").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Removed Plum")) > 0 }
    assertEquals(null, readPhotoRef("Removed Plum"))
  }

  /**
   * Large text keeps the review usable: provenance, correction, and Save stay on screen and
   * the save completes.
   */
  @Test
  fun largeText_reviewKeepsActionsUsable() {
    val priorScale = shell("settings get system font_scale").trim()
    grantCamera()
    try {
      shell("settings put system font_scale 1.3")
      composeRule.waitForIdle()
      val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.detected())
      capturePhotoFirstDraft()

      PhotoStoreHolder.analyzerForTests = fake
      composeRule.onNodeWithTag("analyzePhotoButton").assertIsDisplayed().performClick()
      composeRule.waitUntil(15_000) { nodeCount(hasText("Detected expiry")) > 0 }
      composeRule.onNodeWithText("Detected expiry").assertIsDisplayed()
      composeRule.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().performClick()
      composeRule.waitUntil(10_000) { nodeCount(hasText("Whole Milk")) > 0 }
    } finally {
      shell("settings put system font_scale $priorScale")
      composeRule.waitForIdle()
    }
  }

  /**
   * Dark mode keeps the review content visible through analysis to Save.
   */
  @Test
  fun darkMode_reviewKeepsContentVisible() {
    grantCamera()
    // 0 = auto, 1 = no, 2 = yes; restore the prior mode afterwards.
    val priorNight = shell("settings get secure ui_night_mode").trim().ifEmpty { "0" }
    val restoreNight = mapOf("0" to "auto", "1" to "no", "2" to "yes")[priorNight] ?: "auto"
    try {
      shell("cmd uimode night yes")
      composeRule.waitForIdle()
      val fake = FakePhotoAnalyzer(PhotoAiTestFixtures.detected())
      capturePhotoFirstDraft()

      composeRule.onNodeWithText("Analysis sends this photo to cloud AI.", substring = true).assertIsDisplayed()
      PhotoStoreHolder.analyzerForTests = fake
      composeRule.onNodeWithTag("analyzePhotoButton").performClick()
      composeRule.waitUntil(15_000) { nodeCount(hasText("Detected expiry")) > 0 }
      composeRule.onNodeWithText("Detected expiry").assertIsDisplayed()
      composeRule.onNodeWithText("Clear expiry date").assertIsDisplayed()
      composeRule.onNodeWithText("Save").assertIsDisplayed().performClick()
      composeRule.waitUntil(10_000) { nodeCount(hasText("Whole Milk")) > 0 }
    } finally {
      shell("cmd uimode night $restoreNight")
      composeRule.waitForIdle()
    }
  }

  // --- Photo-first capture ---

  /** Add photo → Take photo → shutter → Use, ending on the review draft with a staged photo. */
  private fun capturePhotoFirstDraft() {
    composeRule.onNodeWithTag("addPhotoButton").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasTestTag("photoSourceCamera")) > 0 }
    composeRule.onNodeWithTag("photoSourceCamera").performClick()
    composeRule.waitUntil(25_000) { nodeCount(hasTestTag("photo_shutter") and androidx.compose.ui.test.isEnabled()) > 0 }
    composeRule.onNodeWithTag("photo_shutter").performClick()
    composeRule.waitUntil(20_000) {
      nodeCount(hasTestTag("photo_use") and androidx.compose.ui.test.isEnabled()) > 0
    }
    composeRule.onNodeWithTag("photo_use").performClick()
    composeRule.waitUntil(10_000) { nodeCount(hasText("Remove photo")) > 0 }
  }

  /** Copies a bundled asset into the shared gallery so the system picker can surface it. */
  private fun seedGalleryImage(displayName: String, assetPath: String): Uri {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val values =
      ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      }
    val uri =
      app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Could not insert $displayName into MediaStore")
    InstrumentationRegistry.getInstrumentation().context.assets.open(assetPath).use { input ->
      app.contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
        ?: error("Could not write $displayName to MediaStore")
    }
    return uri
  }

  // --- IME / nav-bar insets ---

  /** Top of the on-screen keyboard in screen pixels, or null when it is down. */
  private fun imeTop(): Int? {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    return automation.windows
      .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
      ?.let { window -> Rect().also(window::getBoundsInScreen).takeIf { it.height() > 0 }?.top }
  }

  private fun awaitIme() {
    composeRule.waitUntil(10_000) { imeTop() != null }
  }

  /**
   * Top of the bottom system bar (navigation bar or gesture handle), or null when no system
   * window sits at the screen bottom. The status bar is also TYPE_SYSTEM, so only a window
   * anchored to the bottom half counts.
   */
  private fun navBarTop(): Int? {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val displayHeight = screenHeight()
    return automation.windows
      .filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
      .map { window -> Rect().also(window::getBoundsInScreen) }
      .firstOrNull { it.height() > 0 && it.bottom >= displayHeight && it.top > displayHeight / 2 }
      ?.top
  }

  private fun screenHeight(): Int =
    InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.heightPixels

  // --- Permissions, system UI, node counts ---

  private fun grantCamera() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(
      instrumentation.targetContext.packageName,
      Manifest.permission.CAMERA,
    )
  }

  private fun revokeCamera() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.revokeRuntimePermission(
      instrumentation.targetContext.packageName,
      Manifest.permission.CAMERA,
    )
  }

  private fun nodeCount(matcher: androidx.compose.ui.test.SemanticsMatcher): Int =
    try {
      composeRule.onAllNodes(matcher).fetchSemanticsNodes().size
    } catch (_: IllegalStateException) {
      0
    }

  private fun clickSystemLabel(labels: List<String>): Boolean {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (window in automation.windows) {
      val root = window.root ?: continue
      val node = findSystemNode(root) { text, description ->
        labels.any { text.equals(it, ignoreCase = true) || description.equals(it, ignoreCase = true) }
      }
      if (node != null) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        return true
      }
    }
    return false
  }

  private fun hasSystemNodeContaining(substring: String): Boolean {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (window in automation.windows) {
      val root = window.root ?: continue
      val node = findSystemNode(root) { text, description ->
        text.contains(substring, ignoreCase = true) || description.contains(substring, ignoreCase = true)
      }
      if (node != null) return true
    }
    return false
  }

  private fun tapSystemNodeContaining(substring: String): Boolean {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (window in automation.windows) {
      val root = window.root ?: continue
      val node = findSystemNode(root) { text, description ->
        text.contains(substring, ignoreCase = true) || description.contains(substring, ignoreCase = true)
      }
      if (node != null) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
          shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
          return true
        }
      }
    }
    return false
  }

  private fun findSystemNode(
    node: AccessibilityNodeInfo,
    matches: (text: String, description: String) -> Boolean,
  ): AccessibilityNodeInfo? {
    if (matches(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())) return node
    for (index in 0 until node.childCount) {
      val child = node.getChild(index) ?: continue
      val found = findSystemNode(child, matches)
      if (found != null) return found
    }
    return null
  }
}
