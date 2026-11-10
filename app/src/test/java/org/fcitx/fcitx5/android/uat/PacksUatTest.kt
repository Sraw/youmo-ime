/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.Intent
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.pack.PackImports
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.pack.TestShelves
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.PackGateView
import org.fcitx.fcitx5.android.pack.ImportResult
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackRequirement
import org.fcitx.fcitx5.android.pack.PackShelf
import org.fcitx.fcitx5.android.pack.PackStatus
import org.fcitx.fcitx5.android.pack.Refusal
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.PacksFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.nio.file.Files

/**
 * 资源包 (dev/design/060-asset-packs.md, uat/14 ST-60…, uat/12 VO-34): the keyboard without the text
 * pack, voice input without the voice pack, and the page that shows and imports them. The import's
 * checks themselves are PackShelfTest's (lib/ime-core); here the app's side of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class PacksUatTest {

    private fun gate(h: KeyboardHarness): PackGateView? = KeyboardHarness.all(h.inputView).filterIsInstance<PackGateView>().firstOrNull()

    private fun opened(): Intent? = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity

    @Suppress("DEPRECATION") // getParcelableExtra(name, class) is API 33's
    private fun openedPacks(intent: Intent?) =
        intent?.component?.className == MainActivity::class.java.name &&
            intent.getParcelableExtra<SettingsRoute>(MainActivity.EXTRA_SETTINGS_ROUTE) == SettingsRoute.Packs

    // the test shelves back after each test, failed or not: a SettingsHarness test or one reading PackStore
    // without a KeyboardHarness (whose init sets them) would else see the shelf a test before it left
    @After
    fun packsBack() {
        TestShelves.use()
    }

    @Test
    @Uat("ST-60")
    fun withoutTheTextPackTheKeyboardAsksForItAndATapOpensThePage() {
        KeyboardHarness().use { h ->
            h.start()
            assertNull("both packs there: no gate", gate(h))
            // the text pack gone (as on a phone upgraded from 0.5.x): the keyboard made anew under the gate
            TestShelves.use(text = false, voice = true)
            h.idle()
            val gate = checkNotNull(gate(h)) { "no gate" }
            assertTrue(h.shown(gate))
            // over all of the keys, the bar's too: nothing of the keyboard is left to touch
            assertTrue("${gate.height} < ${h.keyboard.height}", gate.height >= h.keyboard.height && gate.width >= h.keyboard.width)
            assertEquals(h.service.getString(R.string.pack_text_needed), gate.contentDescription)
            assertTrue(KeyboardHarness.all(gate).any { (it as? android.widget.TextView)?.text == h.service.getString(R.string.pack_gate_hint) })
            h.capture("PacksUatTest/gate")
            // a finger's touch, in where a finger's go (the input view): the gate takes it, no key
            // under it types, and the page opens
            h.api.calls.clear()
            val f = h.finger(gate, h.at(gate, dx = -120f, dy = 60f))
            f.up(h.at(gate, dx = -120f, dy = 60f))
            h.idle()
            assertTrue(h.api.calls.toString(), h.api.calls.none { it.startsWith("sendKey") })
            assertTrue(openedPacks(opened()))
            // its button: the system's keyboard picker, not the page (the gate covers the bar's ways out)
            val switch = KeyboardHarness.all(gate).first { (it as? android.widget.TextView)?.text == h.service.getString(R.string.pack_gate_switch) }
            assertTrue(h.shown(switch))
            var picked = 0
            val pick = PackGateView.pickInputMethod
            PackGateView.pickInputMethod = { picked++ }
            try {
                h.api.calls.clear()
                h.tap(switch)
                h.idle()
            } finally {
                PackGateView.pickInputMethod = pick
            }
            assertEquals(1, picked)
            assertNull(opened())
            assertTrue(h.api.calls.toString(), h.api.calls.none { it.startsWith("sendKey") })
            // imported: the keyboard again, and the app bound and its field focused again in fcitx,
            // which the import restarted (without it the space read （不可用） on a device till another app)
            (h.service.onCreateInputMethodInterface() as android.view.inputmethod.InputMethod)
                .bindInput(android.view.inputmethod.InputBinding(null, null, UID, 0))
            h.idle()
            h.api.calls.clear()
            TestShelves.use()
            h.idle()
            assertNull(gate(h))
            val calls = h.api.calls.toList()
            val activate = calls.indexOfFirst { it.startsWith("activate($UID,") }
            val flags = calls.indexOfFirst { it.startsWith("setCapFlags") }
            assertTrue(calls.toString(), activate >= 0 && flags > activate && calls.indexOf("focus(true)") > flags)
        }
    }

    private companion object {
        const val UID = 10_234
    }

    @Test
    @Uat("ST-61")
    fun aTextPackTooOldSaysWhichVersionIsNeeded() {
        val root = Files.createTempDirectory("packs").toFile()
        try {
            val shelf = PackShelf(root, listOf(TestShelves.packs.publicKey), TestShelves.requirements)
            shelf.load()
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT, version = 1).inputStream()) is ImportResult.Imported)
            // the app now wants version 2 of the same format: what was imported stays, but is not used
            val strict = PackShelf(
                root,
                listOf(TestShelves.packs.publicKey),
                TestShelves.requirements + (PackKind.TEXT to PackRequirement(format = 1, minVersion = 2)),
            )
            strict.load()
            KeyboardHarness().use { h ->
                h.start()
                PackStore.useShelf(strict)
                h.idle()
                assertEquals(h.service.getString(R.string.pack_text_needed_update, 2), gate(h)?.contentDescription)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    @Uat("VO-34")
    fun withoutTheVoicePackTheMicrophoneAsksForItAndTheSpaceDoesNothing() {
        KeyboardHarness().use { h ->
            h.start()
            TestShelves.use(text = true, voice = false)
            h.idle()
            assertNull("the keyboard types without the voice pack", gate(h))
            // holding the space (voice input, the default): nothing, as 0.5.x's text build; no notice, nothing opened
            val space = h.keyNamed("space")
            val f = h.finger(space, h.at(space))
            h.advance(700)
            f.up(h.at(space))
            h.idle()
            assertEquals(emptyList<String>(), h.noticeTexts())
            assertNull(opened())
            // the bar's microphone: the notice says which pack, and the page opens; no permission asked
            val mic = KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription == h.service.getString(R.string.voice_input) }
            h.tap(mic)
            h.idle()
            assertEquals(listOf(h.service.getString(R.string.pack_voice_needed)), h.noticeTexts())
            assertTrue(openedPacks(opened()))
        }
    }

    // a voice pack newer than this app reads: the microphone says to update the app, not the pack
    @Test
    fun aVoicePackForANewerAppAsksForTheAppsUpdate() {
        val dir = Files.createTempDirectory("packs").toFile()
        val newer = PackShelf(dir, listOf(TestShelves.packs.publicKey), TestShelves.requirements + (PackKind.VOICE to PackRequirement(format = 2, minVersion = 1)))
        newer.load()
        assertTrue(newer.import(TestShelves.pack(PackKind.TEXT).inputStream()) is ImportResult.Imported)
        assertTrue(newer.import(TestShelves.pack(PackKind.VOICE, version = 5, format = 2).inputStream()) is ImportResult.Imported)
        // this app reads format 1
        val shelf = PackShelf(dir, listOf(TestShelves.packs.publicKey), TestShelves.requirements).apply { load() }
        assertTrue(shelf.status(PackKind.VOICE) is PackStatus.NeedsNewerApp)
        try {
            KeyboardHarness().use { h ->
                h.start()
                PackStore.useShelf(shelf)
                h.idle()
                val mic = KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription == h.service.getString(R.string.voice_input) }
                h.tap(mic)
                h.idle()
                assertEquals(listOf(h.service.getString(R.string.pack_voice_needed_app, 5)), h.noticeTexts())
            }
        } finally {
            TestShelves.use()
            dir.deleteRecursively()
        }
    }

    @Test
    @Uat("ST-62")
    fun thePageListsEachPacksStateAndWhatItIsFor() {
        TestShelves.use(text = true, voice = false)
        SettingsHarness().use { st ->
            st.open(SettingsRoute.Packs)
            val text = st.pref(R.string.pack_text)
            val voice = st.pref(R.string.pack_voice)
            assertEquals("${st.s(R.string.pack_status_ready, 1)}\n${st.s(R.string.pack_text_use)}", text.summary.toString())
            assertEquals("${st.s(R.string.pack_status_missing)}\n${st.s(R.string.pack_voice_use)}", voice.summary.toString())
            assertNotNull(st.preferences().firstOrNull { it.key == PacksFragment.IMPORT_KEY })
            assertTrue(st.page is PreferenceFragmentCompat)
            // imported elsewhere (the UAT's import, another page): the rows follow
            TestShelves.use()
            st.idle()
            assertEquals("${st.s(R.string.pack_status_ready, 1)}\n${st.s(R.string.pack_voice_use)}", voice.summary.toString())
            st.capture("PacksUatTest/page")
        }
    }

    @Test
    @Uat("ST-63")
    fun eachFilesOutcomeIsToldAndTheUserMayDeleteTheirFiles() {
        TestShelves.use(text = false, voice = false)
        SettingsHarness().use { st ->
            st.open(SettingsRoute.Packs)
            val app = RuntimeEnvironment.getApplication()
            val manifest = TestShelves.packs.manifest(PackKind.TEXT, mapOf("handwriting/test.txt" to "x".toByteArray()))
            val outcomes = listOf(
                PackImports.Outcome("youmo-text-v1.youmopack", ImportResult.Imported(org.fcitx.fcitx5.android.pack.PackManifest.parse(manifest), null)),
                PackImports.Outcome("photo.jpg", ImportResult.Refused(Refusal.NotAPack("no manifest.json"))),
                PackImports.Outcome("big.youmopack", ImportResult.Refused(Refusal.NoSpace(400L shl 20, 100L shl 20))),
            )
            val lines = outcomes.map { PackImports.describe(app, it) }
            assertEquals(st.s(R.string.pack_imported, st.s(R.string.pack_text), 1), lines[0])
            assertEquals(st.s(R.string.pack_not_a_pack, "photo.jpg"), lines[1])
            assertTrue(lines[2], lines[2].startsWith(st.s(R.string.pack_no_space, "big.youmopack", "", "").substringBefore("：").substringBefore(":")))
            // an older version over a newer one: imported, and said so; over an older one, as any import
            val v1 = org.fcitx.fcitx5.android.pack.PackManifest.parse(manifest)
            val v3 = org.fcitx.fcitx5.android.pack.PackManifest.parse(TestShelves.packs.manifest(PackKind.TEXT, mapOf("handwriting/test.txt" to "y".toByteArray()), version = 3))
            assertEquals(st.s(R.string.pack_imported_older, st.s(R.string.pack_text), 1, 3), PackImports.describe(app, PackImports.Outcome("a", ImportResult.Imported(v1, v3))))
            assertEquals(st.s(R.string.pack_imported, st.s(R.string.pack_text), 3), PackImports.describe(app, PackImports.Outcome("a", ImportResult.Imported(v3, v1))))
            // the same version copied anew (one damaged, mended): imported, nothing newer replaced
            assertEquals(st.s(R.string.pack_imported, st.s(R.string.pack_text), 1), PackImports.describe(app, PackImports.Outcome("a", ImportResult.Imported(v1, v1))))
            // all imported: the results say the files may be deleted now (the app never deletes them)
            val good = outcomes.take(1)
            PackImports.finished(good)
            st.idle()
            val dialog = ShadowDialog.getLatestDialog()
            val shown = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<android.widget.TextView>().joinToString("\n") { it.text }
            assertTrue(shown, st.s(R.string.pack_import_done_hint) in shown)
            assertFalse(shown, st.s(R.string.pack_unchanged_hint) in shown)
        }
    }

    @Test
    fun aFileWhoseImportThrowsIsItsOwnOutcomeAndTheFilesAfterItImportAllTheSame() {
        val app = RuntimeEnvironment.getApplication()
        val manifest = org.fcitx.fcitx5.android.pack.PackManifest.parse(TestShelves.packs.manifest(PackKind.VOICE, mapOf("voice/test.txt" to "x".toByteArray())))
        val importer = PackImports.importer
        PackImports.importer = { _, uri, _, _ ->
            // what escaped an import (a malformed zip's IllegalArgumentException) crashed the app's scope
            if (uri.lastPathSegment == "bad.youmopack") throw IllegalArgumentException("MALFORMED")
            ImportResult.Imported(manifest, null)
        }
        try {
            val uris = listOf("bad.youmopack", "youmo-voice-v1.youmopack").map { android.net.Uri.parse("file:///sdcard/$it") }
            val outcomes = ArrayList<PackImports.Outcome>()
            runBlocking { PackImports.importAll(app.contentResolver, uris, outcomes) }
            assertEquals(listOf("bad.youmopack", "youmo-voice-v1.youmopack"), outcomes.map { it.file })
            assertTrue((outcomes[0].result as ImportResult.Refused).why is Refusal.Unreadable)
            assertTrue(outcomes[1].result is ImportResult.Imported)
        } finally {
            PackImports.importer = importer
        }
    }

    @Test
    @Uat("ST-65")
    fun aPackDamagedSinceItsImportIsSaidSoAndImportedAgainMended() {
        val app = RuntimeEnvironment.getApplication()
        val shelf = TestShelves.shelf(PackKind.TEXT, PackKind.VOICE)
        try {
            KeyboardHarness().use { h ->
                h.start()
                PackStore.useShelf(shelf)
                h.idle()
                assertNull(gate(h))
                // as copied: nothing found
                assertFalse(PackStore.verifyNow())
                // a byte of the text pack's changed, its size not: what a start does not see
                val damaged = shelf.dir(PackKind.TEXT)!!
                val file = File(damaged, "handwriting/test.txt")
                file.writeBytes(file.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
                assertTrue(PackStore.verifyNow())
                h.idle()
                assertEquals(h.service.getString(R.string.pack_text_damaged), gate(h)?.contentDescription)
                assertEquals(app.getString(R.string.pack_status_damaged, 1), PackImports.describe(app, PackStore.status(PackKind.TEXT)))
                // the voice pack as it was
                assertTrue(PackStore.ready(PackKind.VOICE))
                // the same pack imported again: copied anew (not 已经导入过), the keyboard back
                val again = runBlocking { PackStore.import(TestShelves.pack(PackKind.TEXT).inputStream()) }
                assertTrue("$again", again is ImportResult.Imported)
                h.idle()
                assertNull(gate(h))
                // the generation replaced deleted once the host is restarted on the new one
                assertFalse(damaged.exists())
            }
        } finally {
            TestShelves.use()
        }
    }

    // a shelf of its own, on [root] (a restart: another on the same root), its clock [now]
    private fun shelfAt(root: File, now: () -> Long = System::currentTimeMillis) =
        PackShelf(root, listOf(TestShelves.packs.publicKey), TestShelves.requirements, clock = now).apply { load() }

    // a byte of [shelf]'s [kind] pack changed, its size not: what a start does not see
    private fun damage(shelf: PackShelf, kind: PackKind) {
        val path = if (kind == PackKind.TEXT) "handwriting/test.txt" else "voice/test.txt"
        val file = File(shelf.dir(kind)!!, path)
        file.writeBytes(file.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
    }

    /**
     * Review M-1, M-2: read through at the first start after the import, found damaged then; that
     * kept, so the next start's keyboard is under the gate at once (not an engine failing at each
     * start); imported again, mended, and so after a restart too.
     */
    @Test
    @Uat("ST-65")
    fun aPackFoundDamagedIsStillSoAfterARestartTillImportedAgain() {
        val app = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("packs").toFile()
        try {
            KeyboardHarness().use { h ->
                h.start()
                val shelf = shelfAt(root)
                for (kind in PackKind.entries) assertTrue(shelf.import(TestShelves.pack(kind).inputStream()) is ImportResult.Imported)
                PackStore.useShelf(shelf)
                damage(shelf, PackKind.TEXT)
                // the first pass after the import reads both, charging or not
                assertFalse(PackStore.verifyDue { false })
                h.idle()
                assertEquals(h.service.getString(R.string.pack_text_damaged), gate(h)?.contentDescription)
                // the process restarted: its load says damaged, no read through needed
                PackStore.useShelf(shelfAt(root))
                h.idle()
                assertEquals(h.service.getString(R.string.pack_text_damaged), gate(h)?.contentDescription)
                assertEquals(app.getString(R.string.pack_status_damaged, 1), PackImports.describe(app, PackStore.status(PackKind.TEXT)))
                assertTrue(PackStore.ready(PackKind.VOICE))
                assertTrue(runBlocking { PackStore.import(TestShelves.pack(PackKind.TEXT).inputStream()) } is ImportResult.Imported)
                h.idle()
                assertNull(gate(h))
                PackStore.useShelf(shelfAt(root))
                h.idle()
                assertNull(gate(h))
                assertTrue(PackStore.ready(PackKind.TEXT))
            }
        } finally {
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    /** Review M-1: read through again after 30 days, but only with the phone charging. */
    @Test
    fun aPackReadThroughA30DaysAgoWaitsForTheCharger() {
        val root = Files.createTempDirectory("packs").toFile()
        var now = 1_000_000_000_000L
        val restart = PackStore.restartHost
        PackStore.restartHost = { true }
        try {
            val shelf = shelfAt(root) { now }
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT).inputStream()) is ImportResult.Imported)
            PackStore.useShelf(shelf)
            assertFalse(PackStore.verifyDue { error("not asked: the first read does not wait") })
            damage(shelf, PackKind.TEXT)
            // read days ago: nothing due
            now += 10L * 24 * 60 * 60 * 1000
            assertFalse(PackStore.verifyDue { error("not asked: nothing due") })
            assertTrue(PackStore.ready(PackKind.TEXT))
            now += 30L * 24 * 60 * 60 * 1000
            // due, not charging: left waiting
            assertTrue(PackStore.verifyDue { false })
            assertTrue(PackStore.ready(PackKind.TEXT))
            val generation = PackStore.state.value.generation
            assertFalse(PackStore.verifyDue { true })
            assertTrue(PackStore.status(PackKind.TEXT) is PackStatus.Damaged)
            assertEquals(generation + 1, PackStore.state.value.generation)
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    /**
     * Review M-2: the host failing on the text pack (an engine that cannot load, a start that
     * throws) reads it through there and then: damaged, it is said so; once a generation a process;
     * a pack missing is not read.
     */
    @Test
    fun theHostFailingOnTheTextPackReadsItThroughOnce() {
        val root = Files.createTempDirectory("packs").toFile()
        val restart = PackStore.restartHost
        val restartedOn = java.util.concurrent.LinkedBlockingQueue<Thread>()
        PackStore.restartHost = { restartedOn.add(Thread.currentThread()); true }
        try {
            // its files not readable at first (no descriptor left): read, nothing found nor marked
            var unreadable = true
            val shelf = PackShelf(root, listOf(TestShelves.packs.publicKey), TestShelves.requirements, openFile = { file ->
                if (unreadable) throw java.io.IOException("EMFILE") else file.inputStream()
            }).apply { load() }
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT).inputStream()) is ImportResult.Imported)
            PackStore.useShelf(shelf)
            val failure = java.io.IOException("Map failed")
            assertFalse(PackStore.verifyAfterFailure(PackKind.TEXT, failure))
            assertNull(shelf.verifiedAt(PackKind.TEXT))
            // that generation read through once this process: not again at each failure
            unreadable = false
            damage(shelf, PackKind.TEXT)
            assertFalse(PackStore.verifyAfterFailure(PackKind.TEXT, failure))
            assertTrue(PackStore.ready(PackKind.TEXT))
            // imported again (damaged after): read through, found so, the state counted up
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT, version = 2).inputStream()) is ImportResult.Imported)
            damage(shelf, PackKind.TEXT)
            assertFalse(PackStore.verifyAfterFailure(PackKind.TEXT, RuntimeException(org.fcitx.fcitx5.android.data.pack.PackUnavailableException(PackKind.TEXT, PackStatus.Missing))))
            val generation = PackStore.state.value.generation
            assertTrue(restartedOn.isEmpty())
            assertTrue(PackStore.verifyAfterFailure(PackKind.TEXT, failure))
            // review r4 LOW-2: the host restarted (the engines it loaded off the damaged files), not
            // on the caller's thread, the host's, which restartIfRunning would wait for
            val on = restartedOn.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(on)
            assertTrue(on !== Thread.currentThread())
            assertTrue(PackStore.status(PackKind.TEXT) is PackStatus.Damaged)
            // counted on the restart's thread, after it
            val until = System.currentTimeMillis() + 5000
            while (PackStore.state.value.generation == generation && System.currentTimeMillis() < until) Thread.sleep(10)
            assertTrue(PackStore.state.value.status(PackKind.TEXT) is PackStatus.Damaged)
            assertEquals(generation + 1, PackStore.state.value.generation)
            // no pack in use now: nothing to read
            assertFalse(PackStore.verifyAfterFailure(PackKind.TEXT, failure))
            // once
            assertNull(restartedOn.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    /**
     * Review r4 LOW-1: read through in the last 10 minutes, or since this process started, the pack
     * is not read again after a failure (a crash loop reading 311 MB at each start); older than
     * both, it is.
     */
    @Test
    fun aPackReadThroughLatelyIsNotReadAgainAfterAFailure() {
        val root = Files.createTempDirectory("packs").toFile()
        val started = PackStore.processStartedAt
        val restart = PackStore.restartHost
        PackStore.restartHost = { true }
        val real = System.currentTimeMillis()
        try {
            val shelf = shelfAt(root)
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT).inputStream()) is ImportResult.Imported)
            PackStore.useShelf(shelf)
            val failure = java.io.IOException("Map failed")
            // just read through, the process started long before: within 10 minutes
            PackStore.processStartedAt = { real - 60 * 60 * 1000L }
            assertFalse(shelf.verify(PackKind.TEXT))
            damage(shelf, PackKind.TEXT)
            assertFalse(PackStore.verifyAfterFailure(PackKind.TEXT, failure))
            assertTrue(PackStore.ready(PackKind.TEXT))
            // read 5 minutes ago (the mark as a start reads it), before the process started 2 minutes
            // ago: within 10 minutes all the same
            File(shelf.dir(PackKind.TEXT)!!, PackShelf.VERIFIED).writeText("${real - 5 * 60 * 1000L}")
            PackStore.useShelf(shelfAt(root))
            PackStore.processStartedAt = { real - 2 * 60 * 1000L }
            assertFalse(PackStore.verifyAfterFailure(PackKind.TEXT, failure))
            assertTrue(PackStore.ready(PackKind.TEXT))
            // read 20 minutes ago (the mark as a start reads it), after the process started 30 minutes ago
            File(shelf.dir(PackKind.TEXT)!!, PackShelf.VERIFIED).writeText("${real - 20 * 60 * 1000L}")
            PackStore.useShelf(shelfAt(root))
            PackStore.processStartedAt = { real - 30 * 60 * 1000L }
            assertFalse(PackStore.verifyAfterFailure(PackKind.TEXT, failure))
            assertTrue(PackStore.ready(PackKind.TEXT))
            // read 20 minutes ago, before the process started (5 minutes ago): read again, found
            PackStore.processStartedAt = { real - 5 * 60 * 1000L }
            assertTrue(PackStore.verifyAfterFailure(PackKind.TEXT, failure))
            assertTrue(PackStore.status(PackKind.TEXT) is PackStatus.Damaged)
        } finally {
            PackStore.processStartedAt = started
            PackStore.restartHost = restart
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    /** The clock set back: a mark later than now does not count as read since the process started. */
    @Test
    fun aMarkLaterThanNowDoesNotSkipTheReadAfterAFailure() {
        val root = Files.createTempDirectory("packs").toFile()
        val started = PackStore.processStartedAt
        val restart = PackStore.restartHost
        PackStore.restartHost = { true }
        val real = System.currentTimeMillis()
        try {
            val shelf = shelfAt(root)
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT).inputStream()) is ImportResult.Imported)
            damage(shelf, PackKind.TEXT)
            File(shelf.dir(PackKind.TEXT)!!, PackShelf.VERIFIED).writeText("${real + 2 * 60 * 60 * 1000L}")
            PackStore.useShelf(shelfAt(root))
            PackStore.processStartedAt = { real - 60 * 60 * 1000L }
            assertTrue(PackStore.verifyAfterFailure(PackKind.TEXT, java.io.IOException("Map failed")))
            assertTrue(PackStore.status(PackKind.TEXT) is PackStatus.Damaged)
        } finally {
            PackStore.processStartedAt = started
            PackStore.restartHost = restart
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    /** Review L-7: what tells the rest of an import failing leaves it imported, counted all the same. */
    @Test
    fun anImportWhoseRestartFailsIsImportedAndCounted() {
        val restart = PackStore.restartHost
        PackStore.restartHost = { throw IllegalStateException("host gone") }
        val shelf = TestShelves.shelf(PackKind.VOICE)
        try {
            PackStore.useShelf(shelf)
            val generation = PackStore.state.value.generation
            val result = runBlocking { PackStore.import(TestShelves.pack(PackKind.TEXT).inputStream()) }
            assertTrue("$result", result is ImportResult.Imported)
            assertEquals(generation + 1, PackStore.state.value.generation)
            assertTrue(PackStore.state.value.ready(PackKind.TEXT))
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
        }
    }

    /**
     * Review L-2: the host not up anew in time (still starting, on what files is not known): the
     * pack it replaced is not deleted under it, but by the next start's load.
     */
    @Test
    fun aPackReplacedWhileTheHostIsNotUpAnewIsDeletedByTheNextStart() {
        val restart = PackStore.restartHost
        PackStore.restartHost = { false }
        val root = Files.createTempDirectory("packs").toFile()
        try {
            val shelf = shelfAt(root)
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT).inputStream()) is ImportResult.Imported)
            PackStore.useShelf(shelf)
            val old = shelf.dir(PackKind.TEXT)!!
            assertTrue(runBlocking { PackStore.import(TestShelves.pack(PackKind.TEXT, version = 2).inputStream()) } is ImportResult.Imported)
            assertTrue(old.isDirectory)
            shelfAt(root)
            assertFalse(old.exists())
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
            root.deleteRecursively()
        }
    }

    /** Review L-5: no pass of its own in the JVM tests (one landing 30 s on in another test); the tests above call it. */
    @Test
    fun thePacksAreNotReadThroughInTheBackgroundInTheseTests() {
        assertFalse(PackStore.verifiesInBackground)
    }

    @Test
    fun theStatusAPackRowSaysForEachState() {
        val app = RuntimeEnvironment.getApplication()
        val manifest = org.fcitx.fcitx5.android.pack.PackManifest.parse(
            TestShelves.packs.manifest(PackKind.VOICE, mapOf("voice/test.txt" to "x".toByteArray()), version = 3, format = 2),
        )
        assertEquals(app.getString(R.string.pack_status_missing), PackImports.describe(app, PackStatus.Missing))
        assertEquals(app.getString(R.string.pack_status_ready, 3), PackImports.describe(app, PackStatus.Ready(manifest)))
        assertEquals(
            app.getString(R.string.pack_status_needs_update, 4, 3),
            PackImports.describe(app, PackStatus.NeedsUpdate(manifest, PackRequirement(format = 2, minVersion = 4))),
        )
        assertEquals(
            app.getString(R.string.pack_status_needs_app, 3),
            PackImports.describe(app, PackStatus.NeedsNewerApp(manifest, PackRequirement(format = 1, minVersion = 1))),
        )
        assertEquals(app.getString(R.string.pack_status_damaged, 3), PackImports.describe(app, PackStatus.Damaged(manifest)))
    }
}
