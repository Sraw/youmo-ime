/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.app.Activity
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
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.core.FakeFcitxConnection
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowPopupMenu

/**
 * The whole keyboard on the JVM: the real [FcitxInputMethodService] built by Robolectric, its
 * [InputView] made as on a device, on a [FakeFcitxAPI] instead of the Kotlin host (the seam
 * [FcitxDaemon.testConnection]). Engine output comes from events pushed with [emit]; what the
 * keys asked of the engine is in [api]`.calls`.
 *
 * [theme] is made the active one first; null keeps what is active.
 * Needs `@Config(application = FcitxApplication::class)`; screenshots also `@GraphicsMode(NATIVE)`.
 * Call [close] at the end (an `@After`): the daemon seam is global.
 */
class KeyboardHarness(
    theme: Theme? = ThemePreset.PixelLight,
    im: InputMethodEntry = PINYIN,
    /** A real host to type through (a [KotlinFcitx] on a scripted engine) in place of [api]: its own events, its own answers. */
    host: FcitxAPI? = null,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val api = FakeFcitxAPI().apply { inputMethodEntryCached = im }
    private val controller: ServiceController<FcitxInputMethodService>
    val service: FcitxInputMethodService

    init {
        val connection = host?.let { HostConnection(scope, it) } ?: FakeFcitxConnection(scope, api)
        FcitxDaemon.testConnection = { connection }
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
            FcitxDaemon.testConnection = null
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

    /** Focus a field of this [inputType], as the app's field gaining focus would; [packageName] the app's. */
    fun start(inputType: Int = InputType.TYPE_CLASS_TEXT, imeOptions: Int = 0, packageName: String = "org.example.app"): KeyboardHarness {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        field = EditText(activity).also {
            it.inputType = inputType
            it.imeOptions = imeOptions
            activity.setContentView(it)
            // keys the keyboard sends (←, Home, Ctrl+Z) go to the focused view, as on a device
            it.requestFocus()
        }
        delivered = intArrayOf(field.selectionStart, field.selectionEnd, -1, -1)
        val info = EditorInfo().also { it.packageName = packageName }
        val target = field
        // a key the keyboard sends (←, Home, Ctrl+Z) the system hands to the focused view: Robolectric's does not
        val ic = object : android.view.inputmethod.InputConnectionWrapper(field.onCreateInputConnection(info), true) {
            override fun sendKeyEvent(event: KeyEvent): Boolean =
                // with Ctrl, a shortcut (Ctrl+Z), as the view root hands it on
                (event.isCtrlPressed && event.action == KeyEvent.ACTION_DOWN && target.onKeyShortcut(event.keyCode, event)) ||
                    target.dispatchKeyEvent(event)
        }
        // InputMethodService's own entry (hidden): sets the connection the service commits through, then onStartInput
        InputMethodService::class.java
            .getDeclaredMethod("doStartInput", InputConnection::class.java, EditorInfo::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(service, ic, info, false)
        service.onStartInputView(info, false)
        // the window put on the screen: views attached (what a chip, a popup checks), the input area shown
        runCatching { service.showWindow(true) }.onFailure { System.err.println("showWindow: $it") }
        idle()
        relayout()
        return this
    }

    /** An engine event, as fcitx would send it, then everything it set off. */
    fun emit(event: FcitxEvent<*>) {
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

    /** The key showing [label] (its main text), in whatever keyboard or panel is up. */
    fun key(label: String): TextKeyView = all(inputView).filterIsInstance<TextKeyView>()
        .firstOrNull { shown(it) && it.mainText.text.toString() == label }
        ?: error("no key $label in ${texts()}")

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

    fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        selectionDelivered()
    }

    private var delivered = intArrayOf(-1, -1, -1, -1)

    // what the system's input method manager does as the field's selection or composing text
    // changes: Robolectric's tells the service nothing, whose tracking of the cursor would lag
    private fun selectionDelivered() {
        if (!::field.isInitialized) return
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
    fun press(v: View) {
        val b = box(v)
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
    fun after(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    /** A finger put on [v]'s middle, slid by [dx], [dy] px in a few steps, lifted. */
    fun swipe(v: View, dx: Float, dy: Float) {
        val b = box(v)
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

    /** The popup's keys up now (a long press's choices), not the keyboard's own labels. */
    fun popupKeys(): List<TextView> = all(inputView).filterIsInstance<AutoScaleTextView>()
        .filter { shown(it) && generateSequence(it as View) { p -> p.parent as? View }.none { p -> p is KeyView } }
        .toList()

    /**
     * A finger held on [v] past the long press, then slid onto the popup's key showing [pick]
     * (left where it is when null) and lifted. Returns the popup keys' labels as shown.
     */
    fun longPress(v: View, pick: String? = null): List<String> {
        val b = box(v)
        var x = b.exactCenterX()
        var y = b.exactCenterY()
        finger(MotionEvent.ACTION_DOWN, x, y)
        after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        val popup = popupKeys()
        val labels = popup.map { it.text.toString() }
        if (pick != null) {
            val t = box(popup.firstOrNull { it.text.toString() == pick } ?: error("no $pick in the popup: $labels"))
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
    fun drag(v: View, toX: Float, toY: Float, holdMs: Long = 0, steps: Int = 12, lift: Boolean = true) {
        val b = box(v)
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
    fun waitFor(what: String, ok: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!ok()) {
            check(System.currentTimeMillis() < deadline) { "never: $what; shown ${texts(inputView)}" }
            Thread.sleep(20)
            idle()
            relayout()
        }
    }

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
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        relayout()
    }

    /**
     * A finger on [v]: down at the first point, then each next one [Touch.ms] after the last, up at
     * the last. Points are in [v]'s own pixels, so a slide off the key is a point outside its size.
     */
    fun touch(v: View, vararg path: Touch) {
        val f = finger(v, path.first())
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
    fun finger(v: View, at: Touch): Finger = Finger(v).also { it.send(MotionEvent.ACTION_DOWN, at) }

    inner class Finger(private val v: View) {
        private val down = SystemClock.uptimeMillis()

        /** [eventTime]: the event's own, earlier than now to tell it from the time it is handled */
        internal fun send(action: Int, p: Touch, eventTime: Long = SystemClock.uptimeMillis()) {
            val from = IntArray(2).also(v::getLocationInWindow)
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
    fun at(v: View, dx: Float = 0f, dy: Float = 0f, ms: Long = 0): Touch {
        val dp = service.resources.displayMetrics.density
        return Touch(v.width / 2f + dx * dp, v.height / 2f + dy * dp, ms)
    }

    /** The nine keys' key for [digit] (its small digit), or the column's punctuation key. */
    fun nineKey(digit: String): AltTextKeyView =
        all(inputView).filterIsInstance<AltTextKeyView>()
            .firstOrNull { shown(it) && it.altText.text.toString() == digit } ?: error("no key $digit in ${texts()}")

    /** Every text shown under [v] (visible ones only), depth first. */
    fun texts(v: View = keyboard): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is TextView -> listOf(v.text.toString())
        v is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    // drawn by hand: Roborazzi's View capture wants an Activity, and the service's window is none
    fun capture(path: String, view: View = keyboard) {
        settled()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.captureRoboImage(
            "src/test/screenshots/$path.png",
            RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)),
        )
    }

    // a keyboard shown slides in, a panel fades: the clock run on past them so every image is the end state
    private fun settled() {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        relayout()
    }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        // the clipboard's database outlives the test, kept open by the app's object: emptied for the next
        runBlocking {
            val cm = org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
            cm.purgeMarked(*cm.deleteAll(skipPinned = false))
        }
        controller.destroy()
        FcitxDaemon.testConnection = null
        scope.cancel()
    }

    // the daemon's connection to a real host, which answers on its own thread
    private class HostConnection(override val lifecycleScope: CoroutineScope, val host: FcitxAPI) : FcitxConnection {
        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = runBlocking { block(host) }
        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = block(host)
        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) {
            if (host.isReady) lifecycleScope.launch { block(host) }
        }
    }

    companion object {
        val PINYIN = InputMethodEntry(Engines.PINYIN, "拼音", "", "", "拼", "zh_CN", "androidengine", false)
        val ENGLISH = InputMethodEntry(Engines.ENGLISH, "English", "", "", "En", "en", "androidengine", false)
        val T9 = InputMethodEntry(Engines.T9, "九键", "", "", "九", "zh_CN", "androidengine", false)

        inline fun <reified T : View> find(v: View): T? = all(v).firstNotNullOfOrNull { it as? T }

        /** [v] and every view under it, depth first. */
        fun all(v: View): Sequence<View> = sequence {
            yield(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(all(v.getChildAt(i)))
        }
    }
}
