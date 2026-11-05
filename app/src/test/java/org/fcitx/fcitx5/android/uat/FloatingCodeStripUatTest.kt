/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.fcitx.fcitx5.android.input.preedit.FloatingCodeStrip
import org.fcitx.fcitx5.android.input.preedit.SyllableStrip
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

/**
 * uat/14's 编码行 cases (ST-48–ST-50): the code row floating over the app, outside the keyboard's
 * top-left, from the first key till the code goes; its touches; the keyboard's height without it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class FloatingCodeStripUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness!!

    @After
    fun close() {
        harness?.close()
        EngineState.syllablesState.value = EngineState.SyllableState()
        // where a test dragged the keyboard: not the next test's (AppPrefs outlives it)
        AppPrefs.getInstance().internal.run {
            floatingKeyboardX.setValue(floatingKeyboardX.defaultValue)
            floatingKeyboardY.setValue(floatingKeyboardY.defaultValue)
        }
    }

    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)

    private fun open(
        keyboards: ImKeyboards.Chosen = ImKeyboards.Chosen(),
        im: InputMethodEntry = KeyboardHarness.PINYIN,
        theme: Theme = ThemePreset.PixelLight,
        style: CodeRowStyle = CodeRowStyle.Floating,
    ): KeyboardHarness {
        harness?.close()
        // set here, put back once at the test's end: a harness's own would flip it back and forth at
        // each open, every flip rebuilding every keyboard alive (the JVM ran out of memory)
        if (ThemeManager.prefs.codeRow.getValue() != style) prefs.set(ThemeManager.prefs.codeRow, style)
        return KeyboardHarness(theme, im, keyboards = keyboards, codeRow = style).also {
            harness = it
            it.start()
        }
    }

    private val dp get() = h.service.resources.displayMetrics.density

    private fun strip(): FloatingCodeStrip = KeyboardHarness.find<FloatingCodeStrip>(h.inputView) ?: error("no strip")

    // laid out, then placed as a frame about to be drawn places it, then laid out at its width
    private fun drawn() {
        h.relayout()
        h.inputView.viewTreeObserver.dispatchOnPreDraw()
        h.relayout()
        h.inputView.viewTreeObserver.dispatchOnPreDraw()
    }

    private fun typing(code: String, im: String = InputMethodCatalog.PINYIN, vararg syllables: String) {
        val panel = HostEvent.InputPanel(FormattedText(arrayOf(code), intArrayOf(0), code.length), FormattedText.Empty, FormattedText.Empty)
        h.api.inputPanelCached = panel
        h.emit(panel)
        if (syllables.isNotEmpty()) EngineState.syllablesState.value = EngineState.SyllableState(im, composing = true, syllables = syllables.toList(), id = 1)
        h.idle()
        drawn()
    }

    private fun cleared() {
        val panel = HostEvent.InputPanel(FormattedText.Empty, FormattedText.Empty, FormattedText.Empty)
        h.api.inputPanelCached = panel
        h.emit(panel)
        drawn()
    }

    private fun region(): Rect? = Rect().takeIf { h.inputView.codeStripRegion(it) }

    private fun shownStrip(): Boolean = strip().visibility == View.VISIBLE

    private fun inKeyboard(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === h.keyboard) return true
            p = p.parent
        }
        return false
    }

    @Test
    @Uat("ST-48")
    fun theStripComesWithTheFirstKeyAndGoesWithTheCode() {
        open()
        drawn()
        val code = h.inputView.findViewById<View>(R.id.code_row_text)
        assertFalse("the row is not in the keyboard", inKeyboard(code))
        assertFalse(shownStrip())
        assertEquals(null, region())
        typing("ni hao")
        assertTrue(shownStrip())
        val box = h.box(strip())
        val keyboard = h.box(h.keyboard)
        // outside the keyboard's top-left, as wide as its text, 24 dp high
        assertEquals("$box over $keyboard", keyboard.top, box.bottom)
        assertEquals(keyboard.left, box.left)
        assertEquals(CodeRowHeight.FLOAT_DP.toFloat(), box.height() / dp, 1f)
        assertTrue("$box", box.width() < keyboard.width() / 2)
        assertEquals(box, region())
        cleared()
        assertFalse(shownStrip())
        assertEquals(null, region())
    }

    @Test
    @Uat("ST-48")
    fun aTapOnTheStripPutsTheCursorInTheCode() {
        open()
        typing("nihao")
        h.api.calls.clear()
        val text = h.inputView.findViewById<View>(R.id.code_row_text)
        // near the start of the code, through the input view as a finger is
        h.touch(text, KeyboardHarness.Touch(4 * dp, text.height / 2f))
        h.idle()
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("moveCursor(") && it != "moveCursor(5)" })
    }

    @Test
    @Uat("ST-48")
    fun dockedTheStripTakesTouchesAndTheAppTheRest() {
        open()
        typing("ni")
        val insets = InputMethodService.Insets()
        h.service.onComputeInsets(insets)
        val box = h.box(strip())
        val keyboard = h.box(h.keyboard)
        // the app is laid out to the keyboard's top: the strip comes and goes over it
        assertEquals(keyboard.top, insets.contentTopInsets)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, insets.touchableInsets)
        assertTrue(insets.touchableRegion.contains(box.centerX(), box.centerY()))
        assertTrue(insets.touchableRegion.contains(keyboard.centerX(), keyboard.centerY()))
        assertFalse("over the strip, the app's", insets.touchableRegion.contains(box.centerX(), box.top - (20 * dp).toInt()))
        assertFalse("beside the strip, the app's", insets.touchableRegion.contains(box.right + (20 * dp).toInt(), box.centerY()))
        cleared()
        h.service.onComputeInsets(insets)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE, insets.touchableInsets)
    }

    @Test
    @Uat("ST-49")
    fun theKeyboardIsAsHighOnEveryKeyboardAndLowerThanEmbedded() {
        fun heights(style: CodeRowStyle, keyboards: ImKeyboards.Chosen): Pair<Int, Int> {
            open(keyboards, style = style)
            drawn()
            return h.box(h.keyboard).height() to h.box(h.board).height()
        }
        val full = heights(CodeRowStyle.Floating, ImKeyboards.Chosen())
        val fourteen = heights(CodeRowStyle.Floating, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        val embedded = heights(CodeRowStyle.Embedded, ImKeyboards.Chosen())
        val embedded14 = heights(CodeRowStyle.Embedded, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        assertEquals("the same height on the 14 keys", full.first, fourteen.first)
        // the keys keep their height; the keyboard loses the embedded row's 18 dp (32 on the 14 keys)
        assertEquals(full.second, embedded.second)
        assertEquals(CodeRowHeight.THIN_DP.toFloat(), (embedded.first - full.first) / dp, 1f)
        assertEquals(CodeRowHeight.STRIP_DP.toFloat(), (embedded14.first - fourteen.first) / dp, 1f)
    }

    private fun syllables(): SyllableStrip = h.inputView.findViewById(R.id.code_row_syllables)

    private fun syllableItems() = (syllables().getChildAt(0) as ViewGroup).children.filterIsInstance<TextView>().filter { it.isVisible }.toList()

    @Test
    @Uat("ST-49")
    fun theSyllablesAreInTheStripBesideTheCode() {
        open(ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        typing("bn ui", InputMethodCatalog.PINYIN, "ni", "bu", "mi")
        val box = h.box(strip())
        assertEquals(CodeRowHeight.STRIP_DP.toFloat(), box.height() / dp, 1f)
        assertTrue(syllables().isShown)
        assertEquals(listOf("ni", "bu", "mi"), syllableItems().map { it.text.toString() })
        val s = h.box(syllables())
        assertTrue("$s in $box", s.left >= h.box(h.inputView.findViewById(R.id.code_row_text)).right - 1 && s.right <= box.right)
        h.api.calls.clear()
        h.tap(syllableItems().first())
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
        // pinyin's nine keys: the column has them, the strip only the code
        open(KeyboardHarness.NINE_KEYS)
        typing("64", InputMethodCatalog.PINYIN, "ni", "mi")
        assertFalse(syllables().isShown)
        assertEquals(CodeRowHeight.FLOAT_DP.toFloat(), h.box(strip()).height() / dp, 1f)
    }

    @Test
    @Uat("ST-50")
    fun oneHandedTheStripIsAtTheBoardsLeft() {
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        open()
        typing("ni hao")
        val bar = h.box(h.bar)
        assertTrue("at a side: $bar", bar.left > 0)
        assertEquals(bar.left + h.bar.paddingLeft, h.box(strip()).left)
        assertEquals(h.box(h.keyboard).top, h.box(strip()).bottom)
        h.capture("FloatingCodeStripUatTest/one_handed", h.inputView)
    }

    @Test
    @Uat("ST-50")
    fun floatingTheStripIsOutsideTheKeyboardAndUnderItAtTheTop() {
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        open()
        typing("ni hao")
        val keyboard = h.box(h.keyboard)
        assertTrue("floating: $keyboard", keyboard.width() < h.inputView.width * 0.9)
        assertEquals(keyboard.left, h.box(strip()).left)
        assertEquals(keyboard.top, h.box(strip()).bottom)
        val insets = InputMethodService.Insets()
        h.service.onComputeInsets(insets)
        val box = h.box(strip())
        assertTrue(insets.touchableRegion.contains(box.centerX(), box.centerY()))
        assertTrue(insets.touchableRegion.contains(keyboard.centerX(), keyboard.centerY()))
        h.capture("FloatingCodeStripUatTest/floating", h.inputView)
        // dragged to the top: no room above, under it
        cleared()
        h.drag(KeyboardHarness.find<FloatingHandle>(h.inputView)!!, 0f, 0f)
        h.after(1000)
        typing("ni hao")
        val top = h.box(h.keyboard)
        assertEquals(top.bottom, h.box(strip()).top)
        assertEquals(top.left, h.box(strip()).left)
        h.capture("FloatingCodeStripUatTest/floating_top", h.inputView)
    }

    // light and dark, the full keys, 14, nine, 双拼's 18: for the user to see
    @Test
    @Uat("ST-48")
    fun theStripOnEachKeyboardLightAndDark() {
        for ((theme, name) in listOf(ThemePreset.PixelLight to "light", ThemePreset.PixelDark to "dark")) {
            open(theme = theme)
            typing("jin tian qing")
            h.capture("FloatingCodeStripUatTest/full_$name", h.inputView)
            open(ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14), theme = theme)
            typing("bn ui", InputMethodCatalog.PINYIN, "ni", "bu", "mi", "o")
            h.capture("FloatingCodeStripUatTest/keys14_$name", h.inputView)
            open(KeyboardHarness.NINE_KEYS, theme = theme)
            typing("ni hao")
            h.capture("FloatingCodeStripUatTest/nine_$name", h.inputView)
            open(ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS18), im = shuangpin, theme = theme)
            typing("ni hk", InputMethodCatalog.SHUANGPIN, "ni", "nu")
            h.capture("FloatingCodeStripUatTest/shuangpin18_$name", h.inputView)
        }
    }
    // the Transparent themes' bar is 15% / 30% black over the keyboard: the strip the colour that looks
    @Test
    @Uat("ST-48")
    fun onTheTransparentThemesTheStripIsTheBarAsItLooks() {
        for ((theme, name) in listOf(ThemePreset.TransparentLight to "light", ThemePreset.TransparentDark to "dark")) {
            open(theme = theme)
            typing("jin tian qing")
            val colour = (strip().background as GradientDrawable).color!!.defaultColor
            assertEquals(FloatingCodeStrip.ALPHA, Color.alpha(colour))
            val text = h.inputView.findViewById<HorizontalScrollView>(R.id.code_row_text)
            val ink = KeyboardHarness.all(text).filterIsInstance<TextView>().first().currentTextColor
            // the code readable on it: far apart in lightness
            val gap = kotlin.math.abs(Color.luminance(colour or 0xff000000.toInt()) - Color.luminance(ink))
            assertTrue("$name: strip ${Integer.toHexString(colour)} text ${Integer.toHexString(ink)}", gap > 0.4f)
            h.capture("FloatingCodeStripUatTest/transparent_$name", h.inputView)
        }
    }

    @Test
    @Uat("ST-48")
    fun aLongCodeScrollsInTheStripToTheCursor() {
        open()
        typing("zhuang bu hao kuo ni hao a ni hao a ni hao a ni hao a ni hao a ni hao")
        val box = h.box(strip())
        assertEquals("as wide as the keyboard at most", h.box(h.keyboard).width(), box.width())
        val codeRow = h.inputView.findViewById<HorizontalScrollView>(R.id.code_row_text)
        val content = codeRow.getChildAt(0)
        assertTrue("${content.width} in ${codeRow.width}", content.width > codeRow.width)
        assertTrue("scrolled ${codeRow.scrollX}", codeRow.scrollX + codeRow.width >= content.width - 32 * dp)
    }

    @Test
    @Uat("ST-48")
    fun theSwitchFlippedWithTheKeyboardUp() {
        open()
        typing("ni")
        assertTrue(shownStrip())
        prefs.set(ThemeManager.prefs.codeRow, CodeRowStyle.Embedded)
        h.idle()
        h.relayout()
        assertNull("no strip embedded", KeyboardHarness.find<FloatingCodeStrip>(h.inputView))
        assertTrue("the row in the keyboard", inKeyboard(h.inputView.findViewById(R.id.code_row_text)))
        ThemeManager.prefs.codeRow.setValue(CodeRowStyle.Floating)
        h.idle()
        typing("ni")
        assertTrue(shownStrip())
        assertFalse(inKeyboard(h.inputView.findViewById(R.id.code_row_text)))
    }

    // a notice kept as keys are typed (what the engine says as a key fails): off the strip that comes under it
    @Test
    @Uat("ST-48")
    fun aSteadyNoticeMakesWayForTheStrip() {
        open()
        drawn()
        h.service.say("词库没装上", steady = true)
        h.advance(1000)
        val before = h.noticeBox()
        typing("zhuang bu hao kuo ni hao a ni hao a ni hao")
        h.advance(1000)
        val strip = region()!!
        val chip = h.noticeBox()
        assertTrue("$chip off $strip (was $before)", !Rect.intersects(chip, strip))
        assertTrue("above it: $chip", chip.bottom <= strip.top)
    }

    @Test
    @Uat("ST-50")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun inLandscapeTheStripIsOverTheKeyboard() {
        open()
        typing("jin tian qing")
        val keyboard = h.box(h.keyboard)
        assertEquals(keyboard.top, h.box(strip()).bottom)
        assertEquals(keyboard.left, h.box(strip()).left)
        h.capture("FloatingCodeStripUatTest/landscape", h.inputView)
    }
}
