/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The quick phrase file format: one entry per line, `keyword` then whitespace then the phrase,
 * with the phrase escaped the same way fcitx escapes config values.
 *
 * Mirrors fcitx5's own parser:
 * https://github.com/fcitx/fcitx5/blob/5.1.5/src/modules/quickphrase/quickphraseprovider.cpp#L67
 */
class QuickPhraseEntryTest {

    private fun parse(line: String) = QuickPhraseEntry.fromLine(line)

    // region accepted lines

    @Test
    fun parsesASimpleLine() {
        val e = parse("hello world")
        assertEquals(QuickPhraseEntry("hello", "world"), e)
    }

    @Test
    fun keywordEndsAtTheFirstWhitespace() {
        assertEquals(QuickPhraseEntry("a", "b c d"), parse("a b c d"))
    }

    @Test
    fun anyWhitespaceCharacterSeparatesKeywordFromPhrase() {
        // the separator set matches fcitx-utils' macros.h
        for (ws in listOf(' ', '\t', '\u000b', '\u000c')) {
            assertEquals("separated by ${ws.code}", QuickPhraseEntry("k", "v"), parse("k${ws}v"))
        }
    }

    @Test
    fun runsOfWhitespaceBetweenKeywordAndPhraseAreSkipped() {
        assertEquals(QuickPhraseEntry("k", "v"), parse("k \t  v"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(QuickPhraseEntry("k", "v"), parse("   k v   "))
    }

    @Test
    fun phraseKeepsItsInternalSpacing() {
        assertEquals(QuickPhraseEntry("k", "a  b"), parse("""k "a  b""""))
    }

    @Test
    fun quotedPhraseIsUnescaped() {
        assertEquals(QuickPhraseEntry("k", "a b"), parse("""k "a b""""))
        assertEquals(QuickPhraseEntry("k", "\""), parse("""k "\"""""))
        assertEquals(QuickPhraseEntry("k", "a\nb"), parse("""k "a\nb""""))
    }

    @Test
    fun nonAsciiIsPreserved() {
        assertEquals(QuickPhraseEntry("nihao", "你好"), parse("nihao 你好"))
        assertEquals(QuickPhraseEntry("wave", "👋"), parse("wave 👋"))
    }

    @Test
    fun keywordMayContainPunctuation() {
        assertEquals(QuickPhraseEntry(":)", "😀"), parse(":) 😀"))
    }

    // endregion

    // region rejected lines

    @Test
    fun blankLinesAreSkipped() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("\t"))
        assertNull(parse("\n"))
    }

    @Test
    fun aLineWithoutWhitespaceHasNoPhraseAndIsSkipped() {
        assertNull(parse("keywordonly"))
    }

    /** After trimming there is nothing past the separator, so there is no phrase. */
    @Test
    fun aKeywordFollowedOnlyByWhitespaceIsSkipped() {
        assertNull(parse("keyword   "))
    }

    /**
     * An unterminated quote is not an error: `unescapeForValue` returns the text as-is, so the
     * opening quote ends up in the phrase. Pinned because it looks like it should be rejected.
     */
    @Test
    fun anUnterminatedQuoteIsKeptLiterally() {
        assertEquals(QuickPhraseEntry("k", "\"unterminated"), parse("""k "unterminated"""))
    }

    // endregion

    // region serialize

    @Test
    fun serializeJoinsKeywordAndEscapedPhrase() {
        assertEquals("hello world", QuickPhraseEntry("hello", "world").serialize())
    }

    @Test
    fun serializeQuotesAPhraseContainingSpaces() {
        assertEquals("""k "a b"""", QuickPhraseEntry("k", "a b").serialize())
    }

    @Test
    fun serializeEscapesSpecialCharacters() {
        assertEquals("""k "a\nb"""", QuickPhraseEntry("k", "a\nb").serialize())
        assertEquals("""k "\""""", QuickPhraseEntry("k", "\"").serialize())
    }

    // endregion

    // region round trip

    @Test
    fun serializeThenParseIsIdentityForRepresentablePhrases() {
        val entries = listOf(
            QuickPhraseEntry("hello", "world"),
            QuickPhraseEntry("k", "a b"),
            QuickPhraseEntry("k", "a\nb"),
            QuickPhraseEntry("k", "\""),
            QuickPhraseEntry("k", "a\tb"),
            QuickPhraseEntry("nihao", "你好"),
            QuickPhraseEntry(":)", "😀"),
            QuickPhraseEntry("k", "a\\b"),
        )
        for (entry in entries) {
            assertEquals("round trip of $entry", entry, parse(entry.serialize()))
        }
    }

    /**
     * An empty phrase escapes to nothing, so the line serialises to a keyword followed by a
     * trailing space -- which the parser then rejects. Entries with an empty phrase silently
     * disappear across a save/load cycle; callers must not create them.
     */
    @Test
    fun anEmptyPhraseDoesNotSurviveARoundTrip() {
        val entry = QuickPhraseEntry("k", "")
        assertEquals("k ", entry.serialize())
        assertNull("the re-read line is dropped", parse(entry.serialize()))
    }

    // endregion
}
