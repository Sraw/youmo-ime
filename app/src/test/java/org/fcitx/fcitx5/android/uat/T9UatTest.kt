/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Rect
import android.text.InputType
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.lettersOf
import org.fcitx.fcitx5.android.input.popup.PopupFocus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class T9UatTest {

    private var h: KeyboardHarness? = null

    private fun harness(
        im: InputMethodEntry = KeyboardHarness.PINYIN,
        keyboards: ImKeyboards.Chosen = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS, english = ImKeyboard.NINE_KEYS),
    ) =
        KeyboardHarness(im = im, keyboards = keyboards).also {
            h = it
            it.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
            it.api.enabledIme = it.api.availableIme
        }

    @After
    fun close() {
        h?.close()
        EngineState.syllablesState.value = EngineState.SyllableState()
    }

    /** EngineState.syllables, set from the engine's snapshot on a device; a global, put back in [close]. */
    private fun typing(composing: Boolean, vararg syllables: String) {
        EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.PINYIN, composing, syllables.toList(), syllables.size)
        this.h?.idle()
    }

    private val KeyboardHarness.keys get() = api.calls.filter { it.startsWith("sendKey") || it.startsWith("select") }

    private fun KeyboardHarness.popup(): List<String> = popupChoices().map { it.text }

    private fun KeyboardHarness.cell(label: String): Rect =
        popupChoices().lastOrNull { it.text == label }?.cell ?: error("no $label in the popup: ${popup()}")

    /** In dp from the cell the finger starts on: the popup moves its focus by how far the finger goes, not by where it is. */
    private fun KeyboardHarness.offsetTo(key: ShownKey, label: String): Pair<Float, Float> {
        // its text is drawn centred in it, not laid out to its size
        fun centre(text: String): Pair<Float, Float> = cell(text).let { it.exactCenterX() to it.exactCenterY() }
        val from = centre(key.alt.orEmpty())
        val to = centre(label)
        val step = PopupFocus.STEP / service.resources.displayMetrics.density
        return (to.first - from.first) * step to (to.second - from.second) * step
    }

    private fun KeyboardHarness.longPressTo(key: ShownKey, label: String?) {
        val f = finger(key, at(key))
        advance(700)
        // the popup counts from where the finger is at its first move
        f.move(at(key))
        val (dx, dy) = if (label == null) 0f to 0f else offsetTo(key, label)
        val p = at(key, dx, dy)
        f.move(p)
        f.up(p)
    }

    /** Let go below the key, out of the popup: nothing chosen. */
    private fun KeyboardHarness.popupOf(key: ShownKey): List<String> {
        val f = finger(key, at(key))
        advance(700)
        val shown = popup()
        f.move(at(key))
        val below = KeyboardHarness.Touch(key.width / 2f, key.height * 3f)
        f.move(below)
        f.up(below)
        return shown
    }

    private fun KeyboardHarness.textView(text: String): TextView =
        KeyboardHarness.all(inputView).filterIsInstance<TextView>().last { shown(it) && it.text.toString() == text }

    private fun KeyboardHarness.sentKeys() = api.calls.filter { it.startsWith("sendKey") }.map { Regex("key=([^,]*)").find(it)?.groupValues?.get(1) ?: it }

    // the number pad is on the nine keys' frame, so 123 from them moves none of their keys; the way back starts where 123 was
    // and fills 1's column
    @Test
    @Uat("KB-90")
    fun going123FromTheNineKeysMovesNoKey() {
        val h = harness().start()
        val nine = ((1..9).map { "$it" } + listOf("backspace", "enter", "symbols", "numbers")).associateWith { h.keyNamed(it).bounds }
        h.tap(h.keyNamed("numbers"))
        assertTrue(h.texts().toString(), "=" in h.texts())
        assertRows(
            (1..9).map { Triple("$it", "$it", "the digit where the nine keys' $it is") } + listOf(
                Triple("backspace", "backspace", "⌫ at the top right on both"),
                Triple("enter", "enter", "Return at the bottom right on both"),
                Triple("symbols", "symbols", "符 first in the bottom row, as on every letter board"),
            ),
            { (pad, _, why) -> "$pad: $why" },
        ) { (pad, there, _) -> assertEquals(nine.getValue(there), h.keyNamed(pad).bounds) }
        val back = h.keyNamed("back").bounds
        val numbers = nine.getValue("numbers")
        assertEquals("the way back starts where 123 was", Rect(numbers.left, numbers.top, back.right, numbers.bottom), back)
        assertEquals("返回 fills 1's column", nine.getValue("1").right, back.right)
        assertEquals("符", h.keyNamed("symbols").label)
        assertEquals(h.service.getString(R.string.picker_back), h.keyNamed("back").label)
        h.tap(h.keyNamed("back"))
        assertEquals(nine, nine.keys.associateWith { h.keyNamed(it).bounds })
    }

    @Test
    @Uat("T9-09")
    fun aLongPressedLetterGoesInAsItIs() {
        val h = harness().start()
        val five = h.nineKey("5")
        assertEquals(listOf("l", "j", "K", "5", "J", "L", "k"), h.popupOf(five).takeLast(7))
        h.longPressTo(five, "L")
        assertEquals("L", h.field.text.toString())
        assertEquals(emptyList<String>(), h.sentKeys())
        typing(true, "ni")
        h.api.calls.clear()
        h.longPressTo(h.nineKey("5"), "l")
        // what was typed ended first (the engine puts its first candidate in on reset), then the letter
        assertEquals(listOf("reset()"), h.api.calls.filter { it == "reset()" || it.startsWith("sendKey") || it.startsWith("select") })
        assertEquals("Ll", h.field.text.toString())
    }

    @Test
    @Uat("T9-10")
    fun aDigitKeySwipedUpTypesItsDigit() {
        val h = harness().start()
        val eight = h.nineKey("8")
        // a 30 dp touch with no move between was once taken for a tap, and a tap of 8 then sent the digit too
        h.swipe(eight, 0f, -eight.height.toFloat())
        // the number pad's 8 (the engine types it when nothing is composed), not the key's code
        assertEquals(listOf("sendKey(sym=${FcitxKeyMapping.FcitxKey_KP_0 + 8}, states=536870912, code=0, up=false)"), h.sentKeys())
        assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("select") || it == "offerGlide" })
    }

    @Test
    @Uat("T9-11")
    fun startOverClearsWhatIsTyped() {
        val h = harness().start()
        typing(true, "mi", "ni")
        assertTrue(h.texts().toString(), "ni" in h.texts() && "…" !in h.texts())
        h.api.calls.clear()
        h.tap(h.key("重输"))
        assertEquals(listOf("sendKey(sym=${FcitxKeyMapping.FcitxKey_Escape}, states=536870912, code=0, up=false)"), h.api.calls)
        typing(false)
        assertEquals("", h.field.text.toString())
        assertTrue(h.texts().toString(), "…" in h.texts() && "ni" !in h.texts())
    }

    @Test
    @Uat("T9-17")
    fun aShortSwipeDownWithGlideOnIsATapOfTheKey() {
        AppPrefs.getInstance().keyboard.keyDrawing.setValue(KeyDrawing.Glide)
        try {
            val h = harness().start()
            val five = h.nineKey("5")
            // half a key down: not the most of one, which would be a glide
            val half = five.height / 2f / h.service.resources.displayMetrics.density
            h.touch(five, h.at(five), h.at(five, dy = half / 2, ms = 30), h.at(five, dy = half, ms = 30))
            assertEquals(listOf("sendKey(sym=${UNICODE + Keyboard.pinyinNineKey('5')}, states=536870912, code=0, up=false)"), h.sentKeys())
            assertFalse(h.api.calls.toString(), "offerGlide" in h.api.calls)
        } finally {
            AppPrefs.getInstance().keyboard.keyDrawing.setValue(KeyDrawing.Off)
        }
    }

    @Test
    @Uat("T9-23")
    fun aLongPressedDigitWhileTyping() {
        val h = harness().start()
        typing(true, "ni")
        h.api.calls.clear()
        h.longPressTo(h.nineKey("2"), "2")
        // with the keypad's 2 the engine picks the second candidate, nothing ended first
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_2), h.keypad())
        assertEquals("", h.field.text.toString())
        typing(true, "xi")
        h.api.calls.clear()
        h.swipedUp(h.nineKey("1"))
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_1), h.keypad())
        assertEquals("", h.field.text.toString())
    }

    private fun KeyboardHarness.swipedUp(key: ShownKey) = touch(key, at(key), at(key, dy = -25f, ms = 30), at(key, dy = -50f, ms = 30))

    @Test
    @Uat("T9-22")
    fun aDigitSwipedUpWhileTypingPicksItsCandidate() {
        val h = harness().start()
        typing(true, "ni")
        h.api.calls.clear()
        h.swipedUp(h.nineKey("2"))
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_2), h.keypad())
        assertEquals("", h.field.text.toString())
        // not typing: still the keypad's 2 (the engine types it), so nothing put in the field here
        typing(false)
        h.api.calls.clear()
        h.swipedUp(h.nineKey("2"))
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_2), h.keypad())
        assertEquals("", h.field.text.toString())
    }

    private fun KeyboardHarness.keypad() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
        .map { Regex("sym=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() ?: error("not a keysym: $it") }

    @Test
    @Uat("T9-18")
    fun theNumberPadsDigitsAreDigitsNotTheNineKeys() {
        val h = harness().start()
        h.tap(h.key("123"))
        h.api.calls.clear()
        h.tap(h.key("2"))
        h.tap(h.key("3"))
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_2, FcitxKeyMapping.FcitxKey_KP_3), h.keypad())
        h.tap(h.key(h.service.getString(R.string.picker_back)))
        assertTrue(h.texts().toString(), "重输" in h.texts())
        typing(true, "ni")
        h.tap(h.key("123"))
        h.api.calls.clear()
        h.tap(h.key("1"))
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_1), h.keypad())
    }

    @Test
    @Uat("T9-27")
    fun aLongPressedSymbolAfterTheCandidate() {
        val h = harness().start()
        val two = h.nineKey("2")
        val popup = h.popupOf(two)
        assertEquals(listOf("c", "a", "B", "2", "A", "C", "b"), popup.takeLast(7))
        assertTrue(popup.toString(), "@" in popup.dropLast(7) && "!" in popup.dropLast(7))
        assertEquals("", h.field.text.toString())
        assertEquals(emptyList<String>(), h.sentKeys())
        h.captureBoard("T9UatTest/popup_2")
        typing(true, "ni")
        h.api.calls.clear()
        h.longPressTo(h.nineKey("2"), "!")
        // the mark to the engine, as a key, nothing reset: its punctuation makes it ！ after the candidate
        assertEquals(listOf("!"), h.sentKeys())
        assertFalse(h.api.calls.toString(), "reset()" in h.api.calls)
        assertEquals("", h.field.text.toString())
    }

    @Test
    @Uat("T9-34")
    fun anEmailFieldsColumn() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val email = listOf("@", ".", ".com", "-", "_", "+", ".cn", ".net", ".org")
        fun column() = h.texts().takeLast(email.size)
        assertEquals(email, column())
        assertTrue(h.texts().toString(), "Pinyin" in h.texts())
        h.describedKey(h.service.getString(R.string.a11y_key_switch_language))
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        assertEquals(email, column())
        h.tap(h.textView("@"))
        h.tap(h.textView(".com"))
        assertEquals("@.com", h.field.text.toString())
    }

    @Test
    @Uat("T9-37")
    fun nineKeysEnglishStaysOverARotation() {
        val h = harness(KeyboardHarness.ENGLISH).start()
        h.describedKey("Shift")
        for (q in listOf("+land", "+port")) {
            RuntimeEnvironment.setQualifiers(q)
            h.service.onConfigurationChanged(h.service.resources.configuration)
            h.idle()
            h.relayout()
            val keys = h.texts()
            assertTrue("$q $keys", "ghi" in keys && "English" in keys && "q" !in keys)
            h.describedKey("Shift")
        }
    }

    @Test
    @Uat("T9-38")
    fun anAddressFieldsColumn() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val url = listOf("/", ".", ".com", ":", "-", "_", "?", "=", "&", "#", ".cn", ".net", ".org")
        assertEquals(url, h.texts().takeLast(url.size))
        assertTrue(h.texts().toString(), "Pinyin" in h.texts())
        h.tap(h.textView("/"))
        h.tap(h.textView(".com"))
        assertEquals("/.com", h.field.text.toString())
        typing(true, "mi", "ni")
        assertEquals(listOf("mi", "ni"), h.texts().takeLast(2))
        typing(false)
        assertEquals(url, h.texts().takeLast(url.size))
    }

    @Test
    @Uat("T9-31")
    fun nineKeysEnglishShiftAtASentencesStartOnceAndLocked() {
        val h = harness(KeyboardHarness.ENGLISH).start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val shift = h.describedKey("Shift")
        fun state() = shift.state
        val once = h.service.getString(R.string.a11y_state_shift_once)
        val lock = h.service.getString(R.string.a11y_state_caps_lock)
        fun sent(): List<Int> = h.api.calls.filter { it.startsWith("sendKey(sym=") && "up=false" in it }
            .map { Regex("sym=(\\d+)").find(it)!!.groupValues[1].toInt() - UNICODE }
        fun cased(digits: String, cases: String) = digits.indices.map { Keyboard.nineKey(digits[it], cases[it] == 'U') }
        fun press(digits: String) = digits.forEach { h.tap(h.nineKey(it.toString())) }
        fun typedIn(text: String) {
            h.field.setText(text)
            h.field.setSelection(text.length)
            h.idle()
        }
        assertEquals(once, state())
        assertTrue(h.texts().toString(), "GHI" in h.texts())
        press("43556")
        assertEquals(null, state())
        assertTrue(h.texts().toString(), "ghi" in h.texts())
        assertEquals(cased("43556", "U...."), sent())
        typedIn("Hello ")
        assertEquals(null, state())
        h.api.calls.clear()
        h.tap(shift)
        assertEquals(once, state())
        press("46")
        assertEquals(cased("46", "U."), sent())
        typedIn("Hello Go ")
        h.api.calls.clear()
        h.touch(shift, h.at(shift))
        h.touch(shift, h.at(shift, ms = 50))
        assertEquals(lock, state())
        press("46")
        assertEquals(lock, state())
        assertEquals(cased("46", "UU"), sent())
    }

    @Test
    @Uat("T9-45")
    fun aPasswordFieldIsTheFullKeyboard() {
        // a password's English is the full keyboard whatever English's is
        val h = KeyboardHarness(im = KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS).also { this.h = it }
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // androidengine switches a password to English
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        val keys = h.texts()
        assertTrue(keys.toString(), listOf("q", "w", "e", "r", "t", "y").all { it in keys } && "English" in keys)
        h.tap(h.key("q"))
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
        h.start()
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
        assertTrue(h.texts().toString(), "重输" in h.texts())
        val nine = ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)
        assertEquals(TextKeyboard.Name, lettersOf(InputMethodCatalog.ENGLISH, nine, password = true))
        assertEquals(T9Keyboard.EnglishName, lettersOf(InputMethodCatalog.ENGLISH, nine))
        h.close()
        // a password field's full keyboard, then the nine keys again in the next field, with no input method change to say so
        val e = harness(im = KeyboardHarness.ENGLISH)
        e.start()
        fun nineShown() = KeyboardHarness.find<T9Keyboard>(e.inputView)?.let(e::shown) == true
        assertTrue(e.texts().toString(), nineShown())
        e.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue(e.texts().toString(), "q" in e.texts() && !nineShown())
        e.start()
        assertTrue(e.texts().toString(), nineShown() && "q" !in e.texts())
    }

    @Test
    @Uat("T9-48")
    fun theLongPressPopupIsSmall() {
        val h = harness().start()
        val five = h.nineKey("5")
        val f = h.finger(five, h.at(five))
        h.advance(700)
        val cell = h.cell("j").width()
        assertTrue("cell $cell key ${five.width}", cell * 2 < five.width)
        h.captureBoard("T9UatTest/popup_5")
        h.api.calls.clear()
        f.up(h.at(five))
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_5), h.keypad())
        fun slide(key: ShownKey, dx: Float, dy: Float = 0f, from: KeyboardHarness.Touch = h.at(key)) {
            val g = h.finger(key, from)
            h.advance(700)
            g.move(from)
            val to = KeyboardHarness.Touch(from.x + dx * h.service.resources.displayMetrics.density, from.y + dy * h.service.resources.displayMetrics.density)
            g.move(to)
            g.up(to)
        }
        slide(h.nineKey("5"), 25f)
        assertEquals("J", h.field.text.toString())
        slide(h.nineKey("5"), -400f)
        assertEquals("Jl", h.field.text.toString())
        val k = h.nineKey("5")
        val dp = h.service.resources.displayMetrics.density
        slide(k, 0f, 10f, KeyboardHarness.Touch(k.width / 2f, k.height - 3 * dp))
        assertEquals("Jl", h.field.text.toString())
        slide(h.nineKey("9"), -25f)
        assertEquals("JlX", h.field.text.toString())
        h.close()
        val q = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen()).start()
        val key = q.key("q")
        val g = q.finger(key, q.at(key))
        q.advance(700)
        val full = q.popupChoices().last { it.text == "Q" }.cell.width()
        g.up(q.at(key))
        assertEquals(full, cell)
    }

    // the nine keys are pinyin's keyboard, not an input method: the globe goes on to English's own keyboard
    @Test
    @Uat("T9-58")
    fun theGlobeGoesToEnglishOnItsOwnKeyboard() {
        val h = harness(keyboards = KeyboardHarness.NINE_KEYS).start()
        assertTrue(h.texts().toString(), "重输" in h.texts() && "Pinyin" in h.texts())
        val globe = h.service.getString(R.string.a11y_key_switch_language)
        fun to(im: InputMethodEntry) {
            h.api.calls.clear()
            h.tap(h.describedKey(globe))
            assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("enumerateIme") })
            h.api.inputMethodEntryCached = im
            h.emit(HostEvent.InputMethodChanged(im))
        }
        to(KeyboardHarness.ENGLISH)
        val keys = h.texts()
        assertTrue(keys.toString(), listOf("q", "w", "e", "r", "t", "y").all { it in keys } && "English" in keys)
        to(KeyboardHarness.PINYIN)
        assertTrue(h.texts().toString(), "重输" in h.texts())
        AppPrefs.getInstance().keyboard.setImKeyboard(InputMethodCatalog.ENGLISH, ImKeyboard.NINE_KEYS)
        h.idle()
        to(KeyboardHarness.ENGLISH)
        assertTrue(h.texts().toString(), h.texts().any { it.equals("ghi", ignoreCase = true) } && "English" in h.texts() && "q" !in h.texts())
        h.describedKey("Shift")
        to(KeyboardHarness.PINYIN)
        assertTrue(h.texts().toString(), "重输" in h.texts())
    }
}
