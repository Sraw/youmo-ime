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
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.keyboard.LetterCaseChoice
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.scene.look.BoardBase
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.util.Locale

/**
 * On-device drawing baseline: keyboards made and first drawn, theme switches, key presses, memory. Results go to
 * logcat under [TAG]; how to read them: dev/design/060-render-perf.md. Driven only through what a user does, so it
 * runs unchanged on another renderer ([shows] is the one place that knows the keyboard's classes).
 *
 * Not a regular check: run alone with `-Pandroid.testInstrumentationRunnerArguments.class=…KeyboardPerfBaselineTest`
 * (perfRuns sets the samples). Puts back the device's keyboard, input methods, settings and themes.
 */
class KeyboardPerfBaselineTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(1_800)

    private lateinit var scenario: ActivityScenario<TypingHostActivity>
    internal lateinit var service: FcitxInputMethodService
    internal lateinit var frames: KeyboardFrames

    internal val runs = InstrumentationRegistry.getArguments().getString("perfRuns")?.toInt() ?: 20
    internal val warmUp = 3
    private val only = InstrumentationRegistry.getArguments().getString("perfOnly")?.split(',')

    private val screenshots = InstrumentationRegistry.getArguments().getString("perfScreenshot") == "true"

    private var deviceIme: String? = null
    private var deviceImeEnabled = false
    // the disable and enable below move this input method to the end, and `ime reset` would install the default list
    private var enabledIms: String? = null
    private var spellChecker: String? = null
    private var imsWere: Array<String>? = null
    private var imWas: String? = null
    private var pinyinKeyboardWas: PinyinKeyboard? = null
    internal var followSystemWas: Boolean? = null
    internal var themeWas: Theme? = null
    internal val themesMade = mutableListOf<String>()

    @Test
    fun keyboardDrawingBaseline() {
        val sdk = android.os.Build.VERSION.SDK_INT
        log("device ${android.os.Build.MODEL} sdk $sdk runs $runs warm-up $warmUp")
        selectThisInputMethod()
        if (only != null) {
            // perfOnly=theme,shift,build,press,popup,anim,picture: those parts alone, for a short trace or an A/B
            launch()
            if ("theme" in only) switchThemes()
            if ("shift" in only) sceneCosts()
            if ("build" in only) buildEachKeyboard()
            if ("press" in only) pressFeedback()
            if ("popup" in only) popupSlide()
            if ("rebuild" in only) rebuilds()
            if ("anim" in only || "idle" in only) idleRows()
            animRows(only)
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
        idleRows()
        motionRows().also { pictureRows(soak = false) }
        // so the last memory rows do not count the animated picture's decoder
        onMain { themeWas?.let { ThemeManager.prefs.normalModeTheme.setValue(it) } }
        hideKeyboard()
        memory("after the runs, hidden")
        showKeyboard()
        memory("after the runs, shown")
    }

    private fun selectThisInputMethod() {
        deviceIme = shell("settings get secure default_input_method").takeIf { it.isNotEmpty() && it != "null" }
        val self = ComponentName.unflattenFromString(imeId)
        enabledIms = shell("settings get secure enabled_input_methods").takeIf { it.isNotEmpty() && it != "null" }
        deviceImeEnabled = enabledIms.orEmpty().split(':')
            .any { ComponentName.unflattenFromString(it.substringBefore(';')) == self }
        // the spell checker's underline would draw frames of its own in the field
        spellChecker = shell("settings get secure spell_checker_enabled")
        shell("settings put secure spell_checker_enabled 0")
        shell("ime enable $imeId")
        shell("ime set $imeId")
        bindInThisProcess(imeId, imePkg, ::shell)
    }

    private fun launch() {
        shell("input keyevent KEYCODE_WAKEUP")
        scenario = ActivityScenario.launch(Intent(context, TypingHostActivity::class.java))
        scenario.onActivity {
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
        }
        showKeyboard()
        AppPrefs.getInstance().keyboard.pinyinKeyboard.let { pref ->
            pinyinKeyboardWas = pref.getValue()
            if (pref.getValue() != PinyinKeyboard.Full) onMain { pref.setValue(PinyinKeyboard.Full) }
        }
        host {
            imsWere = enabledIme().map { it.uniqueName }.toTypedArray()
            imWas = currentIme().uniqueName
            val wanted = listOf(PINYIN).filter { im -> enabledIme().none { it.uniqueName == im } }
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
        runCatching { pinyinKeyboardWas?.let { onMain { AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(it) } } }
        runCatching {
            host {
                imsWere?.let { setEnabledIme(it) }
                imWas?.let { activateIme(it) }
            }
        }
        if (::scenario.isInitialized) scenario.close()
        deviceIme.let { if (it == null) shell("ime reset") else shell("ime set $it") }
        if (!deviceImeEnabled) shell("ime disable $imeId")
        enabledIms?.let { shell("settings put secure enabled_input_methods $it") }
        spellChecker.let {
            if (it.isNullOrEmpty() || it == "null") shell("settings delete secure spell_checker_enabled")
            else shell("settings put secure spell_checker_enabled $it")
        }
    }

    private enum class Board(val label: String, val pinyin: PinyinKeyboard?) {
        FULL("26 keys (pinyin)", PinyinKeyboard.Full),
        NINE("9 keys", PinyinKeyboard.NineKeys),
        KEYS14("14 keys", PinyinKeyboard.Keys14),
        KEYS18("18 keys", PinyinKeyboard.Keys18),
        ZHUYIN("zhuyin (大千)", PinyinKeyboard.ZhuyinFull),
        ZHUYIN_NINE("zhuyin 9 keys", PinyinKeyboard.ZhuyinNine),
        NUMBER("number", null),
        SYMBOLS("symbols", null),
    }

    private val built = linkedMapOf<Board, MutableList<KeyboardFrames.Sample>>()
    private val viewsIn = linkedMapOf<Board, Pair<Int, Int>>()

    /** A keyboard picked in ⋯ is a setting that remakes the keyboards; number and symbol keyboards are timed from the tap's lift on a fresh input view. */
    private fun buildEachKeyboard() {
        val picked = Board.entries.filter { it.pinyin != null && it != Board.FULL }
        repeat(warmUp + runs) {
            for (board in picked) {
                record(board, pick(board))
                record(Board.FULL, pick(Board.FULL))
            }
        }
        val number = keyAt(onKeyboard().text("123"))
        val symbols = keyAt(onKeyboard().text("符"))
        repeat(warmUp + runs) {
            fresh()
            record(Board.NUMBER, tapTo(number, Board.NUMBER))
            fresh()
            record(Board.SYMBOLS, tapTo(symbols, Board.SYMBOLS))
        }
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

    internal fun fresh() {
        pick(Board.KEYS14)
        pick(Board.FULL)
    }

    private fun pick(board: Board): KeyboardFrames.Sample {
        // InputView.remakeKeyboards keeps the frame and the view around it
        val before = onMain { keyboardFrame()?.getChildAt(0) }
        return measure({ keyboardFrame().let { it?.getChildAt(0) !== before && shows(it, board) } }) {
            AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(board.pinyin!!)
        }
    }

    private fun tapTo(key: PointF, board: Board): KeyboardFrames.Sample {
        // before the tap: the symbols take the keyboard's frame away
        onMain { keyboardArea() }
        val mark = onMain { frames.mark(0) { shows(keyboardFrame(), board) } }
        val heap = Heap.now()
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, key, down, down)
        SystemClock.sleep(HOLD_MS)
        val up = SystemClock.uptimeMillis()
        mark.from = up * DownMarks.NS_PER_MS
        inject(MotionEvent.ACTION_UP, key, down, up)
        val sample = try {
            frames.await(mark)
        } catch (it: IllegalStateException) {
            val shown = onMain { keyboardFrame()?.let { f -> (0 until f.childCount).map { f.getChildAt(it).javaClass.simpleName } } }
            throw IllegalStateException("$board not shown after the tap at $key: the keyboard's frame holds $shown", it)
        }
        return sample.also { settle() }.heap(Heap.now() - heap)
    }

    /** The symbols are a window of their own, so they show as the keyboard's frame gone. */
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
            Board.SYMBOLS -> error("above")
        }
    }

    private fun keyboardFrame(): ViewGroup? = service.window.window!!.decorView.findViewById(R.id.keyboard_view)

    private var area: View? = null
    private fun keyboardArea(): View {
        keyboardFrame()?.parent?.let { area = it as View }
        return area!!
    }

    /** Themes as the list picks them; a picture is decoded off the main thread (ThemeAssets.prefetch) and may show a frame or more later. */
    private fun switchThemes() {
        val prefs = ThemeManager.prefs
        followSystemWas = prefs.followSystemDayNightTheme.getValue()
        themeWas = prefs.normalModeTheme.getValue()
        log("theme\tkey border setting ${prefs.keyBorderChoice.getValue()} (FollowTheme: each theme's own)")
        val block = ThemeManager.builtin("pixel#light")
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
        // a theme not seen before each run: a flat one with corners of its own
        val unseen = mutableListOf<KeyboardFrames.Sample>()
        repeat(warmUp + runs) { i ->
            val radius = (flat.look.keys.radiusDp + 1 + i) % (Keys.MAX_RADIUS_DP + 1)
            val new = Theme.Custom("$UNSEEN-$i", isDark = false, look = flat.look.copy(keys = flat.look.keys.copy(radiusDp = radius)))
            if (!onMain { ThemeManager.saveTheme(new) }) {
                log("theme\t${new.name} could not be saved: left out")
                return@repeat
            }
            themesMade += new.name
            settle()
            val sample = theme(new)
            if (i >= warmUp) unseen += sample
            theme(block)
        }
        row("theme", "block → a flat one not seen before", unseen)
    }

    internal fun theme(to: Theme): KeyboardFrames.Sample {
        val before = onMain { keyboardFrame() }
        // the frame shown alone, its keyboard not yet in it, is not the switch done
        return measure({ keyboardFrame().let { it !== before && shows(it, Board.FULL) } }) { ThemeManager.setNormalModeTheme(to) }
    }

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

    /** Each down timed from its event to the window's next frame done ([DownMarks]). */
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
            val runFrames = mutableListOf<KeyboardFrames.Frame>()
            repeat(warmUp + runs) { i ->
                val one = presses(marks, listOf(at.getValue('a')), gapMs = HOLD_MS * 2)
                endTyping(space)
                val from = System.nanoTime()
                val ten = presses(marks, letters.map(at::getValue), gapMs = FAST_GAP_MS)
                if (i >= warmUp) runFrames += frames.between(from, System.nanoTime())
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
            log("frames\t10-key run, every frame\t${frames.summary(runFrames)}")
            // a send field: Return's label changes with the first key and the commit, remaking the scene each time
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

    private fun fieldAction(action: Int?) {
        scenario.onActivity {
            it.field.isSingleLine = action != null
            it.field.imeOptions = action ?: EditorInfo.IME_ACTION_UNSPECIFIED
            context.inputMethodManager.restartInput(it.field)
        }
        settle()
    }

    internal fun presses(marks: DownMarks, keys: List<PointF>, gapMs: Long): List<KeyboardFrames.Sample> {
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
        // a down with no frame after it is reported, not counted
        val drawn = marks.marks.mapNotNull { runCatching { frames.await(it) }.getOrNull() }
        if (drawn.size != keys.size) log("press\t${keys.size - drawn.size} of ${keys.size} downs had no frame")
        settle()
        val step = Heap.now() - heap
        return drawn.onEach { it.heap(step, keys.size) }
    }

    internal fun endTyping(space: PointF) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, space, down, down)
        inject(MotionEvent.ACTION_UP, space, down, SystemClock.uptimeMillis())
        settle()
        scenario.onActivity { it.field.setText("") }
        settle()
    }

    /** Shift's tap to the frame that shows it, and the scene's build cost alone on the main thread, fresh and unchanged. */
    private fun sceneCosts() {
        fresh()
        val shift = keyAt(onKeyboard().res(imePkg, "button_caps"))
        val taps = mutableListOf<KeyboardFrames.Sample>()
        repeat(warmUp + runs) { i ->
            // apart, so the second tap is not a double tap's lock
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

    internal fun shiftTap(at: PointF): KeyboardFrames.Sample {
        // Shift's state is the canvas's: its scene made anew is the tap shown
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

    internal fun canvasView(): KeyboardCanvasView? = keyboardFrame()?.let(::all)?.filterIsInstance<KeyboardCanvasView>()?.firstOrNull()

    private fun textKeyboard(): TextKeyboard = keyboardFrame()!!.let(::all).filterIsInstance<TextKeyboard>().first()

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    /** Height setting changed and screen rotated, each timed to the first frame at the new size. */
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

    /** A move that drew no frame (focus not moved) is reported, not counted. */
    private fun popupSlide() {
        fresh()
        val e = keyAt(onKeyboard().text("e"))
        val r = keyAt(onKeyboard().text("r"))
        val step = (r.x - e.x) * POPUP_STEP
        val space = keyAt(onKeyboard().res(imePkg, "button_space"))
        val moved = mutableListOf<KeyboardFrames.Sample>()
        var undrawn = 0
        // made as the window gets each move: one made earlier would take a traversal already due
        val marks = onMain { DownMarks(service.window.window!!.callback, frames, MotionEvent.ACTION_MOVE).also { service.window.window!!.callback = it } }
        try {
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
                    marks.marks.clear()
                    marks.recording = true
                    inject(MotionEvent.ACTION_MOVE, at, down, SystemClock.uptimeMillis())
                    marks.recording = false
                    val mark = marks.marks.firstOrNull()
                    val sample = mark?.let { runCatching { frames.await(it, POPUP_FRAME_WAIT_MS) }.getOrNull() }
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
        } finally {
            onMain { service.window.window!!.callback = marks.original }
        }
        if (undrawn > 0) log("popup\t$undrawn moves drew no frame")
        row("popup", "focus moved a choice", moved)
    }

    /** Frames and CPU with nothing happening: after a run, shown and idle, and hidden (expect no frame, CPU about 0). */
    internal fun idleRows() {
        fresh()
        val letters = "nihaoshiji"
        val at = letters.toSet().associateWith { keyAt(onKeyboard().text("$it")) }
        val space = keyAt(onKeyboard().res(imePkg, "button_space"))
        val marks = onMain { DownMarks(service.window.window!!.callback, frames).also { service.window.window!!.callback = it } }
        val after = mutableListOf<Int>()
        try {
            repeat(warmUp + runs) { i ->
                presses(marks, letters.map(at::getValue), gapMs = FAST_GAP_MS)
                SystemClock.sleep(IDLE_LEAD_MS)
                val from = System.nanoTime()
                SystemClock.sleep(IDLE_AFTER_MS)
                if (i >= warmUp) after += frames.between(from, System.nanoTime()).size
                endTyping(space)
            }
        } finally {
            onMain { service.window.window!!.callback = marks.original }
        }
        log("anim\tidle after\tframes in 2 s ${Stats(after.map { it.toDouble() })} (median / p90)\tmax ${after.maxOrNull()}\tstatic theme")
        settle()
        log("anim\tshown, static theme\t${frames.idle(IDLE_MS)}")
        hideKeyboard()
        log("anim\thidden, static theme\t${frames.idle(IDLE_MS)}")
        showKeyboard()
    }

    internal fun memory(label: String) = log(memoryLine(label, ::shell))

    internal fun hideKeyboard() {
        scenario.onActivity { context.inputMethodManager.hideSoftInputFromWindow(it.field.windowToken, 0) }
        device.wait(Until.gone(By.pkg(imePkg).res(imePkg, "keyboard_view")), 5_000)
        settle()
    }

    internal fun showKeyboard() {
        scenario.onActivity {
            it.field.requestFocus()
            context.inputMethodManager.showSoftInput(it.field, 0)
        }
        find(By.pkg(imePkg).res(imePkg, "keyboard_view"))
        settle()
    }

    private fun measure(ready: () -> Boolean, action: () -> Unit): KeyboardFrames.Sample {
        val heap = Heap.now()
        val mark = onMain {
            val from = System.nanoTime()
            frames.mark(from, ready).also { action() }
        }
        return frames.await(mark).also { settle() }.heap(Heap.now() - heap)
    }

    internal fun inject(action: Int, at: PointF, down: Long, time: Long) {
        val event = MotionEvent.obtain(down, time, action, at.x, at.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "not injected" }
        event.recycle()
    }

    // the main thread idle, plus a little for what is posted after a frame (the bar, a11y)
    internal fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(SETTLE_MS)
    }

    internal fun onKeyboard() = By.pkg(imePkg).hasAncestor(By.res(imePkg, "keyboard_view"))

    private fun find(selector: BySelector) = device.wait(Until.findObject(selector), 10_000) ?: error("not on screen: $selector")

    internal fun keyAt(selector: BySelector) = find(selector).visibleBounds.let { PointF(it.exactCenterX(), it.exactCenterY()) }

    internal fun row(what: String, label: String, samples: List<KeyboardFrames.Sample>, extra: String = "") {
        fun stats(of: (KeyboardFrames.Sample) -> Double?) = Stats(samples.mapNotNull(of))
        val withMetrics = samples.count { it.toDone != null }
        val dispatch = if (samples.any { it.dispatch != null }) "\tdispatch ${stats { it.dispatch }}\tdispatch to done ${stats { it.fromDispatch }}" else ""
        log(
            "$what\t$label\tn ${samples.size} (metrics $withMetrics)\tto frame done ${stats { it.latency }}" +
                "\tto commit ${stats { it.toCommit }}\tmain before draw ${stats { it.toPreDraw }}$dispatch" +
                "\tframe total ${stats { it.frameTotal }}\tlayout ${stats { it.layoutMeasure }}\tdraw ${stats { it.draw }}" +
                "\tmean ${"%.1f".format(Locale.ROOT, stats { it.fromDispatch ?: it.latency }.mean)}" +
                "\tallocated ${"%.0f".format(Locale.ROOT, stats { it.allocated }.mean / KIB)} KiB\tgcs ${"%.2f".format(Locale.ROOT, stats { it.gcs }.mean)}\t$extra",
        )
    }

    internal fun log(line: String) = Unit.also { Log.i(TAG, line) }

    internal fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    internal companion object {
        const val TAG = "KeyboardPerf"
        const val PINYIN = InputMethodCatalog.PINYIN
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
        const val SCREENSHOT_WAIT_MS = 1_000L
        const val IDLE_LEAD_MS = 300L
        const val IDLE_AFTER_MS = 2_000L
        const val IDLE_MS = 10_000L
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

// as SoftKeyboardTest: am instrument force-stops this package, and on API 36 `ime set` on the chosen one binds
// nothing anew. Out of the class for detekt LargeClass.
private fun bindInThisProcess(imeId: String, imePkg: String, shell: (String) -> String) {
    fun running() = shell("dumpsys activity services $imePkg").let {
        FcitxInputMethodService::class.java.name in it && " ${Process.myPid()}:$imePkg" in it.replace("{", " ").replace("/", " ")
    }
    if (running()) return
    shell("ime disable $imeId")
    shell("ime enable $imeId")
    shell("ime set $imeId")
    val deadline = SystemClock.uptimeMillis() + 30_000
    while (!running() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200)
    check(running()) { "input method not running in this process" }
}

private const val GC_WAIT_MS = 500L
private const val MEMORY_SAMPLES = 3

// median of MEMORY_SAMPLES after two collections
private fun memoryLine(label: String, shell: (String) -> String): String {
    repeat(2) {
        Runtime.getRuntime().gc()
        SystemClock.sleep(GC_WAIT_MS)
    }
    val samples = (1..MEMORY_SAMPLES).map {
        SystemClock.sleep(GC_WAIT_MS)
        Memory.parse(shell("dumpsys meminfo ${Process.myPid()}"))
    }
    fun median(of: (Memory) -> Int) = Stats(samples.map { of(it).toDouble() }).median.toInt()
    return "memory\t$label\tjava ${median { it.javaHeap }} kB\tnative ${median { it.nativeHeap }} kB\tgraphics ${median { it.graphics }} kB\ttotal pss ${median { it.totalPss }} kB"
}
