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

    private val keyDrawing = AppPrefs.getInstance().keyboard.keyDrawing
    private var open: KeyboardHarness? = null
    private val h get() = open!!

    @After
    fun close() {
        open?.close()
    }

    // 笔画筛选 on, or 滑行输入 instead where [glide]
    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)

    private fun harness(im: InputMethodEntry, glide: Boolean = false, keyboards: ImKeyboards.Chosen? = null): KeyboardHarness {
        prefs.set(keyDrawing, if (glide) KeyDrawing.Glide else KeyDrawing.StrokeFilter)
        return KeyboardHarness(TestThemes.PixelLight, im, keyboards = keyboards).also { open = it }.start()
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

    // a finger down in [k]'s middle, moved in steps of 16ms to [dx], [dy] dp from it, then lifted
    private fun drawDp(k: ShownKey, dx: Float, dy: Float, steps: Int = 6, ms: Long = 16) {
        val path = (0..steps).map { i -> h.at(k, dx * i / steps, dy * i / steps, if (i == 0) 0 else ms) }
        h.touch(k, *path.toTypedArray())
    }

    // what a plain tap on [k] sends, while pinyin is typed
    private fun tapped(k: () -> ShownKey): List<String> {
        typed("hao")
        h.api.calls.clear()
        h.tap(k())
        return downs().also { assertTrue(it.toString(), it.isNotEmpty() && strokesSent().isEmpty()) }
    }

    // 笔画筛选灵敏度: a stroke started (12dp) but lifted never its distance from its down is the key's tap,
    // the same as a tap there sends; past it a stroke. Its trail is drawn only past it
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
        // 30dp and back to 5dp: a stroke, by the farthest it went
        typed("hao")
        h.api.calls.clear()
        val k = h.key("q")
        h.touch(k, h.at(k), h.at(k, 15f, 0f, 16), h.at(k, 30f, 0f, 16), h.at(k, 5f, 0f, 16))
        assertEquals(downs().toString(), 1, strokesSent().size)
        assertEquals(downs().toString(), 1, downs().size)
        // the trail: none short of it, drawn past it
        typed("hao")
        val f = h.finger(k, h.at(k))
        h.after(16)
        f.move(h.at(k, 15f, 0f))
        assertFalse(board().glideForTest!!.trailShown)
        h.after(16)
        f.move(h.at(k, 25f, 0f))
        assertTrue(board().glideForTest!!.trailShown)
        f.up(h.at(k, 25f, 0f))
        // the nine keys: the digit's group, as its tap
        close()
        harness(KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS)
        val two = tapped { h.nineKey("2") }
        typed("hao")
        h.api.calls.clear()
        drawDp(h.nineKey("2"), 16f, 8f)
        assertEquals(two, downs())
        close()
        // the 14 keys, whose tap is sent with where across the key it landed: the tap's place is its down's
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

    // the tap a short stroke is (GlideTouch.Route.TAP_KEY) goes to the engine with where the finger came down,
    // as a plain tap's does: a key tapped off-centre read as the one next to it (061-neighbour-correction)
    @Test
    fun aShortStrokesTapCarriesWhereTheFingerCameDown() {
        harness(KeyboardHarness.PINYIN)
        typed("hao")
        h.api.touches.clear()
        val s = h.key("s")
        // down 10dp left of the middle, toward a; a stroke started (12dp) but lifted 15dp off, short of the lift distance (24dp)
        h.touch(s, h.at(s, -10f, 0f), h.at(s, -4f, 0f, 16), h.at(s, 2f, 0f, 16), h.at(s, 5f, 0f, 16))
        assertTrue(strokesSent().toString(), strokesSent().isEmpty())
        val (key, touch) = h.api.touches.single()
        assertTrue(key, "s" in key)
        assertEquals(-10f, touch.dx * touch.widthDp, 1f)
        assertTrue(touch.near.toString(), touch.near('a') != null)
    }

    // two thumbs overlapping: q drifting 14dp down as a lands, q lifted, then a: both typed, q first, no stroke
    // (0.6.0 started a stroke at about 19dp down, and typed both)
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
        // a lifted first, then q: the same
        typed("hao")
        h.api.calls.clear()
        h.drag(qb, qb.exactCenterX(), qb.exactCenterY() + 14f * dp, steps = 4, lift = false)
        h.secondFingerThenLift(ab.exactCenterX(), ab.exactCenterY(), firstLifts = false)
        assertEquals(q + a, downs())
        assertEquals(emptyList<String>(), strokesSent())
    }

    // the debug trace (YoumoStrokeTap) has a line for a stroke whose other finger lifts last, swallowed: that
    // touch's line, not lost
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

    // a finger down near a key's edge sliding out past it (touch slop and all), short of the lift: the key it came down on
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

    // the sensitivity read at each touch: a 22dp 横 a stroke on 高 (20dp), the key's tap on 低 (28dp); the nine keys alike
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

    // a slow stroke: started (12dp) before the long press, a stroke read and no long press, at each long press delay
    @Test
    @Uat("KB-103")
    fun aSlowStrokeIsReadBeforeAnyLongPress() {
        for (delay in listOf(200, 300, 450)) {
            prefs.set(AppPrefs.getInstance().keyboard.longPressDelay, delay)
            harness(KeyboardHarness.PINYIN)
            typed("hao")
            h.api.calls.clear()
            // 40dp down in 480ms, about 13mm a second
            drawDp(h.key("e"), 0f, 40f, steps = 12, ms = 40)
            assertEquals("$delay ${downs()}", listOf(true), downs().map { stroke('s') in it })
            close()
        }
    }

    // a stroke drawn while pinyin is typed is sent as one, the keys under it none: from any row, short or long
    @Test
    @Uat("KB-42")
    fun aStrokeDrawnWhilePinyinIsTypedIsSentAsOneTheKeysNone() {
        harness(KeyboardHarness.PINYIN)
        class Row(val why: String, val strokes: String, val drawn: () -> Unit)
        assertRows(
            listOf(
                // past the lift distance (中, 24dp): short of it a tap (KB-101)
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

    // the bug: a stroke started on 九键's 1 (top left: a 横 along the top row) was no stroke, the letters' keys alone
    // taken as where one starts; 1 is in the 3×3 drawn over as the toolbar's 手写 writes there. A tap on it is the separator still
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
        // the engine's word that nine keys are typed: 1's tap its code (PINYIN_NINE's 1), the engine's separator
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
        // 双拼's nine keys the same
        harness(shuangpin, keyboards = ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS))
        typed("hk")
        h.api.calls.clear()
        draw(h.nineKey("1"), across = 2f, down = 0f)
        assertEquals(downs().toString(), listOf(true), downs().map { stroke('h') in it })
        // and a tap on its 1 while typing the separator ' still (SP-22), no stroke
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
            // one-handed: the keyboard narrower than the screen, its letters as one block at a side
            if (b.oneHanded) assertTrue("${kb.width} of ${kb.rootView.width}", kb.width < kb.rootView.width * 0.9)
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
        // wide enough to split (ScreenShape.WIDE_DP)
        private const val LANDSCAPE = "w851dp-h393dp-land-440dpi"
    }
}
