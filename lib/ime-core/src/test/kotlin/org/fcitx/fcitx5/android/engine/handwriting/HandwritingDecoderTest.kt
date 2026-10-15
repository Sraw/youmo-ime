/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class HandwritingDecoderTest {

    private val data = handwritingData
    private val decoder = handwritingDecoder()

    @Test
    fun `a later character puts an earlier one right`() {
        // 淡 the pad's first reading, and the likelier alone
        assertEquals("淡", decoder.decode(listOf(read("淡", "谈", step = 0.3))).text)
        // with 话 after it, the word 谈话
        assertEquals("谈话", decoder.decode(listOf(read("淡", "谈", step = 0.3), read("话"))).text)
    }

    @Test
    fun `the words found before are found again as more is written, as a new decoder finds them`() {
        val d = handwritingDecoder()
        val tan = read("淡", "谈", step = 0.3)
        val hua = read("话", "天")
        assertEquals("淡", d.decode(listOf(tan)).text)
        assertEquals("谈话", d.decode(listOf(tan, hua)).text)
        // the last read again
        assertEquals("淡天", d.decode(listOf(tan, read("天"))).text)
        val other = listOf(read("话"), tan, hua)
        assertEquals(handwritingDecoder().decode(other).sentences, d.decode(other).sentences)
    }

    @Test
    fun `each sentence has its words as found, none where a character has no reading`() {
        val d = handwritingDecoder().decode(listOf(read("淡", "谈", step = 0.3), read("话", "鬼")))
        assertEquals(listOf("谈话", "淡鬼"), d.sentences)
        assertEquals("[谈话(tan hua)]", d.words[0].toString())
        assertEquals(null, d.words[1])
        assertEquals("[天(tian), 人(ren)]", handwritingDecoder().decode(listOf(read("天"), read("人"))).words[0].toString())
    }

    @Test
    fun `a word of a character read two ways has the reading the dictionary gives it`() {
        val syl = { s: String -> s.split(" ").map { Syllables.id(it) }.toIntArray() }
        // 长 read chang first, and chang da leads to a word (畅达): 长大 is not there, but at zhang da
        val d = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f).unigram("长", -2f, 0f).unigram("大", -2f, 0f)
                    .unigram("长大", -3f, 0f).unigram("畅达", -5f, 0f)
                    .entry("长", syl("chang")).entry("长", syl("zhang")).entry("大", syl("da"))
                    .entry("长大", syl("zhang da")).entry("畅达", syl("chang da"))
                    .build().toByteArray(),
            ),
        )
        val readings = Predictor.charReadingsOf(d.dictionary, d.vocabulary)
        assertEquals(syl("chang zhang").toList(), readings["长".codePointAt(0)]!!.toList())
        val decoder = HandwritingDecoder(d.dictionary, d.vocabulary, d.wordIndex, WordScorer.of(d.model), { readings })
        val decoded = decoder.decode(listOf(read("长"), read("大")))
        assertEquals("长大", decoded.text)
        assertEquals("[长大(zhang da)]", decoded.words[0].toString())
    }

    @Test
    fun `the words before weigh the sentence`() {
        val shan = listOf(read("人", "山", step = 0.5))
        assertEquals("人", decoder.decode(shan).text)
        val gao = data.wordIndex.find("高")
        assertEquals("山", decoder.decode(shan, NO_WORD, gao).text)
    }

    @Test
    fun `each reading of the last position is offered, as its best sentence has it`() {
        val d = decoder.decode(listOf(read("淡", "谈", step = 0.3), read("话", "天", "Ω")))
        assertEquals(listOf("谈话", "淡天", "淡Ω"), d.sentences)
        // a character the model lacks is a word of its own, the unknown word
        assertEquals("Ω", decoder.decode(listOf(read("Ω"))).text)
        // one past the BMP, two chars, offered whole
        assertEquals(setOf("淡𠀀", "淡天"), decoder.decode(listOf(read("淡"), read("𠀀", "天", step = 0.1))).sentences.toSet())
        assertTrue(decoder.decode(emptyList()).sentences.isEmpty())
        assertEquals("", decoder.decode(emptyList()).text)
    }

    // the user's one word, 淡话 (dan hua), past the dictionary's
    private inner class OneWord(private val blocked: Int = -2) : UserWords {
        val id = data.vocabulary.size
        private val dan = 1
        private val hua = 2
        override fun child(node: Int, syllable: Int) = when {
            node == 0 && syllable == Syllables.id("dan") -> dan
            node == dan && syllable == Syllables.id("hua") -> hua
            else -> -1
        }

        override fun childCount(node: Int) = if (node == hua) 0 else 1
        override fun wordCount(node: Int) = if (node == hua) 1 else 0
        override fun word(node: Int, index: Int) = id
        override fun text(word: Int) = "淡话"
        // blocked as read some other way (行 as hang, not xing): ink has no reading, so blocked anyhow
        override fun blocked(word: Int, node: Int) = false
        override fun blockedAnyhow(word: Int) = word == blocked
    }

    @Test
    fun `the user's words are found, and those blocked are not`() {
        val user = OneWord()
        val model = WordScorer.of(data.model)
        val likes = WordScorer { prev2, prev, word -> if (word == user.id) -1f else model.score(prev2, prev, word) }
        val written = listOf(read("淡", "谈", step = 0.3), read("话"))
        assertEquals("淡话", handwritingDecoder(user, likes).decode(written).text)
        // the user blocked theirs, or the dictionary's 谈话
        assertEquals("谈话", handwritingDecoder(OneWord(blocked = user.id), likes).decode(written).text)
        val tanhua = data.wordIndex.find("谈话")
        assertNotEquals("谈话", handwritingDecoder(OneWord(blocked = tanhua), model).decode(written).text)
    }

    @Test
    fun `a coarse word costs more`() {
        val written = listOf(read("淡", "谈", step = 0.3), read("话"))
        val coarse = CoarseWords.of({ data.wordIndex.find(it) }, listOf("谈话"))
        assertEquals("淡话", handwritingDecoder(coarse = coarse).decode(written).text)
    }

    @Test
    fun `a weight or a count out of range is refused`() {
        val d = data
        assertThrows(IllegalArgumentException::class.java) {
            HandwritingDecoder(d.dictionary, d.vocabulary, d.wordIndex, WordScorer.of(d.model), { emptyMap() }, inkWeight = -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HandwritingDecoder(d.dictionary, d.vocabulary, d.wordIndex, WordScorer.of(d.model), { emptyMap() }, beam = 0)
        }
    }
}
