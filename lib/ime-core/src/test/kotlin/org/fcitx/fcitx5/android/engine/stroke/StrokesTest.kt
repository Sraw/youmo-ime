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
import org.fcitx.fcitx5.android.engine.session.HandedBack
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.fcitx.fcitx5.android.engine.data.syl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class StrokesTest {

    // 㐄 and 亖 are rare but first by code; 𠀀, past the BMP, is not kept
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
        亖	hhhh
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
        val h = s.find("h")
        val all = listOf("一", "十", "干", "王", "土", "丰", "㐄", "亖")
        assertEquals(all, h.toList())
        assertEquals(all, h.toList())
        assertEquals(listOf("干", "王", "丰", "亖"), s.find("hh").toList())
        assertEquals(listOf("㐄"), s.find("hzs").toList())
        assertEquals(emptyList<String>(), s.find("z").toList())
        assertEquals(emptyList<String>(), s.find("").toList())
        assertEquals("一丨丿丶乛", Strokes.shown("hspnz"))
    }

    @Test
    fun aCharacterIsWrittenInAnyOfItsOrders() {
        val s = strokes(dict + "\n王\thhsz\n")
        assertEquals(listOf("hhsh", "hhsz"), s.ordersOf('王').sorted())
        assertTrue(s.startsWith('王', "hhs"))
        assertTrue(s.startsWith('王', "hhsz"))
        assertTrue(s.startsWith('王', "hhsh"))
        assertFalse(s.startsWith('王', "hs"))
        // no longer than its order, nothing drawn, a character the table has not
        assertFalse(s.startsWith('一', "hh"))
        assertFalse(s.startsWith('一', ""))
        assertFalse(s.startsWith('人', "p"))
        assertEquals(emptyList<String>(), s.ordersOf('人'))
        assertEquals(listOf("hsh"), s.ordersOf('土'))
    }

    @Test
    fun onlyTheCharactersOfTheBmpAreKept() {
        val read = Strokes.read("...\n㐄\thzs\n𠀀\thzs\n".reader().buffered(), "stroke")
        assertEquals(listOf("hzs" to "㐄"), read.entryList)
    }

    @Test
    fun aLineThatIsNoEntryIsAnError() {
        val e = assertThrows(SourceException::class.java) {
            Strokes.read("...\n一\th\n二\tx\n".reader().buffered(), "stroke")
        }
        assertEquals(3, e.line)
    }

    /** Pinyin as far as the lookup sees it: shows what it is typed, keys going in at a cursor an arrow moves; records the rest. */
    private class Pinyin : Session {
        val actions = ArrayList<Action>()
        val typed = StringBuilder()
        var cursor = 0
        // keys it reads but not where it stands (双拼's ; where no syllable starts)
        var refuses = emptySet<Char>()
        override var learning = true
        override fun reads(c: Char) = c in 'a'..'z'
        override fun endsInput(c: Char) = !reads(c) || c in refuses
        override fun apply(action: Action): Snapshot {
            actions += action
            when (action) {
                is Action.Key -> typed.insert(cursor++, action.char)
                is Action.Step -> cursor = action.applyTo(cursor, typed.length)
                is Action.Select, Action.Reset, is Action.Context -> {
                    typed.setLength(0)
                    cursor = 0
                }
                else -> {}
            }
            return Snapshot(
                "", typed.toString(), emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false,
                cursor = if (cursor < typed.length) cursor else -1,
            )
        }
        override fun candidates(from: Int, count: Int) = listOf(Choice("拼"))
        override fun offers(index: Int) = setOf(Offer.FORGET)
    }

    private fun lookup(pinyin: Pinyin = Pinyin(), strokes: Strokes? = strokes()) =
        StrokeLookup(pinyin, { strokes }, reading = { if (it == "王") "wang" else "" }, pageSize = 3)

    // pinyin's answer passes through; u while idle starts a lookup whatever pinyin says
    @Test
    fun whetherAKeyEndsTheInputIsPinyinsSaveUWhileIdle() {
        val l = lookup(Pinyin().apply { refuses = setOf('a', 'u') })
        assertTrue(l.reads('a'))
        assertTrue(l.endsInput('a'))
        assertFalse(l.endsInput('b'))
        assertTrue(l.endsInput(','))
        assertFalse(l.endsInput('u'))
    }

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
        assertEquals("uhha", l.apply(Action.Key('a')).preedit)
        assertEquals("uhha".map { Action.Key(it) }, pinyin.actions)
        assertEquals(listOf(Choice("拼")), l.candidates(0, 1))
        assertEquals(setOf(Offer.FORGET), l.offers(0))
        assertEquals("uhhau", l.apply(Action.Key('u')).preedit)
    }

    @Test
    fun theKeysOfALookupALetterEndedAreHandedOverAtOnce() {
        val pinyin = Pinyin()
        val handed = ArrayList<String>()
        val l = StrokeLookup(pinyin, { strokes() }, retype = { keys ->
            handed += keys
            Snapshot("", keys, emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false)
        })
        "uhh".forEach { l.apply(Action.Key(it)) }
        assertEquals("uhha", l.apply(Action.Key('a')).preedit)
        assertEquals(listOf("uhha"), handed)
        l.apply(Action.Key('u'))
        assertEquals(listOf(Action.Key('u')), pinyin.actions)
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
        // typed into the pinyin a key at a time, and handed over at once, as Engines has it
        for (handedOver in listOf(false, true)) {
            val pinyin = PinyinSession(data, PinyinSegmenter(latin = LatinWords.of(data.dictionary)))
            val l = if (handedOver) StrokeLookup(pinyin, { strokes() }, retype = pinyin::typeAll) else StrokeLookup(pinyin, { strokes() })
            l.apply(Action.Key('u'))
            assertEquals("u丨", l.apply(Action.Key('s')).preedit)
            val s = l.apply(Action.Key('b'))
            assertEquals("$handedOver", "USB", s.candidates.first())
            assertEquals("$handedOver", "usb", s.preedit)
        }
    }

    @Test
    fun theFieldsFlagsReachThePinyinWhileLookingUpOrNot() {
        val passed = ArrayList<Int>()
        val real = PinyinSession(sessionTestData(), PinyinSegmenter())
        val pinyin = object : Session by real {
            override fun fieldFlags(flags: Int) {
                passed += flags
            }
        }
        val l = StrokeLookup(pinyin, { strokes("...\n再\th\n") })
        l.fieldFlags(1)
        l.apply(Action.Key('u'))
        // looking up: the strokes' own, not the pinyin's to reread or tell why
        l.fieldFlags(2)
        assertEquals(null, l.reread())
        assertFalse(l.rereads)
        assertEquals(listOf(1, 2), passed)
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
    fun aCharacterCommittedByAnotherKeyOrWhatEnterCommitsIsNoContext() {
        val contexts = ArrayList<String>()
        val pinyin = PinyinSession(sessionTestData(), PinyinSegmenter()) { context, _, _ -> contexts += context; 0 }
        val zai = strokes("...\n再\th\n")
        val l = StrokeLookup(pinyin, { zai }, follow = pinyin::follow)
        "wo".forEach { l.apply(Action.Key(it)) }
        assertEquals("我", l.apply(Action.Select(0)).commit)
        "uh".forEach { l.apply(Action.Key(it)) }
        assertEquals("再", l.apply(Action.Key(',')).commit)
        // 我再，: what follows does not run on from it, as after pinyin's own 我，
        "zai".forEach { l.apply(Action.Key(it)) }
        assertEquals("", contexts.last())
        l.apply(Action.Reset)
        "wo".forEach { l.apply(Action.Key(it)) }
        l.apply(Action.Select(0))
        "uh".forEach { l.apply(Action.Key(it)) }
        assertEquals("uh", l.apply(Action.CommitRaw).commit)
        "zai".forEach { l.apply(Action.Key(it)) }
        assertEquals("", contexts.last())
    }

    @Test
    fun aKeyPassedOnReachesThePinyinAndEndsALookup() {
        var passed = 0
        val real = Pinyin()
        val pinyin = object : Session by real {
            override fun passedOn() {
                passed++
            }
        }
        val l = StrokeLookup(pinyin, { strokes() })
        l.passedOn()
        assertEquals(1, passed)
        "uh".forEach { l.apply(Action.Key(it)) }
        l.passedOn()
        assertEquals(2, passed)
        assertEquals(emptyList<Action>(), real.actions)
        // over: u starts another, not a key ending the one that was on (uhu typed into the pinyin)
        assertEquals("u", l.apply(Action.Key('u')).preedit)
        assertEquals("u一", l.apply(Action.Key('h')).preedit)
    }

    @Test
    fun handedBackALookupIsItsKeysAndThePinyinIsAsThePinyinHandsBack() {
        val pinyin = PinyinSession(sessionTestData(), PinyinSegmenter())
        val l = StrokeLookup(pinyin, { strokes("...\n再\th\n") }, follow = pinyin::follow)
        "uh".forEach { l.apply(Action.Key(it)) }
        assertEquals(HandedBack("", null, "uh"), l.handBack())
        "nihao".forEach { l.apply(Action.Key(it)) }
        l.apply(Action.Pick(l.candidates(0, 20).indexOfFirst { it.text == "你" }))
        val back = l.handBack()
        assertEquals("你" to "hao", back.picked to back.keys)
        "uh".forEach { l.apply(Action.Key(it)) }
        assertEquals(listOf(Choice("再")), l.candidates(0, 1))
    }

    @Test
    fun pickedTheCharacterIsWhatPinyinGoesOnFrom() {
        val pinyin = Pinyin()
        val l = lookup(pinyin)
        l.apply(Action.Key('u'))
        l.apply(Action.Key('h'))
        assertEquals("一", l.apply(Action.Pick(0)).commit)
        assertEquals(Action.Context("一"), pinyin.actions.last())
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
        val pinyin = Pinyin()
        val l = lookup(pinyin)
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
        l.apply(Action.Key('u'))
        l.apply(Action.Key('h'))
        assertEquals("u一", l.apply(Action.Select(5)).preedit)
        assertEquals("u一", l.apply(Action.Refine).preedit)
        assertEquals("", l.apply(Action.Reset).preedit)
        l.learning = false
        assertFalse(pinyin.learning)
    }

    @Test
    fun theStrokesKeepACursorAsPinyinDoes() {
        val pinyin = Pinyin()
        val l = lookup(pinyin)
        l.apply(Action.Key('u'))
        l.apply(Action.Key('h'))
        assertEquals("u一丨", l.apply(Action.Key('s')).preedit)
        val tapped = l.apply(Action.Cursor(1))
        assertTrue(tapped.handled)
        assertEquals(1, tapped.cursor)
        assertTrue("the pinyin is not told", pinyin.actions.none { it is Action.Cursor })
        assertEquals("u一丨", l.apply(Action.Backspace).preedit)
        val mended = l.apply(Action.Key('h'))
        assertEquals("u一一丨", mended.preedit)
        assertEquals(2, mended.cursor)
        assertEquals(-1, l.apply(Action.Step.END).cursor)
        assertEquals("u一一", l.apply(Action.Backspace).preedit)
        // a letter ends the lookup where the cursor is: u, then the a, then the strokes
        l.apply(Action.Step.START)
        val a = l.apply(Action.Key('a'))
        assertEquals("uahh", pinyin.typed.toString())
        // and the pinyin's cursor after the a: the next key goes there
        assertEquals(2, a.cursor)
        l.apply(Action.Key('n'))
        assertEquals("uanhh", pinyin.typed.toString())
    }

    @Test
    fun anArrowMovesTheCursorAStrokeAtATimeAndBackspaceThereDeletesTheStrokeBeforeIt() {
        val l = lookup()
        "uhsp".forEach { l.apply(Action.Key(it)) }
        assertEquals(3, l.apply(Action.Step(-1)).cursor)
        val deleted = l.apply(Action.Backspace)
        assertEquals("u一丿", deleted.preedit)
        assertEquals(2, deleted.cursor)
        assertEquals(-1, l.apply(Action.Step(1)).cursor)
    }

    @Test
    fun aLetterEndingTheLookupBeforeStrokesLeavesThePinyinSessionsCursorAfterIt() {
        val data = sessionTestData()
        // typed into the pinyin a key at a time, and handed over at once, as Engines has it
        for (handedOver in listOf(false, true)) {
            val pinyin = PinyinSession(data, PinyinSegmenter())
            val l = if (handedOver) StrokeLookup(pinyin, { strokes() }, retype = pinyin::typeAll) else StrokeLookup(pinyin, { strokes() })
            "uhh".forEach { l.apply(Action.Key(it)) }
            l.apply(Action.Step.START)
            l.apply(Action.Key('a'))
            val fixed = l.apply(Action.Key('n'))
            val typed = PinyinSession(data, PinyinSegmenter())
            "uanhh".forEach { typed.apply(Action.Key(it)) }
            val expected = typed.apply(Action.Step(-2))
            assertEquals("$handedOver", expected.preedit, fixed.preedit)
            assertEquals("$handedOver", expected.cursor, fixed.cursor)
        }
    }

    @Test
    fun theCharactersFoundAreMadeAsAskedForTheTotalKnownOnceAllAre() {
        val l = lookup()
        l.apply(Action.Key('u'))
        val first = l.apply(Action.Key('h'))
        assertEquals(listOf("一", "十", "干"), first.candidates)
        assertTrue(first.hasNextPage)
        assertEquals(-1, first.total)
        l.apply(Action.NextPage)
        val last = l.apply(Action.NextPage)
        assertEquals(listOf("㐄", "亖"), last.candidates)
        assertFalse(last.hasNextPage)
        assertEquals(8, last.total)
        assertEquals(2, l.apply(Action.NextPage).page)
        assertEquals(listOf(Choice("十"), Choice("干")), l.candidates(1, 2))
        assertEquals(8, l.candidates(0, 20).size)
    }

    @Test
    fun anotherKeyWithNothingFoundCommitsWhatWasTypedAsEnterDoes() {
        val l = lookup()
        l.apply(Action.Key('u'))
        val comma = l.apply(Action.Key(','))
        assertEquals("u", comma.commit)
        assertFalse(comma.handled)
        l.apply(Action.Key('u'))
        l.apply(Action.Key('z'))
        assertEquals(emptyList<String>(), l.apply(Action.Key('z')).candidates)
        assertEquals("uzz", l.apply(Action.Key('.')).commit)
    }
}
