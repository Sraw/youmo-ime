/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // the dictionaries, not the dot file of which are merged into the new words
    private fun names() = dir.list()!!.filter { !it.startsWith(".") }.sorted()

    @Before
    fun empty() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun aPackReplacesTheLastOfItsNameOnOrOffAsItWas() {
        val first = PinyinDictManager.importPack("2026q1", pack).getOrThrow()
        assertEquals(PinyinDictionary.Type.Words, first.type)
        assertEquals(listOf("2026q1.words"), names())
        // new words: merged into the new words dictionary
        assertTrue(PinyinDictManager.isIntoNew("2026q1"))
        PinyinDictManager.setIntoNew("2026q1", false).getOrThrow()
        assertTrue(first.disable())
        PinyinDictManager.importPack("2026q1", pack + "智驾 zhi'jia -5.9\n").getOrThrow()
        assertEquals(listOf("2026q1.words.disable"), names())
        assertTrue(File(dir, "2026q1.words.disable").readText().contains("智驾"))
        // the next one stays where the user moved the last
        assertFalse(PinyinDictManager.isIntoNew("2026q1"))
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

    @Test
    fun aDictionaryWhoseExtensionIsInCapitalsIsImported() {
        val imported = PinyinDictManager.importFromInputStream("搭子 da'zi 0\n".byteInputStream(), "MINE.TXT").getOrThrow()
        assertEquals("MINE", imported.name)
        assertEquals(listOf("MINE.txt"), names())
        assertEquals(listOf("搭子 da'zi 0"), PinyinDictManager.readWords("搭子 da'zi 0\n".byteInputStream(), "WORDS.TXT").getOrThrow())
    }

    @Test
    fun aPackFirstImportedUnderAnyNameIsReplacedByItsNext() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "新词 2026.words").apply { writeText(pack) }
        PinyinDictManager.importFromFile(file).getOrThrow()
        PinyinDictManager.importPack("新词 2026", pack + "智驾 zhi'jia -5.9\n").getOrThrow()
        assertEquals(listOf("新词 2026.words"), names())
        assertTrue(File(dir, "新词 2026.words").readText().contains("智驾"))
    }

    @Test
    fun packsFromBeforeTheLayersWereChosenStayWhereTheirHeadersPutThem() {
        File(dir, "cloud.words").writeText("# youmo words 1\n# layer: new\n搭子 da'zi -5.6\n")
        File(dir, "plain.words.disable").writeText("# youmo words 1\n# layer: base\n搭子 da'zi -5.6\n")
        File(dir, "mine.txt").writeText("搭子 da'zi 0\n")
        assertEquals(setOf("cloud"), ImportedDictionaries.intoNew(dir))
        // the first change keeps them so
        PinyinDictManager.setIntoNew("plain", true).getOrThrow()
        assertEquals(setOf("cloud", "plain"), ImportedDictionaries.intoNew(dir))
    }

    @Test
    fun aDictionaryIsMergedWhereAskedAndForgottenWhenDeleted() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "mine.txt").apply { writeText("搭子 da'zi 0\n") }
        val base = PinyinDictManager.importFromFile(file).getOrThrow()
        assertFalse(PinyinDictManager.isIntoNew("mine"))
        file.renameTo(File(file.parentFile, "theirs.txt"))
        val new = PinyinDictManager.importFromFile(File(file.parentFile, "theirs.txt"), intoNew = true).getOrThrow()
        assertTrue(PinyinDictManager.isIntoNew("theirs"))
        assertEquals(setOf("theirs"), ImportedDictionaries.intoNew(dir))
        assertTrue(PinyinDictManager.delete(new))
        assertEquals(emptySet<String>(), ImportedDictionaries.intoNew(dir))
        assertEquals(listOf("mine.txt"), names())
        assertEquals(1, PinyinDictManager.wordCount(base))
    }
}
