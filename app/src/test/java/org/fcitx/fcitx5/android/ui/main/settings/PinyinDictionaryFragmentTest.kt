/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries.Unread
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.ImportLayer
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.backup.BackupHead
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class PinyinDictionaryFragmentTest {

    @get:Rule
    val folder = TemporaryFolder()

    // another input method's .scel for the user dictionary, read in ime-core (ImportFiles.Scel)
    @Test
    fun aCellDictionaryForTheUserDictionaryReachesTheEngineAsLines() = runTest {
        val scel = ByteArray(0x1540).also {
            byteArrayOf(0x40, 0x15, 0, 0, 0x44, 0x43, 0x53, 0x01, 0x01, 0, 0, 0).copyInto(it)
            it[0x120] = 1
        } + le(0, 0, 1, 4, 0, 1, 4) + le(0x9cb8, 0x56ed) + le(2, 120)
        var got: List<String>? = null
        importWordsFrom("鲸.scel", { scel.inputStream() }) { lines ->
            got = lines.toList()
            Engines.Imported(0, 0, 0, imported = 1)
        }
        // u32 0 syllables; 1 word of a's (0) and ai's (1) syllables, "鲸园", 2 bytes more: its count
        assertEquals(listOf("鲸园 a'ai 120"), got)
    }

    // 16-bit little-endian values, but the first two: a u32 0, the table's size
    private fun le(vararg v: Int) = v.flatMap { listOf((it and 0xff).toByte(), (it shr 8).toByte()) }.toByteArray()

    @Test
    fun theListsImportedShowNewestFirstButThoseWithNothingLeftToUndo() {
        val a = ImportLayer.Batch(1, "a.txt", 20000, 3, 3)
        val b = ImportLayer.Batch(2, "b.scel", 20001, 5, 2)
        val all = ImportLayer.Batch(3, "c.txt", 20002, 4, 0)
        assertEquals(listOf(b, a), shownBatches(listOf(a, b, all)))
    }

    // past 100,000 user words in all: refused before any is read in
    @Test
    fun aFileOfTooManyWordsIsNoImportForTheUserDictionary() {
        fun words(analysis: DictionaryFormat.Analysis?) = wordsForUser(analysis) { error("a detected file's lines are not read again") }
        assertFalse(tooLargeForUser(USER_MAX_WORDS, 0))
        assertTrue(tooLargeForUser(USER_MAX_WORDS + 1, 0))
        // words without pinyin are the user dictionary's too: counted
        val textOnly = DictionaryFormat.analyse({ "你好 ni'hao\n幽默\n搭子\n".byteInputStream() })
        assertEquals(2, (textOnly as DictionaryFormat.Detected).textOnly)
        assertEquals(3, words(textOnly))
        // the total: ten imports under the cap each are not ten times it
        assertTrue(tooLargeForUser(10, USER_MAX_WORDS - 9))
        assertFalse(tooLargeForUser(10, USER_MAX_WORDS - 10))
        assertEquals(R.string.dict_user_imports_full, userCapMessage(USER_MAX_WORDS))
        assertEquals(R.string.dict_too_large_for_user, userCapMessage(USER_MAX_WORDS + 1))
        // refused for the base, still read by the user dictionary: its lines counted
        val mixed = DictionaryFormat.Refused(DictionaryFormat.Reason.MIXED)
        assertEquals(7, wordsForUser(mixed) { 7 })
        val file = folder.newFile("mixed.txt").apply { writeText("# head\n\nyou mo 幽默\n搭子 da'zi\n") }
        assertEquals(2, countLines(file))
        // the user's own export, not analysed: as it was, past the cap or not
        assertEquals(0, wordsForUser(null) { error("not read") })
        assertFalse(tooLargeForUser(0, USER_MAX_WORDS + 5))
    }

    // `.dict.yaml` alone, or a dot-name, names no base file
    @Test
    fun aNameOfNothingIsNoDictionaryOfTheBase() {
        assertFalse(Incoming(".dict.yaml").namedForBase)
        assertFalse(Incoming(".hidden.txt").namedForBase)
        assertTrue(Incoming("luna_pinyin.dict.yaml").namedForBase)
        assertTrue(Incoming("mine.txt").namedForBase)
    }

    // Sogou's UTF-16 list, which a conversion reads as UTF-8; any name
    @Test
    fun aTextFileForTheUserDictionaryIsReadAsAWordList() {
        assertTrue(readAsWordList("youmo-words-20261018.txt"))
        assertTrue(readAsWordList("MINE.TXT"))
        assertTrue(readAsWordList("words.csv"))
        assertTrue(readAsWordList("words"))
        assertFalse(readAsWordList("mine.scel"))
        assertFalse(readAsWordList("mine.dict"))
    }

    @Test
    fun theUserDictionaryIsPickedForItsExportAndForAnyOtherText() {
        assertTrue(startsInUser(Incoming("youmo-words.txt", userExport = true)))
        assertTrue(startsInUser(Incoming("words.csv")))
        assertTrue(startsInUser(Incoming("words")))
        assertFalse(startsInUser(Incoming("mine.TXT")))
        assertFalse(startsInUser(Incoming("mine.dict")))
        assertFalse(startsInUser(Incoming("mine.scel")))
        assertFalse(startsInUser(Incoming("luna_pinyin.Dict.YAML")))
        assertEquals("luna_pinyin", Incoming("luna_pinyin.dict.yaml").name)
        assertTrue(startsInUser(Incoming("config.yaml")))
        // a name no base file can have goes to the user dictionary first
        class Row(val name: String, val base: Boolean, val why: String)
        val rows = listOf(
            Row("words.csv", base = false, "no dictionary's type"),
            Row(".hidden.txt", base = false, "hidden: no base file's name"),
            Row(".dict.yaml", base = false, "Rime's extensions with no name before them"),
            Row("mine.txt", base = true, "plain text"),
            Row("luna.dict.yaml", base = true, "Rime's dictionary"),
        )
        assertRows(rows, { it.why }) { row ->
            assertEquals(row.name, row.base, offersBase(Incoming(row.name)))
            assertEquals(row.name, !row.base, startsInUser(Incoming(row.name)))
        }
    }

    // the user dictionary's export is a learning backup, restored as one; an older text export is still words
    @Test
    fun theExportIsKnownAsABackupAndAnEarlierOneAsWords() {
        assertTrue(readAsWordList("youmo-words-20261021${LearnBackup.EXTENSION}"))
        val backup = LearnBackup.bytes(BackupHead(0, 1, 2L, BackupHead.SAVED), LearnState(), emptyList(), emptyList())
        val exported = incoming("youmo-words-20261021${LearnBackup.EXTENSION}", backup)
        assertTrue(exported.backup)
        assertFalse(exported.userExport)
        val earlier = incoming("youmo-words.txt", "# youmo-words 1\n幽默 you'mo\n".toByteArray())
        assertTrue(earlier.userExport)
        assertFalse(earlier.backup)
        assertFalse(incoming("mine.dict", null).backup)
        // renamed by a chat app: still a backup
        assertTrue(incoming("youmo-words.dict", backup).backup)
        assertFalse(incoming("youmo-words.dict", "# youmo-words 1\n幽默 you'mo\n".toByteArray()).userExport)
        // named as one but cut short: never offered as words
        assertFalse(exported.brokenBackup)
        assertTrue(incoming("youmo-words-20261021${LearnBackup.EXTENSION.uppercase()}", backup.copyOf(3)).brokenBackup)
        assertFalse(incoming("words.txt", "a".toByteArray()).brokenBackup)
    }

    @Test
    fun aWordListForTheUserDictionaryReachesTheEngineDecoded() = runTest {
        var got: List<String>? = null
        val imported = importWordsFrom("sogou.txt", { utf16("幽默 you'mo\r\nyou mo 幽默\r\n").inputStream() }) { lines ->
            got = lines.toList()
            Engines.Imported(2, 0, 0)
        }
        assertEquals(Engines.Imported(2, 0, 0), imported)
        assertEquals(listOf("幽默 you'mo", "you mo 幽默"), got!!.map { it.trim() }.filter { it.isNotEmpty() })
    }

    private fun utf16(text: String) = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    @Test
    fun nothingPutAsideUnreadIsNoNotice() {
        assertNull(unreadNotice(emptyList(), "intro", "outro") { it.fileName })
    }

    @Test
    fun eachDictionaryPutAsideUnreadIsALineOfTheNotice() {
        val unread = listOf(Unread("zhwiki.dict", Unread.Reason.TOO_LARGE), Unread("old.dict.disable", Unread.Reason.DAMAGED))
        assertEquals(
            "intro\nzhwiki.dict TOO_LARGE\nold.dict.disable DAMAGED\noutro",
            unreadNotice(unread, "intro", "outro") { "${it.fileName} ${it.reason}" },
        )
    }

    @Test
    fun aDictionaryIsCountedAgainOnlyOnceItsFileChanged() {
        var read = 0
        val counts = WordCounts { read++; it.file.readLines().size }
        val dictionary = TextDictionary(folder.newFile("mine.txt").apply { writeText("你 ni 0\n") })
        assertEquals(listOf(1), counts.of(listOf(dictionary)))
        assertTrue(dictionary.disable())
        assertEquals(listOf(1), counts.of(listOf(dictionary)))
        assertEquals(1, read)
        dictionary.file.appendText("好 hao 0\n")
        assertEquals(listOf(2), counts.of(listOf(dictionary)))
        assertEquals(2, read)
    }

    @Test
    fun oneNoLongerListedIsCountedAnewShouldItReturn() {
        var read = 0
        val counts = WordCounts { read++; 1 }
        val kept = TextDictionary(folder.newFile("kept.txt"))
        val gone = TextDictionary(folder.newFile("gone.txt"))
        counts.of(listOf(kept, gone))
        counts.of(listOf(kept))
        counts.of(listOf(kept, gone))
        assertEquals(3, read)
    }

    private class Changes(scope: CoroutineScope) {
        val made = mutableListOf<String>()
        val failures = mutableListOf<String?>()
        val changes = DictionaryChanges({ block -> scope.launch { block() } }, EmptyCoroutineContext) { failures += it.message }

        fun change(name: String, after: CompletableDeferred<Unit>? = null, failure: Throwable? = null) =
            changes.change({
                after?.await()
                made += name
                if (failure == null) Result.success(Unit) else Result.failure(failure)
            }) { made += "listed" }
    }

    @Test
    fun eachChangeIsMadeOnceTheOneBeforeItIs() = runTest {
        val c = Changes(backgroundScope)
        val first = CompletableDeferred<Unit>()
        c.change("first", after = first)
        c.change("second")
        runCurrent()
        assertEquals(emptyList<String>(), c.made)
        first.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "listed", "second", "listed"), c.made)
    }

    @Test
    fun theEngineReadsThemAgainOnceTheLastChangeIsMade() = runTest {
        val c = Changes(backgroundScope)
        assertNull("nothing changed", c.changes.left())
        val last = CompletableDeferred<Unit>()
        c.change("first")
        c.change("last", after = last)
        val reload = c.changes.left()!!
        assertNull("once for the changes made before", c.changes.left())
        val fcitx = FakeHostApi()
        backgroundScope.launch { fcitx.reload() }
        runCurrent()
        assertEquals(emptyList<String>(), fcitx.calls)
        last.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "listed", "last", "listed"), c.made)
        assertEquals(listOf("reloadEngine()"), fcitx.calls)
    }

    @Test
    fun aChangeThatFailedIsToldAndTheNextIsStillMade() = runTest {
        val c = Changes(backgroundScope)
        c.change("broken", failure = IOException("cannot rename"))
        c.change("next")
        runCurrent()
        assertEquals(listOf<String?>("cannot rename"), c.failures)
        assertEquals(listOf("broken", "listed", "next", "listed"), c.made)
    }

    @Test
    fun aPageMadeAnewListsThemOnceTheChangesOfTheOneItReplacedAreMade() = runTest {
        val c = Changes(backgroundScope)
        c.changes.awaitMade()
        val pending = CompletableDeferred<Unit>()
        c.change("before", after = pending)
        backgroundScope.launch {
            c.changes.awaitMade()
            c.made += "listed anew"
        }
        runCurrent()
        assertEquals(emptyList<String>(), c.made)
        pending.complete(Unit)
        runCurrent()
        assertEquals(listOf("before", "listed", "listed anew"), c.made)
    }

    private class WordsRow(val why: String, val missing: Missing, val words: Int, val shown: WordsShown)

    @Test
    fun eachRowSaysWhatOfItsWordsIsIn() = assertRows(
        listOf(
            WordsRow("nothing missing: its count", Missing.NONE, 12, WordsShown.COUNT),
            WordsRow("nothing missing, none counted: still its count (0)", Missing.NONE, 0, WordsShown.COUNT),
            // the words before the damage are in the merged file, counted in its header
            WordsRow("a gzip damaged past some words: damaged, with them", Missing.UNREADABLE, 12, WordsShown.DAMAGED),
            // unreadable text, or a gzip bad from its start (its count throws: 0)
            WordsRow("unreadable with none counted: unreadable", Missing.UNREADABLE, 0, WordsShown.UNREADABLE),
            WordsRow("not converted for want of space: said so, whatever counted", Missing.UNCONVERTED, 12, WordsShown.UNCONVERTED),
        ),
        { it.why },
    ) { assertEquals(it.shown, wordsShown(it.missing, it.words)) }
}
