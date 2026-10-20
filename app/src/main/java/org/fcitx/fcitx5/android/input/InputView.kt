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
import android.graphics.Rect
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import org.fcitx.fcitx5.android.input.bar.Calculation
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.expanded.window.BaseExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.floating.FloatingCorners
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.floating.FloatingPlacer
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightCap
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.DisplayMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.RealSize
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SplitDock
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.picker.PickerData
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

// a button in the room beside a board at a side, square
private const val SIDE_BUTTON_DP = SplitKeyboard.ONE_HANDED_MIN_FREE_DP

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

    private fun sideButton(icon: Int, description: Int, onClick: () -> Unit) = imageView {
        imageResource = icon
        colorFilter = PorterDuffColorFilter(theme.altKeyTextColor, PorterDuff.Mode.SRC_IN)
        background = borderlessRippleDrawable(theme.keyPressHighlightColor)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(description)
        visibility = GONE
        setOnClickListener { onClick() }
    }

    // whether the board is at a side one-handed (not split): what the buttons beside it change
    private var oneHandedPlaced = false

    // the empty side of a board put at the other (SplitKeyboard.Dock): a button moves it across
    private fun moveButton(to: SplitKeyboard.Dock, icon: Int, description: Int) = sideButton(icon, description) {
        if (oneHandedPlaced) ThemeManager.prefs.oneHanded.setValue(OneHanded.entries.first { it.dock == to })
        else ThemeManager.prefs.splitDock.setValue(SplitDock.entries.first { it.dock == to })
    }

    private val moveLeft = moveButton(SplitKeyboard.Dock.LEFT, R.drawable.ic_baseline_arrow_back_24, R.string.split_dock_move_left)
    private val moveRight = moveButton(SplitKeyboard.Dock.RIGHT, R.drawable.ic_baseline_arrow_forward_24, R.string.split_dock_move_right)

    // one-handed, the other button there: the whole width again, at either side
    private fun fullWidthButton() = sideButton(R.drawable.ic_baseline_open_in_full_24, R.string.one_handed_exit) {
        ThemeManager.prefs.oneHanded.setValue(OneHanded.Off)
    }

    private val fullWidthLeft = fullWidthButton()
    private val fullWidthRight = fullWidthButton()

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = frameLayout {
        setOnClickListener(placeholderOnClickListener)
        add(moveLeft, FrameLayout.LayoutParams(dp(SIDE_BUTTON_DP), dp(SIDE_BUTTON_DP), Gravity.CENTER))
        add(fullWidthLeft, FrameLayout.LayoutParams(dp(SIDE_BUTTON_DP), dp(SIDE_BUTTON_DP), Gravity.CENTER))
    }
    private val rightPaddingSpace = frameLayout {
        setOnClickListener(placeholderOnClickListener)
        add(moveRight, FrameLayout.LayoutParams(dp(SIDE_BUTTON_DP), dp(SIDE_BUTTON_DP), Gravity.CENTER))
        add(fullWidthRight, FrameLayout.LayoutParams(dp(SIDE_BUTTON_DP), dp(SIDE_BUTTON_DP), Gravity.CENTER))
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

    // the keys' radius and margins as KeyView draws them; square where the user turned corners off
    private fun floatingCornerPx(): Float {
        val prefs = ThemeManager.prefs
        if (!prefs.floatingKeyboardRound.getValue()) return 0f
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val h = (if (landscape) prefs.keyHorizontalMarginLandscape else prefs.keyHorizontalMargin).getValue()
        val v = (if (landscape) prefs.keyVerticalMarginLandscape else prefs.keyVerticalMargin).getValue()
        return dp(FloatingKeyboard.cornerDp(prefs.keyRadius.getValue(), h, v, prefs.keyBorder.getValue())).toFloat()
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

    @Keep
    private val onOneHandedChangeListener = ManagedPreference.OnChangeListener<OneHanded> { _, _ ->
        updateKeyboardSize()
    }

    val keyboardView: View

    // read once: the view is made anew as it changes (ThemeManager), the letters splitting or not
    private val floating = ThemeManager.prefs.floatingKeyboard.getValue()

    private var floatingPlacer: FloatingPlacer? = null

    private val floatingHandle = if (!floating) null else FloatingHandle(
        context, theme,
        onDrag = { dx, dy -> floatingPlacer?.drag(dx, dy) },
        onDrop = { floatingPlacer?.drop() },
        onDock = { ThemeManager.prefs.floatingKeyboard.setValue(false) },
    )

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
        // emoji.data read and parsed off the main thread before the panel first opens; the lazy
        // holds the panel back if it opens before this is done
        viewScope.launch(Dispatchers.IO) { PickerData.Emoji }
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
            floatingHandle?.let {
                add(it, lParams(0, dp(FloatingHandle.HEIGHT_DP)) {
                    topOfParent()
                    leftToLeft = LayoutParams.PARENT_ID
                    rightToRight = LayoutParams.PARENT_ID
                })
            }
            add(preedit.ui.root, lParams(0, dp(KawaiiBarComponent.PREEDIT_HEIGHT)) {
                if (floatingHandle != null) below(floatingHandle) else topOfParent()
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

        add(keyboardView, lParams(if (floating) dp(TextKeyboard.widthDp(context)) else matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        floatingPlacer = if (floating) FloatingPlacer(this, keyboardView) else null
        if (floating) FloatingCorners.frame(keyboardView, ::floatingCornerPx, dp(FloatingCorners.ELEVATION_DP).toFloat())
        floatingPlacer?.let { placer -> keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placer.place() } }
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        advancedPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        ThemeManager.prefs.splitDock.registerOnChangeListener(onSplitDockChangeListener)
        ThemeManager.prefs.oneHanded.registerOnChangeListener(onOneHandedChangeListener)
    }

    // told each window attached, by the broadcaster: added once the views it places are laid out
    private inner class DockFollower : UniqueComponent<DockFollower>(), InputBroadcastReceiver {
        override fun onWindowAttached(window: InputWindow) = updateKeyboardSize()
    }

    // what updateKeyboardSize placed the views for last: each change of window or layout asks again
    private var placed: List<Int?>? = null

    private fun updateKeyboardSize() {
        // floating: no padding, no side, keys as much shorter as the keyboard is narrower
        val sidePadding = if (floating) 0 else keyboardSidePaddingPx
        val placement = if (floating) null else placement()
        val boardHeight = if (floating) {
            FloatingKeyboard.height(keyboardHeightPx, TextKeyboard.widthDp(context), resources.configuration.screenWidthDp)
        } else {
            // the whole docked area stays under ~70% of the screen, so the field shows in landscape
            KeyboardHeightCap.keysHeight(
                keyboardHeightPx,
                resources.displayMetrics.heightPixels,
                dp(KawaiiBarComponent.HEIGHT + KawaiiBarComponent.PREEDIT_HEIGHT),
                keyboardBottomPaddingPx,
            )
        }
        val bottomPadding = if (floating) 0 else keyboardBottomPaddingPx
        oneHandedPlaced = placement != null && !TextKeyboard.splits(context)
        val placing = listOf(boardHeight, bottomPadding, sidePadding, placement?.side?.ordinal, placement?.widthDp, if (oneHandedPlaced) 1 else 0)
        if (placing == placed) return
        placed = placing
        windowManager.view.updateLayoutParams {
            height = boardHeight
        }
        bottomPaddingSpace.updateLayoutParams {
            height = bottomPadding
        }
        placeBoard(placement, sidePadding)
        placeBar(placement?.side, sidePadding)
    }

    /** The windows across between the side paddings, or [placement]'s width at its side, the rest left empty. */
    private fun placeBoard(placement: SplitKeyboard.Placement?, sidePadding: Int) {
        val side = placement?.side
        showSideButtons(side)
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
            width = if (placement == null) 0 else dp(placement.widthDp)
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

    // in the room beside a board at [side]: the arrow across, and one-handed the way back to the whole width
    private fun showSideButtons(side: SplitKeyboard.Dock?) {
        moveLeft.isVisible = side == SplitKeyboard.Dock.RIGHT
        moveRight.isVisible = side == SplitKeyboard.Dock.LEFT
        fullWidthLeft.isVisible = oneHandedPlaced && side == SplitKeyboard.Dock.RIGHT
        fullWidthRight.isVisible = oneHandedPlaced && side == SplitKeyboard.Dock.LEFT
        // one-handed, the two one above the other about the middle; the arrow alone in it
        val apart = if (oneHandedPlaced) dp(SIDE_BUTTON_DP / 2 + 4).toFloat() else 0f
        moveLeft.translationY = -apart
        moveRight.translationY = -apart
        fullWidthLeft.translationY = apart
        fullWidthRight.translationY = apart
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

    /** Where the board shown goes, while the letters split or one-handed, or null for the whole width. */
    private fun placement(): SplitKeyboard.Placement? {
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
        val oneHanded = ThemeManager.prefs.oneHanded.getValue().dock
        return SplitKeyboard.placement(oneHanded, dock, TextKeyboard.splits(context), lettersShown = !docks, TextKeyboard.widthDp(context))
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val navBar = getNavBarBottomInset(insets)
        // floating, it is kept above the bar rather than drawn over it
        floatingPlacer?.bottomInset = navBar
        floatingPlacer?.topInset = WindowInsetsCompat.toWindowInsetsCompat(insets)
            .getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = if (floating) 0 else navBar
        }
        return insets
    }

    /**
     * Floating ([FloatingKeyboard]), where the keyboard is in the window, all of it that takes
     * touches (the service gives the rest to the app under it); false docked.
     */
    fun floatingRegion(out: Rect): Boolean {
        floatingPlacer?.region(out) ?: return false
        return true
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

    /** The sum just written, [worked] out, for the bar to offer while the cursor stays [at] its end. */
    fun offerCalculation(worked: Calculation.Worked, at: Int) = kawaiiBar.offerCalculation(worked, at)

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
        ThemeManager.prefs.oneHanded.unregisterOnChangeListener(onOneHandedChangeListener)
        // replaced (a rotation, a theme) its windows are never detached: what they had going,
        // the microphone above all, stops here
        broadcaster.onFinishInput()
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        viewScope.cancel()
        super.onDetachedFromWindow()
    }

}
