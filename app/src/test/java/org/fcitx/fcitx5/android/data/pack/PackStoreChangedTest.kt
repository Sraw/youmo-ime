/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pack

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.pack.ImportResult
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackRequirement
import org.fcitx.fcitx5.android.pack.PackShelf
import org.fcitx.fcitx5.android.pack.PackStatus
import org.fcitx.fcitx5.android.pack.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

// only the text pack restarts the host (a restart loses composing); state counted after restart; old generation deleted once unread
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
                if (kind == PackKind.TEXT) assertEquals(before, countedAtRestart.last())
                assertEquals(kind.toString(), before + 1, PackStore.state.value.generation)
                // swept at once: voice readers look again, the host is up anew on the text pack's
                assertFalse("$kind: $replaced", replaced.exists())
                assertTrue(kind.toString(), shelf.dir(kind)!!.exists())
            }
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    // host restart failing: state counted anyway, old generation kept for the old host and left for the next start's tidy
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
            PackStore.tidyAtStart(shelf) { false }
            assertFalse("$replaced", replaced.exists())
        } finally {
            root.deleteRecursively()
        }
    }
    // an older pack over a newer one is asked first, nothing copied before; kept unless the answer is yes
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
            val newer = File(root, "new.youmopack").apply { writeBytes(TestShelves.pack(PackKind.TEXT, version = 3)) }
            assertTrue(runBlocking { PackStore.import(resolver, Uri.fromFile(newer)) { _, _ -> error("asked") } } is ImportResult.Imported)
            PackStore.useShelf(PackShelf(File(root, "empty"), listOf(TestShelves.packs.publicKey), TestShelves.requirements).apply { load() })
            // a provider that gives its stream only once must still be read
            val oneShot = Uri.parse("content://packs.test/old.youmopack")
            var opened = 0
            shadowOf(resolver).registerInputStreamSupplier(oneShot) {
                if (opened++ == 0) file.inputStream() else ByteArrayInputStream(ByteArray(0))
            }
            val once = runBlocking { PackStore.import(resolver, oneShot) { _, _ -> error("asked") } }
            assertTrue("$once", once is ImportResult.Imported)
            assertEquals(1, opened)
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    // an older pack this build cannot use is refused at once, not asked; one it can use is still asked about
    @Test
    fun anOlderPackThisBuildCannotUseIsRefusedWithoutAsking() {
        val root = Files.createTempDirectory("packs").toFile()
        val restart = PackStore.restartHost
        PackStore.restartHost = { true }
        try {
            val needed = PackRequirement(format = 1, minVersion = 2)
            val shelf = PackShelf(root, listOf(TestShelves.packs.publicKey), TestShelves.requirements + (PackKind.TEXT to needed)).apply { load() }
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT, version = 3).inputStream()) is ImportResult.Imported)
            PackStore.useShelf(shelf)
            val resolver = RuntimeEnvironment.getApplication().contentResolver
            val asked = mutableListOf<Pair<Int, Int>>()
            var copied = 0
            fun import(version: Int) = runBlocking {
                val file = File(root, "v$version.youmopack").apply { writeBytes(TestShelves.pack(PackKind.TEXT, version = version)) }
                PackStore.import(resolver, Uri.fromFile(file), { _, _ -> copied++ }) { new, there -> asked += new.version to there.version; false }
            }
            val refused = import(1)
            assertTrue("$refused", refused is ImportResult.Refused)
            val why = (refused as ImportResult.Refused).why as Refusal.Incompatible
            assertEquals(1 to needed, why.manifest.version to why.needed)
            assertEquals("not asked", emptyList<Pair<Int, Int>>(), asked)
            assertEquals(0, copied)
            assertEquals(3, shelf.installed(PackKind.TEXT)!!.version)
            assertTrue(import(2) is ImportResult.KeptNewer)
            assertEquals(listOf(2 to 3), asked)
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    // a kept v3 voice pack from test builds stays in use; the released v2 over it is asked about as older, and 替换 uses it
    @Test
    @Uat("ST-82")
    fun aTestBuildsVoicePackV3StaysInUseTillTheReleasedV2ReplacesIt() {
        val dir = Files.createTempDirectory("packs").toFile()
        val format = BuildConfig.PACK_VOICE_FORMAT
        val app = TestShelves.requirements + (PackKind.VOICE to PackRequirement(format, BuildConfig.PACK_VOICE_MIN_VERSION))
        val beta = PackShelf(dir, listOf(TestShelves.packs.publicKey), TestShelves.requirements + (PackKind.VOICE to PackRequirement(format, 3))).apply { load() }
        assertTrue(beta.import(TestShelves.pack(PackKind.TEXT).inputStream()) is ImportResult.Imported)
        assertTrue(beta.import(TestShelves.pack(PackKind.VOICE, version = 3, format = format).inputStream()) is ImportResult.Imported)
        val shelf = PackShelf(dir, listOf(TestShelves.packs.publicKey), app).apply { load() }
        val restart = PackStore.restartHost
        PackStore.restartHost = { true }
        try {
            PackStore.useShelf(shelf)
            val context = RuntimeEnvironment.getApplication()
            val before = shelf.status(PackKind.VOICE)
            val voiceDir = PackStore.dir(PackKind.VOICE)
            val file = File(dir, "youmo-voice-v2.youmopack").apply { writeBytes(TestShelves.pack(PackKind.VOICE, version = 2, format = format)) }
            val asked = mutableListOf<Pair<Int, Int>>()
            val result = runBlocking {
                PackStore.import(context.contentResolver, Uri.fromFile(file)) { new, there -> asked += new.version to there.version; true }
            }
            assertRows(
                "this build takes the voice pack from v2" to { assertEquals(2, BuildConfig.PACK_VOICE_MIN_VERSION) },
                "the test build's v3 is still used: its models read" to {
                    assertTrue("$before", before is PackStatus.Ready && before.manifest.version == 3)
                    assertNotNull(voiceDir)
                },
                "the packs page says v3 is there" to {
                    assertEquals(context.getString(R.string.pack_status_ready, 3), PackImports.describe(context, before))
                },
                "the released v2 over it is asked about, as an older pack over a newer one" to { assertEquals(listOf(2 to 3), asked) },
                "替换: imported, told as replacing the newer v3" to {
                    assertEquals(
                        context.getString(R.string.pack_imported_older, context.getString(R.string.pack_voice), 2, 3),
                        PackImports.describe(context, PackImports.Outcome(file.name, result)),
                    )
                },
                "v2 is the one used" to {
                    val after = shelf.status(PackKind.VOICE)
                    assertTrue("$after", after is PackStatus.Ready && after.manifest.version == 2)
                    assertNotNull(PackStore.dir(PackKind.VOICE))
                },
            )
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
            dir.deleteRecursively()
        }
    }
}
