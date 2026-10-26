/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.floating.FloatingHandleSide
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.learning.UndoChip
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

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

    private val floating get() = ThemeManager.prefs.floatingKeyboard
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
        prefs.set(floating, true)
        h.start()
        assertTrue(floatingNow())
        h.tap(h.described(h.service.getString(R.string.floating_keyboard_dock)))
        h.after(1000)
        h.relayout()
        assertFalse(floating.getValue())
        assertFalse("${keys()}", floatingNow())
    }

    // the floating keyboard is cut to its outline as the device draws it (a software draw does not
    // clip, so the outline is read rather than a pixel)
    private fun cornerRadius(k: KeyboardHarness): Float {
        val card = KeyboardHarness.all(k.inputView.rootView).first { it.outlineProvider?.javaClass?.name?.contains("FloatingCorners") == true }
        return android.graphics.Outline().also { card.outlineProvider.getOutline(card, it) }.radius
    }

    @Test
    @Uat("TB-32")
    fun squareKeysASquareFloatingKeyboard() {
        prefs.set(floating, true)
        h.start()
        assertTrue("rounded by default", cornerRadius(h) > 0f)
        h.close()
        val square = KeyboardHarness()
        try {
            prefs.set(ThemeManager.prefs.keyRadius, 0)
            square.start()
            assertEquals(0f, cornerRadius(square), 0f)
        } finally {
            square.close()
        }
    }

    @Test
    @Uat("TB-34")
    fun theDockingZoneShownAndDroppedIn() {
        prefs.set(floating, true)
        h.start()
        h.drag(grip(), screenW / 2f, screenH / 2f)
        h.drag(grip(), screenW / 2f, screenH.toFloat(), lift = false)
        assertTrue("the mark at the bottom", dockMark() != null)
        assertTrue("above the bottom ${keys()}", h.box(grip()).bottom <= screenH)
        h.move(screenW / 2f, screenH - 150 * dp, lift = true)
        assertTrue(floating.getValue())
        // up left, then into the zone and let go there
        h.drag(grip(), 0f, 0f)
        val at = keys()
        h.drag(grip(), screenW / 2f, screenH.toFloat())
        h.after(1000)
        h.relayout()
        assertFalse(floating.getValue())
        assertFalse(floatingNow())
        floating.setValue(true)
        h.start()
        h.after(1000)
        h.relayout()
        assertTrue(floatingNow())
        assertTrue("back at $at: ${keys()}", abs(keys().left - at.left) <= 2 && abs(keys().top - at.top) <= 2)
    }

    @Test
    @Uat("TB-36")
    fun aSecondFingerInTheZoneKeepsItFloating() {
        prefs.set(floating, true)
        h.start()
        h.drag(grip(), 0f, 0f)
        h.drag(grip(), screenW / 2f, screenH.toFloat(), lift = false)
        assertTrue(dockMark() != null)
        h.secondFingerThenLift(screenW / 4f, screenH / 3f)
        h.after(1000)
        h.relayout()
        assertTrue(floating.getValue())
        assertTrue(floatingNow())
        assertTrue("left at the lowest, not back up left: ${keys()}", keys().top > screenH / 2)
    }

    @Test
    @Uat("TB-35")
    fun theHandleOnTop() {
        prefs.set(floating, true)
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
            KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextKeyView>()
                .first { it.mainText.text.toString() == "q" }.mainText.textSize
        }
        h.close()
        prefs.set(floating, true)
        val on = size()
        floating.setValue(false)
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
        prefs.set(floating, true)
        h.start()
        undoAtTheTop()
        assertNotEquals(0, keys().height())
    }

    @Test
    @Uat("TB-39")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun landscapeAsTallAsTheScreenTheUndoStillShown() {
        prefs.set(floating, true)
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
}
