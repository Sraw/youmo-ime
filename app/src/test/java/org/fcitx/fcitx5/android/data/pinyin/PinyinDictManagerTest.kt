/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

// the real application: the manager and its error messages go through appContext
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PinyinDictManagerTest {

    // the manager's own, made once for the class: each test's application has another external dir
    private val dir get() = PinyinDictManager.pinyinDicDir
    private val pack = "# youmo words 1\n# layer: 2026q1\n搭子 da'zi -5.6\n"

    private fun names() = dir.list()!!.sorted()

    @Before
    fun empty() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun aPackFromTheServerReplacesTheLastOfItsNameOnOrOffAsItWas() {
        val first = PinyinDictManager.importPack("2026q1", pack).getOrThrow()
        assertEquals(PinyinDictionary.Type.Words, first.type)
        assertEquals(listOf("2026q1.words"), names())
        assertTrue(first.disable())
        PinyinDictManager.importPack("2026q1", pack + "智驾 zhi'jia -5.9\n").getOrThrow()
        assertEquals(listOf("2026q1.words.disable"), names())
        assertTrue(File(dir, "2026q1.words.disable").readText().contains("智驾"))
        // the imported dictionaries' list has it
        assertEquals(listOf("2026q1"), PinyinDictManager.listDictionaries().map { it.name })
    }

    @Test
    fun aPackThatIsNoPackOrNamedLikeAPlainDictionaryIsRefused() {
        assertTrue(PinyinDictManager.importPack("../x", pack).isFailure)
        assertTrue(PinyinDictManager.importPack("bad", "你 ni 0\n").isFailure)
        assertTrue(PinyinDictManager.importPack("empty", "# youmo words 1\n").isFailure)
        File(dir, "mine.txt").writeText("你 ni 0\n")
        assertTrue(PinyinDictManager.importPack("mine", pack).isFailure)
        assertEquals(listOf("mine.txt"), names())
    }

    @Test
    fun aTextFileThatStartsAsAPackIsImportedAsOne() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "new.txt").apply { writeText("\uFEFF$pack") }
        val imported = PinyinDictManager.importFromFile(file).getOrThrow()
        assertEquals(PinyinDictionary.Type.Words, imported.type)
        assertEquals("new", imported.name)
        assertEquals(pack, imported.file.readText())
        // a second of the name, as a plain dictionary, is refused
        file.writeText("你 ni 0\n")
        assertTrue(PinyinDictManager.importFromFile(file).isFailure)
    }
}
