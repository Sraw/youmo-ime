/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PinyinSessionT9Test {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("米", -3f, 0f)
                .unigram("哦", -4f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("高", -3f, 0f)
                .unigram("你好", -2.5f, 0f)
                .unigram("米糕", -5f, 0f)
                .unigram("爸爸", -4f, 0f)
                .entry("你", syl("ni"))
                .entry("米", syl("mi"))
                .entry("哦", syl("o"))
                .entry("好", syl("hao"))
                .entry("高", syl("gao"))
                .entry("你好", syl("ni", "hao"))
                .entry("米糕", syl("mi", "gao"))
                .entry("爸爸", syl("ba", "ba"))
                .build().toByteArray(),
        ),
    )

    private fun session(user: UserModel? = null, refiner: SentenceRefiner? = null) =
        PinyinSession(data, T9Segmenter(abbreviations = false), spell = true, user = user, prediction = false, refiner = refiner)

    // a dictionary of [words], each with its score and its syllables apart by spaces
    private fun dataOf(vararg words: Triple<String, Float, String>) = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                .apply { words.forEach { (w, score, reading) -> unigram(w, score, 0f).entry(w, syl(*reading.split(' ').toTypedArray())) } }
                .build().toByteArray(),
        ),
    )

    private fun sessionOf(data: PinyinData) = PinyinSession(data, T9Segmenter(abbreviations = false), spell = true, prediction = false)

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    // [digit] pressed [times] fast, as the keyboard sends it: the key, then each press again its next letter
    private fun Session.press(digit: Char, times: Int, keys: NineKeyLetters = NineKeyLetters.STANDARD): Snapshot {
        var s = apply(Key(digit))
        for (n in 1 until times) s = apply(Action.Letter(keys.letters(digit)[n % keys.letters(digit).length], again = true))
        return s
    }

    @Test
    fun digitsShowAsThePinyinOfTheBestReading() {
        val s = session().type("64426")
        assertEquals("你好", s.candidates[0])
        assertEquals("ni hao", s.preedit)
        // the first digits' syllables, the best reading's first, the letters of 6 last
        assertEquals(listOf("ni", "mi", "o", "m", "n"), s.syllables)
    }

    @Test
    fun keysHandedOverTogetherReadAsTypedOneByOne() {
        // the syllables offered with their id: a pick from the list shown names it
        assertEquals(session().type("64426"), session().typeAll("64426"))
    }

    @Test
    fun aSyllableTakenNarrowsTheReadingsAndBackspaceGivesItBack() {
        val session = session()
        session.type("64426")
        val taken = session.apply(Action.Syllable(1))
        assertEquals("米糕", taken.candidates[0])
        // a syllable taken stands apart
        assertEquals("mi'gao", taken.preedit)
        // offered next: the digits after it
        assertTrue("gao" in taken.syllables && "hao" in taken.syllables)
        val back = session.apply(Backspace)
        assertEquals("你好", back.candidates[0])
        assertEquals(listOf("ni", "mi", "o", "m", "n"), back.syllables)
        // then a digit goes
        assertEquals("ni ha", session.apply(Backspace).preedit)
    }

    @Test
    fun twoTakenThenBackspaceGivesBackTheLast() {
        val session = session()
        session.type("64426")
        val first = session.apply(Action.Syllable(0))
        val both = session.apply(Action.Syllable(first.syllables.indexOf("hao")))
        assertEquals("你好", both.candidates[0])
        assertEquals(emptyList<String>(), both.syllables)
        val back = session.apply(Backspace)
        assertEquals("ni'hao", back.preedit)
        assertTrue("hao" in back.syllables)
    }

    @Test
    fun aRunOfAKeyPressedAgainIsReadAsKeysAndAsTheLetterItCameTo() {
        // 6 four times fast: m, as no four keys read
        val session = session()
        val m = session.press('6', 4)
        assertTrue("m" in m.syllables)
        val s = session.type("4426")
        assertEquals("米糕", s.candidates[0])
        assertEquals("mi gao", s.preedit)
        // 2 four times fast at a pace of keys, not letters: baba, which was 'a' (B, 2026-10-10)
        val baba = session().press('2', 4)
        assertEquals("爸爸", baba.candidates[0])
        assertEquals("ba ba", baba.preedit)
        assertTrue(baba.preedit.all { it in 'a'..'z' || it == ' ' })
    }

    @Test
    fun aLetterChosenGoesAfterTheLastAndAgainAfterAnotherKeyIsTheKey() {
        val session = session()
        session.type("6")
        // 4 pressed again after 6, which the keyboard does not send: the key 4, any of g h i
        val again = session.apply(Action.Letter('h', again = true))
        assertEquals(session().type("64").preedit, again.preedit)
        session.apply(Backspace)
        // a letter chosen from the long press, after the last key though of the same
        val h = session.apply(Action.Letter('h'))
        assertTrue(h.preedit.endsWith("h"))
        assertEquals(h.preedit.lowercase(), h.preedit)
        session.apply(Action.Letter('i'))
        assertEquals(h.preedit, session.apply(Backspace).preedit)
    }

    @Test
    fun aKeyPressedAgainWithNoKeyBeforeItIsTheKeyAnyOfItsLetters() {
        // a candidate picked between the presses, on the bar: nothing typed is left
        val again = session().apply(Action.Letter('h', again = true))
        val four = session().type("4")
        assertEquals(four.preedit, again.preedit)
        assertEquals(four.syllables, again.syllables)
    }

    @Test
    fun aLetterTypedAsItselfShowsAsTheLetterInTheCandidatesToo() {
        val session = session()
        session.type("8")
        val u = session.apply(Action.Letter('u', again = true))
        assertTrue(u.candidates.none { it.any(Char::isUpperCase) })
        assertTrue(u.preedit.none(Char::isUpperCase))
    }

    @Test
    fun noPhraseIsPinnedToAKeyWithALetterTypedAsItself() {
        val book = PhraseBook(CustomPhrases.parse("")) {}
        // m i, each typed as itself: letters, as a phrase key is, but no other input method's
        val session = PinyinSession(data, T9Segmenter(abbreviations = false), spell = true, prediction = false, phraseBook = book)
        session.apply(Action.Letter('m'))
        val mi = session.apply(Action.Letter('i'))
        assertEquals("米", mi.candidates[0])
        assertTrue(Offer.PIN !in session.offers(0))
        // full pinyin's mi may be
        val pinyin = PinyinSession(data, PinyinSegmenter(), prediction = false, phraseBook = book)
        pinyin.apply(Key('m'))
        pinyin.apply(Key('i'))
        assertTrue(Offer.PIN in pinyin.offers(0))
    }

    @Test
    fun aSyllableTakenOverALetterTypedAsItselfGivesItBack() {
        val session = session()
        session.press('6', 4)
        session.type("4")
        val taken = session.apply(Action.Syllable(0))
        assertEquals("mi", taken.preedit)
        assertTrue(taken.syllables.isEmpty())
        // given back as typed: still m, not any of 6
        val back = session.apply(Backspace)
        assertEquals("mi", back.syllables.first())
        assertTrue("ni" !in back.syllables)
    }

    @Test
    fun theKeyboardSendsALetterTypedAsItselfPastThePlane() {
        val keyboard = Keyboard(session(), nineKeys = true)
        keyboard.onEvent(EngineEvent.CHAR, '6'.code)
        val n = keyboard.onEvent(EngineEvent.CHAR, Keyboard.letterKey('n', again = true))
        assertTrue(n.handled)
        // 6 pressed again: the run read as n too
        assertTrue("n" in n.syllables)
        assertTrue(Keyboard.letterKey('z', again = true) in Keyboard.LETTERS && Keyboard.letterKey('a', again = false) == Keyboard.LETTERS.first)
        // a keyboard of letters has no use for them
        val letters = Keyboard(session(), nineKeys = false)
        assertFalse(letters.onEvent(EngineEvent.CHAR, Keyboard.letterKey('m', again = false)).handled)
    }

    @Test
    fun theKeyboardSendsASyllableAsACharacterOfThePrivateUseArea() {
        val keyboard = Keyboard(session(), nineKeys = true)
        val typed = "64426".map { keyboard.onEvent(EngineEvent.CHAR, it.code) }.last()
        val s = keyboard.onEvent(EngineEvent.CHAR, Keyboard.syllableKey(1, typed.syllablesId).code)
        assertEquals("米糕", s.candidates[0])
        // 0 is no key on the nine keys: it ends the input as typed
        val zero = keyboard.onEvent(EngineEvent.CHAR, '0'.code)
        assertEquals("米糕", zero.commit)
    }

    @Test
    fun aLetterTakenIsAnInitial() {
        val session = session()
        val s = session.type("64426")
        // m for the 6 alone: 米 its likeliest character, the rest read on
        val m = session.apply(Action.Syllable(s.syllables.indexOf("m")))
        assertTrue(m.preedit.startsWith("m'"))
        assertTrue(m.candidates[0].startsWith("米"))
    }

    @Test
    fun oneSeparatesWhileTypingAndIsTheAppsOtherwise() {
        val keyboard = Keyboard(session(), nineKeys = true)
        assertEquals(false, keyboard.onEvent(EngineEvent.CHAR, '1'.code).handled)
        "64".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        val separated = keyboard.onEvent(EngineEvent.CHAR, '1'.code)
        assertEquals(true, separated.handled)
        "42".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        assertEquals("ni'hao", keyboard.onEvent(EngineEvent.CHAR, '6'.code).preedit)
    }

    @Test
    fun enterCommitsThePinyinShownNotTheDigits() {
        val session = session()
        session.type("64426")
        assertEquals("nihao", session.apply(Action.CommitRaw).commit)
        session.type("64426")
        session.apply(Action.Syllable(1))
        assertEquals("migao", session.apply(Action.CommitRaw).commit)
        // what no reading took is kept: digits, as typed
        session.type("64")
        session.apply(Action.Syllable(0))
        val shown = session.type("9999").preedit
        val commit = session.apply(Action.CommitRaw).commit
        assertEquals(shown.filter { it != ' ' && it != '\'' }, commit)
        assertEquals("ni9999", commit)
    }

    @Test
    fun aFuzzyReadingShowsAndCommitsItsWholeSyllable() {
        val zhong = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("中", -3.5f, 0f)
                    .entry("中", syl("zhong"))
                    .build().toByteArray(),
            ),
        )
        val session = PinyinSession(zhong, T9Segmenter(setOf(Fuzzy.Z_ZH), abbreviations = false), spell = true, prediction = false)
        // 9664 types zong, and z stands for zh: zhong, not its first four letters
        val s = session.type("9664")
        assertEquals("中", s.candidates[0])
        assertEquals("zhong", s.preedit)
        assertEquals("zhong", session.apply(Action.CommitRaw).commit)
    }

    @Test
    fun startingOverWithNothingTypedIsNotTheApps() {
        val keyboard = Keyboard(session(), nineKeys = true)
        assertEquals(true, keyboard.onEvent(EngineEvent.ESCAPE, 0).handled)
        // nor is a syllable for a session without the nine keys
        val full = Keyboard(PinyinSession(data, PinyinSegmenter(), prediction = false), nineKeys = false)
        assertEquals(false, full.onEvent(EngineEvent.CHAR, Keyboard.syllableKey(0, 0).code).handled)
        assertEquals(false, full.onEvent(EngineEvent.ESCAPE, 0).handled)
    }

    @Test
    fun aSyllableTakenAfterASeparatorTypedKeepsItAndDigitsTypedAfterAreDeletedFirst() {
        val session = session()
        session.type("641")
        session.apply(Action.Syllable(0))
        session.type("426")
        repeat(2) { session.apply(Backspace) }
        assertEquals("ni", session.apply(Backspace).preedit)
        // all typed after it gone: backspace gives the syllable back, the separator typed stays
        val back = session.apply(Backspace)
        assertEquals(listOf("ni", "mi", "o", "m", "n"), back.syllables)
        assertEquals("ni", back.preedit)
    }

    @Test
    fun aPickFromAListAKeyReplacedIsDropped() {
        val session = session()
        val shown = session.type("6")
        assertEquals("o", shown.syllables[0])
        // 4 typed before the pick reached the engine: its list is another
        val typed = session.type("4")
        assertTrue(typed.syllablesId != shown.syllablesId)
        val stale = session.apply(Action.Syllable(0, shown.syllablesId))
        assertEquals("ni", stale.preedit)
        assertEquals(typed.syllables, stale.syllables)
        // picked from the list shown now, taken
        assertEquals("ni", session.apply(Action.Syllable(0, typed.syllablesId)).preedit)
        assertEquals(emptyList<String>(), session.apply(Action.Syllable(0, typed.syllablesId)).syllables)
        // the same list again keeps its id: a word forgotten reads the input again, offering what
        // it did, and stales no pick (a pause reads nothing again: see the test after this one)
        val again = session(UserModel(data.dictionary, data.vocabulary)) { _, _, _, _ -> 0 }
        val first = again.type("64")
        val forgotten = again.apply(Action.Forget(0))
        // the input was read again: the refiner has its readings anew
        assertTrue(forgotten.refines)
        assertEquals(first.syllables, forgotten.syllables)
        assertEquals(first.syllablesId, forgotten.syllablesId)
    }

    @Test
    fun aPauseThatPutsAnotherReadingFirstKeepsTheSyllablesOffered() {
        val session = session { _, readings, _, _ -> readings.indexOfFirst { it.startsWith("米") } }
        val typed = session.type("64426")
        assertEquals("ni", typed.syllables[0])
        assertTrue(typed.refines)
        val refined = session.apply(Action.Refine)
        assertTrue(refined.candidates[0].startsWith("米"))
        // as shown when the user paused: a tap on it, on its way, is not dropped
        assertEquals(typed.syllables, refined.syllables)
        assertEquals(typed.syllablesId, refined.syllablesId)
        assertEquals("ni'hao", session.apply(Action.Syllable(0, typed.syllablesId)).preedit)
    }

    @Test
    fun theKeysTypeTheLettersTheUserPutOnThem() {
        // h moved to 6 beside m n o, i on 5 too
        val keys = NineKeyLetters.of(listOf("abc", "def", "gi", "jkli", "mnoh", "pqrs", "tuv", "wxyz"))
        fun session() = PinyinSession(data, T9Segmenter(abbreviations = false, letters = keys), spell = true, prediction = false)
        val s = session().type("65626")
        assertEquals("你好", s.candidates[0])
        // 6's letters offered as initials, h with them
        assertTrue(s.syllables.containsAll(listOf("m", "n", "h")))
        // pressed again soon, a letter of the same key goes in place of the last
        val h = session().press('6', 4, keys)
        assertEquals("h", h.preedit)
    }

    @Test
    fun aLetterOnTwoKeysPressedAgainWithNoKeyBeforeGoesInAsItself() {
        // a on 2 and on 5: which was pressed is not told, so not 2's letters for it
        val keys = NineKeyLetters.of(listOf("abc", "def", "ghi", "jkla"))
        val session = PinyinSession(data, T9Segmenter(abbreviations = false, letters = keys), spell = true, prediction = false)
        assertEquals("a", session.apply(Action.Letter('a', again = true)).preedit)
        // on one key: that key, any of its letters
        val one = PinyinSession(data, T9Segmenter(abbreviations = false, letters = keys), spell = true, prediction = false)
        one.apply(Action.Letter('h', again = true))
        assertTrue(one.apply(Key('4')).candidates.isNotEmpty())
    }

    /** A key pressed again that no reading takes was the private character it stands as in the input. */
    @Test
    fun aKeyPressedAgainKeptAsTypedIsItsDigit() {
        val session = session()
        // no word of w, x, y or z here
        val s = session.press('9', 8)
        assertEquals("99999999", s.candidates[0])
        val commit = session.apply(Action.CommitRaw).commit
        assertEquals("99999999", commit)
        assertTrue(commit.none { it in T9Segmenter.AGAIN })
    }

    /** 屄 held past the first page still gave the preedit, the column and Enter its bi, 爱 first. */
    @Test
    fun theFirstReadingShownNotACoarseOneHeldPastThePageIsWhatThePreeditShows() {
        val session = sessionOf(dataOf(Triple("屄", -1f, "bi"), Triple("爱", -6f, "ai")))
        val s = session.type("24")
        assertEquals("爱", s.candidates[0])
        assertEquals("ai", s.preedit)
        assertEquals("ai", s.syllables[0])
        assertEquals("ai", session.apply(Action.CommitRaw).commit)
    }

    /** Every reading of 24 held, the preedit, the column and Enter still went by 屄 while 啊 came first. */
    @Test
    fun everyReadingHeldThePreeditGoesByTheCandidateShownFirst() {
        // five of the list, read bi here, each likelier than 啊 then 4 as typed: no reading of all of it is left
        val held = listOf("屄", "骚屄", "嫩屄", "肉棒", "鸡巴").mapIndexed { i, w -> Triple(w, -0.1f * (i + 1), "bi") }
        val session = sessionOf(dataOf(*held.toTypedArray(), Triple("啊", -2f, "a")))
        val s = session.type("24")
        assertEquals("啊", s.candidates[0])
        assertEquals("a 4", s.preedit)
        assertEquals("a", s.syllables[0])
        assertEquals("a4", session.apply(Action.CommitRaw).commit)
    }

    /** 3 3 fast read as 鹅点, e of one press: taking e took both, and 鹅好 was read of the rest. */
    @Test
    fun theBestReadingsSyllableTakenStandsForThePressesItRead() {
        val session = sessionOf(dataOf(Triple("鹅", -2f, "e"), Triple("点", -2f, "dian"), Triple("的", -5f, "de"), Triple("好", -5f, "hao")))
        session.press('3', 2)
        val s = session.type("426")
        assertEquals("鹅点", s.candidates[0])
        assertEquals("e", s.syllables[0])
        val taken = session.apply(Action.Syllable(0))
        assertEquals("e'dian", taken.preedit)
        assertEquals("鹅点", taken.candidates[0])
    }

    /** 2, a pick the keyboard did not see, then 2 2 fast: the keyboard showed c, the run read b. */
    @Test
    fun aPressAgainNotCountedFromTheKeyBeforeIsAKeyOfItsOwn() {
        val data = dataOf(Triple("不", -2f, "bu"), Triple("啊", -4f, "a"))
        val session = sessionOf(data)
        // nothing typed: the keyboard counted the 2 before the pick
        session.apply(Action.Letter('b', again = true))
        session.apply(Action.Letter('c', again = true))
        val s = session.type("8")
        // 2 2 8 as keys, no B to read 不 of
        val keys = sessionOf(data).type("228")
        assertEquals(keys.candidates, s.candidates)
        assertEquals(keys.preedit, s.preedit)
    }
}
