/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Shader
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.keyboard.LetterCaseChoice
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.Keys
import org.fcitx.fcitx5.android.input.theme.Picture
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.util.Locale

/**
 * How fast the keyboard is drawn now, measured on a device so that its redraw (design 060: one View a
 * keyboard, a RenderNode a key) can be held to no worse: each keyboard made and first drawn, a theme
 * changed, a key's press shown, the process's memory with the keyboard up. Results go to logcat,
 * tag [TAG], a line a number; how to read them and the numbers so far: dev/design/060-render-perf.md.
 *
 * Measured through what a user does, so that the same test runs on the new drawing unchanged: a
 * keyboard picked in ⋯ (the setting, which makes the input view anew), keys tapped as the system
 * delivers touches, a theme picked as its list does. What it reads of the views is the window
 * ([KeyboardFrames]), the keyboard's frame (`keyboard_view`) and, in [shows], which keyboard is in
 * it: the one place to change when the keyboards are drawn by other classes.
 *
 * Not a check run with the rest: it takes minutes and asserts nothing about speed. Run alone, with
 * `-Pandroid.testInstrumentationRunnerArguments.class=…KeyboardPerfBaselineTest` (perfRuns sets the
 * samples, 20 by default). Puts back what it changes: the device's keyboard, the input methods, the
 * settings and themes; not the screen it wakes, nor perfScreenshot's file.
 */
class KeyboardPerfBaselineTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(1_800)

    private lateinit var scenario: ActivityScenario<TypingHostActivity>
    private lateinit var service: FcitxInputMethodService
    private lateinit var frames: KeyboardFrames

    private val runs = InstrumentationRegistry.getArguments().getString("perfRuns")?.toInt() ?: 20
    private val warmUp = 3
    private val only = InstrumentationRegistry.getArguments().getString("perfOnly")?.split(',')

    // perfScreenshot=true: the picture theme's keyboard captured once, to see the picture is drawn
    private val screenshots = InstrumentationRegistry.getArguments().getString("perfScreenshot") == "true"

    // put back after the test, each once changed
    private var deviceIme: String? = null
    private var deviceImeEnabled = false
    private var spellChecker: String? = null
    private var imsWere: Array<String>? = null
    private var imWas: String? = null
    private var pinyinKeyboardWas: PinyinKeyboard? = null
    private var followSystemWas: Boolean? = null
    private var themeWas: Theme? = null
    private val themesMade = mutableListOf<String>()

    @Test
    fun keyboardDrawingBaseline() {
        val sdk = android.os.Build.VERSION.SDK_INT
        log("device ${android.os.Build.MODEL} sdk $sdk runs $runs warm-up $warmUp")
        selectThisInputMethod()
        if (only != null) {
            // perfOnly=theme,shift: those parts alone, for a trace short enough to read
            launch()
            if ("theme" in only) switchThemes()
            if ("shift" in only) sceneCosts()
            return
        }
        memory("no keyboard shown yet")
        launch()
        memory("26 keys shown, first time")
        hideKeyboard()
        memory("26 keys made, hidden")
        showKeyboard()
        memory("26 keys shown again")
        buildEachKeyboard()
        switchThemes()
        pressFeedback()
        sceneCosts()
        rebuilds()
        shiftWithLowerFaces()
        popupSlide()
        hideKeyboard()
        memory("after the runs (handwriting's models loaded), hidden")
        showKeyboard()
        memory("after the runs (handwriting's models loaded), shown")
    }

    // ---- the device

    private fun selectThisInputMethod() {
        deviceIme = shell("settings get secure default_input_method").takeIf { it.isNotEmpty() && it != "null" }
        val self = ComponentName.unflattenFromString(imeId)
        deviceImeEnabled = shell("settings get secure enabled_input_methods").split(':')
            .any { ComponentName.unflattenFromString(it.substringBefore(';')) == self }
        // the system's spell checker would underline what the runs type, a frame of its own in the field
        spellChecker = shell("settings get secure spell_checker_enabled")
        shell("settings put secure spell_checker_enabled 0")
        shell("ime enable $imeId")
        shell("ime set $imeId")
    }

    private fun launch() {
        // a phone left locked: the field over the lock screen (this activity only), the keyboard with it
        shell("input keyevent KEYCODE_WAKEUP")
        scenario = ActivityScenario.launch(Intent(context, TypingHostActivity::class.java))
        scenario.onActivity {
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
        }
        showKeyboard()
        // pinyin on the 26 keys first; handwriting enabled for its pad
        AppPrefs.getInstance().keyboard.pinyinKeyboard.let { pref ->
            pinyinKeyboardWas = pref.getValue()
            if (pref.getValue() != PinyinKeyboard.Full) onMain { pref.setValue(PinyinKeyboard.Full) }
        }
        host {
            imsWere = enabledIme().map { it.uniqueName }.toTypedArray()
            imWas = currentIme().uniqueName
            val wanted = listOf(PINYIN, HANDWRITING).filter { im -> enabledIme().none { it.uniqueName == im } }
            if (wanted.isNotEmpty()) setEnabledIme(imsWere!! + wanted)
            activateIme(PINYIN)
        }
        find(onKeyboard().text("123"))
        onMain {
            service = checkNotNull(findInputMethodService()) { "the input method's window is not in this process" }
            frames = KeyboardFrames(service.window.window!!)
        }
        settle()
    }

    @After
    fun putBack() {
        runCatching { if (::frames.isInitialized) onMain { frames.close() } }
        runCatching { putThemesBack() }
        pinyinKeyboardWas?.let { onMain { AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(it) } }
        runCatching {
            host {
                imsWere?.let { setEnabledIme(it) }
                imWas?.let { activateIme(it) }
            }
        }
        if (::scenario.isInitialized) scenario.close()
        deviceIme.let { if (it == null) shell("ime reset") else shell("ime set $it") }
        if (!deviceImeEnabled) shell("ime disable $imeId")
        spellChecker.let {
            if (it.isNullOrEmpty() || it == "null") shell("settings delete secure spell_checker_enabled")
            else shell("settings put secure spell_checker_enabled $it")
        }
    }

    // ---- 1. each keyboard made and first drawn

    private enum class Board(val label: String, val pinyin: PinyinKeyboard?) {
        FULL("26 keys (pinyin)", PinyinKeyboard.Full),
        NINE("9 keys", PinyinKeyboard.NineKeys),
        KEYS14("14 keys", PinyinKeyboard.Keys14),
        KEYS18("18 keys", PinyinKeyboard.Keys18),
        ZHUYIN("zhuyin (大千)", PinyinKeyboard.ZhuyinFull),
        ZHUYIN_NINE("zhuyin 9 keys", PinyinKeyboard.ZhuyinNine),
        NUMBER("number", null),
        SYMBOLS("symbols", null),
        HANDWRITING("handwriting", null),
    }

    private val built = linkedMapOf<Board, MutableList<KeyboardFrames.Sample>>()
    private val viewsIn = linkedMapOf<Board, Pair<Int, Int>>()

    /**
     * A keyboard picked in ⋯ is a setting, and the input view is made anew with it (as on a theme
     * change): counted from the setting to its first frame. The number and symbol keyboards and the
     * handwriting pad are made the first time a fresh input view shows them: the 26 keys made anew
     * (via 14 keys, a setting each), then counted from the tap's lift, or from handwriting asked for.
     */
    private fun buildEachKeyboard() {
        val picked = Board.entries.filter { it.pinyin != null && it != Board.FULL }
        repeat(warmUp + runs) {
            // from the 26 keys and back: the 26 keys' samples are the way back from each
            for (board in picked) {
                record(board, pick(board))
                record(Board.FULL, pick(Board.FULL))
            }
        }
        val number = keyAt(onKeyboard().text("123"))
        val symbols = keyAt(onKeyboard().text("符"))
        val handwriting = host { availableIme().any { it.uniqueName == HANDWRITING } }
        repeat(warmUp + runs) {
            fresh()
            record(Board.NUMBER, tapTo(number, Board.NUMBER))
            fresh()
            record(Board.SYMBOLS, tapTo(symbols, Board.SYMBOLS))
            if (handwriting) {
                fresh()
                record(Board.HANDWRITING, switchTo(HANDWRITING, Board.HANDWRITING))
                switchTo(PINYIN, Board.FULL)
            }
        }
        if (!handwriting) log("handwriting\tnot in this build's input methods")
        fresh()
        for ((board, samples) in built) {
            val kept = samples.drop(warmUp)
            val (area, window) = viewsIn[board] ?: (-1 to -1)
            row("build", board.label, kept, "views area $area window $window")
        }
    }

    private fun record(board: Board, sample: KeyboardFrames.Sample) {
        built.getOrPut(board) { mutableListOf() } += sample
        if (board !in viewsIn) viewsIn[board] = onMain { viewCount(keyboardArea()) to viewCount(service.window.window!!.decorView) }
    }

    // a fresh input view on the 26 keys: none of the others made yet
    private fun fresh() {
        pick(Board.KEYS14)
        pick(Board.FULL)
    }

    private fun pick(board: Board): KeyboardFrames.Sample {
        val before = onMain { keyboardFrame() }
        return measure({ keyboardFrame().let { it !== before && shows(it, board) } }) {
            AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(board.pinyin!!)
        }
    }

    private fun tapTo(key: PointF, board: Board): KeyboardFrames.Sample {
        // the area as it is before the tap: the symbols take the keyboard's frame away
        onMain { keyboardArea() }
        val mark = onMain { frames.mark(0) { shows(keyboardFrame(), board) } }
        val heap = Heap.now()
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, key, down, down)
        SystemClock.sleep(HOLD_MS)
        val up = SystemClock.uptimeMillis()
        mark.from = up * DownMarks.NS_PER_MS
        inject(MotionEvent.ACTION_UP, key, down, up)
        return frames.await(mark).also { settle() }.heap(Heap.now() - heap)
    }

    private fun switchTo(im: String, board: Board): KeyboardFrames.Sample {
        val mark = onMain { frames.mark(0) { shows(keyboardFrame(), board) } }
        val heap = Heap.now()
        mark.from = System.nanoTime()
        host { activateIme(im) }
        return frames.await(mark).also { settle() }.heap(Heap.now() - heap)
    }

    /**
     * Whether [frame] shows [board]: the keyboard in it by its class; the symbols, a window of their own,
     * as the keyboard's frame gone. The one part to change for the new drawing, if its keyboards are
     * other classes.
     */
    private fun shows(frame: ViewGroup?, board: Board): Boolean {
        if (board == Board.SYMBOLS) return frame == null || !frame.isShown
        if (frame == null || !frame.isShown || frame.childCount == 0) return false
        val shown = frame.getChildAt(0).javaClass.simpleName
        return shown == when (board) {
            Board.FULL -> "TextKeyboard"
            Board.NINE -> "T9Keyboard"
            Board.KEYS14, Board.KEYS18 -> "GroupedKeyboard"
            Board.ZHUYIN, Board.ZHUYIN_NINE -> "ZhuyinKeyboard"
            Board.NUMBER -> "NumberKeyboard"
            Board.HANDWRITING -> "HandwritingKeyboard"
            Board.SYMBOLS -> error("above")
        }
    }

    private fun keyboardFrame(): ViewGroup? = service.window.window!!.decorView.findViewById(R.id.keyboard_view)

    // what holds the keyboard's frame and the pickers (InputWindowManager's view): the keyboard area
    private var area: View? = null
    private fun keyboardArea(): View {
        keyboardFrame()?.parent?.let { area = it as View }
        return area!!
    }

    // ---- 2. a theme picked

    /**
     * Themes picked as the theme list picks them (followed by no day and night setting), each made anew
     * with the input view: a block theme (a built-in's look) and a flat one, and a flat one over a
     * picture. No built-in has a picture, so the two flat ones are made here and deleted after. The
     * first frame of the new view is counted; a picture is decoded off the main thread
     * (ThemeAssets.prefetch) and may come a frame or more later.
     */
    private fun switchThemes() {
        val prefs = ThemeManager.prefs
        followSystemWas = prefs.followSystemDayNightTheme.getValue()
        themeWas = prefs.normalModeTheme.getValue()
        log("theme\tkey border setting ${prefs.keyBorderChoice.getValue()} (FollowTheme: each theme's own)")
        val block = ThemePreset.PixelLight
        val flat = Theme.Custom(FLAT, isDark = false, look = block.look.copy(keys = block.look.keys.copy(blocks = false)))
        val pictured = Theme.Custom(PICTURED, isDark = false, look = flat.look.copy(picture = picture(), base = BoardBase.Picture))
        val saved = onMain {
            prefs.followSystemDayNightTheme.setValue(false)
            listOf(flat, pictured).all { ThemeManager.saveTheme(it).also { ok -> if (ok) themesMade += it.name } }
        }
        assumeTrue("themes could not be saved", saved)
        if (ThemeManager.activeTheme.name != block.name) theme(block)
        log("theme\tblocks: ${block.name} ${ThemeManager.shownLook(block).keys.blocks}, $FLAT ${ThemeManager.shownLook(flat).keys.blocks}")
        if (screenshots) {
            theme(pictured)
            SystemClock.sleep(SCREENSHOT_WAIT_MS)
            shell("screencap -p $SCREENSHOT")
            log("theme\tthe picture theme on screen: $SCREENSHOT")
            theme(block)
        }
        val samples = linkedMapOf<String, MutableList<KeyboardFrames.Sample>>()
        repeat(warmUp + runs) {
            for ((label, to) in listOf("block → flat" to flat, "flat → block" to block, "block → flat+picture" to pictured, "flat+picture → block" to block)) {
                samples.getOrPut(label) { mutableListOf() } += theme(to)
            }
        }
        samples.forEach { (label, s) -> row("theme", label, s.drop(warmUp)) }
        row("theme", "every switch above", samples.values.flatMap { it.drop(warmUp) })
        // a theme not seen before: a flat one with corners of its own each run, saved before it is timed
        val unseen = mutableListOf<KeyboardFrames.Sample>()
        repeat(warmUp + runs) { i ->
            val radius = (flat.look.keys.radiusDp + 1 + i) % (Keys.MAX_RADIUS_DP + 1)
            val new = Theme.Custom("$UNSEEN-$i", isDark = false, look = flat.look.copy(keys = flat.look.keys.copy(radiusDp = radius)))
            assumeTrue("a theme could not be saved", onMain { ThemeManager.saveTheme(new) })
            themesMade += new.name
            settle()
            val sample = theme(new)
            if (i >= warmUp) unseen += sample
            theme(block)
        }
        row("theme", "block → a flat one not seen before", unseen)
    }

    private fun theme(to: Theme): KeyboardFrames.Sample {
        val before = onMain { keyboardFrame() }
        // the 26 keys in the new frame, as a build's: the frame shown alone, its keyboard not yet in it, is not the switch done
        return measure({ keyboardFrame().let { it !== before && shows(it, Board.FULL) } }) { ThemeManager.setNormalModeTheme(to) }
    }

    // a picture as wide as a phone's keyboard, as a theme's cut is: a gradient and some shapes
    private fun picture(): Picture {
        val file = File(ThemeFilesManager.themeDir, "$PICTURED.png")
        val bitmap = Bitmap.createBitmap(PICTURE_W, PICTURE_H, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, PICTURE_W.toFloat(), PICTURE_H.toFloat(), 0xff3a6ea5.toInt(), 0xfff2c14e.toInt(), Shader.TileMode.CLAMP) })
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55ffffff }
            for (i in 0 until 40) drawCircle((i * 97 % PICTURE_W).toFloat(), (i * 61 % PICTURE_H).toFloat(), 20f + i % 7 * 9, dot)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Picture(file.path, Crop(file.path, null, 0), null, brightness = 100, blurDp = 0)
    }

    private fun putThemesBack() {
        onMain {
            themeWas?.let { ThemeManager.prefs.normalModeTheme.setValue(it) }
            followSystemWas?.let { ThemeManager.prefs.followSystemDayNightTheme.setValue(it) }
            themesMade.forEach { ThemeManager.deleteTheme(it) }
        }
    }

    // ---- 3. a key's press shown

    /**
     * Keys pressed on the 26 keys as the system delivers touches: a single key (a letter, lifted after
     * 80 ms, then Space and the field emptied), and ten letters in a row at 80 ms a key. Each down is
     * counted from its event's time to the window's next frame done ([DownMarks]).
     */
    private fun pressFeedback() {
        fresh()
        val letters = "nihaoshiji"
        val at = (letters.toSet() + 'a').associateWith { keyAt(onKeyboard().text("$it")) }
        val space = keyAt(onKeyboard().res(imePkg, "button_space"))
        val marks = onMain { DownMarks(service.window.window!!.callback, frames).also { service.window.window!!.callback = it } }
        try {
            val single = mutableListOf<KeyboardFrames.Sample>()
            val run = mutableListOf<KeyboardFrames.Sample>()
            val runWorst = mutableListOf<Double>()
            repeat(warmUp + runs) { i ->
                val one = presses(marks, listOf(at.getValue('a')), gapMs = HOLD_MS * 2)
                endTyping(space)
                val ten = presses(marks, letters.map(at::getValue), gapMs = FAST_GAP_MS)
                endTyping(space)
                if (i >= warmUp) {
                    single += one
                    run += ten
                    ten.maxOfOrNull { it.latency }?.let { runWorst += it }
                }
            }
            row("press", "single key", single)
            row("press", "10-key run, every key", run)
            log("press\t10-key run, slowest key of a run\t${Stats(runWorst)}")
            // a field that sends (a chat's): Return's label changes with the first key and the commit
            // (发送, then 换行 while composing), the scene made again for each, where a plain field keeps it
            fieldAction(EditorInfo.IME_ACTION_SEND)
            val sent = mutableListOf<KeyboardFrames.Sample>()
            repeat(warmUp + runs) { i ->
                val ten = presses(marks, letters.map(at::getValue), gapMs = FAST_GAP_MS)
                endTyping(space)
                if (i >= warmUp) sent += ten
            }
            row("press", "10-key run in a send field, every key", sent)
        } finally {
            runCatching { fieldAction(null) }
            onMain { service.window.window!!.callback = marks.original }
        }
    }

    // the field single-line with [action] (null: back to the plain field), the input method told anew
    private fun fieldAction(action: Int?) {
        scenario.onActivity {
            it.field.isSingleLine = action != null
            it.field.imeOptions = action ?: EditorInfo.IME_ACTION_UNSPECIFIED
            context.inputMethodManager.restartInput(it.field)
        }
        settle()
    }

    private fun presses(marks: DownMarks, keys: List<PointF>, gapMs: Long): List<KeyboardFrames.Sample> {
        marks.marks.clear()
        marks.recording = true
        val heap = Heap.now()
        for (key in keys) {
            val down = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, key, down, down)
            SystemClock.sleep(gapMs / 2)
            inject(MotionEvent.ACTION_UP, key, down, SystemClock.uptimeMillis())
            SystemClock.sleep(gapMs / 2)
        }
        marks.recording = false
        // a down with no frame after it (nothing drawn for the press) is told, not counted
        val drawn = marks.marks.mapNotNull { runCatching { frames.await(it) }.getOrNull() }
        if (drawn.size != keys.size) log("press\t${keys.size - drawn.size} of ${keys.size} downs had no frame")
        settle()
        val step = Heap.now() - heap
        return drawn.onEach { it.heap(step, keys.size) }
    }

    // what was typed committed and the field emptied, for the next run to start alike
    private fun endTyping(space: PointF) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, space, down, down)
        inject(MotionEvent.ACTION_UP, space, down, SystemClock.uptimeMillis())
        settle()
        scenario.onActivity { it.field.setText("") }
        settle()
    }

    // ---- 3b. what the keys show changed: Shift

    /**
     * Shift tapped on the 26 keys (each letter shown anew: on the scene engine, the scene made again
     * as the frame draws), counted from the lift to the frame showing Shift's state, Shift tapped back
     * after; drawn by the scene engine, also what making the scene costs alone, on the main thread:
     * made anew as things are, and asked again with nothing changed (the first key typed in pinyin).
     */
    private fun sceneCosts() {
        fresh()
        val shift = keyAt(onKeyboard().res(imePkg, "button_caps"))
        val taps = mutableListOf<KeyboardFrames.Sample>()
        repeat(warmUp + runs) { i ->
            // on and back off, each a scene of its own; apart, so no tap is taken for a double tap's lock
            repeat(2) {
                val sample = shiftTap(shift)
                if (i >= warmUp) taps += sample
                SystemClock.sleep(SHIFT_APART_MS)
            }
        }
        row("shift", "tap, on or off", taps)
        val view = onMain { canvasView() } ?: return log("scene	no canvas")
        val board = onMain { textKeyboard() }
        fun timed(prepare: () -> Unit): List<Double> = (1..warmUp + runs).map {
            onMain {
                prepare()
                val start = System.nanoTime()
                checkNotNull(view.scene)
                (System.nanoTime() - start) / NANOS_A_MS
            }
        }.drop(warmUp)
        fun stats(ms: List<Double>) = Stats(ms).let { "%.2f / %.2f".format(Locale.ROOT, it.median, it.p90) }
        log("scene\tmade anew (Shift's, a theme's)\t${stats(timed(board::forgetScene))}")
        log("scene\tasked again, nothing changed (pinyin's first key)\t${stats(timed(view::invalidateScene))}")
    }

    private fun shiftTap(at: PointF): KeyboardFrames.Sample {
        // Shift's state is the canvas's now (no button_caps view to ask): its scene made anew is the tap shown
        val scene = onMain { canvasView()?.scene }
        val mark = onMain { frames.mark(0) { canvasView()?.scene.let { it != null && it !== scene } } }
        val heap = Heap.now()
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, at, down, down)
        SystemClock.sleep(HOLD_MS)
        val up = SystemClock.uptimeMillis()
        mark.from = up * DownMarks.NS_PER_MS
        inject(MotionEvent.ACTION_UP, at, down, up)
        return frames.await(mark).also { settle() }.heap(Heap.now() - heap)
    }

    private fun canvasView(): KeyboardCanvasView? = keyboardFrame()?.let(::all)?.filterIsInstance<KeyboardCanvasView>()?.firstOrNull()

    private fun textKeyboard(): TextKeyboard = keyboardFrame()!!.let(::all).filterIsInstance<TextKeyboard>().first()

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    // ---- 3c. the keyboard made again for a new size, Shift with faces that do not follow it, a popup's focus moved

    /**
     * The keyboard's height setting changed (30% and 35% in turn: the input view laid out anew at a new
     * size) and the screen turned (portrait and landscape in turn: the input view made anew), each
     * counted from the change to the first frame at the new size.
     */
    private fun rebuilds() {
        fresh()
        val height = AppPrefs.getInstance().keyboard.keyboardHeightPercent
        val heightWas = onMain { height.getValue() }
        val resized = mutableListOf<KeyboardFrames.Sample>()
        try {
            repeat(warmUp + runs) { i ->
                for (to in listOf(heightWas + 5, heightWas)) {
                    val before = onMain { keyboardArea().height }
                    val sample = measure({ keyboardFrame()?.let { shows(it, Board.FULL) && keyboardArea().height != before } == true }) { height.setValue(to) }
                    if (i >= warmUp) resized += sample
                }
            }
        } finally {
            onMain { height.setValue(heightWas) }
        }
        row("rebuild", "height setting changed", resized)
        val autoWas = shell("settings get system accelerometer_rotation")
        val rotationWas = shell("settings get system user_rotation")
        val turned = mutableListOf<KeyboardFrames.Sample>()
        try {
            shell("settings put system accelerometer_rotation 0")
            repeat(warmUp + runs) { i ->
                for (to in listOf(1, 0)) {
                    val before = onMain { keyboardFrame() }
                    val beforeWidth = onMain { before?.width }
                    val mark = onMain { frames.mark(0) { keyboardFrame().let { it != null && shows(it, Board.FULL) && it.width != beforeWidth } } }
                    val heap = Heap.now()
                    mark.from = System.nanoTime()
                    shell("settings put system user_rotation $to")
                    val sample = runCatching { frames.await(mark).also { settle() }.heap(Heap.now() - heap) }.getOrNull()
                    if (sample == null) log("rebuild\tturned to $to: no frame (the window made anew?)")
                    else if (i >= warmUp) turned += sample
                    SystemClock.sleep(ROTATE_SETTLE_MS)
                }
            }
        } finally {
            shell("settings put system user_rotation ${rotationWas.toIntOrNull() ?: 0}")
            if (autoWas == "1") shell("settings put system accelerometer_rotation 1")
            settle()
        }
        if (turned.isNotEmpty()) row("rebuild", "screen turned", turned)
    }

    /** Shift tapped as in [sceneCosts], with the letters' faces lower case whatever Shift is (the faces left as they are). */
    private fun shiftWithLowerFaces() {
        val case = AppPrefs.getInstance().keyboard.letterCase
        val caseWas = onMain { case.getValue() }
        log("shift\tletter case setting $caseWas (Shift: the faces follow it)")
        try {
            onMain { case.setValue(LetterCaseChoice.Lower) }
            fresh()
            val shift = keyAt(onKeyboard().res(imePkg, "button_caps"))
            val taps = mutableListOf<KeyboardFrames.Sample>()
            repeat(warmUp + runs) { i ->
                repeat(2) {
                    val sample = shiftTap(shift)
                    if (i >= warmUp) taps += sample
                    SystemClock.sleep(SHIFT_APART_MS)
                }
            }
            row("shift", "tap, letter faces lower case always", taps)
        } finally {
            onMain { case.setValue(caseWas) }
            fresh()
        }
    }

    /**
     * E held for its popup (13 choices), then the finger moved over it a choice at a time, right and
     * back: each move counted from its event to the frame after it, then lifted on the choice and the
     * field emptied. A move that drew no frame (the focus not moved) is told, not counted.
     */
    private fun popupSlide() {
        fresh()
        val e = keyAt(onKeyboard().text("e"))
        val r = keyAt(onKeyboard().text("r"))
        val step = (r.x - e.x) * POPUP_STEP
        val space = keyAt(onKeyboard().res(imePkg, "button_space"))
        val moved = mutableListOf<KeyboardFrames.Sample>()
        var undrawn = 0
        repeat(warmUp + runs) { i ->
            val down = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, e, down, down)
            SystemClock.sleep(LONG_PRESS_MS)
            settle()
            val path = (1..POPUP_MOVES) + (POPUP_MOVES - 1 downTo 0)
            val samples = mutableListOf<KeyboardFrames.Sample>()
            val heap = Heap.now()
            for (n in path) {
                val at = PointF(e.x + step * n, e.y)
                val mark = onMain { frames.mark(0) }
                val time = SystemClock.uptimeMillis()
                mark.from = time * DownMarks.NS_PER_MS
                inject(MotionEvent.ACTION_MOVE, at, down, time)
                val sample = runCatching { frames.await(mark, POPUP_FRAME_WAIT_MS) }.getOrNull()
                if (sample == null) undrawn++ else samples += sample
                SystemClock.sleep(POPUP_GAP_MS)
            }
            val alloc = Heap.now() - heap
            samples.forEach { it.heap(alloc, samples.size) }
            inject(MotionEvent.ACTION_UP, e, down, SystemClock.uptimeMillis())
            settle()
            endTyping(space)
            if (i >= warmUp) moved += samples
        }
        if (undrawn > 0) log("popup\t$undrawn moves drew no frame")
        row("popup", "focus moved a choice", moved)
    }

    // ---- 4. memory

    private fun memory(label: String) {
        repeat(2) {
            Runtime.getRuntime().gc()
            SystemClock.sleep(GC_WAIT_MS)
        }
        val samples = (1..MEMORY_SAMPLES).map {
            SystemClock.sleep(GC_WAIT_MS)
            Memory.parse(shell("dumpsys meminfo ${Process.myPid()}"))
        }
        fun median(of: (Memory) -> Int) = Stats(samples.map { of(it).toDouble() }).median.toInt()
        log("memory\t$label\tjava ${median { it.javaHeap }} kB\tnative ${median { it.nativeHeap }} kB\tgraphics ${median { it.graphics }} kB\ttotal pss ${median { it.totalPss }} kB")
    }

    private fun hideKeyboard() {
        scenario.onActivity { context.inputMethodManager.hideSoftInputFromWindow(it.field.windowToken, 0) }
        device.wait(Until.gone(By.pkg(imePkg).res(imePkg, "keyboard_view")), 5_000)
        settle()
    }

    private fun showKeyboard() {
        scenario.onActivity {
            it.field.requestFocus()
            context.inputMethodManager.showSoftInput(it.field, 0)
        }
        find(By.pkg(imePkg).res(imePkg, "keyboard_view"))
        settle()
    }

    // ---- the parts

    /** Counts [action] (main thread) to the first frame where [ready] holds. */
    private fun measure(ready: () -> Boolean, action: () -> Unit): KeyboardFrames.Sample {
        val heap = Heap.now()
        val mark = onMain {
            val from = System.nanoTime()
            frames.mark(from, ready).also { action() }
        }
        return frames.await(mark).also { settle() }.heap(Heap.now() - heap)
    }

    private fun inject(action: Int, at: PointF, down: Long, time: Long) {
        val event = MotionEvent.obtain(down, time, action, at.x, at.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "not injected" }
        event.recycle()
    }

    // the main thread idle, and a little more for what is posted after a frame (the bar, a11y)
    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(SETTLE_MS)
    }

    private fun onKeyboard() = By.pkg(imePkg).hasAncestor(By.res(imePkg, "keyboard_view"))

    private fun find(selector: BySelector) = device.wait(Until.findObject(selector), 10_000) ?: error("not on screen: $selector")

    // a key's centre on the screen, found as TalkBack and the UAT find it
    private fun keyAt(selector: BySelector) = find(selector).visibleBounds.let { PointF(it.exactCenterX(), it.exactCenterY()) }

    private fun row(what: String, label: String, samples: List<KeyboardFrames.Sample>, extra: String = "") {
        fun stats(of: (KeyboardFrames.Sample) -> Double?) = Stats(samples.mapNotNull(of))
        val withMetrics = samples.count { it.toDone != null }
        // a press's event to the window, and from there: the system's part and the app's
        val dispatch = if (samples.any { it.dispatch != null }) "\tdispatch ${stats { it.dispatch }}\tdispatch to done ${stats { it.fromDispatch }}" else ""
        log(
            "$what\t$label\tn ${samples.size} (metrics $withMetrics)\tto frame done ${stats { it.latency }}" +
                "\tto commit ${stats { it.toCommit }}\tmain before draw ${stats { it.toPreDraw }}$dispatch" +
                "\tframe total ${stats { it.frameTotal }}\tlayout ${stats { it.layoutMeasure }}\tdraw ${stats { it.draw }}" +
                "\tmean ${"%.1f".format(Locale.ROOT, stats { it.fromDispatch ?: it.latency }.mean)}" +
                "\tallocated ${"%.0f".format(Locale.ROOT, stats { it.allocated }.mean / KIB)} KiB\tgcs ${"%.2f".format(Locale.ROOT, stats { it.gcs }.mean)}\t$extra",
        )
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private companion object {
        const val TAG = "KeyboardPerf"
        const val PINYIN = InputMethodCatalog.PINYIN
        const val HANDWRITING = InputMethodCatalog.HANDWRITING
        const val FLAT = "perf-baseline-flat"
        const val PICTURED = "perf-baseline-picture"
        const val UNSEEN = "perf-baseline-new"
        const val PICTURE_W = 1080
        const val PICTURE_H = 900
        const val HOLD_MS = 80L
        const val SHIFT_APART_MS = 400L
        const val ROTATE_SETTLE_MS = 1_000L
        const val LONG_PRESS_MS = 600L
        const val POPUP_STEP = 1f
        const val POPUP_MOVES = 5
        const val POPUP_GAP_MS = 60L
        const val POPUP_FRAME_WAIT_MS = 300L
        const val NANOS_A_MS = 1e6
        const val KIB = 1024.0
        const val FAST_GAP_MS = 80L
        const val SETTLE_MS = 150L
        const val GC_WAIT_MS = 500L
        const val MEMORY_SAMPLES = 3
        const val SCREENSHOT_WAIT_MS = 1_000L
        const val SCREENSHOT = "/data/local/tmp/keyboard-perf-picture.png"

        val instrumentation = InstrumentationRegistry.getInstrumentation()!!
        val context = instrumentation.targetContext!!
        val device: UiDevice = UiDevice.getInstance(instrumentation)
        val imePkg: String = context.packageName
        val imeId = "$imePkg/${FcitxInputMethodService::class.java.name}"

        fun shell(command: String): String = device.executeShellCommand(command).trim()

        fun <T> host(block: suspend HostApi.() -> T): T = try {
            runBlocking { withTimeout(30_000) { HostDaemon.connect(TAG).runOnReady(block) } }
        } finally {
            HostDaemon.disconnect(TAG)
        }
    }
}
