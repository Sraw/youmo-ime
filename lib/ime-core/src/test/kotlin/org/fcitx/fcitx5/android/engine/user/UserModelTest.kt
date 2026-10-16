/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserFixtures.data
import org.fcitx.fcitx5.android.engine.user.UserFixtures.entry
import org.fcitx.fcitx5.android.engine.user.UserFixtures.inTrie
import org.fcitx.fcitx5.android.engine.user.UserFixtures.model
import org.fcitx.fcitx5.android.engine.user.UserFixtures.syl
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class UserModelTest {

    private fun word(text: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == text }

    @Test
    fun aWordTheDictionaryHasIsItsOwn() {
        val m = model()
        assertEquals(word("你好"), m.id(entry("你好", "ni", "hao")))
        assertEquals(0, m.size)
    }

    @Test
    fun aWordItLacksBecomesTheUsers() {
        val m = model()
        // 拟好 is not a word; nor is 好 read as ni
        val nihao = m.id(entry("拟好", "ni", "hao"))
        val haoAsNi = m.id(entry("好", "ni"))
        assertEquals(data.vocabulary.size, nihao)
        assertEquals(data.vocabulary.size + 1, haoAsNi)
        assertEquals(nihao, m.id(entry("拟好", "ni", "hao")))
        assertEquals(2, m.size)
        assertEquals("拟好", m.text(nihao))
        // in the trie under their syllables
        val ni = m.child(m.root, Syllables.id("ni"))
        val hao = m.child(ni, Syllables.id("hao"))
        assertEquals(listOf(haoAsNi), (0 until m.wordCount(ni)).map { m.word(ni, it) })
        assertEquals(listOf(nihao), (0 until m.wordCount(hao)).map { m.word(hao, it) })
        assertEquals(1, m.childCount(ni))
        assertEquals(0, m.childCount(hao))
        assertEquals(-1, m.child(m.root, Syllables.id("ma")))
        // children kept sorted, whichever came first
        m.id(entry("吗", "a"))
        assertEquals(ni, m.child(m.root, Syllables.id("ni")))
        assertTrue(m.child(m.root, Syllables.id("a")) >= 0)
    }

    @Test
    fun entriesAreChecked() {
        assertThrows(IllegalArgumentException::class.java) { Entry("", syl("ni")) }
        assertThrows(IllegalArgumentException::class.java) { Entry("你", IntArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { Entry("你", intArrayOf(-1)) }
        assertThrows(IllegalArgumentException::class.java) { model().learn(null, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { model(0f) }
        assertEquals(entry("你", "ni"), entry("你", "ni"))
        assertEquals(entry("你", "ni").hashCode(), entry("你", "ni").hashCode())
        assertNotEquals(entry("你", "ni"), entry("你", "nie"))
        assertNotEquals(entry("你", "ni"), "你")
        assertEquals("你(ni)", entry("你", "ni").toString())
    }

    @Test
    fun learningCountsWordsAndPairs() {
        val m = model()
        val ni = word("你")
        val hao = word("好")
        assertEquals(0f, m.probability(NO_WORD, ni))
        val ids = m.learn(null, listOf(entry("你", "ni"), entry("好", "hao")))
        assertEquals(listOf(ni, hao), ids.toList())
        assertEquals(2f, m.total)
        // one of two counted, beside what the model keeps for words not yet typed
        val alone = 1f / (2f + UserModel.UNSEEN)
        assertEquals(alone, m.probability(NO_WORD, ni))
        // after 你, 好 came every time; after 好, 你 never did
        assertEquals((1f + UserModel.PRIOR * alone) / (1f + UserModel.PRIOR), m.probability(ni, hao))
        assertEquals((0f + UserModel.PRIOR * alone) / (1f + UserModel.PRIOR), m.probability(hao, ni))
        // a prev never typed says nothing
        assertEquals(alone, m.probability(word("吗"), hao))
        // the word before a sentence pairs with its first
        m.learn(entry("好", "hao"), listOf(entry("吗", "ma")))
        assertTrue(m.probability(hao, word("吗")) > m.probability(ni, word("吗")))
    }

    @Test
    fun countsHalvePastTheLimit() {
        val m = model(limit = 2.5f)
        fun counted(): Map<String, Float> {
            val out = HashMap<String, Float>()
            m.forEachCount({ e, c -> out[e.text] = c }, { a, b, c -> out[a.text + b.text] = c })
            return out
        }
        m.learn(null, listOf(entry("你", "ni"), entry("好", "hao")))
        m.learn(null, listOf(entry("你", "ni")))
        // 3 > 2.5: 你 2, 好 1 and the pair 1 halve
        assertEquals(mapOf("你" to 1f, "好" to 0.5f, "你好" to 0.5f), counted())
        assertEquals(1.5f, m.total)
        m.learn(null, listOf(entry("吗", "ma")))
        m.learn(null, listOf(entry("吗", "ma")))
        assertEquals(mapOf("你" to 0.5f, "好" to 0.25f, "你好" to 0.25f, "吗" to 1f), counted())
        m.learn(null, listOf(entry("吗", "ma")))
        // 好 and the pair fall below a quarter and go
        assertEquals(mapOf("你" to 0.25f, "吗" to 1f), counted())
        assertEquals(1.25f, m.total)
    }

    @Test
    fun theUsersOwnWordKeepsAQuarterAsCountsHalveAndIsWrittenOutSo() {
        val m = model(limit = 2.5f)
        val niHao = entry("拟好", "ni", "hao")
        m.learn(null, listOf(niHao))
        m.learn(null, listOf(entry("好", "hao")))
        // halved three times: 拟好 and 好, typed once, would fall below a quarter
        repeat(4) { m.learn(null, listOf(entry("你", "ni"))) }
        val counted = HashMap<String, Float>()
        m.forEachCount({ e, c -> counted[e.text] = c }, { a, b, c -> counted[a.text + b.text] = c })
        // the dictionary's 好 goes; the user's word keeps a quarter
        assertEquals(mapOf("拟好" to 0.25f, "你" to 1.125f), counted)
        assertEquals(1.375f, m.total)
        // what a compaction writes, a start reads back: the word still there to type
        val restored = model()
        m.forEachCount({ e, c -> restored.addCount(e, c) }, { a, b, c -> restored.addCount(a, b, c) })
        assertTrue(inTrie(restored, "拟好", "ni", "hao"))
        assertEquals(listOf(niHao), restored.ownWords().map { it.first })
    }

    @Test
    fun countsWrittenOutRestoreTheSame() {
        val m = model()
        m.learn(null, listOf(entry("你", "ni"), entry("拟好", "ni", "hao"), entry("吗", "ma")))
        m.learn(entry("吗", "ma"), listOf(entry("你", "ni")))
        val copy = model()
        m.forEachCount({ e, c -> copy.addCount(e, c) }, { a, b, c -> copy.addCount(a, b, c) })
        assertEquals(m.total, copy.total)
        val nihao = copy.id(entry("拟好", "ni", "hao"))
        for (prev in listOf(NO_WORD, word("你"), word("吗"), nihao)) {
            for (w in listOf(word("你"), word("吗"), nihao)) assertEquals(m.probability(prev, w), copy.probability(prev, w))
        }
    }

    @Test
    fun everyWordIsWrittenOutBeforeAnyPair() {
        val m = model()
        m.learn(null, listOf(entry("你", "ni"), entry("拟好", "ni", "hao"), entry("吗", "ma")))
        m.learn(entry("吗", "ma"), listOf(entry("你", "ni")))
        val order = ArrayList<String>()
        m.forEachCount({ e, _ -> order += e.text }, { a, b, _ -> order += a.text + b.text })
        assertEquals(setOf("你", "拟好", "吗"), order.take(3).toSet())
        assertEquals(setOf("你拟好", "拟好吗", "吗你"), order.drop(3).toSet())
    }

    @Test
    fun theJournalHearsEachSentence() {
        val m = model()
        val heard = ArrayList<String>()
        m.journal = UserModel.Journal { prev, sentence -> heard += "$prev ${sentence.joinToString()}" }
        m.learn(null, listOf(entry("你", "ni")))
        m.learn(entry("你", "ni"), listOf(entry("好", "hao")))
        m.addCount(entry("吗", "ma"), 1f)
        assertEquals(listOf("null 你(ni)", "你(ni) 好(hao)"), heard)
    }

    @Test
    fun theJournalHearsOfASentenceOnceItsCountsHalved() {
        val m = model(limit = 2.5f)
        val totals = ArrayList<Float>()
        m.journal = UserModel.Journal { _, _ -> totals += m.total }
        m.learn(null, listOf(entry("你", "ni"), entry("好", "hao")))
        m.learn(null, listOf(entry("你", "ni")))
        // what a compaction then writes out is what a restart restores
        assertEquals(listOf(2f, 1.5f), totals)
    }

    @Test
    fun countsGrowAndScale() {
        val c = Counts()
        for (k in 0L until 1000L) c.add(k * 7919, 1f)
        c.add(7919, 2f)
        assertEquals(1000, c.size)
        assertEquals(3f, c[7919])
        assertEquals(0f, c[-5])
        c.scale(0.5f, 1f)
        assertEquals(1, c.size)
        assertEquals(1.5f, c[7919])
        assertThrows(IllegalArgumentException::class.java) { c.add(Long.MIN_VALUE, 1f) }
    }

    @Test
    fun countsAreSetAndTellAKeyWithNone() {
        val c = Counts()
        c[5] = -2f
        c[5] = -1f
        assertEquals(-1f, c.getOrDefault(5, Float.NaN))
        assertTrue(c.getOrDefault(6, Float.NaN).isNaN())
        assertEquals(0f, c[6])
        // set as the table grows: none lost to a resize
        for (k in 1L..1000L) c[k * 7919] = k.toFloat()
        assertTrue((1L..1000L).all { c[it * 7919] == it.toFloat() })
        assertEquals(1001, c.size)
    }

    @Test
    fun aWordForgottenLosesItsCountsAndPairs() {
        val m = model()
        val heard = ArrayList<String>()
        m.learn(null, listOf(entry("你", "ni"), entry("好", "hao"), entry("吗", "ma")))
        m.learn(null, listOf(entry("你", "ni")))
        m.journal = object : UserModel.Journal {
            override fun record(prev: Entry?, sentence: List<Entry>) = Unit
            override fun forgot(words: List<Entry>) {
                heard += words.joinToString()
            }
        }
        m.forget(listOf(entry("好", "hao")))
        assertEquals(listOf("好(hao)"), heard)
        assertEquals(0f, m.probability(NO_WORD, word("好")))
        assertEquals(3f, m.total)
        // the pairs it was in are gone, those without it stay
        val counted = HashMap<String, Float>()
        m.forEachCount({ e, c -> counted[e.toString()] = c }, { a, b, c -> counted["$a $b"] = c })
        assertEquals(mapOf("你(ni)" to 2f, "吗(ma)" to 1f), counted)
        // nothing learned of it: nothing to forget, nothing heard
        m.forget(listOf(entry("拟", "ni")))
        assertEquals(1, heard.size)
    }

    @Test
    fun theUsersWordForgottenAloneIsGoneUntilLearnedAgain() {
        val m = model()
        m.learn(null, listOf(entry("拟好", "ni", "hao"), entry("好", "ni")))
        m.forget(listOf(entry("拟好", "ni", "hao"), entry("吗", "ma")))
        // in a longer candidate: typeable still, its count gone
        assertTrue(inTrie(m, "拟好", "ni", "hao"))
        assertEquals(0f, m.probability(NO_WORD, m.id(entry("拟好", "ni", "hao"))))
        m.forget(listOf(entry("拟好", "ni", "hao")))
        assertFalse(inTrie(m, "拟好", "ni", "hao"))
        assertTrue(inTrie(m, "好", "ni"))
        // learned again, a new word in the trie again
        val again = m.learn(null, listOf(entry("拟好", "ni", "hao"))).single()
        assertEquals(data.vocabulary.size + 2, again)
        assertTrue(inTrie(m, "拟好", "ni", "hao"))
        assertEquals(1f / (2f + UserModel.UNSEEN), m.probability(NO_WORD, again))
    }

    @Test
    fun aWordForgottenIsNotMadeAgainAsTheWordBefore() {
        val m = model()
        val niZai = entry("拟再", "ni", "zai")
        m.learn(null, listOf(niZai))
        m.forget(listOf(niZai))
        m.learn(niZai, listOf(entry("你", "ni")))
        assertFalse(inTrie(m, "拟再", "ni", "zai"))
        val counted = HashMap<String, Float>()
        m.forEachCount({ e, c -> counted[e.toString()] = c }, { a, b, c -> counted["$a $b"] = c })
        assertEquals(mapOf("你(ni)" to 1f), counted)
        // one the dictionary has is the word before as ever
        m.learn(entry("你", "ni"), listOf(entry("好", "hao")))
        counted.clear()
        m.forEachCount({ _, _ -> }, { a, b, c -> counted["$a $b"] = c })
        assertEquals(mapOf("你(ni) 好(hao)" to 1f), counted)
    }

    @Test
    fun aPacksWordKeepsWhatItWasListedWithLastAndIsListed() {
        val m = model()
        val id = m.list(entry("拟好", "ni", "hao"), -5f, 1)
        assertEquals(id, m.list(entry("拟好", "ni", "hao"), -6f, 2))
        assertEquals(-6f, m.score(id), 0f)
        assertEquals(2, m.layerOf(id))
        // layer 0 is a layer, not the -1 of a word no pack listed
        assertEquals(0, m.layerOf(m.list(entry("拟吗", "ni", "ma"), -5f, 0)))
        // a word of the model's: the pack's score and layer are not taken
        val ni = m.id(entry("你", "ni"))
        assertEquals(ni, m.list(entry("你", "ni"), -1f, 1, modelWords = ni + 1))
        assertTrue(m.score(ni).isNaN())
        assertEquals(-1, m.layerOf(ni))
        assertTrue(inTrie(m, "拟好", "ni", "hao"))
        // listed: forgetting it drops nothing but its counts
        m.forget(listOf(entry("拟好", "ni", "hao")))
        assertTrue(inTrie(m, "拟好", "ni", "hao"))
        assertEquals(-6f, m.score(id), 0f)
        assertThrows(IllegalArgumentException::class.java) { m.list(entry("拟", "ni"), 0.5f, 1) }
    }

    @Test
    fun aListedWordForgottenStaysToType() {
        val m = model()
        val niZai = entry("拟再", "ni", "zai")
        val id = m.list(niZai)
        assertEquals(id, m.list(niZai))
        m.learn(null, listOf(niZai))
        m.forget(listOf(niZai))
        assertTrue(inTrie(m, "拟再", "ni", "zai"))
        assertEquals(0f, m.probability(NO_WORD, id))
        // the dictionary's own words are never listed: nothing to keep
        assertEquals(word("你"), m.list(entry("你", "ni")))
    }

    @Test
    fun aWordIsBlockedInTheReadingItWasBlockedIn() {
        val m = model()
        val nihao = data.dictionary.find(syl("ni", "hao"))
        assertTrue(m.block(entry("你好", "ni", "hao")))
        assertTrue(m.blocked(word("你好"), nihao))
        assertTrue(m.blockedAnyhow(word("你好")))
        // the same word at another node, another reading of it
        assertFalse(m.blocked(word("你好"), data.dictionary.find(syl("ni"))))
        m.unblock(entry("你好", "ni", "hao"))
        assertFalse(m.blocked(word("你好"), nihao))
        assertFalse(m.blockedAnyhow(word("你好")))
    }

    @Test
    fun aWordBlockedBeforeItIsMadeIsBlockedAsItIsMadeAndAgainOnceForgotten() {
        val m = model()
        val word = entry("拟好", "ni", "hao")
        // no such word yet: nothing to block now
        assertFalse(m.block(word))
        val id = m.learn(null, listOf(word)).single()
        assertTrue(m.blocked(id, -1))
        assertEquals(emptyList<Pair<Entry, Float>>(), m.ownWords())
        m.forget(listOf(word))
        val again = m.learn(null, listOf(word)).single()
        assertTrue(m.blocked(again, -1))
        m.unblock(word)
        assertFalse(m.blocked(again, -1))
        assertEquals(listOf(word), m.ownWords().map { it.first })
    }

    @Test
    fun aPolyphoneIsBlockedInEachReadingApartAndOfferedOnceNoneIs() {
        val m = UserModel(polyphones.dictionary, polyphones.vocabulary)
        val hang = entry("行", "hang")
        val xing = entry("行", "xing")
        val id = m.learn(null, listOf(hang)).single()
        val atHang = polyphones.dictionary.find(syl("hang"))
        val atXing = polyphones.dictionary.find(syl("xing"))
        m.block(hang)
        // blocked again in the same reading: still one reading blocked
        m.block(hang)
        m.block(xing)
        assertTrue(m.blocked(id, atHang))
        assertTrue(m.blocked(id, atXing))
        m.unblock(xing)
        // still blocked as hang: not offered so, nor predicted, nor shared
        assertTrue(m.blocked(id, atHang))
        assertFalse(m.blocked(id, atXing))
        assertTrue(m.blockedAnyhow(id))
        assertEquals(emptyList<String>(), m.ownTexts())
        m.unblock(hang)
        assertFalse(m.blocked(id, atHang))
        assertFalse(m.blockedAnyhow(id))
        assertEquals(listOf("行"), m.ownTexts())
    }

    @Test
    fun aPolyphoneIsReadAsTypedNotAsListedOrBlocked() {
        val m = UserModel(polyphones.dictionary, polyphones.vocabulary)
        val hang = entry("行", "hang")
        val xing = entry("行", "xing")
        val id = m.learn(null, listOf(hang)).single()
        m.list(xing)
        m.block(xing)
        m.unblock(xing)
        assertEquals(hang.syllables.toList(), m.reading(id)?.toList())
        val counted = HashMap<String, Float>()
        m.forEachCount({ e, c -> counted[e.toString()] = c }, { _, _, _ -> })
        assertEquals(mapOf("行(hang)" to 1f), counted)
        // typed the other way, it is read so
        m.learn(null, listOf(xing))
        assertEquals(xing.syllables.toList(), m.reading(id)?.toList())
    }

    @Test
    fun aWordIsReadAsTheUserHasItOnlyWhileTheyHaveIt() {
        val m = UserModel(polyphones.dictionary, polyphones.vocabulary)
        val hang = entry("行", "hang")
        val id = m.learn(null, listOf(hang)).single()
        // forgotten, the dictionary's word keeps the entry it was learned as, but not as a reading
        m.forget(listOf(hang))
        assertEquals(null, m.reading(id))
        // one of their dictionaries', never typed, is not theirs yet (its reading may be wrong); one
        // added by hand is, and one imported once typed
        val listed = m.list(entry("行行", "hang", "hang"))
        assertEquals(null, m.reading(listed))
        val byHand = m.listByHand(entry("行行", "hang", "xing"))
        assertEquals(entry("行行", "hang", "xing").syllables.toList(), m.reading(byHand)?.toList())
        m.learn(null, listOf(entry("行行", "hang", "hang")))
        assertEquals(entry("行行", "hang", "hang").syllables.toList(), m.reading(listed)?.toList())
        m.forget(listOf(entry("行行", "hang", "hang")))
        // their own, forgotten in a longer candidate: still in the trie, no longer theirs
        val own = entry("行行", "xing", "xing")
        val made = m.learn(null, listOf(own)).single()
        m.forget(listOf(own, hang))
        assertEquals(null, m.reading(made))
    }

    @Test
    fun theTextsSharedAreTheUsersTheirDictionariesApartAndChangesSay() {
        val m = model()
        val before = m.changes
        m.list(entry("拟好", "ni", "hao"))
        assertEquals(listOf("拟好"), m.listedTexts())
        // made from pieces, and the dictionary's typed: their own
        m.learn(null, listOf(entry("好吗", "hao", "ma")))
        m.learn(null, listOf(entry("你好", "ni", "hao")))
        assertEquals(setOf("好吗", "你好"), m.ownTexts().toSet())
        val learned = m.changes
        assertTrue(learned > before)
        // typed again, nothing new to share
        m.learn(null, listOf(entry("你好", "ni", "hao")))
        assertEquals(learned, m.changes)
        m.block(entry("好吗", "hao", "ma"))
        assertEquals(setOf("你好"), m.ownTexts().toSet())
        assertTrue(m.changes > learned)
    }

    @Test
    fun aWordMadeIsFoundByItsTextAtTheReadingTypedMost() {
        val m = model()
        m.learn(null, listOf(entry("好吗", "hao", "a")))
        m.learn(null, listOf(entry("好吗", "hao", "ma")))
        m.learn(null, listOf(entry("好吗", "hao", "ma")))
        assertEquals("好吗(hao ma)", m.newEntryOf("好吗").toString())
        // none made
        assertEquals(null, m.newEntryOf("吗好"))
        m.forget(listOf(entry("好吗", "hao", "ma")))
        assertEquals("好吗(hao a)", m.newEntryOf("好吗").toString())
        m.forgetText("好吗")
        assertEquals(null, m.newEntryOf("好吗"))
        // learned again: a second id for the text, the one found
        m.learn(null, listOf(entry("好吗", "hao", "ma")))
        assertEquals("好吗(hao ma)", m.newEntryOf("好吗").toString())
    }

    @Test
    fun aTextIsForgottenHoweverItWasRead() {
        val m = model()
        m.learn(null, listOf(entry("好吗", "hao", "ma")))
        m.learn(null, listOf(entry("好吗", "hao", "a"), entry("吗", "ma")))
        assertEquals(2, m.entriesOf("好吗").size)
        m.forgetText("好吗")
        // each reading: its counts, its pairs, its place in the trie
        assertEquals(emptyList<Entry>(), m.entriesOf("好吗"))
        val counted = HashMap<String, Float>()
        m.forEachCount({ e, c -> counted[e.toString()] = c }, { a, b, c -> counted["$a $b"] = c })
        assertEquals(mapOf("吗(ma)" to 1f), counted)
        assertFalse("好吗" in m.ownTexts())
        assertFalse(inTrie(m, "好吗", "hao", "ma"))
        assertFalse(inTrie(m, "好吗", "hao", "a"))
    }

    @Test
    fun aTextIsHadInAReadingTypedMadeOrListedTillItIsForgotten() {
        val m = model()
        // the dictionary's, never typed: no id given it yet
        assertFalse(m.hasText("你好"))
        m.learn(null, listOf(entry("你好", "ni", "hao"), entry("好吗", "hao", "ma")))
        m.list(entry("拟好", "ni", "hao"))
        assertTrue(m.hasText("你好"))
        assertTrue(m.hasText("好吗"))
        assertTrue(m.hasText("拟好"))
        assertFalse(m.hasText("好"))
        m.forgetText("好吗")
        assertFalse(m.hasText("好吗"))
    }

    @Test
    fun aTextIsTypedInAnyReadingTillItsCountIsGone() {
        val m = model()
        val niHao = entry("拟好", "ni", "hao")
        // listed, a word the dictionary lacks: an id, never typed
        m.list(niHao)
        assertFalse(m.typed("拟好"))
        m.learn(null, listOf(niHao))
        // 好 read as ni, which the dictionary lacks: 好 typed all the same
        m.learn(null, listOf(entry("好", "ni")))
        assertTrue(m.typed("拟好"))
        assertTrue(m.typed("好"))
        assertFalse(m.typed("你"))
        // what a compaction writes, a start reads back
        val restored = model()
        m.forEachCount({ e, c -> restored.addCount(e, c) }, { a, b, c -> restored.addCount(a, b, c) })
        assertTrue(restored.typed("拟好"))
        m.forget(listOf(entry("好", "ni")))
        assertFalse(m.typed("好"))
        m.forget(listOf(niHao))
        assertFalse(m.typed("拟好"))
    }

    @Test
    fun aTextWhoseCountHalvedAwayIsNoLongerTyped() {
        val m = model(limit = 2.5f)
        val niHao = entry("拟好", "ni", "hao")
        m.list(niHao)
        m.learn(null, listOf(entry("你", "ni"), niHao))
        // 你 typed again: at a half as 拟好 goes at an eighth, not on the quarter below which a count goes
        repeat(2) { m.learn(null, listOf(entry("你", "ni"))) }
        repeat(3) { m.learn(null, listOf(entry("吗", "ma"))) }
        // listed, not made by the user: no quarter is kept for it, and below a quarter it goes
        assertFalse(m.typed("拟好"))
        assertTrue(m.typed("你"))
    }

    @Test
    fun wordsForgottenEachAloneAreAllGoneAndReadBackSo() {
        val m = model()
        val heard = ArrayList<String>()
        val log = ByteArrayOutputStream().apply { write(UserLog.header()) }
        m.journal = object : UserModel.Journal {
            override fun record(prev: Entry?, sentence: List<Entry>) = log.write(UserLog.sentence(prev, sentence))
            override fun forgot(words: List<Entry>) {
                heard += words.joinToString()
                log.write(UserLog.forgot(words))
            }
        }
        val niHao = entry("拟好", "ni", "hao")
        val haoMa = entry("好吗", "hao", "ma")
        m.learn(null, listOf(niHao, haoMa, entry("你", "ni")))
        // 拟 was never typed: nothing to forget
        m.forgetEach(listOf(niHao, haoMa, entry("拟", "ni")))
        assertEquals(listOf("拟好(ni hao)", "好吗(hao ma)"), heard)
        assertFalse(inTrie(m, "拟好", "ni", "hao"))
        assertFalse(inTrie(m, "好吗", "hao", "ma"))
        assertEquals(emptyList<Pair<Entry, Float>>(), m.ownWords())
        assertEquals(listOf("你"), m.ownTexts())
        assertEquals(1f, m.total)
        // each written as forgotten alone: read back, neither is a word of the user's any more
        val again = model().also { UserLog.read(log.toByteArray(), it) }
        assertEquals(emptyList<Pair<Entry, Float>>(), again.ownWords())
        assertFalse(inTrie(again, "拟好", "ni", "hao"))
        assertEquals(1f, again.total)
    }

    private companion object {
        // 行, one word of the dictionary's read two ways
        val polyphones = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("行", -2f, 0f)
                    .entry("行", syl("hang"))
                    .entry("行", syl("xing"))
                    .build().toByteArray(),
            ),
        )
    }
}
