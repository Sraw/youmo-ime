/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
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
 * The nine keys drawn by the scene (060-render-p2 S5a, nine.xml) do what their views did, in each of
 * the four (九键拼音, English's, 双拼's with and without `;`): every key's face and what it is read as, its
 * bubble as it is pressed, its tap, its swipe down, its long press, what reaches the engine and the field;
 * 1 as what is typed has it (分词 while the engine says so). Each key is wired from its node (U4 B).
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

    private fun harness(v: Variant): KeyboardHarness {
        EngineState.shuangpinScheme = v.scheme
        return KeyboardHarness(ThemePreset.PixelLight, v.im, keyboards = v.keyboards).start().also { h ->
            h.relayout()
            assertTrue(KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
            assertTrue(KeyboardHarness.all(h.keyboard).any { it is T9Keyboard })
        }
    }

    // what a finger does on each digit key and the right column's, in [v], typing or not
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
        // the keys
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

    @Test
    fun pinyinsEveryKeyAsItsView() {
        val scene = gestures(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS))
        // what the keys do: 5's bubble its letters, its long press its letters and digit
        assertTrue(scene.joinToString("\n"), "5 pressed: [JKL]" in scene)
        assertTrue(scene.joinToString("\n"), scene.any { it.startsWith("5 long press: [") && "J" in it && "5" in it && "l" in it })
    }

    @Test
    fun pinyinsOneIsTheSeparatorWhileTyping() {
        val scene = gestures(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS), composing = true)
        assertTrue(scene.first(), "1=分词/1" in scene.first())
    }

    @Test
    fun englishsEveryKeyAsItsView() {
        val scene = gestures(Variant(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)))
        assertTrue(scene.first(), "5=jkl/5" in scene.first() || "5=JKL/5" in scene.first())
    }

    @Test
    fun shuangpinsEveryKeyAsItsView() {
        val scene = gestures(Variant(SHUANGPIN, ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS), ShuangpinScheme.MICROSOFT))
        assertTrue(scene.first(), "5=JKL;/5" in scene.first())
    }

    @Test
    fun shuangpinPlainsEveryKeyAsItsView() {
        val scene = gestures(Variant(SHUANGPIN, ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS)))
        assertTrue(scene.first(), "5=JKL/5" in scene.first())
    }

    // a digit's letters and digit read with the app's a11y_key_grouped, as qwerty's q，1 is: in Chinese a full-width
    // comma (once the scene's built-in English ", " was read on the nine keys only)
    @Test
    @Config(qualifiers = "zh-rCN-" + KeyboardCanvasViewTest.PORTRAIT)
    fun aDigitIsReadWithTheAppsSeparator() = harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
        assertEquals("ABC，2", h.keys().single { it.id == "2" }.description)
    }

    // 连按 on: 5 pressed again soon previews its next letter (its preview found by the scene key's id as by its view's)
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

    // the language key as the setting says, shown again as it changes (as ZhuyinSceneGesturesTest's)
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

    // the column, a native view, in the scene's slot: its box the slot's cell, over the canvas
    @Test
    fun theColumnSitsInTheScenesSlot() = harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
        val canvas = KeyboardHarness.boardCanvas(h.keyboard)!!
        val slot = canvas.scene!!.slots.single { it.id == "side" }.bounds
        // placed after the scene was made (T9Keyboard.onSceneBuilt), then laid out there
        h.idle()
        h.relayout()
        val column = KeyboardHarness.all(h.keyboard).filterIsInstance<android.widget.ScrollView>().single()
        assertEquals(Math.round(slot.left).toInt(), column.left - canvas.left)
        assertEquals(Math.round(slot.top).toInt(), column.top - canvas.top)
        assertEquals(Math.round(slot.right).toInt(), column.right - canvas.left)
        assertEquals(Math.round(slot.bottom).toInt(), column.bottom - canvas.top)
        val parent = column.parent as android.view.ViewGroup
        assertTrue("the column over the canvas", parent.indexOfChild(column) > parent.indexOfChild(canvas))
    }

    // Return's look told alone (nothing typed changes): the scene drawn anew, its key read as the field's action
    @Test
    fun returnsLookAloneIsDrawn() = harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
        val t9 = KeyboardHarness.all(h.keyboard).filterIsInstance<T9Keyboard>().single()
        val search = ReturnKeyAppearance(R.drawable.ic_baseline_search_24, R.string.a11y_key_action_search)
        assertTrue(h.keys().single { it.id == "enter" }.description != search.label(h.keyboard.context))
        t9.onReturnDrawableUpdate(search)
        h.idle()
        assertEquals(search.label(h.keyboard.context), h.keys().single { it.id == "enter" }.description)
    }

    // typing told alone (Return's look as it was): 1 drawn anew as 分词, and back
    @Test
    fun typingAloneRedrawsOne() = harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
        val t9 = KeyboardHarness.all(h.keyboard).filterIsInstance<T9Keyboard>().single()
        t9.show(EngineState.T9State(composing = true, syllables = listOf("a")))
        h.idle()
        assertEquals("分词", h.nineKey("1").label)
        t9.show(EngineState.T9State())
        h.idle()
        assertTrue(h.nineKey("1").label, h.nineKey("1").label != "分词")
    }

    // a glide read where the scene draws the digit keys, not where their views (laid out, not drawn) are: the
    // letters' centres the engine gets, in key widths, as far apart as the keys shown, in each of the keyboard's forms
    @Test
    fun aGlideIsReadWhereTheSceneDrawsTheKeys() {
        assertGlidesOnTheScene("docked")
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        assertGlidesOnTheScene("floating")
        prefs.set(ThemeManager.prefs.floatingKeyboard, false)
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        assertGlidesOnTheScene("one-handed")
    }

    private fun assertGlidesOnTheScene(form: String, before: (KeyboardHarness) -> Unit = {}) {
        prefs.set(AppPrefs.getInstance().keyboard.glideTyping, true)
        harness(Variant(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)).use { h ->
            before(h)
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

    // each key wired from nine.xml's node (U4 B) as the views' KeyDef has it, in each of the four: shortcut,
    // both ways' swipe, what a tap, a hold, a swipe, a double tap and a long press do, its bubble, its long press's
    // keys (`popup from="nine"`) and its menu, sound, space/backspace, enabled; 123's long press (unicode, every
    // board's) the one exception
    @Test
    fun eachKeyIsWiredAsItsViewWas() {
        val app = PlaceholderStrings(RuntimeEnvironment.getApplication())
        val letters = NineKeyLetters.STANDARD
        val variants = mapOf(
            "pinyin" to { T9Keyboard(app, ThemePreset.PixelLight, letters) },
            "english" to { T9Keyboard(app, ThemePreset.PixelLight, letters, english = true) },
            "shuangpin" to { T9Keyboard(app, ThemePreset.PixelLight, shuangpin = true) },
            "shuangpin plain" to { T9Keyboard(app, ThemePreset.PixelLight, shuangpin = true, semicolon = false) },
        )
        val olds = mapOf(
            "pinyin" to T9Keyboard.layout(letters),
            "english" to T9Keyboard.layout(letters, english = true),
            "shuangpin" to T9Keyboard.layout(NineKeyLetters.STANDARD, shuangpin = true),
            "shuangpin plain" to T9Keyboard.layout(NineKeyLetters.STANDARD, shuangpin = true, semicolon = false),
        )
        for ((name, make) in variants) {
            val k = make().laidOut()
            val canvas = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single()
            val old = olds.getValue(name).flatten().filter { it.name != "side" && it.behaviors.isNotEmpty() }.associateBy { idOf(it) }
            val shown = canvas.scene!!.keys
            assertEquals(name, old.keys, shown.map { it.id }.toSet())
            val wasLines = mutableListOf<String>()
            val nowLines = mutableListOf<String>()
            for (node in shown) {
                val was = old.getValue(node.id)
                val now = k.sceneKey(node)!!.def as SceneKeyDef
                val at = "$name ${node.id}"
                assertEquals(at, was.shortcut, now.shortcut)
                assertEquals(at, was.swipesBothWays, now.swipesBothWays)
                // 123's long press is every board's (unicode): the old 九宫格 view had none
                val long = if (node.id == "numbers") setOf("long ${KeyAction.UnicodeAction}") else emptySet()
                assertEquals(at, actions(was) + long, actions(now))
                assertEquals(at, popups(was), popups(now))
                // its sound, its space's and backspace's sideways swipe, and whether it takes a touch (a blank view
                // took none): as its view's
                assertEquals(at, was.appearance.soundEffect, now.sound)
                assertEquals(at, was is SpaceKey, now.isSpace)
                assertEquals(at, was is BackspaceKey, now.isBackspace)
                assertEquals(at, was !is BlankKey, k.sceneKey(node)!!.keyEnabled)
                wasLines += WiringSnapshot.line(node.id, was.shortcut, was.swipesBothWays, actions(was) + long, popups(was), was.appearance.soundEffect, was is SpaceKey, was is BackspaceKey, was !is BlankKey)
                nowLines += WiringSnapshot.line(node.id, now.shortcut, now.swipesBothWays, actions(now), popups(now), now.sound, now.isSpace, now.isBackspace, k.sceneKey(node)!!.keyEnabled)
            }
            WiringSnapshot.check("nine-${name.replace(' ', '-')}", wasLines, nowLines)
        }
    }

    // the digit keys' long press, written out here rather than read from T9Keyboard: the views' and the scene's
    // (ninePopup / onePopup, nine.xml's `popup from="nine"`) alike
    @Test
    fun aDigitKeysLongPressIsItsCapitalsDigitLettersAndSymbols() {
        val app = PlaceholderStrings(RuntimeEnvironment.getApplication())
        fun KeyDef.Popup?.said() = (this as KeyDef.Popup.Keyboard.Explicit).let { "${it.items.toList()} ${it.symbolsFrom} ${it.inRow} ${it.asIs}" }
        fun sceneOf(k: T9Keyboard, id: String): KeyDef.Popup.Keyboard.Explicit? {
            val canvas = KeyboardHarness.all(k.laidOut()).filterIsInstance<KeyboardCanvasView>().single()
            return k.sceneKey(canvas.keyAt(id)!!)!!.def.popup!!.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>().singleOrNull()
        }
        val pinyin2 = "[A, B, C, 2, a, b, c, @, !, \"] 7 3 false"
        val shuangpin5 = "[J, K, L, :, 5, j, k, l, ;, #, (, )] 9 4 false"
        val english1 = "[:, ;, \", (, ), @, &, /, *, ~] 10 -1 true"
        assertEquals(pinyin2, T9Keyboard.ninePopup('2', "ABC", NineKeySymbols.STANDARD.symbols('2')).said())
        assertEquals(shuangpin5, T9Keyboard.ninePopup('5', "JKL;", NineKeySymbols.STANDARD.symbols('5')).said())
        assertEquals(english1, T9Keyboard.onePopup(true, NineKeySymbols.STANDARD).said())
        assertEquals(pinyin2, sceneOf(T9Keyboard(app, ThemePreset.PixelLight), "2").said())
        assertEquals(shuangpin5, sceneOf(T9Keyboard(app, ThemePreset.PixelLight, shuangpin = true), "5").said())
        assertEquals(english1, sceneOf(T9Keyboard(app, ThemePreset.PixelLight, english = true), "1").said())
    }

    // a view's key by the scene's id: its name, else the function key it is
    private fun idOf(def: KeyDef) = when (def) {
        is BackspaceKey -> "backspace"
        is CapsKey -> "shift"
        is SpaceKey -> "space"
        is LanguageKey -> "lang"
        is ReturnKey -> "enter"
        is LayoutSwitchKey -> if ((def.appearance as KeyDef.Appearance.Text).displayText == "符") "symbols" else "numbers"
        else -> def.name ?: "?"
    }

    private fun actions(def: KeyDef) = def.behaviors.map {
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
            is KeyDef.Popup.AltPreview -> "bubble ${p.content} ${p.alternative}"
            is KeyDef.Popup.Preview -> "bubble ${p.content}"
            is KeyDef.Popup.Keyboard.Explicit -> "keys ${p.items.toList()} ${p.symbolsFrom} ${p.asIs} ${p.inRow}"
            is KeyDef.Popup.Keyboard.Preset -> "preset $p"
            is KeyDef.Popup.Menu -> "menu " + p.items.map { "${it.label} ${RuntimeEnvironment.getApplication().resources.getResourceEntryName(it.icon)} ${it.action}" }
            else -> "$p"
        }
    }.toSet()

    private companion object {
        val SHUANGPIN = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
        const val BUBBLE_GONE_MS = 1000L
    }
}
