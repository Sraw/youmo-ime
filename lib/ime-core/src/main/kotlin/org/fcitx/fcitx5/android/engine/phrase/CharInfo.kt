/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.data.BitPacked
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.ensureFormat
import java.nio.ByteBuffer

/**
 * A character's radical, its strokes and its parts, for its card (`engine/charinfo.data`,
 * ime-dict-tool's `charinfo`): Unihan's kRSUnicode and kTotalStrokes (Unicode License v3) with
 * CJKRadicals.txt, the open 汉语拆字字典 (kfcd/chaizi, CC BY 3.0) and rime-stroke for a character
 * Unihan counts no strokes of. How each is chosen: [CharInfoBuilder].
 */
class CharInfo private constructor(
    private val chars: BitPacked,
    private val radicals: BitPacked,
    private val radical: BitPacked,
    private val strokes: BitPacked,
    private val partChars: BitPacked,
    private val partStart: BitPacked,
    private val parts: BitPacked,
) {
    /**
     * Of one character: its [radical] as the mainland writes it (江 氵, 说 讠), its [strokes] (0 if
     * not known), and the [parts] it is made of, the radical's among them where one is (氵 工); null
     * for a character not taken apart (乾, and those of one part: 一 乙).
     */
    data class Entry(val radical: String, val strokes: Int, val parts: List<String>?)

    init {
        ensureFormat(radical.size == chars.size && strokes.size == chars.size) { "character info disagrees with its characters" }
        radical.checkBelow("radicals", radicals.size)
        ensureFormat(partStart.size == partChars.size + 1) { "parts disagree with their characters" }
        partStart.checkRuns("parts", 0, parts.size)
    }

    private fun find(table: BitPacked, codePoint: Int): Int {
        val i = table.lowerBound(0, table.size, codePoint)
        return if (i < table.size && table[i] == codePoint) i else -1
    }

    /** What is known of [codePoint]; null for one Unihan gives no radical (no Han character). */
    fun of(codePoint: Int): Entry? {
        val i = find(chars, codePoint)
        if (i < 0) return null
        val p = find(partChars, codePoint)
        val partList = if (p < 0) null else (partStart[p] until partStart[p + 1]).map { String(Character.toChars(parts[it])) }
        return Entry(String(Character.toChars(radicals[radical[i]])), strokes[i], partList)
    }

    /** How many characters it knows. */
    val size: Int get() = chars.size

    /** How many it knows the parts of. */
    val taken: Int get() = partChars.size

    internal object Section {
        const val CHARS = 1
        const val RADICALS = 2
        const val RADICAL = 3
        const val STROKES = 4
        const val PART_CHARS = 5
        const val PART_START = 6
        const val PARTS = 7
    }

    companion object {
        /** Layout of the sections; bump on any change to it. */
        const val VERSION = 1

        /** None known: an app without the file. */
        val EMPTY: CharInfo by lazy { load(ByteBuffer.wrap(CharInfoBuilder().build().toByteArray())) }

        fun load(buffer: ByteBuffer, verify: Boolean = true): CharInfo {
            val file = DataFile.open(buffer, DataFile.KIND_CHAR_INFO, VERSION, verify)
            return CharInfo(
                file.bitPacked(Section.CHARS),
                file.bitPacked(Section.RADICALS),
                file.bitPacked(Section.RADICAL),
                file.bitPacked(Section.STROKES),
                file.bitPacked(Section.PART_CHARS),
                file.bitPacked(Section.PART_START),
                file.bitPacked(Section.PARTS),
            )
        }
    }
}
