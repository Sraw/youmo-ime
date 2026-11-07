/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupScenes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A key's menu picked as a long press's keys are ([PopupFocus]): by how far the finger moves from where it started. */
@RunWith(RobolectricTestRunner::class)
// the canvas's scenes read the theme's look (ThemeManager, so AppPrefs)
@Config(application = FcitxApplication::class)
class PopupMenuUiTest {

    private val items = Array(3) {
        KeyDef.Popup.Menu.Item("$it", android.R.drawable.ic_menu_add, KeyAction.HostKeyAction("$it"))
    }
    private val trigger = Rect(480, 500, 520, 550)
    private var moved = 0
    private var dismissed = false

    private fun menu(canvas: Boolean = false) = PopupMenuUi(
        ctx = RuntimeEnvironment.getApplication(),
        theme = ThemePreset.PixelDark,
        outerBounds = Rect(0, 0, 1000, 600),
        triggerBounds = trigger,
        onDismissSelf = { dismissed = true },
        items = items,
        onFocusMoved = { moved++ },
        scenes = if (canvas) PopupScenes(View(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark) else null,
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

    // 060-render-p2 S2: a key the canvas draws has its menu as the engine's scene, the focus its
    // Selected state, drawn again as it moves; each item's icon by a name the painter finds
    @Test
    fun onTheCanvasTheItemInFocusIsDrawnSelected() {
        val ui = menu(canvas = true)
        val view = ui.root as PopupSceneView
        fun selected() = view.scene!!.keys.filter { KeyState.Selected in it.states }.map { it.label }
        val first = (ui.onTrigger() as KeyAction.HostKeyAction).act
        assertEquals(listOf(first), selected())
        ui.changeFocus(20f, 25f)
        ui.changeFocus(20f + step, 25f)
        val next = (ui.onTrigger() as KeyAction.HostKeyAction).act
        assertNotEquals(first, next)
        assertEquals(listOf(next), selected())
        assertEquals(3, view.scene!!.keys.size)
        val icon = view.scene!!.keys.first().ops(false).filterIsInstance<Op.Icon>().single()
        assertEquals(android.R.drawable.ic_menu_add, SceneIcons.of(icon.name))
    }
}
