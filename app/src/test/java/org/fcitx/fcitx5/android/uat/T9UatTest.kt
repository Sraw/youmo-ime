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

/** UAT cases of the nine keys (uat/03), on the whole keyboard over a fake engine. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class T9UatTest {

    private var h: KeyboardHarness? = null

    // B0-九键: pinyin on the nine keys; English on them too where a case switches to it (B0-九键英文)
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

    /**
     * What the engine tells the nine keys of what is typed (EngineState.syllables, set from its snapshot
     * on a device): composing, and the syllables the column offers. A global, put back in [close].
     */
    private fun typing(composing: Boolean, vararg syllables: String) {
        EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.PINYIN, composing, syllables.toList(), syllables.size)
        this.h?.idle()
    }

    private val KeyboardHarness.keys get() = api.calls.filter { it.startsWith("sendKey") || it.startsWith("select") }

    /** The long press's popup: the texts of the popup's cells, as they are laid out (rows top to bottom). */
    private fun KeyboardHarness.popup(): List<String> = popupChoices().map { it.text }

    /** The popup's cell showing [label] (its frame, the text drawn centred in it), whichever draws the popup. */
    private fun KeyboardHarness.cell(label: String): Rect =
        popupChoices().lastOrNull { it.text == label }?.cell ?: error("no $label in the popup: ${popup()}")

    /**
     * The offset in dp from the popup's cell the finger starts on (the key's digit) to its cell
     * [label]: the popup moves its focus by how far the finger goes, not by where it is.
     */
    private fun KeyboardHarness.offsetTo(key: ShownKey, label: String): Pair<Float, Float> {
        // a cell's frame: its text is drawn centred in it, not laid out to its size
        fun centre(text: String): Pair<Float, Float> = cell(text).let { it.exactCenterX() to it.exactCenterY() }
        val from = centre(key.alt.orEmpty())
        val to = centre(label)
        // the focus moves a key for less than a key's width of finger (PopupFocus.STEP)
        val step = PopupFocus.STEP / service.resources.displayMetrics.density
        return (to.first - from.first) * step to (to.second - from.second) * step
    }

    /** A long press on [key], held, the finger then moved onto the popup's [label] (null: not moved) and let go. */
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

    /** Held till its popup shows, the popup's texts read, then let go below the key, out of the popup: nothing chosen. */
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

    /** A shown text view of [text] (a column item, a cell), the last such. */
    private fun KeyboardHarness.textView(text: String): TextView =
        KeyboardHarness.all(inputView).filterIsInstance<TextView>().last { shown(it) && it.text.toString() == text }

    // what the engine was sent: the digits and letters as fcitx key names
    private fun KeyboardHarness.sent() = api.calls.filter { it.startsWith("sendKey") }.map { Regex("key=([^,]*)").find(it)?.groupValues?.get(1) ?: it }

    @Test
    @Uat("T9-09")
    fun aLongPressedLetterGoesInAsItIs() {
        val h = harness().start()
        val five = h.nineKey("5")
        // its letters and digit in a row, the finger starting on the digit
        assertEquals(listOf("J", "K", "L", "5", "j", "k", "l"), h.popupOf(five).takeLast(7))
        h.longPressTo(five, "L")
        assertEquals("L", h.field.text.toString())
        assertEquals(emptyList<String>(), h.sent())
        // while typing: the first candidate goes in first, then the letter
        typing(true, "ni")
        h.api.calls.clear()
        h.longPressTo(h.nineKey("5"), "l")
        // what was typed ended first (the engine puts its first candidate in on reset, T9-23), then the letter
        assertEquals(listOf("reset()"), h.api.calls.filter { it == "reset()" || it.startsWith("sendKey") || it.startsWith("select") })
        assertEquals("Ll", h.field.text.toString())
    }

    @Test
    @Uat("T9-10")
    fun aDigitKeySwipedUpTypesItsDigit() {
        val h = harness().start()
        val eight = h.nineKey("8")
        // a key's height up. A 30 dp touch with no move between was once taken for a tap, and a tap of 8
        // then sent the digit too: this passed for the wrong reason
        h.swipe(eight, 0f, -eight.height.toFloat())
        // the digit itself, as the number pad's 8 (the engine types it when nothing is composed),
        // not the key's code (Keyboard.pinyinNineKey)
        assertEquals(listOf("sendKey(sym=${FcitxKeyMapping.FcitxKey_KP_0 + 8}, states=536870912, code=0, up=false)"), h.sent())
        assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("select") || it == "offerGlide" })
    }

    @Test
    @Uat("T9-11")
    fun startOverClearsWhatIsTyped() {
        val h = harness().start()
        typing(true, "mi", "ni")
        assertTrue(h.texts().toString(), "ni" in h.texts() && "，" !in h.texts())
        h.api.calls.clear()
        h.tap(h.key("重输"))
        // Escape: the engine drops what is typed
        assertEquals(listOf("sendKey(sym=${FcitxKeyMapping.FcitxKey_Escape}, states=536870912, code=0, up=false)"), h.api.calls)
        typing(false)
        assertEquals("", h.field.text.toString())
        assertTrue(h.texts().toString(), "，" in h.texts() && "ni" !in h.texts())
    }

    @Test
    @Uat("T9-17")
    fun aShortSwipeWithGlideOnIsStillTheDigit() {
        AppPrefs.getInstance().keyboard.keyDrawing.setValue(KeyDrawing.Glide)
        try {
            val h = harness().start()
            val five = h.nineKey("5")
            // half a key down: not the most of one, which would be a glide
            val half = five.height / 2f / h.service.resources.displayMetrics.density
            h.touch(five, h.at(five), h.at(five, dy = half / 2, ms = 30), h.at(five, dy = half, ms = 30))
            // the key's press: its code, as a tap sends it (Keyboard.pinyinNineKey)
            assertEquals(listOf("sendKey(sym=${UNICODE + Keyboard.pinyinNineKey('5')}, states=536870912, code=0, up=false)"), h.sent())
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
        // what was typed ended first (the engine puts its first candidate in on reset), then the digit as it is
        assertEquals(listOf("reset()"), h.api.calls.filter { it == "reset()" || it.startsWith("sendKey") || it.startsWith("select") })
        // after the candidate the engine put in on reset (NineKeysHostUatTest.resetPutsTheFirstCandidateIn)
        assertEquals("2", h.field.text.toString())
        // 1 swiped up while typing: the keypad's 1, with which the engine picks the first
        // candidate, no 1 (NineKeysHostUatTest.theNumberPadsDigitsAreTypedOrPick)
        typing(true, "xi")
        h.api.calls.clear()
        h.swipedUp(h.nineKey("1"))
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_1), h.keypad())
        assertEquals("2", h.field.text.toString())
    }

    // past the key's swipe threshold (36 dp) straight down, as a finger swipes
    private fun KeyboardHarness.swipedUp(key: ShownKey) = touch(key, at(key), at(key, dy = -25f, ms = 30), at(key, dy = -50f, ms = 30))

    @Test
    @Uat("T9-22")
    fun aDigitSwipedUpWhileTypingPicksItsCandidate() {
        val h = harness().start()
        typing(true, "ni")
        h.api.calls.clear()
        h.swipedUp(h.nineKey("2"))
        // the keypad's 2, not the key's ABC: the engine picks the second candidate with it, as the
        // number pad's 2 (NineKeysHostUatTest.aSwipedDigitPicksItsCandidate); no digit in the field
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_2), h.keypad())
        assertEquals("", h.field.text.toString())
        // not typing: the digit itself, still the keypad's 2 in a plain field (the engine types it; an
        // address field types it as is, the URL keys' case), so nothing put in the field here
        typing(false)
        h.api.calls.clear()
        h.swipedUp(h.nineKey("2"))
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_2), h.keypad())
        assertEquals("", h.field.text.toString())
    }

    // the keysyms sent down
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
        // the keypad's 2 and 3, never the nine keys' ABC and DEF: the engine types them as they are
        // (NineKeysHostUatTest.theNumberPadsDigitsAreTypedOrPick)
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_2, FcitxKeyMapping.FcitxKey_KP_3), h.keypad())
        h.tap(h.key("ABC"))
        assertTrue(h.texts().toString(), "重输" in h.texts())
        typing(true, "ni")
        h.tap(h.key("123"))
        h.api.calls.clear()
        h.tap(h.key("1"))
        // KP_1 while typing: the engine picks the first candidate with it, as the full keyboard's 1
        assertEquals(listOf(FcitxKeyMapping.FcitxKey_KP_1), h.keypad())
    }

    @Test
    @Uat("T9-27")
    fun aLongPressedSymbolAfterTheCandidate() {
        val h = harness().start()
        val two = h.nineKey("2")
        val popup = h.popupOf(two)
        // the symbols a row above the letters and digit
        assertEquals(listOf("A", "B", "C", "2", "a", "b", "c"), popup.takeLast(7))
        assertTrue(popup.toString(), "@" in popup.dropLast(7) && "!" in popup.dropLast(7))
        // let go out of it: nothing
        assertEquals("", h.field.text.toString())
        assertEquals(emptyList<String>(), h.sent())
        h.captureBoard("T9UatTest/popup_2")
        typing(true, "ni")
        h.api.calls.clear()
        h.longPressTo(h.nineKey("2"), "!")
        // the mark to the engine, as a key, nothing reset: its punctuation makes it ！ after the
        // candidate (NineKeysHostUatTest.aMarkWhileTypingCommitsTheCandidateThenTheChineseMark)
        assertEquals(listOf("!"), h.sent())
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
        // the bottom row as ever: space with the name, the globe
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
        // typing pinyin: its syllables
        typing(true, "mi", "ni")
        assertEquals(listOf("mi", "ni"), h.texts().takeLast(2))
        typing(false)
        assertEquals(url, h.texts().takeLast(url.size))
    }

    // what English makes of the keys sent (Hello, Go, GO): NineKeysUatTest
    @Test
    @Uat("T9-31")
    fun nineKeysEnglishShiftAtASentencesStartOnceAndLocked() {
        val h = harness(KeyboardHarness.ENGLISH).start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val shift = h.describedKey("Shift")
        fun state() = shift.state
        val once = h.service.getString(R.string.a11y_state_shift_once)
        val lock = h.service.getString(R.string.a11y_state_caps_lock)
        // the nine keys' English keys sent, each in its case (Keyboard.nineKey), down only
        fun sent(): List<Int> = h.api.calls.filter { it.startsWith("sendKey(sym=") && "up=false" in it }
            .map { Regex("sym=(\\d+)").find(it)!!.groupValues[1].toInt() - UNICODE }
        fun cased(digits: String, cases: String) = digits.indices.map { Keyboard.nineKey(digits[it], cases[it] == 'U') }
        fun press(digits: String) = digits.forEach { h.tap(h.nineKey(it.toString())) }
        fun typedIn(text: String) {
            h.field.setText(text)
            h.field.setSelection(text.length)
            h.idle()
        }
        // a sentence's start: Shift on by itself, the keys in capitals
        assertEquals(once, state())
        assertTrue(h.texts().toString(), "GHI" in h.texts())
        press("43556")
        assertEquals(null, state())
        assertTrue(h.texts().toString(), "ghi" in h.texts())
        assertEquals(cased("43556", "U...."), sent())
        // English's commit of the word and its space (NineKeysUatTest): mid-sentence, Shift stays off
        typedIn("Hello ")
        assertEquals(null, state())
        h.api.calls.clear()
        h.tap(shift)
        assertEquals(once, state())
        press("46")
        assertEquals(cased("46", "U."), sent())
        typedIn("Hello Go ")
        h.api.calls.clear()
        // tapped twice quickly: locked, every letter a capital
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
        // English on the 26 keys, as by default: a password's English is the full keyboard whatever English's is
        val h = KeyboardHarness(im = KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS).also { this.h = it }
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // androidengine switches a password to English (PR-03)
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        val keys = h.texts()
        assertTrue(keys.toString(), listOf("q", "w", "e", "r", "t", "y").all { it in keys } && "English" in keys)
        h.tap(h.key("q"))
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
        h.start()
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
        assertTrue(h.texts().toString(), "重输" in h.texts())
        // English set to the nine keys: still the full keyboard in a password field
        val nine = ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)
        assertEquals(TextKeyboard.Name, lettersOf(InputMethodCatalog.ENGLISH, nine, password = true))
        assertEquals(T9Keyboard.EnglishName, lettersOf(InputMethodCatalog.ENGLISH, nine))
        h.close()
        // English on the nine keys: a password field's full keyboard, then the nine keys again in the next
        // field, with no input method change to say so
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
        // far narrower than the nine keys' key
        assertTrue("cell $cell key ${five.width}", cell * 2 < five.width)
        h.captureBoard("T9UatTest/popup_5")
        f.up(h.at(five))
        assertEquals("5", h.field.text.toString())
        // a finger moved: about half a finger right, then far left
        fun slide(key: ShownKey, dx: Float, dy: Float = 0f, from: KeyboardHarness.Touch = h.at(key)) {
            val g = h.finger(key, from)
            h.advance(700)
            g.move(from)
            val to = KeyboardHarness.Touch(from.x + dx * h.service.resources.displayMetrics.density, from.y + dy * h.service.resources.displayMetrics.density)
            g.move(to)
            g.up(to)
        }
        slide(h.nineKey("5"), 25f)
        assertEquals("5j", h.field.text.toString())
        slide(h.nineKey("5"), -400f)
        assertEquals("5jJ", h.field.text.toString())
        // from 3 dp above its bottom edge, 10 dp down: just off the key, nothing
        val k = h.nineKey("5")
        val dp = h.service.resources.displayMetrics.density
        slide(k, 0f, 10f, KeyboardHarness.Touch(k.width / 2f, k.height - 3 * dp))
        assertEquals("5jJ", h.field.text.toString())
        // 9 at the screen's right: last in its row (060-edge-popup), z a little left of it
        slide(h.nineKey("9"), -25f)
        assertEquals("5jJz", h.field.text.toString())
        // the full keyboard's cells are as small
        h.close()
        val q = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen()).start()
        val key = q.key("q")
        val g = q.finger(key, q.at(key))
        q.advance(700)
        val full = q.popupChoices().last { it.text == "Q" }.cell.width()
        g.up(q.at(key))
        assertEquals(full, cell)
    }

    // the nine keys are pinyin's keyboard, not an input method (I1): the globe goes on to English on
    // English's own keyboard, the 26 keys till the nine keys are chosen for it (Q3)
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
        // English's keyboard set to the nine keys on its page: 九键英文, pinyin's as it was
        AppPrefs.getInstance().keyboard.setImKeyboard(InputMethodCatalog.ENGLISH, ImKeyboard.NINE_KEYS)
        h.idle()
        to(KeyboardHarness.ENGLISH)
        assertTrue(h.texts().toString(), h.texts().any { it.equals("ghi", ignoreCase = true) } && "English" in h.texts() && "q" !in h.texts())
        h.describedKey("Shift")
        to(KeyboardHarness.PINYIN)
        assertTrue(h.texts().toString(), "重输" in h.texts())
    }
}
