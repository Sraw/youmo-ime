/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The harness's keys ([ShownKey], 060-render-p2 S3) the same with the canvas off (key views) and on
 * (the scene's keys): found by label, id, description; where they are; their words; a tap, a press,
 * a long press on them the same to the engine. A test written on them runs on either drawing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ShownKeyTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun <T> both(im: org.fcitx.fcitx5.android.core.InputMethodEntry = KeyboardHarness.PINYIN, what: (KeyboardHarness, Boolean) -> T): T {
        val (views, scene) = listOf(false, true).map { on ->
            prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, on)
            KeyboardHarness(im = im).start().use { h ->
                assertTrue(h.keys().isNotEmpty())
                // each drawing's keys, and no other's
                assertTrue(h.keys().all { (it is SceneShownKey) == on && (it is ViewShownKey) == !on })
                what(h, on)
            }
        }
        assertEquals(views, scene)
        return scene
    }

    @Test
    fun theKeysFoundAndPlacedAlike() {
        val found = both { h, _ ->
            val keys = h.keys()
            // a description of its own (Shift, Space's): a letter's node says its words, "q, 1",
            // where its view had none and TalkBack read its texts (SceneSpeechTest)
            fun own(k: ShownKey) = k.description?.takeIf { it != listOfNotNull(k.label, k.alt).joinToString(", ") }
            listOf(
                keys.map { "${it.id}#${it.copy} ${it.label} ${it.alt} ${own(it)} ${it.enabled}" }.sorted(),
                keys.associate { "${it.id}#${it.copy}" to listOf(it.bounds, it.face) }.toSortedMap(),
                h.key("q").id, h.key("q").alt, h.keyNamed("space").label, h.describedKey("Shift").id,
                // what the keyboard shows, in its order: a row's keys left to right, a key's label then corner
                h.keysLabelled(",").map { it.id }, h.texts(),
            )
        }
        assertTrue(found.toString(), (found[0] as List<*>).size >= 33)
    }

    @Test
    fun aTapPressAndLongPressOnAKeyTheSame() {
        both { h, _ ->
            val out = mutableListOf<Any?>()
            h.tap(h.key("n"))
            h.tap(h.keyNamed("i"))
            out.add(h.api.calls.filter { it.startsWith("sendKey") })
            val q = h.key("q")
            assertFalse(q.pressed)
            h.press(q)
            out += q.pressed
            h.release()
            out += q.pressed
            out.add(h.longPress(h.key("e")))
            out.add(h.keys().map { it.label }.filter { it.length == 1 }.sorted())
            out
        }.also { assertTrue(it.toString(), it[1] == true && it[2] == false) }
    }
}
