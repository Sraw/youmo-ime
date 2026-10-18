/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PinyinGlideTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你好", -3f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("女", -3f, 0f)
                .unigram("我", -1.8f, 0f)
                .unigram("为", -2.2f, 0f)
                .entry("你好", syl("ni", "hao"))
                .entry("你", syl("ni"))
                .entry("女", syl("nv"))
                .entry("我", syl("wo"))
                .entry("为", syl("wei"))
                .build().toByteArray()
        )
    )

    private val glide = PinyinGlide(data)

    private fun path(keys: String) = keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray()

    @Test
    fun aWordsSyllablesApart() {
        val best = glide.decode(path("nihao"), GlideLayout.QWERTY, 3).first()
        assertEquals("ni'hao", best.keys)
        assertEquals("你好", best.word)
        // ü spelt v, as typed
        assertEquals("nv", glide.decode(path("nv"), GlideLayout.QWERTY, 3).first().keys)
    }

    @Test
    fun whereThePathLiftsDecidesALineAlongTheRow() {
        // w to o passes e and i: wo, not wei, which would lift a key short
        assertEquals("wo", glide.decode(path("wo"), GlideLayout.QWERTY, 3).first().keys)
        assertEquals("wei", glide.decode(path("wei"), GlideLayout.QWERTY, 3).first().keys)
    }

    @Test
    fun shuangpinGlidesItsSchemesKeysTheNineKeysTheLetters() {
        val scheme = ShuangpinScheme.ZIRANMA
        val keys = scheme.encode(Syllables.id("ni"))!! + scheme.encode(Syllables.id("hao"))!!
        val shuangpin = glide.decode(path(keys), GlideLayout.QWERTY, 3, PinyinGlide.Spelling.shuangpin(scheme)).first()
        // two keys a syllable, no boundary
        assertEquals(keys, shuangpin.keys)
        assertEquals("你好", shuangpin.word)
        val nine = GlideLayout.nineKeys()
        val t9 = glide.decode("nihao".flatMap { nine.centre(it).toList() }.toFloatArray(), nine, 3).first()
        assertEquals("ni'hao", t9.keys)
        assertEquals("你好", t9.word)
    }

    @Test
    fun eachReadingOnceAndNoneForNoPath() {
        val readings = glide.decode(path("nihao"), GlideLayout.QWERTY, 10).map { it.keys }
        assertEquals(readings.distinct(), readings)
        assertTrue(glide.decode(floatArrayOf(1f, 1f), GlideLayout.QWERTY, 3).isEmpty())
        assertTrue(glide.decode(path("nihao"), GlideLayout.QWERTY, 0).isEmpty())
    }
}
