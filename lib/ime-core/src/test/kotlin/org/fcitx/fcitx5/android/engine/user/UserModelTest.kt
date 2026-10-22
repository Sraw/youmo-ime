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
import java.nio.ByteBuffer

class UserModelTest {

    private fun word(text: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == text }

    // [text] forgotten at each reading, as an older build's log forgot a candidate: for the replay
    // (LegacyConversion); what this build forgets by text, the learning record forgets (HabitCountsTest)
    private fun forgetText(m: UserModel, text: String) = m.entriesOf(text).forEach { m.forget(listOf(it)) }

    // review R11 F2932: the texts whose ids changed, the last ones; past what is kept, none to go by
    @Test
    fun theTextsWhoseIdsChangedAreTheLastOnesTillMoreThanAreKept() {
        val m = model()
        val at = m.idsChanged
        m.id(entry("拟好", "ni", "hao"))
        m.id(entry("好吗", "hao", "ma"))
        assertEquals(listOf("拟好", "好吗"), m.textsChangedSince(at))
        assertEquals(listOf("好吗"), m.textsChangedSince(at + 1))
        assertEquals(emptyList<String>(), m.textsChangedSince(m.idsChanged))
        // a reading a new word each time: 2 + CHANGED_KEPT changes, more than are kept
        val syllables = listOf("ni", "hao", "ma")
        for (i in 0 until UserModel.CHANGED_KEPT) m.id(entry("拟$i", *Array(1 + i % 3) { syllables[it] }))
        assertEquals(null, m.textsChangedSince(at))
        assertEquals(UserModel.CHANGED_KEPT, m.textsChangedSince(at + 2)?.size)
        assertEquals(null, m.textsChangedSince(at + 1))
        assertEquals(null, m.textsChangedSince(-1))
    }

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
    fun countsGrowAndClear() {
        val c = Counts()
        for (k in 0L until 1000L) c.add(k * 7919, 1f)
        c.add(7919, 2f)
        assertEquals(1000, c.size)
        assertEquals(3f, c[7919])
        assertEquals(0f, c[-5])
        c.clear()
        assertEquals(0, c.size)
        assertEquals(0f, c[7919])
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
        m.learn(null, listOf(entry("你", "ni"), entry("好", "hao"), entry("吗", "ma")))
        m.learn(null, listOf(entry("你", "ni")))
        val before = m.changes
        m.forget(listOf(entry("好", "hao")))
        assertTrue(m.changes > before)
        assertEquals(0f, m.probability(NO_WORD, word("好")))
        assertEquals(3f, m.total)
        // the pairs it was in are gone, those without it stay
        val counted = HashMap<String, Float>()
        m.forEachCount({ e, c -> counted[e.toString()] = c }, { a, b, c -> counted["$a $b"] = c })
        assertEquals(mapOf("你(ni)" to 2f, "吗(ma)" to 1f), counted)
        // nothing learned of it: nothing to forget, nothing changed
        val after = m.changes
        m.forget(listOf(entry("拟", "ni")))
        assertEquals(after, m.changes)
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

    // U-82: deleted from a long press, out of the trie at once; one the user owns stays theirs to type
    @Test
    fun aWordAddedByHandUnlistedIsGoneAtOnceUnlessTheUsersOwn() {
        val m = model()
        val niZai = entry("拟再", "ni", "zai")
        m.listByHand(niZai)
        assertTrue(inTrie(m, "拟再", "ni", "zai"))
        m.unlist(niZai)
        assertFalse(inTrie(m, "拟再", "ni", "zai"))
        val haoZai = entry("好再", "hao", "zai")
        m.listByHand(haoZai)
        m.own(haoZai)
        m.unlist(haoZai)
        assertTrue(inTrie(m, "好再", "hao", "zai"))
        // a word of the dictionary's stays the dictionary's
        m.unlist(entry("你", "ni"))
        assertTrue(m.knows(entry("你", "ni")))
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
        val id = m.own(word)
        assertTrue(m.blocked(id, -1))
        assertEquals(emptyList<Pair<Entry, Float>>(), m.ownWords())
        m.disown(word)
        val again = m.own(word)
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
        val id = m.own(hang)
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
    fun aPolyphoneIsReadAsOwnedNotAsListedOrBlocked() {
        val m = UserModel(polyphones.dictionary, polyphones.vocabulary)
        val hang = entry("行", "hang")
        val xing = entry("行", "xing")
        val id = m.own(hang)
        m.list(xing)
        m.block(xing)
        m.unblock(xing)
        assertEquals(hang.syllables.toList(), m.reading(id)?.toList())
        // owned the other way, it is read so
        m.own(xing)
        assertEquals(xing.syllables.toList(), m.reading(id)?.toList())
    }

    @Test
    fun aWordIsReadAsTheUserHasItOnlyWhileTheyHaveIt() {
        val m = UserModel(polyphones.dictionary, polyphones.vocabulary)
        val hang = entry("行", "hang")
        val id = m.own(hang)
        // no longer theirs, the dictionary's word keeps the entry it was owned as, but not as a reading
        m.disown(hang)
        assertEquals(null, m.reading(id))
        // one of their dictionaries', never typed, is not theirs yet (its reading may be wrong); one
        // added by hand is, and one imported once theirs
        val listed = m.list(entry("行行", "hang", "hang"))
        assertEquals(null, m.reading(listed))
        val byHand = m.listByHand(entry("行行", "hang", "xing"))
        assertEquals(entry("行行", "hang", "xing").syllables.toList(), m.reading(byHand)?.toList())
        m.own(entry("行行", "hang", "hang"))
        assertEquals(entry("行行", "hang", "hang").syllables.toList(), m.reading(listed)?.toList())
        m.disown(entry("行行", "hang", "hang"))
        // their own no longer, listed: still in the trie, no longer theirs
        val own = entry("行行", "xing", "xing")
        m.list(own)
        val made = m.own(own)
        m.disown(own)
        assertEquals(null, m.reading(made))
        assertTrue(inTrie(m, "行行", "xing", "xing"))
        assertFalse(m.owns(made))
    }

    @Test
    fun theTextsSharedAreTheUsersTheirDictionariesApartAndChangesSay() {
        val m = model()
        val before = m.changes
        m.list(entry("拟好", "ni", "hao"))
        assertEquals(listOf("拟好"), m.listedTexts())
        // made, and the dictionary's: their own
        m.own(entry("好吗", "hao", "ma"))
        m.own(entry("你好", "ni", "hao"))
        assertEquals(setOf("好吗", "你好"), m.ownTexts().toSet())
        val learned = m.changes
        assertTrue(learned > before)
        // owned again, nothing new to share
        m.own(entry("你好", "ni", "hao"))
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
        forgetText(m, "好吗")
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
        forgetText(m, "好吗")
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
        forgetText(m, "好吗")
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

    @Test
    fun hasLearnedWhatForgetWouldDrop() {
        val m = model()
        val ni = entry("你", "ni")
        val hao = entry("好", "hao")
        val ma = entry("吗", "ma")
        // nothing of it: never typed, or only listed (a hand-added word not typed, which the settings remove)
        assertFalse(m.hasLearned(listOf(ni)))
        m.list(entry("你好", "ni", "hao"))
        m.listByHand(entry("拟好", "ni", "hao"))
        assertFalse(m.hasLearned(listOf(entry("你好", "ni", "hao"))))
        assertFalse(m.hasLearned(listOf(entry("拟好", "ni", "hao"))))
        // typed after a word of the dictionary's never typed itself: the word before is in a pair only
        m.learn(ni, listOf(hao))
        assertTrue(m.hasLearned(listOf(hao)))
        assertTrue(m.hasLearned(listOf(ni)))
        // a candidate of several: any of its words learned
        assertTrue(m.hasLearned(listOf(ma, hao)))
        assertFalse(m.hasLearned(listOf(ma, entry("你好", "ni", "hao"))))
        // a word the user made, alone
        val made = entry("拟吗", "ni", "ma")
        m.learn(null, listOf(made))
        assertTrue(m.hasLearned(listOf(made)))
        // forgotten, nothing left
        m.forget(listOf(hao))
        assertFalse(m.hasLearned(listOf(hao)))
        assertFalse(m.hasLearned(listOf(ni)))
    }

    @Test
    fun aWordOfTheUsersDictionariesTypedIsTheirsAsATypedWordOfTheDictionarysIs() {
        val m = model()
        val imported = entry("拟好", "ni", "hao")
        m.list(imported)
        assertFalse("拟好" in m.ownTexts())
        m.own(imported)
        assertTrue("拟好" in m.ownTexts())
        // forgotten, no longer
        m.disown(imported)
        assertFalse("拟好" in m.ownTexts())
        // theirs again then blocked: no longer either (F2433)
        m.own(imported)
        assertTrue("拟好" in m.ownTexts())
        m.block(imported)
        assertFalse("拟好" in m.ownTexts())
    }
}
