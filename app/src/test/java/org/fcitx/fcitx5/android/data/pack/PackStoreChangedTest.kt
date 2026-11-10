/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pack

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.pack.ImportResult
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackManifest
import org.fcitx.fcitx5.android.pack.PackShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

// a pack imported through the store (F6301, F5988): the host restarted only for the text pack (it reads no
// voice pack, and a restart loses what is being composed); the state counted after the restart returns, so
// what the keyboard queues on it waits for the new host; the replaced generation deleted once nothing reads it
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PackStoreChangedTest {

    @Test
    fun onlyTheTextPacksImportRestartsTheHostAndTheStateCountsAfterIt() {
        val root = Files.createTempDirectory("packs").toFile()
        val restart = PackStore.restartHost
        val countedAtRestart = mutableListOf<Int>()
        PackStore.restartHost = { countedAtRestart.add(PackStore.state.value.generation); true }
        try {
            val shelf = PackShelf(root, listOf(TestShelves.packs.publicKey), TestShelves.requirements).apply { load() }
            for (kind in PackKind.entries) assertTrue(shelf.import(TestShelves.pack(kind).inputStream()) is ImportResult.Imported)
            PackStore.useShelf(shelf)
            for (kind in listOf(PackKind.VOICE, PackKind.TEXT)) {
                val replaced = shelf.dir(kind)!!
                val restartsBefore = countedAtRestart.size
                val before = PackStore.state.value.generation
                val result = runBlocking { PackStore.import(TestShelves.pack(kind, version = 2).inputStream()) }
                assertTrue("$kind $result", result is ImportResult.Imported)
                assertEquals("$kind: the host restarted", if (kind == PackKind.TEXT) 1 else 0, countedAtRestart.size - restartsBefore)
                // the restart sees the state not yet counted
                if (kind == PackKind.TEXT) assertEquals(before, countedAtRestart.last())
                assertEquals(kind.toString(), before + 1, PackStore.state.value.generation)
                // swept at once: the voice pack's readers look again, the host up anew on the text pack's
                assertFalse("$kind: $replaced", replaced.exists())
                assertTrue(kind.toString(), shelf.dir(kind)!!.exists())
            }
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    // the text pack imported but the host not up anew (its restart failing or throwing): the state counted
    // all the same, the replaced generation kept (the old host may still read it), left for the next start;
    // the start's tidy, run after an import has begun, leaves it too
    @Test
    fun aRestartThatFailsCountsAndKeepsTheReplacedGeneration() {
        val restart = PackStore.restartHost
        try {
            assertRows(
                "the restart says the host is not up" to { importWithRestart { false } },
                "the restart throws" to { importWithRestart { throw IllegalStateException("no host") } },
            )
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
        }
    }

    private fun importWithRestart(restart: () -> Boolean) {
        val root = Files.createTempDirectory("packs").toFile()
        try {
            val shelf = PackShelf(root, listOf(TestShelves.packs.publicKey), TestShelves.requirements).apply { load() }
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT).inputStream()) is ImportResult.Imported)
            PackStore.useShelf(shelf)
            PackStore.restartHost = restart
            val replaced = shelf.dir(PackKind.TEXT)!!
            val before = PackStore.state.value.generation
            val result = runBlocking { PackStore.import(TestShelves.pack(PackKind.TEXT, version = 2).inputStream()) }
            assertTrue("$result", result is ImportResult.Imported)
            assertEquals(before + 1, PackStore.state.value.generation)
            assertTrue("$replaced", replaced.exists())
            PackStore.tidyAtStart(shelf)
            assertTrue("the start's tidy after an import: $replaced", replaced.exists())
            // what the start's tidy deletes with no import begun
            PackStore.tidyAtStart(shelf) { false }
            assertFalse("$replaced", replaced.exists())
        } finally {
            root.deleteRecursively()
        }
    }
    // pending L8: an older pack over a newer one asked first, nothing copied before; kept unless the answer is yes
    @Test
    fun anOlderPackReplacesTheNewerOneOnlyWhereTheAnswerIsYes() {
        val root = Files.createTempDirectory("packs").toFile()
        val restart = PackStore.restartHost
        PackStore.restartHost = { true }
        try {
            val shelf = PackShelf(root, listOf(TestShelves.packs.publicKey), TestShelves.requirements).apply { load() }
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT, version = 2).inputStream()) is ImportResult.Imported)
            PackStore.useShelf(shelf)
            val file = File(root, "old.youmopack").apply { writeBytes(TestShelves.pack(PackKind.TEXT)) }
            val resolver = RuntimeEnvironment.getApplication().contentResolver
            val asked = mutableListOf<Pair<Int, Int>>()
            var copied = 0
            fun import(yes: Boolean) = runBlocking {
                PackStore.import(resolver, Uri.fromFile(file), { _, _ -> copied++ }) { new, there -> asked += new.version to there.version; yes }
            }
            val kept = import(false) as ImportResult.KeptNewer
            assertEquals("nothing copied before the answer, nor after a no", 0, copied)
            assertEquals(1 to 2, kept.manifest.version to kept.installed.version)
            assertEquals(2, shelf.installed(PackKind.TEXT)!!.version)
            val taken = import(true) as ImportResult.Imported
            assertEquals(1 to 2, taken.manifest.version to taken.replaced!!.version)
            assertEquals(listOf(1 to 2, 1 to 2), asked)
            // a newer one, or none there: not asked
            val newer = File(root, "new.youmopack").apply { writeBytes(TestShelves.pack(PackKind.TEXT, version = 3)) }
            assertTrue(runBlocking { PackStore.import(resolver, Uri.fromFile(newer)) { _, _ -> error("asked") } } is ImportResult.Imported)
            PackStore.useShelf(PackShelf(File(root, "empty"), listOf(TestShelves.packs.publicKey), TestShelves.requirements).apply { load() })
            assertTrue(runBlocking { PackStore.import(resolver, Uri.fromFile(file)) { _, _ -> error("asked") } } is ImportResult.Imported)
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    // the page's side of an older pack over a newer one: the question published while the import waits, the
    // answer resumes it, the question cleared; the next file asked only once the first is answered
    @Test
    fun theReplaceQuestionWaitsForThePagesAnswerOneFileAtATime() {
        val manifestOf = { version: Int -> PackManifest.parse(TestShelves.packs.manifest(PackKind.TEXT, mapOf("handwriting/test.txt" to "x".toByteArray()), version)) }
        val installed = manifestOf(2)
        val importer = PackImports.importer
        PackImports.importer = { _, _, _, replaceNewer ->
            val manifest = manifestOf(1)
            if (replaceNewer(manifest, installed)) ImportResult.Imported(manifest, installed) else ImportResult.KeptNewer(manifest, installed)
        }
        try {
            val uris = listOf("a.youmopack", "b.youmopack").map { Uri.parse("file:///sdcard/$it") }
            val outcomes = ArrayList<PackImports.Outcome>()
            runBlocking {
                val job = launch(Dispatchers.Default) { PackImports.importAll(RuntimeEnvironment.getApplication().contentResolver, uris, outcomes) }
                val first = withTimeout(WAIT) { PackImports.replaceQuestion.filterNotNull().first() }
                assertEquals(listOf("a.youmopack", 1, 2), listOf(first.file, first.version, first.installed))
                // an answer to a question no longer asked is let be: the one asked still waits
                PackImports.answer(PackImports.ReplaceNewerQuestion("stale", PackKind.TEXT, 1, 2), true)
                assertEquals(first, PackImports.replaceQuestion.value)
                PackImports.answer(first, false)
                val second = withTimeout(WAIT) { PackImports.replaceQuestion.filterNotNull().first { it !== first } }
                assertEquals("b.youmopack", second.file)
                PackImports.answer(second, true)
                withTimeout(WAIT) { job.join() }
            }
            assertEquals(null, PackImports.replaceQuestion.value)
            assertTrue("${outcomes[0].result}", outcomes[0].result is ImportResult.KeptNewer)
            assertTrue("${outcomes[1].result}", outcomes[1].result is ImportResult.Imported)
            val app = RuntimeEnvironment.getApplication()
            assertEquals(
                app.getString(R.string.pack_kept_newer, app.getString(R.string.pack_text), 2, 1),
                PackImports.describe(app, outcomes[0]),
            )
        } finally {
            PackImports.importer = importer
        }
    }

    // the import cancelled while it asks: the question taken down, not left for the page
    @Test
    fun aCancelledImportTakesItsQuestionDown() {
        val manifest = PackManifest.parse(TestShelves.packs.manifest(PackKind.TEXT, mapOf("handwriting/test.txt" to "x".toByteArray()), 1))
        val importer = PackImports.importer
        PackImports.importer = { _, _, _, replaceNewer ->
            replaceNewer(manifest, manifest)
            ImportResult.AlreadyThere(manifest)
        }
        try {
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    PackImports.importAll(RuntimeEnvironment.getApplication().contentResolver, listOf(Uri.parse("file:///sdcard/a.youmopack")), ArrayList())
                }
                withTimeout(WAIT) { PackImports.replaceQuestion.filterNotNull().first() }
                job.cancelAndJoin()
            }
            assertEquals(null, PackImports.replaceQuestion.value)
        } finally {
            PackImports.importer = importer
        }
    }

    private companion object {
        const val WAIT = 10_000L
    }
}
