/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How an imported dictionary file is classified from its name alone. */
class PinyinDictionaryTypeTest {

    @Test
    fun recognisesEachSupportedExtension() {
        assertEquals(Type.LibIME, Type.fromFileName("words.dict"))
        assertEquals(Type.Sougou, Type.fromFileName("words.scel"))
        assertEquals(Type.Text, Type.fromFileName("words.txt"))
        assertEquals(Type.Words, Type.fromFileName("new.words"))
        assertEquals(Type.Words, Type.fromFileName("new.words.disable"))
    }

    /** A dictionary the user imported is kept as text; `.disable` marks it off. */
    @Test
    fun aDisabledDictionaryIsStillText() {
        assertEquals(Type.Text, Type.fromFileName("words.txt.disable"))
    }

    @Test
    fun unknownExtensionsAreRejected() {
        assertNull(Type.fromFileName("words.bin"))
        assertNull(Type.fromFileName("words"))
        assertNull(Type.fromFileName(""))
    }

    /** Only text has a disabled form: libime's are turned into text, not kept (see ImportedDictionaries). */
    @Test
    fun theDisableSuffixIsNotRecognisedForOtherTypes() {
        assertNull(Type.fromFileName("words.scel.disable"))
        assertNull(Type.fromFileName("words.dict.disable"))
    }

    /** A dictionary exported as `WORDS.SCEL` (common on Windows) is one to import. */
    @Test
    fun theExtensionMatchesInAnyCase() {
        assertEquals(Type.LibIME, Type.fromFileName("words.DICT"))
        assertEquals(Type.Sougou, Type.fromFileName("词库.SCEL"))
        assertEquals(Type.Text, Type.fromFileName("words.TXT"))
        assertEquals(Type.Words, Type.fromFileName("NEW.Words"))
    }

    /** The files kept are named in lowercase, as the engine reads them: no other is one of them. */
    @Test
    fun aKeptFileMatchesOnlyAsItIsNamed() {
        assertNull(Type.fromFileName("words.TXT", ignoreCase = false))
        assertNull(Type.fromFileName("new.WORDS", ignoreCase = false))
        assertEquals(Type.Text, Type.fromFileName("words.txt.disable", ignoreCase = false))
        assertEquals(Type.Words, Type.fromFileName("new.words", ignoreCase = false))
    }

    @Test
    fun namesContainingAnExtensionElsewhereAreNotMatched() {
        assertNull("the extension must be at the end", Type.fromFileName("words.dict.bak"))
        assertEquals("a dot inside the stem is fine", Type.LibIME, Type.fromFileName("my.words.dict"))
    }

    @Test
    fun eachTypeCarriesItsExtension() {
        assertEquals("dict", Type.LibIME.ext)
        assertEquals("scel", Type.Sougou.ext)
        assertEquals("txt", Type.Text.ext)
    }

    @Test
    fun everyTypeIsRecognisedFromItsOwnExtension() {
        for (type in Type.entries) {
            assertEquals(type, Type.fromFileName("name.${type.ext}"))
        }
    }
}
