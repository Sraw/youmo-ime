/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Rect
import android.view.MotionEvent
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.GlideInput
import org.fcitx.fcitx5.android.input.keyboard.GroupedKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ZhuyinKeyboard
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
        draw(h.key("GH"), across = 0f, down = 2f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('s') in it })
        close()
        // pinyin's 18 keys
        harness(KeyboardHarness.PINYIN, keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18))
        typed("hao")
        h.api.calls.clear()
        val faces18 = NineKeyLetters.G18.tag().split(',').map { it.uppercase() }
        draw(h.keys().first { it.label in faces18 }, across = 0f, down = 2f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('s') in it })
        close()
        // 双拼's nine keys, whose keys glide no letters (T9Keyboard's other path): a stroke all the same
        harness(shuangpin, keyboards = ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS))
        typed("hk")
        h.api.calls.clear()
        draw(h.nineKey("2"), across = 0.8f, down = 1.2f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('n') in it })
    }

    // the split keyboard's gap: a stroke may start there (no key to tap); a tap there, or a glide, still does nothing
    @Test
    @Uat("KB-42")
    @Config(qualifiers = LANDSCAPE)
    fun aStrokeStartsInTheSplitGapTooATapOrAGlideThereDoNothing() {
        harness(KeyboardHarness.PINYIN)
        val gap = gap()
        val t = h.box(h.key("t"))
        // what GlideInput is told there, and not on the letters beside it
        assertTrue(gapAt(gap.exactCenterX(), gap.exactCenterY()))
        assertFalse(gapAt(t.exactCenterX(), t.exactCenterY()))
        typed("hao")
        h.api.calls.clear()
        h.drag(gap, gap.exactCenterX(), gap.exactCenterY() + 2f * t.height())
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('s') in it })
        // a tap there: nothing
        h.api.calls.clear()
        h.drag(gap, gap.exactCenterX(), gap.exactCenterY(), steps = 1)
        assertEquals(emptyList<String>(), downs())
        close()
        // glide typing chosen: no glide from the gap, nothing typed
        harness(KeyboardHarness.PINYIN, glide = true)
        typed("hao")
        h.api.calls.clear()
        h.drag(gap, gap.exactCenterX() + 3f * t.width(), gap.exactCenterY())
        assertTrue(h.api.calls.toString(), h.api.calls.none { it.startsWith("offerGlide") } && downs().isEmpty())
    }

    // a finger resting in the gap, another tapping a letter: the letter typed as ever, no stroke
    @Test
    @Uat("KB-42")
    @Config(qualifiers = LANDSCAPE)
    fun aSecondFingerOnALetterWhileOneRestsInTheGapTypesIt() {
        harness(KeyboardHarness.PINYIN)
        val gap = gap()
        val k = h.box(h.key("k"))
        typed("hao")
        // what k typed alone sends
        h.api.calls.clear()
        h.drag(k, k.exactCenterX(), k.exactCenterY(), steps = 1)
        val alone = downs()
        assertEquals(alone.toString(), 1, alone.size)
        typed("hao")
        h.api.calls.clear()
        h.drag(gap, gap.exactCenterX(), gap.exactCenterY(), steps = 1, lift = false)
        h.secondFingerThenLift(k.exactCenterX(), k.exactCenterY())
        assertEquals(alone, downs())
        assertEquals(emptyList<String>(), strokesSent())
    }

    // a stroke from the gap cancelled midway (the system took the touch): nothing sent, its trail gone
    @Test
    @Uat("KB-42")
    @Config(qualifiers = LANDSCAPE)
    fun aStrokeFromTheGapCancelledSendsNothingAndLeavesNoTrail() {
        harness(KeyboardHarness.PINYIN)
        val gap = gap()
        val keyHeight = h.box(h.key("t")).height()
        typed("hao")
        h.api.calls.clear()
        val f = h.Finger { intArrayOf(gap.left, gap.top) }
        val x = gap.width() / 2f
        val y = gap.height() / 2f
        f.send(MotionEvent.ACTION_DOWN, KeyboardHarness.Touch(x, y))
        for (i in 1..6) {
            h.advance(16)
            f.move(KeyboardHarness.Touch(x, y + i * keyHeight / 3f))
        }
        assertTrue(glide().trailShown)
        f.send(MotionEvent.ACTION_CANCEL, KeyboardHarness.Touch(x, y + 2f * keyHeight))
        h.relayout()
        assertFalse(glide().trailShown)
        assertEquals(emptyList<String>(), downs())
        // the next touch as ever: a stroke from the gap again
        typed("hao")
        h.api.calls.clear()
        h.drag(gap, gap.exactCenterX(), gap.exactCenterY() + 2f * keyHeight)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('s') in it })
    }

    // only the split 26 keys have a gap: no point of any other layout's keyboard is one, as GlideInput asks
    @Test
    @Uat("KB-42")
    fun noOtherLayoutHasAGap() {
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        class Board(val name: String, val board: PinyinKeyboard, val kind: Class<*>, val oneHanded: OneHanded = OneHanded.Off)
        val boards = listOf(
            Board("26 keys", PinyinKeyboard.Full, TextKeyboard::class.java),
            Board("26 keys one-handed", PinyinKeyboard.Full, TextKeyboard::class.java, OneHanded.Right),
            Board("14 keys", PinyinKeyboard.Keys14, GroupedKeyboard::class.java),
            Board("18 keys", PinyinKeyboard.Keys18, GroupedKeyboard::class.java),
            Board("nine keys", PinyinKeyboard.NineKeys, T9Keyboard::class.java),
            Board("zhuyin", PinyinKeyboard.ZhuyinFull, ZhuyinKeyboard::class.java),
            Board("zhuyin nine", PinyinKeyboard.ZhuyinNine, ZhuyinKeyboard::class.java),
        )
        for (b in boards) {
            prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, b.board)
            prefs.set(ThemeManager.prefs.oneHanded, b.oneHanded)
            harness(KeyboardHarness.PINYIN)
            val kb = board()
            assertTrue("${b.name}: ${kb.javaClass}", b.kind.isInstance(kb))
            // one-handed: the keyboard narrower than the screen, its letters as one block at a side
            if (b.oneHanded != OneHanded.Off) assertTrue("${kb.width} of ${kb.rootView.width}", kb.width < kb.rootView.width * 0.9)
            val found = grid(kb).filter { (x, y) -> kb.glideForTest?.inGap(x, y) == true }
            assertEquals(b.name, emptyList<Pair<Float, Float>>(), found)
            close()
        }
    }

    // the same grid finds the split keyboard's gap: the sweep above would see one
    @Test
    @Uat("KB-42")
    @Config(qualifiers = LANDSCAPE)
    fun theSweepFindsTheSplitGap() {
        harness(KeyboardHarness.PINYIN)
        val kb = board()
        assertTrue(grid(kb).any { (x, y) -> kb.glideForTest?.inGap(x, y) == true })
    }

    // the letters shown now
    private fun board(): BaseKeyboard = KeyboardHarness.all(h.keyboard).filterIsInstance<BaseKeyboard>().first { h.shown(it) }

    private fun glide(): GlideInput = board().glideForTest!!

    // points every 1/40 across and 1/20 down the keyboard, in its pixels
    private fun grid(kb: BaseKeyboard) = (0..40).flatMap { i -> (0..20).map { j -> kb.width * i / 40f to kb.height * j / 20f } }

    // between T and Y, the top row's halves of the split letters, the screen's
    private fun gap(): Rect {
        assertEquals(h.texts().toString(), 2, h.keys().count { it.label.equals("g", ignoreCase = true) })
        val t = h.box(h.key("t"))
        val y = h.box(h.key("y"))
        assertTrue("$t $y", y.left - t.right > t.width())
        return Rect(t.right, t.top, y.left, t.bottom)
    }

    // whether GlideInput has [x], [y] (the screen's) in the gap
    private fun gapAt(x: Float, y: Float): Boolean {
        val kb = board()
        val at = IntArray(2).also(kb::getLocationInWindow)
        return kb.glideForTest!!.inGap(x - at[0], y - at[1])
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

    companion object {
        // wide enough to split (SplitKeyboard.AUTO_MIN_WIDTH_DP)
        private const val LANDSCAPE = "w851dp-h393dp-land-440dpi"
    }
}
