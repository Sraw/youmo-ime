/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard.SwipeChoice
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a letter's swipe types ([SwipeChoice], [KeySwipe]): the key's own, the first of the long
 * press, or nothing where the user turned that long press off; the preview shows the same. The
 * swipes done on the keys the scene draws, what the keyboard was told recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class BaseKeyboardTest {

    @get:Rule
    val prefs = PrefsRule()

    /** The pinyin keyboard, its key and popup actions recorded as they go on to the service. */
    private class Told(val h: KeyboardHarness, val board: TextKeyboard) {
        val actions = mutableListOf<KeyAction>()
        val popups = mutableListOf<PopupAction>()

        init {
            val keys = board.keyActionListener
            val pops = board.popupActionListener
            board.keyActionListener = KeyActionListener { a, from -> actions += a; keys?.onKeyAction(a, from) }
            board.popupActionListener = PopupActionListener { popups += it; pops?.onPopupAction(it) }
        }

        // key [id] swiped [rows] of its own height down (up when less than 0): 0.8, some 44 dp here, past the 36 dp threshold
        fun drag(id: String, rows: Float) {
            val k = h.keyNamed(id)
            h.swipe(k, 0f, k.height * rows)
        }
    }

    private fun <T> told(done: (Told) -> T): T = KeyboardHarness(im = KeyboardHarness.PINYIN).start().use { h ->
        done(Told(h, KeyboardHarness.all(h.inputView).filterIsInstance<TextKeyboard>().first()))
    }

    @Test
    fun aSwipeTypesTheKeysOwnTheLongPresssFirstOrNothing() {
        val own = KeyAction.HostKeyAction("1")
        assertEquals(own to KeyActionListener.Source.Keyboard, SwipeChoice.of(null).typed(own))
        assertNull("its long press turned off", SwipeChoice.of("").typed(own))
        assertEquals(KeyAction.HostKeyAction("é") to KeyActionListener.Source.Popup, SwipeChoice.of("é").typed(own))
        assertEquals(KeyAction.CommitAction("qu") to KeyActionListener.Source.Popup, SwipeChoice.of("qu").typed(own))
    }

    @Test
    fun theSwipesPreviewIsWhatItTypes() {
        assertEquals("1", SwipeChoice.of(null).preview("1"))
        assertEquals("nothing, as nothing is typed", "", SwipeChoice.of("").preview("1"))
        assertEquals("é", SwipeChoice.of("é").preview("1"))
    }

    // PY-41: a space long press that does nothing (None) took the touch from a slow drag
    @Test
    fun aSpaceLongPressThatDoesNothingLeavesTheSwipeBe() {
        assertFalse(spaceLongPressTakesTouch(SpaceLongPressBehavior.None, voiceReady = true, inPasswordField = false))
        assertTrue(spaceLongPressTakesTouch(SpaceLongPressBehavior.Enumerate, voiceReady = true, inPasswordField = false))
        assertTrue(spaceLongPressTakesTouch(SpaceLongPressBehavior.Voice, voiceReady = true, inPasswordField = false))
        assertFalse("no talking in a password", spaceLongPressTakesTouch(SpaceLongPressBehavior.Voice, voiceReady = true, inPasswordField = true))
        assertFalse("no voice pack", spaceLongPressTakesTouch(SpaceLongPressBehavior.Voice, voiceReady = false, inPasswordField = false))
        assertTrue("another behaviour needs no pack", spaceLongPressTakesTouch(SpaceLongPressBehavior.Enumerate, voiceReady = false, inPasswordField = false))
    }

    // hold to talk follows the finger holding the space: its long press told with that finger's pointer id
    @Test
    fun theSpacesLongPressCarriesTheFingersPointer() {
        told { t ->
            prefs.set(AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior, SpaceLongPressBehavior.Enumerate)
            val h = t.h
            val space = h.keyNamed("space")
            val root = IntArray(2).also(h.inputView::getLocationInWindow)
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 3; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val at = arrayOf(MotionEvent.PointerCoords().apply { x = space.bounds.exactCenterX() - root[0]; y = space.bounds.exactCenterY() - root[1] })
            val down = SystemClock.uptimeMillis()
            fun send(action: Int) = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, at, 0, 0, 1f, 1f, 0, 0, 0, 0)
                .also { h.inputView.dispatchTouchEvent(it); it.recycle() }
            send(MotionEvent.ACTION_DOWN)
            h.after(ViewConfiguration.getLongPressTimeout() + 500L)
            assertTrue(t.actions.toString(), KeyAction.SpaceLongPressAction(pointer = 3) in t.actions)
            send(MotionEvent.ACTION_UP)
        }
    }

    // 滑动 at its default (shortcuts up, symbols down): a's select all up, its symbol down, s with nothing up a tap
    @Test
    @Uat("KB-61")
    fun aLettersSwipeUpIsItsShortcutAndDownItsSymbol() = told { t ->
        t.drag("a", -0.8f)
        assertEquals(listOf(KeyAction.ShortcutAction(KeyShortcut.Command(KeyCommand.SelectAll))), t.actions.filterIsInstance<KeyAction.ShortcutAction>())
        t.actions.clear()
        t.drag("s", -0.8f)
        assertEquals("nothing up there: a tap", listOf("s"), t.actions.filterIsInstance<KeyAction.HostKeyAction>().map { it.act })
        t.actions.clear()
        t.drag("a", 0.8f)
        assertTrue("its symbol, no shortcut: ${t.actions}", t.actions.isNotEmpty() && t.actions.none { it is KeyAction.ShortcutAction })
        t.actions.clear()
        t.board.inPasswordField = true
        t.drag("c", -0.8f)
        assertEquals("no copy of a password, nor a c", emptyList<KeyAction>(), t.actions.filter { it is KeyAction.ShortcutAction || it is KeyAction.HostKeyAction })
    }

    // the space swiped up opens the edit menu over it; the backspace swiped up clears, down restores
    @Test
    @Uat("KB-62")
    fun theSpaceUpIsTheEditMenuAndTheBackspaceUpAndDownClearAndRestore() = told { t ->
        t.drag("space", -0.8f)
        assertTrue(t.popups.toString(), t.popups.any { it is PopupAction.ShowEditMenuAction })
        t.actions.clear()
        t.drag("backspace", -0.8f)
        t.drag("backspace", 0.8f)
        assertEquals(
            listOf(KeyCommand.Clear, KeyCommand.Restore),
            t.actions.filterIsInstance<KeyAction.ShortcutAction>().map { (it.shortcut as KeyShortcut.Command).command },
        )
    }

    // a text shortcut set for a key is committed as its shortcut; the swipe off takes it all the same
    @Test
    @Uat("KB-63")
    fun aKeysOwnTextShortcutIsWhatItsSwipeRuns() {
        prefs.set(AppPrefs.getInstance().internal.keyShortcuts, KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好")).serialize())
        told { t ->
            t.drag("q", -2f)
            assertEquals(listOf(KeyAction.ShortcutAction(KeyShortcut.Text("你好"))), t.actions.filterIsInstance<KeyAction.ShortcutAction>())
        }
    }

    // ST-15: a swipe the other way (up, the symbol being down) types the letter once, just past the threshold or far off
    @Test
    fun aSwipeTheOtherWayTypesTheLetterOnce() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, SwipeLayersChoice.SymbolDownOnly)
        for ((what, rows) in listOf("just past the threshold" to -0.8f, "far off the key" to -2f)) {
            told { t ->
                t.drag("q", rows)
                assertEquals(what, listOf("q"), t.actions.filterIsInstance<KeyAction.HostKeyAction>().map { it.act })
            }
        }
    }

    // with swiping off, sliding off the key takes the press back, as before ST-15
    @Test
    fun withSwipingOffSlidingOffAKeyTypesNothing() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, SwipeLayersChoice.Off)
        told { t ->
            t.drag("q", -2f)
            assertEquals(emptyList<String>(), t.actions.filterIsInstance<KeyAction.HostKeyAction>().map { it.act })
        }
    }

    @Test
    fun theSpaceShowsTheSubModeToo() {
        assertEquals("拼音", spaceLabel("拼音", InputMethodSubMode()))
        assertEquals("拼音 (A)", spaceLabel("拼音", InputMethodSubMode("ascii", "A", "")))
        assertEquals("by its name, unlabelled", "拼音 (ascii)", spaceLabel("拼音", InputMethodSubMode("ascii", "", "")))
    }
}
