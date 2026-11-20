/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import splitties.dimensions.dp
import timber.log.Timber
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class NumberRow(ctx: Context, theme: Theme) : BaseKeyboard(ctx, theme) {

    private var gestureStartEvent: MotionEvent? = null

    // read once, not at each move (the bar height is a preference)
    private val collapseDistance = dp(KawaiiBarComponent.heightDp(ctx))

    var onCollapseListener: (() -> Unit)? = null

    private fun checkGesture(ev: MotionEvent): Boolean {
        val startEvent = gestureStartEvent ?: return false
        val firstPointerId = startEvent.getPointerId(startEvent.actionIndex)
        if (ev.getPointerId(ev.actionIndex) == firstPointerId) {
            val dir = if (context.resources.configuration.layoutDirection == LAYOUT_DIRECTION_LTR) 1 else -1
            val sx = startEvent.x * dir
            val cx = ev.getX(ev.actionIndex) * dir
            val shouldCollapse = cx > sx && abs(cx - sx) > collapseDistance
            if (shouldCollapse) {
                Timber.d("NumberRow: swipe to collapse")
                resetState()
                return true
            }
        }
        return false
    }

    private fun resetState() {
        gestureStartEvent?.recycle()
        gestureStartEvent = null
    }

    // the scene's keys take every touch here: the collapsing swipe is told apart before the keys see the move,
    // and the key under the finger let go of (a cancel: nothing typed)
    private var collapsing = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetState()
                collapsing = false
                gestureStartEvent = MotionEvent.obtain(event)
            }
            MotionEvent.ACTION_MOVE -> if (!collapsing && checkGesture(event)) {
                collapsing = true
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancel)
                cancel.recycle()
                return true
            }
            MotionEvent.ACTION_UP -> if (collapsing) {
                collapsing = false
                resetState()
                onCollapseListener?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val was = collapsing
                collapsing = false
                resetState()
                if (was) return true
            }
        }
        return if (collapsing) true else super.onTouchEvent(event)
    }

    override val sceneDrawsBase: Boolean get() = false

    // a bar's, not resized with a floating keyboard
    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env =
        SceneEnvs.of(this, look, theme.isDark, width, height, Input(), marks = SceneEnvs.NO_MARKS, resized = false)

    // last: the scene reads the keyboard's state
    init {
        drawKeysWithScene(LAYOUT)
    }

    companion object {
        const val LAYOUT = "number-row"
    }
}
