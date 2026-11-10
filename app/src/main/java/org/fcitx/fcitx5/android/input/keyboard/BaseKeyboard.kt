/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import androidx.annotation.CallSuper
import androidx.annotation.Keep
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
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
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupKeyboardUi
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.input.theme.KeyStyleResolver
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import kotlin.math.absoluteValue

abstract class BaseKeyboard(
    context: Context,
    protected val theme: Theme,
    private val keyLayout: List<List<KeyDef>>,
    /** Its keys' board in a theme ([KeyNames]: `qwerty`, `nine`…), each key named by [KeyDef.name]; null, styled by kind alone. */
    val themeBoard: String? = null,
    /** Whether its keys' swipes reach their shortcuts ([KeyDef.shortcut]): the typing keyboards', not a panel's. */
    private val shortcuts: Boolean = false,
) : ConstraintLayout(context) {

    var keyActionListener: KeyActionListener? = null

    private val prefs = AppPrefs.getInstance()

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceKeys = mutableListOf<KeyHandle>()

    /** Holding the space talks here ([updateSpaceBadges]): the microphone on it. Before init, which sets it. */
    protected var holdsToTalk = false
        private set

    // the theme's key images this keyboard draws, each file once: decoded off the main thread, the keys
    // drawn as their kinds till they are in (ThemeAssets)
    protected val keyImages: BoardImages by lazy {
        val look = ThemeManager.shownLook(theme)
        // a pack's: every image its styles name (060-render-p3 T8a; a few, ThemeAssetLimits holds each)
        // every board of it one set, shared (no board in the key: the same files decoded once, not once a board)
        (theme as? Theme.Pack)?.let { return@lazy ThemeAssets.boardImages(look, null, ThemePackFiles.images(it.drawn)) }
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

    // what is typed ([SceneEnvs.typing]: the input method, the field) as the scene was last made anew for
    // it: told while shown, and one shown again (another keyboard was, or the bar's) catches up as it is
    private var typingSeen = SceneEnvs.typing
    private val onTyping = { typingChanged() }

    /** What is typed changed: the theme's scenes of it drawn. */
    private fun typingChanged() {
        val now = SceneEnvs.typing
        if (now == typingSeen) return
        typingSeen = now
        canvas?.invalidateScene()
    }

    /** Set per field ([KeyboardWindow]): an address's has no glide typing, as no word is typed there. */
    open var address: AddressKeys = AddressKeys.NONE

    private val keyDrawing = prefs.keyboard.keyDrawing

    /**
     * Set as the input method has something typed or not, a temporary mode's (快速输入) not counted
     * ([KeyboardWindow]): a stroke drawn then filters its candidates (笔画筛选).
     */
    var imTyping = false
    private val longPressDelay by prefs.keyboard.longPressDelay

    /** Set as a temporary mode (快速输入, Unicode) is on or off ([KeyboardWindow]): nothing is written on the keys in one. */
    var tempMode = false

    /**
     * Where what is written on the keys goes ([KeyboardWindow]): a reading of its piece, or null once the
     * piece is done; takesBack, the first reading after a tap was taken back as its first dot (Action.Ink.takesBack).
     */
    var inkSink: ((InkReading?, piece: Int, takesBack: Boolean) -> Unit)? = null

    // 键盘手写's pad, made with the glide ([glideInput]); the engine's answers watched while shown
    private var keyInk: KeyInk? = null
    private var inkScope: CoroutineScope? = null
    // whether the input method writes on the keys ([glideInput]'s writes)
    private var inkIm: () -> Boolean = { false }

    /** [keyInk], for a test to see the ink drawn. */
    internal val keyInkForTest: KeyInk? get() = keyInk

    /**
     * What a finger drawn over the layout's letters reads, if it has any ([glideInput]): glide typing
     * (滑行输入), a stroke for 笔画筛选, or both; a layout with no glide (the grouped keys) has it for the strokes alone.
     */
    protected open val glide: GlideInput? = null

    /** [glide], for a test to ask where the split gap is and whether a trail is drawn. */
    internal val glideForTest: GlideInput? get() = glide

    /** Whether the layout shows the syllables to take itself (九键's column): the code row's then show none. */
    open val ownsSyllables: Boolean get() = false

    /**
     * Glide typing over [letters], taken where [enabled] says and 滑行输入 is on, in a field where
     * words are typed: not a password's or an address's. Where 笔画筛选 is chosen instead (one setting,
     * [KeyDrawing]), a stroke drawn over them while pinyin is typed in an input method [filters] says reads
     * one: sent as the engine's stroke key (Keyboard.strokeKey), the shape StrokeShape's; one it
     * reads as none is dropped.
     *
     * Made also: the keyboard's 键盘手写 ([keyInk], the input method's [writes]); a keyboard that never calls
     * this writes nothing on its keys ([onAction], [restartInk] and the pad's showing all go by it).
     */
    protected fun glideInput(
        letters: () -> List<Pair<Char, KeyPlace>>,
        down: Float = GlideGesture.DOWN,
        enabled: () -> Boolean = { true },
        filters: () -> Boolean = { false },
        writes: () -> Boolean = { false },
        glided: (path: FloatArray, layout: GlideLayout) -> Unit,
    ): GlideInput {
        val ink = KeyInk(
            this, theme, pause = { prefs.keyboard.handwritingPause.getValue().millis },
            deliver = { reading, piece, takesBack -> inkSink?.invoke(reading, piece, takesBack) }, finished = { inkSink?.invoke(null, it, false) },
        ).also { keyInk = it }
        inkIm = writes
        return GlideInput(
            this, theme,
            enabled = { keyDrawing.getValue() == KeyDrawing.Glide && wordField() && enabled() },
            letters = letters,
            longPressMillis = { longPressDelay.toLong() },
            down = down,
            strokes = { keyDrawing.getValue() == KeyDrawing.StrokeFilter && wordField() && imTyping && filters() },
            stroked = { path -> StrokeShape.classify(path)?.let { onAction(KeyAction.SymAction(KeySym(UNICODE + Keyboard.strokeKey(it)), KeyStates.Virtual)) } },
            ink = ink,
            // 键盘手写: an input method written in [writes] says, in a field where words are typed, not in a
            // temporary mode; not while TalkBack explores by touch, the finger feeling for keys, not writing
            writes = { writing() && !tempMode && !touchExploring() && PackStore.ready(PackKind.TEXT) && writes() },
            typed = { EngineState.keysTyped.value.typed },
            glided = glided,
        )
    }

    private fun writing() = keyDrawing.getValue() == KeyDrawing.Handwriting && wordField()

    private fun touchExploring() = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /** Another input started (a field): what was written on the keys dropped, as the handwriting keyboard's pad is. */
    fun restartInk() {
        keyInk?.restart()
    }

    private fun wordField() = !inPasswordField && address == AddressKeys.NONE

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        glide?.dispatch(ev) { super.dispatchTouchEvent(it) } ?: super.dispatchTouchEvent(ev)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        glide?.draw(canvas)
    }

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
        override fun marksChanged() {
            canvas?.invalidateScene()
        }
    })

    init {
        isMotionEventSplittingEnabled = true
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
        updateSpaceBadges(spaceLongPress.getValue())
        spaceLongPress.registerOnChangeListener(spaceLongPressListener)
    }

    // holding the space to talk: a microphone on every keyboard's space says so, or no one would know
    private fun updateSpaceBadges(behavior: SpaceLongPressBehavior) {
        val voice = behavior.talks(PackStore.ready(PackKind.VOICE), inPasswordField)
        holdsToTalk = voice
        canvas?.invalidateScene()
        // a long press that does nothing here buzzes for nothing either
        val takes = spaceLongPressTakesTouch(behavior, PackStore.ready(PackKind.VOICE), inPasswordField)
        spaceKeys.forEach { it.keyGesture.longPressFeedbackEnabled = takes }
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

    /**
     * What a tap sends for its key's [action]: the action itself, but where a keyboard adds where it fell,
     * [clickAt] across and [clickDown] down ([KeyGesture.clickAt], [KeyGesture.clickDown]).
     */
    protected open fun pressed(clickAt: Float, clickDown: Float, action: KeyAction): KeyAction = action

    /**
     * What [key] of [def], a key the scene draws (060-render-p2 S1), does: its sound, the space's and
     * backspace's swipes, its press, long press, repeat and double tap, its symbol's and shortcut's
     * swipes ([KeySwipes]), its popups.
     */
    private fun wire(key: KeyHandle, def: KeyDef) {
        // every key is a layout's node (SceneKeys): its node's sound (060-render-p2 S4)
        val scene = def as? SceneKeyDef
        key.keyGesture.soundEffect = scene?.sound ?: InputFeedbacks.SoundEffect.Standard
        // the number pad's space moves the cursor too (UAT PAN-14): it had the press only
        if (scene?.isSpace == true) wireSpace(key) else if (scene?.isBackspace == true) wireBackspace(key)
        wireBehaviors(key, def)
        // set as its long press shows a popup, which then has the touch: no swipe of the key's after that
        val longPopup = LongPopup()
        swipes.attach(key, def, longPopup)
        def.popup?.forEach { wirePopup(key, def, it, longPopup) }
    }

    // the space's sideways swipe moves the cursor, as the setting says ([spaceSwipesCursor])
    private fun wireSpace(key: KeyHandle) {
        val g = key.keyGesture
        spaceKeys.add(key)
        // as updateSpaceBadges sets it: a key wired after it ran (the scene's, SceneKeys.byNodes) buzzes for nothing otherwise
        g.longPressFeedbackEnabled = spaceLongPressTakesTouch(spaceLongPress.getValue(), PackStore.ready(PackKind.VOICE), inPasswordField)
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
                is KeyDef.Behavior.Press -> key.onClick { k -> onAction(pressed(k.keyGesture.clickAt, k.keyGesture.clickDown, it.action)) }
                // hold to talk follows the finger that holds the space
                is KeyDef.Behavior.LongPress -> key.onLongClick { k ->
                    val action = it.action
                    if (action is KeyAction.SpaceLongPressAction && !spaceLongPressTakesTouch(spaceLongPress.getValue(), PackStore.ready(PackKind.VOICE), inPasswordField)) {
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
                    // the face's inset in its cell, the theme's: the finger's x is the cell's
                    onPopupAction(PopupAction.ShowKeyboardAction(k.ref, popup, bounds, ((k.keyWidth - bounds.width()) / 2).coerceAtLeast(0)))
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

    /**
     * The keys drawn by the scene engine ([KeyboardCanvasView]), the touches handed to the scene's
     * keys ([sceneKeys]). Set by [drawKeysWithScene], last in a keyboard's init: null before.
     */
    protected var canvas: KeyboardCanvasView? = null
        private set

    // the scene's keys ([drawKeysWithScene]) and what a touch on the canvas finds
    private val sceneKeys = SceneKeys(this) { key, def -> wire(key, def) }

    /** The board the scene engine draws this keyboard's keys from ([drawKeysWithScene], 060-render-p2 S4). */
    protected var sceneBoard: SceneBoard? = null
        private set

    /**
     * The keys drawn by the scene of the app's layout [layoutName] ([BuiltInLayouts]). A layout that
     * does not read ([SceneLayouts.of], logged there) draws nothing: there is no other drawing (U8).
     * Called last in a keyboard's init: the scene reads its state ([sceneEnv]). Each key wired as
     * its layout's node says ([SceneKeyDef], [sceneTable] the keyboard's own words: U4 B).
     */
    protected fun drawKeysWithScene(layoutName: String) = drawKeysWithScene(SceneLayouts.of(context, layoutName))

    /** As [drawKeysWithScene] by its id, [layout] read already (null: none read, nothing drawn): a grouped keyboard's, its groups checked first. */
    protected fun drawKeysWithScene(layout: SceneLayout?) {
        // once, in init: a second canvas would sit under the first, the touches the new one's
        check(canvas == null) { "drawKeysWithScene called twice" }
        val board = SceneBoard(context, theme, layout) { look, read, width, height -> sceneEnv(look, read, width, height) }
        sceneBoard = board
        val view = board.view(keyImages, ::sceneString, { node -> sceneKey(node) }) { onSceneBuilt(it) }
        releaseAllTouchTargets()
        spaceKeys.removeAll { it is SceneKey }
        view.drawsBase = sceneDrawsBase
        canvas = view
        sceneKeys.byNodes(view, sceneTable) { old -> spaceKeys.remove(old) }
        add(view, lParams(0, 0) {
            topOfParent()
            bottomOfParent()
            leftOfParent()
            rightOfParent()
        })
    }

    /**
     * What this keyboard's keys show now as the scene engine's environment, in [look] at [width] ×
     * [height] ([SceneEnvs.of] with its own Input, texts, marks): asked for each frame whose scene
     * was invalidated ([KeyboardCanvasView.invalidateScene], every change of what the keys show).
     */
    protected open fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env =
        SceneEnvs.of(this, look, theme.isDark, width, height, Input(), marks = SceneBoard.marks(layout, charShortcutMarks))

    /** Whether the canvas draws its board's fill under the keys ([KeyboardCanvasView.drawsBase]): a row or a column laid on its own background, not. */
    protected open val sceneDrawsBase: Boolean get() = true

    /** A scene made anew for the canvas ([scene]; null, none drawn): what a keyboard places by it, placed again. */
    internal open fun onSceneBuilt(scene: Scene?) {}

    /** The keyboard's own words for a string its layout names ([KeyboardCanvasView.Host.string]): null, the app's shared ones. */
    protected open fun sceneString(name: String): String? = null

    /** The keyboard's own words for its layout's keys (U4 B): its `action:` placeholders, its composed long presses. */
    protected open val sceneTable: SceneKeyDef.Table = object : SceneKeyDef.Table {}

    /** Where the scene drawn last has its key [id] (its second [copy], a split row's) in this keyboard, as a key's place is (glide typing's, strokes'): nowhere before it is drawn. */
    protected fun scenePlace(id: String, copy: Boolean = false): KeyPlace = KeyPlace { _, out ->
        val c = canvas
        if (c != null && c.cell(KeyRef(id, copy), out)) out.offset(c.left, c.top) else out.setEmpty()
    }

    /** The key wired for the scene's [node] ([SceneTouch.keyOf]): what a screen reader's actions on it do. */
    internal fun sceneKey(node: KeyNode): SceneKey? = sceneKeys.of(node)

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

    private fun findTouchTarget(event: MotionEvent, pointerIndex: Int): TouchTarget? =
        sceneKeys.touch?.find(event.getX(pointerIndex), event.getY(pointerIndex))

    /** Gives finger [pointerIndex] of [event] to [target] for the rest of its touch. */
    private fun dispatchDown(event: MotionEvent, pointerIndex: Int, target: TouchTarget) {
        // a disabled key takes no touch, as on the system's dispatch: CustomGestureView refuses its Down, not its Up
        if (!target.enabled) return
        touchTargets[event.getPointerId(pointerIndex)] = target
        target.send(event, MotionEvent.ACTION_DOWN, pointerIndex)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // intercept ACTION_DOWN and all following events will go to parent's onTouchEvent
        return if (ev.actionMasked == MotionEvent.ACTION_DOWN) true
        else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        return super.onTouchEvent(event)
    }

    @CallSuper
    protected open fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        val ink = keyInk
        // straight on unless written on, in an input method that writes (English's keys never wait on its reads)
        if (ink?.writtenOn != true || !writing() || !inkIm()) return keyActionListener?.onKeyAction(action, source) ?: Unit
        // in order with what is written on the keys: after its readings, backspace its strokes' first (InkKeys)
        ink.keys.onAction({ isAttachedToWindow }) { if (!ink.keys.took(action)) keyActionListener?.onKeyAction(action, source) }
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

    /** Shows [ime] on the space: its name, and its sub-mode's ([spaceLabel]), in the scene's next env. */
    protected fun showInputMethodName(ime: InputMethodEntry) {
        val imeName = spaceLabel(InputMethodNames.of(context, ime), ime.subMode)
        // A change of input method (or of its sub-mode, e.g. an ASCII toggle) is otherwise
        // silent: the name is only on the Space key, which the user is not touching. The first
        // update is the keyboard appearing, which TalkBack announces itself. A change made while
        // another layout was showing is announced on returning to this one.
        if (shownImeName != null && shownImeName != imeName) announce(imeName)
        shownImeName = imeName
        canvas?.invalidateScene()
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
        SceneEnvs.watch(onTyping)
        typingChanged()
        showSwipeMarks()
        keyInk?.let { ink ->
            inkScope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
                s.launch { EngineState.handwritingEnded.collect(ink::ended) }
                // every answer, the same or not: a tap's mark replaced (KeyInkGesture.engineTyped)
                s.launch { EngineState.keysTyped.collect { ink.gesture.engineTyped(it.typed, it.keysHeard) } }
            }
        }
    }

    override fun onDetachedFromWindow() {
        inkScope?.cancel()
        inkScope = null
        // hidden, or another layout shown: what was written is done, as a pause would have it
        keyInk?.hidden()
        SceneEnvs.unwatch(onTyping)
        super.onDetachedFromWindow()
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
 * Whether the space's long press, set to [behavior], takes the touch from its swipe: not when it does
 * nothing, as None does, and Voice where it does not talk ([SpaceLongPressBehavior.talks]: no voice pack, a password).
 */
internal fun spaceLongPressTakesTouch(behavior: SpaceLongPressBehavior, voiceReady: Boolean, inPasswordField: Boolean) = when (behavior) {
    SpaceLongPressBehavior.None -> false
    SpaceLongPressBehavior.Voice -> behavior.talks(voiceReady, inPasswordField)
    else -> true
}

/** What the Space key shows of an input method: its [name], and [subMode]'s label (or else its name) in brackets. */
internal fun spaceLabel(name: String, subMode: InputMethodSubMode): String {
    val mode = subMode.label.ifEmpty { subMode.name }
    return if (mode.isEmpty()) name else "$name ($mode)"
}
