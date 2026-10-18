/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.ENGLISH
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.PINYIN
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.SHUANGPIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginesGlideTest : EnginesFixture() {

    private fun glide(keys: String) = Glide(keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray(), GlideLayout.QWERTY)

    private fun Engines.glided(im: String, keys: String) = run {
        assertTrue(offerGlide(im, glide(keys)))
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
    fun noOtherInputMethodGlides() {
        val engines = Engines(::load, null, warmUps = inPlace)
        assertFalse(engines.offerGlide(SHUANGPIN, glide("ni")))
    }
}
