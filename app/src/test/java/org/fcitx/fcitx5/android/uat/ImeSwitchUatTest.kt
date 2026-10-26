/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UAT cases of the globe key and the clipboard's privacy (uat/01, 15), on the whole keyboard. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ImeSwitchUatTest {

    private val h = KeyboardHarness().apply {
        api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
        api.enabledIme = api.availableIme
    }

    @After
    fun close() = h.close()

    // fcitx tells of the switch it made
    private fun told() = h.emit(FcitxEvent.IMChangeEvent(h.api.inputMethodEntryCached))

    @Test
    @Uat("SET-08")
    fun theGlobeKeyGoesRoundAndTheSpaceSays() {
        h.start()
        assertTrue(h.texts().toString(), "Pinyin" in h.texts())
        h.tap(h.described(h.service.getString(R.string.a11y_key_switch_language)))
        told()
        assertEquals(KeyboardHarness.ENGLISH.uniqueName, h.api.inputMethodEntryCached.uniqueName)
        val english = h.texts()
        // still the 26 keys
        assertTrue(english.toString(), "English" in english && "q" in english && "m" in english)
        h.tap(h.described(h.service.getString(R.string.a11y_key_switch_language)))
        told()
        assertTrue(h.texts().toString(), "Pinyin" in h.texts())
    }

    @Test
    @Uat("PR-08")
    fun aSensitiveClipIsShownAsDots() {
        h.start()
        val clip = ClipData.newPlainText("", "mima1234").apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        h.service.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        h.waitFor("stored") { org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry?.text == "mima1234" }
        // the next field: the bar offers it
        h.start()
        fun all() = KeyboardHarness.all(h.service.window.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(all().toString(), all().any { "•" in it })
        h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
        h.tap(h.described(h.service.getString(R.string.clipboard)))
        h.waitFor("listed") {
            h.relayout()
            all().any { it.startsWith("•") && h.texts(h.inputView).contains(it) }
        }
        assertFalse(all().toString(), all().any { "mima1234" in it })
        h.capture("ImeSwitchUatTest/clipboard_sensitive")
    }
}
