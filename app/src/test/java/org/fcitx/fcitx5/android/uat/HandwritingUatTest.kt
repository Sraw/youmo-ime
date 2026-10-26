/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Looper
import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPadView
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPause
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UAT cases of handwriting (uat/09) on the whole keyboard over a fake engine: the pad, its strokes
 * and pause. What the strokes are read as is the recognizer's (HandwritingRecognitionTest, the
 * readings recorded on a device), not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class HandwritingUatTest {

    private val handwriting = InputMethodEntry(Engines.HANDWRITING, "手写", "", "", "写", "zh_CN", "androidengine", false)

    private val h = KeyboardHarness(im = handwriting).apply {
        api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, handwriting)
        api.enabledIme = api.availableIme
    }

    @After
    fun close() = h.close()

    private val pad: HandwritingPadView get() = KeyboardHarness.find(h.inputView) ?: error("no pad")

    /** A stroke across the pad from ([x0], [y0]) to ([x1], [y1]), fractions of its size, drawn in [ms]. */
    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, ms: Long = 200) {
        val p = pad
        val steps = 8
        val path = (0..steps).map { i ->
            val t = i / steps.toFloat()
            KeyboardHarness.Touch(p.width * (x0 + (x1 - x0) * t), p.height * (y0 + (y1 - y0) * t), if (i == 0) 0 else ms / steps)
        }
        h.touch(p, *path.toTypedArray())
    }

    // the pad's reading and finishing run on the recognizer's thread: waited for in real time
    private fun until(what: String, ms: Long = 5_000, test: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!test()) {
            check(System.currentTimeMillis() < deadline) { "never: $what; ${h.api.calls}" }
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    @Uat("HW-01")
    fun theHandwritingKeyboard() {
        h.start()
        val keys = h.texts()
        assertTrue(keys.toString(), pad.width > h.keyboard.width / 2)
        // on the right ⌫, ，and 。; below 符 123 space globe enter
        assertTrue(keys.toString(), "，" in keys && "。" in keys)
        h.described("Backspace")
        h.described(h.service.getString(org.fcitx.fcitx5.android.R.string.a11y_key_action_done))
        assertTrue(keys.toString(), keys.containsAll(listOf("符", "123")))
        h.capture("HandwritingUatTest/keyboard")
    }

    @Test
    @Uat("HW-06")
    fun backspaceTakesTheLastStrokeBack() {
        h.start()
        stroke(0.2f, 0.5f, 0.8f, 0.5f)
        assertEquals(1, pad.pad.strokes.size)
        h.api.calls.clear()
        h.tap(h.described("Backspace"))
        assertEquals(0, pad.pad.strokes.size)
        // the stroke, not the field's text: nothing to the engine to delete
        assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
    }

    private fun finishes() = h.api.calls.count { it.startsWith("inkFinished") }

    @Test
    @Uat("HW-12")
    fun aShortAndALongPause() {
        val pause = AppPrefs.getInstance().keyboard.handwritingPause
        try {
            pause.setValue(HandwritingPause.Fastest)
            h.start()
            stroke(0.3f, 0.2f, 0.5f, 0.8f)
            stroke(0.5f, 0.4f, 0.7f, 0.8f)
            h.advance(500)
            until("finished") { finishes() == 1 }
            pause.setValue(HandwritingPause.Slowest)
            stroke(0.2f, 0.5f, 0.8f, 0.5f)
            h.advance(1000)
            Thread.sleep(200)
            h.idle()
            // a second later: still on the pad
            assertEquals(1, finishes())
            assertEquals(1, pad.pad.strokes.size)
            stroke(0.5f, 0.2f, 0.5f, 0.8f)
            h.advance(1600)
            until("finished again") { finishes() == 2 }
        } finally {
            pause.setValue(pause.defaultValue)
        }
    }

    @Test
    @Uat("HW-16")
    fun aPasswordIsTheEnglishKeyboard() {
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // androidengine switches a password to English (PR-03)
        h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.ENGLISH))
        val keys = h.texts()
        assertTrue(keys.toString(), keys.take(10) == listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0") && "q" in keys)
        assertTrue(KeyboardHarness.find<HandwritingPadView>(h.inputView)?.let { h.shown(it) } != true)
        h.start()
        h.emit(FcitxEvent.IMChangeEvent(handwriting))
        assertTrue(h.shown(pad))
    }
}
