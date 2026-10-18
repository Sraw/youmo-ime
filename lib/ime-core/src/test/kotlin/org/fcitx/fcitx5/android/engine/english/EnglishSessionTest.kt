/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class EnglishSessionTest {

    private val data = EnglishDecoderTest.data()

    private fun session() = EnglishSession(data)

    private fun EnglishSession.type(text: String): Snapshot = text.map { apply(Action.Key(it)) }.last()

    private fun EnglishSession.commit(word: String): String {
        type(word)
        return apply(Action.Key(' ')).commit
    }

    @Test
    fun theWordTypedComesFirstThenWhatItMayBe() {
        val s = session().type("hel")
        assertEquals("hel", s.preedit)
        // the longer words it starts, likeliest first, then those a slip from it
        assertEquals(listOf("hel", "hello", "help", "hell", "he"), s.candidates)
        assertFalse(s.predicting)
    }

    @Test
    fun aWordTypedThatIsNoneIsCorrectedBySpace() {
        val teh = session()
        assertEquals(listOf("the", "teh"), teh.type("teh").candidates.take(2))
        assertEquals("the ", teh.apply(Action.Key(' ')).commit)
        // written otherwise: an apostrophe, a capital
        for ((typed, meant) in listOf("dont" to "don't", "london" to "London", "im" to "I'm")) {
            val session = session()
            session.type(typed)
            assertEquals("$meant ", session.apply(Action.Key(' ')).commit)
        }
        // punctuation commits the correction too, and a space after itself
        val comma = session().also { it.type("teh") }.apply(Action.Key(','))
        assertEquals("the, ", comma.commit)
        assertTrue(comma.handled)
    }

    @Test
    fun aWordOrTheStartOfOneIsNotCorrected() {
        for (typed in listOf("us", "form", "hel", "he")) {
            val session = session()
            session.type(typed)
            assertEquals("$typed ", session.apply(Action.Key(' ')).commit)
        }
    }

    private fun committed(keys: String, before: String = "I met "): String {
        val session = session()
        session.apply(Action.Context(before))
        session.type(keys)
        return session.apply(Action.Key(' ')).commit.trimEnd()
    }

    @Test
    fun aRareWordIsCorrectedOnlyToOneFarLikelier() {
        // of the many words the pages have, nad is a slip of and
        assertEquals("and", committed("nad"))
        // a word as likely is the user's: no capital more or less, no longer word
        assertEquals("us", committed("us"))
        assertEquals("form", committed("form"))
    }

    @Test
    fun anApostropheTypedIsNeverTakenAway() {
        assertEquals("year's", committed("year's"))
        assertEquals("it's", committed("it's"))
        // nor moved: what follows it is the user's
        assertEquals("its'", committed("its'"))
    }

    @Test
    fun aDigitOrAHyphenLeavesTheWordAsTyped() {
        val digit = session().also { it.type("teh") }.apply(Action.Key('1'))
        assertEquals("teh", digit.commit)
        assertFalse(digit.handled)
        // each part a word of its own, which the words have none of
        assertEquals("teh-cut", committed("teh-cut"))
    }

    // where nothing is learned nothing is known of the text: nothing follows, nothing is a sentence's start
    @Test
    fun withoutLearningNothingIsOffered() {
        val session = session().apply { learning = false }
        session.apply(Action.Context(""))
        session.type("i")
        assertFalse(session.apply(Action.Key(' ')).predicting)
        // turned off, what was known goes
        val turned = session()
        turned.apply(Action.Context("I fed the "))
        turned.learning = false
        assertEquals("cut", turned.type("cit").candidates.first())
    }

    // the user's own capital: a name, likelier than a slip, but for a key beside
    @Test
    fun aWordWithACapitalOfItsOwnIsCorrectedOnlyForAKeyBeside() {
        assertEquals("Steve", committed("Stebe"))
        assertEquals("Stve", committed("Stve"))
        // at a sentence's start the capital is the keyboard's
        assertEquals("Steve", committed("Stve", before = ""))
    }

    // a letter short and a key beside: the word, though the keys start it too
    @Test
    fun aWordALetterShortWithASlipIsCorrected() {
        assertEquals("hello", committed("hwll"))
        assertEquals("hwl", committed("hwl"))
    }

    @Test
    fun backspaceTakesTheCorrectionBackForGood() {
        val session = session()
        session.type("teh")
        session.apply(Action.Key(' '))
        val back = session.apply(Action.Backspace)
        assertEquals(4, back.deleteBefore)
        assertEquals("teh", back.preedit)
        assertEquals("teh ", session.apply(Action.Key(' ')).commit)
        assertEquals("teh", session.type("teh").candidates.first())
        // only right after: a backspace after more is the app's
        val later = session()
        later.type("teh")
        later.apply(Action.Key(' '))
        later.apply(Action.Key(','))
        assertFalse(later.apply(Action.Backspace).handled)
    }

    @Test
    fun theWordsBeforeTheCursorWeighWhatIsTyped() {
        assertEquals("cut", session().type("cit").candidates.first())
        val after = session()
        after.apply(Action.Context("I fed the "))
        assertEquals("cat", after.type("cit").candidates.first())
        // what was written in a field now empty: a word's end after it, not run into what is typed
        val cleared = session()
        cleared.apply(Action.Context("I fed the", cleared = true))
        assertEquals("cat", cleared.type("cit").candidates.first())
        // committed here too
        val typed = session()
        typed.type("the")
        typed.apply(Action.Key(' '))
        assertEquals("cat", typed.type("cit").candidates.first())
        // nothing kept where nothing is learned
        val private = session().apply { learning = false }
        private.apply(Action.Context("I fed the "))
        assertEquals("cut", private.type("cit").candidates.first())
    }

    @Test
    fun aWordCommittedIsFollowedByWhatMayComeNext() {
        val session = session()
        session.apply(Action.Context(""))
        session.type("i")
        val next = session.apply(Action.Key(' '))
        assertTrue(next.predicting)
        assertEquals(listOf("don't", "I'm"), next.candidates)
        assertEquals("don't ", session.apply(Action.Pick(0)).commit)
        // a letter starts a word in their place
        val typed = session.type("h")
        assertFalse(typed.predicting)
        assertEquals("h", typed.candidates.first())
        // a sentence's first, capitalised: after the full stop two spaces make
        val start = session()
        start.type("hello")
        start.apply(Action.Key(' '))
        assertEquals(listOf("I", "The"), start.apply(Action.Key(' ')).candidates)
    }

    @Test
    fun suggestionsTakeTheCaseOfWhatIsTyped() {
        assertEquals(listOf("Hel", "Hello", "Help"), session().type("Hel").candidates.take(3))
        assertEquals(listOf("HEL", "HELLO", "HELP"), session().type("HEL").candidates.take(3))
        // one capital letter is a word's start, whatever follows it
        assertTrue("I'm" in session().type("I'").candidates)
    }

    @Test
    fun spaceCommitsTheWordTypedAndASpace() {
        val session = session()
        session.type("hel")
        val s = session.apply(Action.Key(' '))
        assertEquals("hel ", s.commit)
        assertTrue(s.handled)
        assertEquals("", s.preedit)
    }

    @Test
    fun aWordPickedIsFollowedByASpace() {
        val session = session()
        session.type("hel")
        assertEquals("hello ", session.apply(Action.Pick(1)).commit)
        val he = session.type("he").candidates
        assertEquals("help ", session.apply(Action.Select(he.indexOf("help"))).commit)
    }

    @Test
    fun punctuationAfterTheSpaceOfAWordTakesItsPlace() {
        val session = session()
        session.type("hello")
        session.apply(Action.Key(' '))
        val stop = session.apply(Action.Key('.'))
        assertEquals(". ", stop.commit)
        assertEquals(1, stop.deleteBefore)
        // a space typed after that is that one
        assertEquals("", session.apply(Action.Key(' ')).commit)
    }

    @Test
    fun spaceTwiceAfterAWordIsAFullStop() {
        val session = session()
        session.type("hello")
        session.apply(Action.Key(' '))
        val stop = session.apply(Action.Key(' '))
        assertEquals(". ", stop.commit)
        assertEquals(1, stop.deleteBefore)
        // the space after the full stop is there already: one typed out of habit is taken as it
        val habit = session.apply(Action.Key(' '))
        assertTrue(habit.handled)
        assertEquals("", habit.commit)
        // then a space is the user's
        assertFalse(session.apply(Action.Key(' ')).handled)
    }

    @Test
    fun theSpaceAfterAColonIsTakenBackBySlash() {
        // a sentence after a full stop may start lower case where the field asks for no capitals
        val session = session()
        session.type("hello")
        assertEquals("hello. ", session.apply(Action.Key('.')).commit)
        assertEquals(0, session.apply(Action.Key('w')).deleteBefore)
        // https://
        val url = session()
        url.type("hello")
        assertEquals("hello: ", url.apply(Action.Key(':')).commit)
        val slash = url.apply(Action.Key('/'))
        assertEquals("/", slash.commit)
        assertEquals(1, slash.deleteBefore)
        // after a comma a lower-case word is the next one
        val comma = session()
        comma.type("hello")
        comma.apply(Action.Key(','))
        assertEquals(0, comma.apply(Action.Key('h')).deleteBefore)
    }

    @Test
    fun punctuationEndingAWordEndsItWithASpace() {
        val session = session()
        session.type("hello")
        val stop = session.apply(Action.Key('.'))
        assertEquals("hello. ", stop.commit)
        assertEquals(0, stop.deleteBefore)
        // more punctuation goes before that space: "?!", "..."
        val bang = session.apply(Action.Key('!'))
        assertEquals("! ", bang.commit)
        assertEquals(1, bang.deleteBefore)
        // the next word after the space
        session.type("hello")
        assertEquals("hello ", session.apply(Action.Key(' ')).commit)
    }

    @Test
    fun theCursorMovedOrABackspaceKeepsTheSpaceAsItIs() {
        val moved = session()
        moved.type("hello")
        moved.apply(Action.Key(' '))
        moved.apply(Action.Context("hello "))
        assertFalse(moved.apply(Action.Key('.')).handled)
        val deleted = session()
        deleted.type("hello")
        deleted.apply(Action.Key(' '))
        assertFalse(deleted.apply(Action.Backspace).handled)
        assertFalse(deleted.apply(Action.Key(',')).handled)
    }

    @Test
    fun aLoneIIsUpperCase() {
        val session = session()
        session.type("i")
        assertEquals("I ", session.apply(Action.Key(' ')).commit)
        session.type("i'm")
        assertEquals("I'm ", session.apply(Action.Key(' ')).commit)
        assertEquals(listOf("I", "I'm"), session().type("i").candidates.take(2))
        // in a word it is as typed
        assertEquals("it", session().type("it").candidates[0])
    }

    @Test
    fun otherKeysEndTheWordAsTypedAndAreTheApps() {
        val session = session()
        session.type("hel")
        val digit = session.apply(Action.Key('1'))
        assertEquals("hel", digit.commit)
        assertFalse(digit.handled)
        // an apostrophe or hyphen only within a word
        assertFalse(session.reads('\''))
        session.type("i")
        assertTrue(session.reads('\''))
        // enter: the word as typed, and the key the app's all the same (a search runs)
        val enter = session.apply(Action.CommitRaw)
        assertEquals("I", enter.commit)
        assertFalse(enter.handled)
        assertFalse(session.apply(Action.CommitRaw).handled)
    }

    @Test
    fun backspaceDeletesALetterThenIsTheApps() {
        val session = session()
        session.type("he")
        assertEquals(listOf("h"), session.apply(Action.Backspace).candidates.take(1))
        assertEquals("", session.apply(Action.Backspace).preedit)
        assertFalse(session.apply(Action.Backspace).handled)
    }

    @Test
    fun aTapInTheWordPutsTheCursorWhereLettersGoAndBackspaceDeletes() {
        val session = session()
        assertEquals(-1, session.type("helo").cursor)
        val moved = session.apply(Action.Cursor(3))
        assertEquals(3, moved.cursor)
        val typed = session.apply(Action.Key('l'))
        assertEquals("hello", typed.preedit)
        // after the letter, short of the end
        assertEquals(4, typed.cursor)
        session.apply(Action.Cursor(2))
        assertEquals("hllo", session.apply(Action.Backspace).preedit)
        // at the start, backspace deletes nothing
        session.apply(Action.Cursor(0))
        assertEquals("hllo", session.apply(Action.Backspace).preedit)
        // no word: corrected
        assertEquals("hello ", session.apply(Action.Key(' ')).commit)
        // nothing typed: the app's
        assertFalse(session.apply(Action.Cursor(0)).handled)
    }

    @Test
    fun withNoWordsTheWordTypedAlone() {
        assertEquals(listOf("hel"), EnglishSession(null).type("hel").candidates)
    }

    @Test
    fun pagesOfCandidates() {
        val session = EnglishSession(data, pageSize = 2)
        val first = session.type("hel")
        assertEquals(listOf("hel", "hello"), first.candidates)
        assertTrue(first.hasNextPage)
        val next = session.apply(Action.NextPage)
        assertEquals(1, next.page)
        assertEquals(2, next.first)
        assertEquals(listOf("help", "hell"), next.candidates)
        assertEquals("hell ", session.apply(Action.Pick(3)).commit)
    }

    // the host hands it space and digits: they pick nothing here
    @Test
    fun theKeyboardHandsEnglishEveryKey() {
        val keyboard = Keyboard(session(), nineKeys = false)
        "hel".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        assertEquals("hel ", keyboard.onEvent(EngineEvent.CHAR, ' '.code).commit)
        "he".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        val digit = keyboard.onEvent(EngineEvent.CHAR, '2'.code)
        assertEquals("he", digit.commit)
        assertFalse(digit.handled)
    }

    // an arrow while a word is typed: the word as typed, and the key the app's
    @Test
    fun anArrowCommitsTheWordAndIsTheApps() {
        val keyboard = Keyboard(session(), nineKeys = false)
        "hel".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        val arrow = keyboard.onEvent(EngineEvent.OTHER, 0)
        assertEquals("hel", arrow.commit)
        assertFalse(arrow.handled)
        // a tap with nothing typed is the app's
        assertFalse(keyboard.onEvent(EngineEvent.CURSOR, 0).handled)
    }

    // the space after a word is no longer before the cursor: punctuation goes after what is there
    @Test
    fun aKeyThatIsTheAppsForgetsTheSpaceAfterAWord() {
        for (event in listOf(EngineEvent.ENTER, EngineEvent.OTHER, EngineEvent.ESCAPE)) {
            val keyboard = Keyboard(session(), nineKeys = false)
            "hello ".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
            assertFalse(keyboard.onEvent(event, 0).handled)
            val stop = keyboard.onEvent(EngineEvent.CHAR, '.'.code)
            assertEquals("$event", 0, stop.deleteBefore)
            assertFalse(stop.handled)
        }
    }

    @Test
    fun aCoarseWordIsNeverOfferedYetTypedAsIs() {
        val s = EnglishSession(data, coarseWords = listOf("hell")).type("hel")
        assertFalse("hell" in s.candidates)
        val typed = EnglishSession(data, coarseWords = listOf("hell")).also { it.type("hell") }
        assertEquals("hell ", typed.apply(Action.Key(' ')).commit)
    }

    /** recieve, untill were in the pages often enough to be left as typed. */
    @Test
    fun aMisspellingIsCorrectedThoughThePagesHaveItAndNeverOffered() {
        val list = mapOf("hell" to "hello", "teh" to "the")
        val typed = EnglishSession(data, misspellings = list).also { it.type("Hell") }
        assertEquals("Hello ", typed.apply(Action.Key(' ')).commit)
        assertFalse("hell" in EnglishSession(data, misspellings = list).type("hel").candidates)
        // the shipped list: no word twice, each to a word
        assertTrue(Misspellings.MAP.getValue("recieve") == "receive" && Misspellings.MAP.values.none { it.isBlank() || it in Misspellings.MAP })
        // as the lines are, which the map, keeping the last of a word twice, cannot show; each lower
        // case, as it is looked up
        val misspelt = CoarseWords.listLines(Misspellings::class.java, "misspellings.txt").map { it.substringBefore(' ') }
        assertEquals(misspelt.distinct(), misspelt)
        assertTrue(misspelt.all { it == it.lowercase() })
    }

    // taught, a misspelling is the user's: no longer corrected, and offered as any word
    @Test
    fun aMisspellingTheUserMadeTheirsIsOffered() {
        val session = EnglishSession(data, user = EnglishUser(), misspellings = mapOf("hell" to "hello"))
        session.type("hel")
        assertFalse("hell" in session.candidates(0, 10).map { it.text })
        session.apply(Action.Reset)
        session.type("hell")
        assertEquals("hello ", session.apply(Action.Key(' ')).commit)
        session.apply(Action.Backspace)
        assertEquals("hell ", session.apply(Action.Key(' ')).commit)
        session.type("hel")
        assertTrue("hell" in session.candidates(0, 10).map { it.text })
    }

    // typed once, a coarse word is the user's: offered and predicted as any other
    @Test
    fun aCoarseWordTheUserTypedIsOfferedAndPredicted() {
        val session = EnglishSession(data, user = EnglishUser(), coarseWords = listOf("cat"))
        fun offeredAfterThe(): List<String> {
            session.apply(Action.Context("I fed "))
            session.commit("the")
            return session.candidates(0, 10).map { it.text }
        }
        assertFalse("cat" in offeredAfterThe())
        // typed after no word: what follows "the" is the model's alone
        session.apply(Action.Context(""))
        session.commit("cat")
        assertTrue("cat" in offeredAfterThe())
        session.apply(Action.Context("I fed the "))
        session.type("cit")
        assertTrue("cat" in session.candidates(0, 10).map { it.text })
    }

    // made on the thread that types, before the warm-up built the word index the lookups need
    @Test
    fun theListsAreLookedUpAtTheFirstKeyNotWhenTheSessionIsMade() {
        var asked = 0
        val coarse = object : AbstractList<String>() {
            override val size: Int get() = 1.also { asked++ }
            override fun get(index: Int): String = "hell"
        }
        var spelt = 0
        val misspellings = object : AbstractMap<String, String>() {
            override val entries: Set<Map.Entry<String, String>> get() = mapOf("teh" to "the").entries.also { spelt++ }
        }
        val session = EnglishSession(data, coarseWords = coarse, misspellings = misspellings)
        assertEquals(0, asked)
        assertEquals(0, spelt)
        assertFalse("hell" in session.type("hel").candidates)
        assertTrue(asked > 0)
        assertTrue(spelt > 0)
    }

    // learned as hello, a sentence's first is the word the next is counted and offered after
    @Test
    fun theWordAfterASentencesCapitalisedFirstIsLearnedAfterIt() {
        val user = EnglishUser()
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context(""))
        session.commit("Hello")
        session.commit("youmo")
        assertEquals(setOf("youmo"), user.followers("hello"))
        session.apply(Action.Context("Fine. "))
        session.commit("Hello")
        assertTrue("youmo" in session.candidates(0, 10).map { it.text })
    }

    // typed after Hello, cat is scored up after it: cit is cat, not cut
    @Test
    fun aWordTypedAfterASentencesCapitalisedFirstIsScoredUpAfterIt() {
        val session = EnglishSession(data, user = EnglishUser())
        repeat(3) {
            session.apply(Action.Context(""))
            session.commit("Hello")
            session.commit("cat")
        }
        session.apply(Action.Context(""))
        session.commit("Hello")
        assertEquals("cat", session.type("cit").candidates.first())
    }

    // a correction of two words is each, the next word after its last; a run of letters too long for a word, none
    @Test
    fun whatIsNoWordIsNotLearned() {
        val user = EnglishUser()
        val session = EnglishSession(data, user = user, misspellings = mapOf("alot" to "a lot"))
        session.apply(Action.Context("I like "))
        assertEquals("a lot ", session.commit("alot"))
        session.commit("x".repeat(EnglishText.MAX_WORD + 1))
        assertEquals(setOf("a", "lot"), user.words)
        assertEquals(setOf("lot"), user.followers("a"))
        // taken back, both go
        val back = EnglishUser()
        val undone = EnglishSession(data, user = back, misspellings = mapOf("alot" to "a lot"))
        undone.commit("alot")
        undone.apply(Action.Backspace)
        assertEquals(setOf("alot"), back.words)
    }

    // the text before the cursor cut short is no field's start: a capital there is the user's. The host
    // reads no more than Action.Context.HOST_CHARS, so a context as long may be the end of more
    @Test
    fun aContextCutShortStartsNoSentence() {
        val cut = EnglishUser()
        val longer = EnglishSession(data, user = cut)
        longer.apply(Action.Context(" ".repeat(Action.Context.HOST_CHARS)))
        longer.commit("Hello")
        assertEquals(setOf("Hello"), cut.words)
        val whole = EnglishUser()
        val within = EnglishSession(data, user = whole)
        within.apply(Action.Context(" ".repeat(Action.Context.HOST_CHARS - 1)))
        within.commit("Hello")
        assertEquals(setOf("hello"), whole.words)
    }

    // so is a field's start that what was typed since pushed out of what the session keeps
    @Test
    fun whatWasTypedPastWhatIsKeptStartsNoSentence() {
        fun learnedAfterSpaces(spaces: Int): Set<String> {
            val user = EnglishUser()
            val session = EnglishSession(data, user = user)
            session.apply(Action.Context(""))
            // each the app's, with nothing typed: what the session knows of the text grows by one
            repeat(spaces) { assertFalse(session.apply(Action.Key(' ')).handled) }
            session.commit("Hello")
            return user.words
        }
        assertEquals(setOf("Hello"), learnedAfterSpaces(EnglishText.CONTEXT_CHARS + 1))
        assertEquals(setOf("hello"), learnedAfterSpaces(EnglishText.CONTEXT_CHARS))
    }

    // as written past their first letter: iPhone, not IPhone
    @Test
    fun aSentencesFirstOfferedHasACapitalUnlessItHasOneOfItsOwn() {
        val b = EnglishDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram(EnglishText.START, -9f, -0.5f)
            .unigram("hello", -2f, 0f)
            .unigram("iPhone", -3f, 0f)
            .unigram("the", -1.5f, 0f)
            .bigram(EnglishText.START, "iPhone", -1f, 0f)
            .bigram(EnglishText.START, "the", -1.5f, 0f)
        val session = EnglishSession(EnglishData.load(ByteBuffer.wrap(b.build().toByteArray())))
        session.commit("hello")
        assertEquals(listOf("iPhone", "The"), session.apply(Action.Key(' ')).candidates)
    }

    // in the session's place as punctuation is, with a space after: a bracket closed, a quote where one is open
    @Test
    fun aClosingBracketOrQuoteTakesThePlaceOfTheSpace() {
        val bracket = session()
        bracket.type("above")
        bracket.apply(Action.Key('.'))
        val closed = bracket.apply(Action.Key(')'))
        assertEquals(") ", closed.commit)
        assertEquals(1, closed.deleteBefore)
        // a space typed after that is that one, taken here, not the app's to add
        val after = bracket.apply(Action.Key(' '))
        assertEquals("", after.commit)
        assertTrue(after.handled)
        // after the colon's: hello:)
        val smile = session()
        smile.type("hello")
        smile.apply(Action.Key(':'))
        assertEquals(") ", smile.apply(Action.Key(')')).commit)
        // a quote opened after the space is the app's; then one closes it
        val quote = session()
        quote.commit("said")
        assertFalse(quote.apply(Action.Key('"')).handled)
        quote.type("stop")
        quote.apply(Action.Key('.'))
        val shut = quote.apply(Action.Key('"'))
        assertEquals("\" ", shut.commit)
        assertEquals(1, shut.deleteBefore)
        assertTrue(quote.apply(Action.Key(' ')).handled)
    }

    // the single quote likewise; an apostrophe in a word (don't) or ending one (dogs') opens none
    @Test
    fun anApostropheIsNoQuote() {
        fun EnglishSession.quoteAfterStop(before: String): Snapshot {
            apply(Action.Context(before))
            type("stop")
            apply(Action.Key('.'))
            return apply(Action.Key('\''))
        }
        assertFalse(session().quoteAfterStop("I don't ").handled)
        assertFalse(session().quoteAfterStop("the dogs' ").handled)
        val quoted = session()
        val shut = quoted.quoteAfterStop("she said '")
        assertEquals("' ", shut.commit)
        assertEquals(1, shut.deleteBefore)
        // the quote the session put in ends no word: a space after it is that one, no full stop
        val after = quoted.apply(Action.Key(' '))
        assertTrue(after.handled)
        assertEquals("", after.commit)
        // one after an opening bracket opens one too: ('stop.'
        assertEquals("' ", session().quoteAfterStop("she said ('").commit)
        // a word's own apostrophe before the session's space: a space after it is a full stop, as after any word
        val dogs = session()
        dogs.commit("dogs'")
        assertEquals(". ", dogs.apply(Action.Key(' ')).commit)
        // and a quote typed there is an opening one, the app's
        val possessive = session()
        possessive.commit("dogs'")
        assertFalse(possessive.apply(Action.Key('\'')).handled)
        // one after no letter, though of the word typed (a-'), is a quote: a space after it is that one
        val dashed = session()
        assertEquals("a-' ", dashed.commit("a-'"))
        val space = dashed.apply(Action.Key(' '))
        assertTrue(space.handled)
        assertEquals("", space.commit)
    }

    // a quote is read by what is before it, not counted: one closing a quote opened out of what the
    // session knows is none open, and the one typed next opens one, the space before it kept
    @Test
    fun aQuoteWhoseOpeningOneIsOutOfSightIsNoneOpen() {
        val session = session()
        session.apply(Action.Context("it ended.\" "))
        session.commit("said")
        assertFalse(session.apply(Action.Key('"')).handled)
    }

    // a quote first of what the session knows opens one only at the field's start: first of a context
    // as long as the host reads, or of what was typed since a Reset, it may close one
    @Test
    fun aQuoteFirstOfTextCutShortOrUnknownIsNoneOpen() {
        fun EnglishSession.quoteAfterSaid(): Snapshot {
            commit("said")
            return apply(Action.Key('"'))
        }
        val spaces = " ".repeat(Action.Context.HOST_CHARS - 2)
        val start = session()
        start.apply(Action.Context("\"$spaces"))
        assertEquals("\" ", start.quoteAfterSaid().commit)
        val cut = session()
        cut.apply(Action.Context("\"$spaces "))
        assertFalse(cut.quoteAfterSaid().handled)
        val reset = session()
        reset.apply(Action.Reset)
        assertFalse(reset.apply(Action.Key('"')).handled)
        assertFalse(reset.quoteAfterSaid().handled)
    }

    // the likeliest words a key's search finds coarse: more are asked for, and as many offered as ever
    @Test
    fun wordsLeftOutLeaveNoGapInWhatIsOffered() {
        val b = EnglishDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram(EnglishText.START, -9f, 0f)
        val words = ('a'..'t').map { "pre$it" }
        words.forEachIndexed { i, word -> b.unigram(word, -2f - i * 0.1f, 0f) }
        val pre = EnglishData.load(ByteBuffer.wrap(b.build().toByteArray()))
        // the five likeliest of the ten first asked for
        val session = EnglishSession(pre, coarseWords = words.take(5))
        assertEquals(EnglishSession.SUGGESTED + 1, session.type("pre").total)
        assertEquals(listOf("pre") + words.subList(5, 5 + EnglishSession.SUGGESTED), session.candidates(0, 20).map { it.text })
    }

    // the trigrams of each context, looked up once for the words its search scores, are its own:
    // rud is red after "I the", rod after "saw the", in the one session
    @Test
    fun eachContextIsScoredWithItsOwnTrigrams() {
        val b = EnglishDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram(EnglishText.START, -9f, 0f)
            .unigram("I", -1f, -0.5f)
            .unigram("the", -1.2f, -0.5f)
            .unigram("saw", -2f, -0.5f)
            .unigram("red", -3f, 0f)
            .unigram("rod", -3f, 0f)
            .bigram("I", "the", -1f, -0.5f)
            .bigram("saw", "the", -1f, -0.5f)
            .bigram("the", "red", -2f, 0f)
            .bigram("the", "rod", -2f, 0f)
            .trigram("I", "the", "red", -0.2f)
            .trigram("saw", "the", "rod", -0.2f)
        val session = EnglishSession(EnglishData.load(ByteBuffer.wrap(b.build().toByteArray())))
        session.apply(Action.Context("I the "))
        assertEquals("red ", session.commit("rud"))
        session.apply(Action.Context("saw the "))
        assertEquals("rod ", session.commit("rud"))
    }
}
