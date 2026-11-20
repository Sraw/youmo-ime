/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter

/** Chooses [Penalties.fuzzy] and [Penalties.typo] on [Halves.TUNE], reports on [Halves.HELD_OUT]; a point must beat the default by [MARGIN]. */
class Tuning(private val load: () -> PinyinData, private val clean: List<Sample>, slips: List<Sample>, private val threads: Int = 1) {

    /** On [data] alone: one thread, as data is not to be shared between threads. */
    constructor(data: PinyinData, clean: List<Sample>, slips: List<Sample>) : this({ data }, clean, slips)

    /** Per run, [hits] of [sizes] samples; a run with none has no rate and does not count. */
    data class Point(val penalties: Penalties, val hits: List<Int>, val sizes: List<Int>) {
        val rates: List<Double?> get() = hits.indices.map { if (sizes[it] == 0) null else hits[it].toDouble() / sizes[it] }
        val mean: Double? get() = rates.filterNotNull().takeIf { it.isNotEmpty() }?.average()
    }

    private val fuzzy = slips.filter { it.tag.startsWith(SlipSet.FUZZY) }
    private val typos = slips.filter { it.tag.startsWith(SlipSet.TYPO) }
    private val runs = listOf(clean to false, clean to true, fuzzy to true, typos to false)
    private val keys = slips.filter { it.tag == SlipSet.KEY }

    /** What one thread measures with: its own data and segmenters. */
    private inner class Runner(private val data: PinyinData) {
        private val plain = PinyinSegmenter()
        private val allFuzzy = PinyinSegmenter(Fuzzy.entries.toSet())
        val slipping = PinyinSegmenter(neighbours = true)

        fun point(penalties: Penalties, half: String): Point {
            val hits = ArrayList<Int>()
            val sizes = ArrayList<Int>()
            for ((samples, fuzzyOn) in runs) {
                val selected = Halves.select(samples, half)
                val run = PinyinRun(data, if (fuzzyOn) allFuzzy else plain, penalties)
                hits += selected.count { run.candidates(it.input).firstOrNull() == it.expected }
                sizes += selected.size
            }
            return Point(penalties, hits, sizes)
        }

        fun keyPoint(penalties: Penalties, half: String, read: Boolean): Point {
            val run = PinyinRun(data, if (read) slipping else plain, penalties)
            val sets = listOf(clean, keys).map { Halves.select(it, half) }
            return Point(penalties, sets.map { set -> set.count { run.candidates(it.input).firstOrNull() == it.expected } }, sets.map { it.size })
        }
    }

    private val runner by lazy { Runner(load()) }

    /** What a place-less slip costs on the segmenter [measureKeys] reads with: the tune report's label. */
    val slipFixed: Float get() = runner.slipping.slipFixed

    fun measure(penalties: Penalties, half: String): Point = runner.point(penalties, half)

    /** Top1 of clean input and of input with a key slipped ([KEY_COLUMNS]), slips read or not. */
    fun measureKeys(penalties: Penalties, half: String, read: Boolean): Point = runner.keyPoint(penalties, half, read)

    /** [Penalties.neighbour], on top of a slip's own cost, at each point of [grid], slips read, in the grid's order. */
    fun searchKeys(grid: List<Float> = KEY_GRID, half: String = Halves.TUNE): List<Point> =
        dealt(grid, threads, { Runner(load()) }) { hand -> hand.map { keyPoint(Penalties().copy(neighbour = it), half, read = true) } }

    /** Of [points], the one reading most slips right while keeping clean input within [MARGIN] of [off]; null if none. */
    fun chooseKeys(points: List<Point>, off: Point): Point? {
        val floor = (off.rates[0] ?: 0.0) - MARGIN
        return points.filter { (it.rates[0] ?: 0.0) >= floor }
            .sortedWith(
                compareByDescending<Point> { it.rates[1] ?: 0.0 }
                    .thenByDescending { it.rates[0] ?: 0.0 }
                    .thenBy { it.penalties.neighbour },
            )
            .firstOrNull()
    }

    /** Every point of the grid on [half], best first; ties keep the grid's order. */
    fun search(grid: List<Float> = GRID, half: String = Halves.TUNE): List<Point> {
        val base = Penalties()
        val penalties = grid.flatMap { f -> grid.map { t -> base.copy(fuzzy = f, typo = t) } }
        return dealt(penalties, threads, { Runner(load()) }) { hand -> hand.map { point(it, half) } }
            .sortedByDescending { it.mean ?: -1.0 }
    }

    /** The best of [points] if it beats [default] by more than [MARGIN], else [default]. */
    fun choose(points: List<Point>, default: Point): Point =
        points.firstOrNull()?.takeIf { (it.mean ?: 0.0) - (default.mean ?: 0.0) > MARGIN } ?: default

    companion object {
        val GRID = listOf(-0.5f, -1f, -1.5f, -2f, -2.5f, -3f, -4f)
        val COLUMNS = listOf("clean", "clean+fuzzy", "fuzzy", "typo")
        val KEY_GRID = listOf(0f, -0.5f, -1f, -1.5f, -2f, -3f, -4f, -6f)
        val KEY_COLUMNS = listOf("clean", "key")

        /** Half a point of mean top1: about a sample and a half of each run on a half. */
        const val MARGIN = 0.005
    }
}
