/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.View
import android.widget.EditText
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AddressKeys
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.math.abs
import org.robolectric.annotation.GraphicsMode

/**
 * 大千 drawn by the scene does what its views did (060-render-p2 S4c, U4 B): every key's tap, swipes and
 * long press, the bubble it shows as it is pressed and swiped (its shortcut's name in its place), what
 * reaches the engine and the field, each key wired from its layout's node (action:zhuyin, action:tone-N
 * through ZhuyinKeyboard's table) as from its old KeyDef; a long press's symbol or tone sent as its code
 * (C5.8), the address fields' keys and the language key hidden (C5.9).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class ZhuyinSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun harness(canvas: Boolean, inputType: Int): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, canvas)
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard.ZhuyinFull)
        val prefill = { e: EditText ->
            e.setText(TEXT)
            e.setSelection(TEXT.length)
        }
        return KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).start(inputType, prefill = prefill).also { h ->
            h.relayout()
            assertEquals(canvas, KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
        }
    }

    // what a finger does on each key (the keys that put another keyboard up left out, as they would take
    // the rest away), in a field of [inputType] with text in it and the cursor at its end (what ⌫, space's
    // swipes and the shortcuts act on); [shown] the ids that must be among the keys, [hidden] those that must not
    private fun gestures(
        canvas: Boolean,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        shown: Set<String> = emptySet(),
        hidden: Set<String> = emptySet(),
    ): List<String> = harness(canvas, inputType).use { h ->
        val ids = h.keys().mapNotNull { it.id }.filter { it !in SWITCHING }
        assertTrue(ids.toString(), ids.size >= MOST_KEYS_AT_LEAST)
        assertTrue("canvas $canvas: $ids", ids.containsAll(shown) && ids.none { it in hidden })
        val out = mutableListOf<String>()
        fun sent(what: String) {
            out += "$what: " + h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") } +
                " field ${h.field.text}|${h.field.selectionStart},${h.field.selectionEnd}"
            h.api.calls.clear()
        }
        // the language key held (left out of the keys below: its tap puts another keyboard up): the input method picker
        h.after(BUBBLE_GONE_MS)
        out += "lang long press: " + h.longPress(h.keyNamed("lang"))
        h.idle()
        val picker = ShadowDialog.getLatestDialog()
        // the picker, by its title: held as long as a tap, the key would switch input methods (one enabled: a prompt to add more)
        val title = h.keyboard.context.getString(org.fcitx.fcitx5.android.R.string.choose_input_method)
        val titled = picker?.window?.decorView?.let { d -> KeyboardHarness.all(d).filterIsInstance<android.widget.TextView>().any { it.text.toString() == title } }
        assertTrue("canvas $canvas: the picker shown", picker?.isShowing == true && titled == true)
        sent("lang long press")
        picker?.dismiss()
        h.idle()
        // what the bubble over the key shows
        fun bubble() = h.popupChoices().map { it.text }
        for (id in ids) {
            val k = { h.keyNamed(id) }
            // the last key's bubble gone
            h.after(BUBBLE_GONE_MS)
            // what TalkBack reads: its description, or where it has none (the comma's and full stop's views) its text
            out += "$id said: " + (k().description?.takeIf { it.isNotEmpty() } ?: k().label)
            h.press(k())
            out += "$id pressed: " + bubble()
            h.release()
            sent("$id tap")
            // held past the swipe's threshold: the bubble shows what lifting there does (a shortcut's name)
            val b = k().bounds
            h.drag(k(), b.exactCenterX(), b.exactCenterY() - b.height() * SWIPED, lift = false)
            out += "$id swiping: " + bubble()
            h.move(b.exactCenterX(), b.exactCenterY() - b.height() * SWIPED, steps = 1, lift = true)
            sent("$id up")
            h.swipe(k(), 0f, k().bounds.height().toFloat())
            sent("$id down")
            // sideways: space's the cursor moved, ⌫'s the text before it selected to delete, a symbol's
            // or a tone's nothing but itself typed (its swipes are up and down)
            h.swipe(k(), -k().bounds.width() * 2f, 0f)
            sent("$id left")
            h.swipe(k(), k().bounds.width() * 2f, 0f)
            sent("$id right")
            // a little sideways, still on the key: a symbol typed as tapped, space's cursor a step
            h.swipe(k(), k().bounds.width() * NUDGED, 0f)
            sent("$id nudged")
            if (id != "backspace") {
                out += "$id long press: " + h.longPress(k())
                sent("$id long press")
            }
        }
        // ⌫ held: deletes over and over, as many either way give or take one (how many depends on where in a
        // frame the hold began, which the steps above move: at SDK 29 the scene's came one short, 24 to 25)
        val held = h.field.text.length
        h.press(h.keyNamed("backspace"))
        h.after(HOLD_MS)
        h.release()
        val deletes = h.api.calls.filter { it.startsWith("sendKey") }
        assertTrue("canvas $canvas: ${deletes.size} deletes", deletes.size >= MIN_REPEATS)
        out += "backspace held: ${deletes.distinct()}, the field ${h.field.text} shorter by them: ${held - h.field.text.length == deletes.size}"
        heldDeletes[canvas] = deletes.size
        h.api.calls.clear()
        out
    }

    // how many times ⌫ held deleted, by whether the scene drew the keys
    private val heldDeletes = mutableMapOf<Boolean, Int>()

    private fun assertSame(inputType: Int = InputType.TYPE_CLASS_TEXT, shown: Set<String> = emptySet(), hidden: Set<String> = emptySet()): List<String> {
        val views = gestures(false, inputType, shown, hidden)
        val scene = gestures(true, inputType, shown, hidden)
        // the field after ⌫ held as long as it deleted: compared by what it deleted
        fun same(out: List<String>) = out.map { it.replace(Regex("the field .* shorter by them"), "the field shorter by them") }
        assertEquals(same(views).joinToString("\n"), same(scene).joinToString("\n"))
        assertTrue("⌫ held: $heldDeletes", abs(heldDeletes.getValue(false) - heldDeletes.getValue(true)) <= 1)
        return scene
    }

    // every symbol and tone, the bottom row; ㄇ ㄈ ㄌ ㄏ ㄒ ㄗ swipe to the full keyboard's shortcuts by place
    @Test
    fun everyKeyAsItsView() {
        val scene = assertSame(shown = setOf("q", "3", "7", "/", "comma", "period", "space", "enter"))
        // what was compared is what the keys do: ㄆ's bubble its own, ㄇ's its shortcut's name as it is swiped (select all)
        assertTrue(scene.toString(), "q pressed: [ㄆ]" in scene && "q swiping: [ㄆ]" in scene)
        assertTrue(scene.toString(), scene.any { it.startsWith("a swiping: [") && "ㄇ" !in it && it != "a swiping: []" })
        assertTrue(scene.toString(), scene.any { it.startsWith("a up: []") && it.endsWith("|0,${TEXT.length + 1}") })
    }

    // a 360 dp phone compact (20%, 34% on its side: CompactKeyboardScreenshotTest's zhuyin_full_small_port): ⌫'s face 16 dp
    // tall, its two shortcut icons kept one over the other at their least, their insets eaten half a px, none
    // left out (the engine's rule, ContentTest; here as the app draws it)
    @Test
    @Config(qualifiers = "w360dp-h740dp-port-440dpi")
    fun aSmallPhonesCompactBackspaceKeepsBothItsIcons() {
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        assertEquals(3, harness(canvas = true, InputType.TYPE_CLASS_TEXT).use { h -> h.keyNamed("backspace").drawnIcons })
    }

    // the same compact (the height setting's lowest, 20%, 34% on its side): the text 0.9, the marks smaller
    @Test
    fun everyKeyAsItsViewCompact() {
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        assertTrue(keyboardCompact())
        assertSame(shown = setOf("q", "3", "7", "/", "comma", "period", "space", "enter"))
    }

    // 大千 as shown, whichever draws it: each symbol and tone on its QWERTY place (its theme name, zhuyin/q),
    // row by row as Taiwan's keyboards print them (C5.1), its shortcut by that place (zyfull/q, C5.7), its tap the code
    @Test
    fun dachenOnTheKeysAsShown() {
        for (canvas in listOf(false, true)) harness(canvas, InputType.TYPE_CLASS_TEXT).use { h ->
            val keys = h.keys().filter { k -> k.label.singleOrNull()?.let { it in ZhuyinKeys.SYMBOLS || it in ZhuyinKeys.MARKS } == true }
            assertEquals("canvas $canvas", 41, keys.size)
            keys.forEach { assertEquals("canvas $canvas", ZhuyinKeys.DACHEN_QWERTY.getValue(it.id!!.single()).toString(), it.label) }
            val rows = keys.groupBy { it.bounds.top }.toSortedMap().values.map { r -> r.sortedBy { it.bounds.left }.joinToString("") { it.id!! } }
            assertEquals("canvas $canvas", listOf("1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./"), rows)
            if (canvas) keys.forEach { assertEquals(ZhuyinShortcutNames.fullKey(it.id!!.single()), (it as SceneShownKey).node.shortcut) }
            h.api.calls.clear()
            for (face in listOf("ㄅ", "ˊ", "˙", "ㄥ")) h.tap(h.key(face))
            val sent = h.api.calls.mapNotNull { Regex("sendKey\\(sym=(\\d+),.*up=false").find(it)?.groupValues?.get(1)?.toInt() }
            val codes = listOf(Keyboard.zhuyinKey('ㄅ'), Keyboard.zhuyinTone(2), Keyboard.zhuyinTone(5), Keyboard.zhuyinKey('ㄥ'))
            assertEquals("canvas $canvas", codes.map { UNICODE + it }, sent)
        }
    }

    // an address field's keys: a URL's / . and domains, an address's @ . and domains; neither a , or 。 of its own
    @Test
    fun theAddressFieldsKeys() {
        assertSame(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, setOf("slash", "dot", "domain"), setOf("at", "comma", "period"))
        assertSame(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, setOf("at", "dot", "domain"), setOf("slash", "comma", "period"))
    }

    // the language key as the setting says, shown again as it changes
    @Test
    fun theLanguageKeyHiddenAndShownAgain() {
        for (canvas in listOf(false, true)) harness(canvas, InputType.TYPE_CLASS_TEXT).use { h ->
            val lang = AppPrefs.getInstance().keyboard.showLangSwitchKey
            prefs.set(lang, false)
            h.relayout()
            assertTrue("canvas $canvas", h.keys().none { it.id == "lang" })
            lang.setValue(true)
            h.relayout()
            assertTrue("canvas $canvas", h.keys().any { it.id == "lang" })
        }
    }

    // the bottom row follows what the engine tells with no layout between (each change invalidates the scene):
    // the comma and full stop as punctuation maps them, Return's face, the space's input method, an address's
    // keys; as the views do
    @Test
    fun theBottomRowFollowsPunctuationReturnAndTheInputMethod() {
        // Return's icon as the scene draws it, step by step (the views' drawable is no name to compare)
        val returnIcons = mutableListOf<List<String>>()
        val shown = listOf(false, true).map { canvas ->
            harness(canvas, InputType.TYPE_CLASS_TEXT).use { h ->
                val kb = KeyboardHarness.find<ZhuyinKeyboard>(h.keyboard)!!
                val out = mutableListOf<String>()
                fun look(what: String) {
                    h.idle()
                    out += what + ": " + listOf("comma", "period", "enter", "space").joinToString(" | ") { id ->
                        // the comma's and full stop's description: none on their views, TalkBack reading the text,
                        // the text itself on the scene (S3's nodes), read the same
                        h.keyNamed(id).run { "$label ${if (id in setOf("enter", "space")) description.orEmpty() else ""} icons=$drawnIcons" }
                    }
                    if (canvas) returnIcons += (h.keyNamed("enter") as SceneShownKey).node.ops(false).filterIsInstance<Op.Icon>().map { it.name }
                }
                look("first")
                kb.onPunctuationUpdate(mapOf("," to "，", "." to "。"))
                look("punctuation")
                kb.onReturnDrawableUpdate(ReturnKeyAppearance.Composing)
                look("return")
                kb.onInputMethodUpdate(InputMethodEntry("other", "别的", "", "", "", "zh_TW", "androidengine", false))
                look("input method")
                kb.address = AddressKeys.URL
                h.idle()
                out += "address: " + h.keys().mapNotNull { k -> k.id?.let { "$it ${k.label}" } }.filter { it.substringBefore(' ') !in SYMBOL_IDS }
                out
            }
        }
        assertEquals(shown[0].joinToString("\n"), shown[1].joinToString("\n"))
        // each a change seen
        assertTrue(shown[1].toString(), shown[1].dropLast(1).zipWithNext().all { (a, b) -> a.substringAfter(": ") != b.substringAfter(": ") })
        assertTrue(shown[1].last(), "slash /" in shown[1].last())
        assertTrue(returnIcons.toString(), returnIcons[1] != returnIcons[2] && returnIcons[2] == returnIcons[3])
    }

    // a long press's symbol or tone typed as the keyboard's code, whichever draws (C5.8): the comma's own,
    // the user's 按键字符 giving it ˊ and ㄅ; the comma's own ， as it is
    @Test
    fun aLongPressesSymbolOrToneIsSentAsItsCode() {
        prefs.set(AppPrefs.getInstance().internal.popupOverrides, ", ˊ ㄅ ，")
        val sent = listOf(false, true).map { canvas ->
            harness(canvas, InputType.TYPE_CLASS_TEXT).use { h ->
                listOf("ˊ", "ㄅ", "，").map { pick ->
                    h.api.calls.clear()
                    h.longPress(h.keyNamed("comma"), pick)
                    pick + " " + h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") }
                }
            }
        }
        assertEquals(sent[0], sent[1])
        val tone = UNICODE + Keyboard.zhuyinTone(2)
        assertTrue(sent[1].toString(), "sym=$tone," in sent[1][0])
        val b = UNICODE + Keyboard.zhuyinKey('ㄅ')
        assertTrue(sent[1].toString(), "sym=$b," in sent[1][1])
    }

    // the symbols' size as the views' (20 × KeyTextScale.dachen: 0.9 again at 紧凑, B6.5, C5.4), each height
    @Test
    fun theSymbolsAsLargeAsTheViewsAtEachHeight() {
        val keyboard = AppPrefs.getInstance().keyboard
        for (level in listOf(KeyboardHeightLevels.COMPACT, KeyboardHeightLevels.ALL.size - 1)) {
            val heights = KeyboardHeightLevels.ALL[level]
            prefs.set(keyboard.keyboardHeightPercent, heights.portrait)
            prefs.set(keyboard.keyboardHeightPercentLandscape, heights.landscape)
            val sizes = listOf(false, true).map { canvas ->
                harness(canvas, InputType.TYPE_CLASS_TEXT).use { h -> listOf("ㄅ", "ˇ", "ㄥ").map { h.key(it).textSize(it)!! } }
            }
            sizes[0].zip(sizes[1]).forEach { (old, new) -> assertEquals("level $level: $sizes", old, new, SIZE_SLACK) }
        }
    }

    private companion object {
        val SWITCHING = setOf("symbols", "numbers", "lang")

        // 41 symbols and tones, ⌫ and the bottom row: a key missing on one side is caught by the ids compared
        const val MOST_KEYS_AT_LEAST = 45

        // a sideways move short of the key's side, of its width
        const val NUDGED = 0.3f

        const val TEXT = "hello world, again"

        // a swipe up this many of the key's heights: past the threshold, the shortcut's
        const val SWIPED = 1.3f

        // ⌫ held this long repeats: some deletes at least, the same count each side
        const val HOLD_MS = 1500L
        const val MIN_REPEATS = 3

        // 大千's symbol and tone keys, by their places
        val SYMBOL_IDS = ZhuyinKeys.DACHEN_QWERTY.keys.map(Char::toString).toSet()

        // past a bubble's going (PopupComponent's dismiss delay and fade)
        const val BUBBLE_GONE_MS = 1000L

        // a text's size in px: the views' rounded to whole pixels by TextView, the scene's exact
        const val SIZE_SLACK = 1f
    }
}
