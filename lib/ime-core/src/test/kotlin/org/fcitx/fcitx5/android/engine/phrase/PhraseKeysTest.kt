/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.english.EnglishDecoderTest
import org.fcitx.fcitx5.android.engine.english.EnglishPhrases
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.table.dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/** A1: the phrases' keys typed on keys of letter groups ([PhraseKeys]), and where a table and English place them. */
class PhraseKeysTest {

    private val day = { GregorianCalendar(2026, Calendar.OCTOBER, 31) }
    private val g14 = NineKeyLetters.G14
    private val nine = NineKeyLetters.STANDARD

    private fun codes(letters: NineKeyLetters, key: String) = key.map(letters::key).joinToString("")

    @Test
    fun aCodeIsAnyOfItsLettersAndWhatIsFixedIsItself() {
        val typed = checkNotNull(PhraseKeys.of(codes(nine, "dz"), nine))
        // 39: d e f, then w x y z
        assertTrue(typed.reads("dz"))
        assertTrue(typed.reads("ew"))
        assertFalse(typed.reads("dza"))
        assertFalse(typed.reads("d"))
        // a key of another length is not tried
        assertTrue(typed.fits(2))
        assertFalse(typed.fits(1) || typed.fits(3))
        // a letter typed as itself (upper case), a syllable taken off the bar and its separator
        val fixed = checkNotNull(PhraseKeys.of("ni'" + codes(nine, "h") + "A" + codes(nine, "o"), nine))
        assertTrue(fixed.reads("nihao"))
        assertFalse(fixed.reads("mihao"))
        assertFalse(fixed.reads("nihbo"))
        // nothing but a separator, or a character no key types, is no key
        assertNull(PhraseKeys.of("'", nine))
        assertNull(PhraseKeys.of("1", nine))
        assertTrue(checkNotNull(PhraseKeys.of(codes(g14, "qm"), g14)).reads("wm"))
    }

    @Test
    fun aKeyIsKeptLowerCaseAndLeadsWhereTurnedOn() {
        val phrases = CustomPhrases.parse("DZ,1=北京\nyx,-1=关了\n")
        assertTrue("dz" in phrases)
        assertTrue(phrases.leadsTo("d"))
        assertFalse(phrases.leadsTo("y"))
        assertFalse(phrases.leadsTo("dzz"))
    }

    @Test
    fun aSyllableTakenIn双拼IsItsKeys() {
        val segmenter = GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, NineKeyLetters.G18)
        // 自然码: zhuang vd, 双 ud; an initial zh is v
        assertTrue("vd" in segmenter.takenAs("zhuang"))
        assertEquals(listOf("v"), segmenter.takenAs("zh"))
        val typed = checkNotNull(PhraseKeys.of("zhuang'" + codes(NineKeyLetters.G18, "ud"), NineKeyLetters.G18, taken = segmenter::takenAs))
        assertTrue(typed.reads("vdud"))
        assertFalse(typed.reads("zhuangud"))
    }

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Action.Key(it)) }.last()

    private fun Session.shown() = candidates(0, 30).map { it.text }

    @Test
    fun onTheNineKeysEveryPhraseOfTheKeysIsOfferedAndTheBuiltInOnes() {
        // 77 is rq and pp alike; the built-in dates under rq too, on full pinyin's keyboards
        val book = PhraseBook(CustomPhrases.parse("pp,1=乒乓\nrq,9=我的日期\n"), CustomPhrases.BUILT_IN)
        val session = PinyinSession(sessionTestData(), T9Segmenter(abbreviations = false), spell = true, prediction = false, phraseBook = book, now = day)
        val shown = session.type("77").let { session.shown() }
        assertEquals("乒乓", shown.first())
        // the user's rq has the key: the built-in ones are not offered under it (CustomPhrases.over)
        assertTrue(shown.toString(), "我的日期" in shown)
        assertFalse(shown.toString(), "2026年10月31日" in shown)
        val bare = PinyinSession(sessionTestData(), T9Segmenter(abbreviations = false), spell = true, prediction = false, phraseBook = PhraseBook(builtIn = CustomPhrases.BUILT_IN), now = day)
        bare.type("77")
        assertTrue(bare.shown().toString(), "2026年10月31日" in bare.shown())
        // not in 双拼, where sj and xq spell syllables
        val shuangpin = PinyinSession(
            sessionTestData(), GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, NineKeyLetters.G18), spell = true, prediction = false,
            phraseBook = PhraseBook(builtIn = CustomPhrases.BUILT_IN), now = day,
        )
        // (日期 read there brings them as its word's, never as phrases under the key)
        shuangpin.type(codes(NineKeyLetters.G18, "rq"))
        assertTrue(shuangpin.shown().indices.none { Offer.DELETE_PHRASE in shuangpin.offers(it) })
    }

    @Test
    fun aSyllableTakenOffTheBarMustAgreeWithTheKey() {
        // 64426 is nihao and mihao alike, till ni is taken
        val book = PhraseBook(CustomPhrases.parse("nihao,1=你好呀\nmihao,2=米好\n"))
        val session = PinyinSession(sessionTestData(), T9Segmenter(abbreviations = false), spell = true, prediction = false, phraseBook = book, now = day)
        val typed = session.type(codes(nine, "nihao"))
        assertEquals(listOf("你好呀", "米好"), session.shown().take(2))
        val ni = session.apply(Action.Syllable(typed.syllables.indexOf("ni")))
        assertTrue(ni.preedit, ni.preedit.startsWith("ni'"))
        assertEquals("你好呀", session.shown().first())
        assertFalse("米好" in session.shown())
    }

    @Test
    fun aTablesPhraseIsNeverFirstNorUnderACodeThatCommitsItself() {
        val book = PhraseBook(CustomPhrases.parse("wq,1=我去\nwqiy,1=不出\n"))
        val session = TableSession(dictionary("w 人", "wq 你", "wq 们", "wqiy 好"), TableOptions.WUBI, phrases = book, clock = day)
        session.type("wq")
        assertEquals(listOf("你", "我去", "们"), session.shown().take(3))
        // 好 alone under wqiy commits itself, the phrase or not: typed blind
        session.apply(Action.Reset)
        assertEquals("好", session.type("wqiy").commit)
    }

    @Test
    fun englishKeepsTheWordSpaceCommitsFirst() {
        val book = PhraseBook(CustomPhrases.parse("the,1=the end\n"))
        val session = EnglishSession(EnglishDecoderTest.data(), phrases = EnglishPhrases(book, day))
        session.type("The")
        val shown = session.shown()
        assertEquals("The", shown.first())
        assertEquals("the end", shown[1])
        assertEquals("The ", session.apply(Action.Key(' ')).commit)
    }

    @Test
    fun englishNineKeysWithNoWordShowFirstWhatSpaceCommits() {
        val book = PhraseBook(CustomPhrases.parse("qm,1=张三\n"))
        val session = EnglishSession(EnglishDecoderTest.data(), phrases = EnglishPhrases(book, day))
        // 76: no word of the data's; each key's first letter is the guess space commits
        "qm".forEach { session.apply(Action.NineKey(nine.key(it), false)) }
        val shown = session.shown()
        assertEquals(listOf("pm", "张三"), shown.take(2))
        assertEquals("pm ", session.apply(Action.Key(' ')).commit)
    }

    // keys of two lengths read at once (a syllable taken in two spellings): one list, sorted, a key turned off in it too
    @Test
    fun keysOfSeveralLengthsAreReadSortedTurnedOffToo() {
        val phrases = CustomPhrases.parse("shd,1=长的\nud,1=短的\nuf,-1=关了\nug,1=不读\n")
        val typed = checkNotNull(PhraseKeys.of("sh'" + codes(nine, "d"), nine) { if (it == "sh") listOf("sh", "u") else listOf(it) })
        assertEquals(2..3, typed.lengths)
        // by length it would be ud uf shd
        assertEquals(listOf("shd", "ud", "uf"), phrases.keysRead(typed))
    }
}
