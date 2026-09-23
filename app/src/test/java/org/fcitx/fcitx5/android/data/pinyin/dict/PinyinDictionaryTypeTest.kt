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
    }

    /** A disabled LibIME dictionary keeps its type; only the `.disable` suffix marks it off. */
    @Test
    fun aDisabledLibImeDictionaryIsStillLibIme() {
        assertEquals(Type.LibIME, Type.fromFileName("words.dict.disable"))
    }

    @Test
    fun unknownExtensionsAreRejected() {
        assertNull(Type.fromFileName("words.bin"))
        assertNull(Type.fromFileName("words"))
        assertNull(Type.fromFileName(""))
    }

    /** Only LibIME has a disabled form; the others are not recognised with that suffix. */
    @Test
    fun theDisableSuffixIsNotRecognisedForOtherTypes() {
        assertNull(Type.fromFileName("words.scel.disable"))
        assertNull(Type.fromFileName("words.txt.disable"))
    }

    /**
     * Pins current behaviour, which looks like a limitation rather than a decision: a
     * dictionary exported as `WORDS.SCEL` (common on Windows) is not recognised.
     */
    @Test
    fun matchingIsCaseSensitive() {
        assertNull(Type.fromFileName("words.DICT"))
        assertNull(Type.fromFileName("words.TXT"))
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
