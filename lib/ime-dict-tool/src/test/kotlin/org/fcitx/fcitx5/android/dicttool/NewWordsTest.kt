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
        // 搭 and 子 each occur only in it (子 once more in 子一 ... no: 子 is in every 搭子): the run is as frequent as its parts allow
        assertTrue(dazi.pmi > 0.5)
        // six different characters before it, five after (one end of text)
        assertTrue("left ${dazi.leftEntropy}", dazi.leftEntropy > 1.5)
        assertTrue("right ${dazi.rightEntropy}", dazi.rightEntropy > 1.5)
        val chifan = found.first { it.text == "吃饭" }
        assertTrue(chifan.known)
        assertEquals(2, chifan.count)
        // a known word's neighbours are not gathered
        assertTrue(chifan.leftEntropy.isNaN())
        // 饭搭子: three times, always followed by 一/来/啊 but preceded by 个/有/没 ... counted as a run of three
        assertEquals(2, found.first { it.text == "饭搭子" }.count)
        // a run across two words (一起|吃饭): nothing before or after it ever varies
        val qichi = found.first { it.text == "起吃" }
        assertEquals(2, qichi.count)
        assertEquals(0.0, qichi.leftEntropy, 1e-9)
        assertEquals(0.0, qichi.rightEntropy, 1e-9)

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
        val sketch = NewWords.Sketch(4)
        val keys = (1L..40L).map { it * 7919 }
        keys.forEachIndexed { i, k -> repeat(i + 1) { sketch.add(k) } }
        keys.forEachIndexed { i, k -> assertTrue("$k", sketch.count(k) >= i + 1) }
        // sixteen cells for forty keys: some counts are over, but the smallest key, added once, no more than its collisions
        assertTrue(sketch.count(keys[0]) >= 1)
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
        // 饭 before it both times; after it the full stop, which stands for a different char each time
        assertEquals(0.0, dazi.leftEntropy, 1e-9)
        assertEquals(Math.log(2.0), dazi.rightEntropy, 1e-9)
    }
}
