/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.annotation.CallSuper
import androidx.annotation.Keep
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
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
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
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
) : ConstraintLayout(context) {

    var keyActionListener: KeyActionListener? = null

    private val prefs = AppPrefs.getInstance()

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea
    private val swipeSymbolDirection by prefs.keyboard.swipeSymbolDirection

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceKeys = mutableListOf<KeyView>()
    @Keep
    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        spaceKeys.forEach {
            it.swipeEnabled = v
        }
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

    private val vivoKeypressWorkaround by prefs.advanced.vivoKeypressWorkaround

    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    var popupActionListener: PopupActionListener? = null

    private val selectionSwipeThreshold = dp(10f)
    private val inputSwipeThreshold = dp(36f)

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    protected val keyRows: List<ConstraintLayout>

    init {
        isMotionEventSplittingEnabled = true
        keyRows = keyLayout.mapIndexed { rowIndex, row ->
            // room between a split keyboard's halves: nothing to touch (a plain view takes no touch, and
            // findTouchTarget, vivo's, finds key views only)
            val keyViews = row.map { if (it === GapKey) View(context) else createKeyView(it) }
            val widths = keyWidths?.get(rowIndex) ?: row.map { it.appearance.percentWidth }
            constraintLayout Row@{
                isMotionEventSplittingEnabled = true
                var totalWidth = 0f
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
                    widths[index].let {
                        // 0f means fill remaining space, thus does not need expanding
                        totalWidth += if (it != 0f) it else 1f
                    }
                }
                // a split row fills the keyboard by itself (SplitKeyboard.row)
                if (expandKeypressArea && keyWidths == null && totalWidth < 1f) {
                    val free = (1f - totalWidth) / 2f
                    // a gap at an edge is no key to widen
                    (keyViews.first() as? KeyView)?.apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginLeft = free / (widths.first() + free)
                    }
                    (keyViews.last() as? KeyView)?.apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginRight = free / (widths.last() + free)
                    }
                }
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
        spaceKeys.forEach { (it as? TextKeyView)?.showBadge(icon) }
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

    private fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, def.appearance)
        }.apply {
            soundEffect = when (def) {
                is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
                is ReturnKey -> InputFeedbacks.SoundEffect.Return
                else -> InputFeedbacks.SoundEffect.Standard
            }
            if (def is SpaceKey) {
                spaceKeys.add(this)
                swipeEnabled = spaceSwipeMoveCursor.getValue()
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
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
                        setOnClickListener { _ ->
                            onAction(it.action)
                        }
                    }
                    is KeyDef.Behavior.LongPress -> {
                        // hold to talk follows the finger that holds the space
                        setOnLongClickListener { view ->
                            val action = it.action
                            onAction(if (action is KeyAction.SpaceLongPressAction) action.copy(pointer = (view as CustomGestureView).touchPointer) else action)
                            true
                        }
                    }
                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { view ->
                            onAction(it.action)
                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                        }
                    }
                    is KeyDef.Behavior.Swipe -> {
                        swipeEnabled = true
                        swipeThresholdX = disabledSwipeThreshold
                        swipeThresholdY = inputSwipeThreshold
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            when (event.type) {
                                GestureType.Up -> {
                                    if (event.isSwipe(swipeSymbolDirection)) {
                                        swipeText(view as KeyView).typed(it.action)?.let { (action, source) -> onAction(action, source) }
                                        // consumed when it types nothing too, or a swipe that stayed on the key would type it as a tap
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { _ ->
                            onAction(it.action)
                        }
                    }
                }
            }
            def.popup?.forEach { popup ->
                when (popup) {
                    // TODO: gesture processing middleware
                    is KeyDef.Popup.Menu -> {
                        setOnLongClickListener { view ->
                            view as KeyView
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
                                        val triggered = swipeSymbolDirection.checkY(event.totalY)
                                        val text = if (triggered) swipeText(view).preview(popup.alternative) else popup.content
                                        onPopupAction(
                                            PopupAction.PreviewUpdateAction(view.id, text)
                                        )
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
        val action = triggerAction.outAction ?: return false
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

/** Whether this Up ends a swipe [direction] types on: not one taken already (by a long press, say), nor cancelled. */
internal fun CustomGestureView.Event.isSwipe(direction: SwipeSymbolDirection) =
    !consumed && !cancelled && direction.checkY(totalY)

/** What the Space key shows of an input method: its [name], and [subMode]'s label (or else its name) in brackets. */
internal fun spaceLabel(name: String, subMode: InputMethodSubMode): String {
    val mode = subMode.label.ifEmpty { subMode.name }
    return if (mode.isEmpty()) name else "$name ($mode)"
}
