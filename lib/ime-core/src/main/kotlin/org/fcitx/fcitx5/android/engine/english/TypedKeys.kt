/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.pinyin.Touch
import java.util.BitSet

/**
 * The keys of the word [EnglishSession] is typing, each with where it was tapped (null: no place, a
 * hardware key's or one typed anew) and, on the nine keys, whether Shift was on ([upper]): edited
 * only here, so a key and its place keep in step (061-neighbour-english).
 */
internal class TypedKeys : CharSequence {
    private val keys = StringBuilder()
    private val touches = ArrayList<Touch?>()

    /** Of the keys, the nine keys' typed with Shift (a digit has no case of its own). */
    val upper = BitSet()

    override val length: Int get() = keys.length

    override fun get(index: Int): Char = keys[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = keys.subSequence(startIndex, endIndex)

    override fun toString(): String = keys.toString()

    /** [c] put in at [at], tapped at [touch]. */
    fun insert(at: Int, c: Char, touch: Touch?) {
        keys.insert(at, c)
        touches.add(at, touch)
    }

    /** [c] put on the end, tapped at [touch], with Shift on or not ([upper]). */
    fun append(c: Char, upper: Boolean, touch: Touch?) {
        this.upper[keys.length] = upper
        keys.append(c)
        touches.add(touch)
    }

    /** The key at [at] taken out. */
    fun deleteAt(at: Int) {
        keys.deleteCharAt(at)
        touches.removeAt(at)
        upper.clear(at)
    }

    /** The keys [text] in place of these, none with a place (a word put back, not tapped). */
    fun set(text: String) {
        keys.setLength(0)
        keys.append(text)
        touches.clear()
        touches.addAll(arrayOfNulls(text.length))
    }

    fun clear() {
        keys.setLength(0)
        touches.clear()
        upper.clear()
    }

    /**
     * Where each key [EnglishData.key] keeps (an apostrophe is none) was tapped, for the decoder to read a
     * key as one around it by how far toward it the tap fell; null where no key has a place.
     */
    fun keyTouches(): Array<Touch?>? =
        if (touches.all { it == null }) null else keys.indices.filter { keys[it] != '\'' }.map { touches[it] }.toTypedArray()
}
