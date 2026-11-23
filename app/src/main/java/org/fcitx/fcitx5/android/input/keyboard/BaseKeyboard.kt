/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.annotation.CallSuper
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.absoluteValue
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePanels
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneTouch
import org.fcitx.fcitx5.android.input.keys.KeyResolver
import org.fcitx.fcitx5.android.input.keys.KeyState
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.input.theme.KeyStyleResolver
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState as SceneKeyState
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add

abstract class BaseKeyboard(
    context: Context,
    protected val theme: Theme,
    val themeBoard: String? = null,
) : ConstraintLayout(context) {

    var keyActionListener: KeyActionListener? = null

    private val prefs = AppPrefs.getInstance()

    internal val columns = ScrollColumns(
        this, theme, { canvas }, { keysPunctuation }, { onAction(it) }, { onPopupAction(it) }, ::onPopupChangeFocus, { onPopupTrigger(it, false) },
    )

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceKeys = mutableListOf<KeyHandle>()

    /** Set before init reads it. */
    protected var holdsToTalk = false
        private set

    @Keep
    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        spaceKeys.forEach { spaceSwipesCursor(it, v) }
    }

    // a space swipes up and down to its slots either way, a panel's too: only its sideways steps go
    private fun spaceSwipesCursor(key: KeyHandle, on: Boolean) {
        val g = key.keyGesture
        g.swipeEnabled = true
        g.swipeRepeatEnabled = on
        g.swipeThresholdX = if (on) selectionSwipeThreshold else disabledSwipeThreshold
    }

    private val spaceLongPress = prefs.keyboard.spaceKeyLongPressBehavior

    @Keep
    private val spaceLongPressListener = ManagedPreference.OnChangeListener<SpaceLongPressBehavior> { _, v ->
        updateSpaceBadges(v)
    }

    // a long press and a swipe ask the lists as they happen: only the corners are redrawn, no keyboard remade
    @Keep
    private val keyListsListener = ManagedPreference.OnChangeListener<String> { _, _ -> onKeyListsChanged() }

    @CallSuper
    protected open fun onKeyListsChanged() {
        canvas?.invalidateScene()
    }

    var inPasswordField = false
        set(value) {
            if (field == value) return
            field = value
            updateSpaceBadges(spaceLongPress.getValue())
            keyWriting?.update()
        }

    // as the scene was last made for it: a keyboard shown again catches up
    private var typingSeen = SceneEnvs.typing
    private val onTyping = { typingChanged() }

    private fun typingChanged() {
        val now = SceneEnvs.typing
        if (now == typingSeen) return
        typingSeen = now
        canvas?.invalidateScene()
    }

    var address: AddressKeys = AddressKeys.NONE
        set(value) {
            if (field == value) return
            field = value
            columns.update()
            onAddressChanged()
        }

    protected open fun onAddressChanged() {}

    /** The field's pad (`phone`, `datetime`) or null, for the scroll lists on every keyboard (NumberPad.fieldOf). */
    var fieldPad: String? = null
        set(value) {
            if (field == value) return
            field = value
            canvas?.invalidateScene()
            columns.update()
        }

    /** A panel lock's state (`send="panel-lock"`): the symbol panel's, and the number pad's. */
    var locked = false
        set(value) {
            if (field == value) return
            field = value
            canvas?.invalidateScene()
        }

    private val keyDrawing = prefs.keyboard.keyDrawing

    /** A temporary mode's typing (快速输入) does not count. A stroke drawn then filters candidates (笔画筛选). */
    var imTyping = false

    var inMultiLineField = false
    private val longPressDelay by prefs.keyboard.longPressDelay

    var tempMode = false
        set(value) { field = value; keyWriting?.update() }

    var inkSink: ((InkReading, piece: Int) -> Unit)? = null

    private var keyInk: KeyInk? = null

    private var keyWriting: KeyWriting? = null

    private var letterArea: LetterArea? = null

    open val strokesDrawn: Boolean get() = false

    fun drawsStrokes() = PageStrokeTouch.Draws.keys(keyDrawing.getValue() == KeyDrawing.StrokeFilter, inPasswordField, address != AddressKeys.NONE, imTyping, tempMode, keysIm, strokesDrawn)

    internal val keyInkForTest: KeyInk? get() = keyInk

    internal val letterAreaForTest: LetterArea? get() = letterArea

    protected open val glide: GlideInput? = null

    internal val glideForTest: GlideInput? get() = glide

    open val ownsSyllables: Boolean get() = false

    /**
     * Not in a password's or an address's field. A stroke read as none is dropped. Also makes the toolbar's 手写:
     * a keyboard that never calls this writes nothing on its keys.
     */
    protected fun glideInput(
        letters: () -> List<Pair<Char, ScenePlace>>,
        down: Float = GlideGesture.DOWN,
        enabled: () -> Boolean = { true },
        writesAlso: (KeyNode) -> Boolean = { it.role == SHIFT_ROLE },
        glided: (path: FloatArray, layout: GlideLayout) -> Unit,
    ): GlideInput {
        val (color, width) = ScenePanels.ink(context, theme)
        val ink = KeyInk(
            this, color, width, pause = { prefs.keyboard.handwritingPause.getValue().millis },
            deliver = { reading, piece -> inkSink?.invoke(reading, piece) },
        ).also { keyInk = it }
        val area = LetterArea(letters, writesAlso).also { letterArea = it }
        keyWriting = KeyWriting(this, ink, area, prefs.internal.handwritingOpen, { canvas }, { keysIm }, { inPasswordField }, { tempMode })
        return GlideInput(
            this, theme,
            enabled = { keyDrawing.getValue() == KeyDrawing.Glide && !inPasswordField && address == AddressKeys.NONE && enabled() },
            letters = letters,
            longPressMillis = { longPressDelay.toLong() },
            down = down,
            strokes = ::drawsStrokes,
            stroked = { path -> StrokeShape.classify(path)?.let { onAction(KeyAction.SymAction.stroke(it)) } },
            strokeLift = { prefs.keyboard.strokeLift.getValue().dp },
            tapKey = { x, y -> sceneKeys.touch?.find(x, y)?.takeIf { it.enabled }?.let { it.key.tap(it.cell, x, y) } },
            // the keys drawn: not a scene remade for a change not drawn yet
            strokeKeys = { canvas?.shownKeys.orEmpty().filter { it.role != SHIFT_ROLE && writesAlso(it) }.map { scenePlace(it.id, it.copy == 1) } },
            ink = ink,
            writes = { keyWriting?.now == true },
            writingArea = { x, y -> letterArea?.at(canvas, x, y) == true },
            glided = glided,
        )
    }

    fun restartInk() {
        keyInk?.restart()
        keyWriting?.update()
    }

    fun inputMethodShown() = keyWriting?.update()

    // a stroke never starts in a scroll column: that touch is the column's
    private var downInColumn = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) downInColumn = columns.at(ev.x, ev.y)
        val g = glide?.takeUnless { downInColumn } ?: return super.dispatchTouchEvent(ev)
        return g.dispatch(ev) { super.dispatchTouchEvent(it) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        glide?.draw(canvas)
    }

    var popupActionListener: PopupActionListener? = null

    private val selectionSwipeThreshold = dp(10f)
    private val inputSwipeThreshold = dp(36f)

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    // well over a sideways step, so a slide's drift moves no line
    private val spaceLineStep = dp(28f)

    private val swipes = KeySwipes(this, inputSwipeThreshold, disabledSwipeThreshold, selectionSwipeThreshold, spaceLineStep, object : KeySwipes.Keyboard {
        override val inPasswordField get() = this@BaseKeyboard.inPasswordField
        override val charShortcutMarks get() = this@BaseKeyboard.charShortcutMarks
        override fun swipeText(def: KeyDef) = def.shortcut?.let { resolving.swipe(it, placeholderOf(def)) } ?: SwipeChoice.BuiltIn
        override fun onAction(action: KeyAction, source: KeyActionListener.Source) = this@BaseKeyboard.onAction(action, source)
        override fun onPopupAction(action: PopupAction) = this@BaseKeyboard.onPopupAction(action)
        override fun onPopupChangeFocus(key: KeyRef, x: Float, y: Float) = this@BaseKeyboard.onPopupChangeFocus(key, x, y)
        override fun onPopupTrigger(key: KeyRef, cancelled: Boolean) = this@BaseKeyboard.onPopupTrigger(key, cancelled)
        override fun lines(steps: Int) = this@BaseKeyboard.lines(steps)
        override fun marksChanged() {
            canvas?.invalidateScene()
        }
    })

    protected val showLangSwitchKey = prefs.keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ -> canvas?.invalidateScene() }

    init {
        isMotionEventSplittingEnabled = true
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
        updateSpaceBadges(spaceLongPress.getValue())
        spaceLongPress.registerOnChangeListener(spaceLongPressListener)
        prefs.internal.keyLists.registerOnChangeListener(keyListsListener)
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    // a microphone on every space, or no one would know
    private fun updateSpaceBadges(behavior: SpaceLongPressBehavior) {
        val voice = behavior.talks(PackStore.ready(PackKind.VOICE), inPasswordField)
        holdsToTalk = voice
        canvas?.invalidateScene()
        // a long press that does nothing here buzzes for nothing either
        val takes = spaceLongPressTakesTouch(behavior, PackStore.ready(PackKind.VOICE), inPasswordField)
        spaceKeys.forEach { it.keyGesture.longPressFeedbackEnabled = takes }
    }

    sealed interface SwipeChoice {
        data object BuiltIn : SwipeChoice

        data object None : SwipeChoice

        data class Output(val action: KeyAction, val shown: String, val fresh: (() -> KeyAction)? = null) : SwipeChoice

        fun typed(builtIn: KeyAction): Pair<KeyAction, KeyActionListener.Source>? = when (this) {
            BuiltIn -> builtIn to KeyActionListener.Source.Keyboard
            None -> null
            is Output -> (fresh?.invoke() ?: action) to KeyActionListener.Source.Popup
        }

        fun preview(alternative: String): String = when (this) {
            BuiltIn -> alternative
            None -> ""
            is Output -> shown
        }
    }

    private val resolving = KeyResolving(object : KeyResolving.Board {
        override val layout get() = drawnLayout
        override val im get() = keysIm
        override val nineLetters get() = keysNineLetters
        override fun state() = keyState()
    })

    protected open fun pressed(key: KeyRef, clickAt: Float, clickDown: Float, action: KeyAction): KeyAction = action

    private fun wire(key: KeyHandle, def: KeyDef) {
        val scene = def as? SceneKeyDef
        key.keyGesture.soundEffect = scene?.sound ?: SoundKey.LETTER
        key.keyGesture.systemSound = scene?.systemSound ?: SoundKey.LETTER
        if (scene?.isSpace == true) wireSpace(key) else if (scene?.isBackspace == true) wireBackspace(key)
        wireBehaviors(key, def)
        // once a long press shows a popup, the popup has the touch: no swipe of the key's after that
        val longPopup = LongPopup()
        swipes.attach(key, def, longPopup)
        def.popup?.forEach { wirePopup(key, def, it, longPopup) }
        // a named key's long press is the resolver's: always wired, firing only while the resolver has a list,
        // so a changed list needs no keyboard remade, only the corners redrawn ([onKeyListsChanged])
        val name = def.shortcut
        if (name == null) resolving.unnamed(key.ref.id)
        if (name != null) {
            resolving.named(key.ref.id, name, placeholderOf(def))
            if (takesLongPress(def)) {
                key.keyGesture.longPressNow = { resolving.opens(key.ref.id) }
                wirePopup(key, def, KeyDef.Popup.Keyboard.Named(name), longPopup)
            }
        }
    }

    @VisibleForTesting
    internal fun keyResolver(): KeyResolver? = resolving.resolver()

    @VisibleForTesting
    internal val resolvedKeys: KeyResolving get() = resolving

    protected open val keysNineLetters: Map<Char, String>? get() = null

    private var keysLayout: SceneLayout? = null

    /** Not [SceneBoard.layout] where the engine refused a theme pack's layout and the app's own is drawn instead. */
    protected val drawnLayout: SceneLayout? get() = keysLayout ?: sceneBoard?.layout

    private fun wireSpace(key: KeyHandle) {
        val g = key.keyGesture
        spaceKeys.add(key)
        // as updateSpaceBadges sets it: a key wired after it ran would buzz for nothing otherwise
        g.longPressFeedbackEnabled = spaceLongPressTakesTouch(spaceLongPress.getValue(), PackStore.ready(PackKind.VOICE), inPasswordField)
        g.swipeEnabled = spaceSwipeMoveCursor.getValue()
        g.swipeRepeatEnabled = true
        g.swipeThresholdX = selectionSwipeThreshold
        g.swipeThresholdY = disabledSwipeThreshold
        g.crossStepY = spaceLineStep
        spaceSwipesCursor(key, spaceSwipeMoveCursor.getValue())
        key.gestureListener = { _, event ->
            when (event.type) {
                GestureType.Move -> {
                    // one sample may carry both: sideways first, then the lines (a diagonal slide)
                    val count = event.countX
                    if (count != 0) {
                        val sym =
                            if (count > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                        val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                        repeat(count.absoluteValue) {
                            onAction(action)
                        }
                    }
                    spaceLines(event.countCrossY) || count != 0
                }
                else -> false
            }
        }
    }

    private fun lines(steps: Int) = SpaceCursor.vertical(steps, inMultiLineField, composing = imTyping || tempMode)

    private fun spaceLines(steps: Int): Boolean {
        val lines = lines(steps)
        lines.forEach {
            val sym = if (it == SpaceCursor.Line.Up) FcitxKeyMapping.FcitxKey_Up else FcitxKeyMapping.FcitxKey_Down
            onAction(KeyAction.SymAction(KeySym(sym), KeyStates.Virtual))
        }
        return lines.isNotEmpty()
    }

    private fun wireBackspace(key: KeyHandle) {
        val g = key.keyGesture
        g.swipeEnabled = true
        g.swipeRepeatEnabled = true
        g.swipeThresholdX = selectionSwipeThreshold
        g.swipeThresholdY = disabledSwipeThreshold
        key.gestureListener = { _, event ->
            when (event.type) {
                // must stay reachable: it resets what a cancelled swipe left behind
                GestureType.Down -> {
                    glide?.backspaced(SystemClock.uptimeMillis())
                    onAction(KeyAction.BeginSelectionSwipeAction)
                    false
                }
                GestureType.Move -> {
                    val count = event.countX
                    if (count != 0) {
                        onAction(KeyAction.MoveSelectionAction(count))
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
                is KeyDef.Behavior.Press -> key.onClick { k -> onAction(pressed(k.ref, k.keyGesture.clickAt, k.keyGesture.clickDown, withTouch(it.action, k, this as? TypingKeys, resources.displayMetrics.density, canvas))) }
                is KeyDef.Behavior.LongPress -> key.onLongClick { k ->
                    val action = it.action
                    if (action is KeyAction.SpaceLongPressAction && !spaceLongPressTakesTouch(spaceLongPress.getValue(), PackStore.ready(PackKind.VOICE), inPasswordField)) {
                        // not taken, or a slow drag on the space past the delay moved no cursor
                        false
                    } else {
                        onAction(if (action is KeyAction.SpaceLongPressAction) action.copy(pointer = k.keyGesture.touchPointer) else action)
                        true
                    }
                }
                is KeyDef.Behavior.Repeat -> {
                    g.repeatEnabled = true
                    // past the keyboard's own handling of keys (T9's, Shift's): it types nothing
                    g.holdBegan = if (isBackspace(it.action)) { h -> listened(KeyAction.BackspaceDownAction(h)) } else null
                    key.onRepeat { k ->
                        onAction(withHold(it.action, k.keyGesture.hold))
                    }
                }
                is KeyDef.Behavior.Swipe -> {}
                is KeyDef.Behavior.DoubleTap -> {
                    g.doubleTapEnabled = true
                    key.onDoubleTap { _ -> onAction(it.action) }
                }
            }
        }
    }

    private fun wirePopup(key: KeyHandle, def: KeyDef, popup: KeyDef.Popup, longPopup: LongPopup) {
        when (popup) {
            // TODO: gesture processing middleware
            is KeyDef.Popup.Menu -> {
                key.onLongClick { k ->
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
                    // a key of no swipe of its own is not attached ([KeySwipes.attach]): its touch begins here
                    if (event.type == GestureType.Down) swipes.began(k.ref)
                    if (popupOnKeyPress) {
                        when (event.type) {
                            GestureType.Down -> k.previewBounds?.let { onPopupAction(PopupAction.PreviewAction(k.ref, bubbleOf(k, popup), it)) }
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
                            GestureType.Down -> k.previewBounds?.let { onPopupAction(PopupAction.PreviewAction(k.ref, bubbleOf(k, popup), it)) }
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

    /** Set by [drawKeysWithScene], last in a keyboard's init: null before. */
    protected var canvas: KeyboardCanvasView? = null
        private set

    private val sceneKeys = SceneKeys(this, { keysLayout }) { key, def -> wire(key, def) }

    protected var sceneBoard: SceneBoard? = null
        private set

    @VisibleForTesting
    val scenesMade: Int get() = sceneBoard?.scenesMade ?: 0

    /**
     * A layout that does not read draws nothing: there is no other drawing. Called last in init: the
     * scene reads the keyboard's state ([sceneEnv]).
     */
    protected fun drawKeysWithScene(layoutName: String) = drawKeysWithScene(SceneLayouts.of(context, layoutName))

    protected fun drawKeysWithScene(layout: SceneLayout?) {
        // a second canvas would sit under the first, taking the touches
        check(canvas == null) { "drawKeysWithScene called twice" }
        val board = SceneBoard(context, theme, layout) { look, read, width, height -> sharedEnv(sceneEnv(look, read, width, height)).also { sceneInput = it.input } }
        sceneBoard = board
        val view = board.view(keyImages(theme, themeBoard, layout), ::sceneString, { node -> sceneKey(node) }) { onSceneBuilt(it) }
        releaseAllTouchTargets()
        spaceKeys.removeAll { it is SceneKey }
        view.drawsBase = sceneDrawsBase
        view.faded = { letterArea?.faded(view, it) == true }
        canvas = view
        sceneKeys.byNodes(view, sceneTable) { old -> spaceKeys.remove(old) }
        add(view, lParams(0, 0) {
            topOfParent()
            bottomOfParent()
            leftOfParent()
            rightOfParent()
        })
        columns.toFront()
    }

    // every keyboard's, over what its own env says: the lock and the field's pad
    private fun sharedEnv(env: Env): Env = env.copy(
        input = env.input.copy(fieldPad = fieldPad),
        states = if (locked) env.states + ("lock" to env.states["lock"].orEmpty() + SceneKeyState.Locked) else env.states,
    )

    private var sceneInput = Input()

    /** What the scroll lists read: the scene's input as last made. */
    internal open fun scrollInput(): Input = sceneInput

    /** The nine keys' column on a keyboard without their typing: no syllables, English's marks as English types. */
    internal open fun sideInput(): Input = scrollInput().copy(ime = if (columnEnglish) "english" else "pinyin", composing = false)

    internal open val columnEnglish: Boolean get() = imeName == InputMethodCatalog.ENGLISH

    internal open fun columnSyllables(): List<String> = emptyList()

    internal open fun pickSyllable(i: Int): KeyAction? = null

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        columns.place()
    }

    protected open fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env =
        SceneEnvs.of(this, look, theme.isDark, width, height, Input(), marks = keyMarks(layout))

    internal fun shownAs(look: ThemeLook, width: Int, height: Int): Pair<SceneLayout, Env>? =
        // nothing is drawn here: put back the minute [keyMarks] took as drawn, else [catchUpMinute] skips stale keys
        drawnLayout?.let { val drawn = minuteDrawn; (it to sharedEnv(sceneEnv(look, it, width, height))).also { minuteDrawn = drawn } }

    protected open val sceneDrawsBase: Boolean get() = true

    @CallSuper
    internal open fun onSceneBuilt(scene: Scene?) = columns.built(scene)

    // a lock said as what a tap does
    protected open fun sceneString(name: String): String? = when (name) {
        "a11y_key_panel_lock_on" -> context.getString(R.string.picker_unlock)
        "a11y_key_panel_lock_off" -> context.getString(R.string.picker_lock)
        else -> null
    }

    protected open val sceneTable: SceneKeyDef.Table = object : SceneKeyDef.Table {}

    protected fun scenePlace(id: String, copy: Boolean = false): ScenePlace = ScenePlace(KeyRef(id, copy)) { canvas }

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

    private fun findTouchTarget(event: MotionEvent, pointerIndex: Int): TouchTarget? {
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        return columns.target(x, y, touchTargets.values) ?: sceneKeys.touch?.find(x, y)
    }

    private fun dispatchDown(event: MotionEvent, pointerIndex: Int, target: TouchTarget) {
        // a disabled key takes no touch: CustomGestureView refuses its Down, not its Up
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
    protected open fun onAction(action: KeyAction, source: KeyActionListener.Source = KeyActionListener.Source.Keyboard) = listened(action, source)

    private fun listened(action: KeyAction, source: KeyActionListener.Source = KeyActionListener.Source.Keyboard) {
        val ink = keyInk
        if (ink?.writtenOn != true || !InputMethodCatalog.writesOnKeys(keysIm)) {
            return keyActionListener?.onKeyAction(action, source) ?: Unit
        }
        // in order with the readings: backspace takes its strokes first (InkKeys)
        ink.keys.onAction({ isAttachedToWindow }) { if (!ink.keys.took(action)) keyActionListener?.onKeyAction(action, source) }
    }

    @CallSuper
    protected open fun onPopupAction(action: PopupAction) {
        // last, after a keyboard's own (a table's roots): none where the resolver has no list
        if (action is PopupAction.ShowKeyboardAction) {
            val named = resolving.popup(action.key.id)
            if (named != null) {
                named.keyboard?.let { popupActionListener?.onPopupAction(action.copy(keyboard = it)) }
                return
            }
        }
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
        if (action is KeyAction.ScrollList) columns.change(action) else onAction(resolving.output(key.id, action), KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(key))
        return true
    }

    protected var shownImeName: String? = null
        private set

    protected fun showInputMethodName(ime: InputMethodEntry) {
        val shown = InputMethodNames.of(context, ime)
        // otherwise silent: the name is only on the Space key, untouched. The first update is the keyboard
        // appearing, which TalkBack announces itself
        if (shownImeName != null && shownImeName != shown) announce(shown)
        shownImeName = shown
        canvas?.invalidateScene()
    }

    /** Deprecated from API 36, but a live region on the Space would speak at every rebuild. */
    @Suppress("DEPRECATION")
    private fun announce(text: CharSequence?) {
        if (!text.isNullOrEmpty()) announceForAccessibility(text)
    }

    open fun onAttach() {
        // do nothing by default
    }

    // once the subclass is made: the marks read its corners and symbols (swipeText)
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SceneEnvs.watch(onTyping)
        typingChanged()
        showSwipeMarks()
        catchUpMinute()
        keyWriting?.attached()
    }

    override fun onDetachedFromWindow() {
        keyWriting?.detached()
        SceneEnvs.unwatch(onTyping)
        super.onDetachedFromWindow()
    }

    protected open val charShortcutMarks: Boolean get() = true

    protected open val keysIm: String get() = ""

    protected var imeName = ""
        private set

    protected open fun keyState(): KeyState =
        KeyState(punctuation = keysPunctuation, ways = swipeWays(), spots = swipeSpots(), minute = minuteOfVariables())

    // lists filling in variables are made anew each minute; with none, one state for good
    private fun minuteOfVariables(): Long = if (resolving.resolver()?.fillsIn == true) KeyClock.now() / MINUTE_MS else 0

    private var minuteDrawn = 0L

    fun catchUpMinute() {
        if (minuteOfVariables() != minuteDrawn) canvas?.invalidateScene()
    }

    /**
     * From now on [layout] is the one the long presses and swipes read (each scene's env asks this, so the
     * layout drawn is the one touched; [keyReadings] sets nothing).
     */
    protected fun keyMarks(layout: SceneLayout): MarkSettings {
        keysLayout = layout
        val state = keyState().also { minuteDrawn = it.minute }
        val resolver = resolving.resolver(layout) ?: return SceneBoard.marks(layout, charShortcutMarks, keysIm, state, keysNineLetters)
        return SceneBoard.marks(resolver, charShortcutMarks, state)
    }

    protected fun keyReadings(layout: SceneLayout): Map<String, String> {
        return if (!firstShown()) emptyMap() else resolving.resolver(layout)?.readings(keyState()).orEmpty()
    }

    protected fun showSwipeMarks() {
        swipes.showMarks()
    }

    protected var returnAppearance = ReturnKeyAppearance.Enter
        private set

    fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        returnAppearance = appearance
        canvas?.invalidateScene()
    }

    protected fun Texts.withReturn() = copy(returnLabel = returnAppearance.label(context).toString(), returnIcon = SceneIcons.returnName(returnAppearance.icon))

    protected var keysPunctuation: Map<String, String> = emptyMap()
        private set

    @CallSuper
    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        if (mapping == keysPunctuation) return
        keysPunctuation = mapping
        canvas?.invalidateScene()
        columns.update()
    }

    @CallSuper
    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        imeName = ime.uniqueName
    }

    open fun onDetach() {
        releaseAllTouchTargets()
    }

}

private const val SHIFT_ROLE = "shift"

internal fun spaceLongPressTakesTouch(behavior: SpaceLongPressBehavior, voiceReady: Boolean, inPasswordField: Boolean) = when (behavior) {
    SpaceLongPressBehavior.None -> false
    SpaceLongPressBehavior.Voice -> behavior.talks(voiceReady, inPasswordField)
    else -> true
}

// a held ⌫ stops once what was typed as it began is all deleted, decided on the host's thread
private fun withHold(action: KeyAction, hold: BackspaceHold): KeyAction =
    if (isBackspace(action)) (action as KeyAction.SymAction).copy(hold = hold) else action

private fun isBackspace(action: KeyAction) = action is KeyAction.SymAction && action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace

private const val MINUTE_MS = 60_000L

internal fun customLayout(context: Context, custom: String): SceneLayout? =
    SceneLayouts.custom(context, custom.substringBeforeLast('@'), custom.substringAfterLast('@'))

private fun placeholderOf(def: KeyDef): (String) -> KeyAction? = (def as? SceneKeyDef)?.placeholder ?: { null }

private fun takesLongPress(def: KeyDef): Boolean =
    def.popup.orEmpty().none { it is KeyDef.Popup.Keyboard || it is KeyDef.Popup.Menu } &&
        def.behaviors.none { it is KeyDef.Behavior.LongPress || it is KeyDef.Behavior.Repeat }

private fun bubbleOf(k: KeyHandle, popup: KeyDef.Popup.Preview) = (k as? SceneKey)?.bubble ?: popup.content

// each file decoded once off the main thread; the keys drawn as their kinds till then (ThemeAssets)
private fun keyImages(theme: Theme, board: String?, layout: SceneLayout?): BoardImages {
    val look = ThemeManager.shownLook(theme)
    // every image a pack's styles name, one set shared by every board (decoded once, not once a board)
    (theme as? Theme.Pack)?.let { return ThemeAssets.boardImages(look, null, ThemePackFiles.images(it.drawn)) }
    val names = (layout?.keyIds().orEmpty() + board?.let { KeyNames.POSITIONS[it] }.orEmpty()).distinct()
    return ThemeAssets.boardImages(look, board, KeyStyleResolver.imagesOn(look.keys, board, names))
}

private fun SceneLayout.keyIds(): List<String> =
    layout.parts.flatMap { p ->
        when (p) {
            is Row -> p.cells
            is Include -> fragments[p.src]?.rows.orEmpty().flatMap { it.cells }
        }
    }
        .filterIsInstance<Key>().map { it.id }
