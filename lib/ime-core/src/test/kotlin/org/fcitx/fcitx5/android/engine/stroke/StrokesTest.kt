/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.fcitx.fcitx5.android.engine.session.syl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class StrokesTest {

    // 一 h, 十 hs, 土 hsh, 王 hhsh, 丰 hhhs; 㐄 (rare, unknown to the model) hzs
    private val dict = """
        # Rime dictionary: stroke
        ---
        name: stroke
        ...

        一	h
        十	hs
        土	hsh	100
        王	hhsh
        丰	hhhs
        㐄	hzs
        干	hhs
        𠀀	hzs
    """.trimIndent()

    private val scores = mapOf("一" to -2f, "十" to -3f, "土" to -3.5f, "王" to -3.2f, "丰" to -4f, "干" to -3.1f)

    private fun strokes(dict: String = this.dict) = Strokes(
        CodeTable.load(ByteBuffer.wrap(Strokes.read(dict.reader().buffered(), "stroke").build().toByteArray())),
    ) { scores[it] ?: Float.NEGATIVE_INFINITY }

    @Test
    fun theCommonestWhoseStrokesStartSoComeFirstTheUnknownAfterFewestStrokesFirst() {
        val s = strokes()
        assertEquals(listOf("一", "十", "干", "王", "土", "丰", "㐄"), s.find("h"))
        assertEquals(listOf("干", "王", "丰"), s.find("hh"))
        assertEquals(listOf("㐄"), s.find("hzs"))
        assertEquals(emptyList<String>(), s.find("z"))
        assertEquals(emptyList<String>(), s.find(""))
        assertEquals("一丨丿丶乛", Strokes.shown("hspnz"))
    }

    @Test
    fun aLineThatIsNoEntryIsAnError() {
        val e = assertThrows(SourceException::class.java) {
            Strokes.read("...\n一\th\n二\tx\n".reader().buffered(), "stroke")
        }
        assertEquals(3, e.line)
    }

    /** Pinyin as far as the lookup sees it: shows what it is typed, records the rest. */
    private class Pinyin : Session {
        val actions = ArrayList<Action>()
        val typed = StringBuilder()
        override var learning = true
        override fun reads(c: Char) = c in 'a'..'z'
        override fun apply(action: Action): Snapshot {
            actions += action
            when (action) {
                is Action.Key -> typed.append(action.char)
                is Action.Select, Action.Reset, is Action.Context -> typed.setLength(0)
                else -> {}
            }
            return Snapshot("", typed.toString(), emptyList(), 0, false, false, true, false)
        }
        override fun candidates(from: Int, count: Int) = listOf(Choice("拼"))
        override fun offers(index: Int) = setOf(Offer.FORGET)
    }

    private fun lookup(pinyin: Pinyin = Pinyin(), strokes: Strokes? = strokes()) =
        StrokeLookup(pinyin, { strokes }, reading = { if (it == "王") "wang" else "" }, pageSize = 3)

    @Test
    fun uStartsALookupWhoseStrokesShowAsStrokes() {
        val l = lookup()
        assertTrue(l.reads('u'))
        assertEquals("u", l.apply(Action.Key('u')).preedit)
        l.apply(Action.Key('h'))
        val s = l.apply(Action.Key('h'))
        assertEquals("u一一", s.preedit)
        assertEquals(listOf("干", "王", "丰"), s.candidates)
        assertEquals(listOf("", "wang", ""), s.hints)
        assertEquals("wang", l.candidates(1, 1).single().hint)
        assertEquals(emptySet<Offer>(), l.offers(0))
        assertEquals("王", l.apply(Action.Select(1)).commit)
    }

    @Test
    fun aLetterNoStrokeEndsTheLookupItsKeysTypedAsPinyin() {
        val pinyin = Pinyin()
        val l = lookup(pinyin)
        l.apply(Action.Key('u'))
        l.apply(Action.Key('h'))
        l.apply(Action.Key('h'))
        // not a stroke: what was typed is pinyin's, as typed
        assertEquals("uhha", l.apply(Action.Key('a')).preedit)
        assertEquals("uhha".map { Action.Key(it) }, pinyin.actions)
        assertEquals(listOf(Choice("拼")), l.candidates(0, 1))
        assertEquals(setOf(Offer.FORGET), l.offers(0))
        // pinyin's from then on, u too
        assertEquals("uhhau", l.apply(Action.Key('u')).preedit)
    }

    @Test
    fun aLatinWordStartingWithUIsTypedAsPinyinReadsIt() {
        val data = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("USB", -5f, 0f)
                    .entry("USB", syl("U", "S", "B"))
                    .build().toByteArray(),
            ),
        )
        val l = StrokeLookup(PinyinSession(data, PinyinSegmenter(latin = LatinWords.of(data.dictionary))), { strokes() })
        l.apply(Action.Key('u'))
        // s is 丨, b no stroke
        assertEquals("u丨", l.apply(Action.Key('s')).preedit)
        val s = l.apply(Action.Key('b'))
        assertEquals("USB", s.candidates.first())
        assertEquals("usb", s.preedit)
    }

    @Test
    fun aCharacterPickedFollowsWhatThePinyinCommittedBefore() {
        val contexts = ArrayList<String>()
        val pinyin = PinyinSession(sessionTestData(), PinyinSegmenter()) { context, _, _ -> contexts += context; 0 }
        val zai = strokes("...\n再\th\n")
        val l = StrokeLookup(pinyin, { zai }, follow = pinyin::follow)
        "wo".forEach { l.apply(Action.Key(it)) }
        assertEquals("我", l.apply(Action.Select(0)).commit)
        l.apply(Action.Key('u'))
        l.apply(Action.Key('h'))
        assertEquals("再", l.apply(Action.Pick(0)).commit)
        // the reranker reads the text before the cursor: both, not 再 alone
        "zai".forEach { l.apply(Action.Key(it)) }
        assertEquals("我再", contexts.last())
    }

    @Test
    fun pickedTheCharacterIsWhatPinyinGoesOnFrom() {
        val pinyin = Pinyin()
        val l = lookup(pinyin)
        l.apply(Action.Key('u'))
        l.apply(Action.Key('h'))
        assertEquals("一", l.apply(Action.Pick(0)).commit)
        assertEquals(Action.Context("一"), pinyin.actions.last())
        // pinyin again
        assertEquals("u", l.apply(Action.Key('u')).preedit)
        l.apply(Action.Backspace)
        assertEquals("n", l.apply(Action.Key('n')).preedit)
        assertEquals(listOf(Choice("拼")), l.candidates(0, 1))
        assertEquals(setOf(Offer.FORGET), l.offers(0))
    }

    @Test
    fun uAfterPinyinTypedIsPinyinsAndWithoutStrokesAlways() {
        val pinyin = Pinyin()
        val l = lookup(pinyin)
        l.apply(Action.Key('x'))
        assertEquals("xu", l.apply(Action.Key('u')).preedit)
        val none = lookup(strokes = null)
        assertEquals("u", none.apply(Action.Key('u')).preedit)
        assertEquals("ua", none.apply(Action.Key('a')).preedit)
    }

    @Test
    fun pagesBackspaceEnterAndOtherKeys() {
        val l = lookup()
        l.apply(Action.Key('u'))
        l.apply(Action.Key('h'))
        val next = l.apply(Action.NextPage)
        assertEquals(listOf("王", "土", "丰"), next.candidates)
        assertTrue(next.hasPreviousPage)
        assertEquals(listOf("一", "十", "干"), l.apply(Action.PreviousPage).candidates)
        assertEquals(0, l.apply(Action.PreviousPage).page)
        assertEquals("u", l.apply(Action.Backspace).preedit)
        assertEquals("", l.apply(Action.Backspace).preedit)
        assertFalse(l.reads('1'))
        l.apply(Action.Key('u'))
        l.apply(Action.Key('h'))
        assertEquals("uh", l.apply(Action.CommitRaw).commit)
        l.apply(Action.Key('u'))
        l.apply(Action.Key('h'))
        val comma = l.apply(Action.Key(','))
        assertEquals("一", comma.commit)
        assertFalse(comma.handled)
        // a selection past the page, a refine: nothing
        l.apply(Action.Key('u'))
        l.apply(Action.Key('h'))
        assertEquals("u一", l.apply(Action.Select(5)).preedit)
        assertEquals("u一", l.apply(Action.Refine).preedit)
        assertEquals("", l.apply(Action.Reset).preedit)
        l.learning = false
        assertFalse(l.learning)
    }

    @Test
    fun anotherKeyWithNothingFoundCommitsWhatWasTypedAsEnterDoes() {
        val l = lookup()
        l.apply(Action.Key('u'))
        val comma = l.apply(Action.Key(','))
        assertEquals("u", comma.commit)
        assertFalse(comma.handled)
        // strokes no character has
        l.apply(Action.Key('u'))
        l.apply(Action.Key('z'))
        assertEquals(emptyList<String>(), l.apply(Action.Key('z')).candidates)
        assertEquals("uzz", l.apply(Action.Key('.')).commit)
    }
}
