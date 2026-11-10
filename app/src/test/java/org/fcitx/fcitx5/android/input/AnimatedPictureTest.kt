/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyMotionSwitch
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.Executors

/**
 * 060-render-p3 A4: the animated picture plays only while it is shown and motion is allowed: the keyboard
 * hidden or its view gone (a theme changed), 键盘动效 off, battery saver on, it is stopped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class AnimatedPictureTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule
    val folder = TemporaryFolder()

    // frames that only say whether they play
    private class FakeFrames : Drawable(), Animatable {
        var running = false
        var starts = 0
        override fun start() {
            running = true
            starts++
        }
        override fun stop() {
            running = false
        }
        override fun isRunning() = running
        override fun getIntrinsicWidth() = 200
        override fun getIntrinsicHeight() = 100
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    private val activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().visible().get() }

    private fun shown(d: Drawable): View {
        val root = FrameLayout(activity)
        activity.setContentView(root)
        return PictureBaseView(activity).also {
            it.background = d
            root.addView(it, FrameLayout.LayoutParams(400, 100))
            // the window shown, as its ViewRootImpl has it and tells the views in it (Robolectric's stays GONE,
            // and a view hidden in a window not shown tells nothing)
            ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(it, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
            it.onVisibilityAggregated(true)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun itPlaysWhileShownAndStopsWhenHiddenOrGone() {
        val frames = FakeFrames()
        val board = AnimatedBoardDrawable(frames, 80, appContext)
        val view = shown(board)
        assertTrue("shown: playing", board.isRunning)
        view.visibility = View.GONE
        idle()
        assertFalse("hidden: stopped", board.isRunning)
        view.visibility = View.VISIBLE
        idle()
        assertTrue("shown again: playing", board.isRunning)
        (view.parent as FrameLayout).removeView(view)
        assertFalse("its view gone (a theme changed): stopped", board.isRunning)
    }

    @Test
    fun theScreenOffStopsIt() {
        val board = AnimatedBoardDrawable(FakeFrames(), 100, appContext)
        val view = shown(board)
        assertTrue(board.isRunning)
        view.onScreenStateChanged(View.SCREEN_STATE_OFF)
        assertFalse("the screen off, the keyboard still up", board.isRunning)
        view.onScreenStateChanged(View.SCREEN_STATE_ON)
        assertTrue(board.isRunning)
    }

    @Test
    fun theSwitchesStopItAtOnce() {
        val frames = FakeFrames()
        val board = AnimatedBoardDrawable(frames, 100, appContext)
        shown(board)
        assertTrue(board.isRunning)
        prefs.set(AppPrefs.getInstance().advanced.keyboardMotion, false)
        assertFalse("键盘动效 off", board.isRunning)
        prefs.set(AppPrefs.getInstance().advanced.keyboardMotion, true)
        assertTrue(board.isRunning)
        val power = shadowOf(appContext.getSystemService(PowerManager::class.java))
        power.setIsPowerSaveMode(true)
        appContext.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        idle()
        assertFalse("battery saver", board.isRunning)
        power.setIsPowerSaveMode(false)
        appContext.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        idle()
        assertTrue(board.isRunning)
        assertTrue(KeyMotionSwitch.on(appContext))
    }

    @Test
    fun notAllowedItNeverStarts() {
        val frames = FakeFrames()
        shown(AnimatedBoardDrawable(frames, 100, appContext) { false })
        assertEquals(0, frames.starts)
    }

    @Test
    fun itsFramesAreLaidAsCenterCropLaysThem() {
        val frames = FakeFrames()
        val board = AnimatedBoardDrawable(frames, 100, appContext)
        board.setBounds(0, 0, 400, 100)
        // 200×100 over 400×100: twice, its top and bottom cut
        assertEquals(0, frames.bounds.left)
        assertEquals(-50, frames.bounds.top)
        assertTrue(frames.bounds.width() >= 400)
    }

    // a file that is no animated WebP, or past the limits: drawn still (no frames made)
    @Test
    fun aPictureThatCannotPlayIsDrawnStill() {
        val still = folder.newFile("still.webp").apply { writeBytes(ByteArray(64)) }
        val big = folder.newFile("big.webp").apply { writeBytes(ByteArray((ThemeAssetLimits.MAX_ANIMATED_BYTES + 1).toInt())) }
        assertEquals(ThemeAssetLimits.Still.Size, ThemeAssetLimits.animated(big))
        assertNull(AnimatedBoardDrawable.decode(picture(still, true), 400, 100))
        assertNull(AnimatedBoardDrawable.decode(picture(big, true), 400, 100))
        assertNull(AnimatedBoardDrawable.decode(picture(File(folder.root, "none.webp"), true), 400, 100))
    }

    private fun picture(f: File, animated: Boolean) = Picture(f.path, Crop(f.path, null, 0), null, 100, 0, animated)

    // the keyboard made: nothing read or decoded on the main thread; the frames handed over on it once
    // decoded, not to a keyboard let go meanwhile, nor for a picture not animated
    @Test
    fun theFramesAreDecodedOffTheMainThread() {
        val io = Executors.newSingleThreadExecutor()
        try {
            val p = picture(folder.newFile("a.webp"), true)
            val decodedOn = mutableListOf<Boolean>()
            val decode = { _: Picture, w: Int, h: Int -> decodedOn += Looper.myLooper() == Looper.getMainLooper(); assertEquals(400, w); assertEquals(100, h); FakeFrames() }
            fun load(base: View, picture: Picture): MutableList<AnimatedBoardDrawable> {
                val got = mutableListOf<AnimatedBoardDrawable>()
                AnimatedBoardDrawable.later(picture, 0, base, scope, io.asCoroutineDispatcher(), decode) { got += it }
                return got
            }
            val base = shown(ColorDrawable(0))
            val got = load(base, p)
            assertTrue(load(base, picture(File(p.src), false)).isEmpty())
            waitFor { got.isNotEmpty() }
            assertEquals(listOf(false), decodedOn)
            // a keyboard let go before its frames came: none handed over
            val gone = shown(ColorDrawable(0))
            (gone.parent as FrameLayout).removeView(gone)
            gone.layout(0, 0, 400, 100)
            val none = load(gone, p)
            waitFor { decodedOn.size == 2 }
            repeat(3) { idle() }
            assertTrue(none.isEmpty())
        } finally {
            io.shutdown()
        }
    }

    private val scope = CoroutineScope(SupervisorJob())

    private fun waitFor(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!done()) {
            assertTrue("in time", System.currentTimeMillis() < until)
            idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun playedItIsShownAtOnceAndLetGoWithItsView() {
        val view = shown(ColorDrawable(0)) as PictureBaseView
        val frames = FakeFrames()
        val board = AnimatedBoardDrawable(frames, 100, appContext)
        view.play(board)
        assertTrue(board.isRunning)
        (view.parent as FrameLayout).removeView(view)
        assertFalse(board.isRunning)
        assertNull(view.background)
    }
}
