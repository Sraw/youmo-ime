/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.english.GlideDecoderTest.Companion.glided
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

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
    fun aGlideInAFieldNotLearnedHasASpaceBeforeAndABackspaceTakesItToo() {
        val s = EnglishSession(EnglishDecoderTest.data())
        s.learning = false
        // the host tells it nothing of the field there (FieldPolicy.context): what the session put in itself
        s.apply(Action.Context(""))
        s.apply(Action.Key('.'))
        assertEquals(" the ", s.glided("the").commit)
        // the space the glide put before its word goes with it
        val gone = s.apply(Action.Backspace)
        assertEquals(" the ".length, gone.deleteBefore)
        // and the word tried again has its space again
        assertEquals(" the ", s.glided("the").commit)
        val t = EnglishSession(EnglishDecoderTest.data())
        t.apply(Action.Context("say"))
        val shown = t.glided("helo")
        // another in its place: the space before it stays, and goes with that one
        assertEquals("hello ".length, t.apply(Action.Pick(shown.candidates.indexOf("help"))).deleteBefore)
        assertEquals(" help ".length, t.apply(Action.Backspace).deleteBefore)
    }

    /** The session's own Backspace, nothing typed, in a field not learned from: one char off the edge, as the app's (Session.ERASED), not all of it. */
    @Test
    fun aBackspaceAfterAWordCommittedInAFieldNotLearnedKeepsTheCharBefore() {
        val s = EnglishSession(EnglishDecoderTest.data())
        s.learning = false
        s.apply(Action.Context(""))
        "cat".forEach { s.apply(Action.Key(it)) }
        assertEquals("cat ", s.apply(Action.Key(' ')).commit)
        assertFalse(s.apply(Action.Backspace).handled)
        assertEquals(" hello ", s.glided("hello").commit)
    }

    @Test
    fun aGlideNoWordFitsLeavesTheWordTyped() {
        val s = session()
        s.apply(Action.Key('h'))
        s.apply(Action.Key('e'))
        s.offerGlide(Glide(floatArrayOf(0.5f, 0.7f, 0.6f, 0.7f, 0.5f, 3.5f), GlideDecoderTest.glide("the").layout))
        val shown = s.apply(Action.Glide)
        assertEquals("", shown.commit)
        assertEquals("he", shown.preedit)
    }

    @Test
    fun aGlideCountsItsWordAndTakingItBackUncountsIt() {
        val user = EnglishUser()
        val s = EnglishSession(EnglishDecoderTest.data(), user = user).apply { apply(Action.Context("")) }
        val shown = s.glided("helo")
        assertEquals(1f, user.count("hello"))
        s.apply(Action.Pick(shown.candidates.indexOf("help")))
        assertEquals(0f, user.count("hello"))
        assertEquals(1f, user.count("help"))
        s.apply(Action.Backspace)
        assertEquals(0f, user.count("help"))
        // not learning: nothing counted
        val off = EnglishSession(EnglishDecoderTest.data(), user = user).apply { learning = false }
        off.apply(Action.Context(""))
        assertEquals("hello ", off.glided("helo").commit)
        assertEquals(0f, user.count("hello"))
    }

    @Test
    fun aWordTheUserAddedIsGlidedToo() {
        val user = EnglishUser()
        user.learn(null, "kotlin")
        val s = EnglishSession(EnglishDecoderTest.data(), user = user).apply { apply(Action.Context("")) }
        assertTrue("kotlin" in s.glided("kotlin").candidates)
        // alone on its path it goes in, with the shift a capital
        val shifted = EnglishSession(EnglishDecoderTest.data(), user = user).apply { apply(Action.Context("")) }
        assertEquals("Kotlin ", shifted.glided("kotlin", shift = true).commit)
    }

    // the user's own word on a path a data word fits as well: ahead of a rarer one, behind a far commoner
    @Test
    fun aWordTheUserAddedRanksAmongTheDataWords() {
        val user = EnglishUser()
        user.add("helo")
        user.add("thee")
        val s = EnglishSession(EnglishDecoderTest.data(), user = user).apply { apply(Action.Context("")) }
        val rare = s.glided("helo").candidates
        assertTrue(rare.indexOf("helo") in 0 until rare.indexOf("hello"))
        val t = EnglishSession(EnglishDecoderTest.data(), user = user).apply { apply(Action.Context("")) }
        val common = t.glided("the").candidates
        assertTrue(common.indexOf("the") in 0 until common.indexOf("thee"))
    }

    @Test
    fun aCoarseWordIsNeverGlided() {
        val s = EnglishSession(EnglishDecoderTest.data(), coarseWords = listOf("hello")).apply { apply(Action.Context("")) }
        val shown = s.glided("helo")
        assertFalse(shown.commit == "hello ")
        assertFalse("hello" in shown.candidates)
    }

    @Test
    fun aGlidedWordOnTheSecondPageIsPickedByItsNumber() {
        val s = EnglishSession(EnglishDecoderTest.data(), pageSize = 1).apply { apply(Action.Context("")) }
        s.glided("helo")
        val next = s.apply(Action.NextPage)
        val picked = s.apply(Action.Select(0))
        assertEquals("${next.candidates.single()} ", picked.commit)
        assertEquals("hello ".length, picked.deleteBefore)
    }

    @Test
    fun capsLockGlidesInCapitals() {
        val s = session()
        s.offerGlide(Glide(GlideDecoderTest.path("helo"), GlideLayout.QWERTY, capsLock = true))
        assertEquals("HELLO ", s.apply(Action.Glide).commit)
    }

    @Test
    fun noGlideOfferedNothingCommitted() {
        assertEquals("", session().apply(Action.Glide).commit)
        assertEquals("", EnglishSession(null).glided("the").commit)
    }

    /**
     * A glide gets a capital only where Shift gives one, as a word tapped: the data's sentence-start
     * "Hello" is "hello" wherever the cursor is (a field that asks for capitals has Shift on there).
     */
    @Test
    fun aGlideHasACapitalOnlyWhereShiftGivesOne() {
        val b = EnglishDataBuilder().unigram("<unk>", -7f, 0f).unigram(EnglishText.START, -9f, -0.5f)
        // as the web's text has it: Hello at a sentence's start more often than hello inside one
        b.unigram("Hello", -2.5f, 0f).unigram("hello", -3.0f, 0f).unigram("help", -3.4f, 0f)
        val s = EnglishSession(EnglishData.load(ByteBuffer.wrap(b.build().toByteArray())))
        s.learning = false
        s.apply(Action.Context("c"))
        assertEquals(" hello ", s.glided("hello").commit)
        // a sentence ended before it: still Shift's to give
        s.apply(Action.Context("."))
        assertEquals(" hello ", s.glided("hello").commit)
        // Shift's capital still the user's
        s.apply(Action.Context("c"))
        assertEquals(" Hello ", s.glided("hello", shift = true).commit)
        // the spaces after the char looked past: a full stop's, then a letter's
        s.apply(Action.Context(". "))
        assertEquals("hello ", s.glided("hello").commit)
        s.apply(Action.Context("c "))
        assertEquals("hello ", s.glided("hello").commit)
        // more spaces than the context holds: the char before them still kept
        s.apply(Action.Context("c" + " ".repeat(EnglishText.CONTEXT_CHARS + 40)))
        assertEquals("hello ", s.glided("hello").commit)
        // an ellipsis and a line break end a sentence as a full stop does
        s.apply(Action.Context("Wait… "))
        assertEquals("hello ", s.glided("hello").commit)
        s.apply(Action.Context("Hi\n"))
        assertEquals("hello ", s.glided("hello").commit)
        s.apply(Action.Context("Hi \r\n "))
        assertEquals("hello ", s.glided("hello").commit)
        // a no-break space after a full stop is a space too; an abbreviation's stop ends no sentence
        s.apply(Action.Context("Hi.\u00A0"))
        assertEquals("hello ", s.glided("hello").commit)
        s.apply(Action.Context("Mr. "))
        assertEquals("hello ", s.glided("hello").commit)
        // the field's capital at a sentence's start comes as Shift
        s.apply(Action.Context(". "))
        assertEquals("Hello ", s.glided("hello", shift = true).commit)
    }

    /** A word whose own form has a capital (a name the data has only so, iPhone) keeps it, wherever glided. */
    @Test
    fun aGlidedNameKeepsItsOwnCapital() {
        val b = EnglishDataBuilder().unigram("<unk>", -7f, 0f).unigram(EnglishText.START, -9f, -0.5f)
        b.unigram("London", -2.5f, 0f).unigram("iPhone", -2.5f, 0f).unigram("help", -3.4f, 0f)
        val s = EnglishSession(EnglishData.load(ByteBuffer.wrap(b.build().toByteArray())))
        s.learning = false
        s.apply(Action.Context(""))
        assertEquals("London ", s.glided("london").commit)
        s.apply(Action.Context("abc "))
        assertEquals("London ", s.glided("london").commit)
        assertEquals("iPhone ", s.glided("iphone").commit)
    }

    /** A glided i and i'm are I and I'm, as tapped ones commit, though the data has them lower-case too. */
    @Test
    fun aGlidedIIsACapitalAsTapped() {
        val b = EnglishDataBuilder().unigram("<unk>", -7f, 0f).unigram(EnglishText.START, -9f, -0.5f)
        b.unigram("i", -1.5f, 0f).unigram("I", -2.0f, 0f).unigram("i'm", -2.5f, 0f).unigram("I'm", -3.0f, 0f).unigram("in", -3.5f, 0f)
        val s = EnglishSession(EnglishData.load(ByteBuffer.wrap(b.build().toByteArray())))
        s.learning = false
        s.apply(Action.Context("so "))
        val shown = s.glided("im")
        assertEquals("I'm ", shown.commit)
        assertFalse(shown.candidates.any { it == "i'm" || it == "i" })
        // only i and i'...: in, mid-sentence, stays lower-case (F2472)
        val inWord = s.glided("in")
        assertTrue("in" in inWord.candidates + inWord.commit.orEmpty().trim())
        assertFalse(inWord.candidates.any { it == "In" })
        assertFalse("committed ${inWord.commit}", inWord.commit == "In ")
    }

    /**
     * The app's Backspace keeps what is known where the code point taken and the char left are
     * plain; where either may belong to a cluster an editor takes otherwise, nothing is known.
     * Either way a glide after it has no space before (a space is left) and no capital Shift did
     * not give: what this holds is that no cut cluster makes the session space or case otherwise.
     */
    @Test
    fun aGlideAfterTheAppsBackspaceIsSpacedAndCasedAlike() {
        val b = EnglishDataBuilder().unigram("<unk>", -7f, 0f).unigram(EnglishText.START, -9f, -0.5f)
        b.unigram("Hello", -2.5f, 0f).unigram("hello", -3.0f, 0f).unigram("help", -3.4f, 0f)
        val data = EnglishData.load(ByteBuffer.wrap(b.build().toByteArray()))
        fun glidedAfter(before: String): String {
            val s = EnglishSession(data)
            s.apply(Action.Context(before))
            s.typed(Session.ERASED)
            return s.glided("hello").commit
        }
        // plain chars, an accent earlier in the text: inside the sentence still
        assertEquals("hello ", glidedAfter("ab c d"))
        assertEquals("hello ", glidedAfter("ab ce\u0301 d"))
        // an accent taken, a variation selector left, an emoji taken, a flag left, a ZWJ left:
        // nothing known, still no capital Shift did not give
        assertEquals("hello ", glidedAfter("ab c e\u0301"))
        assertEquals("hello ", glidedAfter("ab c \u2764\uFE0Fd"))
        assertEquals("hello ", glidedAfter("ab c \uD83D\uDC4B"))
        assertEquals("hello ", glidedAfter("ab c \uD83C\uDDEF\uD83C\uDDF5d"))
        assertEquals("hello ", glidedAfter("ab \uD83D\uDC68\u200D\uD83D\uDC69"))
    }

    /** What the app typed itself (a key where English suggests nothing) is the end of the text before the cursor, to space and case a glide after it by. */
    @Test
    fun aGlideAfterWhatTheAppTypedSpacesAndCasesByIt() {
        val b = EnglishDataBuilder().unigram("<unk>", -7f, 0f).unigram(EnglishText.START, -9f, -0.5f)
        b.unigram("Hello", -2.5f, 0f).unigram("hello", -3.0f, 0f).unigram("help", -3.4f, 0f)
        val data = EnglishData.load(ByteBuffer.wrap(b.build().toByteArray()))
        var s = EnglishSession(data)
        // nothing known before: a word's tail perhaps, so only the char counts
        s.typed("c")
        assertEquals(" hello ", s.glided("hello").commit)
        // anew: the hello just glided is learned
        s = EnglishSession(data)
        s.typed(".")
        s.typed(" ")
        assertEquals("hello ", s.glided("hello").commit)
        // a field not learned from: the same, from the edge alone (anew, the Hello above learned)
        s = EnglishSession(data)
        s.learning = false
        s.typed("c")
        s.typed(" ")
        assertEquals("hello ", s.glided("hello").commit)
    }

    /** With text known before the cursor (a field learned from, told it), what the app typed goes after it: spaces and cases a glide, and leaves no glided word to take back. */
    @Test
    fun whatTheAppTypedAfterTextKnownSpacesAndCasesAGlide() {
        val b = EnglishDataBuilder().unigram("<unk>", -7f, 0f).unigram(EnglishText.START, -9f, -0.5f)
        b.unigram("Hello", -2.5f, 0f).unigram("hello", -3.0f, 0f).unigram("help", -3.4f, 0f)
        val data = EnglishData.load(ByteBuffer.wrap(b.build().toByteArray()))
        var s = EnglishSession(data)
        s.apply(Action.Context("Hi"))
        s.typed(".")
        s.typed(" ")
        assertEquals("hello ", s.glided("hello").commit)
        // anew, nothing learned above
        s = EnglishSession(data)
        s.apply(Action.Context("abc"))
        s.typed(" ")
        assertEquals("hello ", s.glided("hello").commit)
        // a char typed after the glide: Backspace is the app's, not the word taken back
        s.typed(",")
        val back = s.apply(Action.Backspace)
        assertFalse(back.handled)
        assertEquals(0, back.deleteBefore)
    }
}
