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
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.ViewAnimator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.IdleUiPolicy.Display
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ToolbarButtonLooks
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig
import org.fcitx.fcitx5.android.input.bar.ui.idle.ButtonsBarUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.CalculationUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.ClipboardSuggestionUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.InlineSuggestionsUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.motion.Motion
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.utils.interpolator
import org.fcitx.fcitx5.android.utils.motionSettings
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
        fixIconSize(ctx.dp(KawaiiBarComponent.ICON_DP))
    }

    init {
        // updateState keeps this current, but is not called for the initial state; without
        // this the button starts out unlabelled
        updateMenuButtonContentDescription()
    }

    /** Every button the user may put on the bar ([ToolbarConfig]), placed by [arrange]. */
    val buttons: Map<ToolbarButton, ToolButton> = ToolbarButton.entries.associateWith { button ->
        val looks = ToolbarButtonLooks.of(button)
        ToolButton(ctx, looks.icon, theme).apply {
            contentDescription = ctx.getString(looks.label)
            fixIconSize(ctx.dp(KawaiiBarComponent.ICON_DP))
        }
    }

    val hideKeyboardButton = buttons.getValue(ToolbarButton.Hide)

    /**
     * The emoji, as Sogou's 表情: a long press on the comma was all there was, and nobody finds
     * that. Opens the emoji or kaomoji, whichever was last.
     */
    val emojiButton = buttons.getValue(ToolbarButton.Emoji).apply {
        setOnClickListener {
            commonKeyActionListener.listener.onKeyAction(KeyAction.PickerSwitchAction(), KeyActionListener.Source.Keyboard)
        }
    }

    /** Voice input, in a voice build only (VoiceFeature): by default beside the emoji, as on Sogou's bar */
    val voiceButton = buttons.getValue(ToolbarButton.Voice)

    /** Whether [button] is where the user sees it: pinned at the right, or on the toolbar while it is out. */
    fun onScreen(button: ToolbarButton): Boolean = buttons.getValue(button).parent.let {
        it === pinnedBar || (it != null && currentState == Display.Toolbar)
    }

    /** A toggle's button drawn lit while on, as its entry in the ⋯ panel is. */
    fun setActive(button: ToolbarButton, active: Boolean) =
        buttons.getValue(button).setIconTint(if (active) theme.accentKeyBackgroundColor else theme.look.palette.toolbarIconColor)

    // the bar's right, after the toolbar: there whenever the bar is idle
    private val pinnedBar = horizontalLayout()

    private var arranged: ToolbarConfig.Layout? = null

    private var layoutFor: ((Int) -> ToolbarConfig.Layout)? = null

    /**
     * How many buttons fit at the right beside the menu key, the middle (a clipboard suggestion,
     * a sum, the password's digits) kept [MIDDLE_BUTTONS] buttons wide: the rest go onto the toolbar.
     */
    private fun roomForPinned(width: Int): Int {
        if (width <= 0) return Int.MAX_VALUE
        val button = ctx.dp(KawaiiBarComponent.buttonWidthDp())
        return (width - button * (1 + MIDDLE_BUTTONS)) / button
    }

    /** The buttons [layout] places given the room at the right, the others nowhere; again as the bar's width changes. */
    fun arrange(layout: (maxPinned: Int) -> ToolbarConfig.Layout) {
        layoutFor = layout
        place(layout(roomForPinned(body.width)))
    }

    private fun place(layout: ToolbarConfig.Layout) {
        if (layout == arranged) return
        arranged = layout
        buttons.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        buttonsUi.show(layout.toolbar.map(buttons::getValue))
        val size = ctx.dp(KawaiiBarComponent.heightDp())
        val buttonWidth = ctx.dp(KawaiiBarComponent.buttonWidthDp())
        layout.pinned.forEach { pinnedBar.addView(buttons.getValue(it), LinearLayout.LayoutParams(buttonWidth, size)) }
    }

    // what the bar shows with nothing else to: the address suggestions (AddressStrip), when there are
    val emptyBar = FrameLayout(ctx)

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

    // their lengths set as each switch starts: animation may have been turned off or on since
    private val inAnimation by lazy {
        AnimationSet(true).apply {
            interpolator = Motion.Curve.Enter.interpolator
            addAnimation(AlphaAnimation(0f, 1f))
            // 2 stands for Animation.RELATIVE_TO_PARENT
            addAnimation(TranslateAnimation(2, -0.3f * translateDirection, 2, 0f, 0, 0f, 0, 0f))
        }
    }

    private val outAnimation by lazy {
        AnimationSet(true).apply {
            interpolator = Motion.Curve.Exit.interpolator
            addAnimation(AlphaAnimation(1f, 0f))
            addAnimation(TranslateAnimation(2, 0f, 2, -0.3f * translateDirection, 0, 0f, 0, 0f))
        }
    }

    private val idleBody = constraintLayout {
        // as tall as the bar; at a small 候选字号 still 40 dp wide, a finger's target
        val size = dp(KawaiiBarComponent.heightDp())
        val buttonWidth = dp(KawaiiBarComponent.buttonWidthDp())
        add(menuButton, lParams(buttonWidth, size) {
            startOfParent()
            centerVertically()
        })
        // never over the menu key, however many there are
        add(pinnedBar, lParams(wrapContent, size) {
            after(menuButton)
            endOfParent()
            horizontalBias = 1f
            constrainedWidth = true
            centerVertically()
        })
        add(animator, lParams(matchConstraints, matchParent) {
            after(menuButton)
            before(pinnedBar)
            centerVertically()
        })
        add(workUi, lParams(matchConstraints, matchParent) {
            after(menuButton)
            before(pinnedBar)
            centerVertically()
        })
    }

    /** 「不学」 (D5), leftmost: before the menu key, or before the password's digits, which give it room. */
    val notLearnedMark = NotLearnedMark(ctx, theme)

    // the bar beside the mark
    private val body = frameLayout {
        add(idleBody, lParams(matchParent, matchParent))
        add(numberRow, lParams(matchParent, matchParent))
        addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) layoutFor?.let { f -> post { place(f(roomForPinned(width))) } }
        }
    }

    override val root = horizontalLayout {
        add(notLearnedMark, LinearLayout.LayoutParams(dp(NotLearnedMark.WIDTH_DP), matchParent))
        add(body, LinearLayout.LayoutParams(0, matchParent, 1f))
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
            val ms = if (instant) 0L else Motion.Medium.enter(motionSettings())
            if (ms > 0L) {
                iconAnimate().setDuration(ms).setInterpolator(Motion.Curve.Move.interpolator).rotation(targetRotation)
            } else {
                iconRotation = targetRotation
            }
        }
    }

    private fun clearAnimation() {
        animator.inAnimation = null
        animator.outAnimation = null
    }

    private fun setAnimation(motion: Motion.Settings) {
        animator.inAnimation = inAnimation.apply { duration = Motion.Medium.enter(motion) }
        animator.outAnimation = outAnimation.apply { duration = Motion.Medium.exit(motion) }
    }

    private fun enableSlideTransition(inTarget: View, outTarget: View, inGravity: Int, outGravity: Int, motion: Motion.Settings) {
        val slideIn = Slide(inGravity).apply {
            duration = Motion.Medium.enter(motion)
            interpolator = Motion.Curve.Enter.interpolator
        }
        val slideOut = Slide(outGravity).apply {
            duration = Motion.Medium.exit(motion)
            interpolator = Motion.Curve.Exit.interpolator
        }
        slideIn.addTarget(inTarget)
        slideOut.addTarget(outTarget)
        val set = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(slideIn)
            addTransition(slideOut)
        }
        TransitionManager.beginDelayedTransition(body, set)
    }

    private fun skipsAnimation(state: Display, fromUser: Boolean, motion: Motion.Settings) =
        !fromUser || !motion.animates || state.isUnanimated() || currentState.isUnanimated()

    private fun Display.isUnanimated() = this == Display.InlineSuggestion || this == Display.NumberRow

    fun updateState(state: Display, fromUser: Boolean = false) {
        Timber.d("Switch idle ui to $state")
        val motion = motionSettings()
        if (skipsAnimation(state, fromUser, motion)) {
            clearAnimation()
        } else {
            setAnimation(motion)
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
            if (fromUser && motion.animates) {
                enableSlideTransition(numberRow, idleBody, Gravity.END, Gravity.START, motion)
            }
            numberRow.visibility = View.VISIBLE
            idleBody.visibility = View.GONE
        } else if (currentState == Display.NumberRow) {
            if (fromUser && motion.animates) {
                enableSlideTransition(idleBody, numberRow, Gravity.START, Gravity.END, motion)
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

        private const val MIDDLE_BUTTONS = 2
    }
}
