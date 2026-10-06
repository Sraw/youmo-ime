/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class UserModelTest {

    companion object {
        fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

        val data: PinyinData = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("你", -2f, 0f)
                    .unigram("拟", -5f, 0f)
                    .unigram("好", -2.5f, 0f)
                    .unigram("你好", -3f, 0f)
                    .unigram("吗", -3.5f, 0f)
                    .entry("你", syl("ni"))
                    .entry("拟", syl("ni"))
                    .entry("好", syl("hao"))
                    .entry("你好", syl("ni", "hao"))
                    .entry("吗", syl("ma"))
                    .build().toByteArray(),
            ),
        )

        fun model(limit: Float = UserModel.DEFAULT_LIMIT) = UserModel(data.dictionary, data.vocabulary, limit)

        fun entry(text: String, vararg syllables: String) = Entry(text, syl(*syllables))

        /** Whether the decoder finds [text] among [m]'s words, read as [syllables]. */
        fun inTrie(m: UserModel, text: String, vararg syllables: String): Boolean {
            var node = m.root
            for (s in syllables) node = m.child(node, Syllables.id(s)).also { if (it < 0) return false }
            return (0 until m.wordCount(node)).any { m.text(m.word(node, it)) == text }
        }
    }

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
        assertEquals(1f / 22f, m.probability(NO_WORD, ni))
        // after 你, 好 came every time; after 好, 你 never did
        val alone = 1f / 22f
        assertEquals((1f + 2f * alone) / 3f, m.probability(ni, hao))
        assertEquals((0f + 2f * alone) / 3f, m.probability(hao, ni))
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
    fun countsWrittenOutRestoreTheSame() {
        val m = model()
        m.learn(null, listOf(entry("你", "ni"), entry("拟好", "ni", "hao"), entry("吗", "ma")))
        m.learn(entry("吗", "ma"), listOf(entry("你", "ni")))
        val copy = model()
        m.forEachCount({ e, c -> copy.restore(e, c) }, { a, b, c -> copy.restore(a, b, c) })
        assertEquals(m.total, copy.total)
        val nihao = copy.id(entry("拟好", "ni", "hao"))
        for (prev in listOf(NO_WORD, word("你"), word("吗"), nihao)) {
            for (w in listOf(word("你"), word("吗"), nihao)) assertEquals(m.probability(prev, w), copy.probability(prev, w))
        }
    }

    @Test
    fun theJournalHearsEachSentence() {
        val m = model()
        val heard = ArrayList<String>()
        m.journal = UserModel.Journal { prev, sentence -> heard += "$prev ${sentence.joinToString()}" }
        m.learn(null, listOf(entry("你", "ni")))
        m.learn(entry("你", "ni"), listOf(entry("好", "hao")))
        m.restore(entry("吗", "ma"), 1f)
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
        assertTrue(!inTrie(m, "拟好", "ni", "hao"))
        assertTrue(inTrie(m, "好", "ni"))
        // learned again, a new word in the trie again
        val again = m.learn(null, listOf(entry("拟好", "ni", "hao"))).single()
        assertEquals(data.vocabulary.size + 2, again)
        assertTrue(inTrie(m, "拟好", "ni", "hao"))
        assertEquals(1f / 22f, m.probability(NO_WORD, again))
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
        // a word of the model's: the pack's score and layer are not taken
        val ni = m.id(entry("你", "ni"))
        assertEquals(ni, m.list(entry("你", "ni"), -1f, 1, modelWords = ni + 1))
        assertTrue(m.score(ni).isNaN())
        assertEquals(0, m.layerOf(ni))
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
        assertEquals(true, learned > before)
        // typed again, nothing new to share
        m.learn(null, listOf(entry("你好", "ni", "hao")))
        assertEquals(learned, m.changes)
        m.block(entry("好吗", "hao", "ma"))
        assertEquals(setOf("你好"), m.ownTexts().toSet())
        assertEquals(true, m.changes > learned)
    }

    @Test
    fun aTextIsForgottenHoweverItWasRead() {
        val m = model()
        m.learn(null, listOf(entry("好吗", "hao", "ma")))
        m.learn(null, listOf(entry("好吗", "hao", "ma"), entry("吗", "ma")))
        assertEquals(1, m.entriesOf("好吗").size)
        m.forgetText("好吗")
        assertEquals(false, "好吗" in m.ownTexts())
        assertEquals(false, inTrie(m, "好吗", "hao", "ma"))
    }

}
