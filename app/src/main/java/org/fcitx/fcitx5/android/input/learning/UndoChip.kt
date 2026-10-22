/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.learning

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.PopupWindow
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.input.UndoChipPlacement
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.gravityCenterVertical
import splitties.views.setPaddingDp

/**
 * What a long press's act did, said on the candidate bar for a moment, with 撤销 to take it back
 * (U-82: 当场都能撤销). A window of its own, not focusable, where [UndoChipPlacement] says: over the
 * top of [anchor], or above [above] when that would hide candidates. The editor
 * keeps the focus and what is composing in it. An act with nothing to take back
 * ([LearningResult.NO_UNDO]) is said alone. Only the last act can be taken back, and only while
 * nothing else was learned after it: the engine says so (Unavailable) and so does the chip then.
 */
class UndoChip(
    private val anchor: View,
    private val theme: Theme,
    private val scope: CoroutineScope,
    private val fcitx: FcitxConnection,
    /** The view the chip goes above when it would hide candidates over [anchor]. */
    private val above: () -> View = { anchor },
    /** Whether [anchor] shows candidates now, which the chip is not to cover. */
    private val candidates: () -> Boolean,
    /** Whether there is room below [above] to go to when there is none above it. */
    private val belowOk: Boolean,
    /** The lowest window y the chip may reach: above the navigation bar, in the window's pixels. */
    private val bottomLimit: () -> Int = { anchor.rootView.height },
) {
    private var window: PopupWindow? = null

    private val dismiss = Runnable { dismiss() }

    fun show(message: String, token: Long) {
        dismiss()
        if (!anchor.isAttachedToWindow) return
        val ctx = anchor.context
        val text = ctx.textView {
            text = message
            setTextColor(theme.popupTextColor)
            textSize = 14f
            setPaddingDp(16, 10, 8, 10)
        }
        val undo = ctx.textView {
            setText(R.string.learning_undo)
            setTextColor(theme.genericActiveForegroundColor)
            setBackgroundColor(theme.genericActiveBackgroundColor)
            textSize = 14f
            setPaddingDp(12, 6, 12, 6)
            // a button to a screen reader, and one a finger finds (review-E-ui S7)
            gravity = gravityCenter
            minHeight = dp(MIN_TOUCH_DP)
            minWidth = dp(MIN_TOUCH_DP)
            accessibilityDelegate = ButtonRole
            setOnClickListener { undo(token) }
        }
        val chip = ctx.horizontalLayout {
            gravity = gravityCenterVertical
            background = GradientDrawable().apply {
                setColor(theme.popupBackgroundColor)
                cornerRadius = dp(18).toFloat()
            }
            add(text, lParams(wrapContent, wrapContent))
            if (token != LearningResult.NO_UNDO) add(undo, lParams(wrapContent, wrapContent) { marginEnd = dp(8) })
        }
        val bar = IntArray(2).also { anchor.getLocationInWindow(it) }
        val over = above()
        val aboveAt = IntArray(2).also { over.getLocationInWindow(it) }
        chip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val y = UndoChipPlacement.top(
            candidates = candidates(),
            barTop = bar[1],
            aboveTop = aboveAt[1],
            chipHeight = chip.measuredHeight,
            gap = anchor.dp(4),
            belowBottom = if (belowOk) aboveAt[1] + over.height else null,
            // off the bottom PopupWindow would clip it back over the keys
            bottomLimit = bottomLimit(),
        )
        window = PopupWindow(chip, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = true
            showAtLocation(anchor, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, y)
        }
        // said aloud as a pane that appears, the window no focus a screen reader follows; as long as its user needs
        ViewCompat.setAccessibilityPaneTitle(chip, message)
        val shown = ctx.getSystemService(AccessibilityManager::class.java)?.let { a ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                a.getRecommendedTimeoutMillis(SHOWN_MS.toInt(), AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong()
            } else {
                null
            }
        } ?: SHOWN_MS
        anchor.postDelayed(dismiss, shown)
    }

    // a TextView read as a button
    private object ButtonRole : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    private fun undo(token: Long) {
        dismiss()
        scope.launch {
            val undone = fcitx.runOnReady { learning(LearningOp.Undo(token)) }
            when (undone) {
                LearningResult.Unavailable -> anchor.context.toast(R.string.learning_undo_gone)
                // a disk that failed: said, as 清掉最近 says it, not the chip just gone
                LearningResult.Failed -> anchor.context.toast(R.string.learning_try_again)
                else -> {}
            }
        }
    }

    fun dismiss() {
        anchor.removeCallbacks(dismiss)
        window?.dismiss()
        window = null
    }

    private companion object {
        // long enough to read a few words and reach for it, short enough not to sit over the candidates
        const val SHOWN_MS = 6000L

        // the smallest a target is to be touched surely (Material's accessibility guidance)
        const val MIN_TOUCH_DP = 48
    }
}
