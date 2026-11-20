/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

/** Which tone each character of a word has read a given way: shown in candidates, checked against typed tones. Only [MAINLAND] exists; an interface so another (Taiwan's) can be added. */
interface ReadingTable {

    val name: String

    /** Per code point of [text] read as [syllables], [ToneMark.NONE] where unknown; [word] is its data id or -1 (by characters alone). Null if [syllables] are not one per code point. */
    fun tones(text: String, syllables: IntArray, word: Int = -1): ByteArray?

    /** True for a character the table does not know. */
    fun allows(codePoint: Int, syllable: Int, tone: Int): Boolean

    companion object {
        const val MAINLAND = "mainland"
    }
}
