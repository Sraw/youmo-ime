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
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.input.theme.KeyStyleResolver
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneTouch
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupKeyboardUi
import org.fcitx.fcitx5.android.input.popup.keyRef
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.theme.ThemeLook
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
    private val spaceKeys = mutableListOf<KeyHandle>()


    /** Holding the space talks here ([updateSpaceBadges]): the microphone on it. Before init, which sets it. */
    protected var holdsToTalk = false
        private set

    // the theme's key images this keyboard draws, each file once: decoded off the main thread, the keys
    // drawn as their kinds till they are in (ThemeAssets)
    protected val keyImages: BoardImages by lazy {
        val look = ThemeManager.shownLook(theme)
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
    private fun spaceSwipesCursor(key: KeyHandle, on: Boolean) {
        val g = key.keyGesture
        if (!shortcuts) {
            g.swipeEnabled = on
            return
        }
        g.swipeEnabled = true
        g.swipeRepeatEnabled = on
        g.swipeThresholdX = if (on) selectionSwipeThreshold else disabledSwipeThreshold
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

    /**
     * The bottom row's address keys as [address] says, for a keyboard that has them (the letters', the
     * grouped and 注音's): the comma and period in an ordinary field, the dot, slash or @ and domain in an address.
     */
    protected fun showAddressKeys() {
        findViewById<View>(R.id.button_comma).isVisible = address == AddressKeys.NONE
        findViewById<View>(R.id.button_period).isVisible = address == AddressKeys.NONE
        findViewById<View>(R.id.button_dot).isVisible = address != AddressKeys.NONE
        findViewById<View>(R.id.button_slash).isVisible = address == AddressKeys.URL
        findViewById<View>(R.id.button_at).isVisible = address == AddressKeys.EMAIL
        findViewById<View>(R.id.button_domain).isVisible = address != AddressKeys.NONE
    }

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
        letters: () -> List<Pair<Char, KeyPlace>>,
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
        override fun swipeText(def: KeyDef) = this@BaseKeyboard.swipeText(def)
        override fun onAction(action: KeyAction, source: KeyActionListener.Source) = this@BaseKeyboard.onAction(action, source)
        override fun onPopupAction(action: PopupAction) = this@BaseKeyboard.onPopupAction(action)
        override fun onPopupChangeFocus(key: KeyRef, x: Float, y: Float) = this@BaseKeyboard.onPopupChangeFocus(key, x, y)
        override fun onPopupTrigger(key: KeyRef, cancelled: Boolean) = this@BaseKeyboard.onPopupTrigger(key, cancelled)
    })

    protected val keyRows: List<ConstraintLayout>

    /** A row's views and their own widths, for [fitRow] again once a key is shown or hidden. */
    private class Row(val keys: List<View>, val widths: List<Float>)

    private val rowKeys = mutableListOf<Row>()

    // a row narrower than the keyboard widens its outermost keys ([fitRow]); a split row fills it by itself (SplitKeyboard.row)
    private val widens get() = expandKeypressArea && keyWidths == null

    /** [view] shown or not, its row fitted again ([fitRow]). */
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    internal fun showKey(view: View, shown: Boolean) {
        if (view.isVisible == shown) return
        view.isVisible = shown
        rowKeys.firstOrNull { view in it.keys }?.let { fitRow(it.keys, it.widths, widens) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // under the canvas the views' glyphs are not drawn: nothing to settle (laid out again without it)
        if (canvas == null) settleLetters()
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
                fitRow(keyViews, widths, widens)
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
        holdsToTalk = voice
        canvas?.invalidateScene()
        val icon = if (voice) R.drawable.ic_baseline_keyboard_voice_24 else null
        // a long press that does nothing here buzzes for nothing either
        val takes = spaceLongPressTakesTouch(behavior, VoiceFeature.AVAILABLE, inPasswordField)
        spaceKeys.forEach {
            (it as? TextKeyView)?.showBadge(icon)
            it.keyGesture.longPressFeedbackEnabled = takes
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

    /** What swiping the key of [def] types where the user chose it ([TextKeyboard]): [SwipeChoice.BuiltIn] for what its key says. */
    protected open fun swipeText(def: KeyDef): SwipeChoice = SwipeChoice.BuiltIn

    /** What a tap sends for its key's [action]: the action itself, but where a keyboard adds where it fell, [clickAt] ([KeyGesture.clickAt]). */
    protected open fun pressed(clickAt: Float, action: KeyAction): KeyAction = action

    private fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, def.appearance)
        }.apply {
            themeKey = def.name
            style(themeBoard, keyImages)
            if (def is BlankKey) {
                isEnabled = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            wire(this, def, this)
        }
    }

    /**
     * What [key] of [def] does, its view's ([view]) or a key the scene draws (060-render-p2 S1): its
     * sound, the space's and backspace's swipes, its press, long press, repeat and double tap, its
     * symbol's and shortcut's swipes ([KeySwipes]), its popups. One wiring for both, so the canvas's
     * keys do what the views did.
     */
    private fun wire(key: KeyHandle, def: KeyDef, view: KeyView?) {
        key.keyGesture.soundEffect = when (def) {
            // a layout's key: its node's sound (060-render-p2 S4)
            is SceneKeyDef -> def.sound
            is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
            is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
            is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
            is ReturnKey -> InputFeedbacks.SoundEffect.Return
            else -> InputFeedbacks.SoundEffect.Standard
        }
        // the number pad's space moves the cursor too (UAT PAN-14): it had the press only
        val space = def is SpaceKey || def is MiniSpaceKey || (def is SceneKeyDef && def.isSpace)
        if (space) wireSpace(key) else if (def is BackspaceKey || (def is SceneKeyDef && def.isBackspace)) wireBackspace(key)
        wireBehaviors(key, def)
        // set as its long press shows a popup, which then has the touch: no swipe of the key's after that
        val longPopup = LongPopup()
        swipes.attach(key, def, longPopup, view)
        def.popup?.forEach { wirePopup(key, def, it, longPopup) }
    }

    // the space's sideways swipe moves the cursor, as the setting says ([spaceSwipesCursor])
    private fun wireSpace(key: KeyHandle) {
        val g = key.keyGesture
        spaceKeys.add(key)
        // as updateSpaceBadges sets it: a key wired after it ran (the scene's, SceneKeys.byNodes) buzzes for nothing otherwise
        g.longPressFeedbackEnabled = spaceLongPressTakesTouch(spaceLongPress.getValue(), VoiceFeature.AVAILABLE, inPasswordField)
        g.swipeEnabled = spaceSwipeMoveCursor.getValue()
        g.swipeRepeatEnabled = true
        g.swipeThresholdX = selectionSwipeThreshold
        g.swipeThresholdY = disabledSwipeThreshold
        spaceSwipesCursor(key, spaceSwipeMoveCursor.getValue())
        key.gestureListener = { k, event ->
            when (event.type) {
                GestureType.Move -> when (val count = event.countX) {
                    0 -> false
                    else -> {
                        val sym =
                            if (count > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                        val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                        repeat(count.absoluteValue) {
                            onAction(action)
                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(k.feedback)
                        }
                        true
                    }
                }
                else -> false
            }
        }
    }

    // backspace swiped sideways selects what it deletes as it lifts
    private fun wireBackspace(key: KeyHandle) {
        val g = key.keyGesture
        g.swipeEnabled = true
        g.swipeRepeatEnabled = true
        g.swipeThresholdX = selectionSwipeThreshold
        g.swipeThresholdY = disabledSwipeThreshold
        key.gestureListener = { k, event ->
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
                        if (hapticOnRepeat) InputFeedbacks.hapticFeedback(k.feedback)
                        true
                    } else false
                }
                GestureType.Up -> {
                    // cancelled, the selection stays selected; the next Down resets the swipe
                    if (!event.cancelled) onAction(KeyAction.DeleteSelectionAction(event.totalX))
                    false
                }
            }
        }
    }

    private fun wireBehaviors(key: KeyHandle, def: KeyDef) {
        val g = key.keyGesture
        def.behaviors.forEach {
            when (it) {
                is KeyDef.Behavior.Press -> key.onClick { k -> onAction(pressed(k.keyGesture.clickAt, it.action)) }
                // hold to talk follows the finger that holds the space
                is KeyDef.Behavior.LongPress -> key.onLongClick { k ->
                    val action = it.action
                    if (action is KeyAction.SpaceLongPressAction && !spaceLongPressTakesTouch(spaceLongPress.getValue(), VoiceFeature.AVAILABLE, inPasswordField)) {
                        // nothing to do: not taken, or a slow drag on the space, its first step past the delay, moved no cursor (PY-41)
                        false
                    } else {
                        onAction(if (action is KeyAction.SpaceLongPressAction) action.copy(pointer = k.keyGesture.touchPointer) else action)
                        true
                    }
                }
                is KeyDef.Behavior.Repeat -> {
                    g.repeatEnabled = true
                    key.onRepeat { k ->
                        onAction(it.action)
                        if (hapticOnRepeat) InputFeedbacks.hapticFeedback(k.feedback)
                    }
                }
                // its lift is the swipe's, below ([KeySwipes.attach])
                is KeyDef.Behavior.Swipe -> {}
                is KeyDef.Behavior.DoubleTap -> {
                    g.doubleTapEnabled = true
                    key.onDoubleTap { _ -> onAction(it.action) }
                }
            }
        }
    }

    // its long press's popup, or its press's bubble
    private fun wirePopup(key: KeyHandle, def: KeyDef, popup: KeyDef.Popup, longPopup: LongPopup) {
        when (popup) {
            // TODO: gesture processing middleware
            is KeyDef.Popup.Menu -> {
                key.onLongClick { k ->
                    // a key no longer where the scene drawn last has it shows no menu, at the window's corner or anywhere
                    val bounds = k.bounds ?: return@onLongClick false
                    longPopup.shown = true
                    onPopupAction(PopupAction.ShowMenuAction(k.ref, popup, bounds))
                    // do not consume this LongClick gesture
                    false
                }
                choosesFromPopup(key)
            }
            is KeyDef.Popup.Keyboard -> {
                key.onLongClick { k ->
                    val bounds = k.bounds ?: return@onLongClick false
                    longPopup.shown = true
                    onPopupAction(PopupAction.ShowKeyboardAction(k.ref, popup, bounds))
                    // do not consume this LongClick gesture
                    false
                }
                choosesFromPopup(key)
            }
            is KeyDef.Popup.AltPreview -> {
                val old = key.gestureListener
                key.gestureListener = { k, event ->
                    if (popupOnKeyPress) {
                        when (event.type) {
                            GestureType.Down -> k.bounds?.let { onPopupAction(PopupAction.PreviewAction(k.ref, bubbleOf(k, popup), it)) }
                            GestureType.Move -> {
                                onPopupAction(swipes.preview(k, def, event.totalY, popup, bubbleOf(k, popup)))
                            }
                            GestureType.Up -> {
                                onPopupAction(PopupAction.DismissAction(k.ref))
                            }
                        }
                    }
                    // never consume gesture in preview popup
                    old?.invoke(k, event) ?: false
                }
            }
            is KeyDef.Popup.Preview -> {
                val old = key.gestureListener
                key.gestureListener = { k, event ->
                    if (popupOnKeyPress) {
                        when (event.type) {
                            GestureType.Down -> k.bounds?.let { onPopupAction(PopupAction.PreviewAction(k.ref, bubbleOf(k, popup), it)) }
                            GestureType.Up -> {
                                onPopupAction(PopupAction.DismissAction(k.ref))
                            }
                            else -> {}
                        }
                    }
                    // never consume gesture in preview popup
                    old?.invoke(k, event) ?: false
                }
            }
        }
    }

    // what a key's bubble shows: a scene key's as the scene has it now (SceneKey.bubble), else its def's
    private fun bubbleOf(k: KeyHandle, popup: KeyDef.Popup.Preview) = (k as? SceneKey)?.bubble ?: popup.content

    // a long press's popup (a menu, a keyboard), its cell focused as the finger moves and picked as it lifts
    private fun choosesFromPopup(key: KeyHandle) {
        val old = key.gestureListener
        key.keyGesture.swipeEnabled = true
        key.gestureListener = { k, event ->
            when (event.type) {
                GestureType.Move -> onPopupChangeFocus(k.ref, event.x, event.y)
                GestureType.Up -> onPopupTrigger(k.ref, event.cancelled)
                else -> false
            } || old?.invoke(k, event) ?: false
        }
    }

    // a key view's touch, the finger's event made the view's own ([forKey]) and dispatched to it
    private class ViewTarget(val view: KeyView, val hitRect: Rect) : TouchTarget {
        override val ref get() = view.keyRef
        override val enabled get() = view.isEnabled

        override fun send(event: MotionEvent, action: Int, pointerIndex: Int) {
            val keyEvent = event.forKey(action, pointerIndex, hitRect)
            view.dispatchTouchEvent(keyEvent)
            // the key reads it in place and keeps none of it: back to the pool, not left to the finalizer
            keyEvent.recycle()
        }

        override fun cancel() = view.cancelGestures()
    }

    /**
     * The keys drawn by the scene engine over this keyboard's own ([KeyboardCanvasView]), their views
     * not drawn then: the touches handed to the scene's keys ([sceneKeys]) as vivo's workaround hands
     * them to the views ([routesTouches]). Null: the keys draw themselves. Set by [drawKeysOn].
     */
    protected var canvas: KeyboardCanvasView? = null
        private set

    // the scene's keys ([drawKeysOn], [drawKeysWithScene]) and what a touch on the canvas finds
    private val sceneKeys = SceneKeys(this) { key, def -> wire(key, def, null) }

    /**
     * The board the scene engine draws this keyboard's keys from ([drawKeysWithScene], 060-render-p2
     * S4); null while the views draw them.
     */
    protected var sceneBoard: SceneBoard? = null
        private set

    /**
     * The keys drawn by the scene of the layout [layoutName] from now on, where the canvas is on
     * (internal.canvasKeyboard) and the keyboard is moved there (the app has its layout,
     * [BuiltInLayouts.has]); else the views go on drawing them, as they always did. A layout that
     * does not read ([SceneLayouts.of], logged there) draws nothing: never the views again (U8,
     * they go in S8). Called last in a keyboard's init: the scene reads its state ([sceneEnv]).
     * Each key wired as its layout's node says ([SceneKeyDef], [sceneTable] the keyboard's own words:
     * U4 B). The rows of views stay laid out, not drawn, not touched, not read out (S8 deletes them).
     * Whether the canvas is put over the keyboard.
     */
    protected fun drawKeysWithScene(layoutName: String): Boolean {
        if (!AppPrefs.getInstance().internal.canvasKeyboard.getValue() || !BuiltInLayouts.has(layoutName)) return false
        val layout = SceneLayouts.of(context, layoutName)
        val board = SceneBoard(context, theme, layout) { look, read, width, height -> sceneEnv(look, read, width, height) }
        sceneBoard = board
        val view = board.view(keyImages, { node -> sceneKey(node) }) { onSceneBuilt(it) }
        releaseAllTouchTargets()
        spaceKeys.removeAll { it is SceneKey }
        canvas = view
        sceneKeys.byNodes(view, sceneTable) { old -> spaceKeys.remove(old) }
        keyRows.forEach { it.visibility = View.INVISIBLE }
        add(view, lParams(0, 0) {
            topOfParent()
            bottomOfParent()
            leftOfParent()
            rightOfParent()
        })
        return true
    }

    /**
     * What this keyboard's keys show now as the scene engine's environment, in [look] at [width] ×
     * [height] ([SceneEnvs.of] with its own Input, texts, marks): asked for each frame whose scene
     * was invalidated ([KeyboardCanvasView.invalidateScene], every change of what the keys show).
     */
    protected open fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env =
        SceneEnvs.of(this, look, theme.isDark, width, height, Input(), marks = SceneBoard.marks(layout, charShortcutMarks))

    /** A scene made anew for the canvas ([scene]; null, none drawn): what a keyboard places by it, placed again. */
    protected open fun onSceneBuilt(scene: Scene?) {}

    /** The keyboard's own words for its layout's keys (U4 B): its `action:` placeholders, its composed long presses. */
    protected open val sceneTable: SceneKeyDef.Table = object : SceneKeyDef.Table {}

    /** Where the scene drawn last has its key [id] (its second [copy], a split row's) in this keyboard, as a key's place is (glide typing's, strokes'): nowhere while the views draw the keys. */
    protected fun scenePlace(id: String, copy: Boolean = false): KeyPlace = KeyPlace { _, out ->
        val c = canvas
        if (c != null && c.cell(KeyRef(id, copy), out)) out.offset(c.left, c.top) else out.setEmpty()
    }

    /** The key wired for the scene's [node] ([SceneTouch.keyOf]): what a screen reader's actions on it do; null while the views draw the keys. */
    internal fun sceneKey(node: KeyNode): SceneKey? = sceneKeys.of(node)

    // the keys' views take touches as the keyboard hands them: vivo's workaround, or keys drawn by the canvas
    private val routesTouches get() = vivoKeypressWorkaround || canvas != null

    /**
     * HashMap of [PointerId (Int)][MotionEvent.getPointerId] to [TouchTarget]
     * for custom touch event dispatching
     */
    private val touchTargets = hashMapOf<Int, TouchTarget>()

    private fun releaseAllTouchTargets() {
        touchTargets.forEach {
            val target = it.value
            target.cancel()
            onPopupAction(PopupAction.DismissAction(target.ref))
        }
        touchTargets.clear()
    }

    private fun findTouchTarget(event: MotionEvent, pointerIndex: Int): TouchTarget? {
        sceneKeys.touch?.let { return it.find(event.getX(pointerIndex), event.getY(pointerIndex)) }
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
        return ViewTarget(key, keyHitRect)
    }

    /** Gives finger [pointerIndex] of [event] to [target] for the rest of its touch. */
    private fun dispatchDown(event: MotionEvent, pointerIndex: Int, target: TouchTarget) {
        // a disabled key takes no touch, as on the system's dispatch: CustomGestureView refuses its Down, not its Up
        if (!target.enabled) return
        touchTargets[event.getPointerId(pointerIndex)] = target
        target.send(event, MotionEvent.ACTION_DOWN, pointerIndex)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // intercept ACTION_DOWN and all following events will go to parent's onTouchEvent
        return if (routesTouches && ev.actionMasked == MotionEvent.ACTION_DOWN) true
        else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (routesTouches) {
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
                        target.send(event, MotionEvent.ACTION_MOVE, i)
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTargets[pid] ?: return true
                    target.send(event, MotionEvent.ACTION_UP, i)
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
                    target.send(event, MotionEvent.ACTION_UP, 0)
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

    private fun onPopupChangeFocus(key: KeyRef, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(key, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(key: KeyRef, cancelled: Boolean): Boolean {
        if (cancelled) {
            // consumed: the listeners under this one would dismiss it a second time
            onPopupAction(PopupAction.DismissAction(key))
            return true
        }
        val triggerAction = PopupAction.TriggerAction(key)
        // ask popup keyboard whether there's a pending KeyAction
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return triggerAction.outConsumed
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(key))
        return true
    }

    /** The input method name last shown on the Space key; null until the first update. */
    protected var shownImeName: String? = null
        private set

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
