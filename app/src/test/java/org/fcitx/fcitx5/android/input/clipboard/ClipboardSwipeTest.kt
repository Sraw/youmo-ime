/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** uat/05-toolbar.md's TB-49 at the edges: the grid's auto-scroll, the pieces replaced mid-sweep, the cut note. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ClipboardSwipeTest {

    private fun grid(text: String, cut: Boolean = false): ClipboardChunksUi {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ui = ClipboardChunksUi(activity, ThemePreset.PixelLight, 8f, text, cut) {}
        activity.setContentView(ui.root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEIGHT))
        ui.show(ClipboardChunks.chunks(text))
        idle(0)
        return ui
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private var downAt = 0L
    private var at = 0f to 0f

    private fun ClipboardChunksUi.finger(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downAt = now
        val e = MotionEvent.obtain(downAt, now, action, x, y, 0)
        root.dispatchTouchEvent(e)
        e.recycle()
        at = x to y
    }

    private fun ClipboardChunksUi.moveTo(x: Float, y: Float, steps: Int = 6) {
        val (x0, y0) = at
        for (i in 1..steps) {
            idle(16)
            finger(MotionEvent.ACTION_MOVE, x0 + (x - x0) * i / steps, y0 + (y - y0) * i / steps)
        }
    }

    // the middle of the cell at adapter [position], in the grid's own coordinates
    private fun ClipboardChunksUi.over(position: Int): Pair<Float, Float> =
        root.findViewHolderForAdapterPosition(position)!!.itemView.let { (it.left + it.right) / 2f to (it.top + it.bottom) / 2f }

    private fun ClipboardChunksUi.pickedTexts() = picked.map { it.text }

    // letters only: a digit would be a piece of its own
    private fun word(i: Int) = "w" + ('a' + i / 26) + ('a' + i % 26)

    private val long = (0 until 300).joinToString(" ", transform = ::word)

    // frames one at a time: the auto-scroll asks for the next as each runs, and the paused looper's clock
    // never gets past them (an idle never returns)
    private fun frames() = repeat(FRAMES) { shadowOf(Looper.getMainLooper()).runOneTask() }

    @Test
    fun heldAtTheBottomTheGridScrollsAndTheRangeFollows() = assertRows(
        "held in the bottom band" to { heldAt(HEIGHT - 4f) },
        // over no cell: the edge row stands in for the finger
        "dragged out past the panel's bottom" to { heldAt(HEIGHT + 40f) },
    )

    private fun heldAt(fingerY: Float) {
        val ui = grid(long)
        val lastShown = (0 until ui.root.childCount).maxOf { ui.root.getChildAdapterPosition(ui.root.getChildAt(it)) }
        val (x, y) = ui.over(1)
        ui.finger(MotionEvent.ACTION_DOWN, x, y)
        // lifted whatever fails, so no scroll frame is left posted for the next row
        try {
            ui.over(3).let { (tx, ty) -> ui.moveTo(tx, ty) }
            // one move: an idle while the grid scrolls never returns
            ui.moveTo(at.first, fingerY, steps = 1)
            frames()
            val offset = ui.root.computeVerticalScrollOffset()
            val size = ui.picked.size
            assertTrue("scrolled", offset > 0)
            // from w1 on, past the last piece shown when the finger came down
            assertEquals(word(1), ui.pickedTexts().first())
            assertTrue(ui.pickedTexts().toString(), size > lastShown)
            frames()
            assertTrue("scrolled on", ui.root.computeVerticalScrollOffset() > offset)
            assertTrue(ui.pickedTexts().toString(), ui.picked.size > size)
        } finally {
            ui.finger(MotionEvent.ACTION_UP, at.first, at.second)
        }
    }

    @Test
    fun aSecondFingerEndsTheSweep() {
        val ui = grid(long)
        val (x, y) = ui.over(0)
        ui.finger(MotionEvent.ACTION_DOWN, x, y)
        ui.over(2).let { (tx, ty) -> ui.moveTo(tx, ty) }
        val props = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = at.first; this.y = at.second },
            MotionEvent.PointerCoords().apply { this.x = x; this.y = HEIGHT - 20f },
        )
        val e = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        ui.root.dispatchTouchEvent(e)
        e.recycle()
        ui.over(4).let { (tx, ty) -> ui.moveTo(tx, ty) }
        ui.finger(MotionEvent.ACTION_UP, at.first, at.second)
        assertEquals((0..2).map(::word), ui.pickedTexts())
    }

    @Test
    fun aSidewaysSweepAlongTheTopRowDoesNotScroll() {
        val ui = grid(long)
        // scrolled down a way, a row's first piece at the top: the grid could scroll either way
        val rowStart = (1 until ui.root.childCount).first { ui.over(it).first < ui.over(it - 1).first && ui.over(it).second > HEIGHT / 2 }
        ui.root.scrollBy(0, ui.root.findViewHolderForAdapterPosition(rowStart)!!.itemView.top - ui.root.paddingTop)
        idle(0)
        val offset = ui.root.computeVerticalScrollOffset()
        val (x, y) = ui.over(rowStart)
        ui.finger(MotionEvent.ACTION_DOWN, x, y)
        ui.over(rowStart + 3).let { (tx, ty) -> ui.moveTo(tx, ty) }
        frames()
        ui.finger(MotionEvent.ACTION_UP, at.first, at.second)
        assertEquals(offset, ui.root.computeVerticalScrollOffset())
        assertEquals((rowStart..rowStart + 3).map(::word), ui.pickedTexts())
    }

    @Test
    fun thePiecesReplacedMidSweepEndIt() {
        val ui = grid(long)
        val (x, y) = ui.over(0)
        ui.finger(MotionEvent.ACTION_DOWN, x, y)
        ui.over(2).let { (tx, ty) -> ui.moveTo(tx, ty) }
        assertEquals((0..2).map(::word), ui.pickedTexts())
        // the model's split coming in: the next move changes nothing
        ui.show(ClipboardChunks.chunks(long))
        idle(0)
        ui.over(4).let { (tx, ty) -> ui.moveTo(tx, ty) }
        ui.finger(MotionEvent.ACTION_UP, at.first, at.second)
        assertEquals((0..2).map(::word), ui.pickedTexts())
    }

    @Test
    fun aSweepFromTheCutNotePicksNothing() {
        val ui = grid("a b c", cut = true)
        val (x, y) = ui.over(3)
        ui.finger(MotionEvent.ACTION_DOWN, x, y)
        ui.moveTo(x - 60f, y)
        ui.moveTo(x + 60f, y)
        ui.finger(MotionEvent.ACTION_UP, at.first, at.second)
        assertEquals(emptyList<String>(), ui.pickedTexts())
    }

    private companion object {
        const val HEIGHT = 300
        const val FRAMES = 60
    }
}
