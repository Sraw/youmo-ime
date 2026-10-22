/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.Space
import android.widget.ViewAnimator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.LearningMarker
import org.fcitx.fcitx5.android.input.bar.IdleUiPolicy.Display
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.idle.ButtonsBarUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.CalculationUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.ClipboardSuggestionUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.InlineSuggestionsUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.gravityCenterVertical
import splitties.views.imageResource
import splitties.views.setPaddingDp
import timber.log.Timber

class IdleUi(
    override val ctx: Context,
    private val theme: Theme,
    private val popup: PopupComponent,
    private val commonKeyActionListener: CommonKeyActionListener
) : Ui {

    var currentState = Display.Empty
        private set

    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private val translateDirection by lazy {
        if (ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1f else -1f
    }

    private val menuButtonRotation
        get() = when {
            currentState == Display.Toolbar -> 90f * translateDirection
            else -> -90f * translateDirection
        }

    val menuButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        iconRotation = menuButtonRotation
    }

    init {
        // updateState keeps this current, but is not called for the initial state; without
        // this the button starts out unlabelled
        updateMenuButtonContentDescription()
    }

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_drop_down_24, theme).apply {
        contentDescription = ctx.getString(R.string.hide_keyboard)
    }

    /**
     * The emoji, on the bar whenever it is idle, as Sogou's 表情: a long press on the comma was
     * all there was, and nobody finds that. Opens the emoji or kaomoji, whichever was last.
     */
    val emojiButton = ToolButton(ctx, R.drawable.ic_baseline_tag_faces_24, theme).apply {
        contentDescription = ctx.getString(R.string.a11y_key_emoji)
        setOnClickListener {
            commonKeyActionListener.listener.onKeyAction(KeyAction.PickerSwitchAction(), KeyActionListener.Source.Keyboard)
        }
    }

    /** Voice input, in a voice build only (VoiceFeature): beside the emoji, as on Sogou's bar */
    val voiceButton = ToolButton(ctx, R.drawable.ic_baseline_keyboard_voice_24, theme).apply {
        contentDescription = ctx.getString(R.string.voice_input)
        visibility = View.GONE
    }

    val emptyBar = Space(ctx)

    /**
     * 这里不学 or 已不用我的输入习惯 (U-87, U-93; [LearningMarker]) beside the menu button. Tapped,
     * it says what it means and nothing more: whoever was lent the phone is not led to turn it back on.
     */
    private val learningMarker = markerView()

    // the same mark before the number row, which a password field shows in the bar's place: there
    // above all U-87 says 这里不学 (UAT PR-20)
    private val rowMarker = markerView()

    private fun markerView() = ctx.textView {
        visibility = View.GONE
        textSize = 12f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.altKeyTextColor)
        gravity = gravityCenter
        // the bar's height to touch, the tag drawn as small as it reads (review-E-ui S7); never so
        // wide the toolbar's buttons are crushed: the tap says it whole (S9)
        background = InsetDrawable(
            GradientDrawable().apply {
                setColor(theme.altKeyBackgroundColor)
                cornerRadius = dp(10).toFloat()
            },
            0, dp(MARKER_INSET), 0, dp(MARKER_INSET),
        )
        minWidth = dp(MIN_TOUCH)
        maxWidth = ctx.resources.displayMetrics.widthPixels * 2 / 5
        setPaddingDp(8, 0, 8, 0)
    }

    fun learningMarker(marker: LearningMarker?) {
        listOf(learningMarker, rowMarker).forEach { it.visibility = if (marker == null) View.GONE else View.VISIBLE }
        // its label and what a tap says, together: a new marker names both here
        val (label, said) = when (marker) {
            null -> return
            LearningMarker.NOT_HERE -> R.string.learning_not_here to R.string.learning_not_here_explain
            LearningMarker.HABITS_OFF -> R.string.learning_habits_off_marker to R.string.learning_habits_off_marker
        }
        listOf(learningMarker, rowMarker).forEach {
            it.setText(label)
            it.contentDescription = ctx.getString(said)
            it.setOnClickListener { ctx.toast(said) }
        }
    }

    val buttonsUi = ButtonsBarUi(ctx, theme)

    val clipboardUi = ClipboardSuggestionUi(ctx, theme)

    val calculationUi = CalculationUi(ctx, theme)

    val numberRow = NumberRow(ctx, theme)

    private val numberRowBox = ctx.horizontalLayout {
        visibility = View.GONE
        add(rowMarker, lParams(wrapContent, matchParent) { gravity = gravityCenterVertical })
        add(numberRow, lParams(0, matchParent) { weight = 1f })
    }

    val inlineSuggestionsBar = InlineSuggestionsUi(ctx)

    private val animator = ViewAnimator(ctx).apply {
        add(emptyBar, lParams(matchParent, matchParent))
        add(buttonsUi.root, lParams(matchParent, matchParent))
        add(clipboardUi.root, lParams(matchParent, matchParent))
        add(inlineSuggestionsBar.root, lParams(matchParent, matchParent))
        add(calculationUi.root, lParams(matchParent, matchParent))
    }

    private val inAnimation by lazy {
        AnimationSet(true).apply {
            duration = 200L
            addAnimation(AlphaAnimation(0f, 1f))
            // 2 stands for Animation.RELATIVE_TO_PARENT
            addAnimation(TranslateAnimation(2, -0.3f * translateDirection, 2, 0f, 0, 0f, 0, 0f))
        }
    }

    private val outAnimation by lazy {
        AnimationSet(true).apply {
            duration = 200L
            addAnimation(AlphaAnimation(1f, 0f))
            addAnimation(TranslateAnimation(2, 0f, 2, -0.3f * translateDirection, 0, 0f, 0, 0f))
        }
    }

    private val idleBody = constraintLayout {
        val size = dp(KawaiiBarComponent.HEIGHT)
        add(menuButton, lParams(size, size) {
            startOfParent()
            centerVertically()
        })
        add(hideKeyboardButton, lParams(size, size) {
            endOfParent()
            centerVertically()
        })
        add(voiceButton, lParams(size, size) {
            before(hideKeyboardButton)
            centerVertically()
        })
        add(emojiButton, lParams(size, size) {
            before(voiceButton)
            centerVertically()
        })
        add(learningMarker, lParams(wrapContent, matchParent) {
            after(menuButton)
            centerVertically()
            before(emojiButton)
            constrainedWidth = true
            horizontalBias = 0f
        })
        add(animator, lParams(matchConstraints, matchParent) {
            after(learningMarker)
            before(emojiButton)
            centerVertically()
        })
    }

    override val root = frameLayout {
        add(idleBody, lParams(matchParent, matchParent))
        add(numberRowBox, lParams(matchParent, matchParent))
    }

    private fun updateMenuButtonContentDescription() {
        menuButton.contentDescription = when {
            currentState == Display.Toolbar -> ctx.getString(R.string.hide_toolbar)
            else -> ctx.getString(R.string.expand_toolbar)
        }
    }

    private fun updateMenuButtonRotation(instant: Boolean = false) {
        val targetRotation = menuButtonRotation
        menuButton.apply {
            if (targetRotation == iconRotation) return
            iconAnimate().cancel()
            if (!instant && !disableAnimation) {
                iconAnimate().setDuration(200L).rotation(targetRotation)
            } else {
                iconRotation = targetRotation
            }
        }
    }

    private fun clearAnimation() {
        animator.inAnimation = null
        animator.outAnimation = null
    }

    private fun setAnimation() {
        animator.inAnimation = inAnimation
        animator.outAnimation = outAnimation
    }

    private fun enableSlideTransition(inTarget: View, outTarget: View, inGravity: Int, outGravity: Int) {
        val slideIn = Slide(inGravity).apply { duration = 200L }
        val slideOut = Slide(outGravity).apply { duration = 200L }
        slideIn.addTarget(inTarget)
        slideOut.addTarget(outTarget)
        val set = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(slideIn)
            addTransition(slideOut)
        }
        TransitionManager.beginDelayedTransition(root, set)
    }

    private fun skipsAnimation(state: Display, fromUser: Boolean) =
        !fromUser || disableAnimation || state.isUnanimated() || currentState.isUnanimated()

    private fun Display.isUnanimated() = this == Display.InlineSuggestion || this == Display.NumberRow

    fun updateState(state: Display, fromUser: Boolean = false) {
        Timber.d("Switch idle ui to $state")
        if (skipsAnimation(state, fromUser)) {
            clearAnimation()
        } else {
            setAnimation()
        }
        when (state) {
            Display.Empty -> animator.displayedChild = 0
            Display.Toolbar -> animator.displayedChild = 1
            Display.Clipboard -> animator.displayedChild = 2
            Display.NumberRow -> {}
            Display.InlineSuggestion -> animator.displayedChild = 3
            Display.Calculation -> animator.displayedChild = 4
        }
        if (state == Display.NumberRow) {
            numberRow.keyActionListener = commonKeyActionListener.listener
            numberRow.popupActionListener = popup.listener
            if (fromUser && !disableAnimation) {
                enableSlideTransition(numberRowBox, idleBody, Gravity.END, Gravity.START)
            }
            numberRowBox.visibility = View.VISIBLE
            idleBody.visibility = View.GONE
        } else if (currentState == Display.NumberRow) {
            if (fromUser && !disableAnimation) {
                enableSlideTransition(idleBody, numberRowBox, Gravity.START, Gravity.END)
            }
            idleBody.visibility = View.VISIBLE
            numberRowBox.visibility = View.GONE
            numberRow.keyActionListener = null
            numberRow.popupActionListener = null
            popup.dismissAll()
        }
        currentState = state
        updateMenuButtonContentDescription()
        updateMenuButtonRotation(instant = !fromUser)
    }

    private companion object {
        // the tag's margin within the bar's height, which all of it takes to be touched
        const val MARKER_INSET = 8
        const val MIN_TOUCH = 48
    }
}
