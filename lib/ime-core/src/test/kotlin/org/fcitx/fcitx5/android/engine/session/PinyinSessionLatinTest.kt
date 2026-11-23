/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.syl
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.UserLatinWords
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PinyinSessionLatinTest {

    private fun letters(word: String) = word.uppercase().map { it.toString() }.toTypedArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("我", -2f, 0f)
                .unigram("的", -1.5f, 0f)
                .unigram("爱", -3f, 0f)
                .unigram("iPhone", -5f, 0f)
                .unigram("app", -4.5f, 0f)
                .unigram("APP", -4.7f, 0f)
                .unigram("AI", -5.5f, 0f)
                .unigram("啧啧啧", -5f, 0f)
                .unigram("ZZZ", -6f, 0f)
                .unigram("你好", -3f, 0f)
                .bigram("我", "的", -0.5f, 0f)
                .entry("我", syl("wo"))
                .entry("的", syl("de"))
                .entry("爱", syl("ai"))
                .entry("iPhone", syl(*letters("iphone")))
                .entry("app", syl(*letters("app")))
                .entry("APP", syl(*letters("app")))
                .entry("AI", syl(*letters("ai")))
                .entry("啧啧啧", syl("ze", "ze", "ze"))
                .entry("ZZZ", syl(*letters("zzz")))
                .entry("你好", syl("ni", "hao"))
                .build().toByteArray(),
        ),
    )

    private val latin = LatinWords.of(data.dictionary)

    private fun session(latin: LatinWords? = this.latin) = PinyinSession(data, PinyinSegmenter(latin = latin))

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun theWordsSpeltInLettersAreFoundWhereTheyAreTyped() {
        assertEquals(4, latin.size)
        fun at(input: String, at: Int) = ArrayList<Int>().also { found -> latin.forEachAt(input, at) { found += it } }
        assertEquals(listOf(6), at("wodeiphone", 4))
        assertEquals(emptyList<Int>(), at("wodeiphone", 0))
        // what auto-capitalisation touched is pinyin's to read as letters
        assertEquals(emptyList<Int>(), at("Iphone", 0))
    }

    @Test
    fun aLatinWordTypedInLowerCaseIsOfferedAsWritten() {
        val s = session().type("wodeiphone")
        assertEquals("我的iPhone", s.candidates.first())
        assertEquals("wo de iphone", s.preedit)
        assertEquals("我的iPhone", session().also { it.type("wodeiphone") }.apply(Select(0)).commit)
        assertEquals("iPhone", session().type("iphone").candidates.first())
        assertNotEquals("iPhone", session(latin = null).type("iphone").candidates.first())
    }

    // a word's letters cost nothing, 简拼 an initial each: a word the user corrected to twice is first there
    @Test
    fun aWordPickedOverALatinWordTwiceIsFirstThereafter() {
        val learner = Learner(data)
        val session = PinyinSession(data, PinyinSegmenter(latin = latin), user = learner.model, learnTo = learner.learnTo())
        fun pick(): List<String> {
            val zzz = session.type("zzz").candidates
            session.apply(Select(zzz.indexOf("啧啧啧")))
            session.apply(Action.Reset)
            return zzz
        }
        assertEquals("ZZZ", pick().first())
        assertEquals("ZZZ", pick().first())
        assertEquals("啧啧啧", session.type("zzz").candidates.first())
    }

    @Test
    fun eachWritingIsACandidateAndPinyinComesFirstWhereItIsCommoner() {
        assertEquals(listOf("app", "APP"), session().type("app").candidates.take(2))
        val ai = session().type("ai").candidates
        assertEquals("爱", ai.first())
        assertTrue("AI" in ai)
    }

    private val keyboards = listOf("nine keys" to NineKeyLetters.STANDARD, "14 keys" to NineKeyLetters.G14, "18 keys" to NineKeyLetters.G18)

    private fun keyed(letters: NineKeyLetters, latin: LatinWords? = this.latin, user: UserLatinWords? = null, model: UserModel? = null) =
        PinyinSession(data, T9Segmenter(abbreviations = false, letters = letters, latin = latin, userLatin = user), spell = true, user = model, prediction = false)

    private fun Session.typeOn(letters: NineKeyLetters, word: String): Snapshot = type(word.map(letters::key).joinToString(""))

    @Test
    fun aLatinWordIsTypedOnEveryPinyinKeyboardAsOnTheFullOne() = assertRows(
        keyboards.flatMap { (name, keys) ->
            listOf<Pair<String, () -> Unit>>(
                Pair("$name: a word alone, each key's letters read", { assertEquals("iPhone", keyed(keys).typeOn(keys, "iphone").candidates.first()) }),
                Pair("$name: a word in a sentence", { assertEquals("我的iPhone", keyed(keys).typeOn(keys, "wodeiphone").candidates.first()) }),
                Pair("$name: the preedit spells it in lower case, as typed on the full keyboard", { assertEquals("wo de iphone", keyed(keys).typeOn(keys, "wodeiphone").preedit) }),
                Pair("$name: Enter commits its letters as the preedit shows them", {
                    assertEquals("iphone", keyed(keys).also { it.typeOn(keys, "iphone") }.apply(Action.CommitRaw).commit)
                }),
                Pair("$name: picked, it is committed as written", { assertEquals("我的iPhone", keyed(keys).also { it.typeOn(keys, "wodeiphone") }.apply(Select(0)).commit) }),
                Pair("$name: 中英混输 off reads none", { assertFalse("iPhone" in keyed(keys, latin = null).typeOn(keys, "iphone").candidates) }),
                Pair("$name: a sentence with no Latin word reads as before", {
                    assertEquals(keyed(keys, latin = null).typeOn(keys, "nihao").candidates, keyed(keys).typeOn(keys, "nihao").candidates)
                }),
                Pair("$name: a word English learned, spelt from the user's words", {
                    // as LatinSync lists it: a user word in letters, and its spelling
                    val model = UserModel(data.dictionary, data.vocabulary).apply { own(UserModel.Entry("youmo", syl(*letters("youmo")))) }
                    val user = UserLatinWords().apply { set(setOf("youmo")) }
                    assertTrue("youmo" in keyed(keys, user = user, model = model).typeOn(keys, "youmo").candidates)
                    assertFalse("youmo" in keyed(keys, model = model).typeOn(keys, "youmo").candidates)
                }),
            )
        },
        { it.first },
    ) { it.second() }

    // as on the full keyboard (aWordPickedOverALatinWordTwiceIsFirstThereafter), learned under the keys typed
    @Test
    fun aWordPickedOverALatinWordOnKeysTwiceIsFirstThereafter() = assertRows(keyboards, { it.first }) { (_, keys) ->
        val learner = Learner(data)
        val session = PinyinSession(data, T9Segmenter(abbreviations = false, letters = keys, latin = latin), spell = true, user = learner.model, learnTo = learner.learnTo(), prediction = false)
        fun pick(): List<String> {
            val zzz = session.typeOn(keys, "zzz").candidates
            session.apply(Select(zzz.indexOf("啧啧啧")))
            session.apply(Action.Reset)
            return zzz
        }
        assertEquals("ZZZ", pick().first())
        assertEquals("ZZZ", pick().first())
        assertEquals("啧啧啧", session.typeOn(keys, "zzz").candidates.first())
    }

    // the nine keys' 连按 or a glide puts a letter itself (upper case in the input): a word must have that letter there
    @Test
    @Uat("T9-77")
    fun aLetterTypedAsItselfOnTheNineKeysKeepsOnlyWordsWithIt() = assertRows(
        listOf(
            Triple('i', true, "i is iPhone's first letter"),
            Triple('h', false, "h is on iPhone's first key, but not its letter"),
        ),
        { it.third },
    ) { (letter, found) ->
        val session = keyed(NineKeyLetters.STANDARD)
        session.apply(Action.Letter(letter))
        assertEquals(found, "iPhone" in session.type("74663").candidates)
    }
}
