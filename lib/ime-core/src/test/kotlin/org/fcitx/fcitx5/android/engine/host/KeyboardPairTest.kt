/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.RecordedSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KeyboardPair]'s routes (design §1.3); UAT HK-43 and HK-44 on a device. */
class KeyboardPairTest {

    private val data = sessionTestData()

    private val full = RecordedSession(PinyinSession(data, PinyinSegmenter(), prediction = false))
    private var keyed: RecordedSession? = null
    private var made = 0

    private val pair = KeyboardPair("pinyin", "keys", { Keyboard(full, nineKeys = false) }, {
        made++
        val s = RecordedSession(PinyinSession(data, T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14), spell = true, prediction = false))
        keyed = s
        Keyboard(s, nineKeys = true)
    }, NineKeyLetters.G14::isCode)

    private val nihao14 = NineKeyLetters.G14.typings("nihao").single()

    private fun type(keys: String): Snapshot = keys.map { pair.onEvent(EngineEvent.CHAR, it.code) }.last()

    @Test
    fun aCodeGoesToTheFourteenKeysMadeAtTheFirstALetterToFullPinyin() {
        assertEquals("你", type("ni").candidates[0])
        assertEquals(0, made)
        assertFalse(pair.keyedActive)
        pair.onEvent(EngineEvent.RESET, 0)
        assertEquals("你好", type(nihao14).candidates[0])
        assertEquals(1, made)
        assertTrue(pair.keyedActive)
        // a syllable picked off the strip is the 14 keys' too
        val s = type(nihao14.take(2))
        assertTrue(pair.onEvent(EngineEvent.CHAR, Keyboard.syllableKey(0, s.syllablesId).code).preedit.startsWith("ni"))
        assertTrue(pair.keyedActive)
    }

    @Test
    fun aTapWithItsPlaceGoesToTheFourteenKeysAsItsCode() {
        pair.onEvent(EngineEvent.CHAR, Keyboard.tapKey(g14Code('b'), 0.75f))
        assertTrue(pair.keyedActive)
        assertEquals(Action.Key(g14Code('b'), at = 0.75f), keyed!!.actions.last())
        assertTrue(full.actions.none { it is Action.Key })
    }

    @Test
    fun withNothingTypedEscapeAndDigitsAreTheAppsAfterTheFourteenKeysToo() {
        type(nihao14)
        assertEquals("你好", pair.onEvent(EngineEvent.CHAR, ' '.code).commit)
        // the 14 keys' Escape is swallowed (the nine keys' rule): full pinyin's is the app's
        assertFalse(pair.onEvent(EngineEvent.ESCAPE, 0).handled)
        assertFalse(pair.onEvent(EngineEvent.CHAR, '1'.code).handled)
        // neither moved the keys on: what follows is still the 14 keys' commit's
        assertTrue(pair.keyedActive)
    }

    // review M1: after its commit a Backspace, space or mark is the 14 keys' session's, which learned the commit (U-03)
    @Test
    fun afterTheFourteenKeysCommitTheirSessionHasTheBackspacesAndMarks() {
        type(nihao14)
        pair.onEvent(EngineEvent.CHAR, ' '.code)
        full.actions.clear()
        repeat(2) { assertFalse(pair.onEvent(EngineEvent.BACKSPACE, 0).handled) }
        pair.onEvent(EngineEvent.CHAR, '，'.code)
        assertEquals(2, keyed!!.actions.count { it is Action.Backspace })
        assertTrue(keyed!!.actions.last().let { it is Action.Key && it.char == '，' })
        assertTrue(full.actions.toString(), full.actions.isEmpty())
        // and full pinyin's after a hardware keyboard's word
        pair.onEvent(EngineEvent.CHAR, 'n'.code)
        pair.onEvent(EngineEvent.CHAR, ' '.code)
        keyed!!.actions.clear()
        pair.onEvent(EngineEvent.BACKSPACE, 0)
        assertTrue(full.actions.last() is Action.Backspace)
        assertTrue(keyed!!.actions.isEmpty())
    }

    // review S3: a hardware digit while the 14 keys type picks, as in full pinyin
    @Test
    fun aDigitWhileTheFourteenKeysTypePicks() {
        type(nihao14)
        val one = pair.onEvent(EngineEvent.CHAR, '1'.code)
        assertEquals("你好", one.commit)
        assertTrue(one.handled)
    }

    // review N2: a syllable of a strip gone stale, full pinyin typing, takes nothing and commits nothing
    @Test
    fun aStaleSyllableWhileFullPinyinTypesIsSwallowed() {
        val s = type(nihao14.take(2))
        type("n")
        val stale = pair.onEvent(EngineEvent.CHAR, Keyboard.syllableKey(0, s.syllablesId).code)
        assertTrue(stale.handled)
        assertEquals("", stale.commit)
        assertFalse(pair.keyedActive)
        assertEquals("n", type("i").preedit.take(1))
    }

    @Test
    fun whileTypingOtherKeysGoToTheOneTypingButLettersToFullPinyin() {
        type(nihao14)
        // the 14 keys' Escape drops what they typed
        val escaped = pair.onEvent(EngineEvent.ESCAPE, 0)
        assertTrue(escaped.handled)
        assertEquals("", escaped.preedit)
        type(nihao14)
        // a keypad digit (a swipe) picks
        assertEquals("你好", pair.onEvent(EngineEvent.KEYPAD, '1'.code).commit)
        type(nihao14)
        val letter = pair.onEvent(EngineEvent.CHAR, 'w'.code)
        assertFalse(pair.keyedActive)
        assertEquals("w", letter.preedit.filter { it.isLetter() })
    }

    @Test
    fun turningToTheOtherCommitsWhatWasTypedAsTypedBeforeWhatComesNext() {
        type("ni")
        pair.onEvent(EngineEvent.CHAR, ' '.code)
        type(nihao14)
        full.actions.clear()
        val w = pair.onEvent(EngineEvent.CHAR, 'w'.code)
        // review S2: both reset, as for text another wrote: the one left after its Enter, the other before its key
        assertTrue(keyed!!.actions.takeLast(2).let { it[0] is Action.CommitRaw && it[1] is Action.Reset })
        assertTrue(full.actions.toString(), full.actions[0] is Action.Reset && full.actions[1] is Action.Key)
        assertEquals("nihao", w.commit)
        assertTrue(w.commit.none(NineKeyLetters.G14::isCode))
        assertTrue(w.handled)
        // and back: full pinyin's w kept as typed
        val b = pair.onEvent(EngineEvent.CHAR, g14Code('b').code)
        assertEquals("w", b.commit)
        assertTrue(pair.keyedActive)
    }

    @Test
    fun aResetAndAContextReachBothTheFourteenKeysMadeLaterToo() {
        pair.context("我", learning = true)
        type(nihao14.take(1))
        // made after the context: told it all the same
        assertTrue(keyed!!.actions.first() is Action.Context)
        assertEquals("我", (keyed!!.actions.first() as Action.Context).before)
        pair.onEvent(EngineEvent.RESET, 0)
        assertTrue(full.actions.last() is Action.Reset)
        assertTrue(keyed!!.actions.last() is Action.Reset)
        full.actions.clear()
        pair.context("你", learning = true)
        assertTrue(full.actions.single() is Action.Context && keyed!!.actions.last() is Action.Context)
        // only the active one's offers
        assertFalse((full.actions.single() as Action.Context).offers)
    }

    // review S2: a context made old by a commit is not told to the 14 keys' session made after it
    @Test
    fun aContextACommitMadeOldIsNotToldToTheFourteenKeysMadeAfter() {
        pair.context("今天", learning = true)
        type("ni")
        assertEquals("你", pair.onEvent(EngineEvent.CHAR, ' '.code).commit)
        type(nihao14.take(1))
        assertTrue(keyed!!.actions.none { it is Action.Context })
    }

    @Test
    fun mergedPutsTheDeletionFirstOnWhatWasCommitted() {
        fun snap(commit: String, delete: Int) = KeyboardPair.NOTHING.copy(commit = commit, deleteBefore = delete)
        assertEquals(snap("ab" + "x", 1), KeyboardPair.merged(snap("abc", 1), snap("x", 1)))
        // deleting past what was committed deletes on before it
        assertEquals(snap("x", 3), KeyboardPair.merged(snap("ab", 1), snap("x", 4)))
    }

    @Test
    fun theSessionShownIsTheOneThatTookTheLastKey() {
        type(nihao14)
        pair.onEvent(EngineEvent.RESET, 0)
        assertNull(full.actions.firstOrNull { it is Action.Key })
        assertEquals("keys", pair.sessionOf("pinyin"))
        assertEquals("other", pair.sessionOf("other"))
        pair.drop()
        assertEquals("pinyin", pair.sessionOf("pinyin"))
    }

    // 双拼's: the 18 keys and the nine keys a session each, turned between as to full; a key the scheme types full's
    @Test
    fun twoKeyedSessionsTurnByTheCodeAndAKeyFullTypesIsFulls() {
        val made = ArrayList<String>()
        val scheme = ShuangpinScheme.MICROSOFT
        val sp = KeyboardPair(
            "sp",
            { c ->
                when (NineKeyLetters.ofCode(c)) {
                    NineKeyLetters.G18 -> "18"
                    NineKeyLetters.NINE_SP -> "9"
                    else -> null
                }
            },
            { Keyboard(PinyinSession(data, ShuangpinSegmenter(scheme), prediction = false), nineKeys = false) },
            { name ->
                made += name
                val letters = if (name == "18") NineKeyLetters.G18 else NineKeyLetters.NINE_SP
                Keyboard(PinyinSession(data, GroupedShuangpinSegmenter(scheme, letters), spell = true, prediction = false), nineKeys = true)
            },
            { c -> c == ';'.code },
        )
        fun codes(keys: String, letters: NineKeyLetters) = keys.map { letters.key(it) }.joinToString("")
        codes("ni", NineKeyLetters.G18).forEach { sp.onEvent(EngineEvent.CHAR, it.code) }
        assertEquals("18", sp.sessionOf("sp"))
        val nine = sp.onEvent(EngineEvent.CHAR, NineKeyLetters.NINE_SP.key('n').code)
        assertEquals(listOf("18", "9"), made)
        assertEquals("9", sp.sessionOf("sp"))
        assertEquals("ni", nine.commit)
        // ; while the nine keys type: full 双拼's, what they typed kept as typed
        val semicolon = sp.onEvent(EngineEvent.HARD_CHAR, ';'.code)
        assertFalse(sp.keyedActive)
        assertTrue(semicolon.commit, semicolon.commit.isNotEmpty() && semicolon.commit.none { NineKeyLetters.ofCode(it.code) != null })
        assertEquals("sp", sp.sessionOf("sp"))
    }

    // a glide is the full keyboard's alone: after the 14 keys' commit its GLIDE reaches full pinyin, which was offered it
    @Test
    fun aGlideAfterTheFourteenKeysCommitIsFullPinyins() {
        type(nihao14)
        pair.onEvent(EngineEvent.CHAR, ' '.code)
        assertTrue(pair.keyedActive)
        pair.onEvent(EngineEvent.CHAR, Keyboard.GLIDE)
        assertFalse(pair.keyedActive)
        assertEquals(Action.Glide, full.actions.last())
        assertTrue(keyed!!.actions.none { it == Action.Glide })
    }

    // a session that offers 联想 for a context: the pair's Escape must see what the context left on the
    // 14 keys' keyboard, whose own Escape with nothing shown is swallowed
    private class Associating : Session {
        override var learning = true
        private val shown = mutableListOf<String>()
        override fun reads(c: Char) = c in 'a'..'z'
        override fun candidates(from: Int, count: Int) = shown.drop(from).take(count).map { Choice(it) }
        override fun offers(index: Int) = emptySet<Offer>()
        override fun apply(action: Action): Snapshot {
            when (action) {
                is Action.Context -> if (action.offers && action.before.endsWith("你")) shown += "好"
                else -> shown.clear()
            }
            return Snapshot("", "", shown.toList(), 0, false, false, handled = action !is Action.Key, predicting = shown.isNotEmpty())
        }
    }

    private val associating = KeyboardPair("pinyin", "keys", { Keyboard(Associating(), nineKeys = false) }, { Keyboard(Associating(), nineKeys = true) }, NineKeyLetters.G14::isCode)

    @Test
    fun aContextThatShowsAssociationsOnTheFourteenKeysHasTheFirstEscapeCloseThem() {
        associating.onEvent(EngineEvent.CHAR, nihao14[0].code)
        assertTrue(associating.context("你"))
        val closed = associating.onEvent(EngineEvent.ESCAPE, 0)
        assertTrue(closed.handled)
        assertTrue(closed.candidates.isEmpty())
        // with them closed the next is the app's
        assertFalse(associating.onEvent(EngineEvent.ESCAPE, 0).handled)
    }
}
