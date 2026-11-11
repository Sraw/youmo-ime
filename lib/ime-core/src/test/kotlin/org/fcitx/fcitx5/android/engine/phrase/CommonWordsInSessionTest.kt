/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.assertRows
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
        prediction: Boolean = false,
    ) = PinyinSession(
        data, segmenter, spell = segmenter !is PinyinSegmenter, pageSize = 20, prediction = prediction, phraseBook = phrases, commonWords = book,
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
        // each a key cut short or run on: no replacement of a key it is not
        assertRows(listOf("niha", "nihaom", "nihaon", "n"), { it }) { typed -> assertTrue(typed, "您好" !in s.type(typed) && "拜" !in s.type(typed)) }
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

    // §4: a replacement is text, the 联想 after it as at the start; the word itself picked goes on
    // from it as ever, and the 联想 offers no replacement
    @Test
    fun theLianxiangAfterAReplacementIsTheStartsAndOffersNone() {
        val s = session(CommonWordBook(words("再[zai]\t拜\n见[jian]\t剑\n")), prediction = true)
        assertEquals("拜", s.type("zai").first())
        val afterWord = s.apply(Action.Pick(s.candidates(0, 20).indexOfFirst { it.text == "再" }))
        assertEquals("再", afterWord.commit)
        val following = s.candidates(0, 20).map { it.text }
        assertEquals("见", following.first())
        assertTrue(following.toString(), "剑" !in following && "拜" !in following)
        // 见 picked from the 联想 is 见, not its replacement
        assertEquals("见", s.apply(Action.Pick(0)).commit)
        s.type("zai")
        assertEquals("拜", s.apply(Action.Pick(0)).commit)
        val afterReplacement = s.candidates(0, 20).map { it.text }
        assertTrue(afterReplacement.toString(), afterReplacement.firstOrNull() != "见" && "剑" !in afterReplacement)
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
    fun onEveryPinyinKeyboardByItsKeysTheInitialsOfTwoOnFullPinyinAlone() {
        // [keys]: 你好's whole, and 你好吗再见's initials, as typed there
        // [initialsOfTwo]: whether two initials type a word of two there (full pinyin's 26 keys alone)
        class Board(val name: String, val segmenter: Segmenter, val whole: String, val letters: NineKeyLetters? = null, val initialsOfTwo: Boolean = false)
        val zrm = ShuangpinScheme.ZIRANMA
        val boards = listOf(
            Board("全拼 26 键", PinyinSegmenter(), "nihao", initialsOfTwo = true),
            Board("九键", T9Segmenter(abbreviations = false), "nihao", NineKeyLetters.STANDARD),
            Board("拼音 14 键", T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14), "nihao", NineKeyLetters.G14),
            // 自然码: ni ni, hao hk
            Board("双拼 26 键", ShuangpinSegmenter(zrm), "nihk"),
            Board("双拼 18 键", GroupedShuangpinSegmenter(zrm, NineKeyLetters.G18), "nihk", NineKeyLetters.G18),
            Board("九键双拼", GroupedShuangpinSegmenter(zrm, NineKeyLetters.NINE_SP), "nihk", NineKeyLetters.NINE_SP),
        )
        assertRows(boards, { it.name }) { b ->
            val s = session(niHao, b.segmenter)
            fun typed(key: String) = b.letters?.let { l -> key.map(l::key).joinToString("") } ?: key
            assertEquals("${b.name} ${b.whole}", "您好", s.type(typed(b.whole)).firstOrNull())
            assertEquals("${b.name} nhmzj", "拜", s.type(typed("nhmzj")).firstOrNull())
            // two initials are a syllable's keys but on full pinyin's 26: the nine keys' 64 is ni, 双拼's nh nothing else
            val two = s.type(typed("nh"))
            assertEquals("${b.name} nh", b.initialsOfTwo, two.firstOrNull() == "您好")
        }
    }

    @Test
    fun aGlideOffersNone() {
        val s = session(niHao)
        s.apply(Action.Reset)
        "nihao".forEach { s.apply(Action.Key(it, glided = true)) }
        assertTrue("您好" !in s.candidates(0, 20).map { it.text })
    }

    @Test
    fun aSeparatorWhereASyllableEndsAlone() {
        val s = session(niHao)
        assertEquals("您好", s.type("ni'hao").first())
        assertEquals("您好", s.type("nihao'").first())
        assertTrue("您好" !in s.type("nih'ao"))
        assertTrue("拜" !in s.type("nihaom'a"))
    }

    @Test
    fun lueAndNueEitherWayOnFullPinyin() {
        val book = CommonWordBook(words("略[lve]\t掠\n虐[nve]\t疟\n绿[lv]\t緑\n"))
        val s = session(book)
        // the way the dictionary spells it and the way a user types it, each its row
        assertRows(listOf("lve" to "掠", "lue" to "掠", "nve" to "疟", "nue" to "疟"), { it.first }) { (typed, want) -> assertEquals(typed, want, s.type(typed).first()) }
        assertEquals("緑", s.type("lv").first())
        assertTrue("緑" !in s.type("lu"))
    }
}
