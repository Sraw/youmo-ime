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
import org.fcitx.fcitx5.android.engine.user.UserFixtures.model
import org.fcitx.fcitx5.android.engine.user.UserFixtures.syl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class LibimeImportTest {

    private fun counts(m: UserModel): Map<String, Float> {
        val out = HashMap<String, Float>()
        m.forEachCount({ e, c -> out[e.toString()] = c }, { a, b, c -> out["$a $b"] = c })
        return out
    }

    @Test
    fun aWordIsReadAsLibimeSpellsIt() {
        assertEquals(entry("你好", "ni", "hao"), LibimeImport.entry("你好", "ni'hao"))
        assertEquals(entry("绿", "lv"), LibimeImport.entry("绿", "lü"))
        assertEquals(entry("虐", "nve"), LibimeImport.entry("虐", "nüe"))
        assertEquals(entry("略", "lve"), LibimeImport.entry("略", "lue"))
        // no such syllable; a reading of another length; nothing read
        assertNull(LibimeImport.entry("你", "nii"))
        assertNull(LibimeImport.entry("你好", "ni"))
        assertNull(LibimeImport.entry("你", ""))
        assertNull(LibimeImport.entry("", "ni"))
    }

    @Test
    fun aLineOfTheUserDictionaryIsTextReadingAndCost() {
        assertEquals(entry("你好", "ni", "hao"), LibimeImport.dictionaryEntry("你好 ni'hao 0"))
        assertEquals(entry("你好", "ni", "hao"), LibimeImport.dictionaryEntry("\"你好\" ni'hao -1.5"))
        // as libime reads it: the cost may be left out, and a tab is whitespace like any
        assertEquals(entry("你好", "ni", "hao"), LibimeImport.dictionaryEntry("你好 ni'hao"))
        assertEquals(entry("你好", "ni", "hao"), LibimeImport.dictionaryEntry("  你好\tni'hao\t0\r"))
        assertNull(LibimeImport.dictionaryEntry(""))
        assertNull(LibimeImport.dictionaryEntry("你好"))
        assertNull(LibimeImport.dictionaryEntry("你好 ni'hao x"))
        assertNull(LibimeImport.dictionaryEntry("你好\tni'hao 0 0"))
        // `#` starts no comment: two values are a word and its reading, whatever the word
        assertEquals(entry("#", "hao"), LibimeImport.dictionaryEntry("# hao"))
    }

    @Test
    fun aDictionaryIsKeptAsTheEngineReadsIt() {
        val text = LibimeImport.dictionaryText(
            sequenceOf("你好\tni'hao\t0", "绿 lü", "\"你\\\"\" ni'hao -2.5", "你 nii 0", "你好 ni'hao cost", "", "虐 nüe 1e2"),
        ).toList()
        assertEquals(listOf("你好 ni'hao 0.0", "绿 lü 0.0", "\"你\\\"\" ni'hao -2.5", "虐 nüe 100.0"), text)
        assertEquals(listOf(entry("你好", "ni", "hao"), entry("绿", "lv")), text.take(2).map { LibimeImport.dictionaryEntry(it) })
    }

    @Test
    fun aHistoryLineIsWordsEachWithItsReading() {
        assertEquals(
            listOf(listOf(entry("你好", "ni", "hao"), entry("拟", "ni"))),
            LibimeImport.sentences("你好\tni'hao 拟\tni"),
        )
        // a word unread, or read as no syllable, splits the sentence
        assertEquals(
            listOf(listOf(entry("你", "ni")), listOf(entry("拟", "ni"))),
            LibimeImport.sentences("你\tni ABC\t 拟\tni"),
        )
        assertEquals(emptyList<List<UserModel.Entry>>(), LibimeImport.sentences("你好 拟"))
        assertEquals(emptyList<List<UserModel.Entry>>(), LibimeImport.sentences(""))
    }

    @Test
    fun valuesInQuotesAreUnescaped() {
        val words = LibimeImport.words("\"a b\"\t\"x\\\"y\" \"\" c\\d \"n\\nm\"")
        assertEquals(listOf("a b", "", "c\\d", "n\nm"), words.map { it.text })
        assertEquals(listOf("x\"y", null, null, null), words.map { it.code })
    }

    @Test
    fun historyIsLearnedThenTheDictionarysWordsItLacks() {
        val m = model()
        val learned = LibimeImport.learn(
            m, data.dictionary, data.vocabulary,
            userDictionary = sequenceOf("你好 ni'hao 0", "拟 ni 0", "garbage"),
            // newest first: 拟 after 你好 was the last thing typed
            history = sequenceOf("你好\tni'hao 拟\tni", "你\tni"),
        )
        assertEquals(3, learned)
        val c = counts(m)
        assertEquals(1f, c["你好(ni hao)"])
        assertEquals(1f, c["拟(ni)"])
        assertEquals(1f, c["你(ni)"])
        assertEquals(1f, c["你好(ni hao) 拟(ni)"])
        // 你 then 你好 were separate sentences: no pair
        assertNull(c["你(ni) 你好(ni hao)"])
        assertTrue(m.probability(NO_WORD, m.id(entry("拟", "ni"))) > 0f)
    }

    @Test
    fun aDictionaryWordNotInTheHistoryIsLearnedOnce() {
        val m = model()
        assertEquals(1, LibimeImport.learn(m, data.dictionary, data.vocabulary, sequenceOf("你好 ni'hao 0", "你好 ni'hao 0"), emptySequence()))
        assertEquals(mapOf("你好(ni hao)" to 1f), counts(m))
    }

    @Test
    fun aWordKeptWithoutItsReadingIsReadAsTheDictionaryReadsIt() {
        val d = polyphones
        val readings = LibimeImport.readings(d.dictionary, d.vocabulary, setOf("行", "银行", "好", "ABC", "银好"))
        assertEquals(mapOf("行" to "xing", "银行" to "yin hang", "好" to "hao"), readings.mapValues { (_, s) -> s.joinToString(" ") { Syllables.spelling(it) } })
        assertEquals(entry("银行", "yin", "hang"), LibimeImport.read("银行", readings))
        // not a word of the dictionary: its characters' readings
        assertEquals(entry("行好", "xing", "hao"), LibimeImport.read("行好", readings))
        assertNull(LibimeImport.read("行A", readings))
        assertNull(LibimeImport.read("", readings))
        assertEquals(emptyMap<String, IntArray>(), LibimeImport.readings(d.dictionary, d.vocabulary, emptySet()))
    }

    @Test
    fun aTextAloneIsReadByTheSyllablesOfItsCharacters() {
        val d = polyphones
        fun reading(text: String) = LibimeImport.readings(d.dictionary, d.vocabulary, setOf(text))[text]?.joinToString(" ") { Syllables.spelling(it) }
        // only yin then hang or xing walked: 行's rarer reading too
        assertEquals("yin hang", reading("银行"))
        assertEquals("xing", reading("行"))
        assertNull(reading("银好"))
        // 河 is no word of its own: after yin, every syllable is walked for it
        assertEquals("yin he", reading("银河"))
    }

    @Test
    fun aCharacterIsReadInsideAWordAsItIsNeverAlone() {
        val d = polyphones
        fun reading(text: String) = LibimeImport.readings(d.dictionary, d.vocabulary, setOf(text))[text]?.joinToString(" ") { Syllables.spelling(it) }
        // 儿 alone is er: read char by char, 这儿 would be zhe er
        assertEquals("zhe r", reading("这儿"))
        // a character past U+FFFF: two chars, one syllable
        assertEquals("qiu", reading("\uD840\uDC00"))
        // neither of 𠀀儿's syllables is its character's alone, more than the walk strays: so 𠀀's own were known, two chars as it is
        assertNull(reading("\uD840\uDC00儿"))
    }

    @Test
    fun aFirstCharacterIsReadAsItIsNeverAloneOnlyInAnImport() {
        val d = polyphones
        fun reading(text: String, strayFirst: Boolean) =
            LibimeImport.readings(d.dictionary, d.vocabulary, setOf(text), strayFirst)[text]?.joinToString(" ") { Syllables.spelling(it) }
        // 了 alone is le: liao, at the first depth, is walked only as an import asks
        assertNull(reading("了解", strayFirst = false))
        assertEquals("liao jie", reading("了解", strayFirst = true))
        // one stray a path still
        assertNull(reading("\uD840\uDC00儿", strayFirst = true))
        val m = UserModel(d.dictionary, d.vocabulary)
        LibimeImport.learn(m, d.dictionary, d.vocabulary, emptySequence(), sequenceOf("了解"))
        assertEquals(mapOf("了解(liao jie)" to 1f), counts(m))
    }

    @Test
    fun historyWithoutReadingsIsLearned() {
        val d = polyphones
        val m = UserModel(d.dictionary, d.vocabulary)
        // as libime dumps it: no tabs, no readings; ABC splits the line
        val learned = LibimeImport.learn(m, d.dictionary, d.vocabulary, emptySequence(), sequenceOf("银行 好 ABC 行", "行\thang"))
        assertEquals(4, learned)
        val c = counts(m)
        assertEquals(1f, c["银行(yin hang) 好(hao)"])
        // read either way, 行 is the dictionary's one word
        assertEquals(2f, c["行(xing)"])
        assertNull(c["好(hao) 行(xing)"])
    }

    @Test
    fun aCodeIsSpelledAsTheAppDecodesIt() {
        val d = polyphones
        val m = UserModel(d.dictionary, d.vocabulary)
        // libime's codes are two bytes a syllable; what does not decode is read by the dictionary
        val decode = { code: String -> if (code == "GAHB") "yin'hang" else "" }
        LibimeImport.learn(m, d.dictionary, d.vocabulary, emptySequence(), sequenceOf("银行\tGAHB 行\tZZ"), decode)
        assertEquals(mapOf("银行(yin hang)" to 1f, "行(xing)" to 1f, "银行(yin hang) 行(xing)" to 1f), counts(m))
    }

    private companion object {
        // 行 is read xing more often than hang; 银行 only yin hang; 河 only in 银河; 儿 r only in 这儿;
        // 𠀀 (U+20000) qiu, and 𠀀儿 as neither of its characters is alone; 了 le alone, liao in 了解
        val polyphones = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("行", -2f, 0f)
                    .unigram("银", -3f, 0f)
                    .unigram("银行", -3f, 0f)
                    .unigram("好", -2.5f, 0f)
                    .entry("行", syl("hang"), -1f)
                    .entry("行", syl("xing"))
                    .entry("银", syl("yin"))
                    .entry("银行", syl("yin", "hang"))
                    .entry("好", syl("hao"))
                    .entry("银河", syl("yin", "he"))
                    .entry("这", syl("zhe"))
                    .entry("儿", syl("er"))
                    .entry("这儿", syl("zhe", "r"))
                    .entry("\uD840\uDC00", syl("qiu"))
                    .entry("\uD840\uDC00儿", syl("ba", "r"))
                    .entry("了", syl("le"))
                    .entry("解", syl("jie"))
                    .entry("了解", syl("liao", "jie"))
                    .build().toByteArray(),
            ),
        )
    }
}
