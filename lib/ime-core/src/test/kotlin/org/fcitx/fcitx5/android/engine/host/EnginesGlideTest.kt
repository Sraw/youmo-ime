/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.ENGLISH
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.HANDWRITING
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.PINYIN
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.SHUANGPIN
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.T9
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.WUBI
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginesGlideTest : EnginesFixture() {

    private fun glide(keys: String, layout: GlideLayout = GlideLayout.QWERTY) = Glide(keys.flatMap { layout.centre(it).toList() }.toFloatArray(), layout)

    private fun Engines.glided(im: String, keys: String, layout: GlideLayout = GlideLayout.QWERTY) = run {
        assertTrue(offerGlide(im, glide(keys, layout)))
        onEvent(im, EngineEvent.CHAR, Keyboard.GLIDE)
    }

    @Test
    fun pinyinTypesTheReadingAndAGlideAfterOneGoesOnAfterABoundary() {
        val engines = Engines(::load, null, warmUps = inPlace)
        assertEquals("ni", engines.glided(PINYIN, "ni").preedit.replace(" ", ""))
        val both = engines.glided(PINYIN, "hao")
        assertEquals("你好", both.candidates.first())
    }

    @Test
    fun aGlideAfterACharacterLookedUpByStrokesCommitsIt() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.type(PINYIN, "uz")
        val s = engines.glided(PINYIN, "hao")
        assertEquals("好", s.commit)
        assertEquals("好", s.candidates.first())
    }

    @Test
    fun englishCommitsItsWord() {
        val engines = Engines(::load, null, warmUps = inPlace)
        assertEquals("the ", engines.glided(ENGLISH, "the").commit)
    }

    @Test
    fun shuangpinGlidesItsSchemesKeys() {
        val engines = Engines(::load, null, warmUps = inPlace)
        val keys = EngineSettings().scheme.encode(Syllables.id("ni"))!!
        assertEquals("你", engines.glided(SHUANGPIN, keys).candidates.first())
    }

    @Test
    fun theNineKeysGlideTheirLettersTypedAsThemselves() {
        val engines = Engines(::load, null, warmUps = inPlace)
        val s = engines.glided(T9, "hao", GlideLayout.nineKeys())
        // the letters, not 426's other readings (gan, ian …); a glide after it goes on after a boundary
        assertEquals("hao", s.preedit.replace(" ", "").lowercase())
        assertEquals("好拟", engines.glided(T9, "ni", GlideLayout.nineKeys()).candidates.first())
    }

    @Test
    fun aTableGlidesACodeAndAGlideAfterItPicksItsFirst() {
        val engines = Engines(::load, null, warmUps = inPlace).apply { settings = wubiShown }
        assertEquals("你", engines.glided(WUBI, "wqiy").candidates.first())
        val s = engines.glided(WUBI, "vbg")
        assertEquals("你", s.commit)
        assertEquals("好", s.candidates.first())
    }

    @Test
    fun handwritingAndAnyOtherDoNotGlide() {
        val engines = Engines(::load, null, warmUps = inPlace)
        assertFalse(engines.offerGlide(HANDWRITING, glide("ni")))
        assertFalse(engines.offerGlide("keyboard-de", glide("ni")))
    }
}
