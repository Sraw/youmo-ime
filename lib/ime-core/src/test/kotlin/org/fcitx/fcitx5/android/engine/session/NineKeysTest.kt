/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.KeyedSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** [NineKeys]' bar in the user's order (U-23): the best reading's syllable, then those taken most for their keys. */
class NineKeysTest {

    // 646 reads min and nin, 您 the commoner
    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                .unigram("您", -2f, 0f).entry("您", syl("nin"))
                .unigram("民", -3f, 0f).entry("民", syl("min"))
                .build().toByteArray(),
        ),
    )

    private fun offered(taken: Map<String, Map<String, Int>>, best: String? = null): List<String> {
        val input = StringBuilder()
        val pressed = PressedKeys()
        pressed.insert(0, "646", input = input, letters = NineKeyLetters.STANDARD)
        input.append("646")
        // the habit asked for by the keys pressed, as a take files them
        val keys = NineKeys(data, NineKeyLetters.STANDARD, pressed) { digits -> taken[digits].orEmpty() }
        if (best != null) keys.note(0, listOf(3), intArrayOf(Syllables.id(best)))
        keys.offer(input, 0, T9Segmenter(abbreviations = false).segment(input.toString()))
        return keys.offered.filter { it.length == 3 }
    }

    @Test
    fun theSyllablesTakenMostGoFirstAfterTheBestReadings() {
        assertEquals(listOf("nin", "min"), offered(emptyMap()))
        val minTwice = mapOf("646" to mapOf("min" to 2))
        assertEquals(listOf("min", "nin"), offered(minTwice))
        // the best reading's stays first
        assertEquals(listOf("nin", "min"), offered(minTwice, best = "nin"))
        // a habit of other keys is none here
        assertEquals(listOf("nin", "min"), offered(mapOf("64" to mapOf("min" to 2))))
    }

    // the 14 keys (design §5.1): their codes found as the keys, through the segmenter's isKey
    @Test
    fun onTheFourteenKeysTheFirstKeysAreOfferedTakenAndGivenBack() {
        val g14 = NineKeyLetters.G14
        val segmenter = T9Segmenter(abbreviations = false, letters = g14)
        val typed = g14.typings("nin").single() // BN UI BN
        val input = StringBuilder(typed)
        val pressed = PressedKeys()
        pressed.insert(0, typed, input = "", letters = g14)
        val keys = NineKeys(data, g14, pressed, segmenter::isKey)
        keys.offer(input, 0, segmenter.segment(input.toString()))
        // BN UI BN is nin or bin, not min (M is a key of its own); 您 the commoner
        assertEquals("nin", keys.offered.first())
        assertTrue(keys.offered.toString(), "bin" in keys.offered && "min" !in keys.offered)
        // BN's letters, as initials, last
        assertEquals(listOf("b", "n"), keys.offered.takeLast(2))
        val at = keys.take(input, keys.offered.indexOf("nin"), keys.id, cursor = input.length)
        assertEquals("nin'", input.toString())
        assertEquals(input.length, at)
        assertEquals(0, keys.giveBack(input))
        assertEquals(typed, input.toString())
    }

    // where the 14 keys were tapped (design k14-tap-position §2.3, review S7, N8): a syllable a tap
    // clearly goes against after the rest, the best reading's not noted so the rule is what orders
    private fun offeredTapped(first: Float): List<String> {
        val g14 = NineKeyLetters.G14
        val segmenter = T9Segmenter(abbreviations = false, letters = g14)
        val typed = g14.typings("nin").single() // BN UI BN
        val pressed = PressedKeys()
        for ((k, c) in typed.withIndex()) pressed.insert(k, c.toString(), tapAt = if (k == 0) first else Float.NaN, input = typed.take(k), letters = g14)
        val keys = NineKeys(data, g14, pressed, segmenter::isKey)
        keys.offer(StringBuilder(typed), 0, segmenter.segment(typed))
        // nun too, after nin either way: n's
        return keys.offered.filter { it == "nin" || it == "bin" }
    }

    @Test
    fun aSyllableATapClearlyGoesAgainstComesAfterTheRest() {
        // 您 the commoner: nin first with no place
        assertEquals(listOf("nin", "bin"), offeredTapped(Float.NaN))
        // BN tapped on b's centre: nin's n goes against it
        assertEquals(listOf("bin", "nin"), offeredTapped(0.25f))
        assertEquals(listOf("bin", "nin"), offeredTapped(0f))
        // a little off the middle, or on n's side: as with no place
        for (at in listOf(7 / 16f, 0.5f, 9 / 16f, 0.75f)) assertEquals("$at", listOf("nin", "bin"), offeredTapped(at))
    }

    // [input] of [segmenter]'s keys, its key at [index] tapped at [tap]: the bar
    private fun offeredOn(data: PinyinData, segmenter: KeyedSegmenter, input: String, index: Int, tap: Float): List<String> {
        val pressed = PressedKeys()
        for ((k, c) in input.withIndex()) pressed.insert(k, c.toString(), tapAt = if (k == index) tap else Float.NaN, input = input.take(k), letters = segmenter.letters)
        val keys = NineKeys(data, segmenter.letters, pressed, segmenter::isKey)
        keys.offer(StringBuilder(input), 0, segmenter.segment(input))
        return keys.offered
    }

    private class OwnPresses(val why: String, val segmenter: KeyedSegmenter, val input: String, val index: Int, val tap: Float)

    // a syllable's bar is weighed against its own presses alone, not a tap on the next syllable's key
    @Test
    fun aTapIsWeighedOnlyAgainstTheSyllablesOwnPresses() {
        val zhong = pinyinDataOf(Triple("中", "zhong", -2f), Triple("吃", "chi", -3f))
        val g18 = GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, NineKeyLetters.G18)
        val g14 = T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14)
        val rows = listOf(
            OwnPresses(
                "18 keys: 双拼's zhong is 2 keys and 5 letters, chi's IO next tapped on i's side; catches letters " +
                    "counted as presses (the 18 keys send no place, so the tap is given here)",
                g18, "vsii".map { NineKeyLetters.G18.key(it) }.joinToString(""), 2, 0f,
            ),
            OwnPresses(
                "14 keys, which do send a place: zhong spans ZX GH OP BN GH, chi's CV next tapped on v's side; letters " +
                    "are presses here, so this catches only a span past the syllable",
                g14, NineKeyLetters.G14.typings("zhongchi").first(), 5, 1f,
            ),
        )
        assertRows(rows, { it.why }) {
            val untapped = offeredOn(zhong, it.segmenter, it.input, it.index, Float.NaN)
            assertTrue(untapped.toString(), "zhong" in untapped)
            assertEquals(untapped, offeredOn(zhong, it.segmenter, it.input, it.index, it.tap))
        }
    }

    @Test
    fun aTapOnUeIsWeighedAsOnU() {
        // üe typed ue (L UI ER): UI tapped on i's side goes against lve as against u, 略 the commoner
        val lve = pinyinDataOf(Triple("略", "lve", -2f), Triple("列", "lie", -3f))
        val g14 = T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14)
        val lue = NineKeyLetters.G14.typings("lue").first()
        assertEquals(listOf("lve", "lie"), offeredOn(lve, g14, lue, 1, Float.NaN).filter { it == "lve" || it == "lie" })
        assertEquals(listOf("lie", "lve"), offeredOn(lve, g14, lue, 1, 1f).filter { it == "lve" || it == "lie" })
    }
}
