/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.RecordedSession
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.fcitx.fcitx5.android.input.scene.SceneData
import org.fcitx.fcitx5.android.input.scene.SceneGroups
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A layout's own letter groups typed (060-render-p3 T9a): the sample 17 keys, a tap's place across and down, the built-in keys' codes as they were. */
class KeyboardCustomTest {
    @After
    fun noneInUse() {
        NineKeyLetters.clearInUse()
    }

    private val k17Layout = (LayoutReader.layout(SceneData.read("k17.xml")) as Parsed.Ok).value
    private val k17 = SceneGroups.letters(SceneGroups.written(k17Layout))!!.also { NineKeyLetters.inUse(it) }

    @Test
    fun theLayoutsOwnKeyboardIsItsKeyboardRowsOrNot() {
        // of() drops the rows, written() keeps them: one keyboard, no mismatch, lean kept
        assertEquals(null, SceneGroups.mismatch(SceneGroups.of(k17Layout), NineKeyLetters.custom(SceneGroups.written(k17Layout))))
        val leaning = SceneGroups.leaningOf(k17, k17Layout)
        assertTrue("pqrs" in leaning && "bn" in leaning && "abc" !in leaning)
        // comparing sets no keyboard in use
        val other = NineKeyLetters.custom(SceneGroups.written(k17Layout).reversed())
        SceneGroups.letters(SceneGroups.written(k17Layout).reversed())
        SceneGroups.mismatch(SceneGroups.written(k17Layout).reversed(), other)
        assertEquals(k17, NineKeyLetters.ofCode(k17.codes[0].code))
    }

    private fun code(group: String) = k17.codes[k17.tag().split(',').indexOf(group)]

    @Test
    fun theBuiltInFourteenKeysSendTheCharactersTheyAlwaysDid() {
        // each code and place: U+F0200 on, 17 places a code, in the codes' letters' order, whatever y
        for (letter in 'a'..'z') for (step in 0..16) {
            val c = NineKeyLetters.G14_BLOCK.first.toChar() + (letter - 'a')
            val expected = 0xF0200 + (letter - 'a') * 17 + step
            assertEquals(expected, Keyboard.tapKey(c, step / 16f))
            for (y in listOf(0f, 0.5f, 1f)) assertEquals(expected, Keyboard.tapKey(c, step / 16f, y))
        }
        // a layout with exactly their groups is them: their codes, their record
        assertEquals(NineKeyLetters.G14, SceneGroups.letters(SceneGroups.written(SceneData.read("k14.xml").let { (LayoutReader.layout(it) as Parsed.Ok).value }, frags())))
    }

    // how the grouped keyboards take a layout (060-render-p3 T9b): theirs as always; a layout's own on pinyin's alone;
    // any other refused with why, the keyboard's built-in layout drawn
    @Test
    fun aGroupedKeyboardTakesItsOwnTheLayoutsOwnOrRefuses() {
        fun layout(name: String) = (LayoutReader.layout(SceneData.read(name)) as Parsed.Ok).value
        val k14 = layout("k14.xml")
        val k18 = layout("k18.xml")
        assertEquals(SceneGroups.Taken.BuiltIn, SceneGroups.taken(k14, NineKeyLetters.G14, own = true, frags()))
        assertEquals(SceneGroups.Taken.BuiltIn, SceneGroups.taken(k14, NineKeyLetters.G14, own = false, frags()))
        assertEquals(SceneGroups.Taken.BuiltIn, SceneGroups.taken(k18, NineKeyLetters.G18, own = false, frags()))
        // the 17 keys on pinyin's: its own keyboard, the rows as written
        val own = SceneGroups.taken(k17Layout, NineKeyLetters.G14, own = true) as SceneGroups.Taken.Own
        assertEquals(k17, own.letters)
        assertTrue("pq rs" in own.written)
        assertEquals(SceneGroups.written(k17Layout), own.written)
        assertEquals(own.letters, (SceneGroups.taken(k17Layout, NineKeyLetters.G18, own = true) as SceneGroups.Taken.Own).letters)
        // on 双拼's: refused, said why
        val sp = SceneGroups.taken(k17Layout, NineKeyLetters.G14, own = false) as SceneGroups.Taken.Refused
        assertTrue(sp.why, "pinyin's alone" in sp.why)
        // the other built-in keyboard's groups in its place: refused, never its codes
        assertTrue(SceneGroups.taken(k18, NineKeyLetters.G14, own = true, frags()) is SceneGroups.Taken.Refused)
        assertTrue(SceneGroups.taken(k14, NineKeyLetters.G18, own = true, frags()) is SceneGroups.Taken.Refused)
        // groups that make no keyboard: a letter on none (k17 without its key of z), refused
        val noZ = LayoutReader.layout(SceneData.read("k17.xml").replace("letters=\"zx\"", "letters=\"x\"").replace("letters=\"xyz\"", "letters=\"xy\"")) as Parsed.Ok
        val bad = SceneGroups.taken(noZ.value, NineKeyLetters.G14, own = true) as SceneGroups.Taken.Refused
        assertTrue(bad.why, "make no keyboard" in bad.why)
    }

    // review L3: each group's key by its id, in the groups' order, two keys of the same letters each its own
    @Test
    fun theKeysOfTheGroupsAreNamedInTheirOrder() {
        assertEquals(17, SceneGroups.ids(k17Layout).size)
        assertEquals(listOf("r3c3", "r3c4", "r3c5"), SceneGroups.ids(k17Layout).takeLast(3))
        val twice = (LayoutReader.layout(SceneData.read("k17.xml").replace("letters=\"xyz\"", "letters=\"ab c\"")) as Parsed.Ok).value
        assertEquals(listOf("abc", "abc"), SceneGroups.of(twice).takeLast(2))
        assertEquals(listOf("r3c4", "r3c5"), SceneGroups.ids(twice).takeLast(2))
        // the grouped bottom row's keys are of no group
        val k14 = (LayoutReader.layout(SceneData.read("k14.xml")) as Parsed.Ok).value
        assertEquals(SceneGroups.of(k14, frags()).size, SceneGroups.ids(k14, frags()).size)
    }

    private fun frags() = mapOf("grouped-bottom" to (LayoutReader.fragment(SceneData.read("grouped-bottom.xml")) as Parsed.Ok).value)

    @Test
    fun aTapAcrossAndDownComesBackAsItsCodeAndPlace() {
        for (c in k17.codes) for (xs in 0..16) for (ys in 0..8) {
            val sent = Keyboard.tapKey(c, xs / 16f, ys / 8f)
            assertTrue(sent in Keyboard.PLACES)
            assertTrue(InputMethodCatalog.isCode(sent))
            assertTrue(InputMethodCatalog.readsCode(InputMethodCatalog.PINYIN, sent))
            val (back, place) = Keyboard.untapped(sent)
            assertEquals(c, back)
            assertEquals(c, Keyboard.tappedCode(sent))
            assertEquals(xs / 16f, NineKeyLetters.placeX(place), 0f)
            assertEquals(ys / 8f, NineKeyLetters.placeY(place), 0f)
        }
        // one dimensional: the middle row
        assertEquals(Keyboard.tapKey(code("pqrs"), 0.25f, 0.5f), Keyboard.tapKey(code("pqrs"), 0.25f))
        // the session gets the code and the place as one
        val session = RecordedSession(PinyinSession(sessionTestData(), T9Segmenter(abbreviations = false, letters = k17), spell = true, prediction = false))
        Keyboard(session, nineKeys = false).onEvent(EngineEvent.CHAR, Keyboard.tapKey(code("pqrs"), 1f, 1f))
        assertEquals(Action.Key(code("pqrs"), at = NineKeyLetters.place(1f, 1f)), session.actions.single())
    }

    @Test
    fun aTemporaryModeGetsTheLetterTappedOn() {
        val pqrs = code("pqrs")
        assertEquals('p', Keyboard.typedLetter(Keyboard.tapKey(pqrs, 0f, 0f)))
        assertEquals('q', Keyboard.typedLetter(Keyboard.tapKey(pqrs, 1f, 0f)))
        assertEquals('r', Keyboard.typedLetter(Keyboard.tapKey(pqrs, 0.1f, 1f)))
        assertEquals('s', Keyboard.typedLetter(Keyboard.tapKey(pqrs, 0.9f, 0.9f)))
        assertEquals('c', Keyboard.typedLetter(Keyboard.tapKey(code("abc"), 1f, 0.5f)))
        // the code alone: its first letter
        assertEquals('x', Keyboard.typedLetter(code("xyz").code))
    }

    @Test
    fun aKeyWhoseLeanIsOffSendsItsCodeAlone() {
        val leaning = SceneGroups.leaning(k17Layout)
        assertFalse("abc" in leaning)
        assertTrue("pqrs" in leaning && "bn" in leaning)
        // one letter: nothing to lean to
        assertFalse("m" in leaning)
    }

    @Test
    fun theSeventeenKeysSegmentAndOfferWhatIsTyped() {
        val session = PinyinSession(sessionTestData(), T9Segmenter(abbreviations = false, letters = k17), spell = true, prediction = false)
        val keyboard = Keyboard(session, nineKeys = false)
        // ni hao: BN UI GH AS OP, n and i on their keys' right, h right, a left, o left
        var last: Snapshot? = null
        for ((group, x) in listOf("bn" to 0.9f, "ui" to 0.9f, "gh" to 0.9f, "as" to 0.1f, "op" to 0.1f)) {
            last = keyboard.onEvent(EngineEvent.CHAR, Keyboard.tapKey(code(group), x, 0.5f))
        }
        assertNotNull(last)
        assertEquals("你好", last!!.candidates.first())
        // p typed on the two row key reads as p too
        val again = PinyinSession(sessionTestData(), T9Segmenter(abbreviations = false, letters = k17), spell = true, prediction = false)
        assertTrue(k17.keysOf('p').length == 2)
        assertTrue(Keyboard(again, nineKeys = false).onEvent(EngineEvent.CHAR, Keyboard.tapKey(code("pqrs"), 0f, 0f)).handled)
        assertEquals('p', Keyboard.typedLetter(Keyboard.tapKey(code("pqrs"), 0f, 0f)))
    }
}
