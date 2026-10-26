/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/06-pinyin.md's 14-key cases whose 预期 is the engine's (design k14-tap-position §4, review
 * S3): the keys tapped as GroupedKeyboard sends them, each a code with where across its key it fell
 * ([Keyboard.tapKey]). The device's cases test the keyboard's side, the place a touch gives.
 */
class Keys14UatTest {

    private val nihao = NineKeyLetters.G14.typings("nihao").single() // BN UI GH AS OP
    private val centre = List(5) { 0.5f }
    private val onNihao = listOf(0.75f, 0.75f, 0.75f, 0.25f, 0.25f)
    private val onBuhao = listOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f)

    // each key tapped at its place, its keysym the character's, as the service sends the screen's keys
    private fun HostReplay.tap(keys: String, at: List<Float>) {
        keys.indices.forEach { key(EngineReplay.symOf(Keyboard.tapKey(keys[it], at[it]))) }
        pause()
    }

    // the 14 keys' codes the learning record holds, and whether it has their kind
    private fun learnedKeys(): Set<String> {
        val record = String(UatEngines.learningRecord(), Charsets.UTF_8)
        assertTrue("no ${Kinds.keys(NineKeyLetters.G14.tag())} in the record", Kinds.keys(NineKeyLetters.G14.tag()) in record)
        // a tap's code (its place) in the record would be learned as other keys than the bare ones
        assertTrue("a tap's code in the record", record.codePoints().noneMatch { it in Keyboard.TAPS })
        return Regex("[${Char(NineKeyLetters.G14_BLOCK.first)}-${Char(NineKeyLetters.G14_BLOCK.last)}]+").findAll(record).map { it.value }.toSet()
    }

    @Test
    @Uat("PY-85")
    fun atTheKeysCentresTheFirstIsTheModelsAndYourIsOnThePage() = with(HostReplay(engines = UatEngines.fresh())) {
        tap(nihao, centre)
        assertEquals("不好", candidates.first())
        assertTrue("$candidates", "你好" in candidates.take(5))
        pick("你好")
        assertEquals("你好", text)
    }

    @Test
    @Uat("PY-100")
    fun tappedOnTheLettersNihaoIsFirst() = with(HostReplay(engines = UatEngines.fresh())) {
        tap(nihao, onNihao)
        assertEquals("你好", candidates.first())
    }

    @Test
    @Uat("PY-101")
    fun tappedOnTheLettersBuhaoIsFirst() = with(HostReplay(engines = UatEngines.fresh())) {
        tap(nihao, onBuhao)
        assertEquals("不好", candidates.first())
    }

    @Test
    @Uat("PY-102")
    fun whereTheKeysWereTappedIsNotLearnedAndQuickInputGetsTheLetter() {
        val atCentre = with(HostReplay(engines = UatEngines.fresh())) {
            tap(nihao, centre)
            pick("你好")
            learnedKeys()
        }
        val onLetters = with(HostReplay(engines = UatEngines.fresh())) {
            tap(nihao, onNihao)
            pick("你好")
            learnedKeys()
        }
        assertTrue("$onLetters", nihao in onLetters)
        assertEquals(atCentre, onLetters)
        with(HostReplay(engines = UatEngines.fresh())) {
            quick()
            tap(g14("bn"), listOf(0.75f))
            enter()
            assertEquals("n", text)
            assertTrue(text, text.codePoints().noneMatch { Character.getType(it) == Character.PRIVATE_USE.toInt() })
        }
    }
}
