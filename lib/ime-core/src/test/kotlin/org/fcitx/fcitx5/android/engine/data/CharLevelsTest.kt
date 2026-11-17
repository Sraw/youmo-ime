/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.data.TestCharLevels.AT_3500
import org.fcitx.fcitx5.android.engine.data.TestCharLevels.AT_3501
import org.fcitx.fcitx5.android.engine.data.TestCharLevels.AT_6500
import org.fcitx.fcitx5.android.engine.data.TestCharLevels.AT_6501
import org.fcitx.fcitx5.android.engine.data.TestCharLevels.AT_8104
import org.fcitx.fcitx5.android.engine.data.TestCharLevels.AT_8105
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

/** 生僻字屏蔽's groups (dev/design/061-rare-char-block.md §3.1): listed first, then the ranges; the rest none. */
class CharLevelsTest {

    private val levels = TestCharLevels.levels

    private fun level(cp: Int, want: Level?) = assertEquals("U+%04X".format(cp), want, levels.of(cp))

    @Test
    fun theTablesLevelsSplitAtTheirRanks() = assertRows(
        "rank 1 (的) is 一级" to { level('的'.code, Level.L1) },
        "rank 3500 is 一级's last" to { level(AT_3500, Level.L1) },
        "rank 3501 is 二级's first" to { level(AT_3501, Level.L2) },
        "rank 6500 is 二级's last" to { level(AT_6500, Level.L2) },
        "rank 6501 is 三级's first, though in 扩展 A: listed first" to { level(AT_6501, Level.L3) },
        "rank 8104 is 三级" to { level(AT_8104, Level.L3) },
        "rank 8105, beyond the basic plane, is 三级, not 扩展 B's EXT" to { level(AT_8105, Level.L3) },
        "a traditional character of one in the table is TRAD" to { level(TestCharLevels.TRAD, Level.TRAD) },
        "a traditional character of one not in the table is listed not: its range's" to { level(TestCharLevels.TRAD_NOT, Level.OUT) },
        "a basic-block character not listed (龘) is OUT" to { level(TestCharLevels.OUT, Level.OUT) },
        "an 扩展 B character not listed (𤝰) is EXT" to { level(TestCharLevels.EXT, Level.EXT) },
        "the listed are the table's 8,105 and the one traditional" to { assertEquals(8106, levels.size) },
    )

    @Test
    fun theRangesEndWhereTheyEnd() = assertRows(
        "U+33FF, before 扩展 A: none" to { level(0x33FF, null) },
        "U+3400, 扩展 A's first" to { level(0x3400, Level.OUT) },
        "U+4DBF, 扩展 A's last" to { level(0x4DBF, Level.OUT) },
        "U+4DC0, the hexagrams after it: none" to { level(0x4DC0, null) },
        "U+9FFF, the basic block's last" to { level(0x9FFF, Level.OUT) },
        "U+A000, Yi after it: none" to { level(0xA000, null) },
        "U+F8FF, private use: none" to { level(0xF8FF, null) },
        "U+F900, the compatibility ideographs' first" to { level(0xF900, Level.OUT) },
        "U+FAFF, their last" to { level(0xFAFF, Level.OUT) },
        "U+FB00, after them: none" to { level(0xFB00, null) },
        "U+1FFFF, before plane 2: none" to { level(0x1FFFF, null) },
        "U+20000, 扩展 B's first" to { level(0x20000, Level.EXT) },
        "U+3FFFF, plane 3's last" to { level(0x3FFFF, Level.EXT) },
        "U+40000, after: none" to { level(0x40000, null) },
        "〇 (U+3007) is not ours" to { level(0x3007, null) },
        "a Kangxi radical (U+2F00) is not ours" to { level(0x2F00, null) },
        "a CJK radical supplement (U+2E80) is not ours" to { level(0x2E80, null) },
        "a stroke (U+31C0) is not ours" to { level(0x31C0, null) },
        "a tag character (U+E0067) is not ours" to { level(0xE0067, null) },
        "a variation selector (U+FE00) is not ours" to { level(0xFE00, null) },
        "plane 15 private use is not ours" to { level(0xF0000, null) },
    )

    @Test
    fun aTableThatIsNotTheTablesFails() {
        val tgh = TestCharLevels.tgh
        assertRows(
            "a rank missing" to {
                assertThrows(IllegalStateException::class.java) { CharLevelsBuilder().tgh(tgh.dropLast(1).asSequence(), "kTGH").build() }
            },
            // told by its code point as the file writes it (R26 F8202, F8470: once U+U+4E00)
            "a character twice" to {
                val e = assertThrows(SourceException::class.java) { CharLevelsBuilder().tgh((tgh + tgh.first()).asSequence(), "kTGH").build() }
                assertEquals("kTGH:${tgh.size + 1}: ${tgh.first().substringBefore('\t')} twice", e.message)
            },
            "a kTGH line of another edition" to {
                assertThrows(SourceException::class.java) { CharLevelsBuilder().tgh(sequenceOf("U+4E00\tkTGH\t1988:1"), "kTGH") }
            },
            "a file of another kind" to {
                assertThrows(Exception::class.java) { CharLevels.load(ByteBuffer.wrap(ByteArray(64))) }
            },
        )
    }
}
