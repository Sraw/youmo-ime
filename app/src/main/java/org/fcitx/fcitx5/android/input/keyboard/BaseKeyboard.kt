/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.CallSuper
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.input.theme.KeyStyleResolver
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupKeyboardUi
import org.fcitx.fcitx5.android.input.voice.VoiceFeature
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add

abstract class BaseKeyboard(
    context: Context,
    protected val theme: Theme,
    private val keyLayout: List<List<KeyDef>>,
    /** Each key's width in place of its own ([KeyDef.Appearance.percentWidth]): a split layout's. */
    private val keyWidths: List<List<Float>>? = null,
    /** Its keys' board in a theme ([KeyNames]: `qwerty`, `nine`…), each key named by [KeyDef.name]; null, styled by kind alone. */
    val themeBoard: String? = null,
    /** Whether its keys' swipes reach their shortcuts ([KeyDef.shortcut]): the typing keyboards', not a panel's. */
    private val shortcuts: Boolean = false,
) : ConstraintLayout(context) {

    var keyActionListener: KeyActionListener? = null

    private val prefs = AppPrefs.getInstance()

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceKeys = mutableListOf<KeyView>()

    // the theme's key images this keyboard draws, each file once: decoded off the main thread, the keys
    // drawn as their kinds till they are in (ThemeAssets)
    protected val keyImages: BoardImages by lazy {
        val look = theme.look
        // no board name (the number row, a search's keys): the kinds' and the roles' images still
        val board = themeBoard
        // its keys' and the places it draws apart from them (the nine keys' column)
        val names = (keyLayout.flatten().mapNotNull { it.name } + board?.let { KeyNames.POSITIONS[it] }.orEmpty()).distinct()
        ThemeAssets.boardImages(look, board, KeyStyleResolver.imagesOn(look.keys, board, names))
    }

    @Keep
    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        spaceKeys.forEach { spaceSwipesCursor(it, v) }
    }

    // a space with shortcuts swipes up whether it moves the cursor or not: only its sideways steps go
    private fun spaceSwipesCursor(view: KeyView, on: Boolean) {
        if (!shortcuts) {
            view.swipeEnabled = on
            return
        }
        view.swipeEnabled = true
        view.swipeRepeatEnabled = on
        view.swipeThresholdX = if (on) selectionSwipeThreshold else disabledSwipeThreshold
    }

    private val spaceLongPress = prefs.keyboard.spaceKeyLongPressBehavior

    @Keep
    private val spaceLongPressListener = ManagedPreference.OnChangeListener<SpaceLongPressBehavior> { _, v ->
        updateSpaceBadges(v)
    }

    /** Set per field ([KeyboardWindow]): no hold to talk in a password (CommonKeyActionListener), so no badge. */
    var inPasswordField = false
        set(value) {
            if (field == value) return
            field = value
            updateSpaceBadges(spaceLongPress.getValue())
        }

    /** Set per field ([KeyboardWindow]): an address's has no glide typing, as no word is typed there. */
    open var address: AddressKeys = AddressKeys.NONE

    private val glideTyping = prefs.keyboard.glideTyping
    private val strokeFilter = prefs.keyboard.strokeFilter

    /**
     * Set as the input method has something typed or not, a temporary mode's (快速输入) not counted
     * ([KeyboardWindow]): a stroke drawn then filters its candidates (笔画筛选).
     */
    var imTyping = false
    private val longPressDelay by prefs.keyboard.longPressDelay

    /**
     * What a finger drawn over the layout's letters reads, if it has any ([glideInput]): glide typing
     * (滑行输入), a stroke for 笔画筛选, or both; a layout with no glide (the grouped keys) has it for the strokes alone.
     */
    protected open val glide: GlideInput? = null

    /** Whether the layout shows the syllables to take itself (九键's column): the code row's then show none. */
    open val ownsSyllables: Boolean get() = false

    /**
     * Glide typing over [letters], taken where [enabled] says and 滑行输入 is on, in a field where
     * words are typed: not a password's or an address's. Where 笔画筛选 is on instead (the settings
     * page keeps the two from being on together), a stroke drawn over them while pinyin is typed in an input method [filters] says reads
     * one: sent as the engine's stroke key (Keyboard.strokeKey), the shape StrokeShape's; one it
     * reads as none is dropped.
     */
    protected fun glideInput(
        letters: () -> List<Pair<Char, TextKeyView>>,
        down: Float = GlideGesture.DOWN,
        enabled: () -> Boolean = { true },
        filters: () -> Boolean = { false },
        glided: (path: FloatArray, layout: GlideLayout) -> Unit,
    ) = GlideInput(
        this, theme,
        enabled = { glideTyping.getValue() && wordField() && enabled() },
        letters = letters,
        longPressMillis = { longPressDelay.toLong() },
        down = down,
        strokes = { strokeFilter.getValue() && wordField() && imTyping && filters() },
        stroked = { path -> StrokeShape.classify(path)?.let { onAction(KeyAction.SymAction(KeySym(UNICODE + Keyboard.strokeKey(it)), KeyStates.Virtual)) } },
        glided = glided,
    )

    private fun wordField() = !inPasswordField && address == AddressKeys.NONE

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        glide?.dispatch(ev) { super.dispatchTouchEvent(it) } ?: super.dispatchTouchEvent(ev)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        glide?.draw(canvas)
    }

    private val vivoKeypressWorkaround by prefs.advanced.vivoKeypressWorkaround

    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    var popupActionListener: PopupActionListener? = null

    private val selectionSwipeThreshold = dp(10f)
    private val inputSwipeThreshold = dp(36f)

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    private val swipes = KeySwipes(this, shortcuts, inputSwipeThreshold, disabledSwipeThreshold, object : KeySwipes.Keyboard {
        override val inPasswordField get() = this@BaseKeyboard.inPasswordField
        override val charShortcutMarks get() = this@BaseKeyboard.charShortcutMarks
        override fun swipeText(view: KeyView) = this@BaseKeyboard.swipeText(view)
        override fun onAction(action: KeyAction, source: KeyActionListener.Source) = this@BaseKeyboard.onAction(action, source)
        override fun onPopupAction(action: PopupAction) = this@BaseKeyboard.onPopupAction(action)
        override fun onPopupChangeFocus(viewId: Int, x: Float, y: Float) = this@BaseKeyboard.onPopupChangeFocus(viewId, x, y)
        override fun onPopupTrigger(viewId: Int, cancelled: Boolean) = this@BaseKeyboard.onPopupTrigger(viewId, cancelled)
    })

    protected val keyRows: List<ConstraintLayout>

    /** A row's views and their own widths, for [fitRow] again once a key is shown or hidden. */
    private class Row(val keys: List<View>, val widths: List<Float>)

    private val rowKeys = mutableListOf<Row>()

    /**
     * A row narrower than the keyboard: its outermost keys shown take the room left at the edges,
     * their labels where they were. Only keys shown count, so a key hidden (the 双拼 `;`) leaves
     * the row's ends widened again.
     */
    private fun fitRow(keyViews: List<View>, widths: List<Float>) {
        val shown = keyViews.indices.filter { keyViews[it].visibility != View.GONE }.ifEmpty { return }
        // back to their own widths first: fitted before, they may no longer be at an end
        keyViews.forEachIndexed { i, view ->
            if (view !is KeyView) return@forEachIndexed
            view.updateLayoutParams<ConstraintLayout.LayoutParams> { matchConstraintPercentWidth = widths[i] }
            view.layoutMarginLeft = 0f
            view.layoutMarginRight = 0f
        }
        // 0f means fill remaining space, thus does not need expanding
        val totalWidth = shown.sumOf { (widths[it].takeIf { w -> w != 0f } ?: 1f).toDouble() }.toFloat()
        // a split row fills the keyboard by itself (SplitKeyboard.row)
        if (!expandKeypressArea || keyWidths != null || totalWidth >= 1f) return
        val free = (1f - totalWidth) / 2f
        val first = shown.first()
        val last = shown.last()
        // a gap at an edge is no key to widen
        (keyViews[first] as? KeyView)?.apply {
            updateLayoutParams<ConstraintLayout.LayoutParams> { matchConstraintPercentWidth += free }
            layoutMarginLeft = free / (widths[first] + free)
        }
        (keyViews[last] as? KeyView)?.apply {
            updateLayoutParams<ConstraintLayout.LayoutParams> { matchConstraintPercentWidth += free }
            layoutMarginRight = free / (widths[last] + free)
        }
    }

    /** [view] shown or not, its row fitted again ([fitRow]). */
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    internal fun showKey(view: View, shown: Boolean) {
        if (view.isVisible == shown) return
        view.isVisible = shown
        rowKeys.firstOrNull { view in it.keys }?.let { fitRow(it.keys, it.widths) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        settleLetters()
    }

    // after the keys' own layout: their letters clear of their symbols and shortcuts (KeyMarkYield.letters), at one
    // size and on one baseline (a letter moved alone sat out of its row: floating at 紧凑, w lower, a left)
    private fun settleLetters() {
        val keys = rowKeys.flatMap { it.keys }.filterIsInstance<AltTextKeyView>().filter { it.isVisible }
            .mapNotNull { k -> k.letterKey()?.let { k to it } }
        val letters = KeyMarkYield.letters(keys.map { it.second }, gap = dp(1).toFloat())
        keys.forEachIndexed { i, (k, _) -> k.settle(letters.keys[i]) }
    }

    init {
        isMotionEventSplittingEnabled = true
        keyRows = keyLayout.mapIndexed { rowIndex, row ->
            // room between a split keyboard's halves: nothing to touch (a plain view takes no touch, and
            // findTouchTarget, vivo's, finds key views only)
            val keyViews = row.map { if (it === GapKey) View(context) else createKeyView(it) }
            val widths = keyWidths?.get(rowIndex) ?: row.map { it.appearance.percentWidth }
            rowKeys += Row(keyViews, widths)
            constraintLayout Row@{
                isMotionEventSplittingEnabled = true
                keyViews.forEachIndexed { index, view ->
                    add(view, lParams {
                        centerVertically()
                        if (index == 0) {
                            leftOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            leftToRightOf(keyViews[index - 1])
                        }
                        if (index == keyViews.size - 1) {
                            rightOfParent()
                            // for RTL
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            rightToLeftOf(keyViews[index + 1])
                        }
                        matchConstraintPercentWidth = widths[index]
                    })
                }
                fitRow(keyViews, widths)
            }
        }
        keyRows.forEachIndexed { index, row ->
            add(row, lParams {
                if (index == 0) topOfParent()
                else below(keyRows[index - 1])
                if (index == keyRows.size - 1) bottomOfParent()
                else above(keyRows[index + 1])
                centerHorizontally()
            })
        }
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
        updateSpaceBadges(spaceLongPress.getValue())
        spaceLongPress.registerOnChangeListener(spaceLongPressListener)
    }

    // holding the space to talk: a microphone on every keyboard's space says so, or no one would know
    private fun updateSpaceBadges(behavior: SpaceLongPressBehavior) {
        val voice = behavior.talks(VoiceFeature.AVAILABLE, inPasswordField)
        val icon = if (voice) R.drawable.ic_baseline_keyboard_voice_24 else null
        // a long press that does nothing here buzzes for nothing either
        val takes = spaceLongPressTakesTouch(behavior, VoiceFeature.AVAILABLE, inPasswordField)
        spaceKeys.forEach {
            (it as? TextKeyView)?.showBadge(icon)
            it.longPressFeedbackEnabled = takes
        }
    }

    /** What swiping a key types: its own swipe, nothing, or what the user chose ([swipeText]). */
    sealed interface SwipeChoice {
        /** The key's own [KeyDef.Behavior.Swipe], previewed as [KeyDef.Popup.AltPreview.alternative]. */
        data object BuiltIn : SwipeChoice

        /** Nothing: the user left the key nothing to swipe to (its long press is off). */
        data object None : SwipeChoice

        /** The first of the long press, typed as the long press types it. */
        data class Text(val text: String) : SwipeChoice

        /** What the swipe's Up types, and from where: null for [None]. */
        fun typed(builtIn: KeyAction): Pair<KeyAction, KeyActionListener.Source>? = when (this) {
            BuiltIn -> builtIn to KeyActionListener.Source.Keyboard
            None -> null
            is Text -> PopupKeyboardUi.keyAction(text) to KeyActionListener.Source.Popup
        }

        /** What the preview shows once the finger has swiped: nothing for [None], as nothing is typed. */
        fun preview(alternative: String): String = when (this) {
            BuiltIn -> alternative
            None -> ""
            is Text -> text
        }

        companion object {
            /** What [TextKeyboard] found for a key (swipeOf): null for [BuiltIn], "" for [None]. */
            fun of(text: String?): SwipeChoice = when {
                text == null -> BuiltIn
                text.isEmpty() -> None
                else -> Text(text)
            }
        }
    }

    /** What swiping [view] types where the user chose it ([TextKeyboard]): [SwipeChoice.BuiltIn] for what its key says. */
    protected open fun swipeText(view: KeyView): SwipeChoice = SwipeChoice.BuiltIn

    /** What a tap on [view] sends for its [action]: the action itself, but where a keyboard adds where it fell ([CustomGestureView.clickAt]). */
    protected open fun pressed(view: CustomGestureView, action: KeyAction): KeyAction = action

    private fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, def.appearance)
        }.apply {
            themeKey = def.name
            style(themeBoard, keyImages)
            soundEffect = when (def) {
                is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
                is ReturnKey -> InputFeedbacks.SoundEffect.Return
                else -> InputFeedbacks.SoundEffect.Standard
            }
            if (def is BlankKey) {
                isEnabled = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            // the number pad's space moves the cursor too (UAT PAN-14): it had the press only
            if (def is SpaceKey || def is MiniSpaceKey) {
                spaceKeys.add(this)
                swipeEnabled = spaceSwipeMoveCursor.getValue()
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                spaceSwipesCursor(this, spaceSwipeMoveCursor.getValue())
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> when (val count = event.countX) {
                            0 -> false
                            else -> {
                                val sym =
                                    if (count > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                                val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                repeat(count.absoluteValue) {
                                    onAction(action)
                                    if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                }
                                true
                            }
                        }
                        else -> false
                    }
                }
            } else if (def is BackspaceKey) {
                swipeEnabled = true
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        // must stay reachable: it resets what a cancelled swipe left behind
                        GestureType.Down -> {
                            onAction(KeyAction.BeginSelectionSwipeAction)
                            false
                        }
                        GestureType.Move -> {
                            val count = event.countX
                            if (count != 0) {
                                onAction(KeyAction.MoveSelectionAction(count))
                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                true
                            } else false
                        }
                        GestureType.Up -> {
                            // cancelled, the selection stays selected; the next Down resets the swipe
                            if (!event.cancelled) onAction(KeyAction.DeleteSelectionAction(event.totalX))
                            false
                        }
                        else -> false
                    }
                }
            }
            def.behaviors.forEach {
                when (it) {
                    is KeyDef.Behavior.Press -> {
                        setOnClickListener { view ->
                            onAction(pressed(view as CustomGestureView, it.action))
                        }
                    }
                    is KeyDef.Behavior.LongPress -> {
                        // hold to talk follows the finger that holds the space
                        setOnLongClickListener { view ->
                            val action = it.action
                            if (action is KeyAction.SpaceLongPressAction && !spaceLongPressTakesTouch(spaceLongPress.getValue(), VoiceFeature.AVAILABLE, inPasswordField)) {
                                // nothing to do: not taken, or a slow drag on the space, its first step past the delay, moved no cursor (PY-41)
                                false
                            } else {
                                onAction(if (action is KeyAction.SpaceLongPressAction) action.copy(pointer = (view as CustomGestureView).touchPointer) else action)
                                true
                            }
                        }
                    }
                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { view ->
                            onAction(it.action)
                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                        }
                    }
                    // its lift is the swipe's, below ([KeySwipes.attach])
                    is KeyDef.Behavior.Swipe -> {}
                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { _ ->
                            onAction(it.action)
                        }
                    }
                }
            }
            // set as its long press shows a popup, which then has the touch: no swipe of the key's after that
            val longPopup = booleanArrayOf(false)
            swipes.attach(this, def, longPopup)
            def.popup?.forEach { popup ->
                when (popup) {
                    // TODO: gesture processing middleware
                    is KeyDef.Popup.Menu -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            longPopup[0] = true
                            onPopupAction(PopupAction.ShowMenuAction(view.id, popup, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id, event.cancelled)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Keyboard -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            longPopup[0] = true
                            onPopupAction(PopupAction.ShowKeyboardAction(view.id, popup, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id, event.cancelled)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.AltPreview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, popup.content, view.bounds)
                                    )
                                    GestureType.Move -> {
                                        onPopupAction(swipes.preview(view, def, event.totalY, popup))
                                    }
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Preview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, popup.content, view.bounds)
                                    )
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                    else -> {}
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                }
            }
        }
    }

    private class TouchTarget(val view: KeyView, val hitRect: Rect)

    /**
     * HashMap of [PointerId (Int)][MotionEvent.getPointerId] to [TouchTarget]
     * for custom touch event dispatching
     */
    private val touchTargets = hashMapOf<Int, TouchTarget>()

    private fun releaseAllTouchTargets() {
        touchTargets.forEach {
            val keyView = it.value.view
            keyView.cancelGestures()
            onPopupAction(PopupAction.DismissAction(keyView.id))
        }
        touchTargets.clear()
    }

    private fun findTouchTarget(event: MotionEvent, pointerIndex: Int): TouchTarget? {
        val x0 = event.getX(pointerIndex).roundToInt()
        val y0 = event.getY(pointerIndex).roundToInt()
        val rowHitRect = Rect()
        val row = keyRows.find {
            it.getHitRect(rowHitRect)
            rowHitRect.contains(x0, y0)
        } ?: return null
        val x1 = x0 - rowHitRect.left
        val y1 = y0 - rowHitRect.top
        val keyHitRect = Rect()
        val key = row.children.filterIsInstance<KeyView>().find {
            it.getHitRect(keyHitRect)
            keyHitRect.contains(x1, y1)
        } ?: return null
        keyHitRect.offset(rowHitRect.left, rowHitRect.top)
        return TouchTarget(key, keyHitRect)
    }

    /** Gives finger [pointerIndex] of [event] to [target] for the rest of its touch. */
    private fun dispatchDown(event: MotionEvent, pointerIndex: Int, target: TouchTarget) {
        // a disabled key takes no touch, as on the system's dispatch: CustomGestureView refuses its Down, not its Up
        if (!target.view.isEnabled) return
        touchTargets[event.getPointerId(pointerIndex)] = target
        dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, pointerIndex, target)
    }

    private fun dispatchMotionEventToTarget(
        event: MotionEvent,
        action: Int,
        pointerIndex: Int,
        target: TouchTarget
    ) {
        val keyEvent = event.forKey(action, pointerIndex, target.hitRect)
        target.view.dispatchTouchEvent(keyEvent)
        // the key reads it in place and keeps none of it: back to the pool, not left to the finalizer
        keyEvent.recycle()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // intercept ACTION_DOWN and all following events will go to parent's onTouchEvent
        return if (vivoKeypressWorkaround && ev.actionMasked == MotionEvent.ACTION_DOWN) true
        else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (vivoKeypressWorkaround) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    releaseAllTouchTargets()
                    // on no key the touch is not the keyboard's; on a disabled one it is, for a finger after it
                    val target = findTouchTarget(event, 0) ?: return false
                    dispatchDown(event, 0, target)
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val target = findTouchTarget(event, i) ?: return true
                    dispatchDown(event, i, target)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val pid = event.getPointerId(i)
                        val target = touchTargets[pid] ?: continue
                        dispatchMotionEventToTarget(event, MotionEvent.ACTION_MOVE, i, target)
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTargets[pid] ?: return true
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, i, target)
                    touchTargets.remove(pid)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val pid = event.getPointerId(0)
                    val target = touchTargets[pid]
                    if (target == null) {
                        releaseAllTouchTargets()
                        return true
                    }
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, 0, target)
                    touchTargets.remove(pid)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    releaseAllTouchTargets()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    @CallSuper
    protected open fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        keyActionListener?.onKeyAction(action, source)
    }

    @CallSuper
    protected open fun onPopupAction(action: PopupAction) {
        popupActionListener?.onPopupAction(action)
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int, cancelled: Boolean): Boolean {
        if (cancelled) {
            // consumed: the listeners under this one would dismiss it a second time
            onPopupAction(PopupAction.DismissAction(viewId))
            return true
        }
        val triggerAction = PopupAction.TriggerAction(viewId)
        // ask popup keyboard whether there's a pending KeyAction
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return triggerAction.outConsumed
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    /** The input method name last shown on the Space key; null until the first update. */
    private var shownImeName: String? = null

    /** Shows [ime] on [space]: its name, and its sub-mode's ([spaceLabel]). */
    protected fun showInputMethodName(space: TextKeyView, ime: InputMethodEntry) {
        val imeName = spaceLabel(InputMethodNames.of(context, ime), ime.subMode)
        // A change of input method (or of its sub-mode, e.g. an ASCII toggle) is otherwise
        // silent: the name is only on the Space key, which the user is not touching. The first
        // update is the keyboard appearing, which TalkBack announces itself. A change made while
        // another layout was showing is announced on returning to this one.
        if (shownImeName != null && shownImeName != imeName) announce(imeName)
        shownImeName = imeName
        space.mainText.text = imeName
        // TalkBack reads the key's description instead of the text drawn on it, so the name
        // has to be in the description too
        space.contentDescription = context.getString(R.string.a11y_key_space_with_ime, imeName)
        // the label's text and this description, changed together, go out as one event for the key
        // with no subtree change: the accessibility cache kept the key's old node, and the old name
        // was read for about a second after a switch (to 拼音 with 英文标点 on; with 中文标点 the
        // punctuation keys' relabel happened to bring a subtree change along). Said outright here
        space.parent?.notifySubtreeAccessibilityStateChanged(space, space, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
    }

    /**
     * `announceForAccessibility` is deprecated from API 36 in favour of describing the UI
     * (state descriptions, live regions). Neither fits the input method name: it is on the
     * Space key, which the user is not touching, and a live region there would speak every
     * time the keyboard is rebuilt.
     */
    @Suppress("DEPRECATION")
    private fun announce(text: CharSequence?) {
        if (!text.isNullOrEmpty()) announceForAccessibility(text)
    }

    open fun onAttach() {
        // do nothing by default
    }

    // the marks drawn once the subclass is made: they read its corners and symbols (swipeText)
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showSwipeMarks()
    }

    /** Whether the character keys draw their shortcuts' marks: not where their faces have no room ([TextKeyboard]'s roots). */
    protected open val charShortcutMarks: Boolean get() = true

    /** The keys' swipe marks drawn again, as what [charShortcutMarks] or the corners read changed. */
    protected fun showSwipeMarks() {
        if (shortcuts) swipes.showMarks()
    }

    open fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        // do nothing by default
    }

    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        // do nothing by default
    }

    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        // do nothing by default
    }

    open fun onDetach() {
        releaseAllTouchTargets()
    }

}

/**
 * Finger [pointerIndex] of this event alone, as [action], in the coordinates of the key at [hitRect].
 * Its id stays the finger's: hold to talk follows that id in the real events (VoiceHoldOverlay).
 */
internal fun MotionEvent.forKey(action: Int, pointerIndex: Int, hitRect: Rect): MotionEvent {
    KeyPointer.properties[0].id = getPointerId(pointerIndex)
    KeyPointer.coords[0].apply {
        x = getX(pointerIndex) - hitRect.left
        y = getY(pointerIndex) - hitRect.top
        pressure = getPressure(pointerIndex)
        size = getSize(pointerIndex)
    }
    return MotionEvent.obtain(
        downTime, eventTime, action, 1, KeyPointer.properties, KeyPointer.coords,
        metaState, 0, xPrecision, yPrecision, deviceId, edgeFlags, InputDevice.SOURCE_UNKNOWN, 0
    )
}

// one of each, refilled for every event: obtain copies them, and touches come on the main thread alone
private object KeyPointer {
    val properties = arrayOf(MotionEvent.PointerProperties())
    val coords = arrayOf(MotionEvent.PointerCoords())
}

/**
 * Whether the space's long press, set to [behavior], takes the touch from its swipe: not when it does
 * nothing, as None does, and Voice where it does not talk ([SpaceLongPressBehavior.talks]).
 */
internal fun spaceLongPressTakesTouch(behavior: SpaceLongPressBehavior, voiceAvailable: Boolean, inPasswordField: Boolean) = when (behavior) {
    SpaceLongPressBehavior.None -> false
    SpaceLongPressBehavior.Voice -> behavior.talks(voiceAvailable, inPasswordField)
    else -> true
}

/** What the Space key shows of an input method: its [name], and [subMode]'s label (or else its name) in brackets. */
internal fun spaceLabel(name: String, subMode: InputMethodSubMode): String {
    val mode = subMode.label.ifEmpty { subMode.name }
    return if (mode.isEmpty()) name else "$name ($mode)"
}
