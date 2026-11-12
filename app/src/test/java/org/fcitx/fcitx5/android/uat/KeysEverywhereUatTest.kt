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
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.ChinesePunctuation
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.input.keyboard.ZhuyinKeyboard
import org.fcitx.fcitx5.android.input.keys.First
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.input.popup.PopupFocus
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

/**
 * One key the same on every keyboard (060-keys-unify §6.2, UAT KB-73–79): ，'s long press, its edit and its
 * first; the nine keys' 1 and 5 by input method; 注音九键's ˉ and its slot r4c4, which is ，.
 */
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

    private val handwriting = InputMethodEntry(InputMethodCatalog.HANDWRITING, "手写", "", "", "写", "zh_CN", "androidengine", false)

    private fun harness(im: InputMethodEntry, chosen: ImKeyboards.Chosen? = null, punctuation: Map<String, String>? = null): KeyboardHarness {
        made?.close()
        return KeyboardHarness(TestThemes.PixelLight, im, keyboards = chosen).apply {
            api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, handwriting)
            api.enabledIme = api.availableIme
        }.start().also { h ->
            made = h
            // fcitx's Chinese punctuation on, as its addon tells it (cached too: an input view made anew replays it)
            if (punctuation != null) {
                val active = listOf(StatusEntry(1, "punctuation", "fcitx-punc-active", ""))
                h.api.statusAreaCached = active
                h.api.punctuationCached = punctuation
                h.emit(HostEvent.StatusArea(active, im, punctuation))
            }
            h.relayout()
        }
    }

    // ，'s list as Chinese punctuation shows it, its first (。) under the finger as it opens
    private val zh = ChinesePunctuation.map

    // held: the popup's items and the one focused as it opens (let go there: it is typed)
    private fun KeyboardHarness.held(key: ShownKey): Pair<List<String>, String?> {
        val f = finger(key, at(key))
        advance(700)
        f.move(at(key))
        val shown = popupChoices()
        f.up(at(key))
        idle()
        return shown.map { it.text } to shown.singleOrNull { it.focused }?.text
    }

    // every keyboard with a ，: pinyin's full, 14 and 18 keys, 注音's two, handwriting; [punctuation] on where given
    private fun everyComma(punctuation: Map<String, String>? = null): Sequence<Pair<String, KeyboardHarness>> = sequence {
        for (k in listOf(ImKeyboard.FULL, ImKeyboard.KEYS14, ImKeyboard.KEYS18, ImKeyboard.ZHUYIN_FULL, ImKeyboard.ZHUYIN_NINE)) {
            yield(k.name to harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = k), punctuation))
        }
        yield("handwriting" to harness(handwriting, punctuation = punctuation))
    }

    // the keys named [name] the board shows (注音九键 has two ，: the bottom row's and r4c4)
    private fun KeyboardHarness.named(name: String): List<SceneShownKey> =
        keys().filterIsInstance<SceneShownKey>().filter { k -> k.node.copy == 0 && k.node.shortcut?.let { KeyId.of(it).name } == name }

    private fun KeyboardHarness.sent() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    // held, then slid from [from]'s cell to [label]'s: the popup's focus moves by PopupFocus.STEP, not by where its cells are
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
        for ((board, h) in everyComma(zh)) {
            val keys = h.named("fn/comma")
            assertEquals(board, if (board == ImKeyboard.ZHUYIN_NINE.name) 2 else 1, keys.size)
            // the same items and the first focused; their places on the screen go by where the key is, so not their order
            for (k in keys) assertEquals("$board ${k.node.id}", comma.map(zh::getValue).sorted() to "。", h.held(k).let { (items, focused) -> items.sorted() to focused })
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

    // held and let go where it opened: its first typed, focused as it opens wherever it is in the list (a first
    // above the row opened on, not the row's middle)
    @Test
    @Uat("KB-75")
    fun heldAndLetGoTypesTheFirst() {
        fun first(id: String, symbols: List<String>, first: String) =
            prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId(id) to UserList(false, symbols, First.Literal(first)))).serialize())
        // in the row
        first("t9/5", listOf("#", "/"), "k")
        var h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)
        assertEquals("k", h.held(h.nineKey("5")).second)
        // a symbol above a row
        first("t9/5", listOf("#", "/"), "/")
        h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)
        h.api.calls.clear()
        assertEquals("/", h.held(h.nineKey("5")).second)
        assertTrue(h.sent().toString(), "key=/," in h.sent().last())
        // a key of no row
        first("full/q", listOf("1", "Q", "‰"), "Q")
        h = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.FULL))
        h.api.calls.clear()
        assertEquals("Q", h.held(h.named("full/q").single()).second)
        assertTrue(h.sent().toString(), "key=Q," in h.sent().last())
    }

    // narrow (one-handed): the popup's rows more, a first in the symbols above still focused as it opens and typed
    @Test
    @Uat("KB-75")
    fun heldAndLetGoTypesTheFirstOnANarrowKeyboard() {
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Left)
        val lists = mapOf(
            KeyId("t9/1") to UserList(false, listOf("^", ":", ";", "~", ".", "-", "@", "\\", "\""), First.Literal("@")),
            KeyId("t9/5") to UserList(false, listOf("#", "(", ")", "/", "%"), First.Literal("/")),
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
        // Chinese punctuation on: shown as it types, 。, sent as the key . the engine maps (用户 2026-11-11)
        var h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS, punctuation = zh)
        val one = listOf("1", "^", ":", ";", "~", ".", "-", "@", "\\", "\"")
        assertEquals(one.map { zh[it] ?: it }.sorted(), h.longPress(h.nineKey("1")).sorted())
        h.api.calls.clear()
        h.longPressTo(h.nineKey("1"), "1", "。")
        assertTrue(h.sent().toString(), "key=.," in h.sent().last())
        // English's the same list (no case of its own), as English types it
        h = harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS))
        assertEquals(one.sorted(), h.longPress(h.nineKey("1")).sorted())
        h.api.calls.clear()
        h.longPressTo(h.nineKey("1"), "1", ".")
        assertTrue(h.sent().toString(), "key=.," in h.sent().last())
        // changed once on pinyin's: English's the same (M3), its 1 kept
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("t9/1") to UserList(false, listOf(".", "@")))).serialize())
        h = harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS))
        assertEquals(listOf(".", "1", "@"), h.longPress(h.nineKey("1")).sorted())
    }

    @Test
    @Uat("KB-76")
    fun nineKeysOnesTapCyclesThePunctuationAsItMapsInBothLanguages() {
        // 用户 2026-11-11: English's 1 as pinyin's (no ' or -: the long presses have them), each as the punctuation
        // maps it, its face as it types
        prefs.set(AppPrefs.getInstance().keyboard.t9MultiTap, T9MultiTap.Medium)
        fun KeyboardHarness.cycle(): List<String> = List(5) {
            tap(nineKey("1"))
            // within 连按's time (中, 0.6 s): the next of the run
            advance(150)
            field.text.toString()
        }
        var h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS, punctuation = zh)
        assertEquals("，。？！", h.nineKey("1").label)
        assertEquals(listOf("，", "。", "？", "！", "，"), h.cycle())
        // Chinese punctuation off: as they are, as English's
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
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("t9/5") to UserList(false, listOf("#", "(", ")", "%")))).serialize())
        val pinyin = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS).let { it.longPress(it.nineKey("5")) }
        assertTrue(pinyin.toString(), pinyin.containsAll(listOf("J", "K", "L", "5", "j", "k", "l", "%")))
        // English's pinyin's: its capitals too
        val english = harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)).let { it.longPress(it.nineKey("5")) }
        assertEquals(pinyin.sorted(), english.sorted())
    }

    @Test
    @Uat("KB-78")
    fun zhuyinNinesToneAndSlot() {
        prefs.setWays(up = true, down = true)
        prefs.set(internal.keySwipes, SwipeSlots.Default.with("fn/comma", up = true, SwipeSlot.First).serialize())
        val h = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_NINE))
        // ˉ held: ˙, the neutral tone, sent as the tone key (its item's action:tone-5, the keyboard's)
        val tone = h.named("zy9/tone-1").single()
        val sentBefore = h.sent().size
        assertEquals(listOf("˙"), h.longPress(tone, "˙"))
        val toneSent = h.sent().drop(sentBefore)
        assertEquals(toneSent.toString(), 1, toneSent.size)
        h.swipe(tone, 0f, -tone.height * 1.3f)
        h.idle()
        assertEquals("swiped to its first, the same", toneSent.single(), h.sent().last())
        // the slot is ，: the bottom row's list, its first up; its down empty, a tap (the slot's own)
        val (bottom, slot) = h.named("fn/comma").sortedBy { it.node.id != "r4c4" }.let { it[1] to it[0] }
        assertEquals(h.longPress(bottom).sorted(), h.longPress(slot).sorted())
        h.swipe(slot, 0f, -slot.height * 1.3f)
        h.idle()
        assertTrue(h.sent().toString(), "key=.," in h.sent().last())
        // composing: the slot's tap is 分词, and so is its empty down (D3: a swipe to nothing is a tap on every
        // keyboard); its up still its first, the . the engine types after committing what is typed
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

    // ˉ's list turned off: no popup, its up to nothing a tap (ˉ), as the resolver has it
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
        // held and swiped up: the bubble shows the first the resolver has, not the layout's corner (M6)
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
        // the full keyboard's q is another key (L3)
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("full/q") to UserList(false, listOf("?"), First.Literal("?")))).serialize())
        val again = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        assertTrue(again.named("g14/qw").single().drawnTexts.toString(), "1" in again.named("g14/qw").single().drawnTexts)
    }
}
