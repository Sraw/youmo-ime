/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.os.Looper
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Docking from the floating keyboard: slid down first, its drag over meanwhile; at once with animation off. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class FloatingControllerTest {

    private val context = PlaceholderStrings(RuntimeEnvironment.getApplication())
    private val floatingPref = ThemeManager.prefs.floatingKeyboard
    private val noAnimation = AppPrefs.getInstance().advanced.disableAnimation

    private val internal = AppPrefs.getInstance().internal

    // [before] runs ahead of the first layout, as a grab from the docked keyboard does
    private fun floating(before: (FloatingController) -> Unit = {}): Pair<FloatingController, View> {
        floatingPref.setValue(true)
        val root = ConstraintLayout(context)
        val keyboard = View(context)
        root.addView(keyboard, ConstraintLayout.LayoutParams(400, 400))
        val controller = FloatingController(root, ThemePreset.PixelDark) { null }
        controller.setUp(keyboard)
        before(controller)
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 2000)
        return controller to keyboard
    }

    // the handle's dock button (放回底部), by what TalkBack reads
    private fun FloatingController.dockButton() =
        handle!!.children.first { it.contentDescription == context.getString(R.string.floating_keyboard_dock) }

    @After
    fun tearDown() {
        noAnimation.setValue(false)
        floatingPref.setValue(false)
    }

    @Test
    fun animationOffItDocksAtOnce() {
        noAnimation.setValue(true)
        val (controller, _) = floating()
        controller.dockButton().performClick()
        assertFalse(floatingPref.getValue())
    }

    @Test
    fun itSlidesDownFirstItsDragOverAndThenDocks() {
        noAnimation.setValue(false)
        val (controller, keyboard) = floating()
        keyboard.translationY = -500f
        controller.dockButton().performClick()
        assertTrue("sliding", floatingPref.getValue())
        val x = AppPrefs.getInstance().internal.floatingKeyboardX.getValue()
        controller.carried!!.drag(-300f, 0f)
        controller.carried!!.drop(lifted = true)
        controller.dockButton().performClick()
        assertTrue(floatingPref.getValue())
        assertEquals("not dragged nor kept anew", x, AppPrefs.getInstance().internal.floatingKeyboardX.getValue())
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertFalse("docked once down", floatingPref.getValue())
        assertEquals(0f, keyboard.translationY, 0f)
    }

    // F3484: grabbed from the docked keyboard, laid out with its grip (the unlaid handle's 0, 0) under
    // the finger, clamped to the bottom it can go; kept as the finger lifts
    @Test
    fun grabbedItGoesUnderTheFingerOnceLaidOutAndIsKeptAsTheFingerLifts() {
        internal.floatingKeyboardY.setValue(0f)
        val (controller, keyboard) = floating { it.grab(500f, 1990f, FloatingSwitch.Bounds(0f, 1600f, 1000f, 400f)) }
        assertEquals("at the lowest of the room, 2000 less the mark's 32 and its 400", 1568f, keyboard.top + keyboard.translationY, 0.01f)
        assertEquals("not kept while the finger is down", 0f, internal.floatingKeyboardY.getValue(), 0f)
        controller.carried!!.drop(lifted = true)
        assertEquals(1f, internal.floatingKeyboardY.getValue(), 0.001f)
    }

    // F3467/F3470: TalkBack's detach has no finger down and no drop to come: kept as it is laid out,
    // and the next drag moves it at once (no slack left from the grab)
    @Test
    fun grabbedWithNoFingerDownItIsKeptAtOnceAndTheNextDragMovesIt() {
        internal.floatingKeyboardY.setValue(0f)
        val (controller, keyboard) = floating { it.grab(500f, 1990f, FloatingSwitch.Bounds(0f, 1600f, 1000f, 400f), ended = true) }
        assertEquals(1f, internal.floatingKeyboardY.getValue(), 0.001f)
        val top = keyboard.top + keyboard.translationY
        controller.carried!!.drag(0f, -100f)
        assertEquals(top - 100f, keyboard.top + keyboard.translationY, 0.01f)
    }
}
