/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Rect
import android.graphics.drawable.ShapeDrawable
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⋯ and its panel for the toolbar: the menu key as ⋯ where the toolbar has nothing to show (uat/05 TB-69), 手写 in the
 * panel as on the bar (TB-67), the narrowest bar's middle (TB-68).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi", shadows = [HideRecordingShadow::class])
class ToolbarMoreUatTest {

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness ?: KeyboardHarness().also { harness = it }

    @get:Rule
    val prefs = PrefsRule()

    // the app's recognizer is native, not on the JVM: one that loads and reads nothing, so 手写 stays on
    private val reader = HandwritingRecognition.shared.reader.also {
        HandwritingRecognition.shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = true
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>): InkReading? = null
        }
    }

    @After
    fun close() {
        harness?.close()
        HandwritingRecognition.shared.reader = reader
    }

    private fun s(id: Int) = h.service.getString(id)

    private val internal get() = AppPrefs.getInstance().internal

    private fun has(d: Int) = KeyboardHarness.all(h.inputView).any { h.shown(it) && it.contentDescription?.toString() == s(d) }

    private fun panelOpen() = has(R.string.back_to_keyboard)

    private fun backToKeyboard() {
        h.tap(h.described(s(R.string.back_to_keyboard)))
        h.after(1000)
        h.relayout()
    }

    // FB11 (the user's call, 2026-11-15): none of the toolbar's to show, the menu key is ⋯ itself, a tap opening its
    // panel, over a clipboard suggestion too; never opened, 默认展开工具栏 or not; a greyed 手写 counts as one to show
    @Test
    @Uat("TB-69")
    fun aToolbarWithNothingToShowHasMoreForTheMenuKey() {
        prefs.set(internal.toolbarButtons, "-undo,-redo,-cursor_move,-clipboard,|,emoji,handwriting,voice,hide")
        prefs.set(AppPrefs.getInstance().keyboard.expandToolbarByDefault, true)
        h.start()
        val cells = ToolbarSpacingUatTest.barCells(h)
        assertEquals(cells.toString(), s(R.string.toolbar_more), cells.first().first)
        assertFalse("never opened", has(R.string.hide_toolbar) || has(R.string.expand_toolbar))
        ToolbarSpacingUatTest.assertOnSlots(cells)
        h.tap(h.described(s(R.string.toolbar_more)))
        h.waitFor("⋯'s panel") { h.relayout(); panelOpen() }
        backToKeyboard()
        // a clip offered: the tap still the panel's
        h.copied("a clip to paste")
        h.waitFor("the clip offered") { h.relayout(); KeyboardHarness.all(h.bar).any { h.shown(it) && it is TextView && it.text.toString() == "a clip to paste" } }
        h.tap(h.described(s(R.string.toolbar_more)))
        h.waitFor("⋯'s panel over the clip") { h.relayout(); panelOpen() }
        backToKeyboard()
        assertFalse(has(R.string.hide_toolbar))
        // 手写 ticked on the toolbar: in English greyed but there, so the menu key again (the clip still offered: shut)
        prefs.set(internal.toolbarButtons, "handwriting,|,emoji,voice,hide")
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        h.relayout()
        assertFalse(has(R.string.toolbar_more))
        h.tap(h.described(s(R.string.expand_toolbar)))
        h.relayout()
        assertTrue(has(R.string.hide_toolbar))
        val greyed = h.described(h.service.getString(R.string.handwriting_unavailable, s(R.string.handwriting))) as ToolButton
        assertEquals(0.38f, greyed.alpha, 0.01f)
    }

    // 手写 with no slot on the bar, in ⋯'s panel: lit while on, greyed and said so where it does not write, as on the bar
    @Test
    @Uat("TB-67")
    fun handwritingInMoresPanelIsGreyedAndLitAsOnTheBar() {
        // ten slots: the menu key, three at the right, ⋯, five of the toolbar's; 手写 the sixth, in the panel
        prefs.set(internal.toolbarButtons, "undo,redo,cursor_move,clipboard,quick_input,handwriting,|,emoji,voice,hide")
        prefs.set(internal.handwritingOpen, true)
        h.start()
        h.tap(h.described(s(R.string.expand_toolbar)))
        assertEquals(s(R.string.handwriting), ToolbarSpacingUatTest.openMore(h).first())
        val lit = entry(s(R.string.handwriting))
        assertEquals(1f, lit.alpha, 0.01f)
        assertEquals(ThemeManager.activeTheme.genericActiveBackgroundColor, background(lit))
        backToKeyboard()
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        h.relayout()
        val greyedName = h.service.getString(R.string.handwriting_unavailable, s(R.string.handwriting))
        assertEquals(greyedName, ToolbarSpacingUatTest.openMore(h).first())
        val greyed = entry(greyedName)
        assertEquals(0.38f, greyed.alpha, 0.01f)
        assertEquals(ThemeManager.activeTheme.keyBackgroundColor, background(greyed))
    }

    // the panel's cell named [label]
    private fun entry(label: String): View {
        val text = KeyboardHarness.all(h.inputView).first { h.shown(it) && it is TextView && it.text.toString() == label }
        return generateSequence(text) { it.parent as? View }.first { it.hasOnClickListeners() }
    }

    // the circle behind a panel cell's icon
    private fun background(cell: View): Int =
        KeyboardHarness.all(cell).firstNotNullOf { (it.background as? ShapeDrawable)?.paint?.color }

    // the floating keyboard at its narrowest (260 dp): about 74 dp between the menu key and the right's, a clipboard
    // suggestion and a sum's result each shortened there, over none of the buttons
    @Test
    @Uat("TB-68")
    fun theSmallestBarsMiddleHoldsAClipAndASum() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        prefs.set(internal.floatingPortraitWidthDp, 260f)
        h.start()
        val cells = ToolbarSpacingUatTest.barCells(h)
        val middle = Rect(cells.first().second.right, cells.first().second.top, cells[1].second.left, cells.first().second.bottom)
        assertTrue("the middle about 74 dp: $middle", middle.width() / h.service.resources.displayMetrics.density in 60f..90f)
        fun assertInMiddle(what: String, text: String) {
            val view = KeyboardHarness.all(h.bar).first { h.shown(it) && it is TextView && it.text.startsWith(text) } as TextView
            for (v in generateSequence<View>(view) { it.parent as? View }.takeWhile { it !== h.bar }.filter { it.hasOnClickListeners() } + view) {
                val b = h.box(v)
                assertTrue("$what $b in the middle $middle", b.left >= middle.left - 1 && b.right <= middle.right + 1)
            }
            assertTrue("$what shortened", (view.layout?.getEllipsisCount(0) ?: 0) > 0)
        }
        // the strip's text cut at its length, the rest of it shortened to the room
        val clip = "a clip far longer than the narrowest bar's"
        h.copied("$clip middle")
        h.waitFor("the clip offered") { h.relayout(); KeyboardHarness.all(h.bar).any { h.shown(it) && it is TextView && it.text.startsWith(clip) } }
        assertInMiddle("the clip", clip)
        assertEquals(cells, ToolbarSpacingUatTest.barCells(h))
        // a sum's long result, the clip let go (its strip goes as the sum's comes)
        h.field.setText("123456789*987654321")
        h.field.setSelection(19)
        h.idle()
        h.emit(HostEvent.Commit("=", -1))
        h.relayout()
        val sum = "= 1.21932631"
        h.waitFor("the sum offered") { h.relayout(); KeyboardHarness.all(h.bar).any { h.shown(it) && it is TextView && it.text.startsWith(sum) } }
        assertInMiddle("the sum", sum)
        assertEquals(cells, ToolbarSpacingUatTest.barCells(h))
    }
}
