/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardCanvasViewTest
import org.fcitx.fcitx5.android.input.picker.PickerData
import org.fcitx.fcitx5.android.input.picker.PickerGridAdapter
import org.fcitx.fcitx5.android.input.picker.PickerGridView
import org.fcitx.fcitx5.android.input.picker.PickerPolicy
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.layout.Text
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The picker's cells as one-key scenes (060-render-p2 S6, its review): every 颜文字 whole in its cell at
 * 360 and 393 dp (rules B §8a), an emoji centred in its own, a reused cell's words and what a screen
 * reader reads both the new item's, and a long press's popup over the face drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class PickerSceneCellTest {

    @get:Rule
    val prefs = PrefsRule()

    @Before
    fun canvasOn() = prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)

    private val measurer by lazy { AndroidTextMeasurer(SceneFonts(ThemeManager.shownLook(ThemeManager.activeTheme))) }

    // the bar's 表情 button opens the panel last shown: the 颜文字's own switch key (表情 too) back to the emoji
    private fun KeyboardHarness.emojiPanel() {
        tap(described(service.getString(R.string.a11y_key_emoji)))
        relayout()
        if (keys().none { it.label == ":-)" }) {
            tap(keys().first { it.description == service.getString(R.string.a11y_key_emoji) })
            relayout()
        }
    }

    private fun KeyboardHarness.emoticons() {
        emojiPanel()
        tap(key(":-)"))
        relayout()
    }

    private fun KeyboardHarness.grid() = KeyboardHarness.find<PickerGridView>(inputView)!!

    private fun KeyboardHarness.shownCells(): List<SceneKeyCell> =
        KeyboardHarness.all(grid()).filterIsInstance<SceneKeyCell>().filter(::shown).toList()

    private fun KeyboardHarness.scrolledTo(label: String): SceneKeyCell {
        repeat(60) {
            shownCells().firstOrNull { it.keyNode?.label == label }?.let { return it }
            grid().scrollBy(0, grid().height / 3)
            relayout()
        }
        error("no $label in the grid")
    }

    // the label's ink across, measured as the scene measures it
    private fun ink(cell: SceneKeyCell): Pair<Double, Double> {
        val t = cell.keyNode!!.ops(false).filterIsInstance<Op.Text>().single()
        val m = measurer.measure(t.text, t.font, t.weight, t.size)
        return (t.x + m.ink.left * t.scaleX) to (t.x + m.ink.right * t.scaleX)
    }

    // outside the cell is cut off; half a px of rounding allowed
    private fun outside(cell: SceneKeyCell): String? {
        val (l, r) = ink(cell)
        return if (l < -HALF_PX || r > cell.width + HALF_PX) "${cell.keyNode?.label} ink $l..$r in 0..${cell.width}" else null
    }

    private fun everyEmoticonWhole(width: String) {
        RuntimeEnvironment.setQualifiers(width)
        // the width put back after: a later test in this sandbox starts at the class's
        try {
            everyEmoticonWholeAt()
        } finally {
            RuntimeEnvironment.setQualifiers(KeyboardCanvasViewTest.PORTRAIT)
        }
    }

    private fun everyEmoticonWholeAt() {
        KeyboardHarness().start().use { h ->
            h.emoticons()
            val cell = h.shownCells().first()
            val base = cell.attrs
            val all = PickerData.Emoticon.flatMap { it.second.toList() }
            assertTrue(all.size > 200)
            val cut = all.mapNotNull { e ->
                cell.attrs = base.copy(label = Text.Literal(e), a11y = Text.Literal(e))
                outside(cell)
            }
            assertEquals(emptyList<String>(), cut)
        }
    }

    @Test
    fun everyEmoticonWholeInItsCellAt360dp() = everyEmoticonWhole("+w360dp")

    @Test
    fun everyEmoticonWholeInItsCellAt393dp() = everyEmoticonWhole("+w393dp")

    @Test
    fun aLongEmoticonShownWholeAndReadAsItIs() = KeyboardHarness().start().use { h ->
        h.emoticons()
        val long = "(｡>﹏<｡)ﾉﾞ✧*。"
        val cell = h.scrolledTo(long)
        assertEquals(null, outside(cell))
        assertEquals(long, cell.contentDescription)
        h.tap(cell)
        assertEquals(long, h.field.text.toString())
    }

    @Test
    fun anEmojiCentredInItsCell() = KeyboardHarness().start().use { h ->
        h.emojiPanel()
        val cell = h.scrolledTo("😀")
        assertEquals(null, outside(cell))
        val (l, r) = ink(cell)
        assertTrue("${(l + r) / 2} in ${cell.width}", abs((l + r) / 2 - cell.width / 2.0) < 1.5)
        assertEquals("😀", cell.contentDescription)
    }

    @Test
    fun aReusedCellShowsAndReadsTheNewItem() = KeyboardHarness().start().use { h ->
        h.emoticons()
        val before = h.shownCells().associateWith { it.keyNode!!.label }
        repeat(4) {
            h.grid().scrollBy(0, h.grid().height)
            h.relayout()
        }
        val all = PickerData.Emoticon.flatMap { it.second.toList() }.toSet()
        val reused = h.shownCells().filter { it in before && it.keyNode!!.label != before[it] }
        assertTrue("no cell reused for another item", reused.isNotEmpty())
        for (c in reused) {
            val label = c.keyNode!!.label!!
            assertTrue(label, label in all)
            assertEquals(label, c.contentDescription)
        }
    }

    @Test
    fun aLongPressShowsThePopupOverTheFaceDrawn() {
        val actions = mutableListOf<PopupAction>()
        val grid = PickerGridView(RuntimeEnvironment.getApplication(), ThemePreset.PixelDark, PickerGridView.Density.Medium)
        val policy = object : PickerPolicy {
            override fun filter(raw: String) = true
            override fun transform(raw: String) = raw
            override fun popup(raw: String): KeyDef.Popup.Keyboard = KeyDef.Popup.Keyboard.Explicit(arrayOf("a", "b"))
            override fun invalidateKey(): Any = 0
        }
        val adapter = PickerGridAdapter(
            grid, KeyActionListener { _, _ -> }, PopupActionListener { actions += it },
            listOf(PickerData.Category("a") to arrayOf("x")), "PickerSceneCellTest", bordered = true, policy = policy,
        ).apply { rebuild() }
        // nothing used yet: the header, then "x"
        val holder = adapter.onCreateViewHolder(grid, adapter.getItemViewType(1))
        adapter.onBindViewHolder(holder, 1)
        val cell = holder.itemView as SceneKeyCell
        // in a parent: the listener says not handled, and the view then asks its parent for a context menu
        android.widget.FrameLayout(RuntimeEnvironment.getApplication()).addView(cell)
        cell.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        cell.layout(0, 0, W, H)
        cell.performLongClick()
        // the face as drawn: its fill, a margin in from the cell's sides (bordered)
        val face = cell.keyNode!!.ops(false).filterIsInstance<Op.Fill>().first().shape.bounds
        assertTrue("$face", face.left > 0 && face.right < W)
        val shown = actions.filterIsInstance<PopupAction.ShowKeyboardAction>().single()
        assertEquals(Rect(face.left.roundToInt(), 0, face.right.roundToInt(), H), shown.bounds)
    }

    companion object {
        private const val HALF_PX = 0.5
        private const val W = 121
        private const val H = 150
    }
}
