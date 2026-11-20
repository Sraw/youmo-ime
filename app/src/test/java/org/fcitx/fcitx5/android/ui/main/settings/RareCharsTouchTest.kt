/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.user.RareChoice.Change
import org.fcitx.fcitx5.android.uat.RarePageUat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Drags only while a finger is down and over their own rows; the search keyboard goes with the search; saves never lost. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class RareCharsTouchTest : RarePageUat() {

    private val time = SystemClock.uptimeMillis()

    private fun touch(action: Int, x: Float, y: Float) {
        list().dispatchTouchEvent(MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0))
    }

    private fun middle(c: String): Pair<Float, Float> = cell(c).let { (it.left + it.width / 2f) to (it.top + it.height / 2f) }

    // a long press with no finger (a screen reader's) is over at once: the next touch is a tap, not a drag's end
    @Test
    @Uat("AX-17")
    fun aLongPressWithNoFingerIsNoDrag() {
        RareCharsManager.update(listOf(Change.On(true)), TestCharLevels.levels)
        openPage()
        openGroup(R.string.rare_group_out)
        cell("炟").performLongClick()
        idle()
        assertTrue("the cell long pressed changed", cell("炟").held)
        val (x, y) = middle("鎉")
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_MOVE, x, y)
        touch(MotionEvent.ACTION_UP, x, y)
        idle()
        assertFalse("no drag from 炟 over 畣", cell("畣").held)
    }

    // a drag under way when the search finds fewer rows: lifted, nothing changed, no crash on old positions
    @Test
    fun aDragOverRowsTheSearchChangedEndsWithThem() {
        RareCharsManager.update(listOf(Change.On(true)), TestCharLevels.levels)
        openPage()
        search("DA")
        assertFalse(cell("龘").held)
        val (x, y) = middle("龘")
        touch(MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        search("炟")
        assertEquals(setOf("炟"), cells().keys)
        touch(MotionEvent.ACTION_UP, x, y)
        idle()
        assertFalse(cell("炟").held)
        search("DA")
        assertFalse("the drag's start not changed on the lift", cell("龘").held)
    }

    // left again while the previous save still writes: each change written once, the last not dropped
    @Test
    fun aSaveWhileTheLastStillWritesLosesNothing() {
        RareCharsManager.update(listOf(Change.On(true)), TestCharLevels.levels)
        openPage()
        openGroup(R.string.rare_group_out)
        val first = synchronized(RareCharsManager) {
            cell("炟").performClick()
            idle()
            st.leaveAndReturn()
            val sent = RareCharsViewModel.saving
            cell("鎉").performClick()
            idle()
            st.leaveAndReturn()
            cell("畣").performClick()
            idle()
            sent
        }
        until("saved") { first?.isCompleted == true && RareCharsViewModel.saving?.isCompleted == true }
        listOf("炟", "鎉", "畣").forEach { assertTrue("$it held", cell(it).held) }
    }

    // letters set anew are read from their top, not the letter last gone to
    @Test
    @Uat("AX-17")
    fun newLettersAreAtTheirTop() {
        openPage()
        val bar = LetterScroller(activity)
        val gone = ArrayList<Char>()
        bar.onLetter = { gone += it }
        bar.letters = listOf('a', 'b', 'c')
        assertTrue(bar.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals("B", ViewCompat.getStateDescription(bar)?.toString())
        bar.letters = listOf('x', 'y', 'z')
        assertEquals("X", ViewCompat.getStateDescription(bar)?.toString())
        assertTrue(bar.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(listOf('b', 'y'), gone)
    }

    // a group opened while the search has the keyboard: the keyboard put away and the search let go
    @Test
    @Uat("WD-57")
    fun aGroupOpenedPutsTheSearchsKeyboardAway() {
        RareCharsManager.update(listOf(Change.On(true)), TestCharLevels.levels)
        openPage()
        val field = views().filterIsInstance<EditText>().single()
        field.requestFocus()
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(field, 0)
        assertTrue(field.hasFocus())
        assertTrue(shadowOf(imm).isSoftInputVisible)
        openGroup(R.string.rare_group_out)
        assertFalse(field.hasFocus())
        assertFalse(shadowOf(imm).isSoftInputVisible)
    }
}
