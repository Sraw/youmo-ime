/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CharInfoTest {

    companion object {
        // as Unicode 17.0's Unihan_IRGSources.txt and CJKRadicals.txt have them
        val UNIHAN = """
            # Unihan_IRGSources.txt
            U+4E7E	kRSUnicode	5.10
            U+4E7E	kTotalStrokes	11
            U+548C	kIRG_GSource	G0-3A4D
            U+548C	kRSUnicode	30.5
            U+548C	kTotalStrokes	8
            U+5F73	kRSUnicode	60.0
            U+5F73	kTotalStrokes	3
            U+6253	kRSUnicode	64.2
            U+6253	kTotalStrokes	5
            U+6C5F	kRSUnicode	85.3
            U+6C5F	kTotalStrokes	6
            U+884C	kRSUnicode	144.0
            U+884C	kTotalStrokes	6
            U+8BF4	kRSUnicode	149'.7
            U+8BF4	kTotalStrokes	9
            U+8D62	kRSUnicode	154'.13
            U+8D62	kTotalStrokes	17
            U+8349	kRSUnicode	140.6
            U+8349	kTotalStrokes	9 10
            U+6155	kRSUnicode	61.11
            U+6155	kTotalStrokes	14
            U+20000	kRSUnicode	1.2 1.3
        """.trimIndent()

        val RADICALS = """
            # CJKRadicals.txt
            1; 2F00; 4E00
            5; 2F04; 4E59
            30; 2F1D; 53E3
            60; 2F27; 5F73
            61; 2F3C; 5FC3
            64; 2F3F; 624B
            85; 2F54; 6C34
            140; 2F8B; 8278
            144; 2F8F; 884C
            149; 2F94; 8A00
            149'; 2EC8; 8BA0
            154'; 2EC9; 8D1D
        """.trimIndent()

        val CHAIZI = """
            江	水 工	氵 工
            打	手 丁	扌 丁	才 丁
            行	彳 亍
            和	禾 口
            说	言 兑	讠 兑
            说	言 兑	讠 兑
            赢	亡 口 月 贝 凡
            草	草 早	艸 早	艹 早
            慕	莫 心	莫 小 丶
            一	一
            ab	a b
        """.trimIndent()

        // as lexicon/stroke-order.tsv has them
        val ORDERS = """
            # 字  笔顺编号  note
            江	441121
            行	332112
            和	31234251
        """.trimIndent()

        fun builder(): CharInfoBuilder = CharInfoBuilder()
            .unihan(UNIHAN.lineSequence(), "Unihan_IRGSources.txt")
            .radicals(RADICALS.lineSequence(), "CJKRadicals.txt")
            .chaizi(CHAIZI.lineSequence())
            .strokes(0x20000, "hspn")
            .orders(ORDERS.lineSequence(), "stroke-order.tsv")

        val info: CharInfo by lazy { CharInfo.load(ByteBuffer.wrap(builder().build().toByteArray())) }
    }

    private fun of(c: String) = info.of(c.codePointAt(0))

    @Test
    fun theRadicalAsTheMainlandWritesItWhereThePartsShowIt() {
        assertEquals(CharInfo.Entry("氵", 6, listOf("氵", "工"), "nnhhsh"), of("江"))
        assertEquals(CharInfo.Entry("扌", 5, listOf("扌", "丁")), of("打"))
        // 草's own name for its top (草 早) is no way apart; 艹 早 is
        assertEquals(CharInfo.Entry("艹", 9, listOf("艹", "早")), of("草"))
        // 心 at the bottom: as CJKRadicals writes it
        assertEquals(CharInfo.Entry("心", 14, listOf("莫", "心")), of("慕"))
    }

    @Test
    fun aSimplifiedRadicalAndOneAsKangxiHasIt() {
        assertEquals(CharInfo.Entry("讠", 9, listOf("讠", "兑")), of("说"))
        assertEquals(CharInfo.Entry("贝", 17, listOf("亡", "口", "月", "贝", "凡")), of("赢"))
        assertEquals(CharInfo.Entry("口", 8, listOf("禾", "口"), "phspnszh"), of("和"))
        assertEquals(CharInfo.Entry("行", 6, listOf("彳", "亍"), "ppshhs"), of("行"))
    }

    @Test
    fun noPartsForACharacterNotTakenApartAndStrokesFromTheStrokeOrderWhereUnihanHasNone() {
        assertEquals(CharInfo.Entry("乙", 11, null), of("乾"))
        assertEquals(CharInfo.Entry("一", 4, null), of("𠀀"))
        assertNull(of("a"))
        assertNull(of("一"))
        assertEquals(11, info.size)
        assertEquals(8, info.taken)
    }

    @Test
    fun theMainlandsStrokeOrderByTheStandardsNumbers() {
        // 1 横 2 竖 3 撇 4 点 5 折: 行 ends 丨, as 《现代汉语通用字笔顺规范》 counts its 竖钩 (rime-stroke's first: 乛)
        assertEquals("nnhhsh", of("江")!!.order)
        assertEquals("ppshhs", of("行")!!.order)
        assertEquals("phspnszh", of("和")!!.order)
        assertNull(of("打")!!.order)
        val e = assertThrows(SourceException::class.java) {
            CharInfoBuilder().orders(sequenceOf("# a comment", "江\t441121", "行\t33211a"), "o.tsv")
        }
        assertEquals(3, e.line)
        assertThrows(SourceException::class.java) { CharInfoBuilder().orders(sequenceOf("江河\t44"), "o.tsv") }
        assertThrows(SourceException::class.java) { CharInfoBuilder().orders(sequenceOf("江"), "o.tsv") }
        assertThrows(SourceException::class.java) { CharInfoBuilder().orders(sequenceOf("江\t441121", "江\t441121"), "o.tsv") }
    }

    @Test
    fun anOrderUnlikeUnihansCountOrRimeStrokesFailsTheBuildUnlessItsLineSaysWhy() {
        // rime-stroke writes 行 two ways, one of them the mainland's
        builder().strokes('行'.code, "ppshhz").strokes('行'.code, "ppshhs").build()
        // a slip: 和 none of rime-stroke's orders
        val slip = assertThrows(SourceException::class.java) { builder().strokes('和'.code, "phspnhzh").build() }
        assertEquals("stroke-order.tsv" to 4, slip.source to slip.line)
        assertTrue(slip.message, "和 丿一丨丿丶丨乛一, rime-stroke 丿一丨丿丶一乛一" in slip.message!!)
        // 皙 without 白's 丿: 12 strokes, Unihan 13
        val short = CharInfoBuilder().unihan(sequenceOf("U+7699\tkRSUnicode\t106.8", "U+7699\tkTotalStrokes\t13"), "u.txt")
            .orders(sequenceOf("皙\t123433123251"), "o.tsv")
        assertTrue(assertThrows(SourceException::class.java) { short.build() }.message!!.contains("皙 12 strokes, Unihan 13"))
        // Unihan counts 范's 艹 as Taiwan's, 4 strokes: 9; the mainland's, with its line's note why, 8, counted so
        val fan = CharInfoBuilder()
            .unihan(sequenceOf("U+8303\tkRSUnicode\t140.5", "U+8303\tkTotalStrokes\t9"), "u.txt")
            .radicals(RADICALS.lineSequence(), "CJKRadicals.txt")
            .orders(sequenceOf("范\t12244155\tthe mainland's 艹 3 strokes"), "o.tsv")
        fan.build()
        assertEquals("hssnnhzz" to 8, fan.entry('范'.code)!!.let { it.order to it.strokes })
        assertThrows(SourceException::class.java) {
            CharInfoBuilder().unihan(sequenceOf("U+8303\tkRSUnicode\t140.5", "U+8303\tkTotalStrokes\t9"), "u.txt")
                .orders(sequenceOf("范\t12244155"), "o.tsv").build()
        }
        // a note with nothing to say why of: no place for a line's history
        val stale = assertThrows(SourceException::class.java) {
            CharInfoBuilder().unihan(UNIHAN.lineSequence(), "u.txt").orders(sequenceOf("", "江\t441121\tfixed as hanzipi has it"), "o.tsv").build()
        }
        assertEquals(2, stale.line)
        assertTrue(stale.message, "江 a note, but its order is as Unihan counts and rime-stroke writes" in stale.message!!)
        val unknown = assertThrows(SourceException::class.java) { CharInfoBuilder().orders(sequenceOf("江\t441121\tsome note"), "o.tsv").build() }
        assertTrue(unknown.message, "江 a note, but neither Unihan nor rime-stroke has the character to differ from" in unknown.message!!)
        // a line ended \r\n, a blank one between
        val crlf = CharInfoBuilder().unihan(UNIHAN.lineSequence(), "u.txt").radicals(RADICALS.lineSequence(), "r.txt")
            .orders(sequenceOf("江\t441121\r", "", "行\t332112\r"), "o.tsv")
        crlf.build()
        assertEquals("ppshhs", crlf.entry('行'.code)!!.order)
    }

    @Test
    fun noneKnownWithoutTheFile() {
        assertNull(CharInfo.EMPTY.of('江'.code))
        assertEquals(0, CharInfo.EMPTY.size)
    }

    @Test
    fun aBadLineSaysWhere() {
        val e = assertThrows(SourceException::class.java) {
            CharInfoBuilder().unihan(sequenceOf("U+6C5F\tkRSUnicode\t85.3", "U+XYZ\tkRSUnicode\t1.0"), "u.txt")
        }
        assertEquals("u.txt", e.source)
        assertEquals(2, e.line)
        assertThrows(SourceException::class.java) { CharInfoBuilder().unihan(sequenceOf("U+6C5F\tkRSUnicode\tx.3"), "u.txt") }
        assertThrows(SourceException::class.java) { CharInfoBuilder().unihan(sequenceOf("U+6C5F\tkTotalStrokes\t0"), "u.txt") }
        assertThrows(SourceException::class.java) { CharInfoBuilder().radicals(sequenceOf("85; 2F54"), "r.txt") }
    }

    @Test
    fun anotherKindOfFileIsRefused() {
        val other = DataFile.Writer(DataFile.KIND_PINYIN, CharInfo.VERSION).toByteArray()
        assertThrows(DataFormatException::class.java) { CharInfo.load(ByteBuffer.wrap(other)) }
        val old = DataFile.Writer(DataFile.KIND_CHAR_INFO, CharInfo.VERSION + 1).toByteArray()
        assertThrows(DataFormatException::class.java) { CharInfo.load(ByteBuffer.wrap(old)) }
    }

    @Test
    fun theBuilderSaysWhatTheFileWill() {
        val b = builder()
        assertEquals(of("江"), b.entry('江'.code))
        assertNull(b.entry('a'.code))
        assertTrue(CharInfoBuilder.VARIANTS.getValue(163) == CharInfoBuilder.VARIANTS.getValue(170))
    }
}
