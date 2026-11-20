/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core


/** fcitx-utils/textformatflags.h */
enum class TextFormatFlag(val flag: Int) {
    NoFlag(0),
    Underline(1 shl 3),
    HighLight(1 shl 4),
    DontCommit(1 shl 5),
    Bold(1 shl 6),
    Strike(1 shl 7),
    Italic(1 shl 8)
}

fun Int.hasFlag(flag: TextFormatFlag) = this and flag.flag == flag.flag

data class FormattedText(
    val strings: Array<String>,
    val flags: IntArray,
    /** In Java String units. */
    val cursor: Int
) {

    constructor() : this(arrayOf(), intArrayOf(), -1)

    companion object {
        @JvmStatic
        val Empty = FormattedText()
    }

    val length: Int
        get() = strings.sumOf { it.length }

    override fun toString() = buildString { strings.forEach { append(it) } }

    fun isEmpty() = strings.all { it.isEmpty() }

    fun isNotEmpty() = strings.any { it.isNotEmpty() }

    fun codePointCountUntil(endIndex: Int): Int {
        var count = 0
        var length = 0
        for (str in strings) {
            val total = length + str.length
            if (total < endIndex) {
                count += str.codePointCount(0, str.length)
                length = total
            } else {
                count += str.codePointCount(0, endIndex - length)
                break
            }
        }
        return count
    }

    /** Ignoring [cursor]. */
    fun spanEquals(other: FormattedText): Boolean {
        if (this === other) return true
        if (!flags.contentEquals(other.flags)) return false
        if (!strings.contentEquals(other.strings)) return false
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FormattedText

        if (cursor != other.cursor) return false
        if (!flags.contentEquals(other.flags)) return false
        if (!strings.contentEquals(other.strings)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = strings.contentHashCode()
        result = 31 * result + flags.contentHashCode()
        result = 31 * result + cursor
        return result
    }
}
