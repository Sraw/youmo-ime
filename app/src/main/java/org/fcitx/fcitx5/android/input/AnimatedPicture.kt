/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.Display
import android.view.View
import androidx.annotation.WorkerThread
import androidx.core.view.doOnLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyMotionSwitch
import org.fcitx.fcitx5.android.input.theme.PictureWindow
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.look.Picture
import timber.log.Timber
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/** The view under the keyboard: tells its [AnimatedBoardDrawable] background whether it is shown. */
class PictureBaseView(context: Context) : View(context) {
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        (background as? AnimatedBoardDrawable)?.shown(isVisible)
    }

    // the screen off with the keyboard up (no keyguard): the window stays visible, but RenderThread would go on
    // decoding frames nobody sees
    override fun onScreenStateChanged(screenState: Int) {
        super.onScreenStateChanged(screenState)
        (background as? AnimatedBoardDrawable)?.screen(screenState == SCREEN_STATE_ON)
    }

    /** [frames] put under it once decoded (the still picture till then), playing at once where it is shown. */
    fun play(frames: AnimatedBoardDrawable) {
        background = frames
        // decoded with the screen gone off already: no screen event comes till it is on again
        // the display's state, as onScreenStateChanged reads it (not isInteractive: false in doze, whose wake sends no event)
        frames.screen(display?.state != Display.STATE_OFF)
        frames.shown(isAttachedToWindow && windowVisibility == VISIBLE && isShown)
    }

    // the frames and their decoder let go with the view (a theme changed, the keyboard made again), not
    // left to the GC while the next keyboard decodes its own (stopped already: its visibility told)
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (background is AnimatedBoardDrawable) background = null
    }
}

/**
 * A theme's animated picture under the keyboard (060-render-p3 A4): [frames] (an [AnimatedImageDrawable],
 * which RenderThread steps on its own: no frame of the main thread's) laid as CENTER_CROP lays it, at
 * [brightness] percent (black over it). It plays only while it is shown and [allowed] (键盘动效 on, battery
 * saver and the system's animations not off): its view's aggregated visibility ([PictureBaseView]: the keyboard's
 * window hidden, the view detached as a theme changes) stops it, a switch turned while shown stops or starts it at once.
 */
class AnimatedBoardDrawable(
    private val frames: Drawable,
    val brightness: Int,
    private val context: Context,
    board: Int = 0,
    private val allowed: () -> Boolean = { KeyMotionSwitch.on(context) },
) : Drawable(), Drawable.Callback {

    private val play = frames as Animatable
    private val dark = Paint().apply { color = android.graphics.Color.argb((100 - brightness) * 255 / 100, 0, 0, 0) }
    private var listening = false

    // under the frames: an animated WebP may have alpha (the still picture's drawable has the board's colour too)
    private val under = Paint().apply { color = board }

    init {
        frames.callback = this
    }

    // held here: the prefs keep their listeners weakly
    private val changed = { update() }
    private val prefChanged = ManagedPreference.OnChangeListener<Boolean> { _, _ -> update() }

    /** Whether it plays now. */
    val isRunning: Boolean get() = play.isRunning

    // whether its view is shown ([PictureBaseView]); a drawable's own visibility is told only as it changes,
    // and a view attached in a shown window never tells it (a drawable starts visible), so it is not used
    private var shown = false

    /** Its view shown or not: the window shown or hidden, the view attached or let go (a theme changed). */
    fun shown(visible: Boolean) {
        shown = visible
        listen(visible)
        update()
    }

    private var screenOn = true

    /** The screen on or off (its view's [View.onScreenStateChanged]). */
    fun screen(on: Boolean) {
        screenOn = on
        update()
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        val prefs = AppPrefs.getInstance().advanced
        if (on) {
            KeyMotionSwitch.listen(context, changed)
            prefs.keyboardMotion.registerOnChangeListener(prefChanged)
            prefs.disableAnimation.registerOnChangeListener(prefChanged)
        } else {
            KeyMotionSwitch.unlisten(changed)
            prefs.keyboardMotion.unregisterOnChangeListener(prefChanged)
            prefs.disableAnimation.unregisterOnChangeListener(prefChanged)
        }
    }

    private fun update() {
        val run = shown && screenOn && allowed()
        if (run && !play.isRunning) play.start() else if (!run && play.isRunning) play.stop()
    }

    // where the frames go: their own size, placed and scaled by the canvas. Not by their bounds: API 29's
    // AnimatedImageDrawable draws at its own size from the canvas's origin whatever its bounds (T10 on
    // youmo29: a 480 px animation in the board's top-left corner), API 31+ honours them
    private var dx = 0f
    private var dy = 0f
    private var scale = 1f

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = frames.intrinsicWidth
        val h = frames.intrinsicHeight
        if (w <= 0 || h <= 0 || bounds.isEmpty) return
        val p = PictureWindow.centerCrop(w, h, bounds.width().toFloat(), bounds.height().toFloat())
        dx = bounds.left + p.dx
        dy = bounds.top + p.dy
        scale = p.scale
        frames.setBounds(0, 0, w, h)
    }

    override fun draw(canvas: Canvas) {
        if (under.alpha != 0) canvas.drawRect(bounds, under)
        canvas.save()
        canvas.clipRect(bounds)
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        frames.draw(canvas)
        canvas.restore()
        if (brightness < 100) canvas.drawRect(bounds, dark)
    }

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)

    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * Decodes [picture]'s frames off the main thread once [base] is laid out (its size the frames' target),
         * then, on the main thread, hands them to [ready] where [base] is still attached ([scope] is the
         * keyboard's: cancelled as it is let go, so nothing lands on a keyboard made again). Nothing for a
         * picture not animated, past [ThemeAssetLimits]' animated limits or not decoded: the still picture stays.
         */
        fun later(
            picture: Picture,
            board: Int,
            base: View,
            scope: CoroutineScope,
            io: CoroutineContext = Dispatchers.IO,
            decode: (Picture, Int, Int) -> Drawable? = ::decode,
            ready: (AnimatedBoardDrawable) -> Unit,
        ) {
            if (!picture.animated) return
            base.doOnLayout {
                val w = base.width
                val h = base.height
                scope.launch(io) {
                    val frames = decode(picture, w, h) ?: return@launch
                    withContext(Dispatchers.Main) {
                        if (base.isAttachedToWindow) ready(AnimatedBoardDrawable(frames, picture.brightness, base.context, board))
                    }
                }
            }
        }

        /**
         * [picture]'s frames, sized to cover [w]×[h] (no larger than the file's), or null where it is drawn
         * still. Reads and decodes a file of up to 4 MB: never on the main thread.
         */
        @WorkerThread
        fun decode(picture: Picture, w: Int, h: Int): Drawable? {
            val file = File(picture.src)
            val still = ThemeAssetLimits.animated(file)
            if (still != null) {
                Timber.w("animated picture ${file.name} drawn still: $still")
                return null
            }
            return runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val iw = info.size.width
                    val ih = info.size.height
                    // what the decoder reads, not only what the header said
                    check(maxOf(iw, ih) <= ThemeAssetLimits.MAX_ANIMATED_SIDE) { "${iw}x$ih past the limit" }
                    val scale = if (w > 0 && h > 0) minOf(1f, maxOf(w.toFloat() / iw, h.toFloat() / ih)) else 1f
                    if (scale < 1f) decoder.setTargetSize(maxOf(1, (iw * scale).roundToInt()), maxOf(1, (ih * scale).roundToInt()))
                }
            }.onFailure { Timber.w(it, "animated picture ${file.name} drawn still: not decoded") }
                .getOrNull() as? AnimatedImageDrawable
        }
    }
}
