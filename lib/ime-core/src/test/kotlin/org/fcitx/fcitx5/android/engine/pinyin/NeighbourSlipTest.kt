/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.MICROSOFT
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.XIAOHE
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.ZIRANMA
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes.G14
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes.G18
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes.NINE
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes.QWERTY
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes.tap
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes.touchesAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A key read as the key next to it by where it was tapped (061-neighbour-correction). */
class NeighbourSlipTest {

    private val cost = SlipCost()

    // the slips of [g] from [at] to [to]: each syllable spelt with its cost, as `hao:-1.2`
    private fun SyllableGraph.slipsOf(at: Int, to: Int): Map<String, Float> {
        val out = HashMap<String, Float>()
        for (e in edges(at)) {
            if (to(e) != to || !isSlip(e)) continue
            val m = matches(e)
            for (i in 0 until m.size) out[Syllables.spelling(m.syllable(i))] = slipCost(e, i)
        }
        return out
    }

    // how far each reading of [g] from [at] to [to], slip or not, goes against where the taps fell, as `sa:0.9`
    // (one walk for the tests that weigh the letters; review F8299)
    private fun SyllableGraph.againstOf(at: Int, to: Int): Map<String, Float> {
        val out = HashMap<String, Float>()
        for (e in edges(at)) {
            if (to(e) != to) continue
            val m = matches(e)
            for (i in 0 until m.size) out[Syllables.spelling(m.syllable(i))] = against(e, i)
        }
        return out
    }

    private fun SyllableGraph.anySlip() = (0 until edgeCount).any(::isSlip)

    @Test
    @Uat("SP-38", "SP-39", "SP-40")
    fun aSlipCostsByHowFarTheTapIsFromTheKeyMeant() {
        val g = tap(QWERTY, 'g')
        val h = g.near('h')!!
        // in the middle the key next to it costs more than the floor lets through; at its edge, little
        assertTrue(cost.of(g, h, legal = false).let { it < -3f && it > -4f })
        assertTrue(cost.of(g.copy(dx = 0.45f), h, legal = false) > -0.5f)
        // legal: 2 more, then the floor
        assertTrue(cost.of(g, h, legal = true).isNaN())
        assertEquals(cost.of(g.copy(dx = 0.45f), h, legal = false) - 2f, cost.of(g.copy(dx = 0.45f), h, legal = true), 1e-4f)
        // a key above or below, a key height away, is read only near that edge
        assertTrue(cost.of(g, g.near('t')!!, legal = false).isNaN())
        assertFalse(cost.of(g.copy(dy = -0.45f, dx = -0.2f), g.near('t')!!, legal = false).isNaN())
    }

    @Test
    fun aSlipIsNeverLikelierThanTheKeyHit() {
        // past the key's edge, or by a wide key: never above 0
        val g = tap(QWERTY, 'g', dx = 0.9f)
        assertEquals(0f, cost.of(g, g.near('h')!!, legal = false), 0f)
        val wide = Touch(0.4f, 0f, 36f, 50f, listOf(Near('x', 1.5f, 0f, nw = 2f)))
        assertTrue(cost.of(wide, wide.near[0], legal = false) <= 0f)
    }

    @Test
    fun theKeysAroundAKeyAreThoseBesideItAndThoseAboveAndBelowOverlappingIt() {
        assertEquals("fhtyv".toSet(), QWERTY.around('g').map { it.char }.toSet())
        assertEquals("bmj".toSet(), QWERTY.around('n').map { it.char }.toSet())
        assertEquals("aw".toSet(), QWERTY.around('q').map { it.char }.toSet())
        // the nine keys: no 1 nor 0 among them; 9 has 6 above and 8 beside
        assertEquals("68".toSet(), NINE.around('9').map { it.char }.toSet())
        // a gap between keys is no gap between neighbours
        val apart = KeyBoxes(mapOf('a' to KeyBoxes.Box(0f, 0f, 30f, 50f), 'b' to KeyBoxes.Box(36f, 0f, 66f, 50f)))
        assertEquals(listOf('b'), apart.around('a').map { it.char })
        val n = QWERTY.around('g').first { it.char == 'h' }
        assertEquals(1f, n.nx, 1e-4f)
        assertEquals(0f, n.ny, 1e-4f)
        // review F8167: x two rows high (rows="2") at the end of the second and third rows is in both, beside e and
        // g, c above it; the rows it spans still above and below each other. By centres it was a row of its own,
        // and e had neither x beside it nor g below
        val box = { col: Int, row: Int, rows: Int -> KeyBoxes.Box(col * 40f, row * 50f, (col + 1) * 40f, (row + rows) * 50f) }
        val tall = KeyBoxes(
            mapOf(
                'a' to box(0, 0, 1), 'b' to box(1, 0, 1), 'c' to box(2, 0, 1),
                'd' to box(0, 1, 1), 'e' to box(1, 1, 1), 'x' to box(2, 1, 2),
                'f' to box(0, 2, 1), 'g' to box(1, 2, 1),
            ),
        )
        assertEquals("ceg".toSet(), tall.around('x').map { it.char }.toSet())
        assertEquals("bdgx".toSet(), tall.around('e').map { it.char }.toSet())
        assertEquals("efx".toSet(), tall.around('g').map { it.char }.toSet())
        assertEquals("ace".toSet(), tall.around('b').map { it.char }.toSet())
    }

    @Test
    @Uat("PY-137")
    fun fullPinyinReadsAKeyAsTheOneItWasTappedTowards() {
        val segmenter = PinyinSegmenter(neighbours = true)
        // s tapped at its left edge, toward a: nihso is nihao
        val towardA = segmenter.segment("nihso", null, touchesAt(5, 3, tap(QWERTY, 's', dx = -0.45f)))
        assertTrue(towardA.slipsOf(2, 5).containsKey("hao"))
        // tapped toward d it is no hao, though as typed hso spells nothing: the 0.6.0 rule would read it
        val towardD = segmenter.segment("nihso", null, touchesAt(5, 3, tap(QWERTY, 's', dx = 0.45f)))
        assertFalse(towardD.slipsOf(2, 5).containsKey("hao"))
        // in the middle, though it spells nothing, not: 全拼 reads none past -1.75, the loosest floor that turns no
        // sentence typed on target wrong (the user's 11-16 rule); over the -4 the others read, at its cost
        assertFalse(segmenter.segment("nihso", null, touchesAt(5, 3, tap(QWERTY, 's'))).slipsOf(2, 5).containsKey("hao"))
        val middle = PinyinSegmenter(neighbours = true, slipCost = SlipCost()).segment("nihso", null, touchesAt(5, 3, tap(QWERTY, 's'))).slipsOf(2, 5)
        assertTrue(middle.getValue("hao").let { it < -3f && it >= -4f })
    }

    @Test
    @Uat("PY-137")
    fun aLegalSlipIsReadOnlyNearTheKeyMeant() {
        // 全拼 reads none: a legal slip costs 2 more, past its floor -1.75 wherever the tap fell
        val full = PinyinSegmenter(neighbours = true)
        assertFalse(full.segment("tade", null, touchesAt(4, 0, tap(QWERTY, 't', dx = 0.49f))).anySlip())
        // over -4 (as 双拼's 18 and 14 keys read): tade, in the middle of t, no ya; near y, ya, costing 2 more than a
        // slip that spells nothing (ta at the end may still become tai: no slip there, as before)
        val segmenter = PinyinSegmenter(neighbours = true, slipCost = SlipCost())
        assertFalse(segmenter.segment("tade", null, touchesAt(4, 0, tap(QWERTY, 't'))).anySlip())
        val near = segmenter.segment("tade", null, touchesAt(4, 0, tap(QWERTY, 't', dx = 0.35f))).slipsOf(0, 2)
        assertTrue(near.getValue("ya") < -2f)
        assertEquals(setOf("ya"), near.keys)
    }

    @Test
    @Uat("PY-138", "SP-41")
    fun consonantsAloneReadAsTypedWhereTappedInTheMiddle() {
        val segmenter = PinyinSegmenter(neighbours = true)
        // 你看完了: kw is 简拼, no ke where k and w were tapped in the middle
        val touches = "nikwl".map { tap(QWERTY, it) }.toTypedArray<Touch?>()
        assertTrue(segmenter.segment("nikwl", null, touches).slipsOf(2, 4).isEmpty())
        // with no place, the 0.6.0 rule but for consonants alone: nihso is nihao, kw is no ke
        assertTrue(segmenter.segment("nihso").slipsOf(2, 5).containsKey("hao"))
        assertTrue(segmenter.segment("nikwl").slipsOf(2, 4).isEmpty())
    }

    @Test
    @Uat("PY-139")
    fun withNoPlaceALegalSlipIsNotRead() {
        val segmenter = PinyinSegmenter(neighbours = true)
        assertFalse(segmenter.segment("ta").anySlip())
        // not at the end, where a syllable still growing reads no slip anyway: ta spells, so no ya nor ra
        val tade = segmenter.segment("tade")
        assertFalse(tade.edges(0).any { tade.isSlip(it) && tade.to(it) == 2 })
        assertEquals(-2f, segmenter.segment("nihap").slipsOf(2, 5).getValue("hao"), 0f)
    }

    @Test
    fun aSlipSaysWhichKeyItReadAsWhich() {
        val g = PinyinSegmenter(neighbours = true).segment("nihso", null, touchesAt(5, 3, tap(QWERTY, 's', dx = -0.45f)))
        val e = g.edges(2).first { g.isSlip(it) && g.to(it) == 5 }
        val i = (0 until g.matches(e).size).first { Syllables.spelling(g.matches(e).syllable(it)) == "hao" }
        assertEquals(1, g.slips(e)!!.at(i))
        assertEquals('a', g.slips(e)!!.meant(i))
    }

    @Test
    fun aSlipIsNoSyllableTypedWhole() {
        val g = PinyinSegmenter(neighbours = true).segment("nihap")
        assertTrue(g.hasOtherReadings)
        assertFalse(g.readsWhole(0, 5))
        assertFalse(g.withoutOtherReadings().anySlip())
        // nor tells of other readings, so asked again is itself, not copied once more (review F8203)
        assertFalse(g.withoutOtherReadings().hasOtherReadings)
        assertSame(g.withoutOtherReadings(), g.withoutOtherReadings().withoutOtherReadings())
        val typed = PinyinSegmenter(neighbours = true).segment("nihao")
        assertTrue(typed.readsWhole(0, 5))
    }

    // these and the keyboards' below have one shape (a tap toward a key reads a slip, in the middle none) but stay a test
    // each: each is the JVM check of its own UAT case (@Uat), and their keyboards' setups differ (review F8394)
    @Test
    @Uat("SP-38")
    fun shuangpinReadsTwoKeysWithOneSlippedWhereTapped() {
        val xiaohe = ShuangpinSegmenter(XIAOHE, neighbours = true)
        // ta: t near y is ya, in the middle nothing
        assertTrue(xiaohe.segment("ta", null, touchesAt(2, 0, tap(QWERTY, 't', dx = 0.35f))).slipsOf(0, 2).containsKey("ya"))
        assertFalse(xiaohe.segment("ta", null, touchesAt(2, 0, tap(QWERTY, 't'))).anySlip())
        // with no place, none: two keys make nearly any syllable
        assertFalse(xiaohe.segment("ta").anySlip())
        // off, none
        assertFalse(ShuangpinSegmenter(XIAOHE).segment("ta", null, touchesAt(2, 0, tap(QWERTY, 't', dx = 0.35f))).anySlip())
    }

    // 小鹤, 自然码 and 微软 alike (q is iu, s is ong in each): tq (tiu, no syllable) with q tapped toward a is ta;
    // ts (tong) with s tapped toward a is ta as well; in the middle neither
    @Test
    @Uat("SP-38", "SP-39", "SP-40")
    fun eachShuangpinSchemeReadsTheKeyTappedTowards() {
        for ((name, scheme) in listOf("小鹤" to XIAOHE, "自然码" to ZIRANMA, "微软" to MICROSOFT)) {
            val sp = ShuangpinSegmenter(scheme, neighbours = true)
            val q = tap(QWERTY, 'q', dx = 0.1f, dy = 0.42f)
            assertTrue(name, sp.segment("tq", null, touchesAt(2, 1, q)).slipsOf(0, 2).containsKey("ta"))
            assertFalse(name, sp.segment("tq", null, touchesAt(2, 1, tap(QWERTY, 'q'))).anySlip())
            val s = tap(QWERTY, 's', dx = -0.4f)
            assertTrue(name, sp.segment("ts", null, touchesAt(2, 1, s)).slipsOf(0, 2).containsKey("ta"))
            assertFalse(name, sp.segment("ts", null, touchesAt(2, 1, tap(QWERTY, 's'))).anySlip())
        }
    }

    @Test
    @Uat("T9-66", "ST-136")
    fun theNineKeysReadADigitAsTheOneItWasTappedTowards() {
        val nine = T9Segmenter(abbreviations = false, neighbours = true)
        // 99 spells nothing; the second 9 tapped near 6 above it is 96: wo, yo
        val near = nine.segment("99", null, touchesAt(2, 1, tap(NINE, '9', dy = -0.4f))).slipsOf(0, 2)
        assertTrue(near.keys.toString(), "wo" in near.keys)
        assertTrue(nine.segment("99", null, touchesAt(2, 1, tap(NINE, '9'))).slipsOf(0, 2).isEmpty())
        // no place: none
        assertFalse(nine.segment("99").anySlip())
    }

    // the nine keys' digits read no slip under -3 (N3, measured on the 6T), the other keyboards' floor -4
    @Test
    fun theNineKeysReadNoSlipCostingUnderMinusThree() {
        fun at(dy: Float) = tap(NINE, '9', dy = dy)
        // the second 9 tapped up towards 6 so that 96 (wo) costs between -4 and -3
        val dy = (0..50).map { -it / 100f }.first { SlipCost().of(at(it), at(it).near('6')!!, legal = false) in -4f..-3.2f }
        val floorFour = T9Segmenter(abbreviations = false, neighbours = true, slipCost = SlipCost())
        assertTrue("wo" in floorFour.segment("99", null, touchesAt(2, 1, at(dy))).slipsOf(0, 2))
        assertFalse("wo" in T9Segmenter(abbreviations = false, neighbours = true).segment("99", null, touchesAt(2, 1, at(dy))).slipsOf(0, 2))
        // the 14 keys keep -4: BN tapped towards M so that men (legal: ben spells) costs between -4 and -3 still reads it
        fun bn(dx: Float) = tap(G14, g14Code('b'), dx = dx)
        val m = g14Code('m')
        val dx = (0..50).map { it / 100f }.first { SlipCost().of(bn(it), bn(it).near(m)!!, legal = true) in -4f..-3.2f }
        val keys = T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14, neighbours = true)
        val slips = keys.segment(g14("b e b"), floatArrayOf(0.95f, Float.NaN, Float.NaN), touchesAt(3, 0, bn(dx))).slipsOf(0, 3)
        assertTrue(slips.toString(), slips.getValue("men") < -3f)
    }

    @Test
    @Uat("PY-141")
    fun theFourteenKeysReadAKeyAsTheOneNextToItAndWeighItsLetters() {
        val keys = T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14, neighbours = true)
        val typed = g14("b e b")
        // BN tapped at its right edge, toward M: men, though ben spells as typed
        val bn = tap(G14, g14Code('b'), dx = 0.45f)
        val taps = floatArrayOf(0.95f, Float.NaN, Float.NaN)
        val g = keys.segment(typed, taps, touchesAt(3, 0, bn))
        assertTrue(g.slipsOf(0, 3).containsKey("men"))
        assertFalse(keys.segment(typed, taps, touchesAt(3, 0, tap(G14, g14Code('b')))).anySlip())
    }

    // 拼音's 18 keys, a row of seven keys over one of six: BN tapped at its right edge, toward M, reads men, in the
    // middle not; FG, wider than RT above it (which it has in its own size), tapped at its left edge toward SD
    // reads sa as well as fa
    @Test
    @Uat("PY-142")
    fun theEighteenKeysReadAKeyAsTheOneNextToIt() {
        val keys = T9Segmenter(abbreviations = false, letters = NineKeyLetters.G18, neighbours = true)
        val b = NineKeyLetters.G18.key('b')
        val typed = g18("b e b")
        assertTrue(keys.segment(typed, null, touchesAt(3, 0, tap(G18, b, dx = 0.45f))).slipsOf(0, 3).containsKey("men"))
        assertFalse(keys.segment(typed, null, touchesAt(3, 0, tap(G18, b))).anySlip())
        val fg = tap(G18, NineKeyLetters.G18.key('f'), dx = -0.45f)
        assertEquals(6f / 7, fg.near(NineKeyLetters.G18.key('r'))!!.nw, 0.01f)
        assertTrue(keys.segment(g18("f a"), null, touchesAt(2, 0, fg)).slipsOf(0, 2).containsKey("sa"))
    }

    // 061-neighbour-18key: pinyin's 18 keys' taps go with where across the key they fell (Keyboard.pinyin18Tap), so a
    // slip has the letters of the key slipped to weighed as the 14 keys' do: FG tapped at its left edge, toward SD, is
    // put onto SD's right edge, so sa goes nearly a whole against s and da none; with no place (as before) neither, and FG's
    // own fa and ga as tapped (f none, g most of a whole)
    @Test
    @Uat("PY-152")
    fun theEighteenKeysWeighTheLettersOfTheKeySlippedToByWhereTheTapFell() {
        val keys = T9Segmenter(abbreviations = false, letters = NineKeyLetters.G18, neighbours = true)
        val fg = tap(G18, NineKeyLetters.G18.key('f'), dx = -0.45f)
        val typed = g18("f a")
        fun against(taps: FloatArray?) = keys.segment(typed, taps, touchesAt(2, 0, fg)).againstOf(0, 2)
        val placed = against(floatArrayOf(0.05f, Float.NaN))
        assertTrue(placed.toString(), placed.getValue("sa") > 0.8f)
        assertEquals(0f, placed.getValue("da"), 1e-4f)
        assertEquals(0f, placed.getValue("fa"), 1e-4f)
        assertEquals(0.9f, placed.getValue("ga"), 1e-4f)
        val none = against(null)
        assertTrue(none.toString(), listOf("sa", "da", "fa", "ga").all { none.getValue(it) == 0f })
    }

    // a key of one letter has its place too where the keys lean (GroupedKeyboard.tapped), so a slip from it onto a key
    // of two weighs that key's letters: A tapped at its right edge, toward SD (aa spells nothing), puts the tap onto
    // SD's left edge, s's half: da a whole against d, sa none; and the place of a key of one letter weighs nothing of its own,
    // L; (微软 双拼's ing) l alone however far right
    @Test
    @Uat("PY-152")
    fun aKeyOfOneLetterHasItsPlaceForASlipFromItAlone() {
        val keys = T9Segmenter(abbreviations = false, letters = NineKeyLetters.G18, neighbours = true)
        fun against(typed: String, taps: FloatArray, touches: Array<Touch?>?) = keys.segment(typed, taps, touches).againstOf(0, 2)
        val a = tap(G18, NineKeyLetters.G18.key('a'), dx = 0.45f)
        val slipped = against(g18("a a"), floatArrayOf(0.95f, 0.5f), touchesAt(2, 0, a))
        assertTrue(slipped.toString(), slipped.getValue("da") > 0.8f)
        assertEquals(0f, slipped.getValue("sa"), 1e-4f)
        val l = against(g18("l a"), floatArrayOf(1f, 0.5f), null)
        assertEquals(0f, l.getValue("la"), 1e-4f)
    }

    // 双拼's 18 and 14 keys (061-neighbour-groups), each scheme's: [BN][F] (ben) with BN tapped at its right edge,
    // toward M, reads men too; in the middle, with no place or with the option off, nothing. 微软 has no 14 keys
    @Test
    @Uat("SP-48", "SP-49")
    fun shuangpinsEighteenAndFourteenKeysReadAGroupAsTheOneNextToIt() {
        for ((letters, board) in listOf(NineKeyLetters.G18 to G18, NineKeyLetters.G14 to G14)) {
            val schemes = listOf("小鹤" to XIAOHE, "自然码" to ZIRANMA) + if (letters == NineKeyLetters.G18) listOf("微软" to MICROSOFT) else emptyList()
            for ((name, scheme) in schemes) {
                val what = "$name ${letters.tag()}"
                val keys = GroupedShuangpinSegmenter(scheme, letters, neighbours = true)
                val typed = "bf".map(letters::key).joinToString("")
                val edge = touchesAt(2, 0, tap(board, letters.key('b'), dx = 0.45f))
                assertTrue(what, keys.segment(typed, null, edge).slipsOf(0, 2).containsKey("men"))
                assertFalse(what, keys.segment(typed, null, touchesAt(2, 0, tap(board, letters.key('b')))).anySlip())
                assertFalse(what, keys.segment(typed).anySlip())
                assertFalse(what, GroupedShuangpinSegmenter(scheme, letters).segment(typed, null, edge).anySlip())
            }
        }
    }

    // 双拼's nine keys: 99 with its second 9 tapped at its top, toward 6, reads 96 (wo) as well; like pinyin's nine
    // keys, no slip costing under -3 (the -4 of the other keyboards would read one tapped a little lower)
    @Test
    @Uat("SP-50")
    fun shuangpinsNineKeysReadADigitAsTheOneItWasTappedTowards() {
        val keys = GroupedShuangpinSegmenter(XIAOHE, NineKeyLetters.NINE_SP, neighbours = true)
        val typed = "99".map(TouchBoxes::nineSp).joinToString("")
        fun at(dy: Float) = touchesAt(2, 1, tap(TouchBoxes.NINE_SP, TouchBoxes.nineSp('9'), dy = dy))
        assertTrue("wo" in keys.segment(typed, null, at(-0.45f)).slipsOf(0, 2))
        assertFalse(keys.segment(typed, null, at(0f)).anySlip())
        assertFalse(keys.segment(typed).anySlip())
        assertFalse("wo" in keys.segment(typed, null, at(-0.4f)).slipsOf(0, 2))
        val floorFour = GroupedShuangpinSegmenter(XIAOHE, NineKeyLetters.NINE_SP, neighbours = true, slipCost = SlipCost())
        assertTrue("wo" in floorFour.segment(typed, null, at(-0.4f)).slipsOf(0, 2))
    }

    // 注音大千 (061-neighbour-groups): a symbol key's neighbours the symbol keys around it, its tone keys none.
    // ㄈㄧ (spelling nothing) with ㄈ tapped at its right edge, toward ㄌ, reads ㄌㄧ (li) too; in the middle nothing.
    // ㄇㄚ, which spells ma, with ㄇ toward ㄋ never: past 大千's floor -1.75 (the user's 11-16 rule), na costs 2 more
    @Test
    @Uat("PY-150")
    fun dachenReadsASymbolAsTheOneNextToIt() {
        val keys = T9Segmenter(abbreviations = false, letters = ZhuyinKeys.DACHEN, neighbours = true)
        val f = ZhuyinKeys.symbolCode('ㄈ')
        val typed = "$f${ZhuyinKeys.symbolCode('ㄧ')}"
        assertTrue(keys.segment(typed, null, touchesAt(2, 0, tap(TouchBoxes.DACHEN, f, dx = 0.45f))).slipsOf(0, 2).containsKey("li"))
        assertFalse(keys.segment(typed, null, touchesAt(2, 0, tap(TouchBoxes.DACHEN, f))).anySlip())
        assertFalse(keys.segment(typed).anySlip())
        assertFalse(T9Segmenter(abbreviations = false, letters = ZhuyinKeys.DACHEN).segment(typed, null, touchesAt(2, 0, tap(TouchBoxes.DACHEN, f, dx = 0.45f))).anySlip())
        val m = ZhuyinKeys.symbolCode('ㄇ')
        assertFalse(keys.segment("$m${ZhuyinKeys.symbolCode('ㄚ')}", null, touchesAt(2, 0, tap(TouchBoxes.DACHEN, m, dx = 0.49f))).anySlip())
        // ㄉ, between ㄅ and ˇ on the number row: ˇ to its right no neighbour, ㄅ to its left one
        val d = tap(TouchBoxes.DACHEN, ZhuyinKeys.symbolCode('ㄉ'))
        assertNull(d.near(ZhuyinKeys.toneCode(3)))
        assertTrue(d.near(ZhuyinKeys.symbolCode('ㄅ')) != null)
    }

    // 注音九键 (read since the user's 11-16, over -2): [ㄖㄥㄩ][ㄅㄉㄚ], spelling nothing as typed, with ㄖㄥㄩ
    // tapped at its very left edge, toward ㄈㄌㄝ, reads fa and la; a little in (-0.45), past the floor, nothing
    @Test
    @Uat("PY-151")
    fun zhuyinsNineKeysReadAGroupAsTheOneNextToIt() {
        val keys = T9Segmenter(abbreviations = false, letters = ZhuyinKeys.NINE, neighbours = true)
        val r = ZhuyinKeys.groupCode(10)
        val typed = "$r${ZhuyinKeys.groupCode(0)}"
        val slips = keys.segment(typed, null, touchesAt(2, 0, tap(TouchBoxes.ZHUYIN_NINE, r, dx = -0.49f))).slipsOf(0, 2)
        assertTrue(slips.toString(), slips.keys.containsAll(listOf("fa", "la")))
        assertFalse(keys.segment(typed, null, touchesAt(2, 0, tap(TouchBoxes.ZHUYIN_NINE, r, dx = -0.45f))).anySlip())
        assertFalse(keys.segment(typed, null, touchesAt(2, 0, tap(TouchBoxes.ZHUYIN_NINE, r))).anySlip())
        assertFalse(keys.segment(typed).anySlip())
    }

    @Test
    fun aTapIsPutOntoTheKeyMeantAtItsEdgeNearestTheKeyHit() {
        val near = Near('m', 1f, 0f)
        // a tap at the right edge of the key hit is at the left of the one to its right
        assertEquals(0f, T9Segmenter.placeOn(Touch(0.45f, 0f, 72f, 50f), near, 0.9f), 0.06f)
        assertNull(Touch(0f, 0f, 1f, 1f).near('x'))
    }

    // review L4: a layout of the 18 keys' groups with WE stacked, W over E. A tap at RT's top left read as a slip
    // from WE is on WE's top right: along its letters that is down, near its top, so W, as a tap on WE there is
    // sent (GroupedKeyboard.tapped); across, its right edge, it would be E
    @Test
    fun aSlipOntoAKeyWhoseLettersAreStackedIsPlacedDownIt() {
        val we = NineKeyLetters.G18.key('w')
        val touch = Touch(-0.45f, -0.4f, 36f, 50f)
        val stacked = T9Segmenter.placeOn(touch, Near(we, -1f, 0f, stacked = true), 0.1f)
        assertEquals(0.1f, stacked, 0.01f)
        assertEquals(0f, NineKeyLetters.G18.against(we, 0, stacked), 0f)
        assertTrue(NineKeyLetters.G18.against(we, 1, stacked) > 0f)
        // the same keys with WE across: the right half, E
        val across = T9Segmenter.placeOn(touch, Near(we, -1f, 0f), 0.1f)
        assertEquals(1f, across, 0.06f)
        assertTrue(NineKeyLetters.G18.against(we, 0, across) > 0f)
        // a place of two dimensions (a layout's own groups, whose rows the engine has) stays both
        val both = T9Segmenter.placeOn(touch, Near(we, -1f, 0f, stacked = true), NineKeyLetters.place(0.1f, 0.1f))
        assertEquals(NineKeyLetters.place(1f, 0.1f), both, 0.06f)
    }

    // a split keyboard's G on both halves: the left half's G tapped has F beside it and H across the
    // gap, too far to be read; the right's, H beside it
    @Test
    fun aKeyOnBothHalvesHasTheNeighboursOfTheCopyTapped() {
        val box = { x: Float -> KeyBoxes.Box(x, 0f, x + 40f, 56f) }
        val keys = listOf('f' to box(0f), 'g' to box(40f), 'g' to box(200f), 'h' to box(240f))
        val left = KeyBoxes.touch(keys, 1, 79f, 28f)!!
        assertEquals(-1f, left.near('f')!!.nx, 1e-6f)
        assertTrue(cost.of(left, left.near('h')!!, legal = false).isNaN())
        val right = KeyBoxes.touch(keys, 2, 230f, 28f)!!
        assertEquals(1f, right.near('h')!!.nx, 1e-6f)
        assertTrue(cost.of(right, right.near('f')!!, legal = false).isNaN())
        assertEquals(0.25f, right.dx, 1e-6f)
        assertNull(KeyBoxes.touch(keys, 4, 0f, 0f))
    }

    // an evaluation set's column: written and read back the same, a key around it a 微软 `;` too
    @Test
    fun aTouchAsTextReadsBackTheSame() {
        val t = TouchBoxes.tap(TouchBoxes.QWERTY_SEMICOLON, 'l', dx = 0.3f, dy = -0.1f)
        assertTrue(t.near.any { it.char == ';' })
        assertEquals(t, Touch.parse(t.text()))
        assertNull(Touch.parse("0.1,0.2"))
        assertNull(Touch.parse("0,0,40,56|x"))
    }

    // a slip's readings are an edge of their own, every match of it NEIGHBOUR, never among a reading as typed's:
    // what reads the input as typed (withoutOtherReadings, readsWhole, the decoder's whole filters) drops them by the edge
    @Test
    fun slipReadingsAreEdgesOfTheirOwn() {
        val graphs = listOf(
            PinyinSegmenter(neighbours = true).segment("nihso", null, touchesAt(5, 3, tap(QWERTY, 's', dx = -0.45f))),
            PinyinSegmenter(neighbours = true, slipCost = SlipCost()).segment("tama", null, touchesAt(4, 0, tap(QWERTY, 't', dx = 0.4f))),
            ShuangpinSegmenter(XIAOHE, neighbours = true, slipCost = SlipCost()).segment("ta", null, touchesAt(2, 0, tap(QWERTY, 't', dx = 0.35f))),
            T9Segmenter(abbreviations = false, neighbours = true).segment("99", null, touchesAt(2, 1, tap(NINE, '9', dy = -0.4f))),
            NineKeyLetters.G18.let { l ->
                GroupedShuangpinSegmenter(XIAOHE, l, neighbours = true).segment("bf".map(l::key).joinToString(""), null, touchesAt(2, 0, tap(G18, l.key('b'), dx = 0.45f)))
            },
            ZhuyinKeys.symbolCode('ㄇ').let { m ->
                T9Segmenter(abbreviations = false, letters = ZhuyinKeys.DACHEN, neighbours = true, slipCost = SlipCost())
                    .segment("$m${ZhuyinKeys.symbolCode('ㄚ')}", null, touchesAt(2, 0, tap(TouchBoxes.DACHEN, m, dx = 0.45f)))
            },
        )
        for (g in graphs) {
            assertTrue(g.input, g.hasOtherReadings)
            for (e in 0 until g.edgeCount) {
                val m = g.matches(e)
                val neighbour = (0 until m.size).map { m.flags(it) and SyllableMatches.NEIGHBOUR != 0 }
                assertTrue("${g.input} ${g.text(e)}", if (g.isSlip(e)) neighbour.all { it } && m.size > 0 else neighbour.none { it })
            }
            assertFalse(g.input, (0 until g.withoutOtherReadings().edgeCount).any { g.withoutOtherReadings().isSlip(it) })
        }
    }
}
