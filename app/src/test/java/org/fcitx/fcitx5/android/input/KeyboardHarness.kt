/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.app.Activity
import org.fcitx.fcitx5.android.WAIT_MS
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.TextView
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.comparesScreenshots
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.FakeHostConnection
import org.fcitx.fcitx5.android.core.whenReady
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.pack.TestShelves
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.scene.Op
import android.widget.ImageView
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowPopupMenu

/**
 * The whole keyboard on the JVM: the real [FcitxInputMethodService] built by Robolectric, its
 * [InputView] made as on a device, on a [FakeHostApi] instead of the Kotlin host (the seam
 * [HostDaemon.testConnection]). Engine output comes from events pushed with [emit]; what the
 * keys asked of the engine is in [api]`.calls`.
 *
 * [theme] is made the active one first; null keeps what is active.
 * Needs `@Config(application = FcitxApplication::class)`; screenshots also `@GraphicsMode(NATIVE)`.
 * Call [close] at the end (an `@After`): the daemon seam is global.
 */
class KeyboardHarness(
    theme: Theme? = TestThemes.PixelLight,
    im: InputMethodEntry = PINYIN,
    /** A real host to type through (a [KotlinHost] on a scripted engine) in place of [api]: its own events, its own answers. */
    host: HostApi? = null,
    /** Each input method's keyboard (I1), [NINE_KEYS] pinyin's nine keys: set before the keyboard is made, put back at [close]; null leaves the preferences as they are. */
    keyboards: ImKeyboards.Chosen? = null,
    /**
     * 编码行: embedded, every test's baseline (OneDefaultPreferencesShadow), unless a test asks for the
     * strip floating (the default since 0.5.1): set before the keyboard is made, put back at [close].
     * Only a change is made: each one rebuilds the input view of every keyboard alive.
     */
    codeRow: CodeRowStyle = CodeRowStyle.Embedded,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // the fields' activities, destroyed at [close]
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<Activity>>()
    val api = FakeHostApi().apply { inputMethodEntryCached = im }
    private val controller: ServiceController<FcitxInputMethodService>
    val service: FcitxInputMethodService

    private val keyboardsWere = keyboards?.let { AppPrefs.getInstance().keyboard.imKeyboards() }

    private val codeRowWas = ThemeManager.prefs.codeRow.getValue().takeIf { it != codeRow }

    init {
        // both resource packs there, as on a user's phone: without the text pack the keyboard is a
        // prompt to import it (PackGateView); a test of that, or of voice input's, puts its own
        TestShelves.use()
        keyboards?.let(::setKeyboards)
        codeRowWas?.let { ThemeManager.prefs.codeRow.setValue(codeRow) }
        val connection = host?.let { HarnessConnection(scope, it) } ?: FakeHostConnection(scope, api)
        HostDaemon.testConnection = { connection }
        // the seam is global: a harness that fails to build leaves it to no later test (no close() to call)
        try {
            theme?.let(ThemeManager::setNormalModeTheme)
            controller = Robolectric.buildService(FcitxInputMethodService::class.java).create()
            service = controller.get()
            // the token the system gives an input method's window, without which it is never shown
            // (InputMethod.attachToken wants a display area Robolectric has not: SoftInputWindow's own setter)
            service.window.javaClass.getDeclaredMethod("setToken", IBinder::class.java)
                .apply { isAccessible = true }.invoke(service.window, Binder())
            service.onCreateInputView()
            // Robolectric clears the clipboard's listeners after each test: the app's, added once a process, again
            service.getSystemService(android.content.ClipboardManager::class.java).let {
                it.removePrimaryClipChangedListener(org.fcitx.fcitx5.android.data.clipboard.ClipboardManager)
                it.addPrimaryClipChangedListener(org.fcitx.fcitx5.android.data.clipboard.ClipboardManager)
            }
            idle()
        } catch (e: Throwable) {
            keyboardsWere?.let(::setKeyboards)
            codeRowWas?.let(ThemeManager.prefs.codeRow::setValue)
            HostDaemon.testConnection = null
            scope.cancel()
            throw e
        }
    }

    /** The [InputView] the service made (made anew when a setting needs it, so looked up each time). */
    val inputView: InputView get() = find(service.window.window!!.decorView) ?: error("no InputView")

    /** The board under the bar: the letters, a panel, the pad (the window manager's view), docked where it is. */
    val board: View get() = inputView.findViewById(R.id.input_window)

    /** The bar over the board: toolbar, candidates or a panel's title, as wide as the board or the screen. */
    val bar: View
        get() = inputView.kawaiiBar.view

    /** The part a screenshot shows: bar, keys and their background, not the transparent room above. */
    val keyboard: View get() = inputView.keyboardView

    /** The field being typed in: what the keyboard commits lands here, through its own InputConnection. */
    lateinit var field: EditText
        private set

    /** The field's [EditorInfo], as [start] or [restart] gave it to the service. */
    lateinit var info: EditorInfo
        private set

    private lateinit var connection: InputConnection

    /**
     * Focus a field of this [inputType], as the app's field gaining focus would; [packageName] the
     * app's, [fieldId] its view's (one field told from another by them, as ContextMemory.field does),
     * [prefill] what the app put in it before it was focused.
     */
    fun start(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
        packageName: String = "org.example.app",
        fieldId: Int = 0,
        prefill: (EditText) -> Unit = {},
    ): KeyboardHarness {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().also { activities += it }.get()
        field = EditText(activity).also {
            it.inputType = inputType
            it.imeOptions = imeOptions
            prefill(it)
            activity.setContentView(it)
            // keys the keyboard sends (←, Home, Ctrl+Z) go to the focused view, as on a device
            it.requestFocus()
        }
        delivered = intArrayOf(field.selectionStart, field.selectionEnd, -1, -1)
        val info = EditorInfo().also {
            it.packageName = packageName
            it.fieldId = fieldId
        }
        val target = field
        // a key the keyboard sends (←, Home, Ctrl+Z) the system hands to the focused view: Robolectric's does not
        val ic = object : android.view.inputmethod.InputConnectionWrapper(field.onCreateInputConnection(info), true) {
            override fun sendKeyEvent(event: KeyEvent): Boolean =
                // with Ctrl, a shortcut (Ctrl+Z), as the view root hands it on
                (event.isCtrlPressed && event.action == KeyEvent.ACTION_DOWN && target.onKeyShortcut(event.keyCode, event)) ||
                    target.dispatchKeyEvent(event)
        }
        this.info = info
        connection = ic
        doStartInput(ic, info, restarting = false)
        service.onStartInputView(info, false)
        // the window put on the screen: views attached (what a chip, a popup checks), the input area shown
        runCatching { service.showWindow(true) }.onFailure { System.err.println("showWindow: $it") }
        idle()
        relayout()
        return this
    }

    /** The app restarting input in the same field, now of [inputType] (a WebView's field told anew). */
    fun restart(inputType: Int = field.inputType) {
        field.inputType = inputType
        val info = EditorInfo().also {
            it.packageName = this.info.packageName
            it.fieldId = this.info.fieldId
        }
        field.onCreateInputConnection(info)
        this.info = info
        doStartInput(connection, info, restarting = true)
        service.onStartInputView(info, true)
        idle()
        relayout()
    }

    /**
     * The keyboard put away (back, the app hiding it), the field kept: as hideWindow, the input view
     * finished and not started any more, so leaving the field next finishes the input only.
     */
    fun hide() {
        service.onFinishInputView(false)
        InputMethodService::class.java.getDeclaredField("mInputViewStarted").apply { isAccessible = true }.setBoolean(service, false)
        idle()
        relayout()
    }

    /** The screen turned: input restarted in the same field with the same EditorInfo (resetStateForNewConfiguration). */
    fun turn() {
        doStartInput(connection, info, restarting = true)
        service.onStartInputView(info, true)
        idle()
        relayout()
    }

    // InputMethodService's own entry (hidden): sets the connection the service commits through, then onStartInput
    private fun doStartInput(ic: InputConnection, info: EditorInfo, restarting: Boolean) {
        InputMethodService::class.java
            .getDeclaredMethod("doStartInput", InputConnection::class.java, EditorInfo::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(service, ic, info, restarting)
    }

    /** An engine event, as fcitx would send it, then everything it set off. */
    fun emit(event: HostEvent) {
        check(api.events.tryEmit(event)) { "event buffer full" }
        idle()
        relayout()
    }

    /**
     * The window is never shown under Robolectric (the input area stays hidden): the input view is
     * laid out at the screen's size by hand, as its window would make it.
     */
    fun relayout() {
        val dm = service.resources.displayMetrics
        val v = inputView
        v.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, dm.widthPixels, dm.heightPixels)
        idle()
    }

    /**
     * The keys shown under [root] (060-render-p2 S3), depth first: a canvas's, its scene's in its order
     * (row by row, left to right), and the key views shown, whichever draws a keyboard. [root] may be any
     * view with keyboards in it (the settings' preview), not only the input method's.
     */
    fun keys(root: View = inputView): List<ShownKey> = keysOf(root) { v -> if (root === inputView) shown(v) else true }

    /** The key showing [label] (its main text), in whatever keyboard or panel is up, whichever draws it. */
    fun key(label: String): ShownKey = keys().firstOrNull { it.label == label } ?: error("no key $label in ${texts()}")

    /** Every key showing [label]: a split row draws G and V on both halves. */
    fun keysLabelled(label: String): List<ShownKey> = keys().filter { it.label == label }

    /** The key the scene names [id] (`space`, `q`; a key view's theme name), the [copy]th of a split row's two. */
    fun keyNamed(id: String, copy: Int = 0): ShownKey = keys().firstOrNull { it.id == id && it.copy == copy }
        ?: error("no key named $id ($copy) in ${keys().map { it.id }}")

    /** The key whose view had the id [id] (R.id.button_star), its node the same name; null for none shown. */
    fun keyWithId(id: Int): ShownKey? = service.resources.getResourceEntryName(id).let { name -> keys().firstOrNull { it.resourceName == name } }

    /** The key read out as [description] (Shift, Backspace, Space's with the input method's name), whichever draws it. */
    fun describedKey(description: String): ShownKey = keys().firstOrNull { it.description == description }
        ?: error("no key described as $description in ${keys().mapNotNull { it.description }}")

    /** The view (key, bar button) read out as [description]. */
    fun described(description: String): View = all(inputView)
        .firstOrNull { shown(it) && it.contentDescription?.toString() == description }
        ?: error("nothing described as $description in ${all(inputView).filter { shown(it) }.mapNotNull { it.contentDescription }.toList()}")

    /** Visible up to the input view: [View.isShown] is false throughout, its window never shown. */
    fun shown(v: View): Boolean {
        var at: View? = v
        while (at != null && at !== inputView) {
            if (at.visibility != View.VISIBLE) return false
            at = at.parent as? View
        }
        return at != null
    }

    /** A click, as a finger's tap ends, then everything it set off. */
    fun tap(v: View) {
        check(v.performClick()) { "$v took no click" }
        idle()
        relayout()
    }

    /** A click on [k], as a finger's tap ends, then everything it set off. */
    fun tap(k: ShownKey) {
        check(k.click()) { "$k took no click" }
        idle()
        relayout()
    }

    fun idle() {
        // as the looper's idle(), one task at a time, so that something posting itself again for ever (a layout
        // setting off another) fails here and now, not a test hung at 100% CPU
        val looper = shadowOf(Looper.getMainLooper())
        var ran = 0
        // the handwriting recognizer's reads, and the keys and settings held behind them, run on its own thread and
        // come back posted: not idle till they are, however long that thread takes on a loaded machine
        val end = System.currentTimeMillis() + WAIT_MS
        while (true) {
            while (!looper.isIdle) {
                check(++ran <= IDLE_TASKS) { "the main looper never idles: $IDLE_TASKS tasks run, more posted each time" }
                looper.runOneTask()
            }
            if (!HandwritingRecognition.sharedBusy) break
            check(System.currentTimeMillis() < end) { "the handwriting recognizer's reads not in after $WAIT_MS ms" }
            Thread.sleep(1)
        }
        selectionDelivered()
    }

    private var delivered = intArrayOf(-1, -1, -1, -1)

    /** The field's selection changes not told to the service while set: its tracked selection lagging, as an editor's report can. */
    var selectionLags = false

    // what the system's input method manager does as the field's selection or composing text
    // changes: Robolectric's tells the service nothing, whose tracking of the cursor would lag
    private fun selectionDelivered() {
        if (!::field.isInitialized || selectionLags) return
        val text = field.text
        val now = intArrayOf(
            field.selectionStart, field.selectionEnd,
            android.view.inputmethod.BaseInputConnection.getComposingSpanStart(text),
            android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(text),
        )
        if (now.contentEquals(delivered)) return
        val was = delivered
        delivered = now
        service.onUpdateSelection(was[0], was[1], now[0], now[1], now[2], now[3])
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Where [v] is on the screen: its left, top, right and bottom in the window. */
    fun box(v: View): Rect {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        return Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
    }

    /** Where [k]'s cell is on the screen, as [box] of its view. */
    fun box(k: ShownKey): Rect = k.bounds

    private var downAt = 0L

    // a finger on the service's input area, where the screen has it, as the window passes it on:
    // the keyboard hands the touch to its key; the area itself takes what no view holds (FloatingHandoff)
    private fun finger(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downAt = now
        val e = MotionEvent.obtain(downAt, now, action, x, y, 0)
        ((inputView.parent as? ViewGroup) ?: inputView).dispatchTouchEvent(e)
        e.recycle()
        fingerAt = x to y
    }

    private var fingerAt = 0f to 0f

    /** The finger left down by [drag] (lift = false) moved on to [toX], [toY] in [steps], lifted if [lift]. */
    fun move(toX: Float, toY: Float, steps: Int = 8, lift: Boolean = false) {
        val (x, y) = fingerAt
        for (i in 1..steps) {
            after(16)
            finger(MotionEvent.ACTION_MOVE, x + (toX - x) * i / steps, y + (toY - y) * i / steps)
        }
        if (lift) finger(MotionEvent.ACTION_UP, toX, toY)
        idle()
        relayout()
    }

    /** A second finger put down at [x], [y] beside the one down, then both lifted, the second first. */
    fun secondFingerThenLift(x: Float, y: Float) {
        val (fx, fy) = fingerAt
        val props = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = fx; this.y = fy }, MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        val target = (inputView.parent as? ViewGroup) ?: inputView
        for (action in listOf(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)) {
            after(16)
            val e = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            target.dispatchTouchEvent(e)
            e.recycle()
        }
        finger(MotionEvent.ACTION_UP, fx, fy)
        idle()
        relayout()
    }

    private var heldAt = 0f to 0f

    /** A finger put on [v]'s middle and held there till [release]. */
    fun press(v: View) = press(box(v))

    /** A finger put on [k]'s middle and held there till [release]. */
    fun press(k: ShownKey) = press(k.bounds)

    private fun press(b: Rect) {
        heldAt = b.exactCenterX() to b.exactCenterY()
        finger(MotionEvent.ACTION_DOWN, heldAt.first, heldAt.second)
        idle()
    }

    /** The finger [press] put down lifted where it is. */
    fun release() {
        finger(MotionEvent.ACTION_UP, heldAt.first, heldAt.second)
        idle()
        relayout()
    }

    /** The clock run on by [ms], and what came due. */
    fun after(ms: Long) = idleFor(ms)

    // as the looper's idleFor, the tasks due one at a time under the same limit as [idle]'s
    private fun idleFor(ms: Long) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = SystemClock.uptimeMillis() + ms
        var ran = 0
        while (true) {
            val next = looper.nextScheduledTaskTime.toMillis()
            if (looper.isIdle && (next == 0L || next > end)) break
            check(++ran <= IDLE_TASKS) { "the main looper never idles: $IDLE_TASKS tasks run, more posted each time" }
            looper.runOneTask()
        }
        // nothing due before the end left: the clock run on to it
        looper.idleFor(java.time.Duration.ofMillis(maxOf(0L, end - SystemClock.uptimeMillis())))
    }

    /** A finger put on [v]'s middle, slid by [dx], [dy] px in a few steps, lifted. */
    fun swipe(v: View, dx: Float, dy: Float) = swipe(box(v), dx, dy)

    /** A finger put on [k]'s middle, slid by [dx], [dy] px in a few steps, lifted. */
    fun swipe(k: ShownKey, dx: Float, dy: Float) = swipe(k.bounds, dx, dy)

    private fun swipe(b: Rect, dx: Float, dy: Float) {
        val x = b.exactCenterX()
        val y = b.exactCenterY()
        finger(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..4) {
            after(16)
            finger(MotionEvent.ACTION_MOVE, x + dx * i / 4, y + dy * i / 4)
        }
        after(16)
        finger(MotionEvent.ACTION_UP, x + dx, y + dy)
        idle()
        relayout()
    }

    /**
     * A popup's key as shown: its label and where it is in the window; [focused], the one the finger
     * is on (the scene's key selected); [cell], its whole cell; [size], its text's size (0 for an icon).
     */
    data class PopupKey(val text: String, val box: Rect, val focused: Boolean = false, val cell: Rect = box, val size: Float = 0f)

    /**
     * The popup's keys up now (a long press's choices), as the engine's scene draws them (060-render-p2
     * S2: no view per key), top row first.
     */
    fun popupChoices(): List<PopupKey> = all(inputView).filterIsInstance<PopupSceneView>().filter { shown(it) }.flatMap { sceneChoices(it, box(it)) }.toList()

    /**
     * The icons shown outside the keys, as [PopupKey]s read by their descriptions: the bar's and a
     * menu's cells (the edit menu's) the scene draws (S2).
     */
    fun shownIcons(): List<PopupKey> = all(inputView).filter { v -> v is ImageView && shown(v) }.map { PopupKey(it.contentDescription?.toString().orEmpty(), box(it)) }.toList() +
        all(inputView).filterIsInstance<PopupSceneView>().filter { shown(it) }.flatMap { v ->
            val at = box(v)
            v.scene?.keys.orEmpty().filter { k -> k.kind == "menu" && k.ops(false).any { it is Op.Icon } }
                .map { k -> PopupKey((k.a11y ?: k.label).orEmpty(), sceneBox(v, at, k)) }
        }

    /**
     * A finger held on [v] past the long press, then slid onto the popup's key showing [pick]
     * (left where it is when null) and lifted. Returns the popup keys' labels as shown.
     */
    fun longPress(v: View, pick: String? = null): List<String> = longPress(box(v), pick)

    /** [longPress] on [k]. */
    fun longPress(k: ShownKey, pick: String? = null): List<String> = longPress(k.bounds, pick)

    private fun longPress(b: Rect, pick: String?): List<String> {
        var x = b.exactCenterX()
        var y = b.exactCenterY()
        finger(MotionEvent.ACTION_DOWN, x, y)
        after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        val popup = popupChoices()
        val labels = popup.map { it.text }
        if (pick != null) {
            val t = (popup.firstOrNull { it.text == pick } ?: error("no $pick in the popup: $labels")).box
            // in steps, as a finger slides: the popup follows each move
            val (x0, y0) = x to y
            for (i in 1..4) {
                after(16)
                x = x0 + (t.exactCenterX() - x0) * i / 4
                y = y0 + (t.exactCenterY() - y0) * i / 4
                finger(MotionEvent.ACTION_MOVE, x, y)
            }
        }
        after(16)
        finger(MotionEvent.ACTION_UP, x, y)
        idle()
        relayout()
        return labels
    }

    /**
     * A finger put on [v]'s middle, held [holdMs] (a long press), moved to [toX], [toY] (the
     * screen's) in [steps] and lifted, or left down when [lift] is false (another finger next).
     */
    fun drag(v: View, toX: Float, toY: Float, holdMs: Long = 0, steps: Int = 12, lift: Boolean = true) = drag(box(v), toX, toY, holdMs, steps, lift)

    /** [drag] from [k]'s middle. */
    fun drag(k: ShownKey, toX: Float, toY: Float, holdMs: Long = 0, steps: Int = 12, lift: Boolean = true) = drag(k.bounds, toX, toY, holdMs, steps, lift)

    /** [drag] from the middle of [b], the screen's: where no key is (the split keyboard's gap). */
    fun drag(b: Rect, toX: Float, toY: Float, holdMs: Long = 0, steps: Int = 12, lift: Boolean = true) {
        val x = b.exactCenterX()
        val y = b.exactCenterY()
        finger(MotionEvent.ACTION_DOWN, x, y)
        if (holdMs > 0) {
            after(holdMs)
            // a long press may make the keyboard anew (the docked one let go floating): laid out before the finger moves on
            relayout()
        }
        for (i in 1..steps) {
            after(16)
            finger(MotionEvent.ACTION_MOVE, x + (toX - x) * i / steps, y + (toY - y) * i / steps)
        }
        after(16)
        if (lift) finger(MotionEvent.ACTION_UP, toX, toY)
        idle()
        relayout()
    }

    /** [text] copied in another app: kept in the clipboard's history (on Room's threads) before this returns. */
    fun copied(text: String) {
        // a clip is told apart by its time (the app skips one at the time of the last): each test's
        // clock starts over, so the app's last time, kept in its object from tests before, is forgotten
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastClipTimestamp = -1L
        // and its last entry, else a copy of the text a test before stored passes at once, before its own store: the
        // next copy's store, launched while this one's waits, may take the mutex first and this one land last (MN-04)
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry = null
        service.getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("", text))
        waitFor("$text stored") { org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry?.text == text }
        // its listeners' main-thread work (queued before lastEntry is set) done before the next tap
        idle()
    }

    /** The clipboard panel, from the toolbar, once [shown] is listed in it. */
    fun clipboard(shown: String? = null) {
        val clipboard = service.getString(R.string.clipboard)
        val expand = service.getString(R.string.expand_toolbar)
        // a copy's hint coming to the bar takes the toolbar back in: expanded again till it shows
        repeat(3) {
            if (all(inputView).any { shown(it) && it.contentDescription == clipboard }) return@repeat
            all(inputView).firstOrNull { shown(it) && it.contentDescription == expand }?.let(::tap)
            after(500)
            relayout()
        }
        tap(described(clipboard))
        if (shown != null) waitFor("$shown listed") { entry(shown) != null }
    }

    /** The clipboard panel's entry showing [text], the view taking its taps. */
    fun entry(text: String): View? = all(inputView).firstOrNull { shown(it) && it is TextView && it.text.toString() == text }
        ?.let { t -> generateSequence(t) { it.parent as? View }.firstOrNull { it.hasOnClickListeners() } }

    /** Waits (Room, the IO threads) till [ok], idling the main thread meanwhile. */
    fun waitFor(what: String, ok: () -> Boolean) =
        until(what, state = { "; shown ${texts(inputView)}" }, step = { idle(); relayout() }, done = ok)

    /**
     * The popup menu up now (a long press's, the clipboard's 清空), its item [title] picked as a tap
     * does: the app adds them all with one id, so not by id.
     */
    fun menuPick(title: String) {
        val menu = ShadowPopupMenu.getLatestPopupMenu().menu
        val item = (0 until menu.size()).map(menu::getItem).firstOrNull { it.title.toString() == title }
            ?: error("no $title in ${(0 until menu.size()).map { menu.getItem(it).title }}")
        // a tap puts the menu away first: the notice it leads to is the popup up next
        shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow?.let { menus += it }
        ShadowPopupMenu.getLatestPopupMenu().dismiss()
        // MenuItemImpl's own, what its view's tap calls
        item.javaClass.getMethod("invoke").invoke(item)
        idle()
        relayout()
    }

    /** The notice up now (提示条): its window, or null. */
    fun notice(): PopupWindow? {
        val latest = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        // a menu shown over a notice is the latest popup, the notice still up under it
        if (latest != null && latest !in menus) lastNotice = latest
        return lastNotice?.takeIf { it.isShowing }
    }

    private var lastNotice: PopupWindow? = null
    private val menus = mutableSetOf<PopupWindow>()

    /** What the notice up now says (its rows faded in, the one it replaced faded out), or none. */
    fun noticeTexts(): List<String> {
        after(500)
        val popup = notice() ?: return emptyList()
        return all(popup.contentView).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.alpha > 0.5f }
            .map { it.text.toString() }.toList()
    }

    /** Where the notice's window is on the screen: left, top, right, bottom. */
    fun noticeBox(): Rect {
        val popup = notice() ?: error("no notice")
        val lp = popup.contentView.rootView.layoutParams as WindowManager.LayoutParams
        return Rect(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height)
    }

    /** The clock run on [ms], and everything due in that time done. */
    fun advance(ms: Long) {
        idleFor(ms)
        relayout()
    }

    /**
     * A finger on [v]: down at the first point, then each next one [Touch.ms] after the last, up at
     * the last. Points are in [v]'s own pixels, so a slide off the key is a point outside its size.
     */
    fun touch(v: View, vararg path: Touch) = touch(finger(v, path.first()), path)

    /** [touch] on [k], the points in its cell's pixels. */
    fun touch(k: ShownKey, vararg path: Touch) = touch(finger(k, path.first()), path)

    private fun touch(f: Finger, path: Array<out Touch>) {
        path.drop(1).forEach { p ->
            advance(p.ms)
            f.move(p)
        }
        f.up(path.last())
    }

    /**
     * A finger put down on [v] at [at] (in [v]'s pixels), moved and lifted by the test. Its events
     * go in where a finger's do, at the input view, so the keyboard sees them as on a device
     * (its own touch handling, the popup's focus) and not only the key.
     */
    fun finger(v: View, at: Touch): Finger = Finger { IntArray(2).also(v::getLocationInWindow) }.also { it.send(MotionEvent.ACTION_DOWN, at) }

    /** [finger] on [k], [at] in its cell's pixels. */
    fun finger(k: ShownKey, at: Touch): Finger = Finger { k.bounds.let { intArrayOf(it.left, it.top) } }.also { it.send(MotionEvent.ACTION_DOWN, at) }

    /** A finger whose points are from [origin], the window's place of what it is on, read at each step. */
    inner class Finger(private val origin: () -> IntArray) {
        private val down = SystemClock.uptimeMillis()

        /** [eventTime]: the event's own, earlier than now to tell it from the time it is handled */
        internal fun send(action: Int, p: Touch, eventTime: Long = SystemClock.uptimeMillis()) {
            val from = origin()
            val root = IntArray(2).also(inputView::getLocationInWindow)
            val e = MotionEvent.obtain(
                down, eventTime, action,
                p.x + from[0] - root[0], p.y + from[1] - root[1], 0,
            )
            inputView.dispatchTouchEvent(e)
            e.recycle()
            idle()
        }

        fun move(p: Touch) = send(MotionEvent.ACTION_MOVE, p)

        fun up(p: Touch, eventTime: Long = SystemClock.uptimeMillis()) {
            send(MotionEvent.ACTION_UP, p, eventTime)
            relayout()
        }
    }

    /** A point of [touch]; [ms] the time since the one before. */
    data class Touch(val x: Float, val y: Float, val ms: Long = 0)

    /** The middle of [v], in its own pixels, moved by [dx], [dy] dp. */
    fun at(v: View, dx: Float = 0f, dy: Float = 0f, ms: Long = 0): Touch = at(v.width, v.height, dx, dy, ms)

    /** The middle of [k]'s cell, in its pixels, moved by [dx], [dy] dp. */
    fun at(k: ShownKey, dx: Float = 0f, dy: Float = 0f, ms: Long = 0): Touch = at(k.width, k.height, dx, dy, ms)

    private fun at(width: Int, height: Int, dx: Float, dy: Float, ms: Long): Touch {
        val dp = service.resources.displayMetrics.density
        return Touch(width / 2f + dx * dp, height / 2f + dy * dp, ms)
    }

    /** The nine keys' key for [digit] (its small digit), or the column's punctuation key, whichever draws it. */
    // 微软's 九键双拼's 1 is marked 1; (its ; beside the digit)
    fun nineKey(digit: String): ShownKey = keys().firstOrNull { it.alt == digit || it.alt == "$digit;" } ?: error("no key $digit in ${texts()}")

    /**
     * Every text shown under [v] (visible ones only), depth first: a canvas's keys' labels, corners and
     * marks' words (a shortcut's text) in its scene's order, as the key views' texts were in theirs.
     */
    fun texts(v: View = keyboard): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is KeyboardCanvasView -> v.scene?.keys.orEmpty().flatMap { k ->
            val said = listOfNotNull(k.label, k.alt?.takeIf(String::isNotEmpty))
            said + (k.ops(false).filterIsInstance<Op.Text>().map { it.text } - said.toSet())
        }
        v is TextView -> listOf(v.text.toString())
        v is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    /**
     * [capture] of a keyboard drawn by the canvas: its golden is `canvas_` before the file's name (060-render-p2 S4
     * step 8, when the views' goldens were beside them).
     */
    fun captureBoard(path: String, view: View = keyboard) {
        val canvas = all(view).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE }
        capture(if (canvas) path.substringBeforeLast('/', "").let { if (it.isEmpty()) "canvas_$path" else "$it/canvas_${path.substringAfterLast('/')}" } else path, view)
    }

    // drawn by hand: Roborazzi's View capture wants an Activity, and the service's window is none
    fun capture(path: String, view: View = keyboard) {
        settled()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        if (!comparesScreenshots) return
        bitmap.captureRoboImage(
            "src/test/screenshots/$path.png",
            RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)),
        )
    }

    // a keyboard shown slides in, a panel fades: the clock run on past them so every image is the end state
    private fun settled() {
        idleFor(1_000)
        relayout()
    }

    private var closed = false
    private var destroyed = false

    /** The service destroyed, as another keyboard picked: [close] after it all the same. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        controller.destroy()
    }

    override fun close() {
        if (closed) return
        closed = true
        // the clipboard's database outlives the test, kept open by the app's object: emptied for the next
        runBlocking {
            val cm = org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
            cm.purgeMarked(*cm.deleteAll(skipPinned = false))
        }
        // destroyed first: it learns the last field's address
        destroy()
        // Robolectric keeps every dialog shown till the test method ends (ShadowDialog.shownDialogs): the
        // input method's window, and through its mService the whole service, views and themes, of each
        // harness a test made. ThemeMakerLayoutTest makes a hundred and more: a full run's heap out (the
        // S4a review's OOM, a heap dump's 157 services all held there). Let go of this one's.
        service.window?.let { window ->
            // as many times as it was shown
            ShadowDialog.getShownDialogs().removeAll { it === window }
            if (ShadowDialog.getLatestDialog() === window) ShadowDialog.setLatestDialog(null)
        }
        // what the address bar learned in a test's email field: not the next test's (PrefsRule restores only what a test set)
        AppPrefs.getInstance().internal.run {
            addressCounts.setValue("")
            rememberedAddresses.setValue("")
        }
        // the field's activity destroyed: ActivityThread's list (mActivities) holds every one not destroyed till the
        // test ends, and with it its window, views and themes, the theme's a quarter MB each in Robolectric's
        // registry (NATIVE_THEME9_REGISTRY): ThemeMakerLayoutTest's two hundred harnesses in one test, 250 MB
        activities.forEach { it.close() }
        activities.clear()
        keyboardsWere?.let(::setKeyboards)
        codeRowWas?.let(ThemeManager.prefs.codeRow::setValue)
        HostDaemon.testConnection = null
        scope.cancel()
    }

    // the daemon's connection to a real host, which answers on its own thread
    private class HarnessConnection(override val lifecycleScope: CoroutineScope, val host: HostApi) : HostConnection {
        override fun <T> runImmediately(block: suspend HostApi.() -> T): T = runBlocking { block(host) }
        // as the daemon's: a host not up yet (a test pressing before it starts) does it once it is
        override suspend fun <T> runOnReady(block: suspend HostApi.() -> T): T {
            // the wait timed alone, not [block]; a plain error, which a cancelled job could not swallow. Timed on the
            // caller's dispatcher: on the paused main looper that is the test's clock (after()/advance() run it on,
            // idle() does not), not real time
            if (host is org.fcitx.fcitx5.android.core.KotlinHost)
                withTimeoutOrNull(READY_TIMEOUT_MS) { host.lifecycle.whenReady { } } ?: error("the host not up in $READY_TIMEOUT_MS ms")
            return block(host)
        }
        override fun runIfReady(block: suspend HostApi.() -> Unit) {
            if (host.isReady) lifecycleScope.launch { block(host) }
        }
    }

    companion object {
        // tasks one idle() runs before it calls the looper stuck: far more than any test posts in one go
        private const val IDLE_TASKS = 10_000

        // a host never up fails the test, not hangs it
        private const val READY_TIMEOUT_MS = 30_000L

        val PINYIN = InputMethodEntry(InputMethodCatalog.PINYIN, "拼音", "", "", "拼", "zh_CN", "androidengine", false)
        val ENGLISH = InputMethodEntry(InputMethodCatalog.ENGLISH, "English", "", "", "En", "en", "androidengine", false)

        /** Pinyin on the nine keys (0.4's 九键拼音): [PINYIN] with these [keyboards]. */
        val NINE_KEYS = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)

        /** [keyboards] chosen as the user would: each a preference, the keyboard made anew on a change. */
        fun setKeyboards(keyboards: ImKeyboards.Chosen) {
            val prefs = AppPrefs.getInstance().keyboard
            listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH).forEach {
                if (prefs.imKeyboards().of(it) != keyboards.of(it)) prefs.setImKeyboard(it, keyboards.of(it))
            }
        }

        inline fun <reified T : View> find(v: View): T? = all(v).firstNotNullOfOrNull { it as? T }

        /**
         * [v]'s keys with words, [at] where it is in the window: a bubble's, a long press's, a
         * menu's words (the 注's pills, an item of no icon); a menu's icons are not ([shownIcons]
         * reads those). Top row first, then left to right; [popupChoices] the ones up now.
         */
        fun sceneChoices(v: PopupSceneView, at: Rect): List<PopupKey> = v.scene?.keys.orEmpty()
            .filter { it.kind == "popup" || it.kind == "preview" || (it.kind == "menu" && it.ops(false).none { op -> op is Op.Icon }) }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .map { k ->
                val size = k.ops(false).filterIsInstance<Op.Text>().firstOrNull()?.size?.toFloat() ?: 0f
                PopupKey(k.label.orEmpty(), sceneBox(v, at, k), KeyState.Selected in k.states, size = size)
            }

        // [k]'s cell in the window, its popup view at [at]
        fun sceneBox(v: PopupSceneView, at: Rect, k: KeyNode): Rect {
            val l = at.left + v.outset.left
            val t = at.top + v.outset.top
            return Rect(l + k.bounds.left.toInt(), t + k.bounds.top.toInt(), l + k.bounds.right.toInt(), t + k.bounds.bottom.toInt())
        }

        /**
         * The keys [root] shows, depth first: each canvas's scene's in its order. [root] may be a keyboard
         * made alone (no input view), laid out.
         */
        fun keysOf(root: View, shown: (View) -> Boolean = { true }): List<ShownKey> = when {
            root.visibility != View.VISIBLE || !shown(root) -> emptyList()
            root is KeyboardCanvasView -> root.scene?.keys?.map { SceneShownKey(root, it.id, it.copy) }.orEmpty()
            root is ViewGroup -> (0 until root.childCount).flatMap { keysOf(root.getChildAt(it), shown) }
            else -> emptyList()
        }

        /** The symbol grid's cells under [root], each one key's scene (060-render-p2 S6). */
        fun cells(root: View): Sequence<View> = all(root).filter { it is org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell }

        /** What [cell] ([cells]) shows. */
        fun cellText(cell: View): String = (cell as org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell).keyNode?.label.orEmpty()

        /**
         * The keys' canvas under [root]: one a keyboard board draws its keys on (its nearest [BaseKeyboard] the
         * board), not the bar's number row's (060-render-p2 S6), which is made with the bar and shown only for a
         * password, and would otherwise be found first.
         */
        fun boardCanvas(root: View): KeyboardCanvasView? = all(root).filterIsInstance<KeyboardCanvasView>().firstOrNull { c ->
            val board = generateSequence(c.parent) { it.parent }.firstOrNull { it is BaseKeyboard }
            board != null && board !is org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
        }

        /** [v] and every view under it, depth first. */
        fun all(v: View): Sequence<View> = sequence {
            yield(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(all(v.getChildAt(i)))
        }
    }
}
