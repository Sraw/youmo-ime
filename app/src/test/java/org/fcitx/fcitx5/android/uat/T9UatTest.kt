/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.host.Engines
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

/** UAT cases of the nine keys (uat/03), on the whole keyboard over a fake engine. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class T9UatTest {

    private var h: KeyboardHarness? = null

    private fun harness(im: org.fcitx.fcitx5.android.core.InputMethodEntry = KeyboardHarness.T9) =
        KeyboardHarness(im = im).also {
            h = it
            it.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.T9, KeyboardHarness.ENGLISH)
            it.api.enabledIme = it.api.availableIme
        }

    @After
    fun close() {
        h?.close()
        EngineBridge.syllablesState.value = EngineBridge.SyllableState()
    }

    /**
     * What the engine tells the nine keys of what is typed (EngineBridge.syllables, set from its snapshot
     * on a device): composing, and the syllables the column offers. A global, put back in [close].
     */
    private fun typing(composing: Boolean, vararg syllables: String) {
        EngineBridge.syllablesState.value = EngineBridge.SyllableState(Engines.T9, composing, syllables.toList(), syllables.size)
        this.h?.idle()
    }

    private val KeyboardHarness.keys get() = api.calls.filter { it.startsWith("sendKey") || it.startsWith("select") }

    /** The long press's popup: the texts of the popup's cells, as they are laid out (rows top to bottom). */
    private fun KeyboardHarness.popup(): List<String> = texts(inputView).let { all ->
        // the popup is drawn over the keyboard: the texts after the keyboard's own
        all.drop(texts(keyboard).size)
    }

    /** The popup's cell showing [label], the last such text (the popup is above the keys in the tree). */
    private fun KeyboardHarness.cell(label: String): View =
        KeyboardHarness.all(inputView).filter { shown(it) && it is TextView && it.text.toString() == label }.last()

    /**
     * The offset in dp from the popup's cell the finger starts on (the key's digit) to its cell
     * [label]: the popup moves its focus by how far the finger goes, not by where it is.
     */
    private fun KeyboardHarness.offsetTo(key: View, label: String): Pair<Float, Float> {
        // a cell's frame: its text is drawn centred in it, not laid out to its size
        fun centre(text: String): Pair<Float, Float> {
            val c = cell(text).parent as View
            val at = IntArray(2).also(c::getLocationInWindow)
            return at[0] + c.width / 2f to at[1] + c.height / 2f
        }
        val from = centre((key as org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView).altText.text.toString())
        val to = centre(label)
        // the focus moves a key for less than a key's width of finger (PopupFocus.STEP)
        val step = org.fcitx.fcitx5.android.input.popup.PopupFocus.STEP / service.resources.displayMetrics.density
        return (to.first - from.first) * step to (to.second - from.second) * step
    }

    /** A long press on [key], held, the finger then moved onto the popup's [label] (null: not moved) and let go. */
    private fun KeyboardHarness.longPressTo(key: View, label: String?) {
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
    private fun KeyboardHarness.popupOf(key: View): List<String> {
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
    fun aDigitKeySwipedDownTypesItsDigit() {
        val h = harness().start()
        val eight = h.nineKey("8")
        h.touch(eight, h.at(eight), h.at(eight, dy = 30f, ms = 50))
        // the digit itself, as the number pad's 8 (the engine types it when nothing is composed)
        assertEquals(listOf("8"), h.sent())
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
        AppPrefs.getInstance().keyboard.glideTyping.setValue(true)
        try {
            val h = harness().start()
            val five = h.nineKey("5")
            // half a key down: not the most of one, which would be a glide
            val half = five.height / 2f / h.service.resources.displayMetrics.density
            h.touch(five, h.at(five), h.at(five, dy = half / 2, ms = 30), h.at(five, dy = half, ms = 30))
            assertEquals(listOf("5"), h.sent())
            assertFalse(h.api.calls.toString(), "offerGlide" in h.api.calls)
        } finally {
            AppPrefs.getInstance().keyboard.glideTyping.setValue(false)
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
        assertEquals("2", h.field.text.toString())
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
        h.capture("T9UatTest/popup_2")
        typing(true, "ni")
        h.api.calls.clear()
        h.longPressTo(h.nineKey("2"), "!")
        // the mark to the engine, as a key: its punctuation makes it ！ after the candidate (fcitx's, on the device)
        assertEquals(listOf("!"), h.sent())
        assertEquals("", h.field.text.toString())
    }

    @Test
    @Uat("T9-34")
    fun anEmailFieldsColumn() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val email = listOf("@", ".", ".com", "-", "_", "+", ".cn", ".net", ".org")
        fun column() = h.texts().takeLast(email.size)
        assertEquals(email, column())
        // the bottom row as ever: space with the whole name, 中/英, the globe
        assertTrue(h.texts().toString(), "Pinyin (9 keys)" in h.texts() && "中/英" in h.texts())
        h.described(h.service.getString(org.fcitx.fcitx5.android.R.string.a11y_key_switch_language))
        h.tap(h.key("中/英"))
        h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.ENGLISH))
        assertEquals(email, column())
        h.tap(h.textView("@"))
        h.tap(h.textView(".com"))
        assertEquals("@.com", h.field.text.toString())
    }

    @Test
    @Uat("T9-37")
    fun nineKeysEnglishStaysOverARotation() {
        val h = harness().start()
        h.tap(h.key("中/英"))
        h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.ENGLISH))
        h.described("Shift")
        for (q in listOf("+land", "+port")) {
            org.robolectric.RuntimeEnvironment.setQualifiers(q)
            h.service.onConfigurationChanged(h.service.resources.configuration)
            h.idle()
            h.relayout()
            val keys = h.texts()
            assertTrue("$q $keys", "ghi" in keys && "English" in keys && "q" !in keys)
            h.described("Shift")
        }
    }

    @Test
    @Uat("T9-38")
    fun anAddressFieldsColumn() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val url = listOf("/", ".", ".com", ":", "-", "_", "?", "=", "&", "#", ".cn", ".net", ".org")
        assertEquals(url, h.texts().takeLast(url.size))
        assertTrue(h.texts().toString(), "Pinyin (9 keys)" in h.texts())
        h.tap(h.textView("/"))
        h.tap(h.textView(".com"))
        assertEquals("/.com", h.field.text.toString())
        // typing pinyin: its syllables
        typing(true, "mi", "ni")
        assertEquals(listOf("mi", "ni"), h.texts().takeLast(2))
        typing(false)
        assertEquals(url, h.texts().takeLast(url.size))
    }

    @Test
    @Uat("T9-45")
    fun aPasswordFieldIsTheFullKeyboard() {
        val h = harness()
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // androidengine switches a password to English (PR-03)
        h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.ENGLISH))
        val keys = h.texts()
        assertTrue(keys.toString(), listOf("q", "w", "e", "r", "t", "y").all { it in keys } && "中/英" !in keys && "English" in keys)
        h.tap(h.key("q"))
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
        h.start()
        h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.T9))
        assertTrue(h.texts().toString(), "重输" in h.texts())
    }

    @Test
    @Uat("T9-48")
    fun theLongPressPopupIsSmall() {
        val h = harness().start()
        val five = h.nineKey("5")
        val f = h.finger(five, h.at(five))
        h.advance(700)
        val cell = (h.cell("j").parent as View).width
        // far narrower than the nine keys' key
        assertTrue("cell $cell key ${five.width}", cell * 2 < five.width)
        h.capture("T9UatTest/popup_5")
        f.up(h.at(five))
        assertEquals("5", h.field.text.toString())
        // a finger moved: about half a finger right, then far left
        fun slide(key: View, dx: Float, dy: Float = 0f, from: KeyboardHarness.Touch = h.at(key)) {
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
        slide(h.nineKey("9"), 25f)
        assertEquals("5jJw", h.field.text.toString())
        // the full keyboard's cells are as small
        h.close()
        val q = harness(KeyboardHarness.PINYIN).start()
        val key = q.key("q")
        val g = q.finger(key, q.at(key))
        q.advance(700)
        val full = (q.cell("Q").parent as View).width
        g.up(q.at(key))
        assertEquals(full, cell)
    }
}
