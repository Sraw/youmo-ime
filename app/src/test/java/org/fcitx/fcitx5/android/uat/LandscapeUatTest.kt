/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPadView
import org.fcitx.fcitx5.android.input.keyboard.SplitDock
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboardMode
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UAT cases of the keyboard turned sideways (uat/16), at redroid's screen turned: 851 × 393 dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w851dp-h393dp-land-440dpi")
class LandscapeUatTest {

    private var harness: KeyboardHarness? = null
    private var settings: SettingsHarness? = null

    private val h: KeyboardHarness get() = harness ?: KeyboardHarness().also { harness = it }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        harness?.close()
        settings?.close()
        FcitxApplication.getInstance().directBootAwareContext
            .getSharedPreferences(org.fcitx.fcitx5.android.data.RecentlyUsed.PREFERENCE_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun x(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[0]

    private fun keys(label: String) = KeyboardHarness.all(h.inputView).filterIsInstance<TextKeyView>()
        .filter { h.shown(it) && it.mainText.text.toString() == label }.toList()

    private fun split() = keys("g").size == 2

    private fun union(views: Sequence<View>): Rect? = views.map(h::box).filter { !it.isEmpty }
        .reduceOrNull { a, b -> Rect(a).apply { union(b) } }

    private val arrows get() = listOf(R.string.split_dock_move_left, R.string.split_dock_move_right).map(h.service::getString)

    private fun arrow(): View? = KeyboardHarness.all(h.inputView)
        .firstOrNull { h.shown(it) && it.contentDescription?.toString() in arrows }

    private fun barButtons() = listOf(R.string.expand_toolbar, R.string.hide_keyboard, R.string.a11y_key_emoji).map(h.service::getString)

    private fun bar(): Rect = h.box(h.bar)

    private fun board(): Rect = h.box(h.board)

    private val width get() = h.keyboard.width

    private fun onRight(b: Rect) = b.left > width * 0.35 && b.right > width * 0.9 && b.width() < width * 0.5
    private fun onLeft(b: Rect) = b.left < width * 0.1 && b.right < width * 0.65 && b.width() < width * 0.5
    private fun full(b: Rect) = b.left < width * 0.1 && b.right > width * 0.9

    private fun sent() = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    @Test
    @Uat("LS-01")
    fun landscapeSplitsTheLetters() {
        h.start()
        val width = h.keyboard.width
        fun row(letters: String) = letters.map { c ->
            KeyboardHarness.all(h.inputView).filterIsInstance<TextKeyView>()
                .filter { h.shown(it) && it.mainText.text.toString() == c.toString() }.map(::x).sorted().toList()
        }
        // G and V on both halves
        assertEquals(2, row("g").single().size)
        assertEquals(2, row("v").single().size)
        val top = row("qwertyuiop").map { it.single() }
        // five a side, a gap in the middle wider than a key's own gap
        val keyWidth = h.key("q").width
        val left = top.take(5)
        val right = top.drop(5)
        assertTrue("$top", left.last() + keyWidth < width / 2 && right.first() > width / 2)
        assertTrue("$top", right.first() - left.last() > 2 * keyWidth)
        // both halves' keys equally wide
        assertEquals(keyWidth, h.key("p").width)
        // the space across the middle
        val space = KeyboardHarness.all(h.inputView).first { it.id == R.id.button_space && h.shown(it) }
        assertTrue(x(space) < width / 2 && x(space) + space.width > width / 2)
        h.capture("LandscapeUatTest/split")
    }

    @Test
    @Uat("LS-04")
    fun bothGsAndVsTypeTheSame() {
        harness = KeyboardHarness(im = KeyboardHarness.ENGLISH)
        h.start()
        val (gl, gr) = keys("g").sortedBy(::x)
        val (vl, vr) = keys("v").sortedBy(::x)
        for (k in listOf(gl, gr, vl, vr)) h.tap(k)
        val typed = sent()
        assertEquals(typed.toString(), 4, typed.size)
        assertEquals(typed[0], typed[1])
        assertEquals(typed[2], typed[3])
        assertTrue(typed.toString(), typed[0] != typed[2])
    }

    @Test
    @Uat("LS-05")
    fun swipeAndLongPressOnTheHalves() {
        h.start()
        h.swipe(h.key("o"), 0f, h.key("o").height * 1.3f)
        h.longPress(h.key("q"), "Q")
        // typed into the engine (pinyin takes them as keys); the engine's commit is its own
        val typed = sent()
        assertEquals(typed.toString(), 2, typed.size)
        assertTrue(typed.toString(), "key=9," in typed[0])
        assertTrue(typed.toString(), "key=Q," in typed[1])
    }

    @Test
    @Uat("LS-09")
    fun splitOffTakesAtOnce() {
        h.start()
        assertTrue(split())
        prefs.set(ThemeManager.prefs.splitKeyboard, SplitKeyboardMode.Off)
        h.start()
        assertFalse(h.texts().toString(), split())
        assertEquals(1, keys("g").size)
    }

    @Test
    @Uat("LS-10")
    fun thePreviewSplitsToo() {
        val st = SettingsHarness().also { settings = it }
        st.open(SettingsRoute.Theme)
        fun gs() = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextKeyView>()
            .count { it.isShown && it.mainText.text.toString() == "g" }
        assertEquals(2, gs())
        st.capture("LandscapeUatTest/preview_split")
        prefs.set(ThemeManager.prefs.splitKeyboard, SplitKeyboardMode.Off)
        st.idle()
        assertEquals(1, gs())
    }

    @Test
    @Uat("LS-13")
    fun addressFieldSplitBottomRow() {
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val keys = h.texts()
        assertTrue(keys.toString(), "/" in keys && "." in keys && ".com" in keys)
        val space = KeyboardHarness.all(h.inputView).first { it.id == R.id.button_space && h.shown(it) }
        assertTrue(x(space) < width / 2 && x(space) + space.width > width / 2)
        h.tap(h.key(".com"))
        assertEquals(".com", h.field.text.toString())
    }

    @Test
    @Uat("LS-14")
    fun numbersAtTheRightWithTheirBar() {
        h.start()
        h.tap(h.key("123"))
        val b = board()
        assertTrue("$b of $width", onRight(b))
        val bar = bar()
        assertTrue("bar $bar, board $b", kotlin.math.abs(bar.left - b.left) < 60 && bar.right >= width - 2)
        val a = arrow()!!
        assertEquals(h.service.getString(R.string.split_dock_move_left), a.contentDescription)
        assertTrue(h.box(a).right <= b.left)
        h.capture("LandscapeUatTest/numbers_right")
    }

    @Test
    @Uat("LS-15")
    fun theArrowMovesItAcrossAndItStays() {
        prefs.set(ThemeManager.prefs.splitDock, SplitDock.Right)
        h.start()
        h.tap(h.key("123"))
        h.tap(arrow()!!)
        val b = board()
        assertTrue("$b", onLeft(b))
        assertTrue("${bar()}", bar().left <= 2)
        val a = arrow()!!
        assertEquals(h.service.getString(R.string.split_dock_move_right), a.contentDescription)
        assertTrue(h.box(a).left >= b.right)
        h.tap(h.key("ABC"))
        // turned and back: a keyboard made anew
        h.close()
        harness = null
        h.start()
        h.tap(h.key("123"))
        assertTrue("${board()}", onLeft(board()))
        assertEquals(SplitDock.Left, ThemeManager.prefs.splitDock.getValue())
    }

    @Test
    @Uat("LS-16")
    fun lettersAgainFullAndSplit() {
        h.start()
        h.tap(h.key("123"))
        h.tap(h.key("ABC"))
        assertTrue(split())
        assertTrue("${bar()}", full(bar()))
        assertNull(arrow())
    }

    @Test
    @Uat("LS-17")
    fun symbolsAtTheSide() {
        h.start()
        h.tap(h.key("符"))
        val b = board()
        assertTrue("$b", onRight(b))
        h.capture("LandscapeUatTest/symbols_right")
        val sym = KeyboardHarness.all(h.inputView).filterIsInstance<TextKeyView>()
            .first { h.shown(it) && it.mainText.text.length == 1 && !it.mainText.text[0].isLetterOrDigit() }
        val ch = sym.mainText.text.toString()
        h.tap(sym)
        assertEquals(ch, h.field.text.toString())
        assertTrue(split())
    }

    @Test
    @Uat("LS-18")
    fun emojiAtTheSide() {
        h.start()
        h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
        val b = board()
        assertTrue("$b", onRight(b))
        val emoji = KeyboardHarness.all(h.inputView).filterIsInstance<TextKeyView>()
            .first { h.shown(it) && it.mainText.text.isNotEmpty() && it.mainText.text[0].code > 0x2000 && h.box(it).left >= b.left }
        val e = emoji.mainText.text.toString()
        h.tap(emoji)
        assertEquals(e, h.field.text.toString())
    }

    @Test
    @Uat("LS-19")
    fun clipboardAtTheSide() {
        h.start()
        h.copied("横屏的剪贴板条目")
        h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
        h.after(1000)
        h.relayout()
        h.tap(h.described(h.service.getString(R.string.clipboard)))
        h.waitFor("横屏的剪贴板条目 listed") {
            h.relayout()
            KeyboardHarness.all(h.inputView).any { h.shown(it) && it is TextView && it.text == "横屏的剪贴板条目" }
        }
        assertTrue("${board()}", onRight(board()))
        val entry = KeyboardHarness.all(h.inputView).first { h.shown(it) && it is TextView && it.text == "横屏的剪贴板条目" }
        h.tap(generateSequence(entry) { it.parent as? View }.first { it.hasOnClickListeners() })
        assertEquals("横屏的剪贴板条目", h.field.text.toString())
        assertTrue(split())
    }

    @Test
    @Uat("LS-20")
    fun nineKeysAtTheSide() {
        harness = KeyboardHarness(im = KeyboardHarness.T9)
        h.start()
        val b = board()
        assertTrue("$b", onRight(b))
        for (k in listOf("MNO", "GHI", "GHI", "ABC", "MNO")) h.tap(h.key(k))
        assertEquals(sent().toString(), 5, sent().size)
        // the bar over it as narrow, as far right, with candidates too
        h.api.candidates = arrayOf(CandidateWord("", "你好", ""))
        h.emit(FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(1, h.api.candidates)))
        val word = KeyboardHarness.all(h.inputView).firstOrNull { h.shown(it) && it is TextView && it.text.toString() == "你好" }
            ?: error(h.texts(h.inputView).toString())
        assertTrue("${h.box(word)}", h.box(word).left >= b.left - 60)
        h.capture("LandscapeUatTest/t9_right")
    }

    @Test
    @Uat("LS-21")
    fun handwritingAtTheSide() {
        harness = KeyboardHarness(im = InputMethodEntry(Engines.HANDWRITING, "手写", "", "", "写", "zh_CN", "androidengine", false))
        h.start()
        val pad = KeyboardHarness.find<HandwritingPadView>(h.inputView)!!
        assertTrue(h.shown(pad))
        assertTrue("${h.box(pad)}", onRight(board()))
    }

    @Test
    @Uat("LS-22")
    fun fullWidthTakesTheWholeWidth() {
        prefs.set(ThemeManager.prefs.splitDock, SplitDock.Full)
        h.start()
        h.tap(h.key("123"))
        assertTrue("${board()}", full(board()))
        assertNull(arrow())
    }

    @Test
    @Uat("LS-23")
    fun dockGreyedWhenSplitIsOff() {
        prefs.set(ThemeManager.prefs.splitKeyboard, SplitKeyboardMode.Off)
        val st = SettingsHarness().also { settings = it }
        st.open(SettingsRoute.Theme)
        st.tab(1)
        assertFalse(st.pref(R.string.split_dock).isEnabled)
    }

    @Test
    @Uat("LS-24")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun portraitPanelsFull() {
        h.start()
        h.tap(h.key("123"))
        assertTrue("${board()}", full(board()))
        assertNull(arrow())
        h.tap(h.key("ABC"))
        h.tap(h.key("符"))
        assertTrue("${board()}", full(board()))
        assertNull(arrow())
    }

    @Test
    @Uat("LS-24a")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun portraitAlwaysSplitPanelsFull() {
        prefs.set(ThemeManager.prefs.splitKeyboard, SplitKeyboardMode.Always)
        prefs.set(ThemeManager.prefs.splitDock, SplitDock.Right)
        h.start()
        assertTrue(h.texts().toString(), split())
        h.tap(h.key("123"))
        assertTrue("${board()}", full(board()))
        assertNull(arrow())
        h.tap(h.key("ABC"))
        h.tap(h.key("符"))
        assertTrue("${board()}", full(board()))
        assertNull(arrow())
    }

    @Test
    @Uat("LS-25")
    fun numberFieldDocked() {
        h.start(InputType.TYPE_CLASS_NUMBER)
        val b = board()
        assertTrue("$b", onRight(b))
        assertTrue("${bar()} $b", kotlin.math.abs(bar().left - b.left) < 60)
    }

    @Test
    @Uat("LS-27")
    fun tallestIsCappedAtSevenTenths() {
        // 很高
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 60)
        h.start()
        val screen = h.service.resources.displayMetrics.heightPixels
        val top = KeyboardHarness.all(h.inputView).filter { h.shown(it) && it.contentDescription?.toString() in barButtons() }
            .minOf { h.box(it).top }
        val bottom = h.box(h.keyboard).bottom
        assertTrue("area ${bottom - top} of $screen", bottom - top <= 0.70 * screen + 8)
        h.capture("LandscapeUatTest/tallest")
    }

    @Test
    @Uat("LS-28")
    fun candidatesAcrossTheWidth() {
        h.start()
        val words = arrayOf("你好", "拟好", "你", "呢", "泥", "尼", "妮", "倪", "逆", "腻")
        h.api.candidates = Array(words.size) { CandidateWord("", words[it], "") }
        // more wait behind ⌄, as for a real nihao: only then do the shown words share the bar's width.
        // A list that fits whole stays packed at its natural width (the user's compact decision, 2026-10-25)
        h.emit(FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(words.size + 50, h.api.candidates)))
        val shown = KeyboardHarness.all(h.inputView).filter { h.shown(it) && it is TextView && it.text.toString() in words }.toList()
        assertEquals("你好", (shown.minBy(::x) as TextView).text.toString())
        val across = union(shown.asSequence())!!
        assertTrue("$across of $width", across.left < 400 && across.right > width * 0.6)
    }

    @Test
    @Uat("LS-29")
    fun splitShuangpinHasNoSemicolonKey() {
        val scheme = EngineBridge.shuangpinScheme
        EngineBridge.shuangpinScheme = ShuangpinScheme.MICROSOFT
        prefs.later { EngineBridge.shuangpinScheme = scheme }
        harness = KeyboardHarness(im = InputMethodEntry(Engines.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false))
        h.start()
        assertTrue(split())
        assertFalse(KeyboardHarness.all(h.inputView).any { it.id == R.id.button_semicolon && h.shown(it) })
        // L ends its row where the row above ends
        assertTrue(kotlin.math.abs(h.box(h.key("l")).right - h.box(h.key("p")).right) <= 12)
        val popup = h.longPress(h.key("l"), null)
        assertTrue(popup.toString(), ";" in popup || "；" in popup)
    }
}
