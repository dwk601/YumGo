package com.dwk.yumgo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dwk.yumgo.data.SettingsServices
import com.dwk.yumgo.data.ThemeMode
import java.io.FileInputStream
import java.util.Locale
import kotlin.math.pow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The navigation bar as a person sees it: pixels, not the flags the app asks for.
 *
 * The band the app paints under the platform's buttons lives in
 * [com.dwk.yumgo.ui.common.NavigationBarBand], which every window that can end up behind the bar
 * has to compose: the activity's, and the add sheet's, which is a window of its own. The screens
 * have the other half of the bargain, keeping their controls clear of a bar that sits on a side
 * when the phone is turned, which is what the last test here is about.
 *
 * With the gesture handle the platform draws a pill and takes its colour from this window. With the
 * buttons it draws those itself, and on API 35 it takes their colour from the *device* whatever the
 * app asks for, so an app that picked the other palette got pale buttons on its own light paper at
 * 1.2:1, and dark ones on its own dark paper at 2.3:1. These tests read a real frame and measure
 * the buttons against the bar under them, both ways round, and check that a palette matching the
 * device keeps the app's own paper so that no seam shows.
 *
 * The navigation mode, the device's night setting and its rotation belong to the device, and all
 * three are put back the way they were found.
 */@RunWith(AndroidJUnit4::class)
class NavBarWorkflowTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  /** The navigation the device had, restored afterwards. */
  private var previousOverlay: String = ""

  /** Whether the device was turning itself, restored afterwards. */
  private var autoRotation: String = ""

  @Before
  fun showTheNavigationButtons() {
    assertTrue(
      "This device has no three-button navigation to check",
      navBarShell("cmd overlay list android").contains(THREE_BUTTON),
    )
    previousOverlay = enabledNavOverlay()
    autoRotation = navBarShell("settings get system accelerometer_rotation").trim()
    enableOverlay(THREE_BUTTON)
    // The buttons are what these tests measure, and the first palette is the app's own default.
    runBlocking { SettingsServices.preferences(appContext()).setThemeMode(ThemeMode.Light) }
    composeRule.waitForIdle()
  }

  @After
  fun putTheDeviceBack() {
    runBlocking { SettingsServices.preferences(appContext()).setThemeMode(ThemeMode.Light) }
    setDeviceNightMode(null)
    enableOverlay(previousOverlay.ifEmpty { GESTURE })
    navBarShell("settings put system user_rotation 0")
    navBarShell("settings put system accelerometer_rotation ${autoRotation.ifEmpty { "1" }}")
  }

  /**
   * The two palettes that disagree with the device, with the buttons on screen. Each of them used
   * to draw the device's own button colour onto the app's own paper.
   */
  @Test
  fun navigationButtons_stayReadableOnEitherPalette() {
    // A light app on a dark device: the device draws pale buttons, so the bar has to be dark.
    setDeviceNightMode(true)
    assertAppIsLight("a light app on a dark device")
    assertButtonsAreReadable("a light app on a dark device")

    // A dark app on a light device: the device draws dark buttons, so the bar has to be light.
    chooseTheDarkPalette()
    setDeviceNightMode(false)
    assertAppIsDark("a dark app on a light device")
    assertButtonsAreReadable("a dark app on a light device")
  }

  /**
   * A palette that matches the device needs no bar of its own: the paper the app paints already
   * carries the buttons the device draws on it, and anything else would show as a seam.
   */
  @Test
  fun navigationButtons_leaveTheAppPaperAlone_whenThePaletteMatchesTheDevice() {
    setDeviceNightMode(false)
    assertAppIsLight("a light app on a light device")
    assertBarIsTheAppPaper("a light app on a light device")

    chooseTheDarkPalette()
    setDeviceNightMode(true)
    assertAppIsDark("a dark app on a dark device")
    assertBarIsTheAppPaper("a dark app on a dark device")
  }

  /**
   * The device changing its navigation under a running app. The bar is drawn from the inset the
   * window reports, so the app follows without a restart.
   */
  @Test
  fun switchingToTheButtons_paintsTheBarForThem() {
    setDeviceNightMode(true)
    enableOverlay(GESTURE)
    assertAppIsLight("a light app on a dark device")
    assertBarIsTheAppPaper("a light app on a dark device with the gesture handle")

    enableOverlay(THREE_BUTTON)
    assertButtonsAreReadable("a light app on a dark device after the switch")
  }

  /**
   * Turning the phone moves the bar from the bottom to a side, in the middle of a running app. The
   * bar follows it, and the buttons stay where a person can see them.
   */
  @Test
  fun navigationButtons_stayReadableWithTheBarOnASide() {
    setDeviceNightMode(true)
    rotateTo(1)
    assertAppIsLight("a light app on a dark device, turned sideways")
    assertButtonsAreReadable("a light app on a dark device, turned sideways")
  }

  /**
   * The add sheet is a window of its own and draws over the activity's, so the band has to be in
   * that window too. Adding an item is the main flow, and the keyboard is not always up: a person
   * puts it away to look at what they typed, and the bar is then the app's last word.
   */
  @Test
  fun navigationButtons_stayReadableWithTheAddSheetOpen() {
    setDeviceNightMode(true)
    openTheAddSheetWithoutTheKeyboard()
    assertButtonsAreReadable("the add sheet, a light app on a dark device")
  }

  /**
   * The bar is painted over the window, so anything the screens leave under a bar on a side is
   * under the band. The way into settings has to stay visible and tappable either way round, or the
   * only way to change the palette disappears in landscape.
   */
  @Test
  fun settingsStaysReachableWithTheBarOnEitherSide() {
    setDeviceNightMode(true)
    rotateTo(1)
    assertSettingsIsReachable("with the bar on the right")

    rotateTo(3)
    assertSettingsIsReachable("with the bar on the left")
  }

  /** The sheet as a person leaves it: opened, with the keyboard put away again. */
  private fun openTheAddSheetWithoutTheKeyboard() {
    eventually("The add sheet never opened") {
      runCatching { composeRule.onNodeWithContentDescription("Add").performSemanticsAction(SemanticsActions.OnClick) }
      if (composeRule.onAllNodesWithText("Add to the fridge").fetchSemanticsNodes().isEmpty()) {
        "The add sheet never opened"
      } else {
        null
      }
    }
    // The first Back puts the keyboard away and leaves the sheet up, the second closes the sheet.
    navBarShell("input keyevent 4")
    eventually("The keyboard never went away") {
      if (isKeyboardUp()) "The keyboard is still over the sheet" else null
    }
    assertTrue(
      "Putting the keyboard away closed the sheet",
      composeRule.onAllNodesWithText("Add to the fridge").fetchSemanticsNodes().isNotEmpty(),
    )
  }

  private fun isKeyboardUp(): Boolean {
    var up = false
    onMainThread {
      val insets = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
      up = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
    return up
  }

  /**
   * The settings button is the only way into the palette. It has to be clear of the band, and a
   * tap on it has to open the settings, which is the whole promise of the button.
   */
  private fun assertSettingsIsReachable(what: String) {
    val settings = composeRule.onNodeWithContentDescription("Settings")
    eventually("$what: the Settings button is not on the screen") {
      val bounds =
        runCatching { settings.fetchSemanticsNode().boundsInRoot }
          .getOrNull()
          ?: return@eventually "$what: there is no Settings button on the screen"
      val bar = barInRoot()
      if (bar.overlaps(bounds)) {
        "$what: the band covers the Settings button, which sits at $bounds under a bar at $bar"
      } else {
        null
      }
    }
    settings.performClick()
    eventually("$what: tapping Settings never opened the settings") {
      if (composeRule.onAllNodesWithText("Premade items").fetchSemanticsNodes().isEmpty()) {
        "$what: tapping Settings did not open the settings"
      } else {
        null
      }
    }
    // A palette row has to be reachable too, or the palette is behind the band as well.
    eventually("$what: the theme rows are under the band") {
      val bounds =
        runCatching { composeRule.onNode(hasClickAction() and hasText("Dark", substring = true)).fetchSemanticsNode().boundsInRoot }
          .getOrNull()
          ?: return@eventually "$what: there are no theme rows on the settings screen"
      val bar = barInRoot()
      if (bar.overlaps(bounds)) "$what: the band covers the theme rows, at $bounds under a bar at $bar" else null
    }
    composeRule.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick)
    eventually("$what: the fridge never came back") {
      if (composeRule.onAllNodesWithText("Fridge").fetchSemanticsNodes().isEmpty()) {
        "$what: the fridge never came back"
      } else {
        null
      }
    }
  }

  /**
   * Where the band is drawn, in the coordinates the semantics tree reports. The window covers the
   * whole screen, so the root's size is the screen's and the insets place the band in both.
   */
  private fun barInRoot(): Rect {
    val root = composeRule.onRoot().fetchSemanticsNode().size
    val bar = navigationBar()
    val width = root.width.toFloat()
    val height = root.height.toFloat()
    return when {
      bar.bottom >= bar.right && bar.bottom >= bar.left -> Rect(0f, height - bar.bottom, width, height)
      bar.right >= bar.left -> Rect(width - bar.right, 0f, width, height)
      else -> Rect(0f, 0f, bar.left.toFloat(), height)
    }
  }

  // ------------------------------------------------------------- the pixels --

  /**
   * The buttons the device drew have to stand out from the bar under them. Three to one is the
   * least a non-text control may reach, and the platform's own button colour is what caps it, so
   * the numbers that were measured go into the message.
   */
  private fun assertButtonsAreReadable(what: String) {
    eventually("$what: the navigation buttons are not readable") {
      val bar = readBar()
      val buttons = bar.buttons
      when {
        !bar.isOneColour ->
          "$what: the navigation bar is not painted in one colour, so there is nothing for the " +
            "buttons to sit on: ${hex(bar.background)} covers only ${bar.oneColourShare}% of it"
        buttons == null -> "$what: there are no buttons in the navigation bar"
        else -> {
          val ratio = contrast(buttons, bar.background)
          if (ratio < MINIMUM_CONTRAST) {
            "$what: the buttons are ${hex(buttons)} on a ${hex(bar.background)} bar at " +
              "%.2f:1, short of the %.1f:1 a control has to reach".format(ratio, MINIMUM_CONTRAST)
          } else {
            null
          }
        }
      }
    }
  }

  /**
   * No bar of the app's own making: what the app paints under the buttons has to be the paper it
   * paints everywhere else, or a list scrolling to the bottom would stop at a colour change.
   */
  private fun assertBarIsTheAppPaper(what: String) {
    eventually("$what: the navigation bar is not the app's own paper") {
      val bar = readBar()
      val paper = appPaper()
      val distance = colourDistance(bar.background, paper)
      if (!bar.isOneColour || distance > PAPER_TOLERANCE) {
        "$what: the bar is ${hex(bar.background)} against the app's ${hex(paper)}, $distance " +
          "apart, so the bar shows as a seam"
      } else {
        null
      }
    }
  }

  /** The bar from one frame: the colour it is painted, and the buttons standing on it. */
  private fun readBar(): Bar {
    val frame = frame()
    val pixels = barPixels(frame, navigationBar())
    val background = dominant(pixels)
    val painted = pixels.count { colourDistance(it, background) <= COLOUR_STEP }
    val rest = pixels.filter { colourDistance(it, background) > COLOUR_STEP }
    // The platform paints all three buttons the same colour, and the app paints nothing there.
    val buttons = if (rest.size < pixels.size / 200) null else dominant(rest)
    return Bar(background = background, buttons = buttons, oneColourShare = 100 * painted / pixels.size)
  }

  /**
   * The bar as it sits on the screen: along the bottom with the phone upright, down one side with
   * it turned. Which one it is comes from the insets the window reports, not from the shape of the
   * frame.
   */
  private fun barPixels(frame: Bitmap, bar: Insets): List<Int> =
    when {
      bar.bottom >= bar.right && bar.bottom >= bar.left ->
        band(frame, frame.height - bar.bottom + EDGE, frame.height - EDGE, 0, frame.width)
      bar.right >= bar.left ->
        band(frame, 0, frame.height, frame.width - bar.right + EDGE, frame.width - EDGE)
      else -> band(frame, 0, frame.height, EDGE, bar.left - EDGE)
    }

  /** The paper the app paints, read in a strip just inside the bar, above the button on the right. */
  private fun appPaper(): Int {
    val frame = frame()
    val bar = navigationBar()
    val paper =
      when {
        bar.bottom >= bar.right && bar.bottom >= bar.left ->
          band(frame, frame.height - bar.bottom - PAPER_BAND, frame.height - bar.bottom - EDGE, frame.width / 20, frame.width * 3 / 5)
        bar.right >= bar.left -> band(frame, frame.height / 6, frame.height * 5 / 6, bar.right + EDGE, bar.right + PAPER_BAND)
        else -> band(frame, frame.height / 6, frame.height * 5 / 6, maxOf(0, bar.left - PAPER_BAND), bar.left - EDGE)
      }
    return dominant(paper)
  }

  /** The bar the window reserves, in pixels: where the platform draws the buttons. */
  private fun navigationBar(): Insets {
    var insets: Insets? = null
    onMainThread {
      val reported = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
      insets = reported?.getInsets(WindowInsetsCompat.Type.navigationBars())
    }
    val bar = insets ?: error("The window reported no navigation bar at all")
    assertTrue("The window reported no navigation bar at all", maxOf(bar.bottom, bar.right, bar.left) > 0)
    return bar
  }

  private fun rotateTo(userRotation: Int) {
    navBarShell("settings put system accelerometer_rotation 0")
    navBarShell("settings put system user_rotation $userRotation")
    eventually("The phone never turned to $userRotation") {
      val turned = isTurnedTo(userRotation)
      val bar = navigationBar()
      val onTheRight = bar.right > 0 && bar.right >= bar.left
      val onTheLeft = bar.left > 0 && bar.left > bar.right
      when (userRotation) {
        0 -> if (turned) null else "the phone is not upright"
        1 -> if (turned && onTheRight) null else "the bar is not on the right, the insets are $bar"
        else -> if (turned && onTheLeft) null else "the bar is not on the left, the insets are $bar"
      }
    }
  }

  private fun isTurnedTo(userRotation: Int): Boolean {
    var width = 0
    var height = 0
    onMainThread {
      val view = composeRule.activity.window.decorView
      width = view.width
      height = view.height
    }
    return if (userRotation % 2 == 0) height >= width else width > height
  }

  /** The colour a person would call the app light or dark, from the middle of a real frame. */
  private fun appIsDark(): Boolean {
    val frame = frame()
    var total = 0.0
    var samples = 0
    var y = (frame.height * 0.15f).toInt()
    while (y < frame.height * 0.6f) {
      var x = (frame.width * 0.05f).toInt()
      while (x < frame.width * 0.95f) {
        val pixel = frame.getPixel(x, y)
        total +=
          0.2126 * ((pixel shr 16) and 0xFF) + 0.7152 * ((pixel shr 8) and 0xFF) + 0.0722 * (pixel and 0xFF)
        samples++
        x += 24
      }
      y += 24
    }
    return (total / samples) / 255.0 < 0.5
  }

  private fun assertAppIsLight(what: String) {
    eventually("$what: the app followed the dark device instead of its own light palette") {
      if (appIsDark()) "$what: the app painted itself dark" else null
    }
  }

  private fun assertAppIsDark(what: String) {
    eventually("$what: the app did not go dark") {
      if (appIsDark()) null else "$what: the app is still light"
    }
  }

  // ------------------------------------------------------------- the device --

  private fun setDeviceNightMode(night: Boolean?) {
    navBarShell("cmd uimode night ${if (night == null) "auto" else if (night) "yes" else "no"}")
    // The configuration change takes a moment to reach the app.
    repeat(20) {
      Thread.sleep(250)
      composeRule.waitForIdle()
    }
  }

  private fun enabledNavOverlay(): String =
    navBarShell("cmd overlay list android")
      .lines()
      .firstOrNull { it.trimStart().startsWith("[x]") && "navbar" in it }
      ?.substringAfter("[x]")
      ?.trim()
      ?: ""

  private fun enableOverlay(overlay: String) {
    navBarShell("cmd overlay enable-exclusive $overlay")
    Thread.sleep(2_000)
    composeRule.waitForIdle()
  }

  /**
   * Picks Dark in the settings, the way a person does, and comes back to the fridge. The taps are
   * retried, because the screen can still be settling after a change to the device's night mode.
   */
  private fun chooseTheDarkPalette() {
    eventually("The settings screen never opened") {
      runCatching {
        composeRule.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.OnClick)
      }
      if (composeRule.onAllNodesWithText("Premade items").fetchSemanticsNodes().isEmpty()) {
        "The settings screen never opened"
      } else {
        null
      }
    }
    eventually("The Dark palette was never chosen") {
      runCatching {
        composeRule
          .onNode(hasClickAction() and hasText("Dark", substring = true))
          .performSemanticsAction(SemanticsActions.OnClick)
      }
      if (appIsDark()) null else "The Dark palette was never chosen"
    }
    eventually("The fridge never came back") {
      runCatching { composeRule.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick) }
      if (composeRule.onAllNodesWithText("Fridge").fetchSemanticsNodes().isNotEmpty()) null
      else "The fridge never came back"
    }
  }

  // ------------------------------------------------------------- plumbing --

  private fun appContext() = InstrumentationRegistry.getInstrumentation().targetContext

  private fun onMainThread(block: () -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
  }

  private fun frame(): Bitmap {
    val bytes = navBarShellBytes("screencap -p")
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Could not read the screen")
  }

  private fun band(bitmap: Bitmap, top: Int, bottom: Int, fromX: Int, toX: Int): List<Int> {
    val pixels = ArrayList<Int>((bottom - top) * (toX - fromX))
    for (y in maxOf(0, top) until minOf(bitmap.height, bottom)) {
      for (x in maxOf(0, fromX) until minOf(bitmap.width, toX)) {
        pixels.add(bitmap.getPixel(x, y))
      }
    }
    return pixels
  }

  /** The colour a person sees across a band of the screen: the one that covers the most of it. */
  private fun dominant(pixels: List<Int>): Int =
    pixels
      .groupingBy { it and 0xFFFFFF }
      .eachCount()
      .maxByOrNull { it.value }
      ?.key
      ?: error("could not read the screen")

  /** How far apart two colours are, summed over the three channels. */
  private fun colourDistance(first: Int, second: Int): Int {
    fun channel(shift: Int) = Math.abs(((first shr shift) and 0xFF) - ((second shr shift) and 0xFF))
    return channel(16) + channel(8) + channel(0)
  }

  /** The WCAG contrast between two colours: 1:1 is invisible, 21:1 is the widest. */
  private fun contrast(first: Int, second: Int): Double {
    val high = maxOf(luminance(first), luminance(second))
    val low = minOf(luminance(first), luminance(second))
    return (high + 0.05) / (low + 0.05)
  }

  /**
   * The relative luminance WCAG 2.2 defines: each channel linearised first, then weighted. The
   * plain weighted average of the raw bytes reads a little high on dark colours, which would make
   * dark buttons on a light bar look worse than they are.
   */
  private fun luminance(colour: Int): Double {
    fun channel(shift: Int): Double {
      val raw = ((colour shr shift) and 0xFF) / 255.0
      return if (raw <= 0.03928) raw / 12.92 else ((raw + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
  }

  private fun hex(colour: Int) = "#%06X".format(Locale.US, colour and 0xFFFFFF)

  private fun navBarShell(command: String): String = navBarShellBytes(command).toString(Charsets.UTF_8)

  private fun navBarShellBytes(command: String): ByteArray {
    val process = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return FileInputStream(process.fileDescriptor).use { it.readBytes() }.also { process.close() }
  }

  /**
   * Everything that moves here is the app's own doing: a palette animating, a bar following a
   * device setting, a frame landing a moment later. Runs [check] until it stops complaining, then
   * reports what it last said.
   */
  private fun eventually(complaint: String, timeoutMillis: Long = 20_000, check: () -> String?) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    var last: String? = complaint
    while (System.currentTimeMillis() < deadline) {
      last = runCatching { check() }.getOrElse { it.message ?: complaint }
      if (last == null) return
      Thread.sleep(500)
      composeRule.waitForIdle()
    }
    throw AssertionError(last)
  }

  /** One frame's worth of the navigation bar. */
  private class Bar(
    val background: Int,
    val buttons: Int?,
    val oneColourShare: Int,
  ) {
    val isOneColour: Boolean
      get() = oneColourShare >= 50
  }

  private companion object {
    /** The navigation the device offers, switched through its own system overlay. */
    const val THREE_BUTTON = "com.android.internal.systemui.navbar.threebutton"
    const val GESTURE = "com.android.internal.systemui.navbar.gestural"

    /** The least contrast a control has to reach, from WCAG 2.2's non-text guidance. */
    const val MINIMUM_CONTRAST = 3.0

    /** Rows skipped at each edge of the bar, so an antialiased border is not read as a button. */
    const val EDGE = 8

    /** How far a pixel may sit from the bar's own colour and still be part of it. */
    const val COLOUR_STEP = 60

    /** Two colours this far apart are the same colour, as far as a seam is concerned. */
    const val PAPER_TOLERANCE = 6

    /** The strip read for the app's paper, above the bar and clear of the button on the right. */
    const val PAPER_BAND = 48
  }
}
