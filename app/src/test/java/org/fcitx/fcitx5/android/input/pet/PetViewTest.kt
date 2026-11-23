/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.RasterPacks
import org.fcitx.fcitx5.android.input.floating.BoardFollowers
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyMotionSwitch
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.scene.pet.Pet
import org.fcitx.fcitx5.android.scene.pet.PetLimits
import org.fcitx.fcitx5.android.scene.pet.PetMachine
import org.fcitx.fcitx5.android.scene.pet.PetState
import org.fcitx.fcitx5.android.scene.pet.PetState.Name
import org.fcitx.fcitx5.android.scene.pet.PetTrigger
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration

/** The pet on the main looper: frames as time and keys go, no timer where nothing moves, touches to the keys, placed on the board's corner or the bar's end. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class PetViewTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun atOnce() = RasterPacks.atOnce()

    @After
    fun putBack() = RasterPacks.putBack()

    private val activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().visible().get() }

    private val motion get() = AppPrefs.getInstance().advanced

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // idle 4 frames at 4 fps, typing 6 at 12: half a second
    private fun pet(vararg more: Pair<Name, Pair<Int, Int>>) = PetSheets.pet(folder.root, Name.IDLE to (4 to 4), Name.TYPING to (6 to 12), *more)

    private val under = ArrayList<Int>()

    private fun shown(view: PetView): PetView {
        val root = FrameLayout(activity)
        root.addView(View(activity).apply { setOnTouchListener { _, e -> under += e.actionMasked; true } }, FrameLayout.LayoutParams(-1, -1))
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        view.place(0, 0)
        idle()
        return view
    }

    private fun shown(vararg more: Pair<Name, Pair<Int, Int>>) = shown(PetView(activity, pet(*more), 100, PetFrames.BUDGET_BYTES))

    private val PetView.at get() = PetMachine.stateOf(frame) to PetMachine.frameOf(frame)

    private val PetView.ticking get() = handler.hasCallbacks(tick)

    @Test
    fun itPlaysAndFollowsKeys() {
        val v = shown()
        assertEquals(Name.IDLE to 0, v.at)
        idle(250)
        assertEquals(Name.IDLE to 1, v.at)
        v.on(PetTrigger.TYPING)
        assertEquals(Name.TYPING to 0, v.at)
        idle(84)
        assertEquals(Name.TYPING to 1, v.at)
        idle(500)
        assertEquals(Name.IDLE, v.at.first)
        // a key it has no state for changes nothing
        v.on(PetTrigger.ENTER)
        assertEquals(Name.IDLE, v.at.first)
        assertTrue(v.ticking)
    }

    // a frame of [w] × 2 px, its left half black, drawn in a box [size] px square
    private fun drawn(w: Int, size: Int): Bitmap {
        val sheet = Bitmap.createBitmap(w, 2, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until w) for (y in 0 until 2) setPixel(x, y, if (x < w / 2) Color.BLACK else Color.WHITE)
        }
        val file = File(folder.root, "pet-idle.png").apply { writeBytes(ByteArrayOutputStream().also { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()) }
        val pet = Pet(Pet.Place.KEYBOARD, Pet.Align.TOP_END, 40.0, 0.0, 0.0, mapOf(Name.IDLE to PetState(file.path, 1, 1, 4, PetLimits.resting(Name.IDLE))))
        val v = shown(PetView(activity, pet, size, PetFrames.BUDGET_BYTES))
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    @Test
    fun pixelArtMadeBigIsSharp() {
        // 25 times: whole pixels, not smoothed into one another
        val out = drawn(4, 100)
        assertEquals(Color.BLACK, out.getPixel(49, 50))
        assertEquals(Color.WHITE, out.getPixel(50, 50))
    }

    @Test
    fun pixelArtIsMadeBigAWholeNumberOfTimes() {
        // 2.5 times fits: twice, 80 px at the middle, sharp
        val out = drawn(40, 100)
        assertEquals(Color.TRANSPARENT, out.getPixel(9, 50))
        assertEquals(Color.BLACK, out.getPixel(10, 50))
        assertEquals(Color.BLACK, out.getPixel(49, 50))
        assertEquals(Color.WHITE, out.getPixel(50, 50))
        assertEquals(Color.WHITE, out.getPixel(89, 50))
        assertEquals(Color.TRANSPARENT, out.getPixel(90, 50))
    }

    @Test
    fun underTwiceItIsSmoothedAsBefore() {
        // 1.5 times: the halves meet at 4.5 px, a pixel of both
        val mid = drawn(6, 9).getPixel(4, 4)
        assertTrue(Integer.toHexString(mid), mid != Color.BLACK && mid != Color.WHITE && Color.alpha(mid) > 0)
    }

    @Test
    fun withMotionOffItStandsOnIdlesFirstFrame() {
        prefs.set(motion.keyboardMotion, false)
        val v = shown()
        assertEquals(Name.IDLE to 0, v.at)
        assertFalse(v.ticking)
        v.on(PetTrigger.TYPING)
        idle(1000)
        assertEquals(Name.IDLE to 0, v.at)
        assertFalse(v.ticking)
    }

    @Test
    fun turningMotionOffStopsItAtOnce() {
        val v = shown()
        v.on(PetTrigger.TYPING)
        idle(100)
        prefs.set(motion.keyboardMotion, false)
        assertEquals(Name.IDLE to 0, v.at)
        assertFalse(v.ticking)
        prefs.set(motion.keyboardMotion, true)
        assertTrue(v.ticking)
        prefs.set(motion.disableAnimation, true)
        assertEquals(Name.IDLE to 0, v.at)
        assertFalse(v.ticking)
    }

    // as the developer options' animator scale sets it: hidden API
    private fun animatorScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale", ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, scale))
    }

    // the system's animator scale tells no one: the key that finds it back on plays, not only restarts idle
    @Test
    fun aKeyFindingMotionBackWithoutACallbackPlays() {
        val v = shown()
        try {
            animatorScale(0f)
            v.on(PetTrigger.TYPING)
            assertFalse(v.ticking)
            animatorScale(1f)
            v.on(PetTrigger.TYPING)
            assertEquals(Name.TYPING to 0, v.at)
            assertTrue(v.ticking)
        } finally {
            animatorScale(1f)
        }
    }

    @Test
    fun detachedItListensToNothingAndLetsItsFramesGo() {
        val v = shown()
        val switch = ReflectionHelpers.getStaticField<List<*>>(KeyMotionSwitch::class.java, "changes")
        fun listening() = listOf(
            v.changed in switch,
            v.prefChanged in ReflectionHelpers.getField<Set<*>>(motion.keyboardMotion, "listeners"),
            v.prefChanged in ReflectionHelpers.getField<Set<*>>(motion.disableAnimation, "listeners"),
        )
        assertEquals(listOf(true, true, true), listening())
        (v.parent as FrameLayout).removeView(v)
        assertEquals(listOf(false, false, false), listening())
        assertFalse(v.handler?.hasCallbacks(v.tick) == true)
    }

    @Test
    fun oneRunnableIsPostedAgainNotANewOneEachFrame() {
        val v = shown()
        val tick = v.tick
        repeat(10) {
            idle(250)
            assertTrue(v.handler.hasCallbacks(tick))
        }
        assertTrue(tick === v.tick)
    }

    @Test
    fun touchesGoToTheKeysUnderIt() {
        val v = shown()
        assertFalse(v.isClickable || v.isFocusable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, v.importantForAccessibility)
        val t = SystemClock.uptimeMillis()
        (v.parent as View).dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 20f, 20f, 0))
        assertEquals(listOf(MotionEvent.ACTION_DOWN), under)
    }

    @Test
    fun hiddenItStopsAndBackItIsIdleFromFrameZero() {
        val v = shown()
        v.on(PetTrigger.TYPING)
        idle(170)
        v.dispatchWindowVisibilityChanged(View.GONE)
        assertFalse(v.ticking)
        v.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(Name.IDLE to 0, v.at)
        assertTrue(v.ticking)
        v.on(PetTrigger.TYPING)
        v.show(false)
        assertEquals(View.INVISIBLE, v.visibility)
        assertFalse(v.ticking)
        v.show(true)
        assertEquals(Name.IDLE to 0, v.at)
    }

    @Test
    fun withTheScreenOffItStops() {
        val v = shown()
        v.onScreenStateChanged(View.SCREEN_STATE_OFF)
        assertFalse(v.ticking)
        v.onScreenStateChanged(View.SCREEN_STATE_ON)
        assertTrue(v.ticking)
    }

    @Test
    fun aPetThatFailedToLoadIsNotLoadedAgain() {
        val p = pet()
        val sheet = File(p.idle.sheet)
        val good = sheet.readBytes()
        // its pixels broken since the pack was checked
        sheet.writeBytes(good.copyOf(40))
        val v = shown(PetView(activity, p, 100, PetFrames.BUDGET_BYTES))
        assertFalse(v.ticking)
        sheet.writeBytes(good)
        val before = PetFrames.decodes.get()
        v.show(false)
        v.show(true)
        idle()
        assertEquals(0, PetFrames.decodes.get() - before)
        assertFalse(v.ticking)
    }

    @Test
    fun aStillIdleHasATimerOnlyForItsSleep() {
        val still = PetSheets.pet(folder.root, Name.IDLE to (1 to 8))
        assertFalse(shown(PetView(activity, still, 100, PetFrames.BUDGET_BYTES)).ticking)
        val sleepy = shown(PetView(activity, PetSheets.pet(folder.root, Name.IDLE to (1 to 8), Name.SLEEP to (2 to 2)), 100, PetFrames.BUDGET_BYTES))
        assertTrue(sleepy.ticking)
        idle(PetMachine.SLEEP_MS)
        assertEquals(Name.SLEEP to 0, sleepy.at)
        idle(3000)
        assertEquals(Name.SLEEP to 1, sleepy.at)
        assertFalse(sleepy.ticking)
    }

    // the host: a board of keys under a bar, as InputView has them
    private val board by lazy { ConstraintLayout(activity) }
    private val bar by lazy { View(activity).apply { id = View.generateViewId() } }
    private val keys by lazy { View(activity).apply { id = View.generateViewId() } }

    private fun host(pet: String, rtl: Boolean = false, half: Boolean? = null): PetHost {
        val files = mapOf("pet-idle.png" to PetSheets.png(4, 4, 64), "pet-typing.png" to PetSheets.png(2, 2, 64))
        val pack = RasterPacks.pack("""$pet<state name="idle" sheet="pet-idle.png" frames="4"/><state name="typing" sheet="pet-typing.png" frames="2"/></pet>""", files)[0]
        if (rtl) board.layoutDirection = View.LAYOUT_DIRECTION_RTL
        board.addView(bar, ConstraintLayout.LayoutParams(0, 120).apply {
            leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        board.addView(keys, ConstraintLayout.LayoutParams(if (half == null) 0 else 500, 600).apply {
            if (half != false) leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            if (half != true) rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
            topToBottom = bar.id
        })
        val host = PetHost.of(activity, pack, keys, bar)!!
        board.addView(host.view, ConstraintLayout.LayoutParams(0, 0).apply {
            leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        })
        activity.setContentView(board)
        // as InputView.startInput does
        host.onStartInput(EditorInfo(), CapabilityFlags.DefaultFlags)
        host.onWindowAttached(KeyboardWindow())
        layOut()
        return host
    }

    // twice: a bar's room set as it was laid out takes the next pass, as on a phone
    private fun layOut() = repeat(2) {
        board.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        board.layout(0, 0, 1000, 720)
        idle()
    }

    private val density get() = activity.resources.displayMetrics.density

    @Test
    fun aBarPetsRoomOutlivesTheBarsPlacing() {
        val host = host("""<pet place="bar-end" size="32dp">""")
        val margin = (bar.layoutParams as ConstraintLayout.LayoutParams).rightMargin
        assertEquals(host.view.sizePx + Math.round(4 * density), margin)
        BoardFollowers.place(listOf(bar), keys, null, padding = 30)
        layOut()
        assertEquals(margin, (bar.layoutParams as ConstraintLayout.LayoutParams).rightMargin)
        assertEquals(1000 - margin, bar.right)
        val box = host.view.placed
        assertTrue("the candidates end before it", bar.right - bar.paddingRight <= box.left)
        assertTrue("not into the screen's edge", box.right <= 1000 - 30)
        assertEquals(60, box.centerY())
        // one-handed: the bar over the board, no side padding
        BoardFollowers.place(listOf(bar), keys, SplitKeyboard.Dock.RIGHT, padding = 30)
        layOut()
        assertEquals(margin, (bar.layoutParams as ConstraintLayout.LayoutParams).rightMargin)
        assertTrue(bar.right <= host.view.placed.left)
        assertTrue(host.view.placed.right <= 1000)
    }

    @Test
    fun aBarPetRightToLeftIsAtTheLeft() {
        val host = host("""<pet place="bar-end" size="32dp">""", rtl = true)
        BoardFollowers.place(listOf(bar), keys, null, padding = 30)
        layOut()
        val margin = (bar.layoutParams as ConstraintLayout.LayoutParams).leftMargin
        assertEquals(0, (bar.layoutParams as ConstraintLayout.LayoutParams).rightMargin)
        assertEquals(margin, bar.left)
        val box = host.view.placed
        assertTrue(box.right <= bar.left + bar.paddingLeft)
        assertTrue(box.left >= 30)
    }

    @Test
    fun aKeyboardPetIsOnTheBoardsCornerOneHandedToo() {
        for (half in listOf(null, true, false)) {
            board.removeAllViews()
            val host = host("""<pet align="top-end" offset-x="0" offset-y="0">""", half = half)
            assertEquals("$half", keys.right to keys.top, host.view.placed.right to host.view.placed.top)
        }
        board.removeAllViews()
        val start = host("""<pet align="bottom-start" offset-x="2dp" offset-y="2dp">""", half = false)
        val inward = Math.round(2 * density)
        assertEquals(keys.left + inward to keys.bottom - inward, start.view.placed.left to start.view.placed.bottom)
    }

    private class Other : InputWindow.SimpleInputWindow<Other>() {
        override fun onCreateView(): View = error("not shown")
        override fun onAttached() = Unit
        override fun onDetached() = Unit
    }

    @Test
    fun overAnotherWindowItIsHidden() {
        val host = host("""<pet>""")
        host.view.on(PetTrigger.TYPING)
        host.onWindowAttached(Other())
        assertEquals(View.INVISIBLE, host.view.visibility)
        host.onWindowAttached(KeyboardWindow())
        assertEquals(View.VISIBLE, host.view.visibility)
        assertEquals(Name.IDLE to 0, host.view.at)
    }

    @Test
    fun itTakesItsBoxOnlyAndMovesThere() {
        val host = host("""<pet>""")
        val v = host.view
        assertEquals(v.sizePx to v.sizePx, v.width to v.height)
        assertEquals(v.placed.left.toFloat() to v.placed.top.toFloat(), v.translationX to v.translationY)
    }

    @Test
    fun anotherFieldKeepsItAsItWasAndHidingResetsIt() {
        val host = host("""<pet>""")
        host.view.on(PetTrigger.TYPING)
        val info = EditorInfo()
        host.onFinishInput()
        host.onStartInput(info, CapabilityFlags.DefaultFlags)
        idle()
        assertEquals(View.VISIBLE, host.view.visibility)
        assertEquals(Name.TYPING, host.view.at.first)
        host.onFinishInput()
        idle()
        assertEquals(View.INVISIBLE, host.view.visibility)
    }

    // a setting changed with the keyboard down remakes the keys' window: nothing on screen to play for
    @Test
    fun theKeysWindowAttachedAfterAHideLeavesItHidden() {
        val host = host("""<pet>""")
        assertTrue(host.view.ticking)
        host.onFinishInput()
        idle()
        host.onWindowAttached(KeyboardWindow())
        idle(1000)
        assertEquals(View.INVISIBLE, host.view.visibility)
        assertFalse(host.view.ticking)
        host.onStartInput(EditorInfo(), CapabilityFlags.DefaultFlags)
        host.onWindowAttached(KeyboardWindow())
        assertTrue("back at the next start", host.view.ticking)
    }

    @Test
    fun aThemeWithoutAPetHasNone() {
        val pack = RasterPacks.pack("", emptyMap())[0]
        assertNull(PetHost.of(activity, pack, keys, bar))
    }
}
