/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ShapeDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.input.bar.CandidateSpacing
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.random.Random

/**
 * The bar's row lays its candidates out to the pixel where FlexboxLayoutManager, set up as the bar had it
 * (design 060-perf-cands), did: the same count shown, the same bounds, in every fill style, the English
 * slots, both directions, and as the words change in one bar.
 */
@RunWith(RobolectricTestRunner::class)
class CandidateRowLayoutManagerTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    /** A word of [want] px at most, as wide as allowed when less. */
    private class Word(ctx: Context) : View(ctx) {
        var want = 0
            set(value) {
                field = value
                requestLayout()
            }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                resolveSize(maxOf(want, minimumWidth), widthMeasureSpec),
                resolveSize(10, heightMeasureSpec)
            )
        }
    }

    private enum class Fill { Never, Auto, Always, English }

    private class Bar(val view: RecyclerView, val shown: () -> Int, val set: (List<Int>, Int) -> Unit, val settle: () -> Unit = {})

    private fun adapter(widths: () -> List<Int>, bind: (View) -> Unit) = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = widths().size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(Word(parent.context).apply {
                minimumWidth = MIN
                layoutParams = FlexboxLayoutManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as Word).want = widths()[position]
            bind(holder.itemView)
        }
    }

    private fun recycler(rtl: Boolean, lm: RecyclerView.LayoutManager, a: RecyclerView.Adapter<*>) = RecyclerView(ctx).apply {
        layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        itemAnimator = null
        layoutManager = lm
        adapter = a
        addItemDecoration(FlexboxVerticalDecoration(ShapeDrawable().apply { intrinsicWidth = DIVIDER }, GAP))
    }

    /**
     * HorizontalCandidateComponent before this change: flexGrow and minWidth on each holder; in AutoFillWidth
     * stretched, then, when every word was shown, laid out again at flexGrow 0 ([Bar.settle]: what its
     * second pass in onLayoutCompleted asked for, and what the real bar shows, BarUatTest MN-23).
     */
    private fun flexbox(fill: Fill, rtl: Boolean): Bar {
        var widths = listOf<Int>()
        var total = 0
        var minWidth = 0
        var grow = 1f
        var shown = -1
        lateinit var rv: RecyclerView
        val a = adapter({ widths }) {
            // Flexbox's wrap check reads the insets of the view's last layout, none on a new view (a word
            // then fit without its divider's room, depending on which holder it got): given here, as the
            // new row always counts them (design 060-perf-cands, 待定)
            insets.get(it.layoutParams).let { r -> (r as Rect).set(if (rtl) DIVIDER + GAP else 0, 0, if (rtl) 0 else DIVIDER + GAP, 0) }
            it.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                this.minWidth = minWidth
                flexGrow = grow
            }
        }
        val lm = object : FlexboxLayoutManager(ctx) {
            override fun canScrollVertically() = false
            override fun canScrollHorizontally() = false
            override fun onLayoutCompleted(state: RecyclerView.State) {
                super.onLayoutCompleted(state)
                shown = childCount
            }
        }
        rv = recycler(rtl, lm, a)
        return Bar(rv, { shown }, { w, t ->
            widths = w
            total = t
            minWidth = 0
            grow = 1f
            when (fill) {
                Fill.English -> {
                    minWidth = rv.width / 3 - DIVIDER - GAP
                    grow = if (w.size < 3) 0f else 1f
                }
                Fill.Never -> grow = 0f
                Fill.Auto, Fill.Always -> Unit
            }
            @Suppress("NotifyDataSetChanged")
            a.notifyDataSetChanged()
        }, {
            if (fill == Fill.Auto && !CandidateSpacing.stretches(shown, total)) {
                grow = 0f
                @Suppress("NotifyDataSetChanged")
                a.notifyDataSetChanged()
            }
        })
    }

    private fun row(fill: Fill, rtl: Boolean): Bar {
        var widths = listOf<Int>()
        var shown = -1
        val a = adapter({ widths }) {}
        val lm = CandidateRowLayoutManager().apply { onLaidOut = { shown = it } }
        val rv = recycler(rtl, lm, a)
        return Bar(rv, { shown }, { w, t ->
            widths = w
            lm.minWidth = 0
            when (fill) {
                Fill.English -> {
                    lm.minWidth = rv.width / 3 - DIVIDER - GAP
                    val g = w.size >= 3
                    lm.grows = { g }
                }
                Fill.Never -> lm.grows = { false }
                Fill.Auto -> lm.grows = { CandidateSpacing.stretches(it, t) }
                Fill.Always -> lm.grows = { true }
            }
            @Suppress("NotifyDataSetChanged")
            a.notifyDataSetChanged()
        })
    }

    private fun Bar.layOut(width: Int) {
        // a few passes: Flexbox's second pass waits for the layout its params asked for
        repeat(3) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, HEIGHT)
        }
    }

    private fun Bar.bounds(): List<Rect> = (0 until shown()).map {
        val v = view.getChildAt(it)
        Rect(v.left, v.top, v.right, v.bottom)
    }

    @Test
    fun sameAsFlexboxEverywhere() {
        val random = Random(60)
        for (fill in Fill.entries) for (rtl in listOf(false, true)) {
            val old = flexbox(fill, rtl)
            val new = row(fill, rtl)
            for (width in listOf(301, 720, 1079)) {
                old.layOut(width)
                new.layOut(width)
                repeat(40) { round ->
                    val n = random.nextInt(1, 12)
                    val widths = List(n) { random.nextInt(1, if (round % 7 == 0) width + 50 else 160) }
                    val total = when (random.nextInt(3)) {
                        0 -> -1
                        1 -> n
                        else -> n + random.nextInt(1, 30)
                    }
                    old.set(widths, total)
                    new.set(widths, total)
                    old.layOut(width)
                    old.settle()
                    old.layOut(width)
                    new.layOut(width)
                    val what = "$fill rtl $rtl width $width words $widths total $total"
                    assertEquals(what, old.shown(), new.shown())
                    assertEquals(what, old.bounds(), new.bounds())
                }
            }
        }
    }

    companion object {
        private val insets = RecyclerView.LayoutParams::class.java.getDeclaredField("mDecorInsets").apply { isAccessible = true }

        const val MIN = 40
        const val DIVIDER = 2
        const val GAP = 7
        const val HEIGHT = 50
    }
}
