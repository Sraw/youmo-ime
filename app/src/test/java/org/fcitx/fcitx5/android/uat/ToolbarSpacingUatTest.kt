/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ToolbarButtonLooks
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The bar in slots of one width (uat/05 TB-02), 手写 greyed in English (TB-67); the floating keyboard's (FloatingUatTest, TB-22). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi", shadows = [HideRecordingShadow::class])
class ToolbarSpacingUatTest {

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness ?: KeyboardHarness().also { harness = it }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        harness?.close()
    }

    private fun s(id: Int) = h.service.getString(id)

    // the bar in slots of one width (the user's call, 2026-11-15): shut, the menu key in the first, the right's in the
    // last; open, the toolbar's and ⋯ in the slots from the menu key on, here every slot filled, no gap; the menu key
    // and the right's where they were and as wide, open or shut
    @Test
    @Uat("TB-02")
    fun theBarsButtonsAreInSlotsOfOneWidthOpenOrShut() {
        h.start()
        val shut = barCells(h)
        assertEquals(listOf(R.string.expand_toolbar, R.string.hide_keyboard).map(::s), listOf(shut.first(), shut.last()).map { it.first })
        assertOnSlots(shut)
        h.tap(h.described(s(R.string.expand_toolbar)))
        val open = barCells(h)
        assertEquals("ten slots, all filled: $open", 10, open.size)
        assertOnSlots(open)
        // whole px slots (IdleUi.sizeCells): the bar's width over them leaves under a px a slot, all of it between ⋯ and the right's
        val roundingPx = open.size
        assertEvenlyApart(open, bigGapAfter = open.indexOfFirst { it.first == s(R.string.status_area) }, gap = roundingPx)
        h.tap(h.described(s(R.string.hide_toolbar)))
        assertTheEndsStay(h, "393 dp")
    }

    // the menu key and the right's never move nor change width as the toolbar opens and shuts (the user's call,
    // 2026-11-15), on the wide bar of a phone held sideways too, the slots the toolbar has nothing for after ⋯
    @Test
    @Uat("TB-02")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun theBarsEndsStayAsTheToolbarOpensLandscape() {
        h.start()
        assertTheEndsStay(h, "851 dp")
        h.tap(h.described(s(R.string.expand_toolbar)))
        val open = barCells(h)
        assertOnSlots(open)
        assertEvenlyApart(open, bigGapAfter = open.indexOfFirst { it.first == s(R.string.status_area) })
    }

    // 手写 in English greyed in its place, not taken off: nothing on the bar moves as 中/英 switch, open or shut; a tap
    // says why, and TalkBack does (the user's call, 2026-11-15)
    @Test
    @Uat("TB-67")
    fun englishGreysHandwritingInItsPlaceAndNothingMoves() {
        h.start()
        val shutChinese = barCells(h)
        h.tap(h.described(s(R.string.expand_toolbar)))
        val openChinese = barCells(h)
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        h.relayout()
        val greyed = h.service.getString(R.string.handwriting_unavailable, s(R.string.handwriting))
        val openEnglish = barCells(h)
        assertEquals(openChinese.map { it.second }, openEnglish.map { it.second })
        assertEquals(openChinese.map { it.first }.map { if (it == s(R.string.handwriting)) greyed else it }, openEnglish.map { it.first })
        val button = h.described(greyed) as ToolButton
        assertEquals(0.38f, button.alpha, 0.01f)
        h.tap(button)
        h.idle()
        val popup = Shadows.shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        val said = popup?.let { KeyboardHarness.all(it.contentView).filterIsInstance<TextView>().map { t -> t.text.toString() }.toList() }
        assertTrue("$said", said.orEmpty().contains(s(R.string.handwriting_not_here)))
        assertFalse("not turned on", AppPrefs.getInstance().internal.handwritingOpen.getValue())
        h.tap(h.described(s(R.string.hide_toolbar)))
        assertEquals(shutChinese.map { it.second }, barCells(h).map { it.second })
        // back in 中文: as it was
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
        h.relayout()
        assertEquals(shutChinese, barCells(h))
        assertEquals(1f, (h.described(s(R.string.handwriting)) as ToolButton).alpha, 0.01f)
    }

    companion object {
        /** The bar's buttons in view, left to right: what each is (its description) and where. */
        fun barCells(h: KeyboardHarness): List<Pair<String, Rect>> {
            val bar = h.box(h.bar)
            return KeyboardHarness.all(h.bar).filter { it is ToolButton && h.shown(it) }.map { it.contentDescription.toString() to h.box(it) }
                .filter { (_, b) -> b.left >= bar.left - 1 && b.right <= bar.right + 1 }.sortedBy { it.second.left }.toList()
        }

        /** The bar's right in a new install's order: 表情, 手写, 语音, 收起. */
        val RIGHT = listOf(ToolbarButton.Emoji, ToolbarButton.Handwriting, ToolbarButton.Voice, ToolbarButton.Hide)

        /**
         * The menu key's and [right]'s boxes the same with the toolbar shut, open and shut again (the user's call,
         * 2026-11-15): none of them moves or changes width, and every one of [right] is there. The toolbar shut before
         * and after.
         */
        fun assertTheEndsStay(h: KeyboardHarness, at: String, right: List<ToolbarButton> = RIGHT) {
            val described = right.map { h.service.getString(ToolbarButtonLooks.of(it).label) }
            fun ends(): List<Rect> {
                h.relayout()
                val cells = barCells(h)
                return listOf(cells.first().second) + described.map { d -> checkNotNull(cells.firstOrNull { it.first == d }) { "$at: no $d in $cells" }.second }
            }
            val shut = ends()
            h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
            val open = ends()
            assertEquals("$at: shut, then open, the menu key and $described", shut, open)
            h.tap(h.described(h.service.getString(R.string.hide_toolbar)))
            assertEquals("$at: shut again", shut, ends())
        }

        /**
         * [cells], the bar's buttons left to right, in its slots: all as wide as the menu key (the first), the ones
         * from the menu key on a slot after another, the right's ([right] of them, the last) a slot before another
         * from the bar's end.
         */
        fun assertOnSlots(cells: List<Pair<String, Rect>>, right: Int = RIGHT.size) {
            val w = cells.first().second.width()
            val start = cells.first().second.left
            val end = cells.last().second.right
            cells.forEachIndexed { i, (d, b) ->
                assertTrue("$d $b not ${w}px wide in $cells", kotlin.math.abs(b.width() - w) <= 1)
                val j = cells.size - 1 - i
                if (j < right) assertTrue("$d $b not in the slot ${j + 1} from the end in $cells", kotlin.math.abs(end - j * w - b.right) <= j + 1)
                else assertTrue("$d $b not in the slot $i in $cells", kotlin.math.abs(start + i * w - b.left) <= i + 1)
            }
        }

        /** [d] of the toolbar's opened: its button where the bar has a slot for it, else its entry in ⋯'s panel. */
        fun openFromToolbar(h: KeyboardHarness, d: Int) {
            val name = h.service.getString(d)
            val on = KeyboardHarness.all(h.bar).firstOrNull { h.shown(it) && it.contentDescription?.toString() == name }
            if (on != null) return h.tap(on)
            assertTrue("$name in ⋯'s panel", name in openMore(h))
            val entry = KeyboardHarness.all(h.inputView).first { h.shown(it) && it is TextView && it.text.toString() == name }
            h.tap(generateSequence(entry) { it.parent as? View }.first { it.hasOnClickListeners() })
        }

        /** ⋯ tapped: its panel's entries, in order. */
        fun openMore(h: KeyboardHarness): List<String> {
            h.tap(h.described(h.service.getString(R.string.status_area)))
            h.waitFor("⋯'s panel") { h.relayout(); panelEntries(h).isNotEmpty() }
            return panelEntries(h)
        }

        /** ⋯'s panel's entries in view, in order. */
        fun panelEntries(h: KeyboardHarness): List<String> {
            val grid = KeyboardHarness.all(h.inputView).firstOrNull { h.shown(it) && it is RecyclerView && it.layoutManager is GridLayoutManager } ?: return emptyList()
            return KeyboardHarness.all(grid).filter { h.shown(it) && it is TextView }.map { (it as TextView).text.toString() }.toList()
        }

        /** [cells] all as wide, each right beside the last but after [bigGapAfter]'s (the room past them all), at most [gap] px there. */
        fun assertEvenlyApart(cells: List<Pair<String, Rect>>, bigGapAfter: Int, gap: Int = Int.MAX_VALUE) {
            val widths = cells.map { it.second.width() }
            assertTrue("$cells: as wide", widths.max() - widths.min() <= 1)
            for (i in 1 until cells.size) {
                val between = cells[i].second.left - cells[i - 1].second.right
                val most = if (i - 1 == bigGapAfter) gap else 1
                assertTrue("${cells[i - 1].first} to ${cells[i].first}: $between px apart (at most $most) in $cells", between in -1..most)
            }
        }
    }
}
