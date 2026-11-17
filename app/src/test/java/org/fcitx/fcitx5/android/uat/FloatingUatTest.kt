/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Vibrator
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.floating.FloatingHandleSide
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.floating.FloatingStore
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyles
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.learning.UndoChip
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.Text
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/** UAT cases of the floating keyboard in uat/05: docking, the zone, the handle, its corners and notices. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class FloatingUatTest {

    private val h = KeyboardHarness()
    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        h.close()
        AppPrefs.getInstance().internal.let {
            it.floatingKeyboardX.setValue(it.floatingKeyboardX.defaultValue)
            it.floatingKeyboardY.setValue(it.floatingKeyboardY.defaultValue)
        }
    }

    // 悬浮 as the shape the test is in has it
    private val floating get() = KeyboardStyles.floating(h.service)
    private val screenW get() = h.service.resources.displayMetrics.widthPixels
    private val screenH get() = h.service.resources.displayMetrics.heightPixels
    private val dp get() = h.service.resources.displayMetrics.density

    private fun grip(): View = KeyboardHarness.find<FloatingHandle>(h.inputView)!!.also { check(h.shown(it)) }
    private fun keys() = h.box(h.board)

    // the strip of accent across the bottom while a drag is in the docking zone
    private fun dockMark(): View? = KeyboardHarness.all(h.inputView.rootView).firstOrNull {
        it.javaClass == View::class.java && it.background is GradientDrawable && it.isShown && it.alpha > 0f && it.width > screenW * 0.9
    }

    private fun floatingNow() = keys().width() < screenW * 0.9

    @Test
    @Uat("TB-24")
    fun theDockButtonPutsItBack() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        assertTrue(floatingNow())
        h.tap(h.described(h.service.getString(R.string.floating_keyboard_dock)))
        h.after(1000)
        h.relayout()
        assertFalse(floating)
        assertFalse("${keys()}", floatingNow())
    }

    // Robolectric does not lay the shown keyboard out again on a rotation (onConfigurationChanged
    // leaves its window as it was): the service made anew in the turned screen, its place read
    // from where it was left, as a rotation's new input view reads it
    @Test
    @Uat("TB-23")
    fun leftInACornerItIsThereNextTimeAndAfterARotation() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.drag(grip(), 0f, 0f)
        h.after(1000)
        val at = keys()
        fun KeyboardHarness.inTheCorner(what: String) {
            after(1000)
            relayout()
            val w = service.resources.displayMetrics.widthPixels
            val ht = service.resources.displayMetrics.heightPixels
            val now = box(board)
            assertTrue("$what floating: $now in $w", now.width() < w * 0.9)
            assertTrue("$what: $now in $w x $ht, left at $at", now.left < w * 0.1 && now.top < ht * 0.2)
        }
        h.inTheCorner("left")
        h.start()
        h.inTheCorner("shown again")
        assertTrue("same place: ${keys()} $at", abs(keys().left - at.left) <= 2 && abs(keys().top - at.top) <= 2)
        h.close()
        // 横屏 its own place (061-floating-resize §2.6, the user's ②): never placed there, the one place 0.6.0 kept, here
        // none, so the bottom; left at the top right there, the top left is 竖屏's still
        try {
            RuntimeEnvironment.setQualifiers("+land")
            KeyboardHarness().use { k ->
                k.start()
                k.after(1000)
                val w = k.service.resources.displayMetrics.widthPixels
                val ht = k.service.resources.displayMetrics.heightPixels
                val box = k.box(k.board)
                assertTrue("+land, never placed: at the bottom, $box in $w x $ht", box.left > w * 0.1 && box.bottom > ht * 0.7)
                k.drag(KeyboardHarness.find<FloatingHandle>(k.inputView)!!, w.toFloat(), 0f)
                k.after(1000)
                val now = k.box(k.board)
                assertTrue("+land, left at the top right: $now in $w x $ht", now.right > w * 0.9 && now.top < ht * 0.2)
            }
            RuntimeEnvironment.setQualifiers("+port")
            KeyboardHarness().use { k ->
                k.start()
                k.inTheCorner("+port")
            }
            RuntimeEnvironment.setQualifiers("+land")
            KeyboardHarness().use { k ->
                k.start()
                k.after(1000)
                val w = k.service.resources.displayMetrics.widthPixels
                val now = k.box(k.board)
                assertTrue("+land again, at its own top right: $now in $w", now.right > w * 0.9 && now.top < k.service.resources.displayMetrics.heightPixels * 0.2)
            }
        } finally {
            RuntimeEnvironment.setQualifiers("+port")
        }
    }

    // the floating keyboard is cut to its outline as the device draws it (a software draw does not
    // clip, so the outline is read rather than a pixel)
    private fun cornerRadius(k: KeyboardHarness): Float {
        val card = KeyboardHarness.all(k.inputView.rootView).first { it.outlineProvider?.javaClass?.name?.contains("FloatingCorners") == true }
        return Outline().also { card.outlineProvider.getOutline(card, it) }.radius
    }

    // the keyboard's background as its scene draws it behind the keys (`@floating keyboard`'s radius): its corners
    private fun drawnRadius(k: KeyboardHarness): Double {
        val scene = checkNotNull(KeyboardHarness.boardCanvas(k.inputView)?.scene)
        val back = scene.base.filterIsInstance<Op.Fill>().first()
        return ((back.shape as Shape.Rect).radii).max()
    }

    @Test
    @Uat("TB-32")
    fun squareKeysASquareFloatingKeyboard() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        assertTrue("rounded by default", cornerRadius(h) > 0f)
        // the background drawn behind the keys as round (R20 F6557)
        assertTrue("drawn rounded by default", drawnRadius(h) > 0.0)
        h.close()
        // a theme of square keys (the edit page's 直角)
        val light = TestThemes.PixelLight.look
        val square = KeyboardHarness(Theme.Custom("square", false, light.copy(keys = light.keys.copy(radiusDp = 0))))
        try {
            square.start()
            assertEquals(0f, cornerRadius(square), 0f)
            assertEquals(0.0, drawnRadius(square), 0.0)
        } finally {
            square.close()
        }
    }

    @Test
    @Uat("TB-34")
    fun theDockingZoneShownAndDroppedIn() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.drag(grip(), screenW / 2f, screenH / 2f)
        h.drag(grip(), screenW / 2f, screenH.toFloat(), lift = false)
        assertTrue("the mark at the bottom", dockMark() != null)
        assertTrue("above the bottom ${keys()}", h.box(grip()).bottom <= screenH)
        h.move(screenW / 2f, screenH - 150 * dp, lift = true)
        assertTrue(floating)
        // up left, then into the zone and let go there
        h.drag(grip(), 0f, 0f)
        val at = keys()
        h.drag(grip(), screenW / 2f, screenH.toFloat())
        h.after(1000)
        h.relayout()
        assertFalse(floating)
        assertFalse(floatingNow())
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.after(1000)
        h.relayout()
        assertTrue(floatingNow())
        assertTrue("back at $at: ${keys()}", abs(keys().left - at.left) <= 2 && abs(keys().top - at.top) <= 2)
    }

    @Test
    @Uat("TB-36")
    fun aSecondFingerInTheZoneKeepsItFloating() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.drag(grip(), 0f, 0f)
        h.drag(grip(), screenW / 2f, screenH.toFloat(), lift = false)
        assertTrue(dockMark() != null)
        h.secondFingerThenLift(screenW / 4f, screenH / 3f)
        h.after(1000)
        h.relayout()
        assertTrue(floating)
        assertTrue(floatingNow())
        assertTrue("left at the lowest, not back up left: ${keys()}", keys().top > screenH / 2)
    }

    @Test
    @Uat("TB-35")
    fun theHandleOnTop() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        prefs.set(ThemeManager.prefs.floatingHandle, FloatingHandleSide.Top)
        h.start()
        val g = h.box(grip())
        assertTrue("$g over ${keys()}", g.bottom <= keys().top + 2)
        val top = keys().top
        h.drag(grip(), g.exactCenterX(), g.exactCenterY() - 100 * dp)
        assertTrue("$top -> ${keys().top}", keys().top < top - 50 * dp)
    }

    @Test
    @Uat("TB-37")
    fun thePreviewsLettersAsLargeWhileFloating() {
        fun size(): Float = SettingsHarness().use { st ->
            st.open(SettingsRoute.Theme)
            KeyboardHarness.keysOf(st.activity.window.decorView).first { it.label == "q" }.textSize("q")!!
        }
        h.close()
        prefs.setStyle(KeyboardStyle.FLOATING)
        val on = size()
        prefs.setStyleDefault()
        assertEquals(on, size(), 0.01f)
    }

    private fun undoAtTheTop() {
        h.drag(grip(), screenW / 2f, 0f)
        var undone = false
        h.service.say("已屏蔽", UndoChip.Undo({ undone = true }))
        h.idle()
        val chip = h.noticeBox()
        val bottom = maxOf(keys().bottom, h.box(grip()).bottom)
        assertTrue("notice $chip under $bottom", chip.top >= bottom - 4 && chip.bottom <= screenH)
        val undo = KeyboardHarness.all(h.notice()!!.contentView).filterIsInstance<TextView>()
            .first { it.text.toString() == h.service.getString(R.string.undo) && it.alpha > 0.5f }
        generateSequence<View>(undo) { it.parent as? View }.first { it.hasOnClickListeners() }.performClick()
        h.idle()
        assertTrue("撤销 reached", undone)
    }

    @Test
    @Uat("TB-39")
    fun atTheTopTheUndoIsBelowAndTappable() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        undoAtTheTop()
        assertNotEquals(0, keys().height())
    }

    @Test
    @Uat("TB-39")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun landscapeAsTallAsTheScreenTheUndoStillShown() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.drag(grip(), screenW / 2f, 0f)
        var undone = false
        h.service.say("已屏蔽", UndoChip.Undo({ undone = true }))
        h.idle()
        val chip = h.noticeBox()
        assertTrue("$chip on the screen", chip.top >= 0 && chip.bottom <= screenH)
        val undo = KeyboardHarness.all(h.notice()!!.contentView).filterIsInstance<TextView>()
            .first { it.text.toString() == h.service.getString(R.string.undo) && it.alpha > 0.5f }
        generateSequence<View>(undo) { it.parent as? View }.first { it.hasOnClickListeners() }.performClick()
        h.idle()
        assertTrue(undone)
    }

    // ------------------------------------------------------------------ resized (061-floating-resize)

    private fun resizer(): FloatingHandle.MirroredIcon = checkNotNull(KeyboardHarness.find<FloatingHandle>(h.inputView)!!.resizeButton).also { check(h.shown(it)) }

    // the resize button dragged along the keyboard's diagonal, out (left and down, or left and [up] as it grows up) by
    // [by] of it, in for less than 0
    private fun KeyboardHarness.resize(by: Float, up: Boolean = false) {
        val k = box(keyboard)
        val b = box(checkNotNull(KeyboardHarness.find<FloatingHandle>(inputView)!!.resizeButton))
        drag(b, b.exactCenterX() - by * k.width(), b.exactCenterY() + (if (up) -by else by) * k.height())
        after(500)
        relayout()
    }

    // dragged up from the bottom, where it first floats: room under it to grow into, its top right staying
    private fun KeyboardHarness.upFromTheBottom() {
        val g = box(KeyboardHarness.find<FloatingHandle>(inputView)!!)
        drag(g, g.exactCenterX(), service.resources.displayMetrics.heightPixels * 0.55f)
        after(500)
        relayout()
    }

    private fun KeyboardHarness.widthDp() = box(board).width() / service.resources.displayMetrics.density

    @Test
    @Uat("TB-58")
    fun resizedAllOfItIsAsMuchBiggerOrSmallerTheBarsOnlyWider() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.upFromTheBottom()
        val at = h.box(h.keyboard)
        val board = keys()
        val q = h.key("q").bounds
        val bar = h.box(h.bar)
        val corner = cornerRadius(h)
        h.resize(0.1f)
        val now = h.box(h.keyboard)
        val k = keys().width().toFloat() / board.width()
        assertTrue("bigger: $board -> ${keys()}", k > 1.08f)
        assertEquals("its top right stays: $at -> $now", at.right.toFloat(), now.right.toFloat(), 2f)
        assertEquals("its top right stays: $at -> $now", at.top.toFloat(), now.top.toFloat(), 2f)
        // the keys, their gaps and the keyboard's corners all as much bigger: their shape kept
        assertEquals("keys as much taller", board.height() * k, keys().height().toFloat(), 3f)
        assertEquals("q as much wider", q.width() * k, h.key("q").bounds.width().toFloat(), 2f)
        assertEquals("q as much taller", q.height() * k, h.key("q").bounds.height().toFloat(), 2f)
        assertEquals("corners as much rounder", corner * k, cornerRadius(h), 2f)
        // the bar over them only wider (the user's Q6)
        assertEquals(bar.height(), h.box(h.bar).height())
        assertEquals(keys().width().toFloat(), h.box(h.bar).width().toFloat(), 2f)
        h.resize(-0.2f)
        assertTrue("smaller: $board -> ${keys()}", keys().width() < board.width() * 0.95f)
        assertEquals(bar.height(), h.box(h.bar).height())
        assertEquals("its top right stays", at.right.toFloat(), h.box(h.keyboard).right.toFloat(), 2f)
    }

    @Test
    @Uat("TB-58")
    fun atTheBottomWhereItFirstFloatsItGrowsUpItsBottomRightStaying() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        val at = h.box(h.keyboard)
        val first = h.widthDp()
        h.resize(0.1f, up = true)
        val now = h.box(h.keyboard)
        assertTrue("bigger: $first -> ${h.widthDp()}", h.widthDp() > first * 1.08f)
        assertEquals("its bottom right stays: $at -> $now", at.right.toFloat(), now.right.toFloat(), 2f)
        assertEquals("its bottom right stays: $at -> $now", at.bottom.toFloat(), now.bottom.toFloat(), 2f)
        assertTrue("up: $at -> $now", now.top < at.top - 10)
        // kept at the bottom: the next resize grows up again
        h.resize(-0.05f, up = true)
        assertEquals(at.bottom.toFloat(), h.box(h.keyboard).bottom.toFloat(), 2f)
    }

    @Test
    @Uat("TB-58")
    fun theStripOnTopAtTheTopItGrowsDownItsTopRightStaying() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        prefs.set(ThemeManager.prefs.floatingHandle, FloatingHandleSide.Top)
        h.start()
        val g = h.box(grip())
        h.drag(grip(), g.exactCenterX(), 0f)
        h.after(500)
        h.relayout()
        val at = h.box(h.keyboard)
        val first = h.widthDp()
        h.resize(0.1f)
        val now = h.box(h.keyboard)
        assertTrue("bigger: $first -> ${h.widthDp()}", h.widthDp() > first * 1.08f)
        assertEquals("its top right stays: $at -> $now", at.right.toFloat(), now.right.toFloat(), 2f)
        assertEquals("its top right stays: $at -> $now", at.top.toFloat(), now.top.toFloat(), 2f)
    }

    // the resize button's ⤢ drawn mirrored, as ⤡
    private fun mirrored() = resizer().mirrored

    // the resize button's arrow along the way it grows now, set as it is placed, dragged or resized (the user's call,
    // 2026-11-15): at the bottom, where it first floats, up and left (⤡: mirrored); up mid-screen down and left (⤢)
    @Test
    @Uat("TB-58")
    fun theResizeArrowPointsTheWayItGrows() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        assertTrue("at the bottom: ⤡", mirrored())
        h.upFromTheBottom()
        assertFalse("mid-screen: ⤢", mirrored())
        h.resize(0.05f)
        assertFalse("resized, still mid-screen: ⤢", mirrored())
        // as it is dragged: down to the lowest, the finger still down (lifted there it docks)
        val g = h.box(grip())
        h.drag(grip(), g.exactCenterX(), screenH.toFloat(), lift = false)
        assertTrue("dragged down to the bottom: ⤡", mirrored())
    }

    // the strip on top, the mirror of it: at the bottom up and left (⤡), at the top down and left (⤢)
    @Test
    @Uat("TB-58")
    fun theResizeArrowWithTheStripOnTop() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        prefs.set(ThemeManager.prefs.floatingHandle, FloatingHandleSide.Top)
        h.start()
        assertTrue("at the bottom: ⤡", mirrored())
        val g = h.box(grip())
        h.drag(grip(), g.exactCenterX(), 0f)
        h.after(500)
        h.relayout()
        assertFalse("at the top: ⤢", mirrored())
    }

    @Test
    @Uat("TB-61")
    fun itsSizeIsKeptShownAgainAndDockedAndFloatingAgain() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.upFromTheBottom()
        val first = h.widthDp()
        h.resize(-0.1f)
        val resized = h.widthDp()
        assertTrue("$first -> $resized", resized < first - 10)
        assertEquals(resized, FloatingStore.widthDp(landscape = false), 1f)
        val at = keys()
        h.close()
        KeyboardHarness().use { k ->
            k.start()
            k.after(500)
            assertEquals("shown again, as resized", resized, k.widthDp(), 1f)
            assertEquals("and where it was", at.left.toFloat(), k.box(k.board).left.toFloat(), 2f)
            // docked by its button and floated again by the menu's: its size is the user's still
            k.tap(k.described(k.service.getString(R.string.floating_keyboard_dock)))
            k.after(1000)
            k.relayout()
            assertFalse(KeyboardStyles.floating(k.service))
        }
        prefs.setStyle(KeyboardStyle.FLOATING)
        KeyboardHarness().use { k ->
            k.start()
            k.after(500)
            assertEquals("floating again, as resized", resized, k.widthDp(), 1f)
        }
    }

    // 竖屏 and 横屏 each their own size, as their own place (the user's ②)
    @Test
    @Uat("TB-60")
    fun uprightAndTurnedEachTheirOwnSize() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.upFromTheBottom()
        h.resize(-0.1f)
        val upright = h.widthDp()
        h.close()
        var turned = 0f
        RuntimeEnvironment.setQualifiers("+land")
        try {
            KeyboardHarness().use { k ->
                k.start()
                k.after(500)
                val screenDp = (k.service.resources.displayMetrics.widthPixels / k.service.resources.displayMetrics.density).roundToInt()
                assertEquals("never resized turned: its default", FloatingKeyboard.widthDp(screenDp).toFloat(), k.widthDp(), 1f)
                k.upFromTheBottom()
                k.resize(0.05f)
                turned = k.widthDp()
                assertTrue("turned, resized its own way: $turned", turned > FloatingKeyboard.widthDp(screenDp) + 5)
            }
            RuntimeEnvironment.setQualifiers("+port")
            KeyboardHarness().use { k ->
                k.start()
                k.after(500)
                assertEquals("upright, its own", upright, k.widthDp(), 1f)
            }
            RuntimeEnvironment.setQualifiers("+land")
            KeyboardHarness().use { k ->
                k.start()
                k.after(500)
                assertEquals("turned, its own", turned, k.widthDp(), 1f)
            }
        } finally {
            RuntimeEnvironment.setQualifiers("+port")
        }
    }

    @Test
    @Uat("TB-59")
    fun resizedNoSmallerThan260NorPastTheScreen() {
        val docked = KeyboardHarness().use { k ->
            k.start()
            k.box(k.board).width() / dp
        }
        // a buzz at either end: one long enough to be still going as the test looks
        prefs.set(AppPrefs.getInstance().keyboard.hapticOnKeyPress, InputFeedbackMode.Enabled)
        prefs.set(AppPrefs.getInstance().keyboard.buttonPressVibrationMilliseconds, BUZZ_MS)
        val vibrator = shadowOf(h.service.getSystemService(Vibrator::class.java))
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.upFromTheBottom()
        assertFalse(vibrator.isVibrating)
        h.resize(-1f)
        assertEquals("as narrow as it goes", FloatingKeyboard.MIN_RESIZE_DP.toFloat(), h.widthDp(), 1f)
        assertTrue("a buzz at the least", vibrator.isVibrating)
        h.after(BUZZ_MS * 2L)
        assertFalse(vibrator.isVibrating)
        // the strip's three still on it
        val strip = h.box(grip())
        assertTrue(h.box(resizer()).right <= strip.exactCenterX())
        // out as far as a finger goes: stopped at the screen's left, not pushed past it nor along
        h.resize(3f)
        val k = h.box(h.keyboard)
        assertTrue("on the screen: $k in $screenW x $screenH", k.left >= -1 && k.right <= screenW + 1 && k.bottom <= screenH + 1)
        assertTrue("never wider than docked: ${h.widthDp()} > $docked", h.widthDp() <= docked + 1)
        assertTrue("a buzz at the most", vibrator.isVibrating)
    }

    // the floating keyboard's bar in slots of one width (the user's call, 2026-11-15), at its width and at the narrowest
    // it is resized to: open, the toolbar's in the slots between, ⋯ last and in view; the ones with no slot first in
    // ⋯'s panel, in order, none scrolled, and tapped there as on the bar
    @Test
    @Uat("TB-22", "TB-68")
    fun theToolbarsOverflowIsInMoresPanelFloatingAndResizedToTheLeast() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        fun s(d: Int) = h.service.getString(d)
        fun has(d: Int) = KeyboardHarness.all(h.inputView).any { h.shown(it) && it.contentDescription?.toString() == s(d) }
        val toolbar = listOf(R.string.undo, R.string.redo, R.string.text_editing, R.string.clipboard).map(::s)
        fun overflowInMore(at: String): List<String> {
            if (!has(R.string.hide_toolbar)) h.tap(h.described(s(R.string.expand_toolbar)))
            h.relayout()
            val cells = ToolbarSpacingUatTest.barCells(h)
            ToolbarSpacingUatTest.assertOnSlots(cells)
            val more = cells.indexOfFirst { it.first == s(R.string.status_area) }
            assertTrue("$at: ⋯ in view: $cells", more > 0)
            val onBar = cells.subList(1, more).map { it.first }
            val overflow = toolbar - onBar.toSet()
            assertEquals("$at: the toolbar's in order, none scrolled: $cells", toolbar, onBar + overflow)
            h.tap(h.described(s(R.string.status_area)))
            h.waitFor("$at: the status area") { h.relayout(); has(R.string.open_input_method_settings) }
            assertEquals("$at: first in ⋯'s panel", overflow, ToolbarSpacingUatTest.panelEntries(h).take(overflow.size))
            h.tap(h.described(s(R.string.back_to_keyboard)))
            h.after(1000)
            h.relayout()
            return overflow
        }
        val at300 = overflowInMore("300 dp")
        assertTrue("squeezed: ${ToolbarSpacingUatTest.barCells(h)}", ToolbarSpacingUatTest.barCells(h)[0].second.width() < KawaiiBarComponent.buttonWidthDp(h.service) * dp)
        h.resize(-1f, up = true)
        assertEquals(FloatingKeyboard.MIN_RESIZE_DP.toFloat(), h.widthDp(), 1f)
        val at260 = overflowInMore("260 dp")
        assertTrue("fewer slots, more in ⋯: $at300 $at260", at260.size > at300.size && s(R.string.text_editing) in at260)
        // 文本编辑 from the panel: its panel opens over the keyboard, as from the bar
        ToolbarSpacingUatTest.openFromToolbar(h, R.string.text_editing)
        h.waitFor("the text editing panel") { h.relayout(); has(R.string.clipboard) }
    }

    // every one at the bar's right there at the floating keyboard's width and at the narrowest it is resized to,
    // narrower with the bar, none left out (TB-57); they and the menu key never move nor change width as the toolbar
    // opens and shuts (the user's call, 2026-11-15)
    @Test
    @Uat("TB-22", "TB-57")
    fun theBarsEndsAreAllThereAndStayFloatingAndResizedToTheLeast() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        ToolbarSpacingUatTest.assertTheEndsStay(h, "300 dp")
        h.resize(-1f, up = true)
        assertEquals(FloatingKeyboard.MIN_RESIZE_DP.toFloat(), h.widthDp(), 1f)
        ToolbarSpacingUatTest.assertTheEndsStay(h, "260 dp")
        val cells = ToolbarSpacingUatTest.barCells(h)
        assertEquals("the menu key and the right's four: $cells", 5, cells.size)
        assertTrue("a finger's least: $cells", cells.all { it.second.width() >= 28 * dp - 1 })
    }

    // a symbol's or an emoji's cell in a panel the size its grid gives it, the keyboard resized or not (only the keys
    // are, 061-floating-resize §2.2)
    @Test
    fun aPanelsCellIsItsOwnSizeResizedOrNot() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        fun cell(): Bitmap {
            val c = SceneKeyCell(h.service, ThemeManager.builtin("pixel#light"), ownSize = true).apply {
                attrs = KeyAttrs(label = Text.Literal("a"))
                layout(0, 0, 160, 160)
            }
            return Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also { c.draw(Canvas(it)) }
        }
        val before = cell()
        h.resize(0.2f, up = true)
        assertTrue(h.widthDp() > 340f)
        assertTrue(before.sameAs(cell()))
    }

    // pressed while it still grows from the docked keyboard (scaled): let be, its bounds not yet its own
    @Test
    fun aResizeBegunWhileItStillGrowsFromTheDockedOneIsLetBe() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        val first = h.widthDp()
        h.keyboard.scaleX = 0.9f
        h.keyboard.scaleY = 0.9f
        h.resize(0.1f, up = true)
        assertEquals(first, h.widthDp(), 0.5f)
        h.keyboard.scaleX = 1f
        h.keyboard.scaleY = 1f
        h.resize(0.1f, up = true)
        assertTrue(h.widthDp() > first * 1.08f)
    }

    @Test
    @Uat("TB-63")
    fun talkBacksBiggerAndSmallerATenthEachAndSaidAtTheLimit() {
        val am = h.service.getSystemService(AccessibilityManager::class.java)
        shadowOf(am).setEnabled(true)
        prefs.setStyle(KeyboardStyle.FLOATING)
        // where it first floats, at the bottom: bigger all the same (it grows up there)
        h.start()
        val button = resizer()
        assertEquals(h.service.getString(R.string.floating_keyboard_resize), button.contentDescription)
        fun act(label: Int): Boolean {
            val node = AccessibilityNodeInfoCompat.wrap(button.createAccessibilityNodeInfo())
            val action = node.actionList.single { it.label == h.service.getString(label) }
            return button.performAccessibilityAction(action.id, null).also { h.idle(); h.relayout() }
        }
        val first = h.widthDp()
        val bottom = h.box(h.keyboard).bottom
        fun said() = shadowOf(am).sentAccessibilityEvents
            .filter { it.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT }.map { it.text.joinToString() }
        assertTrue(act(R.string.floating_keyboard_bigger))
        assertEquals("a tenth bigger from the bottom", first * 1.1f, h.widthDp(), 1.5f)
        assertEquals("still at the bottom", bottom, h.box(h.keyboard).bottom)
        assertTrue("not at the most: nothing said ${said()}", said().isEmpty())
        assertEquals("kept", h.widthDp(), FloatingStore.widthDp(landscape = false), 1f)
        assertTrue(act(R.string.floating_keyboard_smaller))
        assertTrue(act(R.string.floating_keyboard_smaller))
        assertEquals(first * 1.1f * 0.9f * 0.9f, h.widthDp(), 2f)
        repeat(20) { act(R.string.floating_keyboard_smaller) }
        assertEquals(FloatingKeyboard.MIN_RESIZE_DP.toFloat(), h.widthDp(), 1f)
        assertFalse("at the least already", act(R.string.floating_keyboard_smaller))
        assertTrue("${said()}", h.service.getString(R.string.floating_keyboard_smallest) in said())
        assertFalse("${said()}", h.service.getString(R.string.floating_keyboard_biggest) in said())
        // bigger till it stops: 已是最大 said only there
        repeat(20) { act(R.string.floating_keyboard_bigger) }
        assertFalse("at the most already", act(R.string.floating_keyboard_bigger))
        assertTrue("${said()}", h.service.getString(R.string.floating_keyboard_biggest) in said())
        // as wide as goes from its bottom right: to the screen's left
        assertTrue("as wide as goes: ${h.box(h.keyboard)}", h.box(h.keyboard).left <= 2 && h.widthDp() > first * 1.1f)
    }

    // resized while typing: the keys made again at the size, the input view not, nothing typed lost (§2.5)
    @Test
    @Uat("TB-62")
    fun resizedWhileTypingWhatIsTypedStays() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.upFromTheBottom()
        h.api.candidates = arrayOf(CandidateWord("", "你", ""), CandidateWord("", "呢", ""))
        h.emit(HostEvent.Candidates(2, h.api.candidates.toList()))
        h.idle()
        val view = h.inputView
        val words = h.texts(h.bar).take(2)
        assertEquals(listOf("你", "呢"), words)
        h.resize(-0.1f)
        assertTrue("the same input view", view === h.inputView)
        assertEquals(words, h.texts(h.bar).take(2))
        h.resize(0.1f)
        assertTrue("the same input view", view === h.inputView)
        assertEquals(words, h.texts(h.bar).take(2))
    }

    private companion object {
        // a buzz long enough to be still going as a test looks
        const val BUZZ_MS = 5000
    }
}
