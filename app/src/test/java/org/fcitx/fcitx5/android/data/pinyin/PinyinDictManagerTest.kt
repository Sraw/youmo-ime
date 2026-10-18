/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream

// the real application: the manager and its error messages go through appContext
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PinyinDictManagerTest {

    // the manager's own, made once for the class: each test's application has another external dir
    private val dir get() = PinyinDictManager.pinyinDicDir

    // the dictionaries, not the dot files kept among them (.libime)
    private fun names() = dir.list()!!.filter { !it.startsWith(".") }.sorted()

    @Before
    fun empty() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        // made when the manager first ran, under that test's application: it may be gone since
        dir.mkdirs()
    }

    @Test
    fun aDictionaryWhoseExtensionIsInCapitalsIsImported() {
        val imported = PinyinDictManager.importFromInputStream("搭子 da'zi 0\n".byteInputStream(), "MINE.TXT").getOrThrow()
        assertEquals("MINE", imported.name)
        assertEquals(listOf("MINE.txt"), names())
        assertEquals(listOf("搭子 da'zi 0"), runBlocking { PinyinDictManager.readWords("搭子 da'zi 0\n".byteInputStream(), "WORDS.TXT") { it.toList() } })
    }

    @Test
    fun theStreamImportedFromIsClosedAndNothingIsLeftOfACopyThatFailed() {
        val imported = Provided("搭子 da'zi 0\n".toByteArray())
        PinyinDictManager.importFromInputStream(imported, "mine.txt").getOrThrow()
        assertTrue(imported.closed)
        val broken = Provided(null)
        assertThrows(IOException::class.java) { PinyinDictManager.importFromInputStream(broken, "theirs.txt") }
        assertTrue(broken.closed)
        assertFalse(File(RuntimeEnvironment.getApplication().cacheDir, "theirs.txt").exists())
        val cache = RuntimeEnvironment.getApplication().cacheDir
        val cached = cache.list()!!.toSet()
        val read = Provided("搭子 da'zi 0\n".toByteArray())
        runBlocking { PinyinDictManager.readWords(read, "words.txt") { it.toList() } }
        assertTrue(read.closed)
        val unread = Provided(null)
        assertThrows(IOException::class.java) { runBlocking { PinyinDictManager.readWords(unread, "words.txt") { it.toList() } } }
        assertTrue(unread.closed)
        // nor of the copy readWords makes and its text, read or not
        assertEquals(cached, cache.list()!!.toSet())
        assertEquals(listOf("mine.txt"), names())
    }

    @Test
    fun theProvidersStreamIsClosedBeforeTheWordsAreTaken() {
        val provided = Provided("搭子 da'zi 0\n".toByteArray())
        val words = runBlocking {
            PinyinDictManager.readWords(provided, "words.txt") {
                // the engine takes them on its own thread: a slow provider would hold up the keyboard
                assertTrue(provided.closed)
                it.toList()
            }
        }
        assertEquals(listOf("搭子 da'zi 0"), words)
    }

    @Test
    fun aProvidersNameIsNoPathOutOfTheCache() {
        val cache = RuntimeEnvironment.getApplication().cacheDir
        val outside = File(cache.parentFile, "outside.txt").apply { writeText("theirs") }
        try {
            val imported = PinyinDictManager.importFromInputStream("搭子 da'zi 0\n".byteInputStream(), "../outside.txt").getOrThrow()
            assertEquals("outside", imported.name)
            assertEquals("theirs", outside.readText())
            val dots = Provided("搭子 da'zi 0\n".toByteArray())
            assertTrue(PinyinDictManager.importFromInputStream(dots, "..").isFailure)
            assertTrue(dots.closed)
            assertTrue(cache.isDirectory)
            assertEquals(listOf("outside.txt"), names())
        } finally {
            outside.delete()
        }
    }

    @Test(timeout = 10_000)
    fun aConverterIsReadThroughAsItRunsAndWhatItPrintedIsTheError() {
        assumeTrue(File("/bin/sh").canExecute())
        val lib = RuntimeEnvironment.getApplication().cacheDir
        // 100 000 bytes, more than a pipe holds: read only after the wait, it would never end
        val loud = "i=0; while [ \$i -lt 2000 ]; do echo 0123456789012345678901234567890123456789012345678 >&2; i=\$((i+1)); done; exit 3"
        val e = assertThrows(IOException::class.java) { PinyinDictManager.runConverter(listOf("/bin/sh", "-c", loud), lib) }
        assertEquals(100_000, e.message!!.length)
        val path = assertThrows(IOException::class.java) {
            PinyinDictManager.runConverter(listOf("/bin/sh", "-c", "echo \"\$LD_LIBRARY_PATH\"; exit 1"), lib)
        }
        assertEquals("${lib.absolutePath}\n", path.message)
        PinyinDictManager.runConverter(listOf("/bin/sh", "-c", "exit 0"), lib)
    }

    // a word list as other input methods export them, read as the user dictionary's import reads one
    @Test
    fun aTextFileIsReadAsAWordListInTheBase() {
        PinyinDictManager.importFromInputStream("you mo 幽默 12\n搭子 da'zi -5.5\n!尬 ga\n".byteInputStream(), "pinyin-first.txt").getOrThrow()
        assertEquals(listOf("幽默 you'mo 0.0", "搭子 da'zi -5.5"), File(dir, "pinyin-first.txt").readLines())
        val sogou = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "幽默 you'mo\r\n'da'zi 搭子 3\r\n".toByteArray(Charsets.UTF_16LE)
        PinyinDictManager.importFromInputStream(sogou.inputStream(), "sogou.txt").getOrThrow()
        assertEquals(listOf("幽默 you'mo 0.0", "搭子 da'zi 0.0"), File(dir, "sogou.txt").readLines())
        val gb = "幽默 you'mo 0\n".toByteArray(charset("GB18030"))
        PinyinDictManager.importFromInputStream(gb.inputStream(), "gb.txt").getOrThrow()
        assertEquals(listOf("幽默 you'mo 0.0"), File(dir, "gb.txt").readLines())
    }

    // the list of those an older build merged into the new words goes as the dictionaries are listed
    @Test
    fun aDictionaryIsImportedAndDeleted() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "mine.txt").apply { writeText("搭子 da'zi 0\n") }
        val base = PinyinDictManager.importFromFile(file).getOrThrow()
        file.renameTo(File(file.parentFile, "theirs.txt"))
        val new = PinyinDictManager.importFromFile(File(file.parentFile, "theirs.txt")).getOrThrow()
        File(dir, ImportedDictionaries.INTO_NEW).writeText("theirs\n")
        assertTrue(PinyinDictManager.delete(new))
        assertEquals(listOf("mine"), PinyinDictManager.listDictionaries().map { it.name })
        assertFalse(File(dir, ImportedDictionaries.INTO_NEW).exists())
        assertEquals(listOf("mine.txt"), names())
        assertEquals(1, PinyinDictManager.wordCount(base))
    }

    // a provider's stream: [bytes], or none that can be read
    private class Provided(bytes: ByteArray?) : InputStream() {
        private val input = bytes?.inputStream()
        var closed = false

        override fun read(): Int = input?.read() ?: throw IOException("I/O error")

        override fun close() {
            closed = true
        }
    }
}
