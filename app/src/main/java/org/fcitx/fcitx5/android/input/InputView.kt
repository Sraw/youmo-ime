/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
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
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.punctuation.PunctuationStartKeys
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.bar.BarIconDrawables
import org.fcitx.fcitx5.android.input.bar.BarIcons
import org.fcitx.fcitx5.android.input.bar.Calculation
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ToolbarButtonLooks
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.candidates.expanded.window.GridExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.dependency.TypedComponent
import org.fcitx.fcitx5.android.input.floating.BoardFollowers
import org.fcitx.fcitx5.android.input.floating.BoardMove
import org.fcitx.fcitx5.android.input.floating.BoardMover
import org.fcitx.fcitx5.android.input.floating.FloatingController
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.floating.FloatingHandoff
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.floating.FloatingPlacer
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyles
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SplitDock
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneTrace
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneWindow
import org.fcitx.fcitx5.android.input.picker.PickerData
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.CodeStripPlacement
import org.fcitx.fcitx5.android.input.preedit.FloatingCodeStrip
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.scene.engine.section
import org.fcitx.fcitx5.android.scene.look.BoardBase
import org.fcitx.fcitx5.android.utils.unset
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
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
import splitties.views.imageResource
import timber.log.Timber

private const val SIDE_BUTTON_DP = SplitKeyboard.ONE_HANDED_MIN_FREE_DP

private const val SIDE_ICON_DP = 24

private fun showFullWidthIcon(b: ImageView, theme: Theme, tint: Int, again: () -> Unit) =
    BarIconDrawables.show(b, PackIcons.of(theme), BarIcons.ONE_HANDED_EXIT, tint, b.dp(SIDE_ICON_DP), again)

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: HostConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val codeFloats = KawaiiBarComponent.codeRowFloats()

    private val customBackground = view(::PictureBaseView)

    private val navbarStrip = view(::View) { visibility = GONE }

    private val barBackground = view(::View) {
        background = SceneBar.background(SceneBar.of(context, theme))
    }

    private val placeholderOnClickListener = OnClickListener { }

    private fun sideButton(icon: Int, description: Int, onClick: () -> Unit) = imageView {
        imageResource = icon
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(description)
        visibility = GONE
        setOnClickListener { onClick() }
    }

    private var oneHandedPlaced = false

    private var placedScenes: Set<String>? = null

    private var placedWindowScenes: Set<String>? = null

    private fun moveButton(to: SplitKeyboard.Dock, icon: Int, description: Int) = sideButton(icon, description) {
        if (oneHandedPlaced) ThemeManager.prefs.oneHandedSide.setValue(OneHanded.entries.first { it.dock == to })
        else ThemeManager.prefs.splitDock.setValue(SplitDock.entries.first { it.dock == to })
    }

    private val moveLeft = moveButton(SplitKeyboard.Dock.LEFT, R.drawable.ic_baseline_arrow_back_24, R.string.split_dock_move_left)
    private val moveRight = moveButton(SplitKeyboard.Dock.RIGHT, R.drawable.ic_baseline_arrow_forward_24, R.string.split_dock_move_right)

    private fun fullWidthButton() = sideButton(ToolbarButtonLooks.drawable(BarIcons.ONE_HANDED_EXIT.fallback), R.string.one_handed_exit) {
        KeyboardStyles.oneHandedOff(floatingController.screenBuilt)
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

    private val dockedRoom = DockedRoom(this, bottomPaddingSpace, { !floating }) { updateKeyboardSize() }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    @VisibleForTesting
    internal val popup = PopupComponent()
    @VisibleForTesting
    internal val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent(codeFloats)

    private var codeRowDp = KawaiiBarComponent.preeditHeight(fcitx.runImmediately { inputMethodEntryCached }.uniqueName)
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    @VisibleForTesting
    internal val kawaiiBar = KawaiiBarComponent()

    /**
     * A child of [keyboardView] so that it goes and scales with it. [underBar]: below the bar (tap to talk
     * keeps the bar's microphone); else over the whole keyboard (the space held).
     */
    fun overKeys(card: View, underBar: Boolean) {
        val board = keyboardView as ConstraintLayout
        board.addView(card, board.lParams(0, 0) {
            leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
            if (underBar) {
                below(kawaiiBar.view)
                bottomToBottomOf(windowManager.view)
            } else {
                topOfParent()
                bottomOfParent()
            }
        })
    }

    /**
     * The rest of the view is kept. The field is then started anew where [info] is one, and the input
     * method and status told again, which pick the letters and the code row's height.
     */
    fun remakeKeyboards(info: EditorInfo?, capFlags: CapabilityFlags) {
        hold?.inputEnded(Hold.InputEnd.LEFT)
        dismissPopups()
        keyboardWindow.remakeKeyboards()
        windowManager.attachWindow(KeyboardWindow)
        kawaiiBar.keyboardsRemade()
        info?.let { startInput(it, capFlags) }
        if (handleEvents) onStartHandleFcitxEvent()
    }

    fun voiceButton(): View? = kawaiiBar.shownButton(ToolbarButton.Voice)

    fun handleShown(): View? = floatingHandle?.takeIf { it.isShown }

    override val undoAnchor: View get() = kawaiiBar.view

    override val lettersShown: Boolean get() = windowManager.isAttached(keyboardWindow) && keyboardWindow.lettersShown

    override val undoAbove: View get() = keyboardView

    override fun undoStrip(out: Rect): Boolean = codeStripRegion(out)
    override val undoAnchorShowsCandidates: Boolean
        get() = kawaiiBar.view.displayedChild == KawaiiBarStateMachine.State.Candidate.ordinal

    // docked, below the keyboard is the screen's edge; floating at the top, there is room below
    override val undoBelowOk: Boolean get() = floating

    override val floatingKeyboard: Boolean get() = floating

    // this view's bottom less its dispatched inset, as FloatingPlacer keeps. The decor may already fit the
    // bar (API < 35, floating), so raw root insets could count it twice or not at all
    override val undoBottomLimit: Int
        get() = floatingPlacer?.let { placer ->
            IntArray(2).also { getLocationInWindow(it) }[1] + height - placer.bottomInset
        } ?: super.undoBottomLimit

    override fun undoFloating(keyboard: Rect, screen: Rect): Boolean {
        val placer = floatingPlacer ?: return false
        placer.region(keyboard)
        val at = IntArray(2).also { getLocationInWindow(it) }
        screen.set(at[0], at[1] + placer.topInset, at[0] + width, undoBottomLimit)
        return true
    }

    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    /**
     * Cancelled when this view is detached (replaced after a theme or layout change), not left holding
     * its components till the service dies. Typed exactly [CoroutineScope]: `imeScope()` finds it by that.
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


    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape
    private val keyboardBottomFlush = keyboardPrefs.keyboardBottomFlush
    private val keyboardHeightFollowTheme = keyboardPrefs.keyboardHeightFollowTheme
    private val keyboardSidePaddingFollowTheme = keyboardPrefs.keyboardSidePaddingFollowTheme
    private val keyboardBottomPaddingFollowTheme = keyboardPrefs.keyboardBottomPaddingFollowTheme

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        keyboardBottomFlush,
        keyboardHeightFollowTheme,
        keyboardSidePaddingFollowTheme,
        keyboardBottomPaddingFollowTheme,
    )

    private val keyboardSidePaddingPx: Int get() = KeyboardArea.sidePadding(context)

    private val keyboardBottomPaddingPx: Int get() = KeyboardArea.bottomPadding(context)

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            SceneTrace.section("keyboard resized") { updateKeyboardSize() }
            // 贴底 is applied with the insets; a theme's bottom padding followed leaves it aside (KeyboardSizes.flush)
            if (key == keyboardBottomFlush.key || key == keyboardBottomPaddingFollowTheme.key) requestApplyInsets()
        }
    }

    @Keep
    private val onSplitDockChangeListener = ManagedPreference.OnChangeListener<SplitDock> { _, _ ->
        updateKeyboardSize(slide = true)
    }

    @Keep
    private val onStyleChangeListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        if (KeyboardStyles.of(floatingController.screenBuilt).let { it.floating == floating && it.splits == floatingController.splitsBuilt }) updateKeyboardSize(slide = true)
    }

    val keyboardView: View

    private val floatingController: FloatingController = FloatingController(this, theme) { (it as? InputView)?.floatingController }
    private val floating = floatingController.floating

    private val floatingPlacer: FloatingPlacer? get() = floatingController.placer
    private val floatingHandle = floatingController.handle
    private val handleAtBottom = floatingController.handleAtBottom

    private val boardFollowers by lazy {
        listOfNotNull(barBackground, preedit.ui.root.takeIf { !codeFloats }, kawaiiBar.view, floatingHandle?.takeIf { !floating })
    }

    private val codeStrip: FloatingCodeStrip?
    private val boardMover by lazy { BoardMover(keyboardView, listOf(windowManager.view) + boardFollowers) }

    init {
        // MUST call before any operation
        setupScope()

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        SceneTrace.section("keyboard window") { windowManager.addEssentialWindow(keyboardWindow, createView = true) }
        punctuation.update(PunctuationStartKeys.known)
        viewScope.launch { PunctuationStartKeys.keys.collect { if (!punctuation.known) punctuation.update(it) } }
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // emoji.data parsed off the main thread; the lazy holds the panel back if it opens first
        viewScope.launch(Dispatchers.IO) { PickerData.Emoji }
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        val look = ThemeManager.shownLook(theme)
        customBackground.background = ThemePictureDrawable.board(look)
        val pictureHost = look.picture?.let {
            PictureHost(look, customBackground, KeyboardArea.landscape(context))
        }
        // decoded off the main thread, the still picture till then; stopped as the keyboard hides
        val animated = look.picture?.takeIf { look.base == BoardBase.Picture }
        if (animated != null && pictureHost != null) {
            AnimatedBoardDrawable.later(animated, ThemeColors.board(look.palette, look.keys), customBackground, viewScope) {
                pictureHost.play(it)
            }
        }

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            PictureHost.install(this, pictureHost)
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(navbarStrip, lParams(0, 0) {
                leftToLeft = LayoutParams.PARENT_ID
                rightToRight = LayoutParams.PARENT_ID
                topToBottom = bottomPaddingSpace.existingOrNewId
                bottomOfParent()
            })
            add(barBackground, lParams(0, 0) {
                topOfParent()
                bottomToBottomOf(kawaiiBar.view)
            })
            floatingHandle?.let {
                add(it, lParams(0, dp(FloatingHandle.HEIGHT_DP)) {
                    if (handleAtBottom) above(bottomPaddingSpace) else topOfParent()
                    leftToLeft = LayoutParams.PARENT_ID
                    rightToRight = LayoutParams.PARENT_ID
                })
            }
            val top = if (codeFloats) null else preedit.ui.root.also {
                add(it, lParams(0, dp(codeRowDp)) {
                    if (floatingHandle != null && !handleAtBottom) below(floatingHandle) else topOfParent()
                })
            }
            add(kawaiiBar.view, lParams(0, dp(KawaiiBarComponent.heightDp(context))) {
                when {
                    top != null -> below(top)
                    floatingHandle != null && !handleAtBottom -> below(floatingHandle)
                    else -> topOfParent()
                }
            })
            // left and right, not start and end: a board docked right is on the right in any locale. Not over a
            // bottom handle: they would take its touches
            val bottomHandle = floatingHandle?.takeIf { handleAtBottom }
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                leftToLeft = LayoutParams.PARENT_ID
                if (bottomHandle != null) above(bottomHandle) else bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                rightToRight = LayoutParams.PARENT_ID
                if (bottomHandle != null) above(bottomHandle) else bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomHandle ?: bottomPaddingSpace)
            })
            add(bottomPaddingSpace, lParams {
                leftToRight = leftPaddingSpace.existingOrNewId
                rightToLeft = rightPaddingSpace.existingOrNewId
                bottomOfParent()
            })
        }

        SceneTrace.section("updateKeyboardSize") { updateKeyboardSize() }
        scope += DockFollower()
        keyboardWindow.onLayoutShown = {
            updateKeyboardSize()
            keysChanged()
        }

        add(keyboardView, lParams(if (floating) dp(TextKeyboard.widthDp(context)) else matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        floatingController.setUp(keyboardView, ::updateKeyboardSize)
        if (codeFloats) preedit.onShown = ::showCodeStrip
        codeStrip = if (!codeFloats) null else FloatingCodeStrip(context, theme, preedit.ui.root).also {
            add(it, lParams(wrapContent, dp(KawaiiBarComponent.stripHeight(fcitx.runImmediately { inputMethodEntryCached }.uniqueName))) {
                topOfParent()
                leftToLeft = LayoutParams.PARENT_ID
            })
        }
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })
        floatingController.keepAbove(popup.root)
        popup.menuRoom = { width, height ->
            val bar = kawaiiBar.view
            val at = IntArray(2).also { bar.getLocationInWindow(it) }
            PopupComponent.MenuRoom(noticePlace(width, height), at[1] + bar.height / 2)
        }
        popup.onMenuShown = ::noticeCovered
        popup.onMenuGone = ::noticeUncovered

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        ThemeManager.prefs.splitDock.registerOnChangeListener(onSplitDockChangeListener)
        KeyboardStyles.styleKey(floatingController.screenBuilt.landscape).registerOnChangeListener(onStyleChangeListener)
        ThemeManager.prefs.oneHandedSide.registerOnChangeListener(onStyleChangeListener)
    }

    private inner class DockFollower : TypedComponent<DockFollower>(), InputBroadcastReceiver {
        override fun onWindowAttached(window: InputWindow) {
            updateKeyboardSize()
            keysChanged()
        }
    }

    private var placed: List<Int?>? = null
    private var placedSide: SplitKeyboard.Dock? = null

    private fun updateKeyboardSize(slide: Boolean = false) {
        val sidePadding = if (floating) 0 else keyboardSidePaddingPx
        val placement = if (floating) null else placement()
        val boardHeight = if (floating) {
            KeyboardArea.floatingSize(KeyboardArea.screen(context)).keysHeightPx
        } else {
            // the app keeps 120 dp over the docked keyboard, the rows 36 dp each, the rows winning (KeyboardHeightCap).
            // A docked handle is counted in the keyboard
            val handle = if (floatingHandle != null) dp(FloatingHandle.HEIGHT_DP) else 0
            KeyboardArea.keysHeight(context, codeRowDp, handle, dockedRoom.take(), keyboardWindow.rowUnits())
        }
        val bottomPadding = if (floating) 0 else keyboardBottomPaddingPx
        oneHandedPlaced = placement != null && !floatingController.splitsBuilt
        placeScenes(if (floating) emptySet() else SceneWindow.placement(oneHandedPlaced, floatingController.splitsBuilt))
        val placing = listOf(boardHeight, bottomPadding, sidePadding, placement?.side?.ordinal, placement?.widthDp, if (oneHandedPlaced) 1 else 0)
        if (placing == placed) return
        boardMover.move(BoardMove.kind(slide, placed != null, placedSide, placement?.side))
        placed = placing
        placedSide = placement?.side
        windowManager.view.updateLayoutParams {
            height = boardHeight
        }
        bottomPaddingSpace.updateLayoutParams {
            height = bottomPadding
        }
        placeBoard(placement, sidePadding)
        BoardFollowers.place(boardFollowers, windowManager.view, placement?.side, sidePadding)
    }

    private fun placeBoard(placement: SplitKeyboard.Placement?, sidePadding: Int) {
        val side = placement?.side
        showSideButtons(side)
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

    private fun placeScenes(scenes: Set<String>) {
        val window = SceneWindow.scenes(context, theme, floating, scenes)
        if (scenes == placedScenes && window == placedWindowScenes) return
        placedScenes = scenes
        placedWindowScenes = window
        paintNavbar()
        service.boardPlaced(scenes)
        if (!floating) floatingHandle?.dress(floatingController.handleLook(window))
    }

    private fun windowScenes() = SceneWindow.scenes(context, theme, floating, placedScenes.orEmpty())

    private fun showSideButtons(side: SplitKeyboard.Dock?) {
        if (side != null) dressSideButtons()
        moveLeft.isVisible = side == SplitKeyboard.Dock.RIGHT
        moveRight.isVisible = side == SplitKeyboard.Dock.LEFT
        fullWidthLeft.isVisible = oneHandedPlaced && side == SplitKeyboard.Dock.RIGHT
        fullWidthRight.isVisible = oneHandedPlaced && side == SplitKeyboard.Dock.LEFT
        val apart = if (oneHandedPlaced) dp(SIDE_BUTTON_DP / 2 + 4).toFloat() else 0f
        moveLeft.translationY = -apart
        moveRight.translationY = -apart
        fullWidthLeft.translationY = apart
        fullWidthRight.translationY = apart
    }

    private var sideButtonsDressed: Set<String>? = null

    private fun dressSideButtons() {
        val scenes = windowScenes()
        if (scenes == sideButtonsDressed) return
        sideButtonsDressed = scenes
        val look = SceneWindow.sideButton(theme, scenes)
        for (b in listOf(moveLeft, moveRight)) b.colorFilter = PorterDuffColorFilter(look.iconColor.value, PorterDuff.Mode.SRC_IN)
        for (b in listOf(fullWidthLeft, fullWidthRight)) showFullWidthIcon(b, theme, look.iconColor.value) { sideButtonsDressed = null; dressSideButtons() }
        for (b in listOf(moveLeft, moveRight, fullWidthLeft, fullWidthRight)) {
            b.background = SceneWindow.sideButtonBackground(context, look, dp(SIDE_BUTTON_DP))
        }
    }

    private fun placement(): SplitKeyboard.Placement? {
        val letters = keyboardWindow.lettersShown
        val kind = when (windowManager.currentWindow) {
            keyboardWindow -> if (letters) SplitKeyboard.Window.LETTERS else SplitKeyboard.Window.OTHER_LAYOUT
            is PickerWindow -> SplitKeyboard.Window.PICKER
            is ClipboardWindow -> SplitKeyboard.Window.CLIPBOARD
            is GridExpandedCandidateWindow -> SplitKeyboard.Window.EXPANDED_CANDIDATES
            else -> SplitKeyboard.Window.OTHER
        }
        val docks = SplitKeyboard.docks(kind, lettersLast = letters)
        val dock = ThemeManager.prefs.splitDock.getValue().dock
        return SplitKeyboard.placement(KeyboardStyles.oneHandedDock(floatingController.screenBuilt), dock, floatingController.splitsBuilt, lettersShown = !docks, TextKeyboard.widthDp(context))
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val navBar = getNavBarBottomInset(insets)
        if (floating) floatingController.insets(
            floatingBottomInset(insets),
            WindowInsetsCompat.toWindowInsetsCompat(insets)
                .getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top,
        )
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            // 贴底: no room for the bar, for ROMs whose hidden gesture line still takes it
            bottomMargin = if (floating || KeyboardArea.bottomFlush()) 0 else navBar
        }
        dockedRoom.check()
        paintNavbar()
        return insets
    }

    private fun paintNavbar() {
        val navbar = if (floating) null else SceneWindow.navbar(theme, windowScenes())
        navbarStrip.isVisible = navbar != null
        navbar?.let(navbarStrip::setBackgroundColor)
    }

    fun floatingRegion(out: Rect): Boolean = floatingController.region(out)

    fun codeStripRegion(out: Rect): Boolean {
        val strip = codeStrip ?: return false
        if (strip.visibility != VISIBLE || !strip.isLaidOut) return false
        strip.getLocationInWindow(stripAt)
        out.set(stripAt[0], stripAt[1], stripAt[0] + strip.width, stripAt[1] + strip.height)
        return true
    }

    private val stripAt = IntArray(2)
    private val barAt = IntArray(2)
    private val keyboardAt = IntArray(2)
    private val viewAt = IntArray(2)

    // each frame, outside the board's top-left as drawn; hidden while a floating keyboard is dragged or
    // scaled, where following it would shake
    private val codeStripPlacer = ViewTreeObserver.OnPreDrawListener {
        placeCodeStrip()
        true
    }

    private fun placeCodeStrip() {
        val strip = codeStrip ?: return
        if (strip.visibility == GONE) return
        val moving = floatingController.moving
        strip.visibility = if (moving) INVISIBLE else VISIBLE
        if (moving) return
        val bar = kawaiiBar.view
        bar.getLocationInWindow(barAt)
        val left = barAt[0] + bar.paddingLeft
        val right = barAt[0] + bar.width - bar.paddingRight
        keyboardView.getLocationInWindow(keyboardAt)
        getLocationInWindow(viewAt)
        val board = UndoChipPlacement.Box(left, keyboardAt[1], right, keyboardAt[1] + keyboardView.height)
        val room = UndoChipPlacement.Box(viewAt[0], viewAt[1] + (floatingPlacer?.topInset ?: 0), viewAt[0] + width, viewAt[1] + height)
        strip.maxWidth = right - left
        val place = CodeStripPlacement.place(board, room, strip.width, strip.height)
        strip.below = place.below
        val x = (place.x - viewAt[0]).toFloat()
        val y = (place.y - viewAt[1]).toFloat()
        if (strip.translationX != x || strip.translationY != y) {
            strip.translationX = x
            strip.translationY = y
            // the touchable region is worked out again on the next traversal (onComputeInsets)
            strip.requestLayout()
        }
        undoStripMoved()
    }

    fun startInput(info: EditorInfo, capFlags: CapabilityFlags) {
        hold?.inputEnded(Hold.InputEnd.STARTED)
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        windowManager.attachWindow(KeyboardWindow)
    }

    fun finishInput(finishingInput: Boolean) {
        hold?.inputEnded(if (finishingInput) Hold.InputEnd.LEFT else Hold.InputEnd.HIDDEN)
        floatingController.inputEnded()
        broadcaster.onFinishInput()
    }

    // the candidates too: a remade view mid-typing showed the code row over an empty bar till the next key
    override fun onStartHandleFcitxEvent() {
        fcitx.runImmediately {
            val entry = inputMethodEntryCached
            listOf(
                inputPanelCached,
                HostEvent.InputMethodChanged(entry),
                // null (none said yet): kept as they are, not taken for punctuation off (that drew , where 、 was typed)
                HostEvent.StatusArea(statusAreaCached, entry, punctuationCached, fullWidthCached),
                candidatesCached,
            )
        }.forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: HostEvent) {
        when (it) {
            is HostEvent.Candidates -> {
                broadcaster.onCandidateUpdate(it)
            }
            is HostEvent.ClientPreedit -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.text)
                broadcaster.onClientPreeditUpdate(it.text)
            }
            is HostEvent.InputPanel -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.preedit)
                broadcaster.onInputPanelUpdate(it)
            }
            is HostEvent.InputMethodChanged -> {
                updateCodeRow(it.im)
                broadcaster.onImeUpdate(it.entry)
            }
            is HostEvent.PunctuationAsTyped -> punctuation.updateAsTyped(it.marks)
            is HostEvent.Ready -> {
                punctuation.updateAsTyped(emptySet())
                fcitx.runImmediately { punctuationCached to fullWidthCached }.let { (keys, full) -> punctuation.update(keys, full) }
            }
            is HostEvent.StatusArea -> {
                punctuation.update(it.punctuation, it.fullWidth)
                broadcaster.onStatusAreaUpdate(it.entries)
            }
            else -> {}
        }
    }

    private fun showCodeStrip() {
        val strip = codeStrip?.takeIf { (it.visibility == GONE) == (preedit.ui.visible || preedit.ui.syllables.isVisible) } ?: return
        strip.isVisible = strip.visibility == GONE
        // at once, not on the next frame: the touchable region of this one is worked out first
        placeCodeStrip()
        if (!strip.isVisible) undoStripMoved()
    }

    fun codePlaceChanged() = preedit.codePlaceChanged()

    private fun updateCodeRow(im: String) {
        codeStrip?.let { strip ->
            val h = dp(KawaiiBarComponent.stripHeight(im))
            if (strip.layoutParams.height != h) strip.updateLayoutParams { height = h }
        }
        val rowDp = KawaiiBarComponent.preeditHeight(im)
        if (rowDp == codeRowDp) return
        codeRowDp = rowDp
        preedit.ui.root.updateLayoutParams { height = dp(rowDp) }
        updateKeyboardSize()
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    fun cursorMoved() = keyboardWindow.cursorMoved()

    fun offerCalculation(worked: Calculation.Worked, at: Int) = kawaiiBar.offerCalculation(worked, at)

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) FloatingHandoff.newTouch(parent)
        return super.onInterceptTouchEvent(ev)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        floatingController.attached()
        if (codeStrip != null) viewTreeObserver.addOnPreDrawListener(codeStripPlacer)
        // the scopes are torn down on detach; a re-attached view would silently do nothing
        if (!viewScope.isActive) Timber.e(IllegalStateException("InputView re-attached after detach"))
    }

    override fun onDetachedFromWindow() = SceneTrace.section("InputView detached") {
        viewTreeObserver.removeOnPreDrawListener(codeStripPlacer)
        floatingController.detached()
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        ThemeManager.prefs.splitDock.unregisterOnChangeListener(onSplitDockChangeListener)
        KeyboardStyles.styleKey(floatingController.screenBuilt.landscape).unregisterOnChangeListener(onStyleChangeListener)
        ThemeManager.prefs.oneHandedSide.unregisterOnChangeListener(onStyleChangeListener)
        // a replaced view's windows are never detached: what they had going, the microphone above all, stops here
        broadcaster.onFinishInput()
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        viewScope.cancel()
        super.onDetachedFromWindow()
    }

}
