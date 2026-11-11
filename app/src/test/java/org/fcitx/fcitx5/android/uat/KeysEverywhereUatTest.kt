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
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
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
 * One key the same on every keyboard (060-keys-unify §6.2, UAT KB-72–78): ，'s long press, its edit and its
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

    private fun harness(im: InputMethodEntry, chosen: ImKeyboards.Chosen? = null): KeyboardHarness {
        made?.close()
        return KeyboardHarness(TestThemes.PixelLight, im, keyboards = chosen).apply {
            api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, handwriting)
            api.enabledIme = api.availableIme
        }.start().also { made = it; it.relayout() }
    }

    // every keyboard with a ，: pinyin's full, 14 and 18 keys, 注音's two, handwriting
    private fun everyComma(): Sequence<Pair<String, KeyboardHarness>> = sequence {
        for (k in listOf(ImKeyboard.FULL, ImKeyboard.KEYS14, ImKeyboard.KEYS18, ImKeyboard.ZHUYIN_FULL, ImKeyboard.ZHUYIN_NINE)) {
            yield(k.name to harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = k)))
        }
        yield("handwriting" to harness(handwriting))
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
    @Uat("KB-72")
    fun commaIsOneKey() {
        for ((board, h) in everyComma()) {
            val keys = h.named("fn/comma")
            assertEquals(board, if (board == ImKeyboard.ZHUYIN_NINE.name) 2 else 1, keys.size)
            for (k in keys) assertEquals("$board ${k.node.id}", comma.sorted(), h.longPress(k).sorted())
        }
    }

    @Test
    @Uat("KB-73")
    fun commaEditedOnceEverywhere() {
        val edited = comma - "~" + "…"
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("fn/comma") to UserList(false, edited))).serialize())
        for ((board, h) in everyComma()) {
            for (k in h.named("fn/comma")) assertEquals("$board ${k.node.id}", edited.sorted(), h.longPress(k).sorted())
        }
    }

    @Test
    @Uat("KB-74")
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

    @Test
    @Uat("KB-75")
    fun nineKeysOneAsIsEverywhereAndSharedOnceEdited() {
        var h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS)
        // as it is: a half-width . whatever the punctuation, not the engine's 。
        h.longPressTo(h.nineKey("1"), "1", ".")
        assertEquals(".", h.field.text.toString())
        h = harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS))
        assertEquals((listOf("1") + ":;\"()@&/*~".map(Char::toString)).sorted(), h.longPress(h.nineKey("1")).sorted())
        // changed once on pinyin's: English's the same (M3), its 1 kept
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("t9/1") to UserList(false, listOf(".", "@")))).serialize())
        h = harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS))
        assertEquals(listOf(".", "1", "@"), h.longPress(h.nineKey("1")).sorted())
    }

    @Test
    @Uat("KB-76")
    fun nineKeysFiveSymbolsSharedLettersByInputMethod() {
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("t9/5") to UserList(false, listOf("#", "(", ")", "%")))).serialize())
        val pinyin = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS).let { it.longPress(it.nineKey("5")) }
        assertTrue(pinyin.toString(), pinyin.containsAll(listOf("J", "K", "L", "5", "j", "k", "l", "%")))
        val english = harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)).let { it.longPress(it.nineKey("5")) }
        assertTrue(english.toString(), english.containsAll(listOf("j", "k", "l", "5", "%")))
    }

    @Test
    @Uat("KB-77")
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
    }

    @Test
    @Uat("KB-78")
    fun fourteenKeysQwFirstDrawnAndTyped() {
        prefs.setWays(up = true, down = true)
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("g14/qw") to UserList(false, listOf("!"), First.Literal("!")))).serialize())
        val h = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        val qw = h.named("g14/qw").single()
        assertTrue(qw.drawnTexts.toString(), "!" in qw.drawnTexts)
        h.swipe(qw, 0f, -qw.height * 1.3f)
        h.idle()
        assertTrue(h.sent().toString(), "key=!," in h.sent().last())
        // the full keyboard's q is another key (L3)
        prefs.set(internal.keyLists, KeyLists.of(mapOf(KeyId("full/q") to UserList(false, listOf("?"), First.Literal("?")))).serialize())
        val again = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        assertTrue(again.named("g14/qw").single().drawnTexts.toString(), "1" in again.named("g14/qw").single().drawnTexts)
    }
}
