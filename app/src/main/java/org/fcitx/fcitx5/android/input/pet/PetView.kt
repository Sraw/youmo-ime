/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyMotionSwitch
import org.fcitx.fcitx5.android.scene.pet.Pet
import org.fcitx.fcitx5.android.scene.pet.PetMachine
import org.fcitx.fcitx5.android.scene.pet.PetTrigger
import kotlin.math.floor

/**
 * The pet over the keys, in a box of [sizePx] that [place] puts; touches pass through to the keys. One bitmap drawn
 * a frame, redrawn only when the frame changes, and no timer where nothing moves (motion off, a still idle, asleep).
 */
@SuppressLint("ViewConstructor")
class PetView(context: Context, private val pet: Pet, val sizePx: Int, private val budget: Long) : View(context) {

    private var frames: PetFrames? = null
    private var machine: PetMachine? = null
    private var loading = false
    // a sheet that would not decode stays so: not read again at each show
    private var failed = false
    private var screenOn = true
    private var motion = false
    private var showing = true
    // as dispatched: windowVisibility reads the window, which a view hidden with its parent does not change
    private var windowShown = false
    /** The frame drawn, as [PetMachine.frame] says it. */
    @VisibleForTesting
    internal var frame = 0
        private set

    private val box = Rect()
    private val src = Rect()
    private val dst = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    @VisibleForTesting
    internal val tick = Runnable { advance() }
    @VisibleForTesting
    internal val changed = { motionChanged() }
    @VisibleForTesting
    internal val prefChanged = ManagedPreference.OnChangeListener<Boolean> { _, _ -> motionChanged() }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val active: Boolean get() = showing && windowShown && screenOn && isAttachedToWindow

    /** Where its box's top-left corner is in the parent, which lays this view out at its own top-left. */
    fun place(left: Int, top: Int) {
        if (box.left == left && box.top == top && !box.isEmpty) return
        box.set(left, top, left + sizePx, top + sizePx)
        // moved, not laid out: a frame then redraws this box only, not the keys under it
        translationX = left.toFloat()
        translationY = top.toFloat()
    }

    @VisibleForTesting
    internal val placed: Rect get() = box

    /** Hidden over another window than the keys': stopped; back, idle from its first frame. */
    fun show(on: Boolean) {
        if (showing == on) return
        showing = on
        // not GONE: that would lay the keyboard out again
        visibility = if (on) VISIBLE else INVISIBLE
        if (on) start() else stop()
    }

    fun on(trigger: PetTrigger) {
        val m = machine ?: return
        if (!active) return
        // asked each key, so a switch turned off takes at once
        if (!KeyMotionSwitch.on(context)) {
            if (motion) motionChanged()
            return
        }
        if (!motion) {
            // back on with no callback (the system's animator scale): started here, and this key plays too
            start()
            if (!motion) return
        }
        m.on(trigger, SystemClock.uptimeMillis())
        advance()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val prefs = AppPrefs.getInstance().advanced
        KeyMotionSwitch.listen(context, changed)
        prefs.keyboardMotion.registerOnChangeListener(prefChanged)
        prefs.disableAnimation.registerOnChangeListener(prefChanged)
    }

    override fun onDetachedFromWindow() {
        val prefs = AppPrefs.getInstance().advanced
        KeyMotionSwitch.unlisten(changed)
        prefs.keyboardMotion.unregisterOnChangeListener(prefChanged)
        prefs.disableAnimation.unregisterOnChangeListener(prefChanged)
        windowShown = false
        removeCallbacks(tick)
        frames?.release()
        frames = null
        machine = null
        super.onDetachedFromWindow()
    }

    // its own box only, pinned top-left and moved there: as big as the keys, each frame would redraw them all; set as it is
    // added, since one changed inside a layout pass is not read again by ConstraintLayout
    override fun setLayoutParams(params: ViewGroup.LayoutParams) {
        params.width = sizePx
        params.height = sizePx
        if (params is ConstraintLayout.LayoutParams) {
            params.rightToRight = ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        }
        super.setLayoutParams(params)
    }

    override fun onScreenStateChanged(screenState: Int) {
        super.onScreenStateChanged(screenState)
        screenOn = screenState == SCREEN_STATE_ON
        if (active) start() else stop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowShown = visibility == VISIBLE
        if (active) start() else stop()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        val sheet = frames?.sheet(PetMachine.stateOf(frame)) ?: return
        val i = PetMachine.frameOf(frame)
        val x = i % sheet.columns * sheet.frameWidth
        val y = i / sheet.columns * sheet.frameHeight
        src.set(x, y, x + sheet.frameWidth, y + sheet.frameHeight)
        // the frame's own shape, as large as the box allows, at its middle; twice or more a whole number of times, unsmoothed,
        // so pixel art stays sharp
        val fit = sizePx.toFloat() / maxOf(sheet.frameWidth, sheet.frameHeight)
        val scale = if (fit >= 2f) floor(fit) else fit
        paint.isFilterBitmap = fit < 2f
        val w = (sheet.frameWidth * scale).toInt()
        val h = (sheet.frameHeight * scale).toInt()
        val c = sizePx / 2
        dst.set(c - w / 2, c - h / 2, c - w / 2 + w, c - h / 2 + h)
        canvas.drawBitmap(sheet.bitmap, src, dst, paint)
    }

    private fun start() {
        if (!active || failed) return
        val m = machine ?: return load()
        motion = KeyMotionSwitch.on(context)
        if (motion) m.shown(SystemClock.uptimeMillis()) else m.hidden()
        frame = 0
        invalidate()
        schedule(SystemClock.uptimeMillis())
    }

    private fun stop() {
        removeCallbacks(tick)
        machine?.hidden()
    }

    private fun load() {
        if (loading) return
        loading = true
        PetFrames.load(pet, sizePx, budget) { loaded ->
            loading = false
            failed = loaded == null
            if (!isAttachedToWindow || loaded == null) return@load run { loaded?.release() }
            frames = loaded
            machine = PetMachine(loaded.pet)
            start()
        }
    }

    private fun motionChanged() {
        if (active) start()
    }

    private fun advance() {
        val m = machine ?: return
        if (!active) return
        if (!KeyMotionSwitch.on(context)) return motionChanged()
        val now = SystemClock.uptimeMillis()
        val f = m.frame(now)
        if (f != frame) {
            frame = f
            invalidate()
        }
        schedule(now)
    }

    private fun schedule(now: Long) {
        removeCallbacks(tick)
        val next = machine?.takeIf { motion }?.nextFrameAtMs(now) ?: return
        if (next != PetMachine.NONE) postDelayed(tick, next - now)
    }
}
