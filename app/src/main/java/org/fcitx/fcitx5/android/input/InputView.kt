/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.expanded.window.BaseExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.DisplayMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.RealSize
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.SplitDock
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import org.fcitx.fcitx5.android.utils.unset
import org.fcitx.fcitx5.android.utils.windowManager
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.bottomToBottomOf
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.existingOrNewId
import splitties.views.imageDrawable
import splitties.views.imageResource
import timber.log.Timber

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    // the bar's colour, under the bar and the row above it for the code being typed: one taller bar
    private val barBackground = view(::View) {
        backgroundColor = if (keyBorder) Color.TRANSPARENT else theme.barColor
    }

    private val placeholderOnClickListener = OnClickListener { }

    // the empty side of a board put at the other (SplitKeyboard.Dock): a button moves it across
    private fun moveButton(to: SplitDock, icon: Int, description: Int) = imageView {
        imageResource = icon
        colorFilter = PorterDuffColorFilter(theme.altKeyTextColor, PorterDuff.Mode.SRC_IN)
        background = borderlessRippleDrawable(theme.keyPressHighlightColor)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(description)
        visibility = GONE
        setOnClickListener { ThemeManager.prefs.splitDock.setValue(to) }
    }

    private val moveLeft = moveButton(SplitDock.Left, R.drawable.ic_baseline_arrow_back_24, R.string.split_dock_move_left)
    private val moveRight = moveButton(SplitDock.Right, R.drawable.ic_baseline_arrow_forward_24, R.string.split_dock_move_right)

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = frameLayout {
        setOnClickListener(placeholderOnClickListener)
        add(moveLeft, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
    }
    private val rightPaddingSpace = frameLayout {
        setOnClickListener(placeholderOnClickListener)
        add(moveRight, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    /**
     * Work that belongs to this view: a child of the service's scope, cancelled when this view is
     * detached -- replaced by a new one after a theme or layout setting change -- rather than
     * left running, holding on to this view's components, until the service is destroyed.
     * Declared as exactly [CoroutineScope]: that is the type `imeScope()` finds it by.
     */
    private val viewScope: CoroutineScope = CoroutineScope(
        service.lifecycleScope.coroutineContext.let { it + SupervisorJob(it[Job]) }
    )

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += viewScope.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val advancedPrefs = AppPrefs.getInstance().advanced
    private val keyboardHeightPercentBase = advancedPrefs.keyboardHeightPercentBase

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        keyboardHeightPercentBase,
    )

    private val keyboardHeightPx: Int
        get() {
            val baseType = keyboardHeightPercentBase.getValue()
            val base = when (baseType) {
                DisplayMetrics -> resources.displayMetrics.heightPixels
                RealSize -> Point().also {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.display
                    } else {
                        context.windowManager.defaultDisplay
                    }.getRealSize(it)
                }.y
            }
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            Timber.d("keyboardHeightPx get(): baseType=${baseType}, base=${base}, percent=${percent}")
            return base * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    @Keep
    private val onSplitDockChangeListener = ManagedPreference.OnChangeListener<SplitDock> { _, _ ->
        updateKeyboardSize()
    }

    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(barBackground, lParams(0, 0) {
                topOfParent()
                bottomToBottomOf(kawaiiBar.view)
                // start and end set in updateKeyboardSize, as the bar's
            })
            add(preedit.ui.root, lParams(0, dp(KawaiiBarComponent.PREEDIT_HEIGHT)) {
                topOfParent()
                // start and end set in updateKeyboardSize, as the bar's
            })
            add(kawaiiBar.view, lParams(0, dp(KawaiiBarComponent.HEIGHT)) {
                below(preedit.ui.root)
                // start and end set in updateKeyboardSize
            })
            // left and right, not start and end: a board docked right is on the right in any locale
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                leftToLeft = LayoutParams.PARENT_ID
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                rightToRight = LayoutParams.PARENT_ID
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                leftToRight = leftPaddingSpace.existingOrNewId
                rightToLeft = rightPaddingSpace.existingOrNewId
                bottomOfParent()
            })
        }

        updateKeyboardSize()
        // the board at a side or across, as it changes, while the letters split: once laid out
        scope += DockFollower()
        keyboardWindow.onLayoutShown = { updateKeyboardSize() }

        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        advancedPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        ThemeManager.prefs.splitDock.registerOnChangeListener(onSplitDockChangeListener)
    }

    // told each window attached, by the broadcaster: added once the views it places are laid out
    private inner class DockFollower : UniqueComponent<DockFollower>(), InputBroadcastReceiver {
        override fun onWindowAttached(window: InputWindow) = updateKeyboardSize()
    }

    // what updateKeyboardSize placed the views for last: each change of window or layout asks again
    private var placed: List<Int?>? = null

    private fun updateKeyboardSize() {
        val sidePadding = keyboardSidePaddingPx
        val side = dockSide()
        val placing = listOf(
            keyboardHeightPx,
            keyboardBottomPaddingPx,
            sidePadding,
            side?.ordinal,
            dp(SplitKeyboard.dockWidth(TextKeyboard.widthDp(context))),
        )
        if (placing == placed) return
        placed = placing
        windowManager.view.updateLayoutParams {
            height = keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        placeBoard(side, sidePadding)
        placeBar(side, sidePadding)
    }

    /** The windows across between the side paddings, or [side]'s width at it, the rest left empty. */
    private fun placeBoard(side: SplitKeyboard.Dock?, sidePadding: Int) {
        moveLeft.visibility = if (side == SplitKeyboard.Dock.RIGHT) VISIBLE else GONE
        moveRight.visibility = if (side == SplitKeyboard.Dock.LEFT) VISIBLE else GONE
        // the space at the board's side: the side padding, if any; at the other, all the rest
        val near = if (side == SplitKeyboard.Dock.LEFT) leftPaddingSpace else rightPaddingSpace
        val far = if (side == SplitKeyboard.Dock.LEFT) rightPaddingSpace else leftPaddingSpace
        listOf(leftPaddingSpace, rightPaddingSpace).forEach {
            it.visibility = if (sidePadding == 0 && (side == null || it === near)) GONE else VISIBLE
            it.updateLayoutParams<LayoutParams> {
                width = sidePadding
                leftToRight = unset
                rightToLeft = unset
            }
        }
        windowManager.view.updateLayoutParams<LayoutParams> {
            leftToRight = unset
            rightToLeft = unset
            leftToLeft = unset
            rightToRight = unset
            width = if (side == null) 0 else dp(SplitKeyboard.dockWidth(TextKeyboard.widthDp(context)))
            when {
                side == null && sidePadding == 0 -> {
                    leftToLeft = LayoutParams.PARENT_ID
                    rightToRight = LayoutParams.PARENT_ID
                }
                side == null -> {
                    leftToRight = leftPaddingSpace.existingOrNewId
                    rightToLeft = rightPaddingSpace.existingOrNewId
                }
                side == SplitKeyboard.Dock.RIGHT ->
                    if (sidePadding == 0) rightToRight = LayoutParams.PARENT_ID else rightToLeft = near.existingOrNewId
                else -> if (sidePadding == 0) leftToLeft = LayoutParams.PARENT_ID else leftToRight = near.existingOrNewId
            }
        }
        if (side != null) far.updateLayoutParams<LayoutParams> {
            width = 0
            if (side == SplitKeyboard.Dock.RIGHT) rightToLeft = windowManager.view.id else leftToRight = windowManager.view.id
        }
    }

    // the bar over the board goes with it: a picker's tabs, the toolbar, under the same thumb
    private fun placeBar(side: SplitKeyboard.Dock?, sidePadding: Int) {
        val padding = if (side == null) sidePadding else 0
        // the row of the code being typed goes with the bar under it
        for (bar in listOf(barBackground, preedit.ui.root, kawaiiBar.view)) {
            bar.updateLayoutParams<LayoutParams> {
                leftToLeft = unset
                rightToRight = unset
                width = 0
                if (side == null) {
                    leftToLeft = LayoutParams.PARENT_ID
                    rightToRight = LayoutParams.PARENT_ID
                } else {
                    leftToLeft = windowManager.view.id
                    rightToRight = windowManager.view.id
                }
            }
            bar.setPadding(padding, 0, padding, 0)
        }
    }

    /** The side the board shown goes to, while the letters split, or null for the whole width. */
    private fun dockSide(): SplitKeyboard.Dock? {
        val letters = keyboardWindow.lettersShown
        val kind = when (windowManager.currentWindow) {
            keyboardWindow -> if (letters) SplitKeyboard.Window.LETTERS else SplitKeyboard.Window.OTHER_LAYOUT
            is PickerWindow -> SplitKeyboard.Window.PICKER
            is ClipboardWindow -> SplitKeyboard.Window.CLIPBOARD
            is BaseExpandedCandidateWindow<*> -> SplitKeyboard.Window.EXPANDED_CANDIDATES
            else -> SplitKeyboard.Window.OTHER
        }
        val docks = SplitKeyboard.docks(kind, lettersLast = letters)
        val dock = ThemeManager.prefs.splitDock.getValue().dock
        return SplitKeyboard.side(dock, TextKeyboard.splits(context), lettersShown = !docks, TextKeyboard.widthDp(context))
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        hold?.inputEnded()
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    /** called when [InputView] is hidden, or its field left */
    fun finishInput() {
        hold?.inputEnded()
        broadcaster.onFinishInput()
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    fun cursorMoved() = keyboardWindow.cursorMoved()

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // the scopes are torn down on detach; a re-attached view would silently do nothing
        if (!viewScope.isActive) Timber.e(IllegalStateException("InputView re-attached after detach"))
    }

    override fun onDetachedFromWindow() {
        advancedPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        ThemeManager.prefs.splitDock.unregisterOnChangeListener(onSplitDockChangeListener)
        // replaced (a rotation, a theme) its windows are never detached: what they had going,
        // the microphone above all, stops here
        broadcaster.onFinishInput()
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        viewScope.cancel()
        super.onDetachedFromWindow()
    }

}
