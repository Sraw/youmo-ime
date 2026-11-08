/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.Theme
import android.view.MotionEvent
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import splitties.dimensions.dp
import timber.log.Timber
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class NumberRow(ctx: Context, theme: Theme) : BaseKeyboard(ctx, theme, Layout) {

    private var gestureStartEvent: MotionEvent? = null
    private var collapseGestureTriggered: Boolean = false

    // a swipe this far collapses the row: read once, not at each move (the bar height is a preference)
    private val collapseDistance = dp(KawaiiBarComponent.heightDp())

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
                Timber.d("NumberRow: intercepted gesture from child keyboard to handle swipe")
                resetState()
                collapseGestureTriggered = true
                return true
            }
        }
        return false
    }

    private fun resetState() {
        gestureStartEvent?.recycle()
        gestureStartEvent = null
        collapseGestureTriggered = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestureStartEvent = MotionEvent.obtain(ev)
            MotionEvent.ACTION_MOVE -> {
                if (checkGesture(ev)) return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> resetState()
        }
        return super.onInterceptTouchEvent(ev)
    }

    // the keys drawn by the scene take every touch here (BaseKeyboard routes them to the scene's keys,
    // SceneTouch): the swipe that collapses the row is told apart first, before the keys see the move,
    // and the key under the finger let go of (a cancel: nothing typed) as a view's was when this intercepted
    private var collapsing = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawnByScene) return sceneTouch(event)
        var handled = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestureStartEvent = MotionEvent.obtain(event)
            MotionEvent.ACTION_MOVE -> checkGesture(event)
            MotionEvent.ACTION_UP -> {
                if (collapseGestureTriggered) {
                    resetState()
                    onCollapseListener?.invoke()
                    handled = true
                }
            }
            MotionEvent.ACTION_CANCEL -> resetState()
        }
        return super.onTouchEvent(event) || handled
    }

    private val drawnByScene get() = canvas != null

    private fun sceneTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetState()
                collapsing = false
                gestureStartEvent = MotionEvent.obtain(event)
            }
            MotionEvent.ACTION_MOVE -> if (!collapsing && checkGesture(event)) {
                collapsing = true
                // the keys' touch ends as the system's cancel ends a view's
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
        // the swipe taken: the rest of its touch is the row's
        return if (collapsing) true else super.onTouchEvent(event)
    }

    // laid on the bar, no keyboard's fill under the digits (as the views drew none)
    override val sceneDrawsBase: Boolean get() = false

    // no marks: the row's keys reach no shortcut
    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env =
        SceneEnvs.of(this, look, theme.isDark, width, height, Input(), marks = MarkSettings(shortcuts = emptyMap()))

    // last: the scene reads the keyboard's state
    init {
        drawKeysWithScene(LAYOUT)
    }

    companion object {
        /** The row drawn by the scene where the canvas is on (060-render-p2 S6). */
        const val LAYOUT = "number-row"

        val Layout = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { digit ->
                KeyDef(
                    KeyDef.Appearance.Text(
                        displayText = digit,
                        textSize = 21f,
                        border = KeyDef.Appearance.Border.Off,
                        margin = false
                    ),
                    setOf(
                        KeyDef.Behavior.Press(KeyAction.SymAction(KeySym(digit.codePointAt(0))))
                    ),
                    arrayOf(KeyDef.Popup.Preview(digit))
                )
            }
        )
    }
}
