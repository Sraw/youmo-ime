/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Rect
import android.view.MotionEvent
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
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
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.StrokeLift
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
import org.robolectric.shadows.ShadowLog

/** A stroke over the keys while pinyin is typed is sent as the engine's stroke key; nothing typed, glide, English: none. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class StrokeUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private val keyDrawing = AppPrefs.getInstance().keyboard.keyDrawing
    private var open: KeyboardHarness? = null
    private val h get() = open!!

    @After
    fun close() {
        open?.close()
    }

    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "双", "zh_CN")

    private fun harness(im: InputMethodEntry, glide: Boolean = false, keyboards: ImKeyboards.Chosen? = null): KeyboardHarness {
        prefs.set(keyDrawing, if (glide) KeyDrawing.Glide else KeyDrawing.StrokeFilter)
        return KeyboardHarness(TestThemes.PixelLight, im, keyboards = keyboards).also { open = it }.start()
    }

    private fun typed(pinyin: String, aux: String = "") {
        val above = if (aux.isEmpty()) FormattedText.Empty else FormattedText(arrayOf(aux), intArrayOf(0), -1)
        h.api.inputPanelCached = HostEvent.InputPanel(FormattedText(arrayOf(pinyin), intArrayOf(0), pinyin.length), above, FormattedText.Empty)
        h.emit(h.api.inputPanelCached)
    }

    private fun downs() = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    private fun strokesSent() = downs().filter { call -> Keyboard.STROKES.any { "sym=${UNICODE + it}," in call } }

    private fun stroke(c: Char) = "sym=${UNICODE + Keyboard.strokeKey(c)},"

    private fun draw(v: ShownKey, across: Float, down: Float) {
        val b = h.box(v)
        h.drag(v, b.exactCenterX() + across * b.width(), b.exactCenterY() + down * b.height(), steps = 12)
    }

    private fun drawDp(k: ShownKey, dx: Float, dy: Float, steps: Int = 6, ms: Long = 16) {
        val path = (0..steps).map { i -> h.at(k, dx * i / steps, dy * i / steps, if (i == 0) 0 else ms) }
        h.touch(k, *path.toTypedArray())
    }

    private fun tapped(k: () -> ShownKey): List<String> {
        typed("hao")
        h.api.calls.clear()
        h.tap(k())
        return downs().also { assertTrue(it.toString(), it.isNotEmpty() && strokesSent().isEmpty()) }
    }

    // a stroke started but lifted never its distance from its down is the key's tap; its trail drawn only past it
    @Test
    @Uat("KB-101")
    fun aFingerThatSlidesShortOfTheLiftDistanceIsTheKeysTap() {
        harness(KeyboardHarness.PINYIN)
        val q = tapped { h.key("q") }
        for ((dx, dy) in listOf(15f to 0f, 0f to 15f, -11f to 11f, 0f to -23.5f, 23.5f to 0f)) {
            typed("hao")
            h.api.calls.clear()
            drawDp(h.key("q"), dx, dy)
            assertEquals("$dx,$dy", q, downs())
        }
        typed("hao")
        h.api.calls.clear()
        val k = h.key("q")
        h.touch(k, h.at(k), h.at(k, 15f, 0f, 16), h.at(k, 30f, 0f, 16), h.at(k, 5f, 0f, 16))
        assertEquals(downs().toString(), 1, strokesSent().size)
        assertEquals(downs().toString(), 1, downs().size)
        typed("hao")
        val f = h.finger(k, h.at(k))
        h.after(16)
        f.move(h.at(k, 15f, 0f))
        assertFalse(board().glideForTest!!.trailShown)
        h.after(16)
        f.move(h.at(k, 25f, 0f))
        assertTrue(board().glideForTest!!.trailShown)
        f.up(h.at(k, 25f, 0f))
        close()
        harness(KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS)
        val two = tapped { h.nineKey("2") }
        typed("hao")
        h.api.calls.clear()
        drawDp(h.nineKey("2"), 16f, 8f)
        assertEquals(two, downs())
        close()
        // the 14 keys' tap is sent with where across the key it landed: the tap's place is its down's
        prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, PinyinKeyboard.Keys14)
        harness(KeyboardHarness.PINYIN)
        val tapAt = { dx: Float ->
            typed("hao")
            h.api.calls.clear()
            h.touch(h.key("GH"), h.at(h.key("GH"), dx, 0f), h.at(h.key("GH"), dx, 0f, 16))
            downs()
        }
        val left = tapAt(-12f)
        val right = tapAt(12f)
        assertTrue("$left $right", left != right)
        typed("hao")
        h.api.calls.clear()
        val gh = h.key("GH")
        h.touch(gh, h.at(gh, -12f), h.at(gh, -4f, 0f, 16), h.at(gh, 3f, 0f, 16))
        assertEquals(left, downs())
    }

    // a short stroke's tap goes to the engine with where the finger came down, as a plain tap's does
    @Test
    fun aShortStrokesTapCarriesWhereTheFingerCameDown() {
        harness(KeyboardHarness.PINYIN)
        typed("hao")
        h.api.touches.clear()
        val s = h.key("s")
        h.touch(s, h.at(s, -10f, 0f), h.at(s, -4f, 0f, 16), h.at(s, 2f, 0f, 16), h.at(s, 5f, 0f, 16))
        assertTrue(strokesSent().toString(), strokesSent().isEmpty())
        val (key, touch) = h.api.touches.single()
        assertTrue(key, "s" in key)
        assertEquals(-10f, touch.dx * touch.widthDp, 1f)
        assertTrue(touch.near.toString(), touch.near('a') != null)
    }

    // two thumbs overlapping: q drifting down as a lands: both typed, q first, no stroke
    @Test
    @Uat("KB-101")
    fun aShortStrokeAsAnotherFingerLandsIsItsTapAndTheOtherKeyTypedToo() {
        harness(KeyboardHarness.PINYIN)
        val q = tapped { h.key("q") }
        val a = tapped { h.key("a") }
        typed("hao")
        h.api.calls.clear()
        val qb = h.box(h.key("q"))
        val ab = h.box(h.key("a"))
        val dp = h.service.resources.displayMetrics.density
        h.drag(qb, qb.exactCenterX(), qb.exactCenterY() + 14f * dp, steps = 4, lift = false)
        h.secondFingerThenLift(ab.exactCenterX(), ab.exactCenterY(), firstLifts = true)
        assertEquals(q + a, downs())
        assertEquals(emptyList<String>(), strokesSent())
        typed("hao")
        h.api.calls.clear()
        h.drag(qb, qb.exactCenterX(), qb.exactCenterY() + 14f * dp, steps = 4, lift = false)
        h.secondFingerThenLift(ab.exactCenterX(), ab.exactCenterY(), firstLifts = false)
        assertEquals(q + a, downs())
        assertEquals(emptyList<String>(), strokesSent())
    }

    // the debug trace (YoumoStrokeTap) has a line for a stroke whose other finger lifts last, swallowed
    @Test
    fun theStrokeTraceHasALineForAStrokeWhoseOtherFingerLiftsLast() {
        harness(KeyboardHarness.PINYIN)
        typed("hao")
        val qb = h.box(h.key("q"))
        val ab = h.box(h.key("a"))
        val dp = h.service.resources.displayMetrics.density
        ShadowLog.reset()
        h.drag(qb, qb.exactCenterX(), qb.exactCenterY() + 40f * dp, steps = 8, lift = false)
        h.secondFingerThenLift(ab.exactCenterX(), ab.exactCenterY(), firstLifts = true)
        val lines = ShadowLog.getLogsForTag("YoumoStrokeTap").map { it.msg }.filter { it.startsWith("kb=") }
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines.single(), "multi=1" in lines.single())
    }

    @Test
    @Uat("KB-102")
    fun aTapNearTheEdgeSlidingOffTheKeyIsStillItsTap() {
        harness(KeyboardHarness.PINYIN)
        val a = tapped { h.key("s") }
        typed("hao")
        h.api.calls.clear()
        val k = h.key("s")
        val half = k.width / 2f / h.service.resources.displayMetrics.density
        h.touch(k, h.at(k, 3f - half), h.at(k, -half - 4f, 0f, 16), h.at(k, -half - 11f, 0f, 16))
        assertEquals(a, downs())
    }

    @Test
    @Uat("ST-134")
    fun theSensitivityIsReadAtEachTouch() {
        val lift = AppPrefs.getInstance().keyboard.strokeLift
        for (nine in listOf(false, true)) {
            harness(KeyboardHarness.PINYIN, keyboards = if (nine) KeyboardHarness.NINE_KEYS else null)
            val key = { if (nine) h.nineKey("5") else h.key("g") }
            val tap = tapped(key)
            prefs.set(lift, StrokeLift.High)
            typed("hao")
            h.api.calls.clear()
            drawDp(key(), 22f, 0f)
            assertEquals("$nine ${downs()}", listOf(true), downs().map { stroke('h') in it })
            prefs.set(lift, StrokeLift.Low)
            typed("hao")
            h.api.calls.clear()
            drawDp(key(), 22f, 0f)
            assertEquals("$nine", tap, downs())
            prefs.set(lift, StrokeLift.Medium)
            close()
        }
    }

    @Test
    @Uat("KB-103")
    fun aSlowStrokeIsReadBeforeAnyLongPress() {
        for (delay in listOf(200, 300, 450)) {
            prefs.set(AppPrefs.getInstance().keyboard.longPressDelay, delay)
            harness(KeyboardHarness.PINYIN)
            typed("hao")
            h.api.calls.clear()
            drawDp(h.key("e"), 0f, 40f, steps = 12, ms = 40)
            assertEquals("$delay ${downs()}", listOf(true), downs().map { stroke('s') in it })
            close()
        }
    }

    @Test
    @Uat("KB-42")
    fun aStrokeDrawnWhilePinyinIsTypedIsSentAsOneTheKeysNone() {
        harness(KeyboardHarness.PINYIN)
        class Row(val why: String, val strokes: String, val drawn: () -> Unit)
        assertRows(
            listOf(
                Row("short, from any row: 竖 from the bottom row 26dp down, a short 点, a 横 26dp across", "snh") {
                    drawDp(h.key("x"), 0f, 26f)
                    drawDp(h.key("e"), 19f, 19f)
                    drawDp(h.key("a"), 26f, 0f)
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
        harness(KeyboardHarness.PINYIN, keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18))
        typed("hao")
        h.api.calls.clear()
        val faces18 = NineKeyLetters.G18.tag().split(',').map { it.uppercase() }
        draw(h.keys().first { it.label in faces18 }, across = 0f, down = 2f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('s') in it })
        close()
        // 双拼's nine keys glide no letters (T9Keyboard's other path): a stroke all the same
        harness(shuangpin, keyboards = ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS))
        typed("hk")
        h.api.calls.clear()
        draw(h.nineKey("2"), across = 0.8f, down = 1.2f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('n') in it })
    }

    // a stroke started on 九键's 1 was once no stroke; 1 is in the 3×3 drawn over. A tap on it is the separator still
    @Test
    @Uat("T9-69")
    fun theNineKeysOneStartsAStrokeAsTheLettersDoATapIsStillTheSeparator() {
        harness(KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS)
        typed("wang")
        h.api.calls.clear()
        draw(h.nineKey("1"), across = 2f, down = 0f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('h') in it })
        h.api.calls.clear()
        draw(h.nineKey("1"), across = 0.8f, down = 1.2f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('n') in it })
        EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.PINYIN, composing = true, syllables = listOf("wang"), id = 1)
        try {
            h.idle()
            h.api.calls.clear()
            h.tap(h.nineKey("1"))
            assertEquals(h.api.calls.toString(), listOf("sym=${UNICODE + Keyboard.pinyinNineKey('1')},"), downs().map { it.substringAfter('(').substringBefore(' ') })
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
        close()
        harness(shuangpin, keyboards = ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS))
        typed("hk")
        h.api.calls.clear()
        draw(h.nineKey("1"), across = 2f, down = 0f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('h') in it })
        EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.SHUANGPIN, composing = true, syllables = listOf("hk"), id = 1)
        try {
            h.idle()
            h.api.calls.clear()
            h.tap(h.nineKey("1"))
            assertEquals(h.api.calls.toString(), listOf("key='"), downs().map { it.substringAfter('(').substringBefore(',') })
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }

    @Test
    @Uat("KB-42")
    @Config(qualifiers = LANDSCAPE)
    fun aStrokeStartsInTheSplitGapTooATapOrAGlideThereDoNothing() {
        harness(KeyboardHarness.PINYIN)
        val gap = gap()
        val t = h.box(h.key("t"))
        assertTrue(gapAt(gap.exactCenterX(), gap.exactCenterY()))
        assertFalse(gapAt(t.exactCenterX(), t.exactCenterY()))
        typed("hao")
        h.api.calls.clear()
        h.drag(gap, gap.exactCenterX(), gap.exactCenterY() + 2f * t.height())
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('s') in it })
        h.api.calls.clear()
        h.drag(gap, gap.exactCenterX(), gap.exactCenterY(), steps = 1)
        assertEquals(emptyList<String>(), downs())
        close()
        harness(KeyboardHarness.PINYIN, glide = true)
        typed("hao")
        h.api.calls.clear()
        h.drag(gap, gap.exactCenterX() + 3f * t.width(), gap.exactCenterY())
        assertTrue(h.api.calls.toString(), h.api.calls.none { it.startsWith("offerGlide") } && downs().isEmpty())
    }

    @Test
    @Uat("KB-42")
    @Config(qualifiers = LANDSCAPE)
    fun aSecondFingerOnALetterWhileOneRestsInTheGapTypesIt() {
        harness(KeyboardHarness.PINYIN)
        val gap = gap()
        val k = h.box(h.key("k"))
        typed("hao")
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
        typed("hao")
        h.api.calls.clear()
        h.drag(gap, gap.exactCenterX(), gap.exactCenterY() + 2f * keyHeight)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('s') in it })
    }

    @Test
    @Uat("KB-42")
    fun noOtherLayoutHasAGap() {
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        class Board(val name: String, val board: PinyinKeyboard, val kind: Class<*>, val oneHanded: Boolean = false)
        val boards = listOf(
            Board("26 keys", PinyinKeyboard.Full, TextKeyboard::class.java),
            Board("26 keys one-handed", PinyinKeyboard.Full, TextKeyboard::class.java, oneHanded = true),
            Board("14 keys", PinyinKeyboard.Keys14, GroupedKeyboard::class.java),
            Board("18 keys", PinyinKeyboard.Keys18, GroupedKeyboard::class.java),
            Board("nine keys", PinyinKeyboard.NineKeys, T9Keyboard::class.java),
            Board("zhuyin", PinyinKeyboard.ZhuyinFull, ZhuyinKeyboard::class.java),
            Board("zhuyin nine", PinyinKeyboard.ZhuyinNine, ZhuyinKeyboard::class.java),
        )
        for (b in boards) {
            prefs.set(AppPrefs.getInstance().keyboard.pinyinKeyboard, b.board)
            if (b.oneHanded) prefs.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right) else prefs.setStyleDefault()
            harness(KeyboardHarness.PINYIN)
            val kb = board()
            assertTrue("${b.name}: ${kb.javaClass}", b.kind.isInstance(kb))
            if (b.oneHanded) assertTrue("${kb.width} of ${kb.rootView.width}", kb.width < kb.rootView.width * 0.9)
            val found = grid(kb).filter { (x, y) -> kb.glideForTest?.inGap(x, y) == true }
            assertEquals(b.name, emptyList<Pair<Float, Float>>(), found)
            close()
        }
    }

    @Test
    @Uat("KB-42")
    @Config(qualifiers = LANDSCAPE)
    fun theSweepFindsTheSplitGap() {
        harness(KeyboardHarness.PINYIN)
        val kb = board()
        assertTrue(grid(kb).any { (x, y) -> kb.glideForTest?.inGap(x, y) == true })
    }

    private fun board(): BaseKeyboard = KeyboardHarness.all(h.keyboard).filterIsInstance<BaseKeyboard>().first { h.shown(it) }

    private fun glide(): GlideInput = board().glideForTest!!

    private fun grid(kb: BaseKeyboard) = (0..40).flatMap { i -> (0..20).map { j -> kb.width * i / 40f to kb.height * j / 20f } }

    private fun gap(): Rect {
        assertEquals(h.texts().toString(), 2, h.keys().count { it.label.equals("g", ignoreCase = true) })
        val t = h.box(h.key("t"))
        val y = h.box(h.key("y"))
        assertTrue("$t $y", y.left - t.right > t.width())
        return Rect(t.right, t.top, y.left, t.bottom)
    }

    private fun gapAt(x: Float, y: Float): Boolean {
        val kb = board()
        val at = IntArray(2).also(kb::getLocationInWindow)
        return kb.glideForTest!!.inGap(x - at[0], y - at[1])
    }

    @Test
    @Uat("KB-43")
    fun withNothingTypedGlideChosenOrEnglishNoStrokeIsSent() {
        harness(KeyboardHarness.PINYIN)
        draw(h.key("w"), across = 3f, down = 0f)
        assertEquals(emptyList<String>(), strokesSent())
        close()
        harness(KeyboardHarness.PINYIN, glide = true)
        typed("hao")
        draw(h.key("w"), across = 3f, down = 0f)
        assertEquals(emptyList<String>(), strokesSent())
        assertEquals(h.api.calls.toString(), 1, h.api.calls.count { it.startsWith("offerGlide") })
        close()
        harness(KeyboardHarness.ENGLISH)
        typed("hel")
        draw(h.key("w"), across = 3f, down = 0f)
        assertEquals(emptyList<String>(), strokesSent())
        assertTrue(h.api.calls.toString(), h.api.calls.none { it.startsWith("offerGlide") })
        close()
        harness(KeyboardHarness.PINYIN)
        typed("ab", aux = "快速输入：")
        draw(h.key("w"), across = 3f, down = 0f)
        assertEquals(emptyList<String>(), strokesSent())
        close()
        harness(InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "五", "zh_CN"))
        typed("wq")
        draw(h.key("w"), across = 3f, down = 0f)
        assertEquals(emptyList<String>(), strokesSent())
        assertTrue(h.api.calls.toString(), h.api.calls.none { it.startsWith("offerGlide") })
    }

    companion object {
        // wide enough to split (ScreenShape.WIDE_DP)
        private const val LANDSCAPE = "w851dp-h393dp-land-440dpi"
    }
}
