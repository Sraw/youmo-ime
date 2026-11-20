/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.ChinesePunctuation
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.input.keyboard.ZhuyinKeyboard
import org.fcitx.fcitx5.android.input.keys.First
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.input.popup.PopupFocus
import org.fcitx.fcitx5.android.input.sent
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One key the same on every keyboard: ，'s long press, its edit and its first; the nine keys' 1 and 5; 注音九键's ˉ. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeysEverywhereUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var made: KeyboardHarness? = null

    @After
    fun close() {
        made?.close()
    }

    private val internal get() = AppPrefs.getInstance().internal

    private fun harness(im: InputMethodEntry, chosen: ImKeyboards.Chosen? = null, punctuation: Map<String, String>? = null): KeyboardHarness {
        made?.close()
        return KeyboardHarness(TestThemes.PixelLight, im, keyboards = chosen).apply {
            api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
            api.enabledIme = api.availableIme
        }.start().also { h ->
            made = h
            // cached too: an input view made anew replays it
            if (punctuation != null) h.chinesePunctuation(im, punctuation)
            h.relayout()
        }
    }

    private val zh = ChinesePunctuation.map

    private fun KeyboardHarness.held(key: ShownKey): Pair<List<String>, String?> {
        val f = finger(key, at(key))
        advance(700)
        f.move(at(key))
        val shown = popupChoices()
        f.up(at(key))
        idle()
        return shown.map { it.text } to shown.singleOrNull { it.focused }?.text
    }

    private fun everyComma(punctuation: Map<String, String>? = null): Sequence<Pair<String, KeyboardHarness>> = sequence {
        for (k in listOf(ImKeyboard.FULL, ImKeyboard.KEYS14, ImKeyboard.KEYS18, ImKeyboard.ZHUYIN_FULL, ImKeyboard.ZHUYIN_NINE)) {
            yield(k.name to harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = k), punctuation))
        }
    }

    // 注音九键 has two ，: the bottom row's and r4c4
    private fun KeyboardHarness.named(name: String): List<SceneShownKey> =
        keys().filterIsInstance<SceneShownKey>().filter { k -> k.node.copy == 0 && k.node.shortcut?.let { KeyId.of(it).name } == name }

    // the popup's focus moves by PopupFocus.STEP, not by where its cells are
    private fun KeyboardHarness.longPressTo(key: ShownKey, from: String, label: String) {
        val f = finger(key, at(key))
        advance(700)
        f.move(at(key))
        fun centre(text: String) = popupChoices().last { it.text == text }.cell.let { it.exactCenterX() to it.exactCenterY() }
        val (a, b) = centre(from) to centre(label)
        val step = PopupFocus.STEP / service.resources.displayMetrics.density
        val p = at(key, (b.first - a.first) * step, (b.second - a.second) * step)
        f.move(p)
        f.up(p)
        idle()
    }

    private val comma = listOf(".", "?", "!", ":", ";", "\"", "'", "(", ")", "~")

    @Test
    @Uat("KB-73")
    fun commaIsOneKey() {
        for (punct in listOf(zh, emptyMap<String, String>())) for ((board, h) in everyComma(punct)) {
            val keys = h.named("fn/comma")
            assertEquals(board, if (board == ImKeyboard.ZHUYIN_NINE.name) 2 else 1, keys.size)
            // their places on the screen go by where the key is, so not their order
            for (k in keys) assertEquals("$board ${k.node.id}", comma.map { punct[it] ?: it }.sorted() to (punct["."] ?: "."), h.held(k).let { (items, focused) -> items.sorted() to focused })
        }
    }

    @Test
    @Uat("KB-74")
    fun commaEditedOnceEverywhere() {
        val edited = comma - "~" + "…"
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("fn/comma") to UserList(false, edited))).serialize())
        for ((board, h) in everyComma(zh)) {
            for (k in h.named("fn/comma")) {
                assertEquals("$board ${k.node.id}", edited.map { zh[it] ?: it }.sorted() to "。", h.held(k).let { (items, focused) -> items.sorted() to focused })
            }
        }
    }

    @Test
    @Uat("KB-75")
    fun commaFirstDrawnAndTyped() {
        prefs.setWays(up = true, down = true)
        prefs.set(internal.keySwipes, SwipeSlots.Default.with("fn/comma", up = true, SwipeSlot.First).serialize())
        for ((board, h) in everyComma()) {
            for (k in h.named("fn/comma")) {
                assertTrue("$board ${k.node.id}: ${k.drawnTexts}", "." in k.drawnTexts)
                h.swipe(k, 0f, -k.height * 1.3f)
                h.idle()
                assertTrue("$board ${k.node.id}: ${h.sent()}", "key=.," in h.sent().last())
            }
        }
    }

    // focused as it opens wherever it is in the list, not the row's middle
    @Test
    @Uat("KB-75")
    fun heldAndLetGoTypesTheFirst() {
        fun first(id: String, symbols: List<String>, first: String) =
            prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId(id) to UserList(false, symbols, First.Literal(first)))).serialize())
        first("t9/5", listOf("J", "K", "L", "5", "j", "k", "l", "#", "/"), "k")
        var h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)
        assertEquals("k", h.held(h.nineKey("5")).second)
        first("t9/5", listOf("J", "K", "L", "5", "j", "k", "l", "#", "/"), "/")
        h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)
        h.api.calls.clear()
        assertEquals("/", h.held(h.nineKey("5")).second)
        assertTrue(h.sent().toString(), "key=/," in h.sent().last())
        first("full/q", listOf("1", "Q", "‰"), "Q")
        h = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.FULL))
        h.api.calls.clear()
        assertEquals("Q", h.held(h.named("full/q").single()).second)
        assertTrue(h.sent().toString(), "key=Q," in h.sent().last())
    }

    // the bubble shows the letter as it types, not in the face's case
    @Test
    @Uat("KB-83")
    fun aLetterFirstSwipedShowsAsItTypes() {
        prefs.setWays(up = true, down = true)
        fun swiped(h: KeyboardHarness, k: SceneShownKey): Pair<List<String>, String> {
            h.api.calls.clear()
            val finger = h.finger(k, h.at(k))
            for (i in 1..5) finger.move(h.at(k, dy = -k.height * 0.3f * i, ms = 16))
            val bubble = h.popupChoices().map { it.text }.filter { it.isNotEmpty() }
            finger.up(h.at(k, dy = -k.height * 1.5f))
            h.idle()
            // sent as a key on the full keyboard, committed as it is on 14 键 (GroupedKeyboard.resolve)
            return bubble to (h.sent().lastOrNull() ?: "committed ${h.field.text}")
        }
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("full/q") to UserList(false, listOf("1", "Q"), First.Literal("Q")))).serialize())
        val full = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.FULL))
        val (q, qSent) = swiped(full, full.named("full/q").single())
        assertEquals(listOf("Q"), q)
        assertTrue(qSent, "key=Q," in qSent)
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("g14/qw") to UserList(false, listOf("q"), First.Literal("q")))).serialize())
        val fourteen = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        val (qw, qwSent) = swiped(fourteen, fourteen.named("g14/qw").single())
        assertEquals(listOf("q"), qw)
        assertEquals("committed q", qwSent)
    }

    @Test
    @Uat("KB-75")
    fun heldAndLetGoTypesTheFirstOnANarrowKeyboard() {
        prefs.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Left)
        val lists = mapOf(
            KeyId("t9/1") to UserList(false, listOf("1", "^", ":", ";", "~", ".", "-", "@", "\\", "\""), First.Literal("@")),
            KeyId("t9/5") to UserList(false, listOf("J", "K", "L", "5", "j", "k", "l", "#", "(", ")", "/", "%"), First.Literal("/")),
        )
        prefs.set(internal.keyLists, KeyLists.of(lists).serialize())
        val h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)
        for ((digit, first) in listOf("1" to "@", "5" to "/")) {
            h.api.calls.clear()
            assertEquals(digit, first, h.held(h.nineKey(digit)).second)
            assertTrue(h.sent().toString(), "key=$first," in h.sent().last())
        }
    }

    @Test
    @Uat("KB-76")
    fun nineKeysOneThroughThePunctuationEverywhereAndSharedOnceEdited() {
        var h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS, punctuation = zh)
        val one = listOf("1", "^", ":", ";", "~", ".", "-", "@", "\\", "\"")
        assertEquals(one.map { zh[it] ?: it }.sorted(), h.longPress(h.nineKey("1")).sorted())
        h.api.calls.clear()
        h.longPressTo(h.nineKey("1"), "1", "。")
        assertTrue(h.sent().toString(), "key=.," in h.sent().last())
        h = harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS))
        assertEquals(one.sorted(), h.longPress(h.nineKey("1")).sorted())
        h.api.calls.clear()
        h.longPressTo(h.nineKey("1"), "1", ".")
        assertTrue(h.sent().toString(), "key=.," in h.sent().last())
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("t9/1") to UserList(false, listOf("1", ".", "@")))).serialize())
        h = harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS))
        assertEquals(listOf(".", "1", "@"), h.longPress(h.nineKey("1")).sorted())
    }

    @Test
    @Uat("KB-76")
    fun nineKeysOnesTapCyclesThePunctuationAsItMapsInBothLanguages() {
        // English's 1 as pinyin's (no ' or -: the long presses have them)
        prefs.set(AppPrefs.getInstance().keyboard.t9MultiTap, T9MultiTap.Medium)
        fun KeyboardHarness.cycle(): List<String> = List(5) {
            tap(nineKey("1"))
            advance(150)
            field.text.toString()
        }
        var h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS, punctuation = zh)
        assertEquals("，。？！", h.nineKey("1").label)
        assertEquals(listOf("，", "。", "？", "！", "，"), h.cycle())
        h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS, punctuation = emptyMap())
        assertEquals(",.?!", h.nineKey("1").label)
        assertEquals(listOf(",", ".", "?", "!", ","), h.cycle())
        h = harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS))
        assertEquals(",.?!", h.nineKey("1").label)
        assertEquals(listOf(",", ".", "?", "!", ","), h.cycle())
    }

    @Test
    @Uat("KB-77")
    fun nineKeysFiveSymbolsSharedLettersByInputMethod() {
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("t9/5") to UserList(false, listOf("J", "K", "L", "5", "j", "k", "l", "#", "(", ")", "%")))).serialize())
        val pinyin = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS).let { it.longPress(it.nineKey("5")) }
        assertTrue(pinyin.toString(), pinyin.containsAll(listOf("J", "K", "L", "5", "j", "k", "l", "%")))
        val english = harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)).let { it.longPress(it.nineKey("5")) }
        assertEquals(pinyin.sorted(), english.sorted())
    }

    @Test
    @Uat("KB-78")
    fun zhuyinNinesToneAndSlot() {
        prefs.setWays(up = true, down = true)
        prefs.set(internal.keySwipes, SwipeSlots.Default.with("fn/comma", up = true, SwipeSlot.First).serialize())
        val h = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_NINE))
        val tone = h.named("zy9/tone-1").single()
        val sentBefore = h.sent().size
        assertEquals(listOf("˙"), h.longPress(tone, "˙"))
        val toneSent = h.sent().drop(sentBefore)
        assertEquals(toneSent.toString(), 1, toneSent.size)
        h.swipe(tone, 0f, -tone.height * 1.3f)
        h.idle()
        assertEquals("swiped to its first, the same", toneSent.single(), h.sent().last())
        val (bottom, slot) = h.named("fn/comma").sortedBy { it.node.id != "r4c4" }.let { it[1] to it[0] }
        assertEquals(h.longPress(bottom).sorted(), h.longPress(slot).sorted())
        h.swipe(slot, 0f, -slot.height * 1.3f)
        h.idle()
        assertTrue(h.sent().toString(), "key=.," in h.sent().last())
        // composing: the slot's tap is 分词, and so is its empty down (a swipe to nothing is a tap on every keyboard)
        KeyboardHarness.find<ZhuyinKeyboard>(h.keyboard)!!.showTyping(true)
        h.tap(slot)
        h.idle()
        val segment = h.sent().last()
        assertTrue(segment, "key=.," !in segment && "sym=44," !in segment)
        h.api.calls.clear()
        h.swipe(slot, 0f, slot.height * 1.3f)
        h.idle()
        assertEquals("swiped down to nothing: 分词", listOf(segment), h.sent())
        h.swipe(slot, 0f, -slot.height * 1.3f)
        h.idle()
        assertTrue(h.sent().toString(), "key=.," in h.sent().last())
    }

    @Test
    @Uat("KB-78")
    fun zhuyinNinesToneOff() {
        prefs.setWays(up = true, down = true)
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("zy9/tone-1") to UserList.Off)).serialize())
        val h = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_NINE))
        val tone = h.named("zy9/tone-1").single()
        h.tap(tone)
        h.idle()
        val tapped = h.sent().last()
        assertEquals("its bubble alone, no ˙", emptyList<String>(), h.longPress(tone) - "ˉ")
        h.api.calls.clear()
        h.swipe(tone, 0f, -tone.height * 1.3f)
        h.idle()
        assertEquals(listOf(tapped), h.sent())
    }

    @Test
    @Uat("KB-79")
    fun fourteenKeysQwFirstDrawnAndTyped() {
        prefs.setWays(up = true, down = true)
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("g14/qw") to UserList(false, listOf("!"), First.Literal("!")))).serialize())
        val h = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        val qw = h.named("g14/qw").single()
        assertTrue(qw.drawnTexts.toString(), "!" in qw.drawnTexts)
        val finger = h.finger(qw, h.at(qw))
        for (i in 1..5) finger.move(h.at(qw, dy = -qw.height * 0.3f * i, ms = 16))
        val bubble = h.popupChoices().map { it.text }.filter { it.isNotEmpty() }
        finger.up(h.at(qw))
        h.idle()
        h.api.calls.clear()
        assertEquals(listOf("!"), bubble)
        h.swipe(qw, 0f, -qw.height * 1.3f)
        h.idle()
        assertTrue(h.sent().toString(), "key=!," in h.sent().last())
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("full/q") to UserList(false, listOf("?"), First.Literal("?")))).serialize())
        val again = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        assertTrue(again.named("g14/qw").single().drawnTexts.toString(), "1" in again.named("g14/qw").single().drawnTexts)
    }
}
