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
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.pack.ImportResult
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// the files picked on the 资源包 page imported in turn: the page's side of an older pack over a newer one
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PackImportsTest {

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
            PackImports.reset()
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
            PackImports.reset()
        }
    }

    private companion object {
        const val WAIT = 10_000L
    }
}
