/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.SpelledForm
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.RecordingHabits
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinyin typed in zhuyin (design 051 §3.3): 大千's and 注音九键's sessions on the session's test data. */
class PinyinSessionZhuyinTest {

    private val data = sessionTestData()

    private fun session(
        nine: Boolean = false,
        habits: RecordingHabits? = null,
        toneAllows: ((Int, Int, Int) -> Boolean)? = null,
    ) = PinyinSession(
        data, T9Segmenter(abbreviations = false, letters = if (nine) ZhuyinKeys.NINE else ZhuyinKeys.DACHEN, toneAllows = toneAllows), spell = true, prediction = false,
        learnTo = LearnTo(habits ?: RecordingHabits(), if (nine) Kinds.ZHUYIN_NINE else Kinds.ZHUYIN_DACHEN),
    )

    // 大千's codes as the keyboard sends them, a mark a tone
    private fun dachen(typed: String) = typed.map { c -> ZhuyinKeys.MARKS.indexOf(c).let { if (it >= 0) Keyboard.zhuyinTone(it + 1) else Keyboard.zhuyinKey(c) } }

    private fun nine(typed: String) = typed.map { c -> Keyboard.zhuyinNineKey(ZhuyinKeys.GROUP_SYMBOLS.indexOfFirst { c in it }) }

    private fun Keyboard.type(codes: List<Int>) = codes.map { onEvent(EngineEvent.CHAR, it) }.last()

    @Test
    fun dachenTypesAWordByItsSymbolsShownInZhuyin() {
        val keyboard = Keyboard(session(), nineKeys = true)
        val out = keyboard.type(dachen("ㄋㄧˇㄏㄠˇ"))
        assertEquals("你好", out.candidates[0])
        assertEquals("ㄋㄧˇ ㄏㄠˇ", out.preedit)
    }

    @Test
    fun theNineKeysTypeAWordInFourKeys() {
        val keyboard = Keyboard(session(nine = true), nineKeys = true)
        val out = keyboard.type(nine("ㄋㄧㄏㄠ"))
        assertEquals("你好", out.candidates[0])
        assertEquals("ㄋㄧ ㄏㄠ", out.preedit)
    }

    // the bar's syllables in zhuyin as the code row is (design 051 §3.2), never pinyin's letters; one
    // tapped still settles its syllable
    @Test
    fun theNineKeysOfferTheSyllablesInZhuyin() {
        val keyboard = Keyboard(session(nine = true), nineKeys = true)
        val out = keyboard.type(nine("ㄋㄧㄏㄠ"))
        assertEquals("ㄋㄧ", out.syllables.first())
        assertTrue(out.syllables.toString(), out.syllables.all { s -> s.all { it in ZhuyinKeys.SYMBOLS } })
        val mi = out.syllables.indexOf("ㄇㄧ")
        assertTrue(out.syllables.toString(), mi > 0)
        val taken = keyboard.onEvent(EngineEvent.CHAR, Keyboard.syllableKey(mi, out.syllablesId).code)
        // taken, it is pinyin in the input; shown in symbols all the same (here no word reads it: as typed)
        assertTrue(taken.preedit, taken.preedit.startsWith("ㄇㄧ") && taken.preedit.endsWith("ㄏㄠ") && taken.preedit.none { it in 'a'..'z' })
    }

    // design 051 Q3 A: a space gives a syllable with no tone its first, then picks the first candidate
    @Test
    fun dachensSpaceIsTheFirstToneThenThePick() {
        val keyboard = Keyboard(session(), nineKeys = true)
        keyboard.type(dachen("ㄓㄨㄥ"))
        val toned = keyboard.onEvent(EngineEvent.CHAR, ' '.code)
        assertEquals("ㄓㄨㄥˉ", toned.preedit)
        assertEquals("中", toned.candidates[0])
        val picked = keyboard.onEvent(EngineEvent.CHAR, ' '.code)
        assertEquals("中", picked.commit)
    }

    // a tone ends a syllable: with nothing typed it ends nothing, typed nothing, and the next
    // syllable starts clean (it was kept unseen, then shown as its code before ㄋㄧ)
    @Test
    fun aToneWithNothingTypedIsNotTyped() {
        for (nine in listOf(false, true)) {
            val keyboard = Keyboard(session(nine = nine), nineKeys = true)
            val tone = if (nine) Keyboard.zhuyinNineTone(3) else Keyboard.zhuyinTone(3)
            val out = keyboard.onEvent(EngineEvent.CHAR, tone)
            assertEquals("", out.preedit)
            assertEquals("", out.commit)
            val typed = keyboard.type(if (nine) nine("ㄋㄧ") else dachen("ㄋㄧ"))
            assertEquals("ㄋㄧ", typed.preedit)
        }
    }

    // text not read as a word goes in as the zhuyin shown (注音文), never the codes
    @Test
    fun enterCommitsTheZhuyinShown() {
        val keyboard = Keyboard(session(), nineKeys = true)
        keyboard.type(dachen("ㄅˇ"))
        val out = keyboard.onEvent(EngineEvent.ENTER, 0)
        assertEquals("ㄅˇ", out.commit)
        assertTrue(out.commit.none { it in ZhuyinKeys.BLOCK })
    }

    // design 051 Q4 A: a tone the reading has not puts the word after, never out. The Ext-B rows: a
    // character outside the BMP (two chars, one syllable), alone and in a word with one inside it,
    // its tone read as one code point a syllable, not passed over as a length that does not match
    @Test
    fun aToneTheReadingHasNotPutsTheWordAfter() {
        val ni = Syllables.id("ni")
        // 你 is ni3 alone here; 拟 any
        val niThird = { cp: Int, syllable: Int, tone: Int -> !(cp == '你'.code && syllable == ni && tone != 3) }
        val extB = "\uD840\uDC00" // U+20000
        val extBData = pinyinDataOf(
            Triple(extB, "ni", -1f), Triple("你", "ni", -2f),
            Triple(extB + "好", "ni hao", -1f), Triple("你好", "ni hao", -2f), Triple("好", "hao", -2.5f),
        )
        val extBThird = { cp: Int, _: Int, tone: Int -> !(cp == 0x20000 && tone != 3) }
        class Row(val data: PinyinData, val allows: (Int, Int, Int) -> Boolean, val typed: String, val first: String, val after: String?, val why: String)
        assertRows(
            listOf(
                Row(data, niThird, "ㄋㄧ", "你", null, "no tone typed: nothing put after"),
                Row(data, niThird, "ㄋㄧˇ", "你", null, "the tone the reading has"),
                Row(data, niThird, "ㄋㄧˊ", "拟", "你", "a tone it has not: after, still there"),
                Row(extBData, extBThird, "ㄋㄧˇ", extB, null, "Ext-B alone, its tone"),
                Row(extBData, extBThird, "ㄋㄧˊ", "你", extB, "Ext-B alone, a tone it has not: one code point read as one syllable"),
                Row(extBData, extBThird, "ㄋㄧˇㄏㄠˇ", extB + "好", null, "Ext-B in a word, its tone"),
                Row(extBData, extBThird, "ㄋㄧˊㄏㄠˇ", "你好", extB + "好", "a tone the reading has not puts a word of an Ext-B character after: in a word, a tone it has not, not passed over as a length that does not match"),
            ),
            { it.why },
        ) {
            val candidates = PinyinSession(
                it.data, T9Segmenter(abbreviations = false, letters = ZhuyinKeys.DACHEN, toneAllows = it.allows), spell = true, prediction = false,
                learnTo = LearnTo(RecordingHabits(), Kinds.ZHUYIN_DACHEN),
            ).let { s -> dachen(it.typed).map { k -> s.apply(Key(Keyboard.keyedChar(k)!!)) }.last().candidates }
            assertEquals(it.first, candidates[0])
            it.after?.let { a -> assertTrue(candidates.toString(), a in candidates) }
        }
    }

    @Test
    fun aPinIsFiledUnderTheZhuyinKind() {
        for (nine in listOf(false, true)) {
            val habits = RecordingHabits()
            val keyboard = Keyboard(session(nine, habits), nineKeys = true)
            keyboard.type(if (nine) nine("ㄋㄧ") else dachen("ㄋㄧ"))
            keyboard.onEvent(EngineEvent.PIN, 0)
            val kind = if (nine) Kinds.ZHUYIN_NINE else Kinds.ZHUYIN_DACHEN
            assertTrue(habits.pins.toString(), habits.pins.single().startsWith("$kind:"))
        }
    }

    // zhuyin's keys spell no pinyin letters: its kinds link no spellings (U-20), their keys filed by the code
    @Test
    fun theZhuyinKindsLinkNoSpellings() {
        assertTrue(Kinds.isZhuyin(Kinds.ZHUYIN_NINE) && Kinds.isZhuyin(Kinds.ZHUYIN_DACHEN))
        assertNull(SpelledForm.of(Kinds.ZHUYIN_DACHEN, "abcd", "ni hao"))
        assertEquals('a', SpelledForm.filedUnder(Kinds.ZHUYIN_NINE, "ab", "ni hao"))
    }

    private fun Keyboard.take(syllables: List<String>, id: Int, shown: String) =
        onEvent(EngineEvent.CHAR, Keyboard.syllableKey(syllables.indexOf(shown).also { assertTrue(syllables.toString(), it >= 0) }, id).code)

    // ㄓ is zhi whole and zh's initial alike: the bar has it once, as the initial (taken, the symbol
    // typed as itself), not twice where none could tell them apart
    @Test
    fun aWholeSyllableShownAsItsInitialIsOnTheBarOnceAsTheInitial() {
        for (symbol in "ㄓㄔㄕㄖㄗㄘㄙ") {
            val s = session(nine = true)
            val keyboard = Keyboard(s, nineKeys = true)
            val out = keyboard.type(nine(symbol.toString()))
            assertEquals(out.syllables.toString(), 1, out.syllables.count { it == symbol.toString() })
            val taken = keyboard.take(out.syllables, out.syllablesId, symbol.toString())
            assertEquals(symbol.toString(), taken.preedit)
            // taken again: the key it is already, nothing changes, and one ⌫ still gives the group key back
            val again = keyboard.take(taken.syllables, taken.syllablesId, symbol.toString())
            assertEquals(symbol.toString(), again.preedit)
            assertEquals(out.syllables, s.apply(Action.Backspace).syllables)
        }
    }

    // the initial taken goes on into a final typed next (ㄓ ㄨㄥ, 中); none after, its whole syllable (ㄖ, ri)
    @Test
    fun anInitialTakenGoesOnIntoTheFinalOrIsItsWholeSyllable() {
        val zhong = Keyboard(session(nine = true), nineKeys = true)
        val zh = zhong.type(nine("ㄓ"))
        zhong.take(zh.syllables, zh.syllablesId, "ㄓ")
        val out = zhong.type(nine("ㄨㄥ"))
        assertEquals("ㄓㄨㄥ", out.preedit)
        assertEquals("中", out.candidates[0])
        val riqi = Keyboard(session(nine = true), nineKeys = true)
        val r = riqi.type(nine("ㄖ"))
        riqi.take(r.syllables, r.syllablesId, "ㄖ")
        assertEquals("日期", riqi.type(nine("ㄑㄧ")).candidates[0])
        // every initial is taken so, not only the seven a whole syllable shares: ㄋ (of ㄇㄋㄜ) taken is ㄋ picked
        // off its key, as a long press picks it, and the keys after go on from it (ㄋ ㄧ ㄏㄠ, 你好). The zhuyin
        // keyboards have no 简拼 (abbreviations off), so an initial alone is not a word's first syllable
        val nh = Keyboard(session(nine = true), nineKeys = true)
        val n = nh.type(nine("ㄋ"))
        assertEquals("ㄋ", nh.take(n.syllables, n.syllablesId, "ㄋ").preedit)
        assertEquals("你好", nh.type(nine("ㄧㄏㄠ")).candidates[0])
        assertEquals("ㄋㄏ", Keyboard(session(nine = true), nineKeys = true).let { k ->
            val m = k.type(nine("ㄋ"))
            k.take(m.syllables, m.syllablesId, "ㄋ")
            k.type(nine("ㄏ")).candidates[0]
        })
        // given back by ⌫ right after: the key as it was
        val s = session(nine = true)
        val back = Keyboard(s, nineKeys = true)
        val typed = back.type(nine("ㄓ"))
        back.take(typed.syllables, typed.syllablesId, "ㄓ")
        val undone = s.apply(Action.Backspace)
        assertEquals(typed.preedit, undone.preedit)
        // the group key's, not ㄓ's alone: the group's other initial offered again
        assertTrue(undone.syllables.toString(), "ㄗ" in undone.syllables)
    }
}
