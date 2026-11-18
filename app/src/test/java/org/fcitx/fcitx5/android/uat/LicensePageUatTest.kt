/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Looper
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.until as waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** The settings app's 开源许可 page over a fake engine (uat/12 VO-15): the voice pack's data credited, apart from SettingsPagesUatTest for its size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class LicensePageUatTest {

    @Before
    fun phone() = phoneStorage()

    private val st = SettingsHarness(FakeHostApi())

    @After
    fun close() = st.close()

    // a page's work done off the main thread (a list read): waited for, as a user would
    private fun until(what: String, test: () -> Boolean) =
        waitUntil(what, { "; shown ${shown()}" }, step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }, done = test)

    private fun shown(): List<String> = KeyboardHarness.all(st.activity.window.decorView)
        .filter { it.isShown && it is TextView }.map { (it as TextView).text.toString() }.toList()

    private fun preferences(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).flatMap { i ->
            val p = group.getPreference(i)
            listOf(p) + ((p as? PreferenceGroup)?.let(::preferences) ?: emptyList())
        }.filter { it.isVisible }

    @Test
    @Uat("VO-15")
    fun theVoiceModelsDataIsCredited() {
        st.open(SettingsRoute.License)
        until("listed") { preferences((st.page as PreferenceFragmentCompat).preferenceScreen ?: return@until false).size > 3 }
        val prefs = preferences((st.page as PreferenceFragmentCompat).preferenceScreen)
        // credited in every build: the voice input is (its models the voice pack's)
        fun credited(title: String, licence: String, url: String): () -> Unit = {
            val pref = prefs.firstOrNull { it.title == title }
            assertNotNull(title, pref)
            assertEquals(title, licence, pref!!.summary.toString())
            pref.performClick()
            st.idle()
            assertEquals(title, url, shadowOf(st.activity).nextStartedActivity.dataString)
        }
        assertRows(
            "the encoder's fine-tuning kept its English with LibriSpeech, whose licence asks for the notice" to
                credited("LibriSpeech", "CC-BY-4.0", "https://www.openslr.org/12/"),
            "the polish tagger is trained on non-commercial data, and on FineWeb-2's sentences: the voice pack is CC BY-NC-SA, apart from the app's code" to
                credited(
                    "Voice polish (fillers, punctuation, numbers): this project's model, fine-tuned from Chinese ELECTRA small (HFL) on " +
                        "transcripts of MagicData-RAMC, RealTalk-CN and CS2W and on speech-like text made from FineWeb-2 sentences. The voice " +
                        "pack is licensed apart from the app, for non-commercial use only (CC BY-NC-SA 4.0); the app's code keeps its own " +
                        "licence (LGPL-2.1-or-later)",
                    "CC-BY-NC-SA-4.0", "https://creativecommons.org/licenses/by-nc-sa/4.0/",
                ),
            "MagicData-RAMC, its transcripts trained on: non-commercial, no derivatives" to
                credited("MagicData-RAMC (Magic Data Technology)", "CC-BY-NC-ND-4.0", "https://www.openslr.org/123/"),
            "RealTalk-CN, trained on: non-commercial, share-alike" to
                credited("RealTalk-CN (BAAI)", "CC-BY-NC-SA-4.0", "https://huggingface.co/datasets/BAAI/RealTalk-CN"),
            "CS2W, trained on: its sentences RAMC's" to
                credited("CS2W (Guo et al., EMNLP 2023; sentences from MagicData-RAMC)", "CC-BY-SA-4.0", "https://github.com/guozishan/CS2W"),
            "FineWeb-2, its sentences made speech-like and trained on: attribution, on the engine data's row, which names the voice polish (R27 F8548)" to
                credited(
                    "Web text the language model, the English words and handwriting's character frequencies are counted from, and the " +
                        "voice polish's speech-like text made of: FineWeb-2",
                    "ODC-By-1.0", "https://huggingface.co/datasets/HuggingFaceFW/fineweb-2",
                ),
            "the tagger was fine-tuned from Chinese ELECTRA small, Apache-2.0" to
                credited("Chinese ELECTRA (hfl/chinese-electra-180g-small-ex-discriminator)", "Apache-2.0", "https://github.com/ymcui/Chinese-ELECTRA"),
        )
    }
}
