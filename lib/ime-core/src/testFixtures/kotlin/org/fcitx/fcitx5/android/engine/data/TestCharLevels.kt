/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * A `charlevels.data` made up for the tests ([CharLevelsBuilder] over kTGH lines of its own): the
 * characters the tests type are 一级 ([FIRST], ranks 1 on), the boundaries' ranks hold the
 * characters named here, the rest of the 8,105 count up from U+4E00. The shipped table is read by
 * ime-eval's RareCharsUatTest.
 */
object TestCharLevels {
    /** 一级 from rank 1, in order: what the fixtures' dictionaries and tables have. */
    const val FIRST = "的是大打你拟好角色一二三四五六七上八下叫员日期妤人山高谈淡话天行长畅达发干"

    const val AT_3500 = 0x4E59 // 乙: 一级's last
    const val AT_3501 = 0x4E5C // 乜: 二级's first
    const val AT_6500 = 0x4E5E // 乞: 二级's last
    const val AT_6501 = 0x3447 // 㑇: 三级's first, in 扩展 A
    const val AT_8104 = 0x7287 // 犇: 三级
    const val AT_8105 = 0x2C8D9 // 𬣙: 三级's last, in 扩展 B's plane

    /** 髮: traditional, of 发 in the table. */
    const val TRAD = 0x9AEE

    /** 鬱: traditional too, but of 郁, which is not in this table: no group of its own, so [Level.OUT][CharLevels.Level.OUT]. */
    const val TRAD_NOT = 0x9B31

    /** 龘: in the basic block, not listed. */
    const val OUT = 0x9F98

    /** 𤝰: in 扩展 B, not listed. */
    const val EXT = 0x24770

    private val placed = mapOf(3500 to AT_3500, 3501 to AT_3501, 6500 to AT_6500, 6501 to AT_6501, 8104 to AT_8104, 8105 to AT_8105)

    /** The kTGH lines, a rank each. */
    val tgh: List<String> by lazy {
        val first = FIRST.codePoints().toArray()
        val taken = (first.toList() + placed.values + listOf(TRAD, TRAD_NOT, OUT, EXT)).toHashSet()
        var next = 0x4E00
        (1..CharLevelsBuilder.TABLE).map { rank ->
            val cp = if (rank <= first.size) first[rank - 1] else placed[rank] ?: run {
                while (next in taken) next++
                next++
            }
            "U+%04X\tkTGH\t2013:%d".format(cp, rank)
        }
    }

    /** TSCharacters.txt's lines: 髮 of 发, 鬱 of 郁. */
    val traditional = listOf("髮\t发 髪", "鬱\t郁")

    val bytes: ByteArray by lazy {
        val out = ByteArrayOutputStream()
        CharLevelsBuilder().tgh(tgh.asSequence(), "kTGH").traditional(traditional.asSequence()).build().writeTo(out)
        out.toByteArray()
    }

    val levels: CharLevels by lazy { CharLevels.load(ByteBuffer.wrap(bytes)) }
}
