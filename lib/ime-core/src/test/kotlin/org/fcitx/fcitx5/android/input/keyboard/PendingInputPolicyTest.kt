/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInputPolicyTest {

    @Test
    fun whatIsPendingIsResolvedByTheLanguage() = assertRows(
        "chinese takes the first candidate for a preedit" to {
            assertEquals(Resolution.SelectFirstCandidate, PendingInputPolicy.resolve("zh_CN", hasPreedit = true))
            assertEquals(Resolution.SelectFirstCandidate, PendingInputPolicy.resolve("zh_TW", hasPreedit = true))
            assertEquals(Resolution.SelectFirstCandidate, PendingInputPolicy.resolve("zh", hasPreedit = true))
        },
        "chinese leaves predictions alone" to {
            assertEquals(Resolution.None, PendingInputPolicy.resolve("zh_CN", hasPreedit = false))
        },
        "other languages keep what was typed" to {
            assertEquals(Resolution.FinishComposing, PendingInputPolicy.resolve("en", hasPreedit = true))
            assertEquals(Resolution.FinishComposing, PendingInputPolicy.resolve("ja", hasPreedit = true))
            assertEquals("finishing with nothing composed is harmless", Resolution.FinishComposing,
                PendingInputPolicy.resolve("en", hasPreedit = false))
        },
    )

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

    /** On the nine keys a digit key is a code, never typed as it is, and a syllable or a letter typed as itself a private character. */
    @Test
    fun onTheNineKeysTheirKeysStayKeys() {
        val nineKeys = { c: Int -> InputMethodCatalog.readsAsKey(InputMethodCatalog.PINYIN, ShuangpinScheme.ZIRANMA, c) }
        assertFalse(PendingInputPolicy.typesAsIs(String(Character.toChars(Keyboard.pinyinNineKey('2'))), address = true, reads = nineKeys))
        assertFalse(PendingInputPolicy.typesAsIs(String(Character.toChars(Keyboard.pinyinNineKey('1'))), address = true, reads = nineKeys))
        assertFalse(PendingInputPolicy.typesAsIs("\uE000", address = true, reads = nineKeys))
        assertFalse(PendingInputPolicy.typesAsIs(String(Character.toChars(0xF0000)), address = true, reads = nineKeys))
        // a digit as the app's own: the 0 key's, the number keyboard's
        assertTrue(PendingInputPolicy.typesAsIs("2", address = true, reads = nineKeys))
        assertTrue(PendingInputPolicy.typesAsIs("_", address = true, reads = nineKeys))
    }

    /** 微软双拼's ing is `;`: put in as it is, a popup's `;` cut the syllable typed in an address field. */
    @Test
    fun inShuangpinTheSchemesOwnKeysStayKeys() {
        fun reads(im: String, scheme: ShuangpinScheme) = { c: Int -> InputMethodCatalog.readsAsKey(im, scheme, c) }
        assertFalse(PendingInputPolicy.typesAsIs(";", address = true, reads = reads(InputMethodCatalog.SHUANGPIN, ShuangpinScheme.MICROSOFT)))
        assertTrue(PendingInputPolicy.typesAsIs("_", address = true, reads = reads(InputMethodCatalog.SHUANGPIN, ShuangpinScheme.MICROSOFT)))
        // a scheme that types no ; with it, and the other input methods, take it as text
        assertTrue(PendingInputPolicy.typesAsIs(";", address = true, reads = reads(InputMethodCatalog.SHUANGPIN, ShuangpinScheme.ZIRANMA)))
        assertTrue(PendingInputPolicy.typesAsIs(";", address = true, reads = reads(InputMethodCatalog.PINYIN, ShuangpinScheme.MICROSOFT)))
        assertTrue(PendingInputPolicy.typesAsIs("2", address = true, reads = reads(InputMethodCatalog.SHUANGPIN, ShuangpinScheme.MICROSOFT)))
    }

    /** On the 14 keys a code is a key: never typed as it is in an address field (design §1.2). */
    @Test
    fun onTheFourteenKeysTheirCodesStayKeys() {
        val pinyin = { c: Int -> InputMethodCatalog.readsAsKey(InputMethodCatalog.PINYIN, ShuangpinScheme.ZIRANMA, c) }
        assertFalse(PendingInputPolicy.typesAsIs(g14("bn"), address = true, reads = pinyin))
        assertFalse(PendingInputPolicy.typesAsIs(g14("m"), address = true, reads = pinyin))
        assertTrue(PendingInputPolicy.typesAsIs("2", address = true, reads = pinyin))
        assertTrue(PendingInputPolicy.typesAsIs("_", address = true, reads = pinyin))
    }
}
