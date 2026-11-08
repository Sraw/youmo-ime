/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.scene.engine.Popups
import org.mechdancer.dependency.plusAssign
import org.junit.Assert.assertEquals
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * The bubble over a key pressed, as the finger moves on: a letter's preview sends an update on every
 * move, also once its long press's keys are up, and each update laid the input view out again,
 * whether or not anything had changed. A long press's keys float over the key on their own, the
 * bubble gone. Under the app, whose theme preferences the popups read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PopupComponentTest {

    @get:Rule
    val prefs = PrefsRule()

    private val popup = PopupComponent()

    // in a root 150 px across (1 dp = 1 px): a long press of five has keys 30 wide
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

    @Test
    fun aLongPressesKeysFloatOverTheKeyAsSmallAsALetters() {
        // a 九键 key, far wider than a letter's: its long press's keys as small as for a letter
        val wide = Rect(10, 500, 140, 550)
        send(PopupAction.PreviewAction(ID, "q", wide))
        send(PopupAction.ShowKeyboardAction(ID, five, wide))
        layOut()
        // the bubble gone, the keys alone
        assertEquals(1, popup.root.childCount)
        val keys = popup.root.getChildAt(0) as PopupSceneView
        assertTrue("${keys.sceneWidth}", keys.sceneWidth / five.items.size <= Popups.KEY_MIN_WIDTH_DP)
        // over the key, clear of it (the shadow's room under it)
        assertEquals(wide.top - Popups.LIFT_DP.toInt(), keys.bottom - keys.outset.bottom)
        // the preview's updates, back on the key and swiped, bring no bubble back
        send(PopupAction.PreviewUpdateAction(ID, "q"))
        send(PopupAction.PreviewUpdateAction(ID, "1"))
        assertEquals(1, popup.root.childCount)
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

    // 060-render-p2 S2: a key's popups are the engine's scenes, in a view as large as the scene and
    // its shadow's room, that room round the bubble (compared with the views' till S8a deleted them)
    @Test
    fun aKeysBubbleIsASceneWithRoomForItsShadow() {
        send(PopupAction.PreviewAction(SCENE, "q", key))
        val scene = bubble as PopupSceneView
        val out = scene.outset
        assertTrue("room for the shadow: $out", out.bottom > 0)
        val lp = scene.layoutParams as FrameLayout.LayoutParams
        // as wide as the key, over it
        assertEquals(key.width(), lp.width - out.left - out.right)
        assertEquals(key.left, lp.leftMargin + out.left)
        assertTrue("${lp.topMargin + out.top}", lp.topMargin + out.top < key.top)
        assertEquals(scene.sceneHeight + out.top + out.bottom, lp.height)
        assertEquals("q", scene.scene!!.keys.single().label)
    }

    @Test
    fun aSceneBubbleShowsTheUpdatedTextAndIsWiderForWords() {
        send(PopupAction.PreviewAction(SCENE, "q", key))
        val scene = bubble as PopupSceneView
        send(PopupAction.PreviewUpdateAction(SCENE, "1"))
        assertEquals("1", scene.scene!!.keys.single().label)
        // as the views' test: Robolectric measures a character 1 px
        send(PopupAction.PreviewUpdateAction(SCENE, "w".repeat(60), words = true))
        assertTrue("${scene.sceneWidth}", scene.sceneWidth > key.width())
        assertEquals(scene.sceneWidth + scene.outset.left + scene.outset.right, bubble.layoutParams.width)
    }

    @Test
    fun aSceneKeysLongPressIsTheEnginesSceneOverTheKey() {
        send(PopupAction.ShowKeyboardAction(SCENE, five, key))
        layOut()
        val keys = popup.root.getChildAt(0) as PopupSceneView
        val out = keys.outset
        // the keys' bottom the lift over the key, its shadow below
        assertEquals(key.top - Popups.LIFT_DP.toInt(), keys.bottom - out.bottom)
        // the columns round the key pressed, as the views lay them out (PopupKeyboardUiTest)
        assertEquals(listOf("1", "2", "3", "4", "5"), keys.scene!!.keys.mapNotNull { it.label }.sorted())
        assertEquals(keys.sceneWidth + out.left + out.right, keys.width)
    }

    // what the engine cannot make (logged) is no popup this time, never one not drawn that a lift
    // still types: the long press as one with no popup, the bubble none
    @Test
    fun aPopupTheEngineCannotMakeIsNoneAndALiftTypesNothing() {
        val logged = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == Log.WARN) logged += message
            }
        }
        Timber.plant(tree)
        try {
            popup.measuring = { TextMeasurer { _, _, _, _ -> error("no font") } }
            send(PopupAction.PreviewAction(SCENE, "q", key))
            assertEquals(0, popup.root.childCount)
            send(PopupAction.ShowKeyboardAction(SCENE, five, key))
            send(PopupAction.ShowMenuAction(SCENE, menu, key))
            assertEquals(0, popup.root.childCount)
            send(PopupAction.ChangeFocusAction(SCENE, 200f, 0f))
            val lift = PopupAction.TriggerAction(SCENE).also { send(it) }
            assertEquals(null, lift.outAction)
            assertFalse(lift.outConsumed)
            assertTrue(logged.toString(), logged.any { "not made" in it })
        } finally {
            Timber.uproot(tree)
        }
    }

    // a bubble up whose new text the engine cannot make goes, not left with the text before while
    // a lift types the new one
    @Test
    fun aShownBubbleTheEngineCannotUpdateGoes() {
        // a character measured, anything longer not
        popup.measuring = { real -> TextMeasurer { t, f, w, size -> if (t.length > 1) error("no font") else real.measure(t, f, w, size) } }
        send(PopupAction.PreviewAction(SCENE, "q", key))
        assertTrue(bubble is PopupSceneView)
        send(PopupAction.PreviewUpdateAction(SCENE, "hello"))
        assertEquals(0, popup.root.childCount)
        // the next press has its bubble again, and one only
        send(PopupAction.PreviewAction(SCENE, "w", key))
        assertEquals(1, popup.root.childCount)
        assertEquals("w", (bubble as PopupSceneView).scene!!.keys.single().label)
    }

    // a focus moved that changes the shadow's room keeps the keys' box where it was, the view moved round it
    @Test
    fun aLongPressWhoseShadowGrowsStaysWhereItIs() {
        send(PopupAction.ShowKeyboardAction(SCENE, five, key))
        layOut()
        val view = popup.root.getChildAt(0) as PopupSceneView
        fun box() = (view.layoutParams as FrameLayout.LayoutParams).let { it.leftMargin + view.outset.left to it.topMargin + view.outset.top }
        val was = box()
        val s = view.scene!!
        val wider = Op.Shadow(Shape.Rect(Box(0.0, 0.0, s.width, s.height)), view.outset.left + 20.0, Argb(0x40000000))
        val before = Rect(view.outset)
        view.scene = Scene(s.width, s.height, s.base + wider, s.keys, s.slots)
        assertTrue(view.outset.left > before.left)
        assertEquals(was, box())
    }

    // the focus moved and the engine could not make it: the scene before kept, the focus with it,
    // so a lift types the key drawn selected
    @Test
    fun aFocusTheEngineCannotDrawStaysWhereItWas() {
        var broken = false
        popup.measuring = { real -> TextMeasurer { t, f, w, size -> if (broken) error("no font") else real.measure(t, f, w, size) } }
        fun lifted(key: KeyRef, moved: Boolean): KeyAction? {
            send(PopupAction.ShowKeyboardAction(key, five, this.key))
            layOut()
            val view = popup.root.getChildAt(popup.root.childCount - 1) as PopupSceneView
            // the first move is where the finger was as it opened: the focus moves from there
            send(PopupAction.ChangeFocusAction(key, 20f, 25f))
            val drawn = view.scene
            broken = moved.not()
            send(PopupAction.ChangeFocusAction(key, 200f, 25f))
            if (!moved) assertSame(drawn, view.scene)
            broken = false
            return PopupAction.TriggerAction(key).also { send(it) }.outAction
        }
        val first = PopupAction.TriggerAction(SCENE).let {
            send(PopupAction.ShowKeyboardAction(SCENE, five, key))
            send(it)
            it.outAction
        }
        assertNotEquals("a working move moves", first, lifted(KeyRef.scene("w", 1), moved = true))
        assertEquals(first, lifted(KeyRef.scene("e", 2), moved = false))
    }

    private companion object {
        val ID = KeyRef.view(1)
        val SCENE = KeyRef.scene("q", 0)
        val menu = KeyDef.Popup.Menu(arrayOf(KeyDef.Popup.Menu.Item("Emoji", 0, KeyAction.CommitAction("x"))))
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

    // the 14 keys': a mark in the row before the symbols as its key's corner shows it; letters and digits as they are
    @Test
    fun aMarkInTheRowIsShownMappedItsLettersAndDigitsNot() {
        val map = mapOf("!" to "！", "1" to "一", "c" to "C", "%" to "％")
        val cv = KeyDef.Popup.Keyboard.Explicit(arrayOf("C", "V", "!", "c", "v", "%"), symbolsFrom = 5, inRow = 2)
        assertEquals(listOf("C", "V", "！", "c", "v", "％"), PopupComponent.explicitLabels(cv) { map[it] ?: it }.toList())
        val qw = KeyDef.Popup.Keyboard.Explicit(arrayOf("Q", "W", "1", "q", "w"), symbolsFrom = 5, inRow = 2)
        assertEquals(listOf("Q", "W", "1", "q", "w"), PopupComponent.explicitLabels(qw) { map[it] ?: it }.toList())
        // committed as they are: shown so
        val asIs = KeyDef.Popup.Keyboard.Explicit(arrayOf("!", "%"), asIs = true)
        assertEquals(listOf("!", "%"), PopupComponent.explicitLabels(asIs) { map[it] ?: it }.toList())
    }

    // what a key's popup is made with goes on to the keys shown: 1's committed as they are, 九键's
    // symbols in rows of their own
    @Test
    fun aKeyboardsWayOfTypingAndItsSymbolsReachTheKeysShown() {
        var id = 1
        // each on a key of its own (a dismissal runs in the IME's scope, not here)
        fun shown(keyboard: KeyDef.Popup.Keyboard.Explicit): Pair<KeyAction?, Int> {
            send(PopupAction.ShowKeyboardAction(KeyRef.view(++id), keyboard, key))
            layOut()
            // the long press's keys, added last: a row a child
            val keys = popup.root.getChildAt(popup.root.childCount - 1) as PopupSceneView
            val rows = keys.scene!!.keys.map { it.bounds.top }.distinct().size
            return PopupAction.TriggerAction(KeyRef.view(id)).also { send(it) }.outAction to rows
        }
        assertEquals(KeyAction.CommitAction(".") to 1, shown(KeyDef.Popup.Keyboard.Explicit(arrayOf(".", "-"), asIs = true)))
        assertEquals(KeyAction.HostKeyAction(".") to 1, shown(KeyDef.Popup.Keyboard.Explicit(arrayOf(".", "-"))))
        assertEquals(2, shown(KeyDef.Popup.Keyboard.Explicit(arrayOf("2", "a", "!"), symbolsFrom = 2)).second)
        // committed as they are, none can be set apart to be mapped
        assertThrows(IllegalArgumentException::class.java) {
            KeyDef.Popup.Keyboard.Explicit(arrayOf("a", "!"), symbolsFrom = 1, asIs = true)
        }
    }
}
