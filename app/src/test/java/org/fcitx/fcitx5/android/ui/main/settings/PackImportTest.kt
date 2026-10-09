/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class PackImportTest {

    @Test
    fun aPackReplacesOnlyAPackOfItsName() {
        assertEquals(PackImport.Action.IMPORT, PackImport.action(pack = true, existing = null))
        assertEquals(PackImport.Action.IMPORT, PackImport.action(pack = false, existing = null))
        assertEquals(PackImport.Action.REPLACE, PackImport.action(pack = true, existing = PinyinDictionary.Type.Words))
        assertEquals(PackImport.Action.REFUSE, PackImport.action(pack = true, existing = PinyinDictionary.Type.Text))
        assertEquals(PackImport.Action.REFUSE, PackImport.action(pack = false, existing = PinyinDictionary.Type.Words))
    }

    @Test
    fun aPackIsNamedWithoutWhatABrowserAddedToTheFile() {
        assertEquals("youmo-new", PackImport.name("youmo-new.words"))
        assertEquals("youmo-new", PackImport.name("youmo-new (1).words"))
        assertEquals("youmo-new", PackImport.name("youmo-new(2).txt"))
        assertEquals("youmo-new", PackImport.name("youmo-new.words.txt"))
        assertEquals("youmo-new", PackImport.name("youmo-new.WORDS.txt"))
        assertEquals("youmo-new", PackImport.name("youmo-new.words (1).txt"))
        assertEquals("2026q1", PackImport.name("2026q1.words"))
        assertEquals("(1)", PackImport.name("(1).words"))
    }

    @Test
    fun aPackIsToldByItsFirstLineWhateverTheFileIsCalled() {
        assertTrue(PackImport.isPack("\uFEFF# youmo words 1\r\n# layer: new\n搭子 da'zi -5.6\n".byteInputStream()))
        assertFalse(PackImport.isPack("你 ni 0\n".byteInputStream()))
        assertFalse(PackImport.isPack("".byteInputStream()))
        // a file with no line break is not read whole for its first line
        val endless = object : InputStream() {
            override fun read() = 'a'.code
        }
        assertFalse(PackImport.isPack(endless))
    }
}
