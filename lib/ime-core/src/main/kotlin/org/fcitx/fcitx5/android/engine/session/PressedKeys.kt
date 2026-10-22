/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph

/**
 * The keys pressed for each place of a [PinyinSession]'s input, kept beside it as it is edited:
 * what learning files a pick under (PLAN §2.1 编码). The input alone does not say: on the nine
 * keys a syllable taken stands in it as its letters, fuzzy ones too (9 2 6 taken as zhan), and a
 * glide's letters are no keys pressed at all.
 *
 * Runs of the input, each its [Run.length] places, for [Run.keys] pressed: a key typed is a run
 * of one; a syllable taken one run of its letters for its digits, then its separator's, of none.
 */
internal class PressedKeys {

    /** [length] places of the input, typed as [keys]; [glided], by a glide; [multiTap], a letter the nine keys' 连按 chose. */
    class Run(val length: Int, val keys: String, val glided: Boolean = false, val multiTap: Boolean = false)

    private val runs = ArrayList<Run>()

    fun clear() = runs.clear()

    /** The runs from input place [from] up to [to], as they are: for a syllable taken, to put back. */
    fun runs(from: Int, to: Int): List<Run> {
        val (i, j) = indices(from, to)
        return runs.subList(i, j).toList()
    }

    /**
     * [text] typed at [at], a key a place, each as [keyOf] files it. Within a run of several (a
     * syllable taken), that run is split first into a key a place of [input] (as it is before), as
     * [delete] splits it.
     */
    fun insert(at: Int, text: String, glided: Boolean = false, multiTap: Boolean = false, input: CharSequence? = null, letters: NineKeyLetters? = null) {
        if (input != null) {
            runAt(at)?.takeIf { (i, start) -> start < at && runs[i].length > 1 }?.let { (i, start) ->
                runs.addAll(i, split(runs.removeAt(i), start, input, letters) { true })
            }
        }
        var i = indexAt(at)
        for (c in text) runs.add(i++, Run(1, keyOf(c), glided, multiTap))
    }

    /**
     * The place at [at] of [input] (as it is before) deleted. One of a run of several (a syllable
     * taken) leaves the rest a key each, the key [letters] has each letter on.
     */
    fun delete(at: Int, input: CharSequence, letters: NineKeyLetters?) {
        val (i, start) = runAt(at) ?: return
        val run = runs.removeAt(i)
        if (run.length == 1) return
        runs.addAll(i, split(run, start, input, letters) { it != at })
    }

    // [run], from [start] of [input], a run a place of those [kept], each on the key [letters] has its letter on
    private inline fun split(run: Run, start: Int, input: CharSequence, letters: NineKeyLetters?, kept: (Int) -> Boolean) =
        (start until start + run.length).filter(kept).map { place ->
            val c = input[place]
            val keys = if (letters != null && c in 'a'..'z') letters.key(c).toString() else keyOf(c)
            Run(1, keys, run.glided, run.multiTap)
        }

    /** The place at [at] typed again as [keys] (a 连按 press: the letter in place of the key). */
    fun set(at: Int, keys: String, multiTap: Boolean) {
        val (i, _) = runAt(at) ?: return
        runs[i] = Run(1, keys, runs[i].glided, multiTap)
    }

    /** The places from [from] up to [to] made one run of [length] places, for [keys]; then [added] separators, of none. */
    fun replace(from: Int, to: Int, length: Int, keys: String, added: Int = 0) {
        val (i, j) = indices(from, to)
        val glided = runs.subList(i, j).any { it.glided }
        val multiTap = runs.subList(i, j).any { it.multiTap }
        runs.subList(i, j).clear()
        runs.add(i, Run(length, keys, glided, multiTap))
        repeat(added) { runs.add(i + 1, Run(1, "")) }
    }

    /** The places from [from] up to [to] put back as [was] (a syllable given back). */
    fun putBack(from: Int, to: Int, was: List<Run>) {
        val (i, j) = indices(from, to)
        runs.subList(i, j).clear()
        runs.addAll(i, was)
    }

    /** The keys pressed for the places from [from] up to [to]. */
    fun keys(from: Int, to: Int): String {
        val (i, j) = indices(from, to)
        return runs.subList(i, j).joinToString("") { it.keys }
    }

    fun glided(from: Int, to: Int): Boolean {
        val (i, j) = indices(from, to)
        return runs.subList(i, j).any { it.glided }
    }

    fun multiTap(from: Int, to: Int): Boolean {
        val (i, j) = indices(from, to)
        return runs.subList(i, j).any { it.multiTap }
    }

    /** How many places the runs cover: the input's length, kept in step. */
    val length get() = runs.sumOf { it.length }

    // the run index a place [at] starts or would be inserted at
    private fun indexAt(at: Int): Int {
        var place = 0
        for (i in runs.indices) {
            if (place >= at) return i
            place += runs[i].length
        }
        return runs.size
    }

    // the run holding place [at], and where that run starts
    private fun runAt(at: Int): Pair<Int, Int>? {
        var place = 0
        for (i in runs.indices) {
            if (at < place + runs[i].length) return i to place
            place += runs[i].length
        }
        return null
    }

    // the runs that start in [from, to)
    private fun indices(from: Int, to: Int): Pair<Int, Int> {
        var place = 0
        var i = 0
        while (i < runs.size && place < from) place += runs[i++].length
        var j = i
        while (j < runs.size && place < to) place += runs[j++].length
        return i to j
    }

    companion object {
        /** A key typed, as learning files it: separators are none, letters lower case. */
        fun keyOf(c: Char): String = if (c == SyllableGraph.SEPARATOR) "" else c.lowercaseChar().toString()
    }
}
