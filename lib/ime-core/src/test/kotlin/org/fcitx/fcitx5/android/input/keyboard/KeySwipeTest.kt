/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe.Key
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe.Outcome
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe.Preview
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe.Ways
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.junit.Assert.assertEquals
import org.junit.Test

class KeySwipeTest {

    private val selectAll = KeyShortcut.Command(KeyCommand.SelectAll)
    private val copy = KeyShortcut.Command(KeyCommand.Copy)
    private val first = SwipeSlot.First
    private val a = Key(char = true, up = first, down = SwipeSlot.of(selectAll))
    private val s = Key(char = true, up = first, down = SwipeSlot.Empty)
    private val space = Key(char = false, up = SwipeSlot.Command(KeyCommand.EditMenu), down = SwipeSlot.Empty)
    private val off = Ways(up = false, down = false)

    @Suppress("LongParameterList")
    private fun up(
        key: Key,
        totalY: Int,
        ways: Ways = Ways.Both,
        swiped: Boolean = totalY != 0,
        consumed: Boolean = false,
        cancelled: Boolean = false,
        password: Boolean = false,
    ) = KeySwipe.decide(key, ways, totalY, swiped, consumed, cancelled, password)

    @Test
    fun eachWayReachesItsSlot() {
        assertEquals(Outcome.Symbol, up(a, -1))
        assertEquals(Outcome.Shortcut(selectAll), up(a, 2))
        val flipped = a.copy(up = a.down, down = a.up)
        assertEquals(Outcome.Shortcut(selectAll), up(flipped, -1))
        assertEquals(Outcome.Symbol, up(flipped, 1))
    }

    @Test
    fun aTextSlotIsRunAsATextShortcut() {
        val q = Key(char = true, up = SwipeSlot.Text("你好"), down = first)
        assertEquals(Outcome.Shortcut(KeyShortcut.Text("你好")), up(q, -1))
    }

    @Test
    fun anEmptySlotOrAWayOffIsATap() {
        assertEquals("no shortcut", Outcome.Tap, up(s, 1))
        assertEquals("long press off: no first", Outcome.Tap, up(a.copy(hasFirst = false), -1))
        assertEquals("the way off", Outcome.Tap, up(a, -1, Ways(up = false, down = true)))
        assertEquals("the other way on", Outcome.Shortcut(selectAll), up(a, 1, Ways(up = false, down = true)))
    }

    @Test
    @Uat("ST-15")
    fun bothOffLeavesTheKeyAsItWas() {
        assertEquals(Outcome.None, up(a, -1, off))
        assertEquals(Outcome.None, up(a, 0, off, swiped = true))
        // 关 is 0.5.0's 禁用: a function key swiped out and back still taps
        assertEquals(Outcome.None, up(space, 0, off, swiped = true))
        assertEquals(Outcome.None, up(space, 2, off))
    }

    @Test
    fun swipedAndBackIsCancelled() {
        assertEquals(Outcome.Cancel, up(a, 0, swiped = true))
        assertEquals(Outcome.Cancel, up(space, 0, swiped = true))
    }

    @Test
    fun noSwipeALongPressOrTheSystemsIsNotTheSwipes() {
        assertEquals(Outcome.None, up(a, 0))
        assertEquals(Outcome.None, up(a, -1, consumed = true))
        assertEquals(Outcome.None, up(a, -1, cancelled = true))
    }

    @Test
    fun aPasswordRunsNoCopyNorCut() {
        assertEquals(Outcome.Cancel, up(Key(true, SwipeSlot.of(copy), first), -1, password = true))
        assertEquals(Outcome.Shortcut(selectAll), up(a, 1, password = true))
        assertEquals(Outcome.Cancel, up(Key(false, SwipeSlot.of(copy), SwipeSlot.of(copy)), 1, password = true))
    }

    @Test
    fun aFunctionKeyWithNothingThereIsAsItWas() {
        assertEquals(Outcome.Shortcut(KeyShortcut.Command(KeyCommand.EditMenu)), up(space, -1))
        assertEquals("nothing set down", Outcome.None, up(space, 1))
        val both = Key(false, SwipeSlot.of(selectAll), SwipeSlot.of(copy))
        assertEquals(Outcome.Shortcut(copy), up(both, 1))
        assertEquals("the way off", Outcome.None, up(both, 1, Ways(up = true, down = false)))
        assertEquals(Outcome.None, up(both, -1, off))
        // the comma's long press's first, swiped to
        assertEquals(Outcome.Symbol, up(Key(false, first, SwipeSlot.Empty), -1))
        assertEquals(Outcome.None, up(Key(false, first, SwipeSlot.Empty, hasFirst = false), -1))
    }

    // a key no layout names (注音九键's ˉ, a panel's): its built-in swipe the way the orientation puts every
    // key's long press's first, its tap the other; flipped with them (翻转全部 flips the orientation)
    @Test
    fun anUnnamedKeyFollowsTheOrientation() {
        val firstUp = Key.unnamed(SwipeOrientation.FirstUp, hasFirst = true)
        val firstDown = Key.unnamed(SwipeOrientation.FirstDown, hasFirst = true)
        fun lift(key: Key, totalY: Int) = KeySwipe.decide(key, Ways.Both, totalY, swiped = true, consumed = false, cancelled = false, password = false)
        assertEquals(Outcome.Symbol, lift(firstUp, -1))
        assertEquals(Outcome.Tap, lift(firstUp, 1))
        assertEquals(Outcome.Symbol, lift(firstDown, 1))
        assertEquals(Outcome.Tap, lift(firstDown, -1))
        // its way off: a tap; both off: nothing of the swipe's
        assertEquals(Outcome.Tap, KeySwipe.decide(firstDown, Ways(up = true, down = false), 1, swiped = true, consumed = false, cancelled = false, password = false))
        assertEquals(Outcome.None, KeySwipe.decide(firstDown, Ways(up = false, down = false), 1, swiped = true, consumed = false, cancelled = false, password = false))
    }

    // the key's own preview where the lift does nothing as well: swiped back, or a password's copy
    // (lifted it cancels, as swiped and back does; the preview shows no shortcut that would not run)
    @Test
    fun thePreviewIsWhatTheLiftWouldDoTheKeysOwnWhereItDoesNothing() {
        assertEquals(Preview.Shortcut(selectAll), KeySwipe.preview(a, Ways.Both, 1, password = false))
        assertEquals(Preview.Symbol, KeySwipe.preview(a, Ways.Both, -1, password = false))
        assertEquals(Preview.Own, KeySwipe.preview(s, Ways.Both, 1, password = false))
        assertEquals(Preview.Own, KeySwipe.preview(a, Ways.Both, 0, password = false))
        assertEquals(Preview.Own, KeySwipe.preview(Key(true, SwipeSlot.of(copy), first), Ways.Both, -1, password = true))
    }
}
