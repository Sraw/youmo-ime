/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A key's menu picked as a long press's keys are ([PopupFocus]): by how far the finger moves from where it started. */
@RunWith(RobolectricTestRunner::class)
class PopupMenuUiTest {

    private val items = Array(3) {
        KeyDef.Popup.Menu.Item("$it", android.R.drawable.ic_menu_add, KeyAction.FcitxKeyAction("$it"))
    }
    private val trigger = Rect(480, 500, 520, 550)
    private var moved = 0
    private var dismissed = false

    private fun menu() = PopupMenuUi(
        ctx = RuntimeEnvironment.getApplication(),
        theme = ThemePreset.PixelDark,
        outerBounds = Rect(0, 0, 1000, 600),
        triggerBounds = trigger,
        onDismissSelf = { dismissed = true },
        items = items,
        onFocusMoved = { moved++ },
    )

    // the menu's keys are 48 dp square; Robolectric's density is 1
    private val step = 48 * PopupFocus.STEP

    @Test
    fun aStepAcrossFocusesTheNextItemOnce() {
        val ui = menu()
        val first = ui.onTrigger()
        // where the finger is as the menu opens is where it starts; the key's own coordinates, as BaseKeyboard passes them
        assertFalse(ui.changeFocus(20f, 25f))
        assertEquals(first, ui.onTrigger())
        assertEquals(0, moved)
        assertFalse(ui.changeFocus(20f + step, 25f))
        assertNotEquals(first, ui.onTrigger())
        assertEquals(1, moved)
        // a little further, the same item: not told again
        assertFalse(ui.changeFocus(20f + step + 1, 25f))
        assertEquals(1, moved)
    }

    @Test
    fun downOffTheKeyCancels() {
        val ui = menu()
        // the key's own coordinates, as BaseKeyboard passes them: below its bottom edge cancels
        ui.changeFocus(10f, trigger.height() - 5f)
        assertFalse(ui.changeFocus(10f, trigger.height() - 1f))
        assertTrue(ui.changeFocus(10f, trigger.height() + 1f))
        assertTrue(dismissed)
    }
}
