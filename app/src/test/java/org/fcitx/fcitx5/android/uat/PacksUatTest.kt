/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.Intent
import androidx.preference.PreferenceFragmentCompat
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
        val shelf = PackShelf(Files.createTempDirectory("packs").toFile(), listOf(TestShelves.packs.publicKey), TestShelves.requirements)
        shelf.load()
        assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT, version = 1).inputStream()) is ImportResult.Imported)
        // the app now wants version 2 of the same format: what was imported stays, but is not used
        val strict = PackShelf(
            shelf.dir(PackKind.TEXT)!!.parentFile!!.parentFile!!,
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
    }

    @Test
    @Uat("VO-34")
    fun withoutTheVoicePackTheMicrophoneAsksForItAndTheSpaceSaysSo() {
        KeyboardHarness().use { h ->
            h.start()
            TestShelves.use(text = true, voice = false)
            h.idle()
            assertNull("the keyboard types without the voice pack", gate(h))
            // the bar's microphone: the notice says which pack, and the page opens; no permission asked
            val mic = KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription == h.service.getString(R.string.voice_input) }
            h.tap(mic)
            h.idle()
            assertEquals(listOf(h.service.getString(R.string.pack_voice_needed)), h.noticeTexts())
            assertTrue(openedPacks(opened()))
            // holding the space: said, nothing opened (a hold is not a tap to go somewhere)
            val space = KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription?.startsWith("Space") == true }
            val f = h.finger(space, h.at(space))
            h.advance(700)
            f.up(h.at(space))
            h.idle()
            assertNull(opened())
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
    }
}
