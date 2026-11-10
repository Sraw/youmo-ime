/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.LogHabits
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * 常用词 in a pinyin session (dev/design/060-common-words.md): a word's replacements first under its
 * whole pinyin, its first three characters' and its initials, on every pinyin keyboard; before a pin
 * and a custom phrase of order 1; picked as text, never learned; deleted from the keyboard.
 */
class CommonWordsInSessionTest {

    private val data = sessionTestData()
    private val day = { GregorianCalendar(2026, Calendar.NOVEMBER, 10) }

    private fun words(text: String) = CommonWords.parse(text).words

    private val log = LearnLog(null).apply { open() }
    private val written = ArrayList<LearnRecord>().also { w -> log.appended = { w += it } }

    private fun session(
        book: CommonWordBook,
        segmenter: Segmenter = PinyinSegmenter(),
        phrases: PhraseBook = PhraseBook(),
    ) = PinyinSession(
        data, segmenter, spell = segmenter !is PinyinSegmenter, pageSize = 20, prediction = false, phraseBook = phrases, commonWords = book,
        now = day, learnTo = LearnTo(LogHabits({ log })),
    )

    private fun Session.type(keys: String): List<String> {
        apply(Action.Reset)
        keys.forEach { apply(Action.Key(it)) }
        return candidates(0, 20).map { it.text }
    }

    private val niHao = CommonWordBook(words("你好[ni'hao]\t您好\t你好呀\n你好吗再见[ni'hao'ma'zai'jian]\t拜\n"))

    @Test
    fun theWholePinyinTheInitialsAndTheFirstThreeTypeIt() {
        val s = session(niHao)
        val whole = s.type("nihao")
        assertEquals(listOf("您好", "你好呀"), whole.take(2))
        // the word itself after them, as ranked
        assertEquals("你好", whole[2])
        assertEquals(listOf("您好", "你好呀"), s.type("nh").take(2))
        assertEquals("拜", s.type("nihaoma").first())
        assertEquals("拜", s.type("nhmzj").first())
        assertEquals("拜", s.type("ni'hao'ma'zai'jian").first())
    }

    @Test
    fun aPartOfAKeyOrMoreTypesNothing() {
        val s = session(niHao)
        for (typed in listOf("niha", "nihaom", "nihaon", "n")) assertTrue(typed, "您好" !in s.type(typed) && "拜" !in s.type(typed))
    }

    @Test
    fun spaceTypesTheFirstReplacement() {
        val s = session(niHao)
        s.type("nh")
        assertEquals("您好", s.apply(Action.Pick(0)).commit)
    }

    @Test
    fun beforeAPinAndAPhraseOfOrderOneEachTextOnce() {
        val phrases = PhraseBook(CustomPhrases.parse("nihao,1=你好呀\nnihao,1=嗨\n"))
        val s = session(niHao, phrases = phrases)
        // the phrases of order 1 ahead of the word, after the replacements
        assertEquals(listOf("您好", "你好呀", "嗨", "你好"), s.type("nihao").take(4))
        s.apply(Action.Pin(3))
        // a pin first after the replacements, the phrases a place down
        val shown = s.type("nihao")
        assertEquals(listOf("您好", "你好呀", "你好", "嗨"), shown.take(4))
        assertEquals(1, shown.count { it == "你好呀" })
    }

    @Test
    fun wordsOfOneKeyAllOfferedTheWholeFirst() {
        val book = CommonWordBook(words("你好吗再见[ni'hao'ma'zai'jian] 拜\n拟好[ni'hao] 拟\n你好[ni'hao] 您好 拟\n你好吗[ni'hao'ma] 吗\n"))
        val s = session(book)
        assertEquals(listOf("拟", "您好"), s.type("nihao").take(2))
        // 你好吗's whole before 你好吗再见's first three, though listed after it
        assertEquals(listOf("吗", "拜"), s.type("nihaoma").take(2))
    }

    @Test
    fun whatIsLeftAfterAPieceIsWhatIsRead() {
        val s = session(niHao)
        val shown = s.type("zainihao")
        // 在 picked off the front: nihao left
        s.apply(Action.Pick(shown.indexOfFirst { it == "在" || it == "再" }))
        assertEquals(listOf("您好", "你好呀"), s.candidates(0, 2).map { it.text })
    }

    @Test
    fun pickedAsTextNothingLearned() {
        val s = session(niHao)
        s.type("nihao")
        assertEquals("您好", s.apply(Action.Pick(0)).commit)
        assertEquals(emptyList<LearnRecord>(), written)
        // the control: the word picked is learned
        s.type("nihao")
        s.apply(Action.Pick(2))
        assertTrue(written.isNotEmpty())
    }

    @Test
    fun deletedFromTheKeyboardItsOneReplacementAlone() {
        val saved = ArrayList<CommonWords>()
        val book = CommonWordBook(words("你好[ni'hao]\t您好\t你好呀\n")) { saved += it }
        val s = session(book)
        s.type("nihao")
        // the only thing offered, learning or not (Q5)
        assertEquals(setOf(Offer.DELETE_COMMON_WORD), s.offers(0))
        s.learning = false
        assertEquals(setOf(Offer.DELETE_COMMON_WORD), s.offers(0))
        s.learning = true
        s.apply(Action.Unpin(0))
        assertEquals("你好[ni'hao] 你好呀", saved.single().words.single().toString())
        assertEquals("你好呀", s.candidates(0, 1).single().text)
        // the word itself is a word as ever
        assertTrue(Offer.DELETE_COMMON_WORD !in s.offers(s.candidates(0, 20).indexOfFirst { it.text == "你好" }))
    }

    @Test
    fun aSaveRefusedTakesNoEffect() {
        val book = CommonWordBook(words("你好[ni'hao]\t您好\n")) { throw java.io.IOException("not settled") }
        val s = session(book)
        s.type("nihao")
        s.apply(Action.Unpin(0))
        assertEquals("您好", s.candidates(0, 1).single().text)
    }

    @Test
    fun onEveryPinyinKeyboardByItsKeys() {
        // [keys]: 你好's whole and initials as typed there
        class Board(val name: String, val segmenter: Segmenter, val whole: String, val initials: String, val letters: NineKeyLetters? = null)
        val zrm = ShuangpinScheme.ZIRANMA
        val boards = listOf(
            Board("全拼 26 键", PinyinSegmenter(), "nihao", "nh"),
            Board("九键", T9Segmenter(abbreviations = false), "64426", "64"),
            Board("拼音 14 键", T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14), "nihao", "nh", NineKeyLetters.G14),
            // 自然码: ni ni, hao hk
            Board("双拼 26 键", ShuangpinSegmenter(zrm), "nihk", "nh"),
            Board("双拼 18 键", GroupedShuangpinSegmenter(zrm, NineKeyLetters.G18), "nihk", "nh", NineKeyLetters.G18),
            Board("九键双拼", GroupedShuangpinSegmenter(zrm, NineKeyLetters.NINE_SP), "nihk", "nh", NineKeyLetters.NINE_SP),
        )
        for (b in boards) {
            val s = session(niHao, b.segmenter)
            for (key in listOf(b.whole, b.initials)) {
                val typed = b.letters?.let { l -> key.map(l::key).joinToString("") } ?: key
                assertEquals("${b.name} $key", "您好", s.type(typed).firstOrNull())
            }
        }
    }
}
