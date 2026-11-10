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
import android.view.View
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyMotionSwitch
import org.fcitx.fcitx5.android.input.theme.AnimatedWebp
import org.fcitx.fcitx5.android.input.theme.PictureWindow
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.look.Picture
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

/** The view under the keyboard: tells its [AnimatedBoardDrawable] background whether it is shown. */
class PictureBaseView(context: Context) : View(context) {
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        (background as? AnimatedBoardDrawable)?.shown(isVisible)
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
    private val allowed: () -> Boolean = { KeyMotionSwitch.on(context) },
) : Drawable(), Drawable.Callback {

    private val play = frames as Animatable
    private val dark = Paint().apply { color = android.graphics.Color.argb((100 - brightness) * 255 / 100, 0, 0, 0) }
    private var listening = false

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
        val run = shown && allowed()
        if (run && !play.isRunning) play.start() else if (!run && play.isRunning) play.stop()
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = frames.intrinsicWidth
        val h = frames.intrinsicHeight
        if (w <= 0 || h <= 0 || bounds.isEmpty) return
        val p = PictureWindow.centerCrop(w, h, bounds.width().toFloat(), bounds.height().toFloat())
        val left = bounds.left + p.dx.toInt()
        val top = bounds.top + p.dy.toInt()
        frames.setBounds(left, top, left + (w * p.scale).toInt() + 1, top + (h * p.scale).toInt() + 1)
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(bounds)
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
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        /**
         * [picture]'s frames to play, or null where it is drawn still (its cut, as a picture that does not
         * move): none animated, past [ThemeAssetLimits]' animated limits, or not decoded as an animation.
         * Only the file's chunk heads are read before it is decoded.
         */
        fun of(picture: Picture, context: Context): AnimatedBoardDrawable? {
            if (!picture.animated) return null
            val file = File(picture.src)
            val still = runCatching { limits(file) }.getOrElse { ThemeAssetLimits.Still.Unread }
            if (still != null) {
                Timber.w("animated picture ${file.name} drawn still: $still")
                return null
            }
            val frames = runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) }
                .onFailure { Timber.w(it, "animated picture ${file.name} drawn still: not decoded") }
                .getOrNull() as? AnimatedImageDrawable ?: return null
            return AnimatedBoardDrawable(frames, picture.brightness, context)
        }

        /** Why [file] is drawn still, null where it may play. */
        fun limits(file: File): ThemeAssetLimits.Still? {
            val bytes = file.length()
            if (bytes > ThemeAssetLimits.MAX_ANIMATED_BYTES) return ThemeAssetLimits.Still.Size
            val header = RandomAccessFile(file, "r").use { f ->
                AnimatedWebp.header({ at, into ->
                    f.seek(at)
                    f.read(into).coerceAtLeast(0)
                }, bytes, ThemeAssetLimits.MAX_ANIMATED_FRAMES)
            }
            return ThemeAssetLimits.animated(bytes, header)
        }
    }
}
