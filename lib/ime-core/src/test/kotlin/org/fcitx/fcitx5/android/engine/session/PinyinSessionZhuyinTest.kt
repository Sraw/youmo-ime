/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.SpelledForm
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.RecordingHabits
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Take
import org.fcitx.fcitx5.android.engine.learn.backup.BackupHead
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.learn.row
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinyin typed in zhuyin: 大千's and 注音九键's sessions on the session's test data. */
class PinyinSessionZhuyinTest {

    private val data = sessionTestData()

    private fun session(
        nine: Boolean = false,
        habits: Habits? = null,
        toneAllows: ((Int, Int, Int) -> Boolean)? = null,
        user: UserModel? = null,
        data: PinyinData = this.data,
    ) = PinyinSession(
        data, T9Segmenter(abbreviations = false, letters = if (nine) ZhuyinKeys.NINE else ZhuyinKeys.DACHEN, toneAllows = toneAllows), spell = true, prediction = false, user = user,
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

    // the bar's syllables in zhuyin as the code row is, never pinyin's letters; one tapped still settles its syllable
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

    // with nothing typed a tone types nothing, and the next syllable starts clean
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

    // a tone the reading has not puts the word after, never out; a non-BMP character's tone is read as one code point
    @Test
    fun aToneTheReadingHasNotPutsTheWordAfter() {
        val ni = Syllables.id("ni")
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

    // zhuyin's keys spell no pinyin letters: its kinds link no spellings, their keys filed by the code
    @Test
    fun theZhuyinKindsLinkNoSpellings() {
        assertTrue(Kinds.isZhuyin(Kinds.ZHUYIN_NINE) && Kinds.isZhuyin(Kinds.ZHUYIN_DACHEN))
        assertNull(SpelledForm.of(Kinds.ZHUYIN_DACHEN, "abcd", "ni hao"))
        assertEquals('a', SpelledForm.filedUnder(Kinds.ZHUYIN_NINE, "ab", "ni hao"))
    }

    private fun Keyboard.take(syllables: List<String>, id: Int, shown: String) =
        onEvent(EngineEvent.CHAR, Keyboard.syllableKey(syllables.indexOf(shown).also { assertTrue(syllables.toString(), it >= 0) }, id).code)

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
        // every initial is taken so; the zhuyin keyboards have no 简拼, so an initial alone is no word's first syllable
        val nh = Keyboard(session(nine = true), nineKeys = true)
        val n = nh.type(nine("ㄋ"))
        assertEquals("ㄋ", nh.take(n.syllables, n.syllablesId, "ㄋ").preedit)
        assertEquals("你好", nh.type(nine("ㄧㄏㄠ")).candidates[0])
        assertEquals("ㄋㄏ", Keyboard(session(nine = true), nineKeys = true).let { k ->
            val m = k.type(nine("ㄋ"))
            k.take(m.syllables, m.syllablesId, "ㄋ")
            k.type(nine("ㄏ")).candidates[0]
        })
        val s = session(nine = true)
        val back = Keyboard(s, nineKeys = true)
        val typed = back.type(nine("ㄓ"))
        back.take(typed.syllables, typed.syllablesId, "ㄓ")
        val undone = s.apply(Action.Backspace)
        assertEquals(typed.preedit, undone.preedit)
        // the group key's, not ㄓ's alone: the group's other initial offered again
        assertTrue(undone.syllables.toString(), "ㄗ" in undone.syllables)
    }

    private fun typed(nine: Boolean, symbols: String, tone: Int = 0) =
        (if (nine) nine(symbols) else dachen(symbols)) + if (tone == 0) emptyList() else listOf(if (nine) Keyboard.zhuyinNineTone(tone) else Keyboard.zhuyinTone(tone))

    // each pick in a field of its own: none follows another
    private fun Session.pick(text: String) {
        apply(Action.Pick(candidates(0, 50).indexOfFirst { it.text == text }.also { check(it >= 0) { text } }))
        apply(Action.Reset)
    }

    // the first offered for a fresh input, left empty again
    private fun Keyboard.firstFor(s: Session, codes: List<Int>) = type(codes).candidates[0].also { s.apply(Action.Reset) }

    private class LastCommit(private val inner: Habits) : Habits by inner {
        var last: LearnRecord.Commit? = null
        override fun record(rows: List<Row>, takes: List<Take>, flags: Int) = inner.record(rows, takes, flags).also { last = it }
    }

    private fun kind(nine: Boolean) = if (nine) Kinds.ZHUYIN_NINE else Kinds.ZHUYIN_DACHEN


    @Test
    @Uat("PY-161")
    fun aWordTypedWithAToneAndWithoutLearnsAsOne() {
        for (nine in listOf(false, true)) {
            val learner = Learner(data)
            val log = learner.log
            val s = session(nine, learner.habits, user = learner.model)
            val keyboard = Keyboard(s, nineKeys = true)
            assertNotEquals("拟", keyboard.firstFor(s, typed(nine, "ㄋㄧ")))
            keyboard.type(typed(nine, "ㄋㄧ", tone = 3))
            s.pick("拟")
            keyboard.type(typed(nine, "ㄋㄧ"))
            s.pick("拟")
            assertEquals("拟", keyboard.firstFor(s, typed(nine, "ㄋㄧ")))
            assertEquals("拟", keyboard.firstFor(s, typed(nine, "ㄋㄧ", tone = 3)))
            val codes = log.state.stats.keys.filter { it.text == "拟" }.map { it.code }.toSet()
            assertEquals(codes.toString(), 1, codes.size)
            assertTrue(codes.single().kind == kind(nine) && codes.single().keys.none { ZhuyinKeys.toneOf(it) > 0 })
        }
    }

    @Test
    @Uat("PY-161")
    fun aTonedPickUndoesAndForgetsFromTheOneRecord() {
        for (nine in listOf(false, true)) {
            val learner = Learner(data)
            val log = learner.log
            val habits = LastCommit(learner.habits)
            val s = session(nine, habits, user = learner.model)
            val keyboard = Keyboard(s, nineKeys = true)
            repeat(2) {
                keyboard.type(typed(nine, "ㄋㄧ", tone = 3))
                s.pick("拟")
            }
            assertEquals("拟", keyboard.firstFor(s, typed(nine, "ㄋㄧ")))
            assertTrue(habits.undo(habits.last!!))
            assertNotEquals("拟", keyboard.firstFor(s, typed(nine, "ㄋㄧ")))
            habits.forget("拟")
            assertTrue(log.state.stats.keys.none { it.text == "拟" })
        }
    }

    // a code of a tone, written before tones were dropped: no input reads it again, yet it is kept, backed up and forgotten as any
    @Test
    @Uat("PY-161")
    fun anOldTonedRecordIsKeptButReadNoMore() {
        for (nine in listOf(false, true)) {
            val learner = Learner(data)
            val log = learner.log
            val s = session(nine, learner.habits, user = learner.model)
            val keyboard = Keyboard(s, nineKeys = true)
            keyboard.type(typed(nine, "ㄋㄧ"))
            val committed = s.apply(Action.Select(0)).commit
            val untoned = log.state.stats.keys.first { it.text == committed }.code.keys
            val toned = Code(kind(nine), untoned + ZhuyinKeys.toneCode(3))
            repeat(2) { log.record(listOf(row(toned.keys, "拟", "ni", flags = Row.PICK or Row.WHOLE or Row.CORRECTED, kind = toned.kind, how = 'B'))) }
            log.pin(Code(kind(nine), untoned + ZhuyinKeys.toneCode(4)), "逆", "ni", on = true)
            assertTrue(log.state.stats.keys.any { it.code == toned && it.text == "拟" })
            for (tone in listOf(0, 3, 4)) {
                val first = keyboard.firstFor(s, typed(nine, "ㄋㄧ", tone))
                assertTrue(first, first != "拟" && first != "逆")
            }
            val head = BackupHead(FakeClock.START, 1, 1L, BackupHead.SAVED, "0.6.3")
            assertEquals(log.state.dump(), LearnBackup.read(LearnBackup.bytes(head, log.state, emptyList(), emptyList())).state.dump())
            log.forget("拟")
            assertTrue(log.state.stats.keys.none { it.text == "拟" })
        }
    }

    // 妈 mā, 马 mǎ, 吗 ma: the tones each may take (the rest any)
    private val maData = pinyinDataOf(Triple("妈", "ma", -2f), Triple("马", "ma", -3f), Triple("吗", "ma", -3.5f),
        Triple("好", "hao", -2.5f), Triple("你", "ni", -2f), Triple("好马", "hao ma", -6f),
    )
    private val maTones = mapOf('妈'.code to 1, '马'.code to 3, '吗'.code to 5)
    private val maAllows = { cp: Int, _: Int, tone: Int -> maTones[cp]?.let { it == tone } ?: true }

    @Test
    @Uat("PY-161")
    fun aWordLearnedFirstGivesWayToATypedToneItHasNot() {
        for (nine in listOf(false, true)) {
            val learner = Learner(maData)
            val s = session(nine, learner.habits, maAllows, learner.model, maData)
            val keyboard = Keyboard(s, nineKeys = true)
            assertEquals("妈", keyboard.firstFor(s, typed(nine, "ㄇㄚ")))
            repeat(2) {
                keyboard.type(typed(nine, "ㄇㄚ"))
                s.pick("马")
            }
            assertEquals("no tone typed: learned first as before", "马", keyboard.firstFor(s, typed(nine, "ㄇㄚ")))
            assertEquals("its tone: first", "马", keyboard.firstFor(s, typed(nine, "ㄇㄚ", tone = 3)))
            assertEquals("a tone it has not: after, as unlearned", "妈", keyboard.firstFor(s, typed(nine, "ㄇㄚ", tone = 1)))
        }
    }

    @Test
    @Uat("PY-161")
    fun aPinGivesWayToATypedToneItHasNot() {
        for (nine in listOf(false, true)) {
            val learner = Learner(maData)
            val s = session(nine, learner.habits, maAllows, learner.model, maData)
            val keyboard = Keyboard(s, nineKeys = true)
            val shown = keyboard.type(typed(nine, "ㄇㄚ")).candidates
            keyboard.onEvent(EngineEvent.PIN, shown.indexOf("马"))
            s.apply(Action.Reset)
            assertEquals("no tone typed: pinned first as before", "马", keyboard.firstFor(s, typed(nine, "ㄇㄚ")))
            assertEquals("its tone: first", "马", keyboard.firstFor(s, typed(nine, "ㄇㄚ", tone = 3)))
            assertEquals("a tone it has not: not in front", "妈", keyboard.firstFor(s, typed(nine, "ㄇㄚ", tone = 1)))
            // a pinned word in a sentence is put in only where no typed tone disagrees
            val word = typed(nine, "ㄏㄠ") + typed(nine, "ㄇㄚ")
            keyboard.onEvent(EngineEvent.PIN, keyboard.type(word).candidates.indexOf("好马"))
            s.apply(Action.Reset)
            assertEquals("好妈你", keyboard.firstFor(s, typed(nine, "ㄏㄠ", tone = 3) + typed(nine, "ㄇㄚ", tone = 1) + typed(nine, "ㄋㄧ", tone = 3)))
            assertEquals("好马你", keyboard.firstFor(s, word + typed(nine, "ㄋㄧ")))
            assertEquals("好马你", keyboard.firstFor(s, typed(nine, "ㄏㄠ", tone = 3) + typed(nine, "ㄇㄚ", tone = 3) + typed(nine, "ㄋㄧ", tone = 3)))
        }
    }
}
