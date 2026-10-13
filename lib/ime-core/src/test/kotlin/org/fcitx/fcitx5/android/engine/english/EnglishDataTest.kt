/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The keys are read where the words lie, not kept as 200k strings on the heap: each as [EnglishData.key]
 * makes it, whatever that drops or lowers. A key order a damaged file breaks offers no word, and says so.
 */
class EnglishDataTest {

    private fun builder(vararg words: String) = EnglishDataBuilder()
        .unigram(EnglishDataBuilder.UNKNOWN, -7f, 0f)
        .unigram(EnglishText.START, -9f, 0f)
        .apply { words.forEachIndexed { i, w -> unigram(w, -1f - i, 0f) } }

    @Test
    fun eachKeyReadInPlaceIsItsWordsKey() {
        // as is, lower-cased, less an apostrophe, both; ǅ is title case, not upper, so kept as it is
        val bytes = builder("the", "London", "don't", "I'm", "US", "ǅemal", "rock'n'roll").build().toByteArray()
        val data = EnglishData.load(ByteBuffer.wrap(bytes))
        assertEquals(7, data.keyed)
        for (rank in 0 until data.keyed) {
            val key = EnglishData.key(data.word(data.keyedWord(rank)))
            assertEquals(key, data.keyAt(rank))
            assertEquals(key, data.keyLength(rank), key.length)
            assertEquals(key, String(CharArray(data.keyLength(rank)) { data.keyChar(rank, it) }))
        }
    }

    @Test
    fun keysOutOfOrderOfferNoWordAndAreReportedOnce() {
        val bytes = builder("and", "the").build().toByteArray()
        // the key order's two values, 2 bits each (and, the: 2 then 3), swapped; found as the reader
        // finds it, so the section table's layout is DataFile's alone
        val order = DataFile.open(ByteBuffer.wrap(bytes), DataFile.KIND_ENGLISH, EnglishData.VERSION).section(EnglishData.KEY_ORDER)
        assertEquals(2L or (3L shl 2), order.getLong(8))
        order.putLong(8, 3L or (2L shl 2))
        // its checksum no longer holds
        assertThrows(DataFormatException::class.java) { EnglishData.load(ByteBuffer.wrap(bytes)) }
        val errors = ArrayList<DataFormatException>()
        // unverified, as the app reads it
        val data = EnglishData.load(ByteBuffer.wrap(bytes), verify = false) { errors += it }
        assertEquals(0, data.keyed)
        data.warmUp()
        assertEquals(0, data.keyed)
        assertEquals(1, errors.size)
    }
}
