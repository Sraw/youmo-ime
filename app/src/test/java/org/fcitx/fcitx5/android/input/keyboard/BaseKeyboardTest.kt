/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard.SwipeChoice
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Under the vivo keypress workaround the keyboard hands each key a one-finger event of its own
 * ([forKey]). Built with id 0 for every finger, hold to talk followed the wrong one: lifting
 * another finger ended it, and sliding the held one up no longer cancelled. The id reaches the
 * hold through the space's long press ([KeyAction.SpaceLongPressAction]). A finger on a disabled
 * key gives no key the touch, but keeps it the keyboard's, or a second finger reached no key.
 *
 * What a letter's swipe types ([SwipeChoice], [symbolSwipe]): the key's own, the first of the long
 * press, or nothing where the user turned that long press off; the preview shows the same.
 *
 * A keyboard is built under the app, whose preferences the keys read; the keys' descriptions are
 * placeholders, as the tests have none of the app's strings.
 */
@RunWith(RobolectricTestRunner::class)
class BaseKeyboardTest {

    /** The input view's lifecycle, which the keys' long-press timers run in. */
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private fun keyboard(actions: MutableList<KeyAction>) =
        TextKeyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark).apply {
            setViewTreeLifecycleOwner(Owner())
            keyActionListener = KeyActionListener { action, _ -> actions += action }
        }

    /** Fingers [ids] at [points] (x to y) as one event of [action]. */
    private fun touch(action: Int, ids: List<Int>, points: List<Pair<Float, Float>>): MotionEvent {
        val properties = ids.map { pid -> MotionEvent.PointerProperties().apply { id = pid } }
        val coords = points.map { (px, py) ->
            MotionEvent.PointerCoords().apply {
                x = px
                y = py
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(100L, 200L, action, ids.size, properties.toTypedArray(), coords.toTypedArray(), 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    /** The middle of [key] in its keyboard: the key is placed in its row, the row in the keyboard. */
    private fun middleOf(key: View): Pair<Float, Float> {
        val row = key.parent as View
        return (row.left + (key.left + key.right) / 2f) to (row.top + (key.top + key.bottom) / 2f)
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun twoFingers(): MotionEvent {
        val properties = intArrayOf(0, 3).map { pid -> MotionEvent.PointerProperties().apply { id = pid } }
        val coords = listOf(10f to 20f, 110f to 30f).map { (px, py) ->
            MotionEvent.PointerCoords().apply {
                x = px
                y = py
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            100L, 200L,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties.toTypedArray(), coords.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
    }

    @Test
    fun theKeysEventKeepsItsFingersId() {
        val e = twoFingers().forKey(MotionEvent.ACTION_DOWN, 1, Rect(100, 0, 140, 50))
        assertEquals(1, e.pointerCount)
        assertEquals("the second finger's id, not 0", 3, e.getPointerId(0))
        assertEquals(MotionEvent.ACTION_DOWN, e.actionMasked)
    }

    @Test
    fun theKeysEventIsInTheKeysCoordinates() {
        val e = twoFingers().forKey(MotionEvent.ACTION_MOVE, 1, Rect(100, 0, 140, 50))
        assertEquals(10f, e.x, 0f)
        assertEquals(30f, e.y, 0f)
        assertEquals(100L, e.downTime)
        assertEquals(200L, e.eventTime)
    }

    @Test
    fun theFirstFingerKeepsItsIdToo() {
        val e = twoFingers().forKey(MotionEvent.ACTION_UP, 0, Rect(0, 0, 40, 50))
        assertEquals(0, e.getPointerId(0))
        assertEquals(10f, e.x, 0f)
        assertEquals(20f, e.y, 0f)
    }

    /** The finger's properties and coordinates are refilled for each event: one built stays as it was. */
    @Test
    fun aKeysEventStaysItsOwnOnceTheNextIsBuilt() {
        val fingers = twoFingers()
        val first = fingers.forKey(MotionEvent.ACTION_MOVE, 0, Rect(0, 0, 40, 50))
        fingers.forKey(MotionEvent.ACTION_MOVE, 1, Rect(100, 0, 140, 50))
        assertEquals(0, first.getPointerId(0))
        assertEquals(10f, first.x, 0f)
        assertEquals(20f, first.y, 0f)
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun holdingTheSpaceSaysWhichFingerHoldsIt() {
        // one that does something: the text build's default does nothing and takes no touch
        val behavior = AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior
        val was = behavior.getValue()
        behavior.setValue(SpaceLongPressBehavior.Enumerate)
        try {
            val actions = mutableListOf<KeyAction>()
            val space = keyboard(actions).space
            space.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, listOf(3), listOf(5f to 5f)))
            // what the long-press timer calls once the delay is up
            space.performLongClick()
            assertEquals(KeyAction.SpaceLongPressAction(pointer = 3), actions.lastOrNull())
        } finally {
            behavior.setValue(was)
        }
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun underTheVivoWorkaroundAFingerOnADisabledKeyKeepsTheTouchForTheNext() {
        val workaround = AppPrefs.getInstance().advanced.vivoKeypressWorkaround
        val was = workaround.getValue()
        workaround.setValue(true)
        try {
            val keyboard = keyboard(mutableListOf()).apply {
                measure(exactly(1080), exactly(800))
                layout(0, 0, 1080, 800)
            }
            keyboard.space.isEnabled = false
            val onSpace = middleOf(keyboard.space)
            assertTrue("the touch stays the keyboard's", keyboard.onTouchEvent(touch(MotionEvent.ACTION_DOWN, listOf(0), listOf(onSpace))))
            assertFalse(keyboard.space.isPressed)
            val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            keyboard.onTouchEvent(touch(secondDown, listOf(0, 1), listOf(onSpace, middleOf(keyboard.caps))))
            assertTrue("the second finger's key has it", keyboard.caps.isPressed)
        } finally {
            workaround.setValue(was)
        }
    }

    @Test
    fun aSwipeTypesTheKeysOwnTheLongPresssFirstOrNothing() {
        val own = KeyAction.FcitxKeyAction("1")
        assertEquals(own to KeyActionListener.Source.Keyboard, SwipeChoice.of(null).typed(own))
        assertNull("its long press turned off", SwipeChoice.of("").typed(own))
        assertEquals(KeyAction.FcitxKeyAction("é") to KeyActionListener.Source.Popup, SwipeChoice.of("é").typed(own))
        assertEquals(KeyAction.CommitAction("qu") to KeyActionListener.Source.Popup, SwipeChoice.of("qu").typed(own))
    }

    @Test
    fun theSwipesPreviewIsWhatItTypes() {
        assertEquals("1", SwipeChoice.of(null).preview("1"))
        assertEquals("nothing, as nothing is typed", "", SwipeChoice.of("").preview("1"))
        assertEquals("é", SwipeChoice.of("é").preview("1"))
    }

    @Test
    fun onlyAnUpNoOneTookNorTheSystemCancelledIsASwipe() {
        fun up(consumed: Boolean = false, cancelled: Boolean = false) = CustomGestureView.Event(
            CustomGestureView.GestureType.Up, consumed, 0f, 0f, 0, 0, 0, 50, cancelled
        )
        assertEquals(SymbolSwipe.Outcome.Symbol, up().symbolSwipe(SwipeSymbolDirection.Down))
        assertEquals("taken by the long press", SymbolSwipe.Outcome.None, up(consumed = true).symbolSwipe(SwipeSymbolDirection.Down))
        assertEquals("cancelled", SymbolSwipe.Outcome.None, up(cancelled = true).symbolSwipe(SwipeSymbolDirection.Down))
        assertEquals("the other way", SymbolSwipe.Outcome.Tap, up().symbolSwipe(SwipeSymbolDirection.Up))
        assertEquals("swipes off", SymbolSwipe.Outcome.None, up().symbolSwipe(SwipeSymbolDirection.Disabled))
    }

    // PY-41: the text build's space long press does nothing, yet took the touch from a slow drag
    @Test
    fun aSpaceLongPressThatDoesNothingLeavesTheSwipeBe() {
        assertFalse(spaceLongPressTakesTouch(SpaceLongPressBehavior.None, voiceAvailable = true, inPasswordField = false))
        assertTrue(spaceLongPressTakesTouch(SpaceLongPressBehavior.Enumerate, voiceAvailable = true, inPasswordField = false))
        assertTrue(spaceLongPressTakesTouch(SpaceLongPressBehavior.Voice, voiceAvailable = true, inPasswordField = false))
        assertFalse("no talking in a password", spaceLongPressTakesTouch(SpaceLongPressBehavior.Voice, voiceAvailable = true, inPasswordField = true))
        assertFalse("no voice in this build", spaceLongPressTakesTouch(SpaceLongPressBehavior.Voice, voiceAvailable = false, inPasswordField = false))
    }

    // PY-41, as the key sees it: the long press the timer calls declines, sends nothing and does not buzz
    @Test
    @Config(application = FcitxApplication::class)
    fun aSpaceLongPressSetToNoneDeclinesTheTouch() {
        val behavior = AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior
        val was = behavior.getValue()
        behavior.setValue(SpaceLongPressBehavior.None)
        try {
            val actions = mutableListOf<KeyAction>()
            val space = keyboard(actions).space
            space.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, listOf(0), listOf(5f to 5f)))
            assertFalse("not taken: the drag goes on", space.performLongClick())
            assertTrue(actions.none { it is KeyAction.SpaceLongPressAction })
            assertFalse("no buzz for nothing", space.longPressFeedbackEnabled)
        } finally {
            behavior.setValue(was)
        }
    }

    /** The space held, slid [dx] sideways at once, then held past the long-press delay: what it sent. */
    private fun holdSpaceAfterSliding(dx: Float): List<KeyAction> {
        val actions = mutableListOf<KeyAction>()
        val space = keyboard(actions).apply {
            measure(exactly(1080), exactly(800))
            layout(0, 0, 1080, 800)
        }.space
        val x = space.width / 2f
        val y = space.height / 2f
        space.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, listOf(0), listOf(x to y)))
        if (dx != 0f) space.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE, listOf(0), listOf(x + dx to y)))
        val delay = AppPrefs.getInstance().keyboard.longPressDelay.getValue().toLong()
        shadowOf(Looper.getMainLooper()).idleFor(delay + 50, TimeUnit.MILLISECONDS)
        return actions
    }

    // PY-41 (F2357/F2359) through the key's own timer: a slow drag past the slop is the cursor's
    // swipe, and the long press due after it stays quiet; a still finger still gets its long press
    @Test
    @Config(application = FcitxApplication::class)
    fun aSlowDragOnTheSpaceKeepsTheLongPressFromFiring() {
        val behavior = AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior
        val was = behavior.getValue()
        behavior.setValue(SpaceLongPressBehavior.Enumerate)
        try {
            assertTrue("held still: the timer fires", holdSpaceAfterSliding(0f).any { it is KeyAction.SpaceLongPressAction })
            val slop = android.view.ViewConfiguration.get(RuntimeEnvironment.getApplication()).scaledTouchSlop
            assertTrue("dragged past the slop", holdSpaceAfterSliding(slop + 2f).none { it is KeyAction.SpaceLongPressAction })
        } finally {
            behavior.setValue(was)
        }
    }

    private fun letter(keyboard: TextKeyboard, label: String) =
        keyboard.allViews.filterIsInstance<KeyView>().first { (it.def as? KeyDef.Appearance.Text)?.displayText == label }

    /** A touch on [key] going down in its middle, moved [dy] and lifted there, in the key's coordinates. */
    private fun drag(key: View, dy: Float) {
        val x = key.width / 2f
        val y = key.height / 2f
        key.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, listOf(0), listOf(x to y)))
        key.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE, listOf(0), listOf(x to y + dy)))
        key.dispatchTouchEvent(touch(MotionEvent.ACTION_UP, listOf(0), listOf(x to y + dy)))
    }

    // ST-15: a swipe the other way (up, the symbol being down) types the letter once, on the key or off it
    @Test
    @Config(application = FcitxApplication::class)
    fun aSwipeTheOtherWayTypesTheLetterOnce() {
        val direction = AppPrefs.getInstance().keyboard.swipeSymbolDirection
        val was = direction.getValue()
        direction.setValue(SwipeSymbolDirection.Down)
        try {
            for ((what, dy) in listOf("on the key" to -45f, "off the key" to -400f)) {
                val actions = mutableListOf<KeyAction>()
                val keyboard = keyboard(actions).apply {
                    measure(exactly(1080), exactly(800))
                    layout(0, 0, 1080, 800)
                }
                drag(letter(keyboard, "Q"), dy)
                assertEquals(what, listOf("q"), actions.filterIsInstance<KeyAction.FcitxKeyAction>().map { it.act })
            }
        } finally {
            direction.setValue(was)
        }
    }

    // with swiping off, sliding off the key takes the press back, as before ST-15
    @Test
    @Config(application = FcitxApplication::class)
    fun withSwipingOffSlidingOffAKeyTypesNothing() {
        val direction = AppPrefs.getInstance().keyboard.swipeSymbolDirection
        val was = direction.getValue()
        direction.setValue(SwipeSymbolDirection.Disabled)
        try {
            val actions = mutableListOf<KeyAction>()
            val keyboard = keyboard(actions).apply {
                measure(exactly(1080), exactly(800))
                layout(0, 0, 1080, 800)
            }
            drag(letter(keyboard, "Q"), -400f)
            assertEquals(emptyList<String>(), actions.filterIsInstance<KeyAction.FcitxKeyAction>().map { it.act })
        } finally {
            direction.setValue(was)
        }
    }

    private fun percentWidth(view: View) = (view.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).matchConstraintPercentWidth

    // SP-03: the A row widens its ends while the 双拼 `;` is hidden, and gives the room back once shown
    @Test
    @Config(application = FcitxApplication::class)
    fun theARowFitsTheSemicolonKeyShownOrNot() {
        val expand = AppPrefs.getInstance().keyboard.expandKeypressArea
        val was = expand.getValue()
        expand.setValue(true)
        try {
            val keyboard = keyboard(mutableListOf())
            val a = letter(keyboard, "A")
            val l = letter(keyboard, "L")
            val semicolon = keyboard.findViewById<View>(org.fcitx.fcitx5.android.R.id.button_semicolon)
            // hidden from the start: no 11th key before an input method says so
            assertFalse(semicolon.isVisible)
            assertEquals(0.15f, percentWidth(a), 1e-6f)
            assertEquals(0.15f, percentWidth(l), 1e-6f)
            assertEquals(0.05f / 0.15f, a.layoutMarginLeft, 1e-6f)
            assertEquals(0.05f / 0.15f, l.layoutMarginRight, 1e-6f)

            keyboard.showKey(semicolon, true)
            assertEquals(0.1f, percentWidth(a), 1e-6f)
            assertEquals(0.1f, percentWidth(l), 1e-6f)
            assertEquals(0f, a.layoutMarginLeft, 0f)
            assertEquals(0f, l.layoutMarginRight, 0f)
            keyboard.measure(exactly(1080), exactly(800))
            keyboard.layout(0, 0, 1080, 800)
            // the faces lose the old margins too, or they stay drawn narrow and off centre
            for (key in listOf(a, l)) {
                val face = (key as android.view.ViewGroup).getChildAt(0)
                assertEquals(key.width, face.width)
            }

            keyboard.showKey(semicolon, false)
            assertEquals(0.15f, percentWidth(a), 1e-6f)
        } finally {
            expand.setValue(was)
        }
    }

    @Test
    fun theSpaceShowsTheSubModeToo() {
        assertEquals("拼音", spaceLabel("拼音", InputMethodSubMode()))
        assertEquals("拼音 (A)", spaceLabel("拼音", InputMethodSubMode("ascii", "A", "")))
        assertEquals("by its name, unlabelled", "拼音 (ascii)", spaceLabel("拼音", InputMethodSubMode("ascii", "", "")))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun dragOnTheNumberPadsSpaceMovesTheCursor() {
        val actions = mutableListOf<KeyAction>()
        val pad = NumberKeyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark).apply {
            setViewTreeLifecycleOwner(Owner())
            keyActionListener = KeyActionListener { action, _ -> actions += action }
        }
        val space = pad.findViewById<KeyView>(org.fcitx.fcitx5.android.R.id.button_mini_space)
        assertTrue(space.swipeEnabled)
        val move = CustomGestureView.Event(CustomGestureView.GestureType.Move, false, 0f, 0f, -2, 0, -2, 0)
        assertTrue(space.onGestureListener!!.onGesture(space, move))
        val left = KeyAction.SymAction(org.fcitx.fcitx5.android.core.KeySym(org.fcitx.fcitx5.android.core.FcitxKeyMapping.FcitxKey_Left), org.fcitx.fcitx5.android.core.KeyStates.Virtual)
        assertEquals(listOf(left, left), actions)
    }
}
