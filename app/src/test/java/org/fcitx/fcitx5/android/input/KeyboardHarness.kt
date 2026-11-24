/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.app.Activity
import android.content.ClipData
import android.content.res.Configuration
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
import org.fcitx.fcitx5.android.WAIT_MS
import org.fcitx.fcitx5.android.comparesScreenshots
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.host.ChinesePunctuation
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
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
 * The real [FcitxInputMethodService] under Robolectric on a [FakeHostApi] (via [HostDaemon.testConnection]): push
 * engine events with [emit], read what the keys asked in [api]`.calls`. [theme] null keeps the active one.
 * Needs `@Config(application = FcitxApplication::class)` (screenshots also `@GraphicsMode(NATIVE)`); call [close]
 * in `@After`, since the daemon seam is global.
 */
class KeyboardHarness(
    theme: Theme? = TestThemes.PixelLight,
    im: InputMethodEntry = PINYIN,
    /** A real host to type through instead of [api]; [ready] is the one a job waits on, a wrapper's too. */
    host: HostApi? = null, ready: org.fcitx.fcitx5.android.core.KotlinHost? = host as? org.fcitx.fcitx5.android.core.KotlinHost,
    /** Set before the keyboard is made, put back at [close]; null leaves the preferences as they are. */
    keyboards: ImKeyboards.Chosen? = null,
    /** Tests default to embedded; set before the keyboard is made, restored at [close]; set only if it differs, since a change rebuilds every input view. */
    codeRow: CodeRowStyle = CodeRowStyle.Embedded,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<Activity>>()
    val api = FakeHostApi().apply { inputMethodEntryCached = im }
    private val controller: ServiceController<FcitxInputMethodService>
    val service: FcitxInputMethodService

    private val keyboardsWere = keyboards?.let { AppPrefs.getInstance().keyboard.imKeyboards() }

    private val codeRowWas = ThemeManager.prefs.codeRow.getValue().takeIf { it != codeRow }

    init {
        // both packs installed, else the keyboard is PackGateView's import prompt; a test of that puts its own
        TestShelves.use()
        keyboards?.let(::setKeyboards)
        codeRowWas?.let { ThemeManager.prefs.codeRow.setValue(codeRow) }
        val connection = host?.let { HarnessConnection(scope, it, ready) } ?: FakeHostConnection(scope, api)
        HostDaemon.testConnection = { connection }
        // the seam is global: a harness that fails to build must not leave it set
        try {
            theme?.let(ThemeManager::setNormalModeTheme)
            controller = Robolectric.buildService(FcitxInputMethodService::class.java).create()
            service = controller.get()
            // without the window token it is never shown; InputMethod.attachToken wants a display area Robolectric lacks
            service.window.javaClass.getDeclaredMethod("setToken", IBinder::class.java)
                .apply { isAccessible = true }.invoke(service.window, Binder())
            service.onCreateInputView()
            // Robolectric clears the clipboard's listeners after each test; the app adds its own once a process
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

    /** Looked up each time: a setting may remake it. */
    val inputView: InputView get() = find(service.window.window!!.decorView) ?: error("no InputView")

    /** Under the bar: the letters, a panel or the pad. */
    val board: View get() = inputView.findViewById(R.id.input_window)

    val bar: View
        get() = inputView.kawaiiBar.view

    /** What a screenshot shows: bar, keys and their background, not the transparent room above. */
    val keyboard: View get() = inputView.keyboardView

    lateinit var field: EditText
        private set

    lateinit var info: EditorInfo
        private set

    private lateinit var connection: InputConnection

    /** [fieldId] tells fields apart as ContextMemory.field does; [terminal]: TYPE_NULL yet taking text, as Termux's view. */
    fun start(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
        packageName: String = "org.example.app",
        fieldId: Int = 0,
        terminal: Boolean = false,
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
        deliveredText = field.text.toString()
        val info = EditorInfo().also {
            it.packageName = packageName
            it.fieldId = fieldId
        }
        val target = field
        val made = ++connectionsMade
        // the system hands sent keys to the focused view; Robolectric's connection does not
        val ic = object : android.view.inputmethod.InputConnectionWrapper(field.onCreateInputConnection(info), true) {
            // after closeConnection every call is dropped, as the system's connection to a gone view drops it
            private val open get() = made > closedThrough

            override fun sendKeyEvent(event: KeyEvent): Boolean =
                open && ((event.isCtrlPressed && event.action == KeyEvent.ACTION_DOWN && target.onKeyShortcut(event.keyCode, event)) ||
                    target.dispatchKeyEvent(event))

            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
                if (nothingBeforeGiven || !open) null else super.getTextBeforeCursor(n, flags).also { beforeReads += n }

            override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText? =
                if (nothingBeforeGiven || nothingAfterGiven || !open) null else super.getSurroundingText(beforeLength, afterLength, flags)

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
                if (nothingAfterGiven || !open) null else super.getTextAfterCursor(n, flags)

            override fun takeSnapshot(): android.view.inputmethod.TextSnapshot? =
                if (nothingBeforeGiven || nothingAfterGiven || !open) null else super.takeSnapshot()

            override fun beginBatchEdit() = open && super.beginBatchEdit()

            override fun endBatchEdit() = open && super.endBatchEdit()

            override fun commitText(text: CharSequence?, newCursorPosition: Int) = open && (writesDropped || super.commitText(text, newCursorPosition))

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = open && (writesDropped || super.setComposingText(text, newCursorPosition))

            override fun setComposingRegion(start: Int, end: Int) = open && (writesDropped || super.setComposingRegion(start, end))

            override fun finishComposingText() = open && (writesDropped || super.finishComposingText())

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = open && (writesDropped || super.deleteSurroundingText(beforeLength, afterLength))

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
                open && (writesDropped || super.deleteSurroundingTextInCodePoints(beforeLength, afterLength))

            override fun setSelection(start: Int, end: Int) = open && (writesDropped || super.setSelection(start, end))

            override fun performEditorAction(editorAction: Int) = open && super.performEditorAction(editorAction)
        }
        if (terminal) info.inputType = InputType.TYPE_NULL
        this.info = info
        connection = ic
        doStartInput(ic, info, restarting = false)
        service.onStartInputView(info, false)
        // views attached (what a chip, a popup checks), the input area shown
        runCatching { service.showWindow(true) }.onFailure { System.err.println("showWindow: $it") }
        idle()
        relayout()
        return this
    }

    /** The app closes the connection before the service is told: every call dropped till the next [start]. */
    fun closeConnection() {
        closedThrough = connectionsMade
    }

    private var connectionsMade = 0
    private var closedThrough = 0

    /** The app's screen recreated: the old connection closed, then a same-id field with the old text and selection (no composing span). */
    fun recreate() {
        val text = field.text.toString()
        val (start, end) = field.selectionStart to field.selectionEnd
        val type = field.inputType
        closeConnection()
        start(inputType = type, imeOptions = field.imeOptions, packageName = info.packageName, fieldId = info.fieldId, terminal = info.inputType == InputType.TYPE_NULL) {
            it.setText(text)
            it.setSelection(start, end)
        }
    }

    /** As a WebView's field told anew. */
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

    /** As hideWindow: the input view finished, so leaving the field next finishes the input only. */
    fun hide() {
        service.onFinishInputView(false)
        InputMethodService::class.java.getDeclaredField("mInputViewStarted").apply { isAccessible = true }.setBoolean(service, false)
        idle()
        relayout()
    }

    /** Another field of the app's focused: the framework finishes this input and starts that one in one go. */
    fun another() {
        val info = EditorInfo().also {
            it.packageName = this.info.packageName
            it.fieldId = this.info.fieldId + 1
        }
        field.onCreateInputConnection(info)
        this.info = info
        doStartInput(connection, info, restarting = false)
        service.onStartInputView(info, false)
        idle()
        relayout()
    }

    /** Input restarted with the same EditorInfo (resetStateForNewConfiguration). */
    fun turn() {
        doStartInput(connection, info, restarting = true)
        service.onStartInputView(info, true)
        idle()
        relayout()
    }

    // InputMethodService's own hidden entry: sets the connection, then onStartInput
    private fun doStartInput(ic: InputConnection, info: EditorInfo, restarting: Boolean) {
        InputMethodService::class.java
            .getDeclaredMethod("doStartInput", InputConnection::class.java, EditorInfo::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(service, ic, info, restarting)
    }

    fun emit(event: HostEvent) {
        check(api.events.tryEmit(event)) { "event buffer full" }
        idle()
        relayout()
    }

    /** Robolectric never shows the window, so the input view is laid out at screen size by hand. */
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

    /** Depth first, each canvas's scene in its order; [root] may be any view with keyboards in it (the settings' preview). */
    fun keys(root: View = inputView): List<ShownKey> = keysOf(root) { v -> if (root === inputView) shown(v) else true }

    fun key(label: String): ShownKey = keys().firstOrNull { it.label == label } ?: error("no key $label in ${texts()}")

    /** A split row draws G and V on both halves. */
    fun keysLabelled(label: String): List<ShownKey> = keys().filter { it.label == label }

    /** [copy]: which of a split row's two. */
    fun keyNamed(id: String, copy: Int = 0): ShownKey = keys().firstOrNull { it.id == id && it.copy == copy }
        ?: error("no key named $id ($copy) in ${keys().map { it.id }}")

    fun keyWithId(id: Int): ShownKey? = service.resources.getResourceEntryName(id).let { name -> keys().firstOrNull { it.resourceName == name } }

    fun describedKey(description: String): ShownKey = keys().firstOrNull { it.description == description }
        ?: error("no key described as $description in ${keys().mapNotNull { it.description }}")

    fun described(description: String): View = all(inputView)
        .firstOrNull { shown(it) && it.contentDescription?.toString() == description }
        ?: error("nothing described as $description in ${all(inputView).filter { shown(it) }.mapNotNull { it.contentDescription }.toList()}")

    /** [View.isShown] is false throughout, the window never being shown. */
    fun shown(v: View): Boolean {
        var at: View? = v
        while (at != null && at !== inputView) {
            if (at.visibility != View.VISIBLE) return false
            at = at.parent as? View
        }
        return at != null
    }

    fun tap(v: View) {
        check(v.performClick()) { "$v took no click" }
        idle()
        relayout()
    }

    fun tap(k: ShownKey) {
        check(k.click()) { "$k took no click" }
        idle()
        relayout()
    }

    fun idle() {
        // one task at a time, so a task reposting itself for ever fails here instead of hanging at 100% CPU
        val looper = shadowOf(Looper.getMainLooper())
        var ran = 0
        // the handwriting recognizer's reads come back posted from its own thread: not idle till they do
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

    /** getTextBeforeCursor, getSurroundingText and takeSnapshot give null: the testbed's `null_before` field. */
    var nothingBeforeGiven = false

    val beforeReads = mutableListOf<Int>()

    /** The mirror of [nothingBeforeGiven]. */
    var nothingAfterGiven = false

    /** Writes are taken and ignored, reads answered: a WebView that lost focus with the keyboard up. Nothing is reported. */
    var writesDropped = false

    /** Selection changes are not reported to the service, as a lagging editor's. */
    var selectionLags = false

    /** As a device's EditText, a change of the composing span alone is not reported till the next change that is. */
    var spanAloneUnreported = false

    private var deliveredText = ""

    // what the system's IMM does on selection or composing changes; Robolectric's tells the service nothing
    private fun selectionDelivered() {
        if (!::field.isInitialized || selectionLags) return
        val text = field.text
        val now = intArrayOf(
            field.selectionStart, field.selectionEnd,
            android.view.inputmethod.BaseInputConnection.getComposingSpanStart(text),
            android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(text),
        )
        if (now.contentEquals(delivered)) return
        val textNow = text.toString()
        val spanAlone = now[0] == delivered[0] && now[1] == delivered[1] && textNow == deliveredText
        if (spanAloneUnreported && spanAlone) return
        deliveredText = textNow
        val was = delivered
        delivered = now
        service.onUpdateSelection(was[0], was[1], now[0], now[1], now[2], now[3])
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun box(v: View): Rect {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        return Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
    }

    fun box(k: ShownKey): Rect = k.bounds

    private var downAt = 0L

    // the keyboard hands the touch to its key; the area takes what no view holds (FloatingHandoff)
    private fun finger(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downAt = now
        val e = MotionEvent.obtain(downAt, now, action, x, y, 0)
        ((inputView.parent as? ViewGroup) ?: inputView).dispatchTouchEvent(e)
        e.recycle()
        fingerAt = x to y
    }

    private var fingerAt = 0f to 0f

    /** Moves the finger [drag] left down. */
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

    /**
     * Both lifted, the second first unless [firstLifts] (two thumbs overlapping); each event's work done before the next.
     * [meanwhile] runs with both down (the first moved by [move]).
     */
    fun secondFingerThenLift(x: Float, y: Float, firstLifts: Boolean = false, meanwhile: () -> Unit = {}) {
        val props = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val target = (inputView.parent as? ViewGroup) ?: inputView
        for (action in listOf(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)) {
            if (action == MotionEvent.ACTION_POINTER_UP) meanwhile()
            val (fx, fy) = fingerAt
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = fx; this.y = fy }, MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
            after(16)
            val index = if (firstLifts && action == MotionEvent.ACTION_POINTER_UP) 0 else 1
            val e = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            target.dispatchTouchEvent(e)
            e.recycle()
            if (firstLifts) idle()
        }
        if (firstLifts) {
            after(16)
            val e = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1, arrayOf(props[1]), arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y }), 0, 0, 1f, 1f, 0, 0, 0, 0)
            target.dispatchTouchEvent(e)
            e.recycle()
        } else {
            finger(MotionEvent.ACTION_UP, fingerAt.first, fingerAt.second)
        }
        idle()
        relayout()
    }

    private var heldAt = 0f to 0f

    /** Held till [release]. */
    fun press(v: View) = press(box(v))

    /** Held till [release]. */
    fun press(k: ShownKey) = press(k.bounds)

    private fun press(b: Rect) {
        heldAt = b.exactCenterX() to b.exactCenterY()
        finger(MotionEvent.ACTION_DOWN, heldAt.first, heldAt.second)
        idle()
    }

    fun release() {
        finger(MotionEvent.ACTION_UP, heldAt.first, heldAt.second)
        idle()
        relayout()
    }

    fun after(ms: Long) = idleFor(ms)

    // as the looper's idleFor, one task at a time under [idle]'s limit, selection changes reported at the end
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
        looper.idleFor(java.time.Duration.ofMillis(maxOf(0L, end - SystemClock.uptimeMillis())))
        selectionDelivered()
    }

    fun swipe(v: View, dx: Float, dy: Float) = swipe(box(v), dx, dy)

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

    /** [focused]: the one the finger is on; [size]: its text's size (0 for an icon). */
    data class PopupKey(val text: String, val box: Rect, val focused: Boolean = false, val cell: Rect = box, val size: Float = 0f)

    /** The scene draws them, no view per key; top row first. */
    fun popupChoices(): List<PopupKey> = all(inputView).filterIsInstance<PopupSceneView>().filter { shown(it) }.flatMap { sceneChoices(it, box(it)) }.toList()

    /** Icons outside the keys, read by their descriptions: the bar's and a menu's cells. */
    fun shownIcons(): List<PopupKey> = all(inputView).filter { v -> v is ImageView && shown(v) }.map { PopupKey(it.contentDescription?.toString().orEmpty(), box(it)) }.toList() +
        all(inputView).filterIsInstance<PopupSceneView>().filter { shown(it) }.flatMap { v ->
            val at = box(v)
            v.scene?.keys.orEmpty().filter { k -> k.kind == "menu" && k.ops(false).any { it is Op.Icon } }
                .map { k -> PopupKey((k.a11y ?: k.label).orEmpty(), sceneBox(v, at, k)) }
        }

    /** Held past the long press, slid onto [pick] (stays put when null) and lifted; returns the popup's labels. */
    fun longPress(v: View, pick: String? = null): List<String> = longPress(box(v), pick)

    fun longPress(k: ShownKey, pick: String? = null, meanwhile: () -> Unit = {}): List<String> = longPress(k.bounds, pick, meanwhile)

    // [meanwhile] runs once the popup is up, before the finger moves
    private fun longPress(b: Rect, pick: String?, meanwhile: () -> Unit = {}): List<String> {
        var x = b.exactCenterX()
        var y = b.exactCenterY()
        finger(MotionEvent.ACTION_DOWN, x, y)
        after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        meanwhile()
        val popup = popupChoices()
        val labels = popup.map { it.text }
        if (pick != null) {
            val t = (popup.firstOrNull { it.text == pick } ?: error("no $pick in the popup: $labels")).box
            // in steps: the popup follows each move
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

    /** [holdMs] makes a long press; [lift] false leaves the finger down for another. */
    fun drag(v: View, toX: Float, toY: Float, holdMs: Long = 0, steps: Int = 12, lift: Boolean = true) = drag(box(v), toX, toY, holdMs, steps, lift)

    fun drag(k: ShownKey, toX: Float, toY: Float, holdMs: Long = 0, steps: Int = 12, lift: Boolean = true) = drag(k.bounds, toX, toY, holdMs, steps, lift)

    /** Where no key is (the split keyboard's gap). */
    fun drag(b: Rect, toX: Float, toY: Float, holdMs: Long = 0, steps: Int = 12, lift: Boolean = true) {
        val x = b.exactCenterX()
        val y = b.exactCenterY()
        finger(MotionEvent.ACTION_DOWN, x, y)
        if (holdMs > 0) {
            after(holdMs)
            // a long press may remake the keyboard (docked let go floating): laid out before the finger moves on
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

    /** Kept in the clipboard's history (Room's threads) before this returns. */
    fun copied(text: String) {
        // the app skips a clip at the time of the last; each test's clock starts over, so forget the last time
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastClipTimestamp = -1L
        // and the last entry, else a copy of a text an earlier test stored passes before its own store lands
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry = null
        service.getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("", text))
        waitFor("$text stored") { org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry?.text == text }
        idle()
    }

    fun clipboard(shown: String? = null) {
        val clipboard = service.getString(R.string.clipboard)
        val expand = service.getString(R.string.expand_toolbar)
        // a copy's hint takes the toolbar back in: expanded again till it shows
        repeat(3) {
            if (all(inputView).any { shown(it) && it.contentDescription == clipboard }) return@repeat
            all(inputView).firstOrNull { shown(it) && it.contentDescription == expand }?.let(::tap)
            after(500)
            relayout()
        }
        // no slot on the bar (one-handed, floating): ⋯'s panel has it
        if (all(inputView).none { shown(it) && it.contentDescription == clipboard }) {
            tap(described(service.getString(R.string.status_area)))
            waitFor("$clipboard in ⋯'s panel") { relayout(); entry(clipboard) != null }
        }
        tap(entry(clipboard) ?: described(clipboard))
        if (shown != null) waitFor("$shown listed") { entry(shown) != null }
    }

    fun entry(text: String): View? = all(inputView).firstOrNull { shown(it) && it is TextView && it.text.toString() == text }
        ?.let { t -> generateSequence(t) { it.parent as? View }.firstOrNull { it.hasOnClickListeners() } }

    /** Idles the main thread while Room and the IO threads work. */
    fun waitFor(what: String, ok: () -> Boolean) =
        until(what, state = { "; shown ${texts(inputView)}" }, step = { idle(); relayout() }, done = ok)

    /** Picked as a tap does, by title: the app adds every item with one id. */
    fun menuPick(title: String) {
        val menu = ShadowPopupMenu.getLatestPopupMenu().menu
        val item = (0 until menu.size()).map(menu::getItem).firstOrNull { it.title.toString() == title }
            ?: error("no $title in ${(0 until menu.size()).map { menu.getItem(it).title }}")
        // a tap puts the menu away first: the notice it leads to is the popup up next
        menuUp()
        ShadowPopupMenu.getLatestPopupMenu().dismiss()
        // MenuItemImpl's own, what its view's tap calls
        item.javaClass.getMethod("invoke").invoke(item)
        idle()
        relayout()
    }

    /** The popup up last is a menu, never taken for a notice. */
    fun menuUp() {
        shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow?.let { menus += it }
    }

    fun notice(): PopupWindow? {
        val latest = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        // a menu shown over a notice is the latest popup, the notice still up under it
        if (latest != null && latest !in menus) lastNotice = latest
        return lastNotice?.takeIf { it.isShowing }
    }

    private var lastNotice: PopupWindow? = null
    private val menus = mutableSetOf<PopupWindow>()

    /** Its rows faded in, not the one it replaced. */
    fun noticeTexts(): List<String> {
        after(500)
        val popup = notice() ?: return emptyList()
        return all(popup.contentView).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.alpha > 0.5f }
            .map { it.text.toString() }.toList()
    }

    fun noticeBox(): Rect {
        val popup = notice() ?: error("no notice")
        val lp = popup.contentView.rootView.layoutParams as WindowManager.LayoutParams
        return Rect(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height)
    }

    fun advance(ms: Long) {
        idleFor(ms)
        relayout()
    }

    /** Points in [v]'s own pixels, each [Touch.ms] after the last; a slide off the key is a point outside its size. */
    fun touch(v: View, vararg path: Touch) = touch(finger(v, path.first()), path)

    fun touch(k: ShownKey, vararg path: Touch) = touch(finger(k, path.first()), path)

    private fun touch(f: Finger, path: Array<out Touch>) {
        path.drop(1).forEach { p ->
            advance(p.ms)
            f.move(p)
        }
        f.up(path.last())
    }

    /** Events go in at the input view, as a finger's do, so the keyboard's own touch handling sees them. */
    fun finger(v: View, at: Touch): Finger = Finger { IntArray(2).also(v::getLocationInWindow) }.also { it.send(MotionEvent.ACTION_DOWN, at) }

    fun finger(k: ShownKey, at: Touch): Finger = Finger { k.bounds.let { intArrayOf(it.left, it.top) } }.also { it.send(MotionEvent.ACTION_DOWN, at) }

    /** [origin] is re-read at each step. */
    inner class Finger(private val origin: () -> IntArray) {
        private val down = SystemClock.uptimeMillis()

        /** [eventTime] earlier than now tells the event's time from when it is handled. */
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

    /** [ms] since the point before. */
    data class Touch(val x: Float, val y: Float, val ms: Long = 0)

    /** [v]'s middle in its pixels, moved by [dx], [dy] dp. */
    fun at(v: View, dx: Float = 0f, dy: Float = 0f, ms: Long = 0): Touch = at(v.width, v.height, dx, dy, ms)

    fun at(k: ShownKey, dx: Float = 0f, dy: Float = 0f, ms: Long = 0): Touch = at(k.width, k.height, dx, dy, ms)

    private fun at(width: Int, height: Int, dx: Float, dy: Float, ms: Long): Touch {
        val dp = service.resources.displayMetrics.density
        return Touch(width / 2f + dx * dp, height / 2f + dy * dp, ms)
    }

    /** As fcitx's addon tells it (cached too, so a remade view replays it). [marks] empty: punctuation off. */
    fun chinesePunctuation(im: InputMethodEntry = PINYIN, marks: Map<String, String> = ChinesePunctuation.map) {
        val status = if (marks.isEmpty()) emptyList() else listOf(StatusEntry(1, "punctuation", "fcitx-punc-active", "", true))
        api.statusAreaCached = status
        api.punctuationCached = marks
        emit(HostEvent.StatusArea(status, im, marks))
        idle()
    }

    /** By its small digit, or the column's punctuation key. */
    // 微软's 九键双拼's 1 is marked 1; (its ; beside the digit)
    fun nineKey(digit: String): ShownKey = keys().firstOrNull { k -> (k as? SceneShownKey)?.node?.takeIf { it.copy == 0 }?.shortcut == "t9/$digit" }
        // the old views have no names: by the digit in the corner
        ?: keys().firstOrNull { it.alt == digit || it.alt == "$digit;" } ?: error("no key $digit in ${texts()}")

    /** Visible texts depth first: a canvas's labels, corners and marks' words in its scene's order. */
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

    /** A canvas-drawn keyboard's golden is prefixed `canvas_`. */
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

    // past slides and fades, so every image is the end state
    private fun settled() {
        idleFor(1_000)
        relayout()
    }

    private var closed = false
    private var destroyed = false

    /** As another keyboard picked; [close] after it all the same. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        controller.destroy()
    }

    override fun close() {
        if (closed) return
        closed = true
        // the clipboard's database outlives the test, held open by the app's object
        runBlocking { org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.nukeTable() }
        // the next test's fake clock starts where this one's did: its first clip would carry the last one's time
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.resetDuplicateCheck()
        // posted recognizer reads run first; what that throws is rethrown once the rest is let go, after the test's own failure
        val idleFailed = runCatching { idle() }.exceptionOrNull()
        // first: it learns the last field's address
        destroy()
        // and what destroying set off: taking the keyboard off finishes what the pad holds behind the reads
        val destroyFailed = runCatching { idle() }.exceptionOrNull()
        // Robolectric keeps every shown dialog, and through the IME window the whole service, till the method ends:
        // a test of hundreds of harnesses runs the heap out
        service.window?.let { window ->
            // as many times as it was shown
            ShadowDialog.getShownDialogs().removeAll { it === window }
            if (ShadowDialog.getLatestDialog() === window) ShadowDialog.setLatestDialog(null)
        }
        // the address bar's learning in a test's email field (PrefsRule restores only what a test set)
        AppPrefs.getInstance().internal.run {
            addressCounts.setValue("")
            rememberedAddresses.setValue("")
        }
        // ActivityThread holds every undestroyed activity, its window and a quarter-MB theme each, till the test ends
        activities.forEach { it.close() }
        activities.clear()
        keyboardsWere?.let(::setKeyboards)
        codeRowWas?.let(ThemeManager.prefs.codeRow::setValue)
        HostDaemon.testConnection = null
        scope.cancel()
        // both kept: the destroy's failure not lost behind the idle's
        (idleFailed?.also { e -> destroyFailed?.let(e::addSuppressed) } ?: destroyFailed)?.let { throw it }
    }

    private class HarnessConnection(override val lifecycleScope: CoroutineScope, val host: HostApi, val ready: org.fcitx.fcitx5.android.core.KotlinHost?) : HostConnection {
        override fun <T> runImmediately(block: suspend HostApi.() -> T): T = runBlocking { block(host) }
        // as the daemon's: a host not up yet does it once it is
        override suspend fun <T> runOnReady(block: suspend HostApi.() -> T): T {
            // only the wait is timed, with a plain error a cancelled job cannot swallow; on the paused main looper it is the
            // test's clock (after()/advance() run it, idle() does not), not real time
            ready?.let { withTimeoutOrNull(READY_TIMEOUT_MS) { it.lifecycle.whenReady { } } ?: error("the host not up in $READY_TIMEOUT_MS ms") }
            return block(host)
        }
        override fun runIfReady(block: suspend HostApi.() -> Unit) {
            if (host.isReady) lifecycleScope.launch { block(host) }
        }
    }

    companion object {
        // far more than any test posts in one go
        private const val IDLE_TASKS = 10_000

        // a host never up fails the test, not hangs it
        private const val READY_TIMEOUT_MS = 30_000L

        val PINYIN = InputMethodEntry(InputMethodCatalog.PINYIN, "拼音", "", "拼", "zh_CN")
        val ENGLISH = InputMethodEntry(InputMethodCatalog.ENGLISH, "English", "", "En", "en")

        val NINE_KEYS = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)

        /** Each a preference, as the user would; a change remakes the keyboard. */
        fun setKeyboards(keyboards: ImKeyboards.Chosen) {
            val prefs = AppPrefs.getInstance().keyboard
            listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH).forEach {
                if (prefs.imKeyboards().of(it) != keyboards.of(it)) prefs.setImKeyboard(it, keyboards.of(it))
            }
        }

        inline fun <reified T : View> find(v: View): T? = all(v).firstNotNullOfOrNull { it as? T }

        /** Keys with words, top row first; a menu's icons are [shownIcons]'s; [popupChoices] the ones up now. */
        fun sceneChoices(v: PopupSceneView, at: Rect): List<PopupKey> = v.scene?.keys.orEmpty()
            .filter { it.kind == "popup" || it.kind == "preview" || (it.kind == "menu" && it.ops(false).none { op -> op is Op.Icon }) }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .map { k ->
                val size = k.ops(false).filterIsInstance<Op.Text>().firstOrNull()?.size?.toFloat() ?: 0f
                PopupKey(k.label.orEmpty(), sceneBox(v, at, k), KeyState.Selected in k.states, size = size)
            }

        fun sceneBox(v: PopupSceneView, at: Rect, k: KeyNode): Rect {
            val l = at.left + v.outset.left
            val t = at.top + v.outset.top
            return Rect(l + k.bounds.left.toInt(), t + k.bounds.top.toInt(), l + k.bounds.right.toInt(), t + k.bounds.bottom.toInt())
        }

        /** [root] may be a keyboard made alone (no input view), laid out. */
        fun keysOf(root: View, shown: (View) -> Boolean = { true }): List<ShownKey> = when {
            root.visibility != View.VISIBLE || !shown(root) -> emptyList()
            root is KeyboardCanvasView -> root.scene?.keys?.map { SceneShownKey(root, it.id, it.copy) }.orEmpty()
            root is ViewGroup -> (0 until root.childCount).flatMap { keysOf(root.getChildAt(it), shown) }
            else -> emptyList()
        }

        fun cells(root: View): Sequence<View> = all(root).filter { it is org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell }

        fun cellText(cell: View): String = (cell as org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell).keyNode?.label.orEmpty()

        /** Not the bar's number row's canvas (made with the bar, shown only for a password), which would otherwise be found first. */
        fun boardCanvas(root: View): KeyboardCanvasView? = all(root).filterIsInstance<KeyboardCanvasView>().firstOrNull { c ->
            val board = generateSequence(c.parent) { it.parent }.firstOrNull { it is BaseKeyboard }
            board != null && board !is org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
        }

        fun all(v: View): Sequence<View> = sequence {
            yield(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(all(v.getChildAt(i)))
        }
    }
}

/** Upright again after [block] whether or not it passed; a failed turn back is added to its failure. Outside the class for detekt LargeClass. */
fun KeyboardHarness.inLandscape(relayout: Boolean = false, block: () -> Unit) {
    fun turnTo(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        service.onConfigurationChanged(Configuration(service.resources.configuration))
        idle()
    }
    fun upright() {
        turnTo("+port")
        if (relayout) relayout()
    }
    try {
        turnTo("+land")
        block()
    } catch (e: Throwable) {
        runCatching { upright() }.exceptionOrNull()?.let(e::addSuppressed)
        throw e
    }
    upright()
}

fun KeyboardHarness.sent() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
