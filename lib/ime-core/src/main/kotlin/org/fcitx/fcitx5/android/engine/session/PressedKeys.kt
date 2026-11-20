/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.Touch

/**
 * The keys pressed for each place of a [PinyinSession]'s input, kept in step with it: what learning files a pick
 * under. The input alone does not say: a taken syllable stands in it as letters, and a glide's letters are no keys.
 */
internal class PressedKeys {

    /** [length] places typed as [keys]; [multiTap], a letter 连按 chose; [at] and [touch], where on its key a single key was tapped (none for a run of several). */
    class Run(val length: Int, val keys: String, val glided: Boolean = false, val multiTap: Boolean = false, val at: Float = Float.NaN, val touch: Touch? = null)

    private val runs = ArrayList<Run>()

    fun clear() = runs.clear()

    fun runs(from: Int, to: Int): List<Run> {
        val (i, j) = indices(from, to)
        return runs.subList(i, j).toList()
    }

    /** [text] typed at [at], a key a place; a run of several there is split into a key a place first, as [delete] does. */
    fun insert(at: Int, text: String, glided: Boolean = false, tapAt: Float = Float.NaN, input: CharSequence? = null, letters: NineKeyLetters? = null, touch: Touch? = null) {
        if (input != null) {
            runAt(at)?.takeIf { (i, start) -> start < at && runs[i].length > 1 }?.let { (i, start) ->
                runs.addAll(i, split(runs.removeAt(i), start, input, letters) { true })
            }
        }
        var i = indexAt(at)
        val one = text.length == 1
        for (c in text) runs.add(i++, Run(1, keyOf(c), glided, at = if (one) tapAt else Float.NaN, touch = touch.takeIf { one }))
    }

    /** Deletes place [at]; the rest of a run of several is left a key each. */
    fun delete(at: Int, input: CharSequence, letters: NineKeyLetters?) {
        val (i, start) = runAt(at) ?: return
        val run = runs.removeAt(i)
        if (run.length == 1) return
        runs.addAll(i, split(run, start, input, letters) { it != at })
    }

    private inline fun split(run: Run, start: Int, input: CharSequence, letters: NineKeyLetters?, kept: (Int) -> Boolean) =
        (start until start + run.length).filter(kept).map { place ->
            val c = input[place]
            val keys = if (letters != null && c in 'a'..'z') letters.key(c).toString() else keyOf(c)
            // the letters of a syllable taken were picked, not tapped
            Run(1, keys, run.glided, run.multiTap, at = Float.NaN)
        }

    fun set(at: Int, keys: String, multiTap: Boolean) {
        val (i, _) = runAt(at) ?: return
        runs[i] = Run(1, keys, runs[i].glided, multiTap, at = Float.NaN)
    }

    fun replace(from: Int, to: Int, length: Int, keys: String, added: Int = 0) {
        val (i, j) = indices(from, to)
        val glided = runs.subList(i, j).any { it.glided }
        val multiTap = runs.subList(i, j).any { it.multiTap }
        runs.subList(i, j).clear()
        runs.add(i, Run(length, keys, glided, multiTap))
        repeat(added) { runs.add(i + 1, Run(1, "")) }
    }

    fun putBack(from: Int, to: Int, was: List<Run>) {
        val (i, j) = indices(from, to)
        runs.subList(i, j).clear()
        runs.addAll(i, was)
    }

    /** The keys pressed for places [from]..[to], [slips] as the key meant. */
    fun keys(from: Int, to: Int, slips: Map<Int, Char> = emptyMap()): String {
        val (i, j) = indices(from, to)
        if (slips.isEmpty()) return runs.subList(i, j).joinToString("") { it.keys }
        val out = StringBuilder()
        var place = (0 until i).sumOf { runs[it].length }
        for (run in runs.subList(i, j)) {
            val meant = if (run.length == 1) slips[place] else null
            out.append(if (meant != null) keyOf(meant) else run.keys)
            place += run.length
        }
        return out.toString()
    }

    /** Where each place in [from]..[to] was tapped across its key (NaN for none); null where none has one. Walked a place at a time, not by [indices]. */
    fun taps(from: Int, to: Int): FloatArray? {
        val out = FloatArray(to - from) { Float.NaN }
        var place = 0
        var any = false
        for (run in runs) {
            if (place >= to) break
            if (run.length == 1 && place >= from && !run.at.isNaN()) {
                out[place - from] = run.at
                any = true
            }
            place += run.length
        }
        return out.takeIf { any }
    }

    fun touches(from: Int, to: Int): Array<Touch?>? {
        var out: Array<Touch?>? = null
        var place = 0
        for (run in runs) {
            if (place >= to) break
            val touch = run.touch
            if (run.length == 1 && place >= from && touch != null) {
                val o = out ?: arrayOfNulls<Touch>(to - from).also { out = it }
                o[place - from] = touch
            }
            place += run.length
        }
        return out
    }

    fun glided(from: Int, to: Int): Boolean {
        val (i, j) = indices(from, to)
        return runs.subList(i, j).any { it.glided }
    }

    fun multiTap(from: Int, to: Int): Boolean {
        val (i, j) = indices(from, to)
        return runs.subList(i, j).any { it.multiTap }
    }

    val length get() = runs.sumOf { it.length }

    private fun indexAt(at: Int): Int {
        var place = 0
        for (i in runs.indices) {
            if (place >= at) return i
            place += runs[i].length
        }
        return runs.size
    }

    private fun runAt(at: Int): Pair<Int, Int>? {
        var place = 0
        for (i in runs.indices) {
            if (at < place + runs[i].length) return i to place
            place += runs[i].length
        }
        return null
    }

    private fun indices(from: Int, to: Int): Pair<Int, Int> {
        var place = 0
        var i = 0
        while (i < runs.size && place < from) place += runs[i++].length
        var j = i
        while (j < runs.size && place < to) place += runs[j++].length
        return i to j
    }

    companion object {
        /** A key as learning files it: separators are none, letters lower case. */
        fun keyOf(c: Char): String = if (c == SyllableGraph.SEPARATOR) "" else c.lowercaseChar().toString()
    }
}
