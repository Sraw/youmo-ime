/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Each key's two slots on a whole keyboard (060-swipe-slots): a text drawn as its first two characters and
 * typed whole, the slots flipped with their places, another keyboard's long press set by name and its
 * first swiped to.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SwipeSlotsUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var made: KeyboardHarness? = null

    @After
    fun close() {
        made?.close()
    }

    private val internal get() = AppPrefs.getInstance().internal

    private fun harness(keyboard: ImKeyboard? = null) =
        KeyboardHarness(TestThemes.PixelLight, keyboards = keyboard?.let { ImKeyboards.Chosen(pinyin = it) }).also { made = it }.start()

    private fun KeyboardHarness.sent() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    // a key's marks as drawn: its shortcuts' places, its symbol's (T/B, L/C/R)
    private fun KeyboardHarness.marksOf(label: String): String {
        val m = (keys().first { it.label == label } as SceneShownKey).markPlaces()
        return "${m.shortcuts.joinToString(",").ifEmpty { "-" }} ${m.symbol ?: "-"}"
    }

    @Test
    @Uat("ST-97")
    fun aTextSlotShowsTwoCharactersAndTypesAll() {
        prefs.set(internal.keySwipes, SwipeSlots.Default.with("full/q", up = false, SwipeSlot.Text("你好世界")).with("full/q", up = true, SwipeSlot.Empty).serialize())
        val h = harness()
        assertTrue(h.texts().toString(), "你好…" in h.texts())
        assertFalse(h.texts().toString(), "你好世界" in h.texts())
        assertEquals("the text at 下滑图标位置, no symbol", "TL -", h.marksOf("q"))
        val q = h.key("q")
        h.swipe(q, 0f, q.height * 1.3f)
        h.idle()
        assertEquals("你好世界", h.field.text.toString())
        // up is empty: a tap of q
        h.swipe(q, 0f, -q.height * 1.3f)
        h.idle()
        assertTrue(h.sent().toString(), "key=q," in h.sent().last())
    }

    @Test
    @Uat("ST-98")
    fun flippedTheSwipesSwapAndTheMarksStay() {
        val k = AppPrefs.getInstance().keyboard
        // as 翻转上下滑 leaves them: the slots flipped, the two places swapped
        prefs.set(internal.keySwipes, SwipeSlots.Default.flippedAll(listOf("fn/space", "fn/backspace")).serialize())
        prefs.set(k.swipeUpPosition, KeyMarkPosition.TopLeft)
        prefs.set(k.swipeDownPosition, KeyMarkPosition.TopRight)
        val h = harness()
        assertEquals("全选 still top left, @ top right", "TL TR", h.marksOf("a"))
        val a = h.key("a")
        h.swipe(a, 0f, a.height * 1.3f)
        h.idle()
        assertTrue(h.sent().toString(), "key=@," in h.sent().last())
        assertEquals(SwipeSlot.Command(KeyCommand.SelectAll), SwipeSlots.parse(internal.keySwipes.getValue()).resolve("full/a", up = true))
        assertEquals(SwipeOrientation.FirstDown, SwipeSlots.parse(internal.keySwipes.getValue()).orientation)
    }

    // 注音九键's ˉ, a key no layout names: its ˙ swiped the way the orientation puts every long press's first;
    // a 0.5 upgrade (the first down) keeps swiping down to it, and 翻转全部 flips it
    @Test
    fun anUnnamedKeysSwipeFollowsTheOrientation() {
        val k = AppPrefs.getInstance().keyboard
        // the 简繁 hint not said
        prefs.set(internal.zhuyinTraditionalTold, true)
        fun swiped(orientation: SwipeOrientation, down: Boolean): Pair<String, String> {
            prefs.set(internal.keySwipes, SwipeSlots.Default.applied(orientation).serialize())
            // as SwipeMigration leaves an upgrade: the first's mark where 标点位置 was (top right)
            prefs.set(k.swipeUpPosition, if (orientation == SwipeOrientation.FirstUp) KeyMarkPosition.TopRight else KeyMarkPosition.TopLeft)
            prefs.set(k.swipeDownPosition, if (orientation == SwipeOrientation.FirstUp) KeyMarkPosition.TopLeft else KeyMarkPosition.TopRight)
            val h = harness(ImKeyboard.ZHUYIN_NINE)
            val tone = h.key("ˉ")
            h.api.calls.clear()
            h.swipe(tone, 0f, (if (down) 1.3f else -1.3f) * tone.height)
            h.idle()
            val out = h.sent().joinToString() to h.marksOf("ˉ")
            h.close()
            made = null
            return out
        }
        val neutralUp = swiped(SwipeOrientation.FirstUp, down = false)
        val tappedDown = swiped(SwipeOrientation.FirstUp, down = true)
        assertTrue("˙ and ˉ apart: $neutralUp $tappedDown", neutralUp.first.isNotEmpty() && neutralUp.first != tappedDown.first)
        assertEquals("first down: ˙ down", neutralUp.first, swiped(SwipeOrientation.FirstDown, down = true).first)
        assertEquals("first down: up a tap", tappedDown.first, swiped(SwipeOrientation.FirstDown, down = false).first)
        // no corner drawn (a function key's cap), either way: nothing that says the other way
        assertEquals("- -", neutralUp.second)
        assertEquals("- -", swiped(SwipeOrientation.FirstDown, down = true).second)
        // 翻转全部 from the new default: the orientation flipped, ˙ down
        prefs.set(internal.keySwipes, SwipeSlots.Default.flippedAll(listOf("fn/space", "fn/backspace")).serialize())
        val h = harness(ImKeyboard.ZHUYIN_NINE)
        val tone = h.key("ˉ")
        h.api.calls.clear()
        h.swipe(tone, 0f, 1.3f * tone.height)
        h.idle()
        assertEquals(neutralUp.first, h.sent().joinToString())
    }

    @Test
    @Uat("ST-100")
    fun aGroupKeysLongPressSetByName() {
        prefs.set(internal.keyPopups, "g14/qw ① ②")
        val h = harness(ImKeyboard.KEYS14)
        val qw = h.key("QW")
        assertEquals(listOf("①", "②"), h.longPress(qw).take(2))
        h.idle()
        h.swipe(qw, 0f, -qw.height * 1.3f)
        h.idle()
        assertTrue(h.field.text.toString() + h.sent(), "①" in h.field.text.toString() || h.sent().any { "①" in it })
    }
}
