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
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.keyboard.AddressKeys
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.GraphicsMode

/**
 * 大千 as the scene draws it (060-render-p2 S4c, U4 B): every key's tap, swipes and
 * long press, the bubble it shows as it is pressed and swiped (its shortcut's name in its place), what
 * reaches the engine and the field, each key wired from its layout's node (action:zhuyin, action:tone-N
 * through ZhuyinKeyboard's table); a long press's symbol or tone sent as its code
 * (C5.8), the address fields' keys and the language key hidden (C5.9).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class ZhuyinSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun noSyllables() {
        EngineState.syllablesState.value = EngineState.SyllableState()
    }

    private fun harness(inputType: Int, board: PinyinKeyboard = PinyinKeyboard.ZhuyinFull): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, board)
        val prefill = { e: EditText ->
            e.setText(TEXT)
            e.setSelection(TEXT.length)
        }
        return KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).start(inputType, prefill = prefill).also { h ->
            h.relayout()
            assertTrue(KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
        }
    }

    // what a finger does on each key (the keys that put another keyboard up left out, as they would take
    // the rest away), in a field of [inputType] with text in it and the cursor at its end (what ⌫, space's
    // swipes and the shortcuts act on); [shown] the ids that must be among the keys, [hidden] those that must not
    private fun gestures(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        shown: Set<String> = emptySet(),
        hidden: Set<String> = emptySet(),
        board: PinyinKeyboard = PinyinKeyboard.ZhuyinFull,
    ): List<String> = harness(inputType, board).use { h ->
        val ids = h.keys().mapNotNull { it.id }.filter { it !in SWITCHING }
        assertTrue(ids.toString(), ids.size >= if (board == PinyinKeyboard.ZhuyinNine) NINE_KEYS_AT_LEAST else MOST_KEYS_AT_LEAST)
        assertTrue("$ids", ids.containsAll(shown) && ids.none { it in hidden })
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
        assertTrue("the picker shown", picker?.isShowing == true && titled == true)
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
        h.press(h.keyNamed("backspace"))
        h.after(HOLD_MS)
        h.release()
        val deletes = h.api.calls.filter { it.startsWith("sendKey") }
        assertTrue("${deletes.size} deletes", deletes.size >= MIN_REPEATS)
        // the deletes go to the host (FakeHostApi types nothing), so what they are is checked, not the field
        out += "backspace held: ${deletes.distinct()}"
        h.api.calls.clear()
        out
    }

    // every symbol and tone, the bottom row; ㄇ ㄈ ㄌ ㄏ ㄒ ㄗ swipe to the full keyboard's shortcuts by place
    @Test
    fun everyKeyGestures() {
        val scene = gestures(shown = setOf("q", "3", "7", "/", "comma", "period", "space", "enter"))
        GestureSnapshot.check("zhuyin-full", scene)
        // what was compared is what the keys do: ㄆ's bubble its own, ㄇ's its shortcut's name as it is swiped (select all)
        assertTrue(scene.toString(), "q pressed: [ㄆ]" in scene && "q swiping: [ㄆ]" in scene)
        assertTrue(scene.toString(), scene.any { it.startsWith("a swiping: [") && "ㄇ" !in it && it != "a swiping: []" })
        assertTrue(scene.toString(), scene.any { it.startsWith("a up: []") && it.endsWith("|0,${TEXT.length + 1}") })
    }

    // 注音九键 (060-render-p2 S4d): every group (its bubble its symbols, its shortcut's name as it is swiped; its
    // long press each symbol), tone (its bubble; ˉ's swipe and long press ˙), ? ! ~ (bubble, preset), the slot
    // (no bubble, its comma, its punctuation), ⌫ and the bottom row
    @Test
    fun nineEveryKeyGestures() {
        val scene = gestures(shown = NINE_SHOWN, board = PinyinKeyboard.ZhuyinNine)
        GestureSnapshot.check("zhuyin-nine", scene)
        // what was compared is what the keys do: a group's bubble, a tone's, ? offering its preset, the slot none
        for (line in listOf("r1c2 pressed: [ㄅㄉㄚ]", "r1c1 pressed: [ˉ]", "r2c1 swiping: [ˊ]", "r2c5 pressed: [?]", "r4c4 pressed: []")) {
            assertTrue(line + "\n" + scene.joinToString("\n"), line in scene)
        }
        assertTrue(scene.toString(), scene.any { it.startsWith("r1c2 long press: [") && "ㄅ" in it && "ㄉ" in it && "ㄚ" in it })
        assertTrue(scene.toString(), scene.any { it.startsWith("r2c5 long press: [") && "¿" in it })
    }

    @Test
    fun nineEveryKeyGesturesCompact() {
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        assertTrue(keyboardCompact())
        GestureSnapshot.check("zhuyin-nine-compact", gestures(shown = NINE_SHOWN, board = PinyinKeyboard.ZhuyinNine))
    }

    // the nine's address fields' keys, its grid as it was
    @Test
    fun nineAddressFieldsKeys() {
        val nine = PinyinKeyboard.ZhuyinNine
        gestures(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, setOf("slash", "dot", "domain", "r4c4"), setOf("at", "comma", "period"), nine)
        gestures(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, setOf("at", "dot", "domain", "r4c4"), setOf("slash", "comma", "period"), nine)
    }

    // the slot (C6.7–C6.9), whichever draws it: the comma as punctuation maps it, 分词 while typing (the engine
    // telling, or a group sent to pinyin before it tells; a tone none, a group sent to another input method
    // none); its tap the separator while typing, the comma else
    @Test
    fun nineSlotAsTheEngineAndTheKeysTell() {
        val shown = run {
            harness(InputType.TYPE_CLASS_TEXT, PinyinKeyboard.ZhuyinNine).use { h ->
                val kb = KeyboardHarness.find<ZhuyinKeyboard>(h.keyboard)!!
                val out = mutableListOf<String>()
                fun slot(what: String) {
                    h.idle()
                    out += "$what: ${h.keyNamed("r4c4").label} " + h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
                    h.api.calls.clear()
                }
                fun tap(id: String) = h.tap(h.keyNamed(id))
                h.api.calls.clear()
                kb.onPunctuationUpdate(emptyMap())
                slot("half-width")
                kb.onPunctuationUpdate(mapOf("," to "，"))
                slot("mapped")
                // the slot's is the face that changes, beside ㄖㄥㄩ: not ~ in the side column
                fun others() = h.keys().filter { it.id != "r4c4" }.map { "${it.id} ${it.label}" }
                val idle = others()
                tap("r4c4")
                slot("tapped")
                EngineState.syllablesState.value = EngineState.SyllableState(im = InputMethodCatalog.PINYIN)
                tap("r3c1")
                slot("a tone")
                // a tone typing nothing of pinyin's: the slot still the comma key (L5)
                tap("r4c4")
                slot("the slot after a tone")
                tap("r4c3")
                slot("a group")
                assertEquals(idle, others())
                tap("r4c4")
                slot("tapped typing")
                EngineState.syllablesState.value = EngineState.SyllableState(im = InputMethodCatalog.PINYIN, composing = true)
                slot("the engine typing")
                EngineState.syllablesState.value = EngineState.SyllableState(im = InputMethodCatalog.PINYIN, composing = false)
                slot("the engine done")
                EngineState.syllablesState.value = EngineState.SyllableState(im = InputMethodCatalog.PINYIN, composing = true)
                kb.onPunctuationUpdate(emptyMap())
                slot("half-width typing")
                EngineState.syllablesState.value = EngineState.SyllableState(im = InputMethodCatalog.T9)
                slot("another input method")
                tap("r1c2")
                slot("a group to it")
                out
            }
        }
        val sep = UNICODE + Keyboard.zhuyinNineTone(0)
        val expected = listOf(
            "half-width: ,", "mapped: ，", "tapped: ，", "a tone: ，", "the slot after a tone: ，", "a group: 分词", "tapped typing: 分词", "the engine typing: 分词",
            "the engine done: ，", "half-width typing: 分词", "another input method: ,", "a group to it: ,",
        )
        assertEquals(expected, shown.map { it.substringBefore(" [") })
        assertTrue(shown[2], "sendKey(sym=44," in shown[2])
        assertTrue(shown[4], "sendKey(sym=44," in shown[4])
        assertTrue(shown[6], "sendKey(sym=$sep," in shown[6])
    }

    // ? ! ~ shown as punctuation maps them, as they are typed (060-render-p2 §5.3, rules C 疑问 5): the views
    // showed them half-width; each types the same either way, the bubble its character as the comma's
    @Test
    fun nineQuestionMarkShownAsItIsTyped() {
        val mapping = mapOf("?" to "？", "!" to "！", "~" to "～", "," to "，", "." to "。")
        val shown = run {
            harness(InputType.TYPE_CLASS_TEXT, PinyinKeyboard.ZhuyinNine).use { h ->
                val kb = KeyboardHarness.find<ZhuyinKeyboard>(h.keyboard)!!
                kb.onPunctuationUpdate(mapping)
                h.idle()
                val typed = listOf("r2c5", "r3c5", "r4c5").map { id ->
                    h.api.calls.clear()
                    h.tap(h.keyNamed(id))
                    "$id ${h.keyNamed(id).label} " + h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
                }
                run {
                    // shown as typed (the user's 「显示和输出一致」): the face, the bubble and what the input method maps
                    // it to the one character; with no mapping (English, half-width punctuation) all half-width again
                    for ((id, c) in QUESTION_MARKS) assertEquals(id, List(3) { mapping.getValue(c) }, faceAndBubble(h, id) + mapping.getValue(c))
                    kb.onPunctuationUpdate(emptyMap())
                    h.idle()
                    for ((id, c) in QUESTION_MARKS) assertEquals(id, listOf(c, c), faceAndBubble(h, id))
                }
                typed
            }
        }
        assertEquals(listOf("？", "！", "～"), shown.map { it.split(' ')[1] })
        // each typed as itself, the input method mapping it
        listOf("?", "!", "~").forEachIndexed { i, c -> assertTrue(shown[i], "key=$c," in shown[i]) }
    }

    // 大千's bottom row's comma and full stop: their bubble as their face, as punctuation maps them (060-render-p2 S4d,
    // M3: its old views bubbled , and . whatever they showed)
    @Test
    fun dachensCommaAndFullStopBubbleAsShown() {
        run {
            harness(InputType.TYPE_CLASS_TEXT).use { h ->
                val kb = KeyboardHarness.find<ZhuyinKeyboard>(h.keyboard)!!
                kb.onPunctuationUpdate(mapOf("," to "，", "." to "。"))
                h.idle()
                assertEquals(listOf("，", "，", "。", "。"), faceAndBubble(h, "comma") + faceAndBubble(h, "period"))
                kb.onPunctuationUpdate(emptyMap())
                h.idle()
                assertEquals(listOf(",", ",", ".", "."), faceAndBubble(h, "comma") + faceAndBubble(h, "period"))
            }
        }
    }

    // a keyboard kept while another was shown, shown again: its punctuation as it changed meanwhile (KeyboardWindow.attachLayout,
    // 060-render-p2 S4d L1: it showed , where it typed ，), whichever draws it
    @Test
    fun aKeyboardShownAgainShowsThePunctuationChangedMeanwhile() {
        run {
            harness(InputType.TYPE_CLASS_TEXT, PinyinKeyboard.ZhuyinNine).use { h ->
                assertEquals(",", h.keyNamed("comma").label)
                h.tap(h.key("123"))
                h.idle()
                val active = listOf(StatusEntry(1, "punctuation", "fcitx-punc-active", ""))
                h.emit(HostEvent.StatusArea(active, KeyboardHarness.PINYIN, mapOf("," to "，")))
                h.idle()
                h.tap(h.key("ABC"))
                h.idle()
                assertEquals(listOf("，", "，"), listOf(h.keyNamed("comma").label, h.keyNamed("r4c4").label))
            }
        }
    }

    // [id]'s face and the bubble it shows pressed
    private fun faceAndBubble(h: KeyboardHarness, id: String): List<String> {
        h.after(BUBBLE_GONE_MS)
        val k = h.keyNamed(id)
        h.press(k)
        val bubble = h.popupChoices().map { it.text }.single()
        h.release()
        return listOf(k.label, bubble)
    }

    // a group's long press picks its symbol, ˉ's ˙, each sent as its code (C6.3, C6.5), whichever draws
    @Test
    fun nineLongPressesSymbolOrToneIsSentAsItsCode() {
        val sent = run {
            harness(InputType.TYPE_CLASS_TEXT, PinyinKeyboard.ZhuyinNine).use { h ->
                listOf("r1c2" to "ㄚ", "r3c3" to "ㄡ", "r1c1" to "˙").map { (id, pick) ->
                    h.api.calls.clear()
                    h.longPress(h.keyNamed(id), pick)
                    "$id $pick " + h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
                }
            }
        }
        val codes = listOf(Keyboard.zhuyinNineSymbol('ㄚ'), Keyboard.zhuyinNineSymbol('ㄡ'), Keyboard.zhuyinNineTone(5))
        sent.zip(codes).forEach { (s, c) -> assertTrue(s, "sym=${UNICODE + c}," in s) }
    }

    // 注音九键 as shown (C6.1–C6.4, C6.11): the tones down the left, three groups a row, ⌫ ? ! ~ down the right, the
    // slot after ㄖㄥㄩ; each key named by its place, a group's shortcut zy9/1…zy9/11; the classes' sizes to each
    // other (groups 18, tones 20, ? ! ~ 23) at each height
    @Test
    fun nineOnTheKeysAsShown() {
        val keyboard = AppPrefs.getInstance().keyboard
        for (level in listOf(KeyboardHeightLevels.COMPACT, KeyboardHeightLevels.ALL.size - 1)) {
            val heights = KeyboardHeightLevels.ALL[level]
            prefs.set(keyboard.keyboardHeightPercent, heights.portrait)
            prefs.set(keyboard.keyboardHeightPercentLandscape, heights.landscape)
            val shown = run {
                harness(InputType.TYPE_CLASS_TEXT, PinyinKeyboard.ZhuyinNine).use { h ->
                    val grid = h.keys().filter { it.id == "backspace" || it.id?.matches(Regex("r[1-4]c[1-5]")) == true }
                    assertEquals(20, grid.size)
                    val rows = grid.groupBy { it.bounds.top }.toSortedMap().values.map { r -> r.sortedBy { it.bounds.left }.joinToString(" ") { "${it.id}=${it.label}" } }
                    ZhuyinKeys.GROUP_SYMBOLS.forEachIndexed { g, symbols ->
                        assertEquals(ZhuyinShortcutNames.nineKey("${g + 1}"), (h.key(symbols) as SceneShownKey).node.shortcut)
                    }
                    val slot = h.keyNamed("r4c4")
                    rows + listOf("ㄅㄉㄚ", "ˇ", "?").map { "$it ${h.key(it).textSize(it)}" } + "slot ${slot.textSize(slot.label)}"
                }
            }
            // the classes' sizes to each other as their dp (groups 18, tones 20, ? ! ~ 23), each class at most its
            // cap smaller (060-render-p2 S4e): the same scale on all three, whatever the height
            val size = shown.drop(4).associate { it.substringBeforeLast(' ') to it.substringAfterLast(' ').toFloat() }
            val group = size.getValue("ㄅㄉㄚ")
            val shrink = (1 - KeyMarkYield.MAX_CLASS_SHRINK).toFloat()
            for ((text, dp) in listOf("ˇ" to 20f, "?" to 23f)) {
                val nominal = group / 18f * dp
                assertTrue("level $level: $text in $shown", size.getValue(text) in nominal * shrink - SIZE_SLACK..nominal / shrink + SIZE_SLACK)
            }
            assertEquals(
                listOf("r1c1=ˉ r1c2=ㄅㄉㄚ r1c3=ㄍㄐㄞ r1c4=ㄓㄗㄢㄦ backspace=", "r2c1=ˊ r2c2=ㄆㄊㄛ r2c3=ㄎㄑㄟ r2c4=ㄔㄘㄣㄧ r2c5=?"),
                shown.take(2),
            )
            assertEquals("r3c1=ˇ r3c2=ㄇㄋㄜ r3c3=ㄏㄒㄠㄡ r3c4=ㄕㄙㄤㄨ r3c5=!", shown[2])
            assertEquals("r4c1=ˋ r4c2=ㄈㄌㄝ r4c3=ㄖㄥㄩ r4c4=, r4c5=~", shown[3])
        }
    }

    // a 360 dp phone compact (20%, 34% on its side: CompactKeyboardScreenshotTest's zhuyin_full_small_port): ⌫'s face 16 dp
    // tall, its two shortcut icons do not both fit: the down one left out, the other kept (the engine's rule,
    // ContentTest; here as the app draws it)
    @Test
    @Config(qualifiers = "w360dp-h740dp-port-440dpi")
    fun aSmallPhonesCompactBackspaceLeavesOutItsDownIcon() {
        // past the shortcuts' cap (060-render-p2 S4e: never smaller on its own, never another corner): the down
        // one, the less swiped, left out; the swipe still works
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        assertEquals(2, harness(InputType.TYPE_CLASS_TEXT).use { h -> h.keyNamed("backspace").drawnIcons })
    }

    // the same compact (the height setting's lowest, 20%, 34% on its side): the text 0.9, the marks smaller
    @Test
    fun everyKeyGesturesCompact() {
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        assertTrue(keyboardCompact())
        GestureSnapshot.check("zhuyin-full-compact", gestures(shown = setOf("q", "3", "7", "/", "comma", "period", "space", "enter")))
    }

    // 大千 as shown, whichever draws it: each symbol and tone on its QWERTY place (its theme name, zhuyin/q),
    // row by row as Taiwan's keyboards print them (C5.1), its shortcut by that place (zyfull/q, C5.7), its tap the code
    @Test
    fun dachenOnTheKeysAsShown() {
        harness(InputType.TYPE_CLASS_TEXT).use { h ->
            val keys = h.keys().filter { k -> k.label.singleOrNull()?.let { it in ZhuyinKeys.SYMBOLS || it in ZhuyinKeys.MARKS } == true }
            assertEquals(41, keys.size)
            keys.forEach { assertEquals(ZhuyinKeys.DACHEN_QWERTY.getValue(it.id!!.single()).toString(), it.label) }
            val rows = keys.groupBy { it.bounds.top }.toSortedMap().values.map { r -> r.sortedBy { it.bounds.left }.joinToString("") { it.id!! } }
            assertEquals(listOf("1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./"), rows)
            keys.forEach { assertEquals(ZhuyinShortcutNames.fullKey(it.id!!.single()), (it as SceneShownKey).node.shortcut) }
            h.api.calls.clear()
            for (face in listOf("ㄅ", "ˊ", "˙", "ㄥ")) h.tap(h.key(face))
            val sent = h.api.calls.mapNotNull { Regex("sendKey\\(sym=(\\d+),.*up=false").find(it)?.groupValues?.get(1)?.toInt() }
            val codes = listOf(Keyboard.zhuyinKey('ㄅ'), Keyboard.zhuyinTone(2), Keyboard.zhuyinTone(5), Keyboard.zhuyinKey('ㄥ'))
            assertEquals(codes.map { UNICODE + it }, sent)
        }
    }

    // an address field's keys: a URL's / . and domains, an address's @ . and domains; neither a , or 。 of its own
    @Test
    fun theAddressFieldsKeys() {
        gestures(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, setOf("slash", "dot", "domain"), setOf("at", "comma", "period"))
        gestures(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, setOf("at", "dot", "domain"), setOf("slash", "comma", "period"))
    }

    // the language key as the setting says, shown again as it changes
    @Test
    fun theLanguageKeyHiddenAndShownAgain() {
        harness(InputType.TYPE_CLASS_TEXT).use { h ->
            val lang = AppPrefs.getInstance().keyboard.showLangSwitchKey
            prefs.set(lang, false)
            h.relayout()
            assertTrue(h.keys().none { it.id == "lang" })
            lang.setValue(true)
            h.relayout()
            assertTrue(h.keys().any { it.id == "lang" })
        }
    }

    // the bottom row follows what the engine tells with no layout between (each change invalidates the scene):
    // the comma and full stop as punctuation maps them, Return's face, the space's input method, an address's
    // keys
    @Test
    fun theBottomRowFollowsPunctuationReturnAndTheInputMethod() {
        // Return's icon as the scene draws it, step by step (the views' drawable is no name to compare)
        val returnIcons = mutableListOf<List<String>>()
        val shown = run {
            harness(InputType.TYPE_CLASS_TEXT).use { h ->
                val kb = KeyboardHarness.find<ZhuyinKeyboard>(h.keyboard)!!
                val out = mutableListOf<String>()
                fun look(what: String) {
                    h.idle()
                    out += what + ": " + listOf("comma", "period", "enter", "space").joinToString(" | ") { id ->
                        // the comma's and full stop's description: none on their views, TalkBack reading the text,
                        // the text itself on the scene (S3's nodes), read the same
                        h.keyNamed(id).run { "$label ${if (id in setOf("enter", "space")) description.orEmpty() else ""} icons=$drawnIcons" }
                    }
                    returnIcons += (h.keyNamed("enter") as SceneShownKey).node.ops(false).filterIsInstance<Op.Icon>().map { it.name }
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
        // each a change seen
        assertTrue(shown.toString(), shown.dropLast(1).zipWithNext().all { (a, b) -> a.substringAfter(": ") != b.substringAfter(": ") })
        assertTrue(shown.last(), "slash /" in shown.last())
        assertTrue(returnIcons.toString(), returnIcons[0] != returnIcons[2] && returnIcons[2] == returnIcons[3])
    }

    // a long press's symbol or tone typed as the keyboard's code, whichever draws (C5.8): the comma's own,
    // the user's 按键字符 giving it ˊ and ㄅ; the comma's own ， as it is
    @Test
    fun aLongPressesSymbolOrToneIsSentAsItsCode() {
        prefs.set(AppPrefs.getInstance().internal.popupOverrides, ", ˊ ㄅ ，")
        val sent = run {
            harness(InputType.TYPE_CLASS_TEXT).use { h ->
                listOf("ˊ", "ㄅ", "，").map { pick ->
                    h.api.calls.clear()
                    h.longPress(h.keyNamed("comma"), pick)
                    pick + " " + h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") }
                }
            }
        }
        val tone = UNICODE + Keyboard.zhuyinTone(2)
        assertTrue(sent.toString(), "sym=$tone," in sent[0])
        val b = UNICODE + Keyboard.zhuyinKey('ㄅ')
        assertTrue(sent.toString(), "sym=$b," in sent[1])
        // the comma's own ， picked: typed, not taken for a tone or a symbol's code
        assertTrue(sent.toString(), sent[2] != "， []" && "sym=$tone," !in sent[2] && "sym=$b," !in sent[2])
    }

    // the symbols one size at each height (B6.5, C5.4)
    @Test
    fun theSymbolsOneSizeAtEachHeight() {
        val keyboard = AppPrefs.getInstance().keyboard
        for (level in listOf(KeyboardHeightLevels.COMPACT, KeyboardHeightLevels.ALL.size - 1)) {
            val heights = KeyboardHeightLevels.ALL[level]
            prefs.set(keyboard.keyboardHeightPercent, heights.portrait)
            prefs.set(keyboard.keyboardHeightPercentLandscape, heights.landscape)
            val sizes = run {
                harness(InputType.TYPE_CLASS_TEXT).use { h -> listOf("ㄅ", "ˇ", "ㄥ").map { h.key(it).textSize(it)!! } }
            }
            // the scene's symbols one size (their size itself the keyboard-scene snapshots' to pin)
            assertEquals("level $level: $sizes", 1, sizes.toSet().size)
        }
    }

    private companion object {
        val SWITCHING = setOf("symbols", "numbers", "lang")

        // 41 symbols and tones, ⌫ and the bottom row: a key missing on one side is caught by the ids compared
        const val MOST_KEYS_AT_LEAST = 45

        val QUESTION_MARKS = listOf("r2c5" to "?", "r3c5" to "!", "r4c5" to "~")

        // 注音九键's keys that must be among those compared: a tone, a group, the slot, ?, ⌫, the bottom row's
        val NINE_SHOWN = setOf("r1c1", "r1c2", "r4c4", "r2c5", "backspace", "comma", "space", "enter")

        // 注音九键: its 4×5 grid but r1c5 (⌫ by its role) and the bottom row's comma, space, full stop and Return
        const val NINE_KEYS_AT_LEAST = 23

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
