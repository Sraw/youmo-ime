/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.host.ChinesePunctuation
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The nine keys in each of the four variants: every key's face, reading, bubble, tap, swipe and long press, held to
 * the committed record ([GestureSnapshot]). The column is a native view over the scene's slot `side`.
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

    private fun harness(v: Variant): KeyboardHarness {
        EngineState.shuangpinScheme = v.scheme
        return KeyboardHarness(TestThemes.PixelLight, v.im, keyboards = v.keyboards).start().also { h ->
            h.relayout()
            assertTrue(KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
            assertTrue(KeyboardHarness.all(h.keyboard).any { it is T9Keyboard })
        }
    }

    private fun gestures(v: Variant, composing: Boolean = false): List<String> = harness(v).use { h ->
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
        out += "faces: " + h.keys().filter { it.id != null && it.id != "side" }.map { "${it.id}=${it.label}/${it.alt}" }
        for (digit in "123456789") {
            val k = { h.nineKey("$digit") }
            h.after(BUBBLE_GONE_MS)
            // a view of no description: its texts, letters then digit
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

    @Test
    fun pinyinsEveryKey() {
        val scene = gestures(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS))
        GestureSnapshot.check("nine-pinyin", scene)
        assertTrue(scene.joinToString("\n"), "5 pressed: [JKL]" in scene)
        assertTrue(scene.joinToString("\n"), scene.any { it.startsWith("5 long press: [") && "J" in it && "5" in it && "l" in it })
    }

    // shown as typed: ，。？！ with Chinese punctuation on, , . ? ! off, toggled with the keyboard up
    @Test
    fun pinyinsOneAndColumnTypeAsThePunctuationMapsThem() {
        val out = harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
            val out = mutableListOf<String>()
            for ((mode, marks) in listOf("on" to ChinesePunctuation.map, "off" to emptyMap())) {
                h.chinesePunctuation(KeyboardHarness.PINYIN, marks)
                val column = column(h)
                out += "$mode: 1=${h.nineKey("1").label} column=${column.map { it.text }}"
                val before = h.field.text.length
                h.after(BUBBLE_GONE_MS)
                h.tap(h.nineKey("1"))
                h.after(BUBBLE_GONE_MS)
                h.tap(column.first())
                h.tap(column[3])
                out += "$mode typed: ${h.field.text.substring(before)}"
            }
            out
        }
        GestureSnapshot.check("nine-pinyin-punctuation", out)
        assertEquals(listOf("on: 1=，。？！ column=[，, 。, ？, ！, …, 、, ：, ～, @]", "on typed: ，，！", "off: 1=,.?! column=[,, ., ?, !, …, 、, :, ~, @]", "off typed: ,,!"), out)
    }

    private fun column(h: KeyboardHarness): List<TextView> = KeyboardHarness.all(h.keyboard).filterIsInstance<ScrollView>().first { h.shown(it) }
        .let { it.getChildAt(0) as ViewGroup }.children.filterIsInstance<TextView>().filter { it.isVisible }.toList()

    @Test
    fun pinyinsOneIsTheSeparatorWhileTyping() {
        val scene = gestures(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS), composing = true)
        assertTrue(scene.first(), "1=分词/1" in scene.first())
    }

    @Test
    fun englishsEveryKey() {
        val scene = gestures(Variant(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)))
        GestureSnapshot.check("nine-english", scene)
        assertTrue(scene.first(), "5=jkl/5" in scene.first())
    }

    @Test
    fun shuangpinsEveryKey() {
        val scene = gestures(Variant(SHUANGPIN, ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS), ShuangpinScheme.MICROSOFT))
        GestureSnapshot.check("nine-shuangpin-microsoft", scene)
        assertTrue(scene.first(), "5=JKL/5" in scene.first())
        assertTrue(scene.first(), "1=,.?!/1," in scene.first())
    }

    @Test
    fun shuangpinPlainsEveryKey() {
        val scene = gestures(Variant(SHUANGPIN, ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS)))
        GestureSnapshot.check("nine-shuangpin-plain", scene)
        assertTrue(scene.first(), "5=JKL/5" in scene.first())
    }

    // read with the app's a11y_key_grouped: in Chinese a full-width comma
    @Test
    @Config(qualifiers = "zh-rCN-" + KeyboardCanvasViewTest.PORTRAIT)
    fun aDigitIsReadWithTheAppsSeparator() = harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
        assertEquals("ABC，2", h.keys().single { it.id == "2" }.description)
    }

    // 连按 on: 5 pressed again soon previews its next letter
    @Test
    fun aDigitPressedAgainPreviewsItsNextLetter() {
        fun bubbles(): List<String> {
            prefs.set(AppPrefs.getInstance().keyboard.t9MultiTap, T9MultiTap.Medium)
            return harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
                h.tap(h.nineKey("5"))
                h.press(h.nineKey("5"))
                val shown = h.popupChoices().map { it.text }
                h.release()
                shown
            }
        }
        assertEquals(listOf("K"), bubbles())
    }

    @Test
    fun theLanguageKeyHiddenAndShownAgain() {
        harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
            val lang = AppPrefs.getInstance().keyboard.showLangSwitchKey
            prefs.set(lang, false)
            h.relayout()
            assertTrue(h.keys().none { it.id == "lang" })
            lang.setValue(true)
            h.relayout()
            assertTrue(h.keys().any { it.id == "lang" })
        }
    }

    @Test
    fun theColumnSitsInTheScenesSlot() = harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
        val canvas = KeyboardHarness.boardCanvas(h.keyboard)!!
        val slot = canvas.scene!!.slots.single { it.id == "side" }.bounds
        val column = KeyboardHarness.all(h.keyboard).filterIsInstance<android.widget.ScrollView>().single()
        assertEquals(Math.round(slot.left).toInt(), column.left - canvas.left)
        assertEquals(Math.round(slot.top).toInt(), column.top - canvas.top)
        assertEquals(Math.round(slot.right).toInt(), column.right - canvas.left)
        assertEquals(Math.round(slot.bottom).toInt(), column.bottom - canvas.top)
        val parent = column.parent as android.view.ViewGroup
        assertTrue("the column beside the canvas", canvas.parent === parent)
        assertTrue("the column over the canvas", parent.indexOfChild(column) > parent.indexOfChild(canvas))
    }

    @Test
    fun returnsLookAloneIsDrawn() = harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
        val t9 = KeyboardHarness.all(h.keyboard).filterIsInstance<T9Keyboard>().single()
        val search = ReturnKeyAppearance(R.drawable.ic_baseline_search_24, R.string.a11y_key_action_search)
        assertTrue(h.keys().single { it.id == "enter" }.description != search.label(h.keyboard.context))
        t9.onReturnDrawableUpdate(search)
        h.idle()
        assertEquals(search.label(h.keyboard.context), h.keys().single { it.id == "enter" }.description)
    }

    @Test
    fun typingAloneRedrawsOne() = harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
        val t9 = KeyboardHarness.all(h.keyboard).filterIsInstance<T9Keyboard>().single()
        t9.show(T9State(composing = true, syllables = listOf("a")))
        h.idle()
        assertEquals("分词", h.nineKey("1").label)
        t9.show(T9State())
        h.idle()
        assertTrue(h.nineKey("1").label, h.nineKey("1").label != "分词")
    }

    // read where the scene draws the keys, not where their views are laid out: the centres as far apart as the keys shown
    @Test
    fun aGlideIsReadWhereTheSceneDrawsTheKeys() {
        assertGlidesOnTheScene("docked")
        prefs.setStyle(KeyboardStyle.FLOATING)
        assertGlidesOnTheScene("floating")
        prefs.setStyleDefault()
        prefs.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right)
        assertGlidesOnTheScene("one-handed")
    }

    private fun assertGlidesOnTheScene(form: String) {
        prefs.set(AppPrefs.getInstance().keyboard.keyDrawing, KeyDrawing.Glide)
        harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
            val keys = "2639".map { h.nineKey("$it") }
            val first = keys.first().bounds
            h.drag(keys.first(), first.exactCenterX(), first.exactCenterY(), steps = 0, lift = false)
            for (k in keys.drop(1)) h.move(k.bounds.exactCenterX(), k.bounds.exactCenterY())
            h.move(keys.last().bounds.exactCenterX(), keys.last().bounds.exactCenterY(), steps = 1, lift = true)
            val layout = h.api.glides.single().layout
            val a = layout.centre('a')
            for ((c, i) in listOf('m' to 1, 'd' to 2, 'w' to 3)) {
                val k = keys[i].bounds
                val shown = (k.exactCenterX() - first.exactCenterX()) / first.width() to (k.exactCenterY() - first.exactCenterY()) / first.width()
                val read = layout.centre(c).let { (it.first - a.first) to (it.second - a.second) * GlideTouch.unitY(first.height().toFloat()) / first.width() }
                assertEquals("$form $c x", shown.first, read.first, 0.02f)
                assertEquals("$form $c y", shown.second, read.second, 0.02f)
            }
        }
    }

    // each key's wiring from nine.xml as committed; a named key's long press is the resolver's
    @Test
    @Uat("QU-12")
    fun eachKeyIsWiredAsCommitted() {
        val app = PlaceholderStrings(RuntimeEnvironment.getApplication())
        val letters = NineKeyLetters.STANDARD
        val variants = mapOf(
            "pinyin" to { T9Keyboard(app, TestThemes.PixelLight, letters, semicolon = false) },
            "english" to { T9Keyboard(app, TestThemes.PixelLight, letters, english = true, semicolon = false) },
            "shuangpin" to { T9Keyboard(app, TestThemes.PixelLight, shuangpin = true, semicolon = true) },
            "shuangpin plain" to { T9Keyboard(app, TestThemes.PixelLight, shuangpin = true, semicolon = false) },
        )
        for ((name, make) in variants) {
            val k = make().laidOut()
            val canvas = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single()
            val lines = canvas.scene!!.keys.map { node ->
                val now = k.sceneKey(node)!!.def as SceneKeyDef
                WiringSnapshot.line(node.id, now.shortcut, now.swipesBothWays, actions(now), popups(now), WiringSnapshot.layoutSound(now.systemSound), now.isSpace, now.isBackspace, k.sceneKey(node)!!.keyEnabled)
            }
            WiringSnapshot.check("nine-${name.replace(' ', '-')}", lines)
        }
    }

    // a named key's swipe is its name's (KeySwipes asks the resolver), not the node's: left out
    private fun actions(def: KeyDef) = def.behaviors.filterNot { def.shortcut != null && it is KeyDef.Behavior.Swipe }.map {
        when (it) {
            is KeyDef.Behavior.Press -> "press ${it.action}"
            is KeyDef.Behavior.LongPress -> "long ${it.action}"
            is KeyDef.Behavior.Repeat -> "repeat ${it.action}"
            is KeyDef.Behavior.Swipe -> "swipe ${it.action}"
            is KeyDef.Behavior.DoubleTap -> "double ${it.action}"
        }
    }.toSet()

    private fun popups(def: KeyDef) = def.popup.orEmpty().map { p ->
        when (p) {
            is KeyDef.Popup.AltPreview -> if (def.shortcut != null) "bubble ${p.content}" else "bubble ${p.content} ${p.alternative}"
            is KeyDef.Popup.Preview -> "bubble ${p.content}"
            is KeyDef.Popup.Keyboard.Explicit -> "keys ${p.items.toList()} ${p.symbolsFrom} ${p.asIs} ${p.inRow}"
            is KeyDef.Popup.Keyboard.Preset -> "preset $p"
            is KeyDef.Popup.Menu -> "menu " + p.items.map { "${it.label} ${RuntimeEnvironment.getApplication().resources.getResourceEntryName(it.icon)} ${it.action}" }
            else -> "$p"
        }
    }.toSet()

    private companion object {
        val SHUANGPIN = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "双", "zh_CN")
        const val BUBBLE_GONE_MS = 1000L
    }
}
