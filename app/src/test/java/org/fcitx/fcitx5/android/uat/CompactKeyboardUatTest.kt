/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.RectF
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.scene.rules.KeyTextScale
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield.MAX_CLASS_SHRINK as LETTER_YIELD
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 键盘高度 紧凑: the keys' text 0.9 of the size, every mark drawn, the keys made anew only as the level goes into or out of it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CompactKeyboardUatTest {

    private var made: KeyboardHarness? = null
    private val h get() = made ?: fresh()
    private var settings: SettingsHarness? = null

    private fun fresh(): KeyboardHarness {
        made?.close()
        return KeyboardHarness().also { made = it }
    }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        made?.close()
        settings?.close()
    }

    private val keyboard get() = AppPrefs.getInstance().keyboard

    private fun level(portrait: Int, landscape: Int) {
        prefs.set(keyboard.keyboardHeightPercent, portrait)
        prefs.set(keyboard.keyboardHeightPercentLandscape, landscape)
    }

    private fun compact() = level(20, 34)

    private fun ShownKey.size() = textSize(label)!!

    // right of the letter, above its middle
    private fun ShownKey.inItsCorner(): Boolean {
        val symbol = alt?.let(::drawn) ?: return false
        val letter = drawn(label)!!
        return symbol.left >= letter.right && symbol.top < letter.centerY()
    }

    private fun backspaceIcons(): Int = h.keyNamed("backspace").drawnIcons

    private fun ShownKey.marks() = (this as SceneShownKey).markPlaces()

    @Test
    @Uat("ST-51")
    fun compactTextIsSmallerAndTheSymbolInItsCornerEvenAtBottom() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.Bottom)
        h.start()
        val size = h.key("q").size()
        val height = h.key("q").height
        assertFalse("standard: under the letter", h.key("w").inItsCorner())
        fresh()
        compact()
        h.start()
        assertEquals(size * 0.9f, h.key("q").size(), 0.5f)
        assertTrue(h.key("q").height < height * 0.8)
        val w = h.key("w")
        assertEquals("2", w.alt)
        assertTrue("底部 too: in its corner", w.inItsCorner())
        h.swipe(w, 0f, -w.height * 1.3f)
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") && "key=2," in it })
    }

    // too low for the bottom edge's middle: the first corner the other way's corner does not take, none left out
    @Test
    @Uat("ST-51")
    fun aBottomShortcutInTheFirstCornerFree() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeDownPosition, KeyMarkPosition.Bottom)
        compact()
        h.start()
        for (k in listOf("a", "z", "x", "c", "v")) {
            assertEquals(k, listOf("BR"), h.key(k).marks().shortcuts)
        }
        fresh()
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.TopLeft)
        h.start()
        assertEquals(listOf("TR"), h.key("a").marks().shortcuts)
    }

    @Test
    @Uat("ST-51")
    fun theShortcutIsDrawnTopLeftBesideTheSymbol() {
        prefs.setShortcuts(KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好")))
        h.start()
        assertTrue("standard: both", "你好" in h.texts())
        val icons = backspaceIcons()
        fresh()
        compact()
        h.start()
        assertTrue("紧凑: both too", "你好" in h.texts())
        val q = h.key("q")
        assertEquals("1", q.alt)
        assertTrue(q.inItsCorner())
        val text = q.drawn("你好")!!
        val symbol = q.drawn("1")!!
        assertTrue("$text left of $symbol", text.right <= symbol.left && text.centerY() < q.drawn("q")!!.centerY())
        // the backspace's 清除 (up) and 恢复 (down) both
        assertEquals(icons, backspaceIcons())
        h.swipe(q, 0f, q.height * 1.3f)
        h.idle()
        assertEquals("你好", h.field.text.toString())
    }

    @Test
    @Uat("ST-51")
    fun theKeysAreMadeAnewOnlyIntoOrOutOfCompact() {
        h.start()
        val standard = h.keyboard
        level(26, 42)
        h.idle()
        assertSame("矮: resized only", standard, h.keyboard)
        compact()
        h.idle()
        val small = h.keyboard
        assertNotSame(standard, small)
        assertEquals("2", h.key("w").alt)
        level(38, 60)
        h.idle()
        assertNotSame("out of it", small, h.keyboard)
    }

    @Test
    @Uat("ST-51")
    fun floatingTakesTheSmallerOfItsScaleAndCompacts() {
        h.start()
        val size = h.key("q").size()
        fresh()
        prefs.setStyle(KeyboardStyle.FLOATING)
        compact()
        h.start()
        val screen = h.service.resources.configuration.screenWidthDp
        val floating = FloatingKeyboard.textScale(FloatingKeyboard.widthDp(screen), screen)
        // the smaller, not the product; the letters' class then a little smaller for the marks to fit
        val nominal = size * minOf(KeyTextScale.COMPACT.toFloat(), floating)
        val lower = lettersStayClearOfTheirSymbols()
        assertTrue("${lower.first().size()} of $nominal", lower.first().size() in nominal * (1 - LETTER_YIELD.toFloat()) - 0.5f..nominal + 0.5f)
        h.tap(h.describedKey("Shift"))
        h.idle()
        assertEquals("Q", h.key("Q").label)
        val upper = lettersStayClearOfTheirSymbols(dropped = true)
        assertTrue("${upper.first().size()} of $nominal", upper.first().size() in nominal * (1 - LETTER_YIELD.toFloat()) - 0.5f..nominal + 0.5f)
    }

    @Test
    @Uat("ST-51")
    fun oneHandedLettersStayInTheirRows() {
        compact()
        h.start()
        val letter = lettersStayClearOfTheirSymbols().first().size()
        val symbol = h.key("a").let { it.textSize(it.alt!!)!! }
        fresh()
        prefs.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right)
        h.start()
        val lower = lettersStayClearOfTheirSymbols()
        assertTrue(lower.joinToString { "${it.label} ${it.size()} ${it.textSize(it.alt!!)}" }, lower.all { it.size() == letter && it.textSize(it.alt!!) == symbol })
        h.tap(h.describedKey("Shift"))
        h.idle()
        lettersStayClearOfTheirSymbols(dropped = true)
        fresh()
        level(38, 60)
        prefs.setStyleDefault()
        h.start()
        val standard = lettersStayClearOfTheirSymbols().first().size()
        fresh()
        prefs.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right)
        h.start()
        assertEquals(standard, lettersStayClearOfTheirSymbols().first().size(), 0.01f)
    }

    // measured from the glyphs drawn, not guessed from its size
    private fun ink(k: ShownKey, text: String): RectF {
        val texts = (k as SceneShownKey).node.ops(false).filterIsInstance<Op.Text>()
        val t = texts.firstOrNull { it.text == text } ?: error("$text not drawn on $k: ${texts.map { it.text }}")
        val r = android.graphics.Rect()
        android.graphics.Paint().apply { textSize = t.size.toFloat(); textScaleX = t.scaleX.toFloat() }.getTextBounds(text, 0, text.length, r)
        return RectF(r).apply { offset(t.x.toFloat(), t.baseline.toFloat()) }
    }

    // each letter and its symbol drawn apart, the letters one size, a row's on one baseline; [dropped]: a capital's
    // symbol may be left out where nothing else makes room
    private fun lettersStayClearOfTheirSymbols(dropped: Boolean = false): List<ShownKey> {
        val letters = h.keys().filter { it.label.length == 1 && it.label[0].isLetter() && it.alt != null }
        assertTrue(letters.size.toString(), letters.size >= 26)
        // left out only where it does not fit: a few wide capitals, never the board's all
        val left = letters.filter { it.alt !in it.drawnTexts }.map { it.label }
        assertTrue("left out: $left", if (dropped) left.size <= 3 else left.isEmpty())
        for (k in letters) {
            val letter = ink(k, k.label)
            assertEquals(k.label, letters[0].size(), k.size(), 0.001f)
            if (dropped && k.alt !in k.drawnTexts) continue
            val symbol = ink(k, k.alt!!)
            assertFalse("${k.label}: $letter, ${k.alt}: $symbol", RectF.intersects(letter, symbol))
        }
        for ((_, row) in letters.groupBy { it.bounds.top }) {
            assertEquals(row.joinToString { it.label }, 1, row.map { it.drawn(it.label)!!.bottom }.distinct().size)
        }
        return letters
    }

    @Test
    @Uat("ST-51")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun turnedItIsCompactToo() {
        h.start()
        val size = h.key("q").size()
        val height = h.key("q").height
        fresh()
        compact()
        h.start()
        assertEquals(size * 0.9f, h.key("q").size(), 0.5f)
        assertTrue("${h.key("q").height} of $height", h.key("q").height < height * 0.8)
        assertTrue(h.key("w").inItsCorner())
    }

    @Test
    @Uat("ST-51")
    fun thePreviewIsCompactAsTheLevelIsPicked() {
        val st = SettingsHarness().also { settings = it }
        st.open(SettingsRoute.Theme)
        fun q() = KeyboardHarness.keysOf(st.activity.window.decorView) { it.isShown }.first { it.label == "q" }
        val size = q().size()
        compact()
        st.idle()
        assertEquals(size * 0.9f, q().size(), 0.5f)
        level(30, 49)
        st.idle()
        assertEquals(size, q().size(), 0.01f)
    }
}
