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
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.import
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
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
        assertEquals("[谈话(tan hua)]", d.endings[0].words.toString())
        assertEquals(null, d.endings[1].words)
        assertEquals("[天(tian), 人(ren)]", handwritingDecoder().decode(listOf(read("天"), read("人"))).endings[0].words.toString())
    }

    @Test
    fun `a character alone is read as the user typed it, else as the dictionary likeliest reads it`() {
        // hang comes first by syllable id: learned so, it would be the user's 行 everywhere
        assertEquals("[行(xing)]", polyphoneDecoder().decode(listOf(read("行"))).endings[0].words.toString())
        val d = polyphoneData
        val user = UserModel(d.dictionary, d.vocabulary)
        user.own(UserModel.Entry("行", intArrayOf(Syllables.id("hang"))))
        assertEquals("[行(hang)]", polyphoneDecoder(user).decode(listOf(read("行"))).endings[0].words.toString())
        // forgotten, the reading it was learned at no longer wins
        user.entriesOf("行").forEach(user::disown)
        assertEquals("[行(xing)]", polyphoneDecoder(user).decode(listOf(read("行"))).endings[0].words.toString())
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
        assertEquals("[长大(zhang da)]", decoded.endings[0].words.toString())
    }

    @Test
    fun `a word found at two readings has the one the dictionary weighs higher`() {
        val syl = { s: String -> s.split(" ").map { Syllables.id(it) }.toIntArray() }
        // 行 read hang first, by syllable id: 行人 found at hang ren first, the less likely
        val d = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f).unigram("行", -2f, 0f).unigram("人", -2f, 0f).unigram("行人", -3f, 0f)
                    .entry("行", syl("hang")).entry("行", syl("xing")).entry("人", syl("ren"))
                    .entry("行人", syl("hang ren"), weight = -1f).entry("行人", syl("xing ren"))
                    .build().toByteArray(),
            ),
        )
        val readings = Predictor.charReadingsOf(d.dictionary, d.vocabulary)
        assertEquals(syl("hang xing").toList(), readings["行".codePointAt(0)]!!.toList())
        val decoder = HandwritingDecoder(d.dictionary, d.vocabulary, d.wordIndex, WordScorer.of(d.model), { readings })
        val decoded = decoder.decode(listOf(read("行"), read("人")))
        assertEquals("行人", decoded.text)
        assertEquals("[行人(xing ren)]", decoded.endings[0].words.toString())
    }

    private fun walk(r: String) = r.split(" ").map { Syllables.id(it) }.toIntArray()

    // 行 read hang and xing, 人 ren; 行人 in the dictionary at [readings] (the first weighed higher)
    private fun walkers(vararg readings: String): PinyinData {
        val b = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f).unigram("行", -2f, 0f).unigram("人", -2f, 0f).unigram("行人", -3f, 0f)
            .entry("行", walk("hang")).entry("行", walk("xing")).entry("人", walk("ren"))
        readings.forEachIndexed { i, r -> b.entry("行人", walk(r), weight = -i.toFloat()) }
        return PinyinData.load(ByteBuffer.wrap(b.build().toByteArray()))
    }

    private fun walkersDecoder(d: PinyinData, user: UserModel): HandwritingDecoder {
        val readings = Predictor.charReadingsOf(d.dictionary, d.vocabulary)
        return HandwritingDecoder(d.dictionary, d.vocabulary, d.wordIndex, UserScorer(d.model, user), { readings }, user)
    }

    @Test
    fun `a word of the dictionary the user learned at a reading of its own has that one, as a character alone`() {
        val d = walkers("xing ren", "hang ren")
        val user = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        user.own(UserModel.Entry("行人", walk("hang ren")))
        assertEquals("[行人(hang ren)]", walkersDecoder(d, user).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
        // forgotten: the dictionary's likelier reading, not the one it was learned at
        user.entriesOf("行人").forEach(user::disown)
        assertEquals("[行人(xing ren)]", walkersDecoder(d, user).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
    }

    @Test
    fun `a word both the user's and the dictionary's has the user's reading, whichever is found first`() {
        // the user's hang ren, which the dictionary lacks, found first (hang before xing by id)
        val first = walkers("xing ren")
        val theirs = UserModel(first.dictionary, first.vocabulary, index = first.wordIndex)
        theirs.own(UserModel.Entry("行人", walk("hang ren")))
        assertEquals("[行人(hang ren)]", walkersDecoder(first, theirs).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
        // the dictionary's hang ren found first: the user's xing ren after it takes its place
        val later = walkers("hang ren")
        val after = UserModel(later.dictionary, later.vocabulary, index = later.wordIndex)
        after.own(UserModel.Entry("行人", walk("xing ren")))
        assertEquals("[行人(xing ren)]", walkersDecoder(later, after).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
        // forgotten, the dictionary's reading again
        after.entriesOf("行人").forEach(after::disown)
        assertEquals("[行人(hang ren)]", walkersDecoder(later, after).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
    }

    @Test
    fun `a word added by hand has its reading, whichever is found first`() {
        // added by hand, never typed: hang ren, which the dictionary lacks, found before xing ren
        val first = walkers("xing ren")
        val theirs = UserModel(first.dictionary, first.vocabulary, index = first.wordIndex)
        theirs.listByHand(UserModel.Entry("行人", walk("hang ren")))
        val listed = walkersDecoder(first, theirs).decode(listOf(read("行"), read("人"))).endings[0]
        assertEquals("[行人(hang ren)]", listed.words.toString())
        // the whole path scores as UserScorer scores the word added by hand (the ink is 0 here)
        val listedId = theirs.id(UserModel.Entry("行人", walk("hang ren")))
        val scorer = UserScorer(first.model, theirs)
        assertEquals(scorer.score(NO_WORD, NO_WORD, listedId), listed.score, 1e-6f)
        // xing ren, found after the dictionary's hang ren
        val later = walkers("hang ren")
        val after = UserModel(later.dictionary, later.vocabulary, index = later.wordIndex)
        after.listByHand(UserModel.Entry("行人", walk("xing ren")))
        assertEquals("[行人(xing ren)]", walkersDecoder(later, after).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
    }

    @Test
    fun `an imported reading never picked yields to the dictionary's`() {
        // from a dictionary of the user's, its reading perhaps wrong: not theirs till picked, so the
        // dictionary's xing ren keeps the arc and its score, not the unknown word's (行+人 would win)
        val d = walkers("xing ren")
        val user = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        user.list(UserModel.Entry("行人", walk("hang ren")))
        val best = walkersDecoder(d, user).decode(listOf(read("行"), read("人"))).endings[0]
        assertEquals("[行人(xing ren)]", best.words.toString())
        // made the user's own, it is theirs
        user.own(UserModel.Entry("行人", walk("hang ren")))
        assertEquals("[行人(hang ren)]", walkersDecoder(d, user).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
    }

    @Test
    fun `a word of the user's no longer theirs but listed yields to the dictionary's reading`() {
        // still in the trie, found first, but no longer theirs
        val d = walkers("xing ren")
        val user = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        val own = UserModel.Entry("行人", walk("hang ren"))
        user.list(own)
        user.own(own)
        user.disown(own)
        assertEquals("[行人(xing ren)]", walkersDecoder(d, user).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
    }

    @Test
    fun `of two readings the user owns of a word the more typed wins, whichever is found first`() {
        val d = walkers("xing ren")
        val hang = UserModel.Entry("行人", walk("hang ren"))
        val xing = UserModel.Entry("行人", walk("xing ren"))
        // hang ren, the user's own, is found first
        val more = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        more.own(hang)
        more.own(xing)
        more.addCount(hang, 1f)
        more.addCount(xing, 2f)
        assertEquals("[行人(xing ren)]", walkersDecoder(d, more).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
        val fewer = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        fewer.own(hang)
        fewer.own(xing)
        fewer.addCount(hang, 2f)
        fewer.addCount(xing, 1f)
        assertEquals("[行人(hang ren)]", walkersDecoder(d, fewer).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
        // hang ren listed, not theirs, against xing ren theirs
        val listed = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        listed.list(hang)
        listed.own(xing)
        assertEquals("[行人(xing ren)]", walkersDecoder(d, listed).decode(listOf(read("行"), read("人"))).endings[0].words.toString())
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
    fun `a character the user blocked is not offered, unless it is all the ink reads`() {
        val ren = data.wordIndex.find("人")
        val blocking = handwritingDecoder(OneWord(blocked = ren))
        assertEquals(listOf("山"), blocking.decode(listOf(read("人", "山"))).sentences)
        // something to put in all the same
        assertEquals(listOf("人"), blocking.decode(listOf(read("人"))).sentences)
    }

    @Test
    fun `a stroke searches on from the characters before it, as a new decoder reads them all`() {
        val d = data
        val readings = Predictor.charReadingsOf(d.dictionary, d.vocabulary)
        // a beam of one: a position not the last keeps a sentence alone, the last all its readings
        fun oneBeam() = HandwritingDecoder(d.dictionary, d.vocabulary, d.wordIndex, WordScorer.of(d.model), { readings }, beam = 1)
        val kept = oneBeam()
        val tan = read("淡", "谈", step = 0.3)
        val hua = read("话", "天", "Ω")
        val shan = read("人", "山", step = 0.5)
        val gao = d.wordIndex.find("高")
        // more written, the last taken back, read again as it was, after other words
        val steps = listOf(
            listOf(tan) to NO_WORD, listOf(tan, hua) to NO_WORD, listOf(tan, hua, shan) to NO_WORD, listOf(tan, hua) to NO_WORD,
            listOf(tan, hua) to NO_WORD, listOf(tan) to NO_WORD, listOf(tan, hua) to gao, listOf(tan, hua, shan) to gao,
            // the last read again, as each stroke more has it: a new reading, the same or another; then the first
            listOf(tan, hua) to NO_WORD, listOf(tan, read("话", "天", "Ω")) to NO_WORD, listOf(tan, read("天", "话")) to NO_WORD,
            listOf(read("谈", "淡", step = 0.3), hua) to NO_WORD,
        )
        for ((positions, prev) in steps) {
            val got = kept.decode(positions, NO_WORD, prev)
            val fresh = oneBeam().decode(positions, NO_WORD, prev)
            assertEquals(fresh.sentences, got.sentences)
            assertEquals(fresh.endings.map { it.words }, got.endings.map { it.words })
            assertEquals(fresh.best, got.best)
            assertEquals(fresh.bestScores, got.bestScores)
        }
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

    // review-G-design M2: a word only imported is no word of a handwritten sentence (C19); owned, it is
    @Test
    fun `a word only imported is no word of a sentence written`() {
        val d = polyphoneData
        val user = UserModel(d.dictionary, d.vocabulary)
        user.attachImports(ImportedWords().import("人行" to "ren hang"))
        val readings = Predictor.charReadingsOf(d.dictionary, d.vocabulary)
        val decoder = HandwritingDecoder(d.dictionary, d.vocabulary, d.wordIndex, UserScorer(d.model, user), { readings }, user)
        fun words() = decoder.decode(listOf(read("人"), read("行"))).endings.flatMap { it.words.orEmpty() }.map { it.text }
        assertTrue("人行" !in words())
        // typed once, still not the user's (U-101, review-int-runtime Q1): no word of a sentence yet
        val renHang = UserModel.Entry("人行", intArrayOf(Syllables.id("ren"), Syllables.id("hang")))
        user.learn(null, listOf(renHang))
        assertTrue(user.startOnly(user.idsOfText("人行").first()))
        assertTrue("人行" !in words())
        // the user's, typed: a word of the sentence
        user.own(renHang)
        repeat(3) { user.learn(null, listOf(renHang)) }
        assertTrue("人行" in words())
    }

    // review S6: a merged file's word (DictionaryFile) is written as one word, at its file's reading, once learned; blocked, it is not
    @Test
    fun `a merged dictionary's word is found by its characters, and not once blocked`() {
        val d = walkers()
        val file = java.io.File.createTempFile("merged", ".ymd").apply { deleteOnExit() }
        DictionaryCompiler.compile(listOf(sequenceOf("行人 hang'ren 0.5")), file, inBase = DictionaryCompiler.inBase(d))
        val user = UserModel(d.dictionary, d.vocabulary, d.wordIndex, DictionaryFile.open(file))
        fun endings() = walkersDecoder(d, user).decode(listOf(read("行"), read("人"))).endings.map { it.words.toString() }
        // unpicked it is the unknown word, below 行 + 人; learned, the file's word is read as one, from its trie
        assertEquals(listOf("[行(hang), 人(ren)]"), endings())
        user.addCount(UserModel.Entry("行人", walk("hang ren")), 5f)
        assertEquals(listOf("[行人(hang ren)]"), endings())
        user.block(UserModel.Entry("行人", walk("hang ren")))
        assertTrue(endings().toString(), "[行人(hang ren)]" !in endings())
    }
}
