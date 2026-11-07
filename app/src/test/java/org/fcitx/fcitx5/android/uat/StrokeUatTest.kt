/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.ViewShownKey
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
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
 * The keyboard's half of 笔画筛选 (uat/02 KB-42, KB-43): a stroke drawn over the keys while pinyin
 * is typed is sent as the engine's stroke key, the keys cancelled; with nothing typed, with glide
 * typing chosen instead, or in English, none is. What the engine does with it is StrokeFilterUatTest
 * (:lib:ime-eval), the shape read StrokeShapeTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class StrokeUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private val glideTyping = AppPrefs.getInstance().keyboard.glideTyping
    private val strokeFilter = AppPrefs.getInstance().keyboard.strokeFilter
    private var open: KeyboardHarness? = null
    private val h get() = open!!

    @After
    fun close() {
        open?.close()
    }

    // 笔画筛选 on, or 滑行输入 instead where [glide]
    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)

    private fun harness(im: InputMethodEntry, glide: Boolean = false, keyboards: ImKeyboards.Chosen? = null): KeyboardHarness {
        prefs.set(strokeFilter, !glide)
        prefs.set(glideTyping, glide)
        return KeyboardHarness(ThemePreset.PixelLight, im, keyboards = keyboards).also { open = it }.start()
    }

    // pinyin typed (the code row shows it) or nothing; in a temporary mode, with its line [aux] above
    private fun typed(pinyin: String, aux: String = "") {
        val above = if (aux.isEmpty()) FormattedText.Empty else FormattedText(arrayOf(aux), intArrayOf(0), -1)
        h.api.inputPanelCached = HostEvent.InputPanel(FormattedText(arrayOf(pinyin), intArrayOf(0), pinyin.length), above, FormattedText.Empty)
        h.emit(h.api.inputPanelCached)
    }

    private fun downs() = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    private fun strokesSent() = downs().filter { call -> Keyboard.STROKES.any { "sym=${UNICODE + it}," in call } }

    private fun stroke(c: Char) = "sym=${UNICODE + Keyboard.strokeKey(c)},"

    // a finger down on [v], moved [across] and [down] of its width and height, then lifted
    private fun draw(v: org.fcitx.fcitx5.android.input.keyboard.KeyView, across: Float, down: Float) = draw(ViewShownKey(v), across, down)

    private fun draw(v: ShownKey, across: Float, down: Float) {
        val b = h.box(v)
        h.drag(v, b.exactCenterX() + across * b.width(), b.exactCenterY() + down * b.height(), steps = 12)
    }

    // a stroke drawn while pinyin is typed is sent as one, the keys under it none: from any row, short or long
    @Test
    @Uat("KB-42")
    fun aStrokeDrawnWhilePinyinIsTypedIsSentAsOneTheKeysNone() {
        harness(KeyboardHarness.PINYIN)
        class Row(val why: String, val strokes: String, val drawn: () -> Unit)
        assertRows(
            listOf(
                Row("short, from any row: 竖 from the bottom row half a key down, a short 点, a 横 half a key across", "snh") {
                    draw(h.key("x"), across = 0f, down = 0.5f)
                    draw(h.key("e"), across = 0.35f, down = 0.35f)
                    draw(h.key("a"), across = 0.5f, down = 0f)
                },
                Row("long: 横 across the top row, 竖 down two rows, 撇 down to the left, 点 down to the right, 折 right then down", "hspnz") {
                    draw(h.key("w"), across = 3f, down = 0f)
                    draw(h.key("t"), across = 0f, down = 2f)
                    draw(h.key("o"), across = -1.5f, down = 1.8f)
                    draw(h.key("e"), across = 1.5f, down = 1.8f)
                    val e = h.box(h.key("e"))
                    h.drag(h.key("e"), e.exactCenterX() + 2.5f * e.width(), e.exactCenterY(), steps = 6, lift = false)
                    h.move(e.exactCenterX() + 2.5f * e.width(), e.exactCenterY() + 2f * e.height(), steps = 6, lift = true)
                },
                // up is no stroke: nothing sent, nothing typed
                Row("up", "") { draw(h.key("x"), across = 0f, down = -2f) },
            ),
            { it.why },
        ) { row ->
            typed("hao")
            h.api.calls.clear()
            row.drawn()
            val strokes = row.strokes.map(::stroke)
            assertEquals(downs().toString(), strokes.size, downs().size)
            assertEquals(downs().toString(), strokes, downs().map { call -> strokes.first { it in call } })
            assertTrue(h.api.calls.toString(), h.api.calls.none { it.startsWith("offerGlide") })
        }
        // a tap is still a tap
        typed("hao")
        h.api.calls.clear()
        h.tap(h.key("q"))
        assertTrue(downs().toString(), strokesSent().isEmpty() && downs().isNotEmpty())
    }

    @Test
    @Uat("KB-42")
    fun theNineAndFourteenKeysSendAStrokeToo() {
        harness(KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS)
        typed("wang")
        h.api.calls.clear()
        draw(h.nineKey("2"), across = 0.8f, down = 1.2f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('n') in it })
        close()
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.Keys14)
        harness(KeyboardHarness.PINYIN)
        typed("hao")
        h.api.calls.clear()
        val gh = KeyboardHarness.all(h.inputView).filterIsInstance<AltTextKeyView>().first { h.shown(it) && it.mainText.text.toString() == "GH" }
        draw(gh, across = 0f, down = 2f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('s') in it })
        close()
        // pinyin's 18 keys
        harness(KeyboardHarness.PINYIN, keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18))
        typed("hao")
        h.api.calls.clear()
        val faces18 = NineKeyLetters.G18.tag().split(',').map { it.uppercase() }
        val top18 = KeyboardHarness.all(h.inputView).filterIsInstance<AltTextKeyView>().first { h.shown(it) && it.mainText.text.toString() in faces18 }
        draw(top18, across = 0f, down = 2f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('s') in it })
        close()
        // 双拼's nine keys, whose keys glide no letters (T9Keyboard's other path): a stroke all the same
        harness(shuangpin, keyboards = ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS))
        typed("hk")
        h.api.calls.clear()
        draw(h.nineKey("2"), across = 0.8f, down = 1.2f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('n') in it })
    }

    @Test
    @Uat("KB-43")
    fun withNothingTypedGlideChosenOrEnglishNoStrokeIsSent() {
        // nothing typed: the drag is the keys' as ever, no stroke
        harness(KeyboardHarness.PINYIN)
        draw(h.key("w"), across = 3f, down = 0f)
        assertEquals(emptyList<String>(), strokesSent())
        close()
        // glide typing chosen: the same drag glides, no stroke
        harness(KeyboardHarness.PINYIN, glide = true)
        typed("hao")
        draw(h.key("w"), across = 3f, down = 0f)
        assertEquals(emptyList<String>(), strokesSent())
        assertEquals(h.api.calls.toString(), 1, h.api.calls.count { it.startsWith("offerGlide") })
        close()
        // 笔画筛选 chosen: English neither glides nor filters
        harness(KeyboardHarness.ENGLISH)
        typed("hel")
        draw(h.key("w"), across = 3f, down = 0f)
        assertEquals(emptyList<String>(), strokesSent())
        assertTrue(h.api.calls.toString(), h.api.calls.none { it.startsWith("offerGlide") })
        close()
        // a temporary mode (快速输入): what is typed is not pinyin's, no stroke
        harness(KeyboardHarness.PINYIN)
        typed("ab", aux = "快速输入：")
        draw(h.key("w"), across = 3f, down = 0f)
        assertEquals(emptyList<String>(), strokesSent())
        close()
        // nor does a table (五笔): it glides only when glide typing is chosen
        harness(InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "", "五", "zh_CN", "androidengine", false))
        typed("wq")
        draw(h.key("w"), across = 3f, down = 0f)
        assertEquals(emptyList<String>(), strokesSent())
        assertTrue(h.api.calls.toString(), h.api.calls.none { it.startsWith("offerGlide") })
    }
}
