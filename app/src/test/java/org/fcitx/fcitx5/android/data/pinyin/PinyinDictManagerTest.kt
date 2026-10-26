/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat
import org.fcitx.fcitx5.android.utils.errorArg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.util.zip.GZIPInputStream

// the real application: the manager and its error messages go through appContext
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PinyinDictManagerTest {

    // the manager's own, made once for the class: each test's application has another external dir
    private val dir get() = PinyinDictManager.pinyinDicDir

    // the dictionaries, not the dot files kept among them (.libime) nor the file compiled from them
    private fun names() = dir.list()!!.filter { !it.startsWith(".") && !MergedDictionary.isCompiled(File(dir, it)) }.sorted()

    @Before
    fun empty() {
        MergedDictionary.reset()
        MergedDictionary.made = {}
        MergedDictionary.base = { null }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        // made when the manager first ran, under that test's application: it may be gone since
        dir.mkdirs()
    }

    @Test
    fun aDictionaryWhoseExtensionIsInCapitalsIsImported() {
        val imported = PinyinDictManager.importFromInputStream("搭子 da'zi 0\n".byteInputStream(), "MINE.TXT").getOrThrow()
        assertEquals("MINE", imported.name)
        assertEquals(listOf("MINE.txt.gz"), names())
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
        assertEquals(listOf("mine.txt.gz"), names())
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
            // a picked .gz is no dictionary either, though they are kept so (S4)
            val gz = Provided("搭子 da'zi 0\n".toByteArray())
            assertTrue(PinyinDictManager.importFromInputStream(gz, "words.txt.gz").isFailure)
            assertTrue(gz.closed)
            assertTrue(cache.isDirectory)
            assertEquals(listOf("outside.txt.gz"), names())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun aSogouCellDictionaryBecomesScel2org5sTextAndABrokenOneFails() {
        // relative to app/, where Gradle runs the tests; an IDE's other working directory would read as a converter failure
        val scel = File("../uat/fixtures/scel")
        check(scel.isDirectory) { "fixtures not found at ${scel.absolutePath}: run with app/ as the working directory" }
        val dest = File(RuntimeEnvironment.getApplication().cacheDir, "extra.txt")
        PinyinDictManager.sougouDictConv(File(scel, "extra.scel"), dest)
        assertEquals(File(scel, "extra.txt").readText(), dest.readText())
        assertThrows(IOException::class.java) { PinyinDictManager.sougouDictConv(File(scel, "truncated.scel"), dest) }
        // the half written, not the call before's output left in its place (F3793)
        assertFalse(dest.exists())
    }

    // a word list as other input methods export them, its layout told from the whole file (D6)
    @Test
    fun aTextFileIsReadInTheLayoutItHas() {
        PinyinDictManager.importFromInputStream("you mo 幽默 12\nda zi 搭子 3\n".byteInputStream(), "pinyin-first.txt").getOrThrow()
        assertEquals(listOf("幽默 you'mo 12.0", "搭子 da'zi 3.0"), lines(File(dir, "pinyin-first.txt.gz")))
        val sogou = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "'you'mo 幽默 1\r\n'da'zi 搭子 3\r\n".toByteArray(Charsets.UTF_16LE)
        PinyinDictManager.importFromInputStream(sogou.inputStream(), "sogou.txt").getOrThrow()
        assertEquals(listOf("幽默 you'mo 1.0", "搭子 da'zi 3.0"), lines(File(dir, "sogou.txt.gz")))
        val gb = "幽默 you'mo 0\n".toByteArray(charset("GB18030"))
        PinyinDictManager.importFromInputStream(gb.inputStream(), "gb.txt").getOrThrow()
        assertEquals(listOf("幽默 you'mo 0.0"), lines(File(dir, "gb.txt.gz")))
        // the three compiled into one file, each counted
        assertEquals(mapOf("gb" to 1, "pinyin-first" to 2, "sogou" to 2), MergedDictionary.sourceWords(dir))
    }

    // never imported half right: a file of two layouts, or initials only, is refused and nothing kept
    @Test
    fun aFileNotSurelyReadIsRefusedWithTheReason() {
        val cache = RuntimeEnvironment.getApplication().cacheDir.list()!!.toSet()
        val mixed = PinyinDictManager.importFromInputStream("you mo 幽默\n搭子 da'zi\n".byteInputStream(), "mixed.txt")
        assertEquals(DictionaryFormat.Reason.MIXED, (mixed.exceptionOrNull() as PinyinDictManager.Refusal).reason)
        val initials = PinyinDictManager.importFromInputStream("幽默 ym\n搭子 dz\n".byteInputStream(), "initials.txt")
        assertEquals(DictionaryFormat.Reason.INITIALS, (initials.exceptionOrNull() as PinyinDictManager.Refusal).reason)
        assertEquals(emptyList<String>(), names())
        assertEquals(cache, RuntimeEnvironment.getApplication().cacheDir.list()!!.toSet())
    }

    // one of a name there, on or off, is not imported over (ImportedDictionaries.hasDictionary): its
    // text and the merged file as they were (review S7)
    @Test
    fun oneOfANameThereOnOrOffIsNotImportedOver() {
        val mine = PinyinDictManager.importFromInputStream("幽默 you'mo\n".byteInputStream(), "mine.txt").getOrThrow()
        for (on in listOf(true, false)) {
            if (!on) assertTrue(PinyinDictManager.turn(mine, false))
            MergedDictionary.refreshNow(dir)
            val files = names()
            val merged = File(dir, MergedDictionary.FILE).takeIf { it.exists() }?.readBytes()
            val again = PinyinDictManager.importFromInputStream("搭子 da'zi\n".byteInputStream(), "mine.txt")
            assertTrue("on=$on", again.isFailure)
            assertEquals("on=$on", files, names())
            assertEquals("on=$on", listOf("幽默 you'mo 0.0"), lines(File(dir, files.single())))
            assertEquals("on=$on", merged?.toList(), File(dir, MergedDictionary.FILE).takeIf { it.exists() }?.readBytes()?.toList())
        }
    }

    // the page's turning off and on: the merged file made again without it, its count read from the file
    @Test
    fun turningOneOffCompilesTheRestWithoutIt() {
        val mine = PinyinDictManager.importFromInputStream("幽默 you'mo\n搭子 da'zi\n".byteInputStream(), "mine.txt").getOrThrow()
        PinyinDictManager.importFromInputStream("你好 ni'hao\n".byteInputStream(), "theirs.txt").getOrThrow()
        assertEquals(2, PinyinDictManager.wordCount(mine))
        assertTrue(PinyinDictManager.turn(mine, false))
        MergedDictionary.refreshNow(dir)
        assertEquals(mapOf("theirs" to 1), MergedDictionary.sourceWords(dir))
        // off: counted from its text, listed again as the page does after a change (turned by name, the row is the page's as listed)
        assertEquals(2, PinyinDictManager.wordCount(PinyinDictManager.listDictionaries().single { it.name == "mine" }))
        assertTrue(PinyinDictManager.turn(mine, true))
        MergedDictionary.refreshNow(dir)
        assertEquals(3, MergedDictionary.current(dir).file!!.size)
    }

    // an import that does not end leaves nothing turned on to be compiled silently later: the
    // directory as it was. Each row: how it fails, and what it fails with
    @Test
    fun anImportThatFailsLeavesNothingBehind() {
        PinyinDictManager.importFromInputStream("你好 ni'hao\n".byteInputStream(), "theirs.txt").getOrThrow()
        class Row(val why: String, val cancel: Boolean, val base: MergedDictionary.Base?, val thrown: Class<out Throwable>)
        val rows = listOf(
            // its compile fails (the disk full) (review M2)
            Row("compile failed", cancel = false, MergedDictionary.Base("full") { throw IOException("no space left") }, IOException::class.java),
            // cancelled before the compile, while the file is written (review M1)
            Row("cancelled", cancel = true, null, CancellationException::class.java),
        )
        val noBase = MergedDictionary.base
        for (row in rows) {
            val before = names()
            MergedDictionary.base = noBase
            val prepared = PinyinDictManager.prepare("幽默 you'mo\n搭子 da'zi\n".byteInputStream(), "mine.txt")
            // set after prepare, which reads the base's readings too: the failure is the compile's
            MergedDictionary.base = { row.base }
            val failed = prepared.use { PinyinDictManager.importPrepared(it, "mine", AtomicBoolean(row.cancel)) }
            assertTrue(row.why, row.thrown.isInstance(failed.exceptionOrNull()))
            assertEquals(row.why, before, names())
        }
    }

    // one of its name that comes as it is written (another import's, a restore) is told 词库已存在,
    // not the commit's raw IOException, and is left as it is (F3603, F3654)
    @Test
    fun aNameTakenAsItIsWrittenIsToldAlreadyThere() {
        val prepared = PinyinDictManager.prepare("幽默 you'mo\n".byteInputStream(), "mine.txt")
        val theirs = File(dir, TextDictionary.fileName("mine", false))
        val failed = prepared.use {
            PinyinDictManager.importPrepared(it, "mine") { if (!theirs.exists()) theirs.writeText("theirs") }
        }
        // the same message errorArg gives, with app resources or without (F3671)
        val expected = runCatching { errorArg(R.string.dict_already_exists) }.exceptionOrNull()?.message
        assertEquals(expected, failed.exceptionOrNull()?.message)
        assertEquals("theirs", theirs.readText())
        theirs.delete()
    }

    // turning one on that would take the merged words past what the compiler takes is refused, left
    // off (review S5); a limit of 3 here, not the compiler's millions written and compiled. Each row:
    // how those already on are counted (review F3405)
    @Test
    fun turningOnPastTheLimitIsRefused() {
        class Row(val why: String, val compiledFirst: Boolean)
        val rows = listOf(
            // the merged file's header
            Row("counted from the merged file", compiledFirst = true),
            // not made yet (0.3.1's upgrade under way, or failed): from their texts, not as none (review NIT, F3386)
            Row("counted from the texts, the merged file not made", compiledFirst = false),
        )
        assertRows(rows, { it.why }) { row ->
            empty()
            val mine = PinyinDictManager.importFromInputStream("幽默 you'mo\n".byteInputStream(), "mine.txt").getOrThrow()
            assertTrue(row.why, PinyinDictManager.turn(mine, false))
            if (!row.compiledFirst) MergedDictionary.refreshNow(dir)
            File(dir, "big.txt").writeText("搭子 da'zi 0.0\n妮 ni 0.0\n你 ni 0.0\n")
            if (row.compiledFirst) MergedDictionary.refreshNow(dir)
            assertEquals(row.why, row.compiledFirst, File(dir, MergedDictionary.FILE).exists())
            val off = PinyinDictManager.listDictionaries().single { it.name == "mine" }
            val refused = runCatching { PinyinDictManager.turn(off, true, max = 3) }.exceptionOrNull()
            assertEquals(row.why, DictionaryFormat.Reason.TOO_MANY, (refused as? PinyinDictManager.Refusal)?.reason)
            assertFalse(row.why, PinyinDictManager.listDictionaries().single { it.name == "mine" }.isEnabled)
            // up to the limit: turned on
            assertTrue(row.why, PinyinDictManager.turn(off, true, max = 4))
        }
    }

    // the user's export put into the base: its learned words (`~`) are words there too, read only
    // after the export's mark (WordListFiles.baseLines, not baseLine line by line)
    @Test
    fun anExportsLearnedWordsAreReadIntoTheBase() {
        val export = "\n# youmo-words 1\n# header\n幽默 you'mo\n~ 这个誓言很难 zhe'ge'shi'yan'hen'nan 3.0\n~搭子 da'zi 2\n!尬 ga\n"
        PinyinDictManager.importFromInputStream(export.byteInputStream(), "export.txt").getOrThrow()
        assertEquals(
            listOf("幽默 you'mo 0.0", "这个誓言很难 zhe'ge'shi'yan'hen'nan 0.0", "搭子 da'zi 0.0"),
            lines(File(dir, "export.txt.gz")),
        )
        // in another list a learned word is no line it reads: half of it unread, refused
        val list = PinyinDictManager.importFromInputStream("幽默 you'mo\n~ 搭子 da'zi 2\n".byteInputStream(), "list.txt")
        assertEquals(DictionaryFormat.Reason.UNREADABLE, (list.exceptionOrNull() as PinyinDictManager.Refusal).reason)
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
        assertEquals(listOf("mine.txt.gz"), names())
        assertEquals(1, PinyinDictManager.wordCount(base))
    }

    // 0.3.2's texts: listed as they are, counted toward the limit while not converted (RM1), and
    // the page's row, listed before the conversion, turned and deleted by its name after it (RS1)
    @Test
    fun anOlderBuildsTextIsListedCountedAndActedOnByName() {
        File(dir, "old.txt").writeText("搭子 da'zi 0.0\n妮 ni 0.0\n")
        File(dir, "off.txt.disable").writeText("你 ni 0.0\n")
        val listed = PinyinDictManager.listDictionaries()
        assertEquals(listOf("off.txt.disable", "old.txt"), listed.map { it.file.name })
        val off = listed.first()
        val refused = runCatching { PinyinDictManager.turn(off, true, max = 2) }.exceptionOrNull()
        assertEquals(DictionaryFormat.Reason.TOO_MANY, (refused as? PinyinDictManager.Refusal)?.reason)
        MergedDictionary.refreshNow(dir)
        assertEquals(listOf("off.txt.gz.disable", "old.txt.gz"), names())
        // counted by its name as it is now, not as none from the text the page listed (F3591)
        val again = runCatching { PinyinDictManager.turn(off, true, max = 2) }.exceptionOrNull()
        assertEquals(DictionaryFormat.Reason.TOO_MANY, (again as? PinyinDictManager.Refusal)?.reason)
        // a text and its gzip of one name (a kill between the rename and the delete): counted once, 2 + 1 (F3595)
        File(dir, "old.txt").writeText("搭子 da'zi 0.0\n妮 ni 0.0\n")
        assertTrue(PinyinDictManager.turn(off, true, max = 3))
        assertTrue(PinyinDictManager.delete(listed.last()))
        MergedDictionary.refreshNow(dir)
        assertEquals(listOf("off.txt.gz"), names())
        assertEquals(mapOf("off" to 1), MergedDictionary.sourceWords(dir))
    }

    // an import while a conversion runs: its file committed mid-gzip, the import done after, no deadlock
    // on the sources lock. Fails if the conversion deflated under the lock (the commit never comes), or
    // the import waited on the compile inside it (the conversion's commit never comes) (RS2, F3605)
    @Test(timeout = 30_000)
    fun anImportWhileAConversionRunsEnds() {
        File(dir, "old.txt").writeText((1..20_000).joinToString("") { "词$it ci 0.0\n" })
        val imported = File(dir, "new.txt.gz")
        val result = AtomicReference<Result<TextDictionary>>()
        // asserted here, not on the compile thread: refresh logs what its compile throws
        val committedMidway = AtomicBoolean()
        var importing: Thread? = null
        MergedDictionary.told = {
            if (it.stage == MergedDictionary.Step.CONVERT && it.percent > 0 && importing == null) {
                importing = thread { result.set(PinyinDictManager.importFromInputStream("搭子 da'zi 0\n".byteInputStream(), "new.txt")) }
                val until = System.currentTimeMillis() + COMMIT_WAIT_MS
                while (!imported.exists() && System.currentTimeMillis() < until) Thread.sleep(10)
                committedMidway.set(imported.exists())
            }
        }
        MergedDictionary.refresh(dir)!!.get()
        importing!!.join(COMMIT_WAIT_MS)
        assertTrue("the import's commit waited on the conversion", committedMidway.get())
        result.get().getOrThrow()
        assertEquals(setOf("new", "old"), MergedDictionary.sourceWords(dir).keys)
    }

    private fun lines(file: File) = GZIPInputStream(file.inputStream()).bufferedReader().readLines()

    // a provider's stream: [bytes], or none that can be read
    private class Provided(bytes: ByteArray?) : InputStream() {
        private val input = bytes?.inputStream()
        var closed = false

        override fun read(): Int = input?.read() ?: throw IOException("I/O error")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val COMMIT_WAIT_MS = 5_000L
    }
}
