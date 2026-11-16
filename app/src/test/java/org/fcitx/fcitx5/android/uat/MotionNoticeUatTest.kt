/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.floating.FloatingHandleSide
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightCap
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPopupMenu

/** UAT cases of the notices, the docked keyboard's handle and the floating one (uat/19). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class MotionNoticeUatTest {

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness ?: KeyboardHarness().also { harness = it }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        harness?.close()
        val internal = AppPrefs.getInstance().internal
        internal.floatingKeyboardX.setValue(internal.floatingKeyboardX.defaultValue)
        internal.floatingKeyboardY.setValue(internal.floatingKeyboardY.defaultValue)
    }

    private fun s(id: Int) = h.service.getString(id)

    private fun statusArea() {
        h.tap(h.described(s(R.string.expand_toolbar)))
        h.after(500)
        h.tap(h.described(s(R.string.status_area)))
    }

    // a status area entry, by its title
    private fun entry(title: String): View {
        val t = KeyboardHarness.all(h.inputView).firstOrNull { h.shown(it) && it is TextView && it.text.toString() == title }
            ?: error("no $title in ${h.texts(h.inputView)}")
        return generateSequence(t) { it.parent as? View }.first { it.hasOnClickListeners() }
    }

    private val screenW get() = h.service.resources.displayMetrics.widthPixels
    private val screenH get() = h.service.resources.displayMetrics.heightPixels

    // the keys' area: the keyboard's own box, whatever it floats in
    private fun keys() = h.box(h.board)

    private fun grip(): View? = KeyboardHarness.find<FloatingHandle>(h.inputView)?.takeIf { h.shown(it) }

    @Test
    @Uat("MN-02", "PR-17")
    fun habitsOffAndOnEachSaidTheNewInTheOldsPlace() {
        prefs.set(AppPrefs.getInstance().internal.useHabits, true)
        h.start()
        statusArea()
        h.tap(entry(s(R.string.learning_habits_off)))
        val first = h.notice()
        assertEquals(listOf(s(R.string.learning_habits_off_said)), h.noticeTexts().filter { it.isNotEmpty() })
        if (h.notice() == null) statusArea()
        h.tap(entry(s(R.string.learning_habits_on)))
        // the same window, its words replaced: one notice on the screen
        assertSame(first, h.notice())
        assertTrue(first!!.isShowing)
        assertEquals(listOf(s(R.string.learning_habits_on_said)), h.noticeTexts().filter { it.isNotEmpty() })
    }

    @Test
    @Uat("MN-03")
    fun floatingAtTheTopTheNoticeGoesBelow() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        val handle = grip()!!
        h.drag(handle, h.box(handle).exactCenterX(), 0f)
        val kb = keys()
        assertTrue("$kb", kb.top < screenH / 3)
        h.service.say(R.string.text_copied)
        h.idle()
        val chip = h.noticeBox()
        // below the whole of it, its handle too
        val bottom = maxOf(kb.bottom, h.box(grip()!!).bottom)
        assertTrue("notice $chip, keyboard $kb to $bottom", chip.top >= bottom - 4)
        assertTrue("$chip", chip.bottom <= screenH)
    }

    @Test
    @Uat("MN-03")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun floatingAtTheLeftTheNoticeGoesRight() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.start()
        val handle = grip()!!
        h.drag(handle, 0f, h.box(handle).exactCenterY())
        val kb = keys()
        assertTrue("$kb", kb.left <= 10)
        h.service.say(R.string.text_copied)
        h.idle()
        val chip = h.noticeBox()
        assertTrue("notice $chip, keyboard $kb", chip.left >= kb.right - 4)
        assertTrue("$chip", chip.right <= screenW)
    }

    private fun deleteByMenu(text: String) {
        h.entry(text)!!.performLongClick()
        h.idle()
        h.menuPick(s(R.string.delete))
    }

    private fun undo() {
        val popup = h.notice()!!
        val button = KeyboardHarness.all(popup.contentView).filterIsInstance<TextView>()
            .last { it.text.toString() == s(R.string.undo) }
        assertTrue(button.performClick())
        h.idle()
        h.after(500)
        h.relayout()
    }

    private fun deleteAndUndo(name: String) {
        harness = KeyboardHarness(theme = null)
        h.start()
        h.copied("公园")
        h.clipboard("公园")
        deleteByMenu("公园")
        // marked on Room's thread, then said; the list drops it after (Paging's load anew): else "back" below passes on
        // the list as it was, the undo not yet in, and the close's clearing may run before it and leave 公园 to the next
        h.waitFor("the notice") { h.notice() != null }
        h.waitFor("公园 gone") { h.entry("公园") == null }
        val said = h.noticeTexts()
        assertTrue(said.toString(), s(R.string.undo) in said && h.service.resources.getString(R.string.num_items_deleted, 1) in said)
        // over the keys, where every notice is, not a bar at the bottom
        assertTrue("${h.noticeBox()}", h.noticeBox().bottom <= h.box(h.board).top + 4)
        // 撤销 on the notice's own colour: no patch of another behind it
        val popup = h.notice()!!
        val undo = KeyboardHarness.all(popup.contentView).filterIsInstance<TextView>().last { it.text.toString() == s(R.string.undo) }
        assertTrue(undo.background == null || undo.background !is android.graphics.drawable.ColorDrawable)
        h.capture("MotionNoticeUatTest/clipboard_undo_$name", popup.contentView)
        undo()
        h.waitFor("公园 back") { h.entry("公园") != null }
        h.close()
        harness = null
    }

    @Test
    @Uat("MN-04")
    fun clipboardDeleteUndoneOnTheNoticeInEitherTheme() {
        val themeWas = ThemeManager.prefs.normalModeTheme.getValue()
        prefs.later { ThemeManager.setNormalModeTheme(themeWas) }
        prefs.set(ThemeManager.prefs.followSystemDayNightTheme, false)
        try {
            ThemeManager.setNormalModeTheme(TestThemes.PixelLight)
            deleteAndUndo("light")
            ThemeManager.setNormalModeTheme(TestThemes.PixelDark)
            deleteAndUndo("dark")
        } finally {
            // the rest of the test in the light one
            ThemeManager.setNormalModeTheme(TestThemes.PixelLight)
        }
        // deletes while it shows add up: a swipe, then a long press, one 撤销 for both
        h.start()
        h.copied("公园")
        h.copied("散步")
        h.clipboard("散步")
        val walk = h.entry("散步")!!
        h.drag(walk, h.box(walk).right - 10f, h.box(walk).exactCenterY(), steps = 6)
        h.waitFor("the notice") { h.notice() != null }
        h.noticeTexts().let { assertTrue(it.toString(), h.service.resources.getString(R.string.num_items_deleted, 1) in it) }
        deleteByMenu("公园")
        h.waitFor("2 deleted") { h.service.resources.getString(R.string.num_items_deleted, 2) in h.noticeTexts() }
        h.noticeTexts().let { assertTrue(it.toString(), h.service.resources.getString(R.string.num_items_deleted, 2) in it) }
        h.waitFor("both gone") { h.entry("公园") == null && h.entry("散步") == null }
        undo()
        h.waitFor("both back") { h.entry("公园") != null && h.entry("散步") != null }
    }

    @Test
    @Uat("MN-10")
    fun theDockedHandleUnderOrOverTheKeysTheKeysAsTall() {
        h.start()
        val plain = h.key("q").height
        h.close()
        harness = null
        prefs.set(ThemeManager.prefs.alwaysShowHandle, true)
        h.start()
        val g = grip()!!
        assertEquals(plain, h.key("q").height)
        assertTrue("${h.box(g)} ${keys()}", h.box(g).centerY() > keys().bottom - 10)
        // nothing at its right end: no dock button docked
        assertFalse(KeyboardHarness.all(g).any { it.contentDescription == s(R.string.floating_keyboard_dock) })
        h.capture("MotionNoticeUatTest/handle_bottom")
        prefs.set(ThemeManager.prefs.floatingHandle, FloatingHandleSide.Top)
        h.close()
        harness = null
        h.start()
        val top = grip()!!
        assertTrue("${h.box(top)} ${keys()}", h.box(top).centerY() < keys().top + 10)
        assertEquals(plain, h.key("q").height)
    }

    private fun floating() = keys().width() < screenW * 0.9

    @Test
    @Uat("MN-11")
    fun aLongPressOnTheHandleLetsItFloatUnderTheFinger() {
        prefs.set(ThemeManager.prefs.alwaysShowHandle, true)
        h.start()
        val g = grip()!!
        h.drag(g, h.box(g).exactCenterX(), screenH / 2f, holdMs = 900)
        h.after(500)
        h.relayout()
        assertTrue("${keys()}", floating())
        val there = keys()
        assertTrue("$there", there.top < screenH * 0.65)
        // dragged to the bottom: docked again, the handle still there
        val fg = grip()!!
        h.drag(fg, h.box(fg).exactCenterX(), screenH - 2f)
        h.after(500)
        h.relayout()
        assertFalse("${keys()}", floating())
        assertNotNull(grip())
        // a long press by mistake, on into the bottom without lifting: docked, and floating next where it was
        val g2 = grip()!!
        h.drag(g2, h.box(g2).exactCenterX(), screenH - 2f, holdMs = 900)
        h.after(500)
        h.relayout()
        assertFalse("${keys()}", floating())
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.after(500)
        h.relayout()
        val again = keys()
        assertTrue("$again, was $there", kotlin.math.abs(again.left - there.left) <= 10 && kotlin.math.abs(again.top - there.top) <= 10)
    }

    @Test
    @Uat("MN-12")
    fun oneHandedTheHandleAsWideAsTheKeyboard() {
        prefs.set(ThemeManager.prefs.alwaysShowHandle, true)
        prefs.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right)
        h.start()
        val kb = keys()
        val g = h.box(grip()!!)
        assertTrue("$kb", kb.width() < screenW * 0.9)
        assertTrue("$g of $kb", kotlin.math.abs(g.centerX() - kb.centerX()) <= 12 && kotlin.math.abs(g.width() - kb.width()) <= 12)
        h.capture("MotionNoticeUatTest/handle_one_handed")
    }

    @Test
    @Uat("MN-21")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun landscapeLargestCandidatesStillLeaveTheApp120dp() {
        prefs.set(ThemeManager.prefs.candidateTextSize, 28)
        prefs.set(ThemeManager.prefs.alwaysShowHandle, true)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 60)
        h.start()
        val density = h.service.resources.displayMetrics.density
        // the handle over the bar is the keyboard's: the app's room is above the topmost of them
        val top = minOf(h.box(h.bar).top, grip()?.let { h.box(it).top } ?: Int.MAX_VALUE)
        val app = top - h.box(h.inputView).top
        assertTrue("app $app px of $screenH", app >= KeyboardHeightCap.MIN_APP_DP * density - 2)
        h.capture("MotionNoticeUatTest/landscape_28sp")
    }
}
