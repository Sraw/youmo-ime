/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
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
 * The nine keys drawn by the scene (060-render-p2 S5a, nine.xml) do what their views did, in each of
 * the four (九键拼音, English's, 双拼's with and without `;`): every key's face and what it is read as, its
 * bubble as it is pressed, its tap, its swipe down, its long press, what reaches the engine and the field;
 * 1 as what is typed has it (分词 while the engine says so). Each key is wired as its view was (U4 A).
 * The column is a native view over the scene's slot `side`, in its box, its items the same either way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class NineSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun noSyllables() {
        EngineState.syllablesState.value = EngineState.SyllableState()
        EngineState.shuangpinScheme = ShuangpinScheme.XIAOHE
    }

    private class Variant(val im: InputMethodEntry, val keyboards: ImKeyboards.Chosen, val scheme: ShuangpinScheme = ShuangpinScheme.XIAOHE)

    private fun harness(canvas: Boolean, v: Variant): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, canvas)
        EngineState.shuangpinScheme = v.scheme
        return KeyboardHarness(ThemePreset.PixelLight, v.im, keyboards = v.keyboards).start().also { h ->
            h.relayout()
            assertEquals(canvas, KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
            assertTrue(KeyboardHarness.all(h.keyboard).any { it is T9Keyboard })
        }
    }

    // what a finger does on each digit key and the right column's, in [v], typing or not
    private fun gestures(canvas: Boolean, v: Variant, composing: Boolean = false): List<String> = harness(canvas, v).use { h ->
        if (composing) {
            EngineState.syllablesState.value = EngineState.SyllableState(v.im.uniqueName, true, listOf("shuang", "zhuang"), 1)
            h.idle()
        }
        val out = mutableListOf<String>()
        fun sent(what: String) {
            out += "$what: " + h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") || it.startsWith("select") } +
                " field ${h.field.text}"
            h.api.calls.clear()
        }
        // the keys (the views' column spacers, under the column, no keys of the scene's)
        out += "faces: " + h.keys().filter { it.id != null && it.id != "side" }.map { "${it.id}=${it.label}/${it.alt}" }
        for (digit in "123456789") {
            val k = { h.nineKey("$digit") }
            h.after(BUBBLE_GONE_MS)
            // what TalkBack reads: its description; a view of none its texts, its letters then its digit
            out += "$digit said: " + (k().description?.takeIf { it.isNotEmpty() } ?: listOfNotNull(k().label, k().alt).joinToString(", "))
            h.press(k())
            out += "$digit pressed: " + h.popupChoices().map { it.text }
            h.release()
            sent("$digit tap")
            h.swipe(k(), 0f, k().bounds.height().toFloat())
            sent("$digit down")
            out += "$digit long press: " + h.longPress(k())
            sent("$digit long press")
        }
        for (id in listOf("0", "reset", "backspace")) {
            val k = h.keys().firstOrNull { it.id == id } ?: continue
            h.after(BUBBLE_GONE_MS)
            h.tap(k)
            sent("$id tap")
        }
        out
    }

    private fun assertSame(v: Variant, composing: Boolean = false): List<String> {
        val views = gestures(false, v, composing)
        val scene = gestures(true, v, composing)
        assertEquals(views.joinToString("\n"), scene.joinToString("\n"))
        return scene
    }

    @Test
    fun pinyinsEveryKeyAsItsView() {
        val scene = assertSame(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS))
        // what was compared is what the keys do: 5's bubble its letters, its long press its letters and digit
        assertTrue(scene.joinToString("\n"), "5 pressed: [JKL]" in scene)
        assertTrue(scene.joinToString("\n"), scene.any { it.startsWith("5 long press: [") && "J" in it && "5" in it && "l" in it })
    }

    @Test
    fun pinyinsOneIsTheSeparatorWhileTyping() {
        val scene = assertSame(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS), composing = true)
        assertTrue(scene.first(), "1=分词/1" in scene.first())
    }

    @Test
    fun englishsEveryKeyAsItsView() {
        val scene = assertSame(Variant(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)))
        assertTrue(scene.first(), "5=jkl/5" in scene.first() || "5=JKL/5" in scene.first())
    }

    @Test
    fun shuangpinsEveryKeyAsItsView() {
        val scene = assertSame(Variant(SHUANGPIN, ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS), ShuangpinScheme.MICROSOFT))
        assertTrue(scene.first(), "5=JKL;/5" in scene.first())
    }

    @Test
    fun shuangpinPlainsEveryKeyAsItsView() {
        val scene = assertSame(Variant(SHUANGPIN, ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS)))
        assertTrue(scene.first(), "5=JKL/5" in scene.first())
    }

    // 连按 on: 5 pressed again soon previews its next letter (its preview found by the scene key's id as by its view's)
    @Test
    fun aDigitPressedAgainPreviewsItsNextLetter() {
        fun bubbles(canvas: Boolean): List<String> {
            prefs.set(AppPrefs.getInstance().keyboard.t9MultiTap, T9MultiTap.Medium)
            return harness(canvas, Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
                h.tap(h.nineKey("5"))
                h.press(h.nineKey("5"))
                val shown = h.popupChoices().map { it.text }
                h.release()
                shown
            }
        }
        assertEquals(listOf("K"), bubbles(false))
        assertEquals(listOf("K"), bubbles(true))
    }

    // the language key as the setting says, shown again as it changes (as ZhuyinSceneGesturesTest's)
    @Test
    fun theLanguageKeyHiddenAndShownAgain() {
        for (canvas in listOf(false, true)) harness(canvas, Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
            val lang = AppPrefs.getInstance().keyboard.showLangSwitchKey
            prefs.set(lang, false)
            h.relayout()
            assertTrue("canvas $canvas", h.keys().none { it.id == "lang" })
            lang.setValue(true)
            h.relayout()
            assertTrue("canvas $canvas", h.keys().any { it.id == "lang" })
        }
    }

    // the column, a native view, in the scene's slot: its box the slot's cell, over the canvas
    @Test
    fun theColumnSitsInTheScenesSlot() = harness(true, Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
        val canvas = KeyboardHarness.all(h.keyboard).filterIsInstance<KeyboardCanvasView>().single()
        val slot = canvas.scene!!.slots.single { it.id == "side" }.bounds
        val column = KeyboardHarness.all(h.keyboard).filterIsInstance<android.widget.ScrollView>().single()
        assertEquals(Math.round(slot.left).toInt(), column.left - canvas.left)
        assertEquals(Math.round(slot.top).toInt(), column.top - canvas.top)
        assertEquals(Math.round(slot.right).toInt(), column.right - canvas.left)
        assertEquals(Math.round(slot.bottom).toInt(), column.bottom - canvas.top)
        val parent = column.parent as android.view.ViewGroup
        assertTrue("the column over the canvas", parent.indexOfChild(column) > parent.indexOfChild(canvas))
    }

    private companion object {
        val SHUANGPIN = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
        const val BUBBLE_GONE_MS = 1000L
    }
}
