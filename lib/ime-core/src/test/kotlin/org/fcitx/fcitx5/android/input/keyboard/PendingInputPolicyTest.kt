/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PendingInputPolicyTest {

    @Test
    fun chineseTakesTheFirstCandidateForAPreedit() {
        assertEquals(Resolution.SelectFirstCandidate, PendingInputPolicy.resolve("zh_CN", hasPreedit = true))
        assertEquals(Resolution.SelectFirstCandidate, PendingInputPolicy.resolve("zh_TW", hasPreedit = true))
        assertEquals(Resolution.SelectFirstCandidate, PendingInputPolicy.resolve("zh", hasPreedit = true))
    }

    @Test
    fun chineseLeavesPredictionsAlone() {
        assertEquals(Resolution.None, PendingInputPolicy.resolve("zh_CN", hasPreedit = false))
    }

    @Test
    fun otherLanguagesKeepWhatWasTyped() {
        assertEquals(Resolution.FinishComposing, PendingInputPolicy.resolve("en", hasPreedit = true))
        assertEquals(Resolution.FinishComposing, PendingInputPolicy.resolve("ja", hasPreedit = true))
        assertEquals("finishing with nothing composed is harmless", Resolution.FinishComposing,
            PendingInputPolicy.resolve("en", hasPreedit = false))
    }

    /**
     * Characterises, not specifies: the upstream check is a plain prefix test on fcitx's
     * language code, case and all, so "zhuang" counts as Chinese.
     */
    @Test
    fun currentlyTheLanguageIsMatchedByAPlainPrefix() {
        assertEquals(Resolution.FinishComposing, PendingInputPolicy.resolve("", hasPreedit = true))
        assertEquals(Resolution.FinishComposing, PendingInputPolicy.resolve("ZH_CN", hasPreedit = true))
        assertEquals(Resolution.SelectFirstCandidate, PendingInputPolicy.resolve("zhuang", hasPreedit = true))
    }

    /** xiaoming then @ in an email field was 小明@: there what is typed is meant. */
    @Test
    fun inAnAddressFieldChineseCommitsWhatWasTyped() {
        assertEquals(Resolution.CommitAsTyped, PendingInputPolicy.resolve("zh_CN", hasPreedit = true, address = true))
        // nothing typed: nothing to commit, predictions are not taken there either
        assertEquals(Resolution.None, PendingInputPolicy.resolve("zh_CN", hasPreedit = false, address = true))
        // other languages type the text itself already
        assertEquals(Resolution.FinishComposing, PendingInputPolicy.resolve("en", hasPreedit = true, address = true))
    }

    /** The screen turned mid-pinyin left `ni hao` in the text. */
    @Test
    fun leavingAChinesePreeditDropsItAndOtherLanguagesKeepTheirs() {
        assertFalse(PendingInputPolicy.keepsPreeditOnLeaving("zh_CN"))
        assertTrue(PendingInputPolicy.keepsPreeditOnLeaving("en"))
        assertTrue(PendingInputPolicy.keepsPreeditOnLeaving(""))
        // handwriting's preedit is its characters
        assertTrue(PendingInputPolicy.keepsPreeditOnLeaving("zh_CN", isText = true))
    }

    /** Back over a word selected in WebView, which ended the composing text as the user selected, took the word. */
    @Test
    fun aPreeditGoesUnlessAnEmptyComposingTextWouldTakeTheSelection() {
        assertTrue(PendingInputPolicy.takesPreeditOut("zh_CN", preedit = true, selected = false, editorComposes = false))
        // the editor's composing text is what an empty one replaces, the selection stays
        assertTrue(PendingInputPolicy.takesPreeditOut("zh_CN", preedit = true, selected = true, editorComposes = true))
        assertFalse(PendingInputPolicy.takesPreeditOut("zh_CN", preedit = true, selected = true, editorComposes = false))
        // nothing shown: nothing to take out, over a selection or not
        assertFalse(PendingInputPolicy.takesPreeditOut("zh_CN", preedit = false, selected = false, editorComposes = true))
        assertFalse(PendingInputPolicy.takesPreeditOut("en", preedit = true, selected = false, editorComposes = true))
        assertFalse(PendingInputPolicy.takesPreeditOut("zh_CN", preedit = true, selected = false, editorComposes = true, isText = true))
    }

    /** xiaoming2026@ in an email field came out 小明026@, and xiaoming_ 小明——. */
    @Test
    fun inAnAddressFieldAKeyThatTypesNoLetterGoesInAsItIs() {
        for (key in listOf("2", "0", "_", "-", "#", "，", ";")) {
            assertTrue(key, PendingInputPolicy.typesAsIs(key, address = true))
            assertFalse(key, PendingInputPolicy.typesAsIs(key, address = false))
        }
        assertFalse(PendingInputPolicy.typesAsIs("a", address = true))
        assertFalse(PendingInputPolicy.typesAsIs("Q", address = true))
        assertFalse(PendingInputPolicy.typesAsIs(" ", address = true))
        // more than one character is no key's
        assertFalse(PendingInputPolicy.typesAsIs(".com", address = true))
        assertFalse(PendingInputPolicy.typesAsIs("", address = true))
    }

    /** On the nine keys a digit is a key, and a syllable or a letter typed as itself a private character. */
    @Test
    fun onTheNineKeysTheirKeysStayKeys() {
        val nineKeys = { c: Int -> Engines.readsAsKey(Engines.T9, ShuangpinScheme.ZIRANMA, c) }
        assertFalse(PendingInputPolicy.typesAsIs("2", address = true, reads = nineKeys))
        assertFalse(PendingInputPolicy.typesAsIs("1", address = true, reads = nineKeys))
        assertFalse(PendingInputPolicy.typesAsIs("\uE000", address = true, reads = nineKeys))
        assertFalse(PendingInputPolicy.typesAsIs(String(Character.toChars(0xF0000)), address = true, reads = nineKeys))
        assertTrue(PendingInputPolicy.typesAsIs("_", address = true, reads = nineKeys))
    }

    /** 微软双拼's ing is `;`: put in as it is, a popup's `;` cut the syllable typed in an address field. */
    @Test
    fun inShuangpinTheSchemesOwnKeysStayKeys() {
        fun reads(im: String, scheme: ShuangpinScheme) = { c: Int -> Engines.readsAsKey(im, scheme, c) }
        assertFalse(PendingInputPolicy.typesAsIs(";", address = true, reads = reads(Engines.SHUANGPIN, ShuangpinScheme.MICROSOFT)))
        assertTrue(PendingInputPolicy.typesAsIs("_", address = true, reads = reads(Engines.SHUANGPIN, ShuangpinScheme.MICROSOFT)))
        // a scheme that types no ; with it, and the other input methods, take it as text
        assertTrue(PendingInputPolicy.typesAsIs(";", address = true, reads = reads(Engines.SHUANGPIN, ShuangpinScheme.ZIRANMA)))
        assertTrue(PendingInputPolicy.typesAsIs(";", address = true, reads = reads(Engines.PINYIN, ShuangpinScheme.MICROSOFT)))
        assertTrue(PendingInputPolicy.typesAsIs("2", address = true, reads = reads(Engines.SHUANGPIN, ShuangpinScheme.MICROSOFT)))
    }
}
