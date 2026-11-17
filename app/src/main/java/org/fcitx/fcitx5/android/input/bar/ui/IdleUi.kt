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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ViewAnimator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.BarIconDrawables
import org.fcitx.fcitx5.android.input.bar.BarIcons
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
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
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
import java.util.EnumMap

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

    private val packIcons = PackIcons.of(theme)

    // the app's menu key is one ⌄ turned to say the state; a theme's icon of either state (061-toolbar-icons Q3) has
    // each state drawn as its own, none turned, the one it does not write the app's ⌄ turned as ever. As drawn, not as
    // named: a file that failed is the app's ⌄. Asked each time: a file not read yet counts as drawn (R26 F8451)
    private val menuTurns get() = BarIcons.menuStates.none { BarIconDrawables.drawsOwn(packIcons, it) }

    private val menuOpen get() = currentState == Display.Toolbar

    private fun menuRotation(open: Boolean): Float = when {
        BarIconDrawables.drawsOwn(packIcons, BarIcons.menu(open)) -> 0f
        open -> 90f * translateDirection
        else -> -90f * translateDirection
    }

    // the theme's toolbar button, worked out once for the bar's every button (T6b)
    private val toolbar = SceneBar.of(ctx, theme).toolbar

    // the state the menu key's icon is drawn for
    private var menuDrawnOpen = menuOpen

    // the menu key drawn as ⋯ (ToolbarConfig.Layout.moreOnly)
    private var menuDrawnMore = false

    /** Nothing of the toolbar's to show: the menu key is ⋯, a tap opening its panel (ToolbarConfig.Layout.moreOnly). */
    val moreOnly: Boolean get() = arranged?.moreOnly == true

    val menuButton = ToolButton(ctx, BarIcons.menu(menuOpen), theme, toolbar).apply {
        iconRotation = menuRotation(menuOpen)
        fixIconSize(ctx.dp(KawaiiBarComponent.ICON_DP))
    }

    init {
        // updateState keeps this current, but is not called for the initial state; without
        // this the button starts out unlabelled
        updateMenuButtonContentDescription()
        followMenuIcons()
    }

    // a menu icon not read yet is turned as the theme's (none); one that failed once read is the app's ⌄, turned for the
    // state drawn now, not left upright till the next state's (R26 F8451). A read pending at a forget is told and followed
    // anew; one in already is not told of it (PackIcons.forget tells the waiting alone), drawn as read till the next
    // state's: the pack in use written over is told as changed (ThemeManager.filesChanged), the keyboard drawn anew (R26 F8510)
    private fun followMenuIcons() {
        for (state in BarIcons.menuStates) packIcons.file(state.name)?.let { packIcons.outline(it, ::menuIconRead) }
    }

    private fun menuIconRead() {
        if (!menuDrawnMore) menuButton.iconRotation = menuRotation(menuDrawnOpen)
        followMenuIcons()
    }

    /**
     * Told each button as it is made, to wire and light it ([KawaiiBarComponent]): a button is made
     * the first time it is placed or asked for, not with the bar. Most of the bar's buttons sit on the
     * toolbar, which few open; making them all took a tenth of making an input view (060-render-perf-2 #7).
     */
    var onButtonMade: ((ToolbarButton, ToolButton) -> Unit)? = null

    private val made = EnumMap<ToolbarButton, ToolButton>(ToolbarButton::class.java)

    /** [button] on the bar ([ToolbarConfig]), made now if it was not yet; placed by [arrange]. */
    fun button(button: ToolbarButton): ToolButton = made[button] ?: makeButton(button)

    /** [button] if it was made, else null: one never made is nowhere on screen. */
    fun madeButton(button: ToolbarButton): ToolButton? = made[button]

    /** The buttons made so far. */
    val madeButtons: Map<ToolbarButton, ToolButton> get() = made

    private fun makeButton(button: ToolbarButton): ToolButton {
        val looks = ToolbarButtonLooks.of(button)
        val view = ToolButton(ctx, looks.icon, theme, toolbar).apply {
            contentDescription = ctx.getString(looks.label)
            fixIconSize(ctx.dp(KawaiiBarComponent.ICON_DP))
        }
        made[button] = view
        view.available = button !in arranged?.unavailable.orEmpty()
        // the emoji, as Sogou's 表情: a long press on the comma was all there was, and nobody finds
        // that. Opens the emoji or kaomoji, whichever was last
        if (button == ToolbarButton.Emoji) view.setOnClickListener {
            commonKeyActionListener.listener.onKeyAction(KeyAction.PickerSwitchAction(), KeyActionListener.Source.Keyboard)
        }
        onButtonMade?.invoke(button, view)
        return view
    }

    /** Whether [button] is where the user sees it: pinned at the right, or on the toolbar while it is out. */
    fun onScreen(button: ToolbarButton): Boolean = made[button]?.parent.let {
        it === pinnedBar || (it != null && currentState == Display.Toolbar)
    }

    // the bar's right, after the toolbar: there whenever the bar is idle
    private val pinnedBar = horizontalLayout()

    private var arranged: ToolbarConfig.Layout? = null

    // the bar's slots as last worked out for its width (ToolbarConfig.Layout.slots)
    private var slots: ToolbarConfig.Slots? = null

    // the buttons [buttonsUi] holds; null while it holds an older arrangement's or none
    private var toolbarShown: List<ToolbarButton>? = null

    /** The toolbar's buttons with no slot on the bar, in order: in ⋯'s panel (StatusAreaWindow). */
    val overflow: List<ToolbarButton> get() = slots?.overflow.orEmpty()

    private fun showToolbar() {
        val shown = slots?.toolbar ?: return
        if (shown == toolbarShown) return
        toolbarShown = shown
        buttonsUi.show(shown.map(::button))
    }

    /** The buttons [layout] places, the others nowhere; the same ones but for which are greyed only greyed anew. */
    fun arrange(layout: ToolbarConfig.Layout) {
        if (layout == arranged) return
        val placed = arranged?.let { it.toolbar == layout.toolbar && it.pinned == layout.pinned } == true
        arranged = layout
        made.forEach { (button, view) -> view.available = button !in layout.unavailable }
        showMenuKey()
        if (placed) return
        made.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        // the toolbar's buttons made and put on it as it is first opened
        toolbarShown = null
        val size = ctx.dp(KawaiiBarComponent.heightDp(ctx))
        val buttonWidth = ctx.dp(KawaiiBarComponent.buttonWidthDp(ctx))
        layout.pinned.forEach { pinnedBar.addView(button(it), LinearLayout.LayoutParams(buttonWidth, size)) }
        sizeCells()
    }

    // the bar in slots of one width (ToolbarConfig.Layout.slots), the menu key in the first, the right's in the last,
    // the toolbar's between as it is open: nothing moves nor changes width as it opens or shuts, nor as 中/英 switch
    // (the user's call, 2026-11-15); narrower where the bar is narrow (the floating keyboard's), every one at the right
    // kept. An icon too big for its slot shrinks.
    private fun sizeCells() {
        val layout = arranged ?: return
        val s = layout.slots(root.width, ctx.dp(KawaiiBarComponent.buttonWidthDp(ctx)))
        slots = s
        val cell = s.cellPx
        buttonsUi.cellPx = cell
        (listOf<View>(menuButton) + (0 until pinnedBar.childCount).map(pinnedBar::getChildAt)).forEach {
            if (it.layoutParams.width != cell) {
                it.layoutParams.width = cell
                it.requestLayout()
            }
        }
        if (currentState == Display.Toolbar) showToolbar()
        val icon = minOf(ctx.dp(KawaiiBarComponent.ICON_DP), cell - ctx.dp(ICON_MARGIN_DP)).coerceAtLeast(1)
        (listOf(menuButton) + made.values).forEach { it.fixIconSize(icon) }
    }

    // what the bar shows with nothing else to: the address suggestions (AddressStrip), when there are
    val emptyBar = FrameLayout(ctx)

    val buttonsUi = ButtonsBarUi(ctx, theme)

    val clipboardUi = ClipboardSuggestionUi(ctx, theme)

    val calculationUi = CalculationUi(ctx, theme)

    // the password field's digits, a keyboard too: made anew with the keys for the settings they read
    // (the marks, the long presses, the letters' case; InputView.remakeKeyboards)
    var numberRow = newNumberRow()
        private set

    private fun newNumberRow() = NumberRow(ctx, theme).apply {
        visibility = View.GONE
        onCollapseListener = numberRowCollapsed
    }

    /** Told as the number row is put away by its own key; kept for the one made anew. */
    var numberRowCollapsed: (() -> Unit)? = null
        set(value) {
            field = value
            numberRow.onCollapseListener = value
        }

    /** The number row made anew where it was, shown or not as it was. */
    fun remakeNumberRow() {
        val old = numberRow
        val at = root.indexOfChild(old)
        numberRow = newNumberRow().apply {
            visibility = old.visibility
            if (currentState == Display.NumberRow) {
                keyActionListener = commonKeyActionListener.listener
                popupActionListener = popup.listener
            }
        }
        old.keyActionListener = null
        old.popupActionListener = null
        root.removeView(old)
        root.addView(numberRow, at, old.layoutParams)
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
        val size = dp(KawaiiBarComponent.heightDp(ctx))
        val buttonWidth = dp(KawaiiBarComponent.buttonWidthDp(ctx))
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

    override val root = frameLayout {
        add(idleBody, lParams(matchParent, matchParent))
        add(numberRow, lParams(matchParent, matchParent))
        addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) post { sizeCells() }
        }
    }

    // ⋯ where the toolbar has nothing to show, else the menu key of its state
    private fun showMenuKey() {
        val more = moreOnly
        if (more == menuDrawnMore) return
        menuDrawnMore = more
        menuButton.apply {
            iconAnimate().cancel()
            iconAlpha = 1f
            if (more) {
                setIcon(BarIcons.MORE)
                iconRotation = 0f
            } else {
                menuDrawnOpen = menuOpen
                setIcon(BarIcons.menu(menuOpen))
                iconRotation = menuRotation(menuOpen)
            }
        }
        updateMenuButtonContentDescription()
    }

    private fun updateMenuButtonContentDescription() {
        menuButton.contentDescription = when {
            menuDrawnMore -> ctx.getString(R.string.toolbar_more)
            currentState == Display.Toolbar -> ctx.getString(R.string.hide_toolbar)
            else -> ctx.getString(R.string.expand_toolbar)
        }
    }

    private fun updateMenuButtonIcon(instant: Boolean = false) {
        if (menuDrawnMore) return
        val open = menuOpen
        if (!menuTurns) return crossfadeMenuButton(open, instant)
        val targetRotation = menuRotation(open)
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

    // a theme's menu key: the state's own icon faded in for the other's, Motion.Long (150 ms) in all, at once with 禁用动画
    private fun crossfadeMenuButton(open: Boolean, instant: Boolean) {
        if (open == menuDrawnOpen) return
        menuDrawnOpen = open
        menuButton.apply {
            iconAnimate().cancel()
            val show = {
                setIcon(BarIcons.menu(open))
                iconRotation = menuRotation(open)
            }
            val ms = if (instant) 0L else Motion.Long.enter(motionSettings())
            if (ms > 0L) {
                iconAnimate().setDuration(ms / 2).alpha(0f).withEndAction {
                    show()
                    iconAnimate().setDuration(ms / 2).alpha(1f)
                }
            } else {
                show()
                iconAlpha = 1f
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
        TransitionManager.beginDelayedTransition(root, set)
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
            Display.Toolbar -> {
                showToolbar()
                animator.displayedChild = 1
            }
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
        sizeCells()
        layOutWork()
        updateMenuButtonContentDescription()
        updateMenuButtonIcon(instant = !fromUser)
    }

    companion object {
        private const val WORK_BAR_DP = 2

        // the least room beside an icon in its cell, dp: a cell narrower than the icon and this shrinks the icon
        private const val ICON_MARGIN_DP = 6
    }
}
