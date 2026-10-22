/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class EnginesGlideTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

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

    @Test
    fun aGlideAfterALoneStrokeKeyDoesNotCommitIt() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.type(PINYIN, "u")
        val s = engines.glided(PINYIN, "hao")
        assertEquals("", s.commit)
        assertEquals("好", s.candidates.first())
    }

    // ni'hao, the path's own, likelier than niao a bend away, whose word here is one of the coarse words'
    private val coarsePinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你好", -1f, 0f)
        .unigram("小穴", -3f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("好", -2.5f, 0f)
        .entry("你好", syl("ni", "hao"))
        .entry("小穴", syl("niao"))
        .entry("你", syl("ni"))
        .entry("好", syl("hao"))
        .build()
        .toByteArray()

    private fun loadCoarse(path: String): ByteBuffer = if (path == Engines.PINYIN_DATA) ByteBuffer.wrap(coarsePinyin) else load(path)

    @Test
    fun aCoarseWordIsNotAnotherReadingsOffer() {
        val engines = Engines(::loadCoarse, null, warmUps = inPlace)
        val s = engines.glided(PINYIN, "nihao")
        assertEquals("你好", s.candidates.first())
        assertTrue("小穴" !in s.candidates)
    }

    /** The user's words as the sessions have them, through Engines' own wiring: one added is offered, coarse or not. */
    @Test
    fun aCoarseWordTheUserAddedIsAnotherReadingsOffer() {
        val engines = Engines(::loadCoarse, null, warmUps = inPlace)
        // two characters, two syllables: as the settings take a word
        assertTrue(engines.addWord("小穴", "ni'ao"))
        val s = engines.glided(PINYIN, "nihao")
        assertEquals(listOf("你好", "小穴"), s.candidates.take(2))
    }

    // U-93 off: a glide reads the words as installed, as typing does (review-E-engine M2)
    @Test
    fun withHabitsOffAGlideOffersNoWordOfTheUsers() {
        val engines = Engines(::loadCoarse, null, warmUps = inPlace)
        assertTrue(engines.addWord("小穴", "ni'ao"))
        engines.useHabits = false
        assertFalse("小穴" in engines.glided(PINYIN, "nihao").candidates)
        engines.useHabits = true
        assertTrue("小穴" in engines.glided(PINYIN, "nihao").candidates)
    }

    // as coarsePinyin, niao's words two: 鸟, then 尿
    private val twoAtNiao = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你好", -1f, 0f)
        .unigram("鸟", -3f, 0f)
        .unigram("尿", -4f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("好", -2.5f, 0f)
        .entry("你好", syl("ni", "hao"))
        .entry("鸟", syl("niao"))
        .entry("尿", syl("niao"))
        .entry("你", syl("ni"))
        .entry("好", syl("hao"))
        .build()
        .toByteArray()

    /** A word blocked is no reading's word: the glide decoder, kept over the sessions made again, reads the next of it. */
    @Test
    fun aWordTheUserBlockedLeavesItsReadingToTheNext() {
        val engines = Engines({ if (it == Engines.PINYIN_DATA) ByteBuffer.wrap(twoAtNiao) else load(it) }, null, warmUps = inPlace)
        assertEquals(listOf("你好", "鸟"), engines.glided(PINYIN, "nihao").candidates.take(2))
        assertTrue(engines.blockWord("鸟", "niao"))
        val s = engines.glided(PINYIN, "nihao")
        assertEquals(listOf("你好", "尿"), s.candidates.take(2))
        assertTrue("鸟" !in s.candidates)
    }

    /** A word the user picked, through Engines' wiring of the counts: another reading's word before the model's likelier one. */
    @Test
    fun aWordTheUserLearnedIsAnotherReadingsOfferFirst() {
        val engines = Engines({ if (it == Engines.PINYIN_DATA) ByteBuffer.wrap(twoAtNiao) else load(it) }, null, warmUps = inPlace)
        assertEquals(listOf("你好", "鸟"), engines.glided(PINYIN, "nihao").candidates.take(2))
        engines.onEvent(PINYIN, EngineEvent.RESET, 0)
        repeat(3) {
            val at = engines.type(PINYIN, "niao").candidates.indexOf("尿")
            assertEquals("尿", engines.onEvent(PINYIN, EngineEvent.PICK, at).commit)
            engines.onEvent(PINYIN, EngineEvent.RESET, 0)
        }
        assertEquals(listOf("你好", "尿"), engines.glided(PINYIN, "nihao").candidates.take(2))
    }

    // 五笔 with a word at vbgf, 好 a key short of it
    private val wubiWord = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .entry("wqiy", "你")
        .entry("vbg", "好")
        .entry("vbgf", "你好")
        .build()
        .toByteArray()

    /**
     * A table's glide blocks as its session does, through Engines' wiring: a block in pinyin hides
     * none of the table's own (U-28 C12), a character blocked under one pinyin reading (好 hao) nor
     * a word.
     */
    @Test
    fun aTableGlideKeepsTheTablesOwnBlockedInPinyin() {
        val engines = Engines({ if (it == "${Engines.TABLE_DIR}/wbx.data") ByteBuffer.wrap(wubiWord) else load(it) }, null, warmUps = inPlace)
            .apply { settings = wubiShown }
        assertTrue(engines.blockWord("好", "hao"))
        assertEquals("好", engines.glided(WUBI, "vbg").candidates.first())
        engines.onEvent(WUBI, EngineEvent.RESET, 0)
        assertEquals("你好", engines.glided(WUBI, "vbgf").candidates.first())
        engines.onEvent(WUBI, EngineEvent.RESET, 0)
        assertTrue(engines.blockWord("你好", "ni'hao"))
        assertEquals("你好", engines.glided(WUBI, "vbgf").candidates.first())
    }

    // 五笔 with 你 and 拟 at one code, 你 the likelier
    private val wubiShared = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .entry("wqiy", "你")
        .entry("wqiy", "拟")
        .build()
        .toByteArray()

    /**
     * A word picked from a table is the table's to learn, through Engines' wiring: never counted for
     * the user model's order (D28), which the glide reads.
     */
    @Test
    fun aWordTheUserPickedInATableLeavesTheUserModelsOrder() {
        val engines = Engines({ if (it == "${Engines.TABLE_DIR}/wbx.data") ByteBuffer.wrap(wubiShared) else load(it) }, null, warmUps = inPlace)
            .apply { settings = wubiShown }
        assertEquals("你", engines.glided(WUBI, "wqiy").candidates.first())
        engines.onEvent(WUBI, EngineEvent.RESET, 0)
        val pinyin = engines.type(Engines.PINYIN, "ni").candidates
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        repeat(3) {
            val at = engines.type(WUBI, "wqiy").candidates.indexOf("拟")
            assertEquals("拟", engines.onEvent(WUBI, EngineEvent.PICK, at).commit)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
        }
        // the table's own order by its picks under the code (U-28), the glide reading it; pinyin's as it was
        assertEquals("拟", engines.glided(WUBI, "wqiy").candidates.first())
        engines.onEvent(WUBI, EngineEvent.RESET, 0)
        assertEquals(pinyin, engines.type(Engines.PINYIN, "ni").candidates)
    }

    /**
     * A table word blocked under its code, through Engines' wiring, leaves the glide too: the decoder
     * kept from a glide before reads it anew (the blocks are part of its version).
     */
    @Test
    fun aTableWordBlockedUnderItsCodeLeavesAGlideMadeBefore() {
        val engines = Engines({ if (it == "${Engines.TABLE_DIR}/wbx.data") ByteBuffer.wrap(wubiShared) else load(it) }, folder.newFolder(), warmUps = inPlace)
            .apply { settings = wubiShown }
        assertEquals("你", engines.glided(WUBI, "wqiy").candidates.first())
        engines.onEvent(WUBI, EngineEvent.RESET, 0)
        assertEquals("你", engines.type(WUBI, "wqiy").candidates.first())
        engines.onEvent(WUBI, EngineEvent.BLOCK, 0)
        engines.onEvent(WUBI, EngineEvent.RESET, 0)
        assertEquals("拟", engines.glided(WUBI, "wqiy").candidates.first())
        engines.close()
    }
}
