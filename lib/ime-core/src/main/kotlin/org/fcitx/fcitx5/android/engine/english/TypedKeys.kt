/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.pinyin.Touch
import java.util.BitSet

/** The keys of the word being typed, each with its tap and Shift, edited only here so they keep in step. */
internal class TypedKeys : CharSequence {
    private val keys = StringBuilder()
    private val touches = ArrayList<Touch?>()

    val upper = BitSet()

    override val length: Int get() = keys.length

    override fun get(index: Int): Char = keys[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = keys.subSequence(startIndex, endIndex)

    override fun toString(): String = keys.toString()

    fun insert(at: Int, c: Char, touch: Touch?) {
        for (i in keys.length downTo at + 1) upper[i] = upper[i - 1]
        upper.clear(at)
        keys.insert(at, c)
        touches.add(at, touch)
    }

    fun append(c: Char, upper: Boolean, touch: Touch?) {
        this.upper[keys.length] = upper
        keys.append(c)
        touches.add(touch)
    }

    fun deleteAt(at: Int) {
        for (i in at until keys.length - 1) upper[i] = upper[i + 1]
        upper.clear(keys.length - 1)
        keys.deleteCharAt(at)
        touches.removeAt(at)
    }

    fun set(text: String) {
        upper.clear()
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

    fun keyTouches(): Array<Touch?>? =
        if (touches.all { it == null }) null else keys.indices.filter { keys[it] != '\'' }.map { touches[it] }.toTypedArray()
}
