/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.learning

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.NoticeState.Companion.SAID_MS
import org.fcitx.fcitx5.android.input.NoticeState.Companion.UNDO_MS
import org.fcitx.fcitx5.android.input.UndoChipPlacement
import org.fcitx.fcitx5.android.input.clipboard.DeletedEntries
import org.fcitx.fcitx5.android.input.motion.Motion
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.placeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

/** The keyboard's one notice: one window, replaced in place, put away by a tap, 撤销 or its time. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
// text measured as on a device: the taps below land by position
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UndoChipTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    // the app's strings are not in unit tests' resources: each a placeholder of its id
    private val anchor = FrameLayout(PlaceholderStrings(activity)).also {
        activity.setContentView(it)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val off = Motion.Settings(systemScale = 1f, disabled = true)

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // the longest animation a notice runs, and a frame after: each below run to its end (F3519)
    private val settled = Motion.entries.maxOf { it.enter(Motion.Settings.DEFAULT) } + FRAME_MS

    private fun chip(motion: Motion.Settings = off) =
        UndoChip(anchor, ThemePreset.PixelDark, { _, _, _ -> UndoChipPlacement.Place(0, 0, UndoChipPlacement.Slide.Up) }, { motion })

    // the chips up: panels, the activity's own window left out
    private fun windows(): List<View> {
        val wm = Shadow.extract<ShadowWindowManagerImpl>(activity.windowManager)
        return wm.views.filter { (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL }
    }

    private fun View.texts(): List<String> = when (this) {
        is TextView -> listOf(text.toString())
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).texts() }
        else -> emptyList()
    }

    private fun View.find(text: String): View? = when {
        this is TextView && this.text.toString() == text -> this
        this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).find(text) }
        else -> null
    }

    private val undoLabel get() = placeholder(R.string.learning_undo)

    private class Record {
        val calls = mutableListOf<String>()
        fun undo(name: String) = UndoChip.Undo({ calls += "undo $name" }, { calls += "lapsed $name" })
    }

    @Test
    fun aNewNoticeTakesTheShownOnesPlaceInTheSameWindow() {
        val chip = chip()
        val r = Record()
        chip.show("一", r.undo("一"))
        val first = windows().single()
        chip.show("二")
        assertEquals("one window, the same one", listOf(first), windows())
        assertEquals(listOf("二"), first.texts())
        // the first's act stands: replaced is gone without 撤销
        assertEquals(listOf("lapsed 一"), r.calls)
    }

    @Test
    fun replacedWhileAnimatingTheOldWordsFadeAndGo() {
        val chip = chip(Motion.Settings.DEFAULT)
        chip.show("一")
        idle(50)
        chip.show("二")
        chip.show("三")
        idle(settled)
        assertEquals(listOf("三"), windows().single().texts())
    }

    @Test
    fun aTapPutsItAwayAndTheActStands() {
        val chip = chip()
        val r = Record()
        chip.show("删掉了", r.undo("删"))
        windows().single().find("删掉了")!!.let { (it.parent as View).parent as View }.performClick()
        assertFalse(chip.showing)
        assertEquals(emptyList<View>(), windows())
        assertEquals(listOf("lapsed 删"), r.calls)
    }

    @Test
    fun undoTakesItBackAndDoesNotLapse() {
        val chip = chip()
        val r = Record()
        chip.show("删掉了", r.undo("删"))
        windows().single().find(undoLabel)!!.performClick()
        assertFalse(chip.showing)
        assertEquals(listOf("undo 删"), r.calls)
    }

    @Test
    fun withAnimationsOffItIsInPlaceAndOpaqueAtOnce() {
        // ST-26: 禁用动画 reaches the notice too; a screen recording cannot tell it on a device, the
        // notice's first frame being most of the way in already
        fun View.moved(): Boolean = alpha < 1f || translationX != 0f || translationY != 0f ||
            (this is ViewGroup && (0 until childCount).any { getChildAt(it).moved() })
        chip(off).show("已复制")
        assertFalse("in place and opaque before a frame has run", windows().single().moved())
        val animated = chip(Motion.Settings.DEFAULT)
        animated.show("已复制")
        assertTrue("on its way in, animated", windows().any { it.moved() })
    }

    @Test
    fun animatedItLeavesAfterItsExit() {
        val chip = chip(Motion.Settings.DEFAULT)
        chip.show("已复制")
        idle(settled)
        chip.dismiss(animated = true)
        assertTrue("going, not gone, while it slides back", chip.showing)
        idle(settled)
        assertFalse(chip.showing)
    }

    @Test
    fun aLineSaidGoesInTwoSecondsOneWithUndoInSix() {
        val chip = chip()
        chip.show("已复制")
        // each pinned from both sides, from its show (F3500)
        idle(SAID_MS - 1)
        assertTrue(chip.showing)
        idle(1)
        assertFalse(chip.showing)
        val r = Record()
        chip.show("删掉了", r.undo("删"))
        idle(UNDO_MS - 1)
        assertTrue(chip.showing)
        idle(1)
        assertFalse(chip.showing)
        assertEquals(listOf("lapsed 删"), r.calls)
    }

    @Test
    fun theKeyboardGoneTakesItAtOnce() {
        val chip = chip(Motion.Settings.DEFAULT)
        val r = Record()
        chip.show("删掉了", r.undo("删"))
        chip.dismiss()
        assertFalse(chip.showing)
        assertEquals(listOf("lapsed 删"), r.calls)
    }

    @Test
    fun clipboardDeletesAreUndoneTogetherFromTheChip() {
        val chip = chip()
        val calls = mutableListOf<String>()
        val deleted = DeletedEntries({ calls += "undo ${it.toList()}" }, { calls += "purge ${it.toList()}" })
        deleted.deleted(1).let { (n, undo) -> chip.show("$n", undo, aboveKeys = true) }
        deleted.deleted(2, 3).let { (n, undo) -> chip.show("$n", undo, aboveKeys = true) }
        assertEquals(listOf("3", undoLabel), windows().single().texts())
        windows().single().find(undoLabel)!!.performClick()
        assertEquals("the first notice replaced purges nothing", listOf("undo [1, 2, 3]"), calls)
    }

    @Test
    fun clipboardDeletesArePurgedWhenAnotherNoticeTakesTheirPlace() {
        val chip = chip()
        val calls = mutableListOf<String>()
        val deleted = DeletedEntries({ calls += "undo ${it.toList()}" }, { calls += "purge ${it.toList()}" })
        deleted.deleted(1).let { (n, undo) -> chip.show("$n", undo, aboveKeys = true) }
        chip.show("已复制", aboveKeys = true)
        assertEquals(listOf("purge [1]"), calls)
    }

    // a finger down and up at ([x], [y]) of [view], dispatched from its window's root as a touch is
    private fun tap(view: View, x: Float, y: Float) {
        val root = view.rootView
        val at = IntArray(2).also(view::getLocationInWindow)
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, action, at[0] + x, at[1] + y, 0)
            root.dispatchTouchEvent(e)
            e.recycle()
        }
        idle()
    }

    private fun shownRow(word: String) = windows().single().find(word)!!.parent as ViewGroup

    @Test
    fun aTapOnTheWordsPutsItAwayAndTheLineBesideUndoStillTakesBack() {
        val chip = chip()
        val r = Record()
        chip.show("删掉了", r.undo("删"))
        idle()
        // the line between the words and 撤销: a near miss is 撤销's, not the chip's (which lapses)
        val line = shownRow("删掉了").getChildAt(1)
        tap(line, 0.5f, line.height / 2f)
        assertEquals(listOf("undo 删"), r.calls)
        assertFalse(chip.showing)

        val s = Record()
        chip.show("又删了", s.undo("又"))
        idle()
        val words = windows().single().find("又删了")!!
        tap(words, words.width / 3f, words.height / 2f)
        assertEquals(listOf("lapsed 又"), s.calls)
        assertFalse(chip.showing)
    }

    @Test
    fun theFadingRowsUndoTakesNothingBackAndLeavesTheNewNotice() {
        val chip = chip(Motion.Settings.DEFAULT)
        val r = Record()
        chip.show("一", r.undo("一"))
        idle(settled)
        val old = shownRow("一")
        val s = Record()
        chip.show("二二二二二二", s.undo("二"))
        // mid crossfade: the old row is still there, its 撤销 too; a finger on it, dispatched
        val button = old.find(undoLabel)!!
        tap(button, button.width / 2f, button.height / 2f)
        assertEquals(listOf("lapsed 一"), r.calls)
        assertTrue("the new notice is not tapped away", chip.showing)
        assertEquals("nor its act lapsed", emptyList<String>(), s.calls)
        idle(settled)
        assertEquals(listOf("二二二二二二", undoLabel), windows().single().texts())
    }

    // F4048: mid crossfade only the old 撤销's area is swallowed (its delegate rebuilt by the
    // layout pass replace asks for); the new row's 撤销 takes back its own act
    @Test
    fun duringTheFadeTheOldUndosAreaIsSwallowedAndTheNewUndoTakesBack() {
        val chip = chip(Motion.Settings.DEFAULT)
        val r = Record()
        chip.show("一", r.undo("一"))
        idle(settled)
        val old = shownRow("一")
        val s = Record()
        chip.show("二二二二二二", s.undo("二"))
        // the layout pass replace's requestLayout asks for, run now: the delegate moves to nothing
        old.measure(View.MeasureSpec.makeMeasureSpec(old.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(old.height, View.MeasureSpec.EXACTLY))
        old.layout(old.left, old.top, old.right, old.bottom)
        // the old row's line beside its 撤销: in the delegate's area, outside 撤销 itself
        val line = old.getChildAt(1)
        tap(line, 0.5f, line.height / 2f)
        assertEquals(listOf("lapsed 一"), r.calls)
        assertTrue("swallowed: the new notice is not tapped away", chip.showing)
        assertEquals(emptyList<String>(), s.calls)
        val button = shownRow("二二二二二二").find(undoLabel)!!
        tap(button, button.width / 2f, button.height / 2f)
        assertEquals(listOf("undo 二"), s.calls)
        assertFalse(chip.showing)
    }

    // F4048: the rest of the old row, under the new one, lets a tap by: it reaches the new notice
    @Test
    fun duringTheFadeATapOnTheOldWordsReachesTheNewNotice() {
        val chip = chip(Motion.Settings.DEFAULT)
        chip.show("一", Record().undo("一"))
        idle(settled)
        val words = windows().single().find("一")!!
        val s = Record()
        chip.show("二二二二二二", s.undo("二"))
        tap(words, words.width / 2f, words.height / 2f)
        assertEquals("the new notice tapped away, its act standing", listOf("lapsed 二"), s.calls)
        assertFalse(chip.showing)
    }

    // F4143: a notice said before the keyboard's first layout wraps at the screen's width, not at
    // nothing: as wide as the same notice once the keyboard is laid out
    @Test
    fun aNoticeBeforeTheFirstLayoutIsAsWideAsAfterIt() {
        val message = "已复制到剪贴板，可以粘贴"
        val unlaid = FrameLayout(PlaceholderStrings(activity))
        activity.windowManager.addView(unlaid, WindowManager.LayoutParams(0, 0, WindowManager.LayoutParams.TYPE_APPLICATION, 0, 0))
        idle()
        assertEquals("the anchor's window has no width yet", 0, unlaid.rootView.width)
        val early = UndoChip(unlaid, ThemePreset.PixelDark, { _, _, _ -> UndoChipPlacement.Place(0, 0, UndoChipPlacement.Slide.Up) }, { off })
        assertTrue(early.show(message))
        val before = shownRow(message).layoutParams.width
        early.dismiss()
        activity.windowManager.removeView(unlaid)
        chip().show(message)
        val after = shownRow(message).layoutParams.width
        assertTrue("$before", before in 1..activity.resources.displayMetrics.widthPixels)
        assertEquals(after, before)
    }

    @Test
    fun aNoticeOnItsWayOutTakesTheNewOneInTheSameWindow() {
        val chip = chip(Motion.Settings.DEFAULT)
        chip.show("一")
        idle(settled)
        chip.dismiss(animated = true)
        // still going: its end, which would dismiss the window, is not to run once it is reused
        val window = windows().single()
        chip.show("二")
        idle(settled)
        assertTrue(chip.showing)
        assertEquals(listOf(window), windows())
        assertEquals(listOf("二"), window.texts())
    }

    @Test
    fun withTheKeyboardNotShownNothingOpensAndTheActStands() {
        val detached = FrameLayout(PlaceholderStrings(activity))
        val chip = UndoChip(detached, ThemePreset.PixelDark, { _, _, _ -> UndoChipPlacement.Place(0, 0, UndoChipPlacement.Slide.Up) }, { off })
        val r = Record()
        assertFalse("not shown, for the caller to say otherwise (F3530)", chip.show("删掉了", r.undo("删")))
        assertFalse(chip.showing)
        assertEquals(emptyList<View>(), windows())
        assertEquals(listOf("lapsed 删"), r.calls)
    }

    @Test
    fun aNoticeInTheOldOnesPlaceHasItsOwnTime() {
        val chip = chip()
        chip.show("已复制")
        idle(1500)
        chip.show("删掉了", Record().undo("删"))
        idle(1000)
        assertTrue("6 s from the second, not 2 s from the first", chip.showing)
    }

    @Test
    fun typingPutsAwayWhatWasDoneButNotAFailureSaidAsTheKeyFailed() {
        val chip = chip()
        chip.show("已复制")
        chip.typed()
        assertFalse(chip.showing)
        // the engine could not load: the key it failed on comes to the keyboard after the notice
        chip.show("读不出来", steady = true)
        chip.typed()
        assertTrue(chip.showing)
        // said once, it stays as long as one with 撤销, counted from its show
        idle(UNDO_MS - 1)
        assertTrue(chip.showing)
        idle(1)
        assertFalse(chip.showing)
    }

    @Test
    fun keysTypedWhileItGoesLetItGoOn() {
        val chip = chip(Motion.Settings.DEFAULT)
        chip.show("已复制")
        idle(settled)
        chip.typed()
        idle(50)
        chip.typed()
        idle(50)
        assertFalse("one exit, not started again by each key", chip.showing)
    }

    private companion object {
        const val FRAME_MS = 20L
    }
}
