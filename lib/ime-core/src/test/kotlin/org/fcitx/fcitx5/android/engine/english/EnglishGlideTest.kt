/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.english.GlideDecoderTest.Companion.glided
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishGlideTest {

    private fun session() = EnglishSession(EnglishDecoderTest.data()).apply { apply(Action.Context("")) }

    @Test
    fun aGlideCommitsItsWordWithASpaceAndOffersTheOthers() {
        val s = session()
        val shown = s.glided("helo")
        assertEquals("hello ", shown.commit)
        assertEquals("hello", shown.candidates.first())
        assertEquals("", shown.preedit)
        // the shift on: a capital
        assertEquals("Hello ", session().glided("helo", shift = true).commit)
    }

    @Test
    fun anotherOfferedTakesItsPlaceAndABackspaceTakesTheWord() {
        val s = session()
        val shown = s.glided("helo")
        val other = shown.candidates.indexOf("help")
        val picked = s.apply(Action.Pick(other))
        assertEquals("help ", picked.commit)
        assertEquals("hello ".length, picked.deleteBefore)
        val gone = s.apply(Action.Backspace)
        assertEquals("", gone.commit)
        assertEquals("help ".length, gone.deleteBefore)
        // that done, a backspace is the app's
        assertEquals(false, s.apply(Action.Backspace).handled)
    }

    @Test
    fun theGlidesSpaceIsThereAlreadyAndPunctuationTakesItsPlace() {
        val s = session()
        s.glided("helo")
        // not a full stop, as space twice after a word typed
        assertEquals("", s.apply(Action.Key(' ')).commit)
        val t = session()
        t.glided("helo")
        val comma = t.apply(Action.Key(','))
        assertEquals(", ", comma.commit)
        assertEquals(1, comma.deleteBefore)
    }

    @Test
    fun aWordTypedIsEndedFirstAndGlidesFollowEachOther() {
        val s = session()
        s.apply(Action.Key('h'))
        s.apply(Action.Key('e'))
        assertEquals("he the ", s.glided("the").commit)
        assertEquals("from ", s.glided("from").commit)
    }

    @Test
    fun aGlideRightAfterTextNotTheSessionsHasASpaceBefore() {
        val s = EnglishSession(EnglishDecoderTest.data())
        s.apply(Action.Context("say"))
        assertEquals(" the ", s.glided("the").commit)
    }

    @Test
    fun afterTheSwallowedSpaceTheGlidesOthersAreGone() {
        val s = session()
        val shown = s.glided("helo")
        val after = s.apply(Action.Key(' '))
        // what may come next, if anything: not the glide's others, whose pick would replace nothing
        assertEquals(false, after.candidates.containsAll(shown.candidates))
    }

    @Test
    fun aCorrectionBeforeAGlideIsNotTakenBackAfterIt() {
        val s = session()
        "teh".forEach { s.apply(Action.Key(it)) }
        s.apply(Action.Key(' '))
        s.glided("the")
        s.apply(Action.Key(' '))
        // the glided word and its space are the app's to delete now, not teh to bring back
        val back = s.apply(Action.Backspace)
        assertEquals("", back.preedit)
        assertEquals(false, back.handled)
    }

    @Test
    fun aGlideAfterPunctuationNotTheSessionsHasASpaceBeforeAndNoneIfNoWord() {
        val s = EnglishSession(EnglishDecoderTest.data())
        s.apply(Action.Context("Hi,"))
        assertEquals(" the ", s.glided("the").commit)
        val t = EnglishSession(EnglishDecoderTest.data())
        t.apply(Action.Context("say"))
        // a path no word fits: nothing, not a lone space
        t.offerGlide(Glide(floatArrayOf(0.5f, 0.7f, 0.6f, 0.7f, 0.5f, 3.5f), GlideDecoderTest.glide("the").layout))
        assertEquals("", t.apply(Action.Glide).commit)
    }

    @Test
    fun noGlideOfferedNothingCommitted() {
        assertEquals("", session().apply(Action.Glide).commit)
        assertEquals("", EnglishSession(null).glided("the").commit)
    }
}
