/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.ButtonBarLayout
import com.google.android.material.card.MaterialCardView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.ui.cards.CardColumns
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.cards.AccordionLayout
import org.fcitx.fcitx5.android.ui.main.settings.cards.CheckStrip
import org.fcitx.fcitx5.android.ui.main.settings.cards.KeyboardGlyphView
import org.fcitx.fcitx5.android.ui.rare.RareLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import splitties.dimensions.dp

/**
 * The three card pages laid out (dev/design/061-settings-cards.md §8.1): on the phones and font sizes of §2.3's
 * table, in Chinese and English, upright and on the side, what each must show without a scroll is in the
 * window, no name or count cut, the badge clear of the picture and the name. Robolectric has no status or
 * navigation bar: h568 is a 360×640 phone's window with both taken off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // text measured as drawn
@Config(application = FcitxApplication::class)
class SettingsCardsLayoutTest : RarePageUat() {

    @get:Rule
    val prefs = PrefsRule()

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-port-xhdpi")
    fun smallPhone() = allFit()

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-port-xhdpi")
    fun smallPhoneEnglish() = allFit(stacked = true)

    @Test
    @Uat("WD-62", "ST-151", "ST-153")
    @Config(qualifiers = "zh-rCN-w360dp-h568dp-port-xhdpi")
    fun smallPhonesWindow() = allFit()

    @Test
    @Config(qualifiers = "en-w360dp-h568dp-port-xhdpi")
    fun smallPhonesWindowEnglish() = allFit(stacked = true)

    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi", fontScale = 1.3f)
    fun phoneLargeFont() = allFit()

    @Test
    @Config(qualifiers = "en-w393dp-h851dp-port-440dpi", fontScale = 1.3f)
    fun phoneLargeFontEnglish() = allFit(stacked = true)

    // 生僻字屏蔽's cards scroll here (§3.1), the first in view
    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h568dp-port-xhdpi", fontScale = 1.3f)
    fun smallPhonesWindowLargeFont() = allFit(rareInView = 1)

    @Test
    @Config(qualifiers = "en-w360dp-h568dp-port-xhdpi", fontScale = 1.3f)
    fun smallPhonesWindowLargeFontEnglish() = allFit(rareInView = 1, stacked = true)

    @Test
    @Uat("LS-53")
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-440dpi")
    fun onItsSide() = allFit()

    @Test
    @Config(qualifiers = "en-w851dp-h393dp-land-440dpi")
    fun onItsSideEnglish() = allFit()

    // [stacked]: the editor's three buttons (restore, cancel, OK) one above another, as English's are on a phone upright
    private fun allFit(rareInView: Int = 4, stacked: Boolean = false) {
        rarePage(rareInView)
        keysPage()
        multiSelect()
        editor(stacked)
    }

    private val decor get() = activity.window.decorView

    private val land get() = activity.resources.configuration.screenWidthDp > activity.resources.configuration.screenHeightDp

    // the whole of [v] on the screen as it is, nothing scrolled: no parent clips any of it
    private fun assertInView(what: String, v: View) {
        val seen = Rect()
        assertTrue("$what shown", v.isShown && v.getGlobalVisibleRect(seen))
        assertEquals("$what's height in view", v.height, seen.height())
        assertEquals("$what's width in view", v.width, seen.width())
    }

    private fun rect(v: View) = IntArray(2).also(v::getLocationInWindow).let { (x, y) -> Rect(x, y, x + v.width, y + v.height) }

    // no line cut short, at most [lines] of them, all inside the view
    private fun assertWhole(v: TextView, lines: Int) {
        val text = v.text.toString()
        val layout = v.layout ?: error("$text not laid out")
        assertTrue("$text: ${layout.lineCount} lines", layout.lineCount <= lines)
        assertTrue("$text cut short", (0 until layout.lineCount).none { layout.getEllipsisCount(it) > 0 })
        assertTrue("$text cut off", layout.height <= v.height - v.paddingTop - v.paddingBottom)
        assertTrue("$text too wide", (0 until layout.lineCount).all { layout.getLineWidth(it) <= v.width - v.paddingLeft - v.paddingRight + 0.5f })
    }

    // a strip of a card (§2.7): 48 dp at least, its count whole and clear of the box's picture, the box no target
    private fun assertStrip(what: String, strip: CheckStrip) {
        assertTrue("$what's strip ${strip.height} px", strip.height >= strip.dp(CheckStrip.MIN_HEIGHT_DP))
        assertWhole(strip.count, 2)
        val box = rect(strip.box).apply { inset(strip.dp(CheckStrip.BOX_INSET_DP), strip.dp(CheckStrip.BOX_INSET_DP)) }
        val count = strip.count.let { c -> rect(c).apply { right = left + c.paddingLeft + (0 until c.layout.lineCount).maxOf { c.layout.getLineRight(it) }.toInt() } }
        assertFalse("$what: the count over the box", Rect.intersects(box, count))
        assertFalse(strip.box.isClickable)
        assertFalse(strip.box.isFocusable)
    }

    // 生僻字屏蔽: four cards, the first [inView] of them, body and strip, in view; names one line, the about two;
    // samples only where the window is 600 dp high
    private fun rarePage(inView: Int) {
        openPage()
        val samples = CardColumns.samples(activity.resources.configuration.screenHeightDp.toFloat())
        val cards = cards()
        assertEquals(4, cards.size)
        if (land) assertEquals("one row on the side", 1, cards.map { rect(it).top }.distinct().size)
        RareLayout.ORDER.zip(cards).forEachIndexed { i, (level, card) ->
            val texts = KeyboardHarness.all(card).filterIsInstance<TextView>().toList()
            val name = texts.first { it.text.toString() == s(RareCharsFragment.groupName(level)) }
            if (i < inView) assertInView(name.text.toString(), card)
            assertWhole(name, 1)
            assertWhole(texts.first { it.text.toString() == s(RareCharsFragment.groupAbout(level)) }, 2)
            val strip = KeyboardHarness.all(card).filterIsInstance<CheckStrip>().single()
            if (i < inView) assertInView("${name.text}'s strip", strip)
            assertStrip(name.text.toString(), strip)
            val drawn = texts.filter { it.isShown && it !== name && it !== strip.count && it.text.length > 1 && it.text.toString() != s(RareCharsFragment.groupAbout(level)) }
            assertEquals("${name.text}'s samples", samples, drawn.isNotEmpty())
        }
    }

    private fun board(form: String): View = KeyboardHarness.all(decor).first { it.tag == "view:keys/board/$form" }

    private val forms = listOf("full", "t9", "g14", "g18", "zy9", "zyfull", "num", "fn")

    // 按键字符和快捷键: eight cards all in view, a row as high as each other, names whole in two lines, the badge
    // (here on 全键盘, 九键 and 14 键) clear of the picture and the name
    private fun keysPage() {
        prefs.set(
            AppPrefs.getInstance().internal.keySwipes,
            SwipeSlots.Default.with("full/q", true, SwipeSlot.Empty).with("t9/2", false, SwipeSlot.Empty).with("g14/qw", true, SwipeSlot.Empty).serialize()
        )
        prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER)
        st.open(SettingsRoute.PopupOverrides)
        val cells = forms.map(::board)
        if (land) assertEquals("one row on the side", 1, cells.map { rect(it).top }.distinct().size)
        cells.groupBy { rect(it).top }.values.forEach { row -> assertEquals("a row's heights", 1, row.map { it.height }.distinct().size) }
        var badges = 0
        forms.zip(cells).forEach { (form, cell) ->
            assertInView(form, cell)
            val texts = KeyboardHarness.all(cell).filterIsInstance<TextView>().filter { it.isShown }.toList()
            val badge = texts.singleOrNull { it.text.all(Char::isDigit) }
            val name = texts.single { it !== badge }
            assertWhole(name, 2)
            if (badge == null) return@forEach
            badges++
            val glyph = KeyboardHarness.all(cell).filterIsInstance<KeyboardGlyphView>().single()
            assertFalse("$form: the badge over the picture", Rect.intersects(rect(badge), rect(glyph)))
            assertFalse("$form: the badge over the name", Rect.intersects(rect(badge), rect(name)))
            // above the card, in the room the row keeps over it: all of it, not clipped by the row
            assertInView("$form's badge", badge)
        }
        assertEquals(3, badges)
    }

    // 多选: each card's strip
    private fun multiSelect() {
        var v: View = KeyboardHarness.all(decor).filterIsInstance<TextView>().first { it.text.toString() == s(R.string.swipe_multi_select) }
        while (!v.hasOnClickListeners()) v = v.parent as View
        v.performClick()
        idle()
        forms.forEach { form -> assertStrip(form, KeyboardHarness.all(board(form)).filterIsInstance<CheckStrip>().single()) }
        v = KeyboardHarness.all(decor).filterIsInstance<TextView>().first { it.text.toString() == s(R.string.done) }
        while (!v.hasOnClickListeners()) v = v.parent as View
        v.performClick()
        idle()
    }

    // the editor of 全键盘's a, its key's own default changed (the restore button besides cancel and OK): the
    // three title rows and OK in view; the open card's inner scroll where the titles stay put, all folded where
    // the whole scrolls (§5.1) — so too with the buttons stacked, laid out in a second pass
    private fun editor(stackedHere: Boolean) {
        prefs.set(AppPrefs.getInstance().internal.keySwipes, SwipeSlots.Default.with("full/a", true, SwipeSlot.Empty).serialize())
        st.open(SettingsRoute.PopupOverrides)
        KeyboardHarness.all(decor).first { it.contentDescription?.toString()?.substringBefore(',') == "a" && it.hasOnClickListeners() }.performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val window = dialog.window!!.decorView
        val accordion = KeyboardHarness.all(window).filterIsInstance<AccordionLayout>().single()
        val stacked = KeyboardHarness.all(window).filterIsInstance<ButtonBarLayout>().single().orientation == ButtonBarLayout.VERTICAL
        assertEquals("buttons stacked", stackedHere, stacked)
        val how = "fixed titles ${accordion.fixedTitles}, open ${accordion.open}, buttons stacked $stacked"
        accordion.cards.forEach { assertInView("${it.header.contentDescription} ($how)", it.header) }
        assertInView("OK ($how)", dialog.getButton(AlertDialog.BUTTON_POSITIVE))
        if (accordion.fixedTitles) {
            assertEquals(how, if (land) null else 0, accordion.open)
        } else {
            assertEquals(how, null, accordion.open)
        }
        dialog.dismiss()
        idle()
    }

    private fun View.dp(v: Int) = context.dp(v)

    private fun s(id: Int) = st.s(id)
}
