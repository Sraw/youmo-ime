/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPadView
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.set
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The handwriting keyboard drawn by the scene does what its views did (060-render-p2 S5b): each key's
 * tap and long press, what reaches the engine; the pad in the scene's slot `pad`, written on; 注 on as
 * its key's state (KeyState.On) and said as its view said it. ，and 。 are the one thing meant to
 * differ (§5.3 S5b): shown as they type (punctuation's mapping), where the views showed them full width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class HandwritingSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    private val handwriting = InputMethodEntry(InputMethodCatalog.HANDWRITING, "手写", "", "", "写", "zh_CN", "androidengine", false)

    @After
    fun reset() {
        EngineState.candidateReadingState.value = ReadingStyle.OFF
    }

    private fun harness(canvas: Boolean): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, canvas)
        return KeyboardHarness(im = handwriting).apply {
            api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, handwriting)
            api.enabledIme = api.availableIme
        }.start().also { h ->
            h.relayout()
            assertEquals(canvas, KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
        }
    }

    private fun KeyboardHarness.pad(): HandwritingPadView = KeyboardHarness.find(inputView) ?: error("no pad")

    // what a finger does on each key: tapped, held (its long press's keys or menu); the keys that put
    // another keyboard up last, each in a keyboard of its own
    private fun gestures(canvas: Boolean): List<String> {
        val ids = harness(canvas).use { h -> h.keys().filter { it.enabled }.mapNotNull { it.id } }
        val out = mutableListOf("keys $ids")
        for (id in ids) {
            harness(canvas).use { h ->
                fun sent(what: String) {
                    out += "$what: " + h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") } +
                        " field ${h.field.text}"
                    h.api.calls.clear()
                }
                h.api.calls.clear()
                h.tap(h.keyNamed(id))
                h.idle()
                sent("$id tap")
                // 注's menu: the views' a list the harness does not read, the scene's below ([readingsMenuAsTheSettingsWordIt])
                if (id != "symbols" && id != "numbers" && id != "reading") {
                    out += "$id long press: " + h.longPress(h.keyNamed(id))
                    sent("$id long press")
                }
            }
        }
        return out
    }

    @Test
    fun everyKeyAsItsView() {
        assertEquals(gestures(false).joinToString("\n"), gestures(true).joinToString("\n"))
    }

    @Test
    fun thePadIsInItsSlotAndWrittenOn() {
        harness(true).use { h ->
            val canvas = KeyboardHarness.find<KeyboardCanvasView>(h.keyboard)!!
            val pad = h.pad()
            assertEquals(padSlot(canvas), Rect(pad.left - canvas.left, pad.top - canvas.top, pad.right - canvas.left, pad.bottom - canvas.top))
            // over the canvas: drawn after it, its touches its own
            val kb = KeyboardHarness.find<HandwritingKeyboard>(h.keyboard)!!
            assertTrue("${kb.indexOfChild(pad)} ${kb.indexOfChild(canvas)}", kb.indexOfChild(pad) > kb.indexOfChild(canvas))
            assertTrue(pad.width > h.keyboard.width / 2)
            // the slot's face is the scene's (a letter key's), drawn under the pad
            assertTrue(canvas.scene!!.slots.single { it.id == HandwritingKeyboard.PAD_SLOT }.ops.isNotEmpty())
        }
    }

    // the scene's own slot at the canvas's size now, not through the keyboard's way to it
    private fun padSlot(canvas: KeyboardCanvasView): Rect =
        Rect().apply { set(canvas.scene!!.slots.single { it.id == HandwritingKeyboard.PAD_SLOT }.bounds) }

    // narrower (one-handed, split): the pad follows the slot of the scene at the new size, off the keys beside it
    @Test
    fun thePadFollowsItsSlotAfterAResize() {
        harness(true).use { h ->
            val kb = KeyboardHarness.find<HandwritingKeyboard>(h.keyboard)!!
            val canvas = KeyboardHarness.find<KeyboardCanvasView>(kb)!!
            val before = padSlot(canvas)
            kb.relayoutAt(kb.width * 7 / 10, kb.height)
            val pad = h.pad()
            val after = padSlot(canvas)
            assertTrue("$before $after", after.width() < before.width())
            assertEquals(after, Rect(pad.left - canvas.left, pad.top - canvas.top, pad.right - canvas.left, pad.bottom - canvas.top))
        }
    }

    // ，。 as they type: with Chinese punctuation on, full width; off (the mapping empty), what is typed is half width
    @Test
    fun commaAndFullStopShownAsTheyType() {
        harness(true).use { h ->
            val kb = KeyboardHarness.find<HandwritingKeyboard>(h.keyboard)!!
            kb.onPunctuationUpdate(mapOf("," to "，", "." to "。"))
            h.idle()
            assertEquals(listOf("，", "。"), listOf(h.keyNamed("comma").label, h.keyNamed("period").label))
            kb.onPunctuationUpdate(emptyMap())
            h.idle()
            assertEquals(listOf(",", "."), listOf(h.keyNamed("comma").label, h.keyNamed("period").label))
        }
    }

    @Test
    fun readingKeyOnAsItsStateAndSaidAsItsView() {
        val said = listOf(false, true).map { canvas ->
            harness(canvas).use { h ->
                val off = h.keyNamed("reading").description
                EngineState.candidateReadingState.value = ReadingStyle.PINYIN
                h.idle()
                h.relayout()
                if (canvas) {
                    val node = KeyboardHarness.find<KeyboardCanvasView>(h.keyboard)!!.keyAt("reading")!!
                    assertTrue(node.states.toString(), org.fcitx.fcitx5.android.scene.KeyState.On in node.states)
                }
                val on = h.keyNamed("reading").description
                EngineState.candidateReadingState.value = ReadingStyle.OFF
                listOf(off, on)
            }
        }
        val name = org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.im_candidate_reading)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(listOf(app.getString(R.string.hw_reading_key_off, name), app.getString(R.string.hw_reading_key_on, name)), said[0])
        assertEquals(said[0], said[1])
    }

    // 注's long press on the scene: each style in the settings' words, the one picked set
    @Test
    fun readingsMenuAsTheSettingsWordIt() {
        harness(true).use { h ->
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            val words = listOf(R.string.im_candidate_reading_off, R.string.im_candidate_reading_pinyin, R.string.im_candidate_reading_zhuyin).map(app::getString)
            assertEquals(words.toSet(), h.longPress(h.keyNamed("reading")).toSet())
            h.api.calls.clear()
            h.longPress(h.keyNamed("reading"), pick = words[1])
            // set once what the recognizer is reading is in (its own thread): waited for in real time
            val until = System.currentTimeMillis() + SET_WITHIN_MS
            while (h.api.calls.none { it.startsWith("setImOptions") } && System.currentTimeMillis() < until) {
                Thread.sleep(POLL_MS)
                h.idle()
            }
            assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("setImOptions") })
        }
    }

    @Test
    fun theLanguageKeyHiddenAsTheSettingSays() {
        for (canvas in listOf(false, true)) {
            prefs.set(AppPrefs.getInstance().keyboard.showLangSwitchKey, false)
            harness(canvas).use { h ->
                assertTrue("canvas $canvas", h.keys().none { it.id == "lang" && it.enabled && !it.bounds.isEmpty })
            }
        }
    }

    private fun View.relayoutAt(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(left, top, left + w, top + h)
    }

    private companion object {
        const val SET_WITHIN_MS = 5_000L
        const val POLL_MS = 10L
    }
}
