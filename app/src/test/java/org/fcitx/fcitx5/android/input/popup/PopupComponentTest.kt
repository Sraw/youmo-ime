/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The bubble over a key pressed, as the finger moves on: a letter's preview sends an update on every
 * move, also once its long press's keys are up, which put the bubble back to its own width under
 * them; and each update laid the input view out again, whether or not anything had changed. At an
 * edge the long press's keys are moved in, and the bubble with the key in focus. Under the app,
 * whose theme preferences the popups read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PopupComponentTest {

    private val popup = PopupComponent()

    // in a root 150 px across (1 dp = 1 px): a long press of five has keys 30 wide, narrower than it
    private val key = Rect(55, 500, 95, 550)

    // one row: five columns
    private val five = KeyDef.Popup.Keyboard.Explicit(arrayOf("1", "2", "3", "4", "5"))

    @Before
    fun setUp() {
        val scope = DynamicScope()
        // the types the popups find them by
        val context: ContextThemeWrapper = ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_DeviceDefault)
        val theme: Theme = ThemePreset.PixelDark
        scope += context.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        // off (no engine here): a symbol shown as it is
        scope += PunctuationComponent()
        scope += popup
        layOut()
    }

    // laid out, as in the input view: a long press's keys are fitted across it
    private fun layOut() {
        val exactly = { size: Int -> View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY) }
        popup.root.measure(exactly(150), exactly(600))
        popup.root.layout(0, 0, 150, 600)
    }

    private fun send(action: PopupAction) = popup.listener.onPopupAction(action)

    private val bubble: View get() = popup.root.getChildAt(0)

    private fun placedAt() = (bubble.layoutParams as FrameLayout.LayoutParams).run { listOf(width, leftMargin, topMargin) }

    // the long press's keys, added after the bubble, as laid out: however they are arranged, its columns across
    private fun longPressKeyWidth(): Int {
        layOut()
        return popup.root.getChildAt(1).measuredWidth / five.items.size
    }

    @Test
    fun underALongPressTheBubbleStaysAsWideAsItsKeysAsTheFingerMoves() {
        send(PopupAction.PreviewAction(ID, "q", key))
        send(PopupAction.ShowKeyboardAction(ID, five, key))
        val keys = longPressKeyWidth()
        assertNotEquals("keys of another width than the bubble's own", key.width(), keys)
        assertEquals(keys, bubble.layoutParams.width)
        // the preview's updates, back on the key and swiped
        send(PopupAction.PreviewUpdateAction(ID, "q"))
        send(PopupAction.PreviewUpdateAction(ID, "1"))
        assertEquals(keys, bubble.layoutParams.width)
    }

    @Test
    fun atAnEdgeTheBubbleIsMovedInUnderItsKey() {
        // the five keys fill the root, so they start at its edge: centred over the key, the bubble was at 5
        val edge = Rect(0, 500, 40, 550)
        send(PopupAction.PreviewAction(ID, "q", edge))
        send(PopupAction.ShowKeyboardAction(ID, five, edge))
        layOut()
        assertEquals(0, bubble.left)
    }

    @Test
    fun anUpdateOrAPressThatChangesNothingKeepsTheBubbleAsItIs() {
        send(PopupAction.PreviewAction(ID, "q", key))
        val placed = placedAt()
        layOut()
        send(PopupAction.PreviewUpdateAction(ID, "q"))
        // pressed again before it went
        send(PopupAction.PreviewAction(ID, "q", key))
        assertFalse("laid out again", popup.root.isLayoutRequested)
        // another text no wider than the key: the key's width still (the text itself asks for a layout)
        send(PopupAction.PreviewUpdateAction(ID, "1"))
        assertEquals(placed, placedAt())
        layOut()
        send(PopupAction.PreviewAction(ID, "1", key))
        assertFalse("laid out again", popup.root.isLayoutRequested)
        send(PopupAction.PreviewUpdateAction(ID, "w".repeat(60)))
        assertTrue("wider for a text wider than the key", bubble.layoutParams.width > key.width())
    }

    private companion object {
        const val ID = 1
    }

    // 九键's: the digit and letters as they are, the symbols shown as Chinese punctuation maps them
    @Test
    fun onlyTheKeysSetApartAreShownMapped() {
        val nineKey = KeyDef.Popup.Keyboard.Explicit(arrayOf("2", "a", "!", "@"), symbolsFrom = 2)
        val map = mapOf("!" to "！", "a" to "A", "2" to "二")
        assertEquals(listOf("2", "a", "！", "@"), PopupComponent.explicitLabels(nineKey) { map[it] ?: it }.toList())
        // none set apart (1's, everyone else's): none mapped
        assertEquals(listOf("1", "2", "3", "4", "5"), PopupComponent.explicitLabels(five) { "x" }.toList())
    }

    // what a key's popup is made with goes on to the keys shown: 1's committed as they are, 九键's
    // symbols in rows of their own
    @Test
    fun aKeyboardsWayOfTypingAndItsSymbolsReachTheKeysShown() {
        var id = ID
        // each on a key of its own (a dismissal runs in the IME's scope, not here)
        fun shown(keyboard: KeyDef.Popup.Keyboard.Explicit): Pair<KeyAction?, Int> {
            send(PopupAction.ShowKeyboardAction(++id, keyboard, key))
            layOut()
            // the long press's keys, added last: a row a child
            val rows = (popup.root.getChildAt(popup.root.childCount - 1) as ViewGroup).childCount
            return PopupAction.TriggerAction(id).also { send(it) }.outAction to rows
        }
        assertEquals(KeyAction.CommitAction(".") to 1, shown(KeyDef.Popup.Keyboard.Explicit(arrayOf(".", "-"), asIs = true)))
        assertEquals(KeyAction.FcitxKeyAction(".") to 1, shown(KeyDef.Popup.Keyboard.Explicit(arrayOf(".", "-"))))
        assertEquals(2, shown(KeyDef.Popup.Keyboard.Explicit(arrayOf("2", "a", "!"), symbolsFrom = 2)).second)
        // committed as they are, none can be set apart to be mapped
        assertThrows(IllegalArgumentException::class.java) {
            KeyDef.Popup.Keyboard.Explicit(arrayOf("a", "!"), symbolsFrom = 1, asIs = true)
        }
    }
}
