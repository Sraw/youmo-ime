/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NewWordsTest {

    // 搭子 after many different characters and before many; 吃饭 known; 子一 only ever a chance run
    private val pages = listOf(
        "找个饭搭子一起吃饭" to "2023-05-01T00:00:00Z",
        "我的旅游搭子来了" to "2024-01-02T00:00:00Z",
        "健身搭子很重要" to "",
        "有没有饭搭子啊" to "2022-11-11T00:00:00Z",
        "找搭子，一起吃饭吧" to "2023-01-01T00:00:00Z",
        "考研搭子在哪里" to "2023-07-01T00:00:00Z",
    )

    private fun find(minCount: Int = 2, maxLength: Int = 3): List<NewWords.Candidate> {
        val finder = NewWords(setOf("吃饭", "一起", "旅游", "健身", "考研"), minCount, maxLength, sketchBits = 12)
        while (!finder.done) {
            pages.forEach { (text, date) -> finder.add(text, date) }
            finder.nextPass()
        }
        return ArrayList<NewWords.Candidate>().also { out -> finder.candidates { out += it } }
    }

    @Test
    fun aRunUsedFreelyAndOftenComesOutWithItsFirstYear() {
        val found = find()
        val dazi = found.first { it.text == "搭子" }
        assertEquals(6, dazi.count)
        assertEquals(2022, dazi.year)
        assertFalse(dazi.known)
        // 搭 and 子 occur only in it: the run is as frequent as its parts allow
        assertTrue(dazi.pmi > 0.5)
        // five different characters before it (饭 twice), six after (one the comma's boundary)
        assertTrue("left ${dazi.leftEntropy}", dazi.leftEntropy > 1.5)
        assertTrue("right ${dazi.rightEntropy}", dazi.rightEntropy > 1.5)
        val chifan = found.first { it.text == "吃饭" }
        assertTrue(chifan.known)
        assertEquals(2, chifan.count)
        // a known word's neighbours are not gathered
        assertTrue(chifan.leftEntropy.isNaN())
        // 饭搭子: twice (个饭搭子一, 有饭搭子啊), counted as a run of three
        assertEquals(2, found.first { it.text == "饭搭子" }.count)
        // a run across two words (一起|吃饭): nothing before or after it ever varies
        val qichi = found.first { it.text == "起吃" }
        assertEquals(2, qichi.count)
        assertEquals(0.0, qichi.leftEntropy, 1e-9)
        assertEquals(0.0, qichi.rightEntropy, 1e-9)
    }

    @Test
    fun aRunOfFourIsFoundAsTheWordsCommandLooksForThem() {
        // the length words looks for: 一起吃饭 twice
        val run = find(maxLength = 4).first { it.text == "一起吃饭" }
        assertEquals(2, run.count)
        // its likeliest split 一起吃|饭, 2 and 4 times in 46 characters
        assertEquals(Math.log10(2 / (2.0 * 4 / 46)), run.pmi, 1e-9)
        assertEquals(Math.log(2.0) + 0.25, run.leftEntropy, 1e-9)
        assertEquals(Math.log(2.0) + 0.25, run.rightEntropy, 1e-9)
    }

    @Test
    fun runsUnderTheCountAreNotKeptAndThePassesMustBeWalked() {
        assertTrue(find(minCount = 7).isEmpty())
        val finder = NewWords(emptySet(), 1, sketchBits = 12)
        assertThrows(IllegalArgumentException::class.java) { finder.candidates {} }
        assertThrows(IllegalArgumentException::class.java) { NewWords(emptySet(), 0) }
        assertThrows(IllegalArgumentException::class.java) { NewWords(emptySet(), 1, 5) }
        assertEquals(listOf(1, 2, 3, 4), listOf("㐀", "㐀㐀", "鿿一二", "一二三四").map { NewWords.lengthOf(NewWords.key(it, 0, it.length)) })
        assertEquals("搭子", NewWords.text(NewWords.key("饭搭子", 1, 2), 2))
        // four chars from the top of the Han block: the key must stay a non-negative long (a LongIndex key)
        val high = NewWords.key("鼠鼠我鿿", 0, 4)
        assertTrue(high >= 0)
        assertEquals("鼠鼠我鿿", NewWords.text(high, 4))
    }

    @Test
    fun aSketchNeverCountsUnderAndACollisionOnlyOver() {
        val keys = (1L..40L).map { it * 7919 }
        val narrow = NewWords.Sketch(4)
        val wide = NewWords.Sketch(10)
        keys.forEachIndexed { i, k ->
            repeat(i + 1) {
                narrow.add(k)
                wide.add(k)
            }
        }
        // sixteen cells for forty keys: counts over, none under
        keys.forEachIndexed { i, k -> assertTrue("$k", narrow.count(k) >= i + 1) }
        assertTrue(keys.indices.any { narrow.count(keys[it]) > it + 1 })
        // keys chosen so every count is exact with the lesser of two cells; with another hash, pick keys that share a cell again
        assertEquals(keys.indices.map { it + 1 }, keys.map(wide::count))
        assertEquals(0, wide.count(41L * 7919))
    }

    @Test
    fun aBoundaryIsANeighbourNeverSeenBefore() {
        val finder = NewWords(emptySet(), 2, 2, sketchBits = 12)
        while (!finder.done) {
            repeat(2) { finder.add("饭搭子。", "") }
            // other text, so that 搭子 is more than chance
            finder.add("你好吗", "")
            finder.nextPass()
        }
        val out = ArrayList<NewWords.Candidate>().also { c -> finder.candidates { c += it } }
        val dazi = out.first { it.text == "搭子" }
        // the full stop stands for a different char each time: ln 2 over two, plus Miller-Madow's
        assertEquals(0.0, dazi.leftEntropy, 1e-9)
        assertEquals(Math.log(2.0) + 0.25, dazi.rightEntropy, 1e-9)
    }

    @Test
    fun aFrequentRunsNeighboursAreSampledAndARunOfTwoBelowChanceStillGathered() {
        val finder = NewWords(emptySet(), 2, 2, sketchBits = 12)
        while (!finder.done) {
            // 搭子 on 80 pages, after a different character each time: every other occurrence looked at
            repeat(80) { finder.add("${'一' + it}搭子", "") }
            // 社 and 牛 far more often apart than together: 社牛 below chance, gathered all the same
            repeat(3) { finder.add("社牛。${"社会".repeat(20)}${"牛奶".repeat(20)}", "") }
            finder.nextPass()
        }
        val out = ArrayList<NewWords.Candidate>().also { c -> finder.candidates { c += it } }
        val dazi = out.first { it.text == "搭子" }
        assertEquals(80, dazi.count)
        // 40 different characters once each, and Miller-Madow's 39 / 80
        assertEquals(Math.log(40.0) + 39.0 / 80, dazi.leftEntropy, 1e-9)
        val sheniu = out.first { it.text == "社牛" }
        assertTrue("pmi ${sheniu.pmi}", sheniu.pmi < NewWords.GATHER_PMI)
        assertFalse(sheniu.leftEntropy.isNaN())
    }

    @Test
    fun aFrequentRunOnOneLineIsSampledAllTheSame() {
        val finder = NewWords(emptySet(), 2, 2, sketchBits = 12)
        while (!finder.done) {
            finder.add("你好吗", "")
            // 搭子 50 times after a different character each: every other occurrence looked at
            finder.add((0 until 50).joinToString("") { "${'一' + it}搭子。" }, "")
            finder.nextPass()
        }
        val out = ArrayList<NewWords.Candidate>().also { c -> finder.candidates { c += it } }
        val dazi = out.first { it.text == "搭子" }
        assertEquals(50, dazi.count)
        // 25 different characters once each, and Miller-Madow's 24 / 50; after it the full stop each time
        assertEquals(Math.log(25.0) + 24.0 / 50, dazi.leftEntropy, 1e-9)
        assertEquals(Math.log(25.0) + 24.0 / 50, dazi.rightEntropy, 1e-9)
    }
}
