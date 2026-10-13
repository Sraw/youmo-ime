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
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries.Unread
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A file is imported into the layer asked. The dictionaries the migration put aside unread are
 * told of: they left the list without a word. A file refused is not read; a dictionary's words
 * are counted again only once its file changed. The changes to the files are made in turn, and
 * the engine reads them again once the last is made: on the page's scope, one still waiting as
 * the page was left was cancelled, and the engine read them without it.
 * A page made anew (a rotation) lists them once the changes of the one it replaced are made:
 * those were listed again on the page gone, the new one left showing what was before them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinyinDictionaryFragmentTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Dictionaries where the one of an imported file's name is of type [existing] (none for null); each call noted in [done]. */
    private class Recorded(private val existing: PinyinDictionary.Type?) : ImportTarget {
        val done = mutableListOf<String>()

        override fun typeOf(name: String) = existing

        override fun import(stream: InputStream, fileName: String, intoNew: Boolean) {
            done += "import $fileName ${stream.reader().readText()} intoNew=$intoNew"
        }
    }

    @Test
    fun aFileOfANewNameIsImportedIntoTheLayerAsked() {
        val new = Recorded(null)
        assertTrue(new.importFile(Incoming("mine.txt"), intoNew = true) { "words".byteInputStream() })
        assertEquals(listOf("import mine.txt words intoNew=true"), new.done)
        val plain = Recorded(null)
        assertTrue(plain.importFile(Incoming("mine.scel"), intoNew = false) { "words".byteInputStream() })
        assertEquals(listOf("import mine.scel words intoNew=false"), plain.done)
    }

    @Test
    fun aFileRefusedIsNeitherReadNorKept() {
        val dictionaries = Recorded(PinyinDictionary.Type.Text)
        assertFalse(dictionaries.importFile(Incoming("mine.txt"), intoNew = true) { throw AssertionError("read") })
        assertEquals(emptyList<String>(), dictionaries.done)
    }

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
        // turned off, it is renamed: its words are as they were
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

    /** Changes started on [scope] and made there, each noted in [made] as it is and as the list is built again. */
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
        val fcitx = FakeFcitxAPI()
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
        // none started: at once
        c.changes.awaitMade()
        val pending = CompletableDeferred<Unit>()
        // the replaced page's, listed again on it
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
}
