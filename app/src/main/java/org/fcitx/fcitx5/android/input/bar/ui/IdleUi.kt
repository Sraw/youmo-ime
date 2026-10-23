/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.ProgressBar
import android.widget.Space
import android.widget.ViewAnimator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
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
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.imageResource
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

    val buttonsUi = ButtonsBarUi(ctx, theme)

    val clipboardUi = ClipboardSuggestionUi(ctx, theme)

    val calculationUi = CalculationUi(ctx, theme)

    val numberRow = NumberRow(ctx, theme).apply {
        visibility = View.GONE
    }

    val inlineSuggestionsBar = InlineSuggestionsUi(ctx)

    private val animator = ViewAnimator(ctx).apply {
        add(emptyBar, lParams(matchParent, matchParent))
        add(buttonsUi.root, lParams(matchParent, matchParent))
        add(clipboardUi.root, lParams(matchParent, matchParent))
        add(inlineSuggestionsBar.root, lParams(matchParent, matchParent))
        add(calculationUi.root, lParams(matchParent, matchParent))
    }

    private val workText = textView {
        isSingleLine = true
        textSize = 14f
        setTextColor(theme.altKeyTextColor)
        gravity = Gravity.CENTER
    }

    private val workBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
    }

    // a long job the keyboard waits on without stopping typing (the dictionaries' format upgraded),
    // said where the bar is idle, with a thin line of how far
    private val workUi = frameLayout {
        visibility = View.GONE
        add(workText, lParams(matchParent, matchParent))
        add(workBar, lParams(matchParent, dp(WORK_BAR_DP)) { gravity = Gravity.BOTTOM })
    }

    private var workShown: String? = null

    /**
     * [text] said over the idle bar at [percent], or nothing (null): the bar as it was. Said only
     * while the bar is empty: the toolbar, a clipboard or autofill suggestion keep their place,
     * the thin line alone telling of the work then (review S4).
     */
    fun showWork(text: String?, percent: Int) {
        workShown = text
        workBar.progress = percent
        layOutWork()
    }

    private fun layOutWork() {
        val text = workShown
        val alone = text != null && currentState == Display.Empty
        workUi.visibility = if (text == null) View.GONE else View.VISIBLE
        workText.visibility = if (alone) View.VISIBLE else View.INVISIBLE
        animator.visibility = if (alone) View.INVISIBLE else View.VISIBLE
        if (text != null) workText.text = text
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
        add(animator, lParams(matchConstraints, matchParent) {
            after(menuButton)
            before(emojiButton)
            centerVertically()
        })
        add(workUi, lParams(matchConstraints, matchParent) {
            after(menuButton)
            before(emojiButton)
            centerVertically()
        })
    }

    override val root = frameLayout {
        add(idleBody, lParams(matchParent, matchParent))
        add(numberRow, lParams(matchParent, matchParent))
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
                enableSlideTransition(numberRow, idleBody, Gravity.END, Gravity.START)
            }
            numberRow.visibility = View.VISIBLE
            idleBody.visibility = View.GONE
        } else if (currentState == Display.NumberRow) {
            if (fromUser && !disableAnimation) {
                enableSlideTransition(idleBody, numberRow, Gravity.START, Gravity.END)
            }
            idleBody.visibility = View.VISIBLE
            numberRow.visibility = View.GONE
            numberRow.keyActionListener = null
            numberRow.popupActionListener = null
            popup.dismissAll()
        }
        currentState = state
        layOutWork()
        updateMenuButtonContentDescription()
        updateMenuButtonRotation(instant = !fromUser)
    }

    companion object {
        private const val WORK_BAR_DP = 2
    }
}
