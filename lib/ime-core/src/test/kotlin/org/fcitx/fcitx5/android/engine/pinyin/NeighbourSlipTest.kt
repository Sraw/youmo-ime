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
        // in the middle what spells nothing still reads, at its cost
        val middle = segmenter.segment("nihso", null, touchesAt(5, 3, tap(QWERTY, 's'))).slipsOf(2, 5)
        assertTrue(middle.getValue("hao").let { it < -3f && it >= -4f })
    }

    @Test
    @Uat("PY-137")
    fun aLegalSlipIsReadOnlyNearTheKeyMeant() {
        val segmenter = PinyinSegmenter(neighbours = true)
        // tade: in the middle of t, no ya; near y, ya, costing 2 more than a slip that spells nothing
        // (ta at the end may still become tai: no slip there, as before)
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
        val typed = PinyinSegmenter(neighbours = true).segment("nihao")
        assertTrue(typed.readsWhole(0, 5))
    }

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

    @Test
    fun aTapIsPutOntoTheKeyMeantAtItsEdgeNearestTheKeyHit() {
        val near = Near('m', 1f, 0f)
        // a tap at the right edge of the key hit is at the left of the one to its right
        assertEquals(0f, T9Segmenter.placeOn(Touch(0.45f, 0f, 72f, 50f), near, 0.9f), 0.06f)
        assertNull(Touch(0f, 0f, 1f, 1f).near('x'))
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
            PinyinSegmenter(neighbours = true).segment("tama", null, touchesAt(4, 0, tap(QWERTY, 't', dx = 0.4f))),
            ShuangpinSegmenter(XIAOHE, neighbours = true).segment("ta", null, touchesAt(2, 0, tap(QWERTY, 't', dx = 0.35f))),
            T9Segmenter(abbreviations = false, neighbours = true).segment("99", null, touchesAt(2, 1, tap(NINE, '9', dy = -0.4f))),
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
