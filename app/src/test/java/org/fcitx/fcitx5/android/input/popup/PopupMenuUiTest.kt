/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupScenes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.input.shortcut.EditMenuLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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

    private fun menu(
        canvas: Boolean = false,
        items: Array<KeyDef.Popup.Menu.Item> = this.items,
        fixed: PopupMenuUi.Fixed? = null,
        measuring: (TextMeasurer) -> TextMeasurer = { it },
    ) = PopupMenuUi(
        ctx = RuntimeEnvironment.getApplication(),
        theme = ThemePreset.PixelDark,
        outerBounds = Rect(0, 0, 1000, 600),
        triggerBounds = trigger,
        onDismissSelf = { dismissed = true },
        items = items,
        onFocusMoved = { moved++ },
        fixed = fixed,
        scenes = if (canvas) PopupScenes(View(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark, measuring) else null,
    )

    // the handwriting pad's 注: words, no icons
    private val words = arrayOf("关", "拼音", "注音符号").map { KeyDef.Popup.Menu.Item(it, 0, KeyAction.CommitAction(it)) }.toTypedArray()

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

    // words in pills as the views' (CELL_DP × 1.75 across), their text drawn, not an icon
    @Test
    fun onTheCanvasWordsAreInPillsAsTheViews() {
        val view = menu(canvas = true, items = words).root as PopupSceneView
        val keys = view.scene!!.keys
        // in the columns' order round the focus, as the views'
        assertEquals(words.map { it.label }.sorted(), keys.mapNotNull { it.label }.sorted())
        // Robolectric's density 1: the views' pill, (48 * 1.75).toInt()
        assertEquals(listOf(84.0), keys.map { it.bounds.right - it.bounds.left }.distinct())
        for (k in keys) {
            val ops = k.ops(false)
            assertTrue(ops.none { it is Op.Icon })
            assertEquals(k.label, ops.filterIsInstance<Op.Text>().single().text)
        }
    }

    // a drawable's name reads back only as a menu gave it: a theme's "drawable:<id>" names no
    // resource that may not be there for the painter to look up (and throw)
    @Test
    fun aDrawablesNameReadsBackOnlyAsAMenuGaveIt() {
        val id = android.R.drawable.ic_menu_delete
        assertEquals(null, SceneIcons.of("drawable:$id"))
        assertEquals(null, SceneIcons.of("drawable:2147483647"))
        val name = SceneIcons.nameOf(id)
        assertEquals("drawable:$id", name)
        assertEquals(id, SceneIcons.of(name))
    }

    // a test finds a menu's words on the canvas as the long press's keys (KeyboardHarness), its
    // icons not, as the views' (popupKeys has no ImageView)
    @Test
    fun onTheCanvasAMenusWordsAreAmongThePopupsChoices() {
        val view = menu(canvas = true, items = words).root as PopupSceneView
        val choices = KeyboardHarness.sceneChoices(view, Rect(10, 20, 10, 20))
        assertEquals(view.scene!!.keys.sortedBy { it.bounds.left }.map { it.label }, choices.map { it.text })
        val k = view.scene!!.keys.first { it.label == choices[0].text }
        assertEquals(10 + view.outset.left + k.bounds.left.toInt(), choices[0].box.left)
        assertEquals(emptyList<KeyboardHarness.PopupKey>(), KeyboardHarness.sceneChoices(menu(canvas = true).root as PopupSceneView, Rect()))
    }

    // the edit menu's: cells in their own order where it is put (Fixed.at), the disabled faded and
    // never in focus; placed as the views' are, none of the key's menus' lift
    @Test
    fun onTheCanvasTheEditMenuKeepsItsPlaceAndItsDisabled() {
        val at = EditMenuLayout.Spot(300, 100)
        val fixed = PopupMenuUi.Fixed(start = 0, disabled = setOf(1), at = at)
        val ui = menu(canvas = true, fixed = fixed)
        val views = menu(fixed = fixed)
        assertEquals(views.offsetX to views.offsetY, ui.offsetX to ui.offsetY)
        assertEquals(at.left - trigger.left to at.top - trigger.top, ui.offsetX to ui.offsetY)
        val keys = (ui.root as PopupSceneView).scene!!.keys
        assertEquals(listOf("0", "1", "2"), keys.map { it.label })
        assertEquals(listOf(false, true, false), keys.map { KeyState.Disabled in it.states })
        assertEquals(listOf(true, false, false), keys.map { KeyState.Selected in it.states })
        // a key's menu, nowhere fixed: 52 dp above the key's top, as the views'
        val lifted = menu(canvas = true)
        assertEquals(menu().offsetY, lifted.offsetY)
        assertEquals(-52, lifted.offsetY)
    }

    // TalkBack reads each cell (its name), the one in focus selected, the disabled not enabled
    @Test
    fun onTheCanvasTalkBackReadsEachItem() {
        val ui = menu(canvas = true, fixed = PopupMenuUi.Fixed(start = 0, disabled = setOf(2)))
        val nodes = (ui.root as PopupSceneView).spokenKeys()
        assertEquals(listOf("0", "1", "2"), nodes.map { it.contentDescription.toString() })
        assertEquals(listOf(true, false, false), nodes.map { it.isSelected })
        assertEquals(listOf(true, true, false), nodes.map { it.isEnabled })
        assertTrue(nodes.all { !it.getBoundsInParentCompat().isEmpty })
    }

    // S3: each cell told as its view was: its class (a word's TextView, an icon's ImageView), its words,
    // not clickable (picked as the finger lifts), read a character, word or paragraph at a time as the
    // view's text or description (a TextView's line and page not: one line, no page)
    @Test
    fun onTheCanvasEachCellIsToldAsItsViewWas() {
        for (cells in listOf(items, words)) {
            // in a window: a view not attached tells nothing (View.onInitializeAccessibilityNodeInfoInternal)
            val root = menu(items = cells).root
            org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get().setContentView(root)
            val views = KeyboardHarness.all(root)
                .filter { (it is android.widget.TextView && it.text.isNotEmpty()) || (it is android.widget.ImageView && it.contentDescription != null) }
                .map { v -> AccessibilityNodeInfoCompat.wrap(v.createAccessibilityNodeInfo()) to v }
                .toList()
                // in the scene's order: the views' are the columns' (the focus's first)
                .sortedBy { (n, _) -> (n.contentDescription ?: n.text).toString() }
            val ui = menu(canvas = true, items = cells)
            val canvas = ui.root as PopupSceneView
            val nodes = canvas.spokenKeys().sortedBy { it.contentDescription.toString() }
            // the class a view's node is given (View.onInitializeAccessibilityNodeInfoInternal; Robolectric's node keeps none)
            assertEquals(views.map { it.second.accessibilityClassName }, nodes.map { it.className })
            assertEquals(views.map { (n, _) -> (n.contentDescription ?: n.text).toString() }, nodes.map { it.contentDescription.toString() })
            assertTrue(nodes.none { it.isClickable || it.isLongClickable })
            assertTrue(views.none { it.first.isClickable || it.first.isLongClickable })
            val read = AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER or
                AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD or AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH
            for ((n, node) in views.map { it.first }.zip(nodes)) {
                assertEquals(read, node.movementGranularities)
                assertEquals("${n.text}: the view's are those and more", read, n.movementGranularities and read)
            }
            // a cell's words a character at a time, forward then back, as its view's (its events sent: TalkBack on)
            org.robolectric.Shadows.shadowOf(root.context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)).setEnabled(true)
            val provider = canvas.accessibilityNodeProvider!!
            val keys = canvas.scene!!.keys
            for ((i, v) in views.map { it.second }.withIndex()) {
                val id = keys.indexOfFirst { k -> (k.a11y ?: k.label) == nodes[i].contentDescription.toString() }
                val chars = android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER)
                }
                val steps = listOf(true, true, true, true, false, false, false, false)
                    .map { if (it) AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY else AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY }
                val viewSaid = steps.map { v.performAccessibilityAction(it, chars) }
                val nodeSaid = steps.map { provider.performAction(id, it, chars) }
                assertEquals("${v.accessibilityClassName} ${views[i].first.text ?: views[i].first.contentDescription}", viewSaid, nodeSaid)
            }
        }
    }

    // the engine could not make the menu: unmade, nothing to show (PopupComponent shows none); it
    // could not make the focus moved: the scene before kept, the focus with it
    @Test
    fun onTheCanvasWhatCannotBeMadeIsNotShownOrMoved() {
        assertTrue(menu(canvas = true, items = words, measuring = { TextMeasurer { _, _, _, _ -> error("no font") } }).unmade)
        var broken = false
        val ui = menu(canvas = true, items = words, measuring = { real ->
            TextMeasurer { t, f, w, size -> if (broken) error("no font") else real.measure(t, f, w, size) }
        })
        assertFalse(ui.unmade)
        val view = ui.root as PopupSceneView
        val drawn = view.scene
        val first = ui.onTrigger()
        broken = true
        ui.changeFocus(20f, 25f)
        ui.changeFocus(20f + 84 * PopupFocus.STEP, 25f)
        assertSame(drawn, view.scene)
        assertEquals(first, ui.onTrigger())
        assertEquals(0, moved)
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfoCompat.getBoundsInParentCompat() = Rect().also(::getBoundsInParent)
}
