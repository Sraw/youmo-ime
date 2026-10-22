/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.junit.Assert.assertEquals
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
}
