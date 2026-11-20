/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyles
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 竖屏键盘样式 and 横屏键盘样式: each shape its own, the views made anew only floating or split into or out of.
 * Turned, at redroid's screen: 851 × 393 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w851dp-h393dp-land-440dpi")
class KeyboardStyleUatTest {

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness ?: KeyboardHarness().also { harness = it }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        harness?.close()
        harness = null
    }

    private fun s(id: Int) = h.service.getString(id)

    private val width get() = h.keyboard.width

    private fun split() = h.keysLabelled("g").size == 2

    private fun board(): Rect = h.box(h.board)

    private fun atASide(b: Rect) = b.width() < width * 0.6 && (b.left <= 2 || b.right >= width - 2)

    private fun has(description: Int) = KeyboardHarness.all(h.inputView).any { h.shown(it) && it.contentDescription == s(description) }

    private fun panel(text: Int) {
        if (!has(R.string.hide_toolbar)) {
            h.tap(h.described(s(R.string.expand_toolbar)))
            h.after(500)
            h.relayout()
        }
        h.tap(h.described(s(R.string.status_area)))
        val t = KeyboardHarness.all(h.inputView).firstOrNull { h.shown(it) && it is TextView && it.text.toString() == s(text) }
            ?: error("no ${s(text)} in ${h.texts(h.inputView)}")
        h.tap(generateSequence<View>(t) { it.parent as? View }.first { it.hasOnClickListeners() })
        h.idle()
        h.relayout()
    }

    private fun typing() {
        h.api.inputPanelCached = HostEvent.InputPanel(FormattedText(arrayOf("ni"), intArrayOf(0), 2), FormattedText.Empty, FormattedText.Empty)
        h.emit(h.api.inputPanelCached)
        h.api.calls.clear()
    }

    private fun ended() = h.api.calls.filter { it == "focusOutIn()" || it.startsWith("reset(") }

    private fun stored(landscape: Boolean) = TestStyles.stored(landscape)

    @Test
    @Uat("LS-39")
    fun notSetTurnedSplits() {
        prefs.setStyleDefault()
        h.start()
        assertTrue(split())
    }

    @Test
    @Uat("LS-39")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun notSetUprightIsWhole() {
        prefs.setStyleDefault()
        h.start()
        assertFalse(split())
        assertTrue("${board()}", board().width() >= width - 2)
    }

    @Test
    @Uat("LS-40")
    fun turnedFloatingUprightDockedEachItsOwn() {
        prefs.setStyle(KeyboardStyle.NORMAL, landscape = false)
        prefs.setStyle(KeyboardStyle.FLOATING, landscape = true)
        h.start()
        assertTrue(KeyboardStyles.floating(h.service))
        assertTrue(has(R.string.floating_keyboard_dock) || h.box(h.board).width() < h.service.resources.displayMetrics.widthPixels * 0.9)
        assertFalse("floating never splits", split())
    }

    @Test
    @Uat("LS-40")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun uprightDockedWhereTurnedFloats() {
        prefs.setStyle(KeyboardStyle.NORMAL, landscape = false)
        prefs.setStyle(KeyboardStyle.FLOATING, landscape = true)
        h.start()
        assertFalse(KeyboardStyles.floating(h.service))
        assertTrue("${board()}", board().width() >= h.service.resources.displayMetrics.widthPixels - 2)
    }

    @Test
    @Uat("LS-41")
    fun turnedOneHandedAtTheSideRememberedAndBackToSplit() {
        prefs.setStyle(KeyboardStyle.SPLIT, landscape = true)
        prefs.set(ThemeManager.prefs.oneHandedSide, OneHanded.Left)
        KeyboardStyles.set(h.service, landscape = true, KeyboardStyle.ONE_HANDED)
        h.start()
        assertFalse(split())
        assertTrue("${board()} of $width", atASide(board()) && board().left <= 2)
        val made = h.inputView
        h.tap(KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription?.toString() == s(R.string.one_handed_exit) })
        h.idle()
        h.relayout()
        assertNotSame(made, h.inputView)
        assertTrue(split())
        assertEquals(KeyboardStyle.SPLIT, stored(landscape = true))
    }

    @Test
    @Uat("LS-43")
    fun splitToOneHandedAndBackKeepsWhatIsTyped() {
        prefs.setStyleDefault()
        h.start()
        assertTrue(split())
        typing()
        val made = h.inputView
        panel(R.string.one_handed)
        assertNotSame("made anew", made, h.inputView)
        assertTrue("${board()} of $width", atASide(board()))
        assertEquals(KeyboardStyle.ONE_HANDED, stored(landscape = true))
        assertNull("upright untouched", stored(landscape = false))
        assertEquals("what is typed kept", emptyList<String>(), ended())
        panel(R.string.one_handed)
        assertTrue(split())
        assertEquals(KeyboardStyle.SPLIT, stored(landscape = true))
        assertEquals(emptyList<String>(), ended())
    }

    @Test
    @Uat("LS-42")
    fun floatingFromThePanelIsTheTurnedOnesAndDocksBackToSplit() {
        prefs.setStyleDefault()
        h.start()
        typing()
        panel(R.string.floating_keyboard)
        assertEquals(KeyboardStyle.FLOATING, stored(landscape = true))
        assertNull(stored(landscape = false))
        assertFalse(split())
        assertEquals("what is typed kept", emptyList<String>(), ended())
        KeyboardStyles.floatingOff(KeyboardArea.screen(h.service))
        h.idle()
        h.relayout()
        assertTrue(split())
    }

    @Test
    @Uat("LS-40")
    fun theOtherShapesStyleMakesNothingAnew() {
        prefs.setStyleDefault()
        h.start()
        val made = h.inputView
        TestStyles.set(KeyboardStyle.FLOATING, landscape = false)
        TestStyles.set(KeyboardStyle.ONE_HANDED, landscape = false)
        h.idle()
        assertSame(made, h.inputView)
        assertTrue(split())
    }

    @Test
    @Uat("TB-20")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun normalAndOneHandedSlideTheSameView() {
        prefs.setStyleDefault()
        h.start()
        typing()
        val made = h.inputView
        panel(R.string.one_handed)
        assertSame(made, h.inputView)
        assertTrue("${board()}", board().width() < width * 0.9)
        assertEquals(KeyboardStyle.ONE_HANDED, stored(landscape = false))
        assertNull(stored(landscape = true))
        assertEquals(emptyList<String>(), ended())
    }

    private fun turn(to: String) {
        RuntimeEnvironment.setQualifiers(to)
        h.service.onConfigurationChanged(Configuration(h.service.resources.configuration))
        h.idle()
        h.relayout()
    }

    // after a turn the toggles write the shape the new view was made on
    @Test
    @Uat("LS-42")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun afterATurnTheTurnedShapeIsTheOneWrittenAndMadeAnewOnce() {
        prefs.setStyleDefault()
        h.start()
        assertFalse(split())
        try {
            turn("+land")
            assertTrue("made for the turned screen: split", split())
            val made = h.inputView
            panel(R.string.floating_keyboard)
            val floated = h.inputView
            assertNotSame("made anew", made, floated)
            assertEquals(KeyboardStyle.FLOATING, stored(landscape = true))
            assertNull("upright untouched", stored(landscape = false))
            h.idle()
            h.relayout()
            assertSame("once", floated, h.inputView)
        } finally {
            RuntimeEnvironment.setQualifiers("+port")
        }
    }

    // turned but the view not made anew yet: the toggles write the shape the view was made on, not the screen's
    @Test
    @Uat("LS-42")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun turnedBeforeTheViewIsMadeAnewThePanelWritesTheBuiltShape() {
        prefs.setStyleDefault()
        h.start()
        h.tap(h.described(s(R.string.expand_toolbar)))
        h.after(500)
        h.relayout()
        h.tap(h.described(s(R.string.status_area)))
        try {
            RuntimeEnvironment.setQualifiers("+land")
            assertTrue("the screen there is now is turned", KeyboardArea.screen(h.service).landscape)
            val t = KeyboardHarness.all(h.inputView).firstOrNull { h.shown(it) && it is TextView && it.text.toString() == s(R.string.one_handed) }
                ?: error("no ${s(R.string.one_handed)} in ${h.texts(h.inputView)}")
            h.tap(generateSequence<View>(t) { it.parent as? View }.first { it.hasOnClickListeners() })
            h.idle()
            assertEquals(KeyboardStyle.ONE_HANDED, stored(landscape = false))
            assertNull("turned untouched", stored(landscape = true))
        } finally {
            RuntimeEnvironment.setQualifiers("+port")
        }
    }

    // dragged down to dock and turned while it slides: the view taken out docks the shape it was made on
    @Test
    @Uat("LS-45")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun slidingDownAsTheScreenTurnsDocksTheShapeItWasMadeOn() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        h.tap(h.described(s(R.string.floating_keyboard_dock)))
        try {
            turn("+land")
            h.after(1000)
            h.relayout()
            assertEquals("upright docked", KeyboardStyle.NORMAL, stored(landscape = false))
            assertEquals("turned floating on", KeyboardStyle.FLOATING, stored(landscape = true))
            assertTrue(KeyboardStyles.floating(h.service))
        } finally {
            RuntimeEnvironment.setQualifiers("+port")
        }
    }

    // split to one-handed makes the view anew; TalkBack's focus goes back to its button there
    @Test
    @Uat("TB-65")
    fun fromTheBarTheFocusIsOnTheNewViewsOneHandedButton() {
        prefs.set(AppPrefs.getInstance().internal.toolbarButtons, "|,one_handed,hide")
        prefs.setStyleDefault()
        h.start()
        val before = h.inputView.kawaiiBar.shownButton(ToolbarButton.OneHanded) as ToolButton
        assertFalse(before.createAccessibilityNodeInfo().isChecked)
        h.tap(before)
        h.idle()
        h.relayout()
        val bar = h.inputView.kawaiiBar
        val after = bar.shownButton(ToolbarButton.OneHanded) as ToolButton
        assertNotSame("made anew", before, after)
        assertTrue(after.createAccessibilityNodeInfo().isChecked)
        assertSame(after, bar.talkBackFocused)
        h.tap(after)
        h.idle()
        h.relayout()
        assertTrue(split())
        assertSame(h.inputView.kawaiiBar.shownButton(ToolbarButton.OneHanded), h.inputView.kawaiiBar.talkBackFocused)
    }

    @Test
    @Uat("TB-65")
    fun theBarsOneHandedWhereSplitNotWhereFloating() {
        prefs.set(AppPrefs.getInstance().internal.toolbarButtons, "floating,one_handed,|,hide")
        prefs.setStyleDefault()
        h.start()
        h.tap(h.described(s(R.string.expand_toolbar)))
        h.after(500)
        h.relayout()
        assertTrue("split: one-handed there", has(R.string.one_handed))
        prefs.setStyle(KeyboardStyle.FLOATING, landscape = true)
        h.idle()
        h.relayout()
        if (!has(R.string.hide_toolbar)) {
            h.tap(h.described(s(R.string.expand_toolbar)))
            h.after(500)
            h.relayout()
        }
        assertTrue(has(R.string.floating_keyboard))
        assertFalse("floating: none", has(R.string.one_handed))
    }
}
