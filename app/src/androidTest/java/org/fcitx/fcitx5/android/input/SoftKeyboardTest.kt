/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The keyboard end to end: this app's input method, shown over a real `EditText`, driven by
 * tapping its keys. Everything between a tap and the text in the editor runs for real -- the
 * service, fcitx, EditingSession's predictions and the editor's reports.
 *
 * Needs a device or emulator. Selects this app's input method itself, and puts back the
 * device's input method and fcitx's input method list afterwards.
 */
class SoftKeyboardTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    private lateinit var scenario: ActivityScenario<TypingHostActivity>

    /** Descriptions come from the app's strings, so the tests run in any device language. */
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun launch(action: Int? = null, inputMethod: String) {
        // lives in the debug app (see app/src/debug), next to the input method
        val intent = Intent(context, TypingHostActivity::class.java)
        action?.let { intent.putExtra(TypingHostActivity.EXTRA_ACTION, it) }
        scenario = ActivityScenario.launch(intent)
        // the keyboard is up once its Backspace is
        key(By.pkg(imePkg).desc(string(R.string.a11y_key_backspace)))
        val ime = withFcitx {
            if (originalIms == null) originalIms = enabledIme().map { it.uniqueName }.toTypedArray()
            if (enabledIme().none { it.uniqueName == inputMethod }) {
                setEnabledIme((enabledIme().map { it.uniqueName } + inputMethod).toTypedArray())
            }
            activateIme(inputMethod)
            // with its sub-mode, which only the active input context's status has
            currentIme()
        }
        assertEquals("the input method switched to", inputMethod, ime.uniqueName)
        // the Space key names the input method; wait for the switch to show
        key(onKeyboard().desc(string(R.string.a11y_key_space_with_ime, spaceName(ime))))
    }

    /**
     * Space's name for [ime], as TextKeyboard.onInputMethodUpdate puts it: the app's name for the
     * input method, in the app's language (fcitx names the engine's own in Chinese only: 拼音), and
     * its sub-mode's.
     */
    private fun spaceName(ime: InputMethodEntry) = buildString {
        append(InputMethodNames.of(context, ime))
        ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
    }

    @Before
    fun selectThisInputMethod() {
        shell("ime enable $imeId")
        shell("ime set $imeId")
    }

    @After
    fun close() {
        if (::scenario.isInitialized) scenario.close()
    }

    private fun key(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), 10_000)
            ?: error("no key $selector on screen; keys: " +
                device.findObjects(By.pkg(imePkg)).mapNotNull { it.contentDescription ?: it.text }.distinct())

    /**
     * Keys and candidates, kept apart from each other and from the host's `EditText` -- the
     * same package, so a bare text match could hit the field or a candidate instead of a key.
     */
    private fun onKeyboard() = By.pkg(imePkg).hasAncestor(By.res(imePkg, "keyboard_view"))
    private fun candidate(text: String) = By.pkg(imePkg).text(text).hasAncestor(By.res(imePkg, "candidate_view"))
    private fun anyCandidate() = By.pkg(imePkg).clazz("android.widget.TextView").hasAncestor(By.res(imePkg, "candidate_view"))

    /** A letter key: its label is a TextView inside the key. */
    private fun tapLetters(letters: String) = letters.forEach {
        key(onKeyboard().text("$it")).click()
        device.waitForIdle()
    }

    /** Space is described with the input method's name. */
    private fun tapSpace() {
        key(onKeyboard().res(imePkg, "button_space")).click()
        device.waitForIdle()
    }

    private fun tap(description: Int) {
        key(onKeyboard().desc(string(description))).click()
        device.waitForIdle()
    }

    private fun fieldText(): String {
        var text = ""
        scenario.onActivity { text = it.field.text.toString() }
        return text
    }

    private fun assertFieldBecomes(expected: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (fieldText() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, fieldText())
    }

    @Test
    fun pinyinBecomesHanziOnSpace() {
        launch(inputMethod = PINYIN)
        tapLetters("nihao")
        key(candidate("你好"))
        tapSpace()
        assertFieldBecomes("你好")
    }

    // nothing typed, 1 is ，。？！: pressed again soon, the next in place of the last; while typing, 分词
    @Test
    fun nineKeysOneIsPunctuationTillSomethingIsTyped() {
        // 连按 at its default, 0.6 s, whatever this device has: off, 1 cycles at it too; Fast's 0.4 s is less room
        val multiTap = AppPrefs.getInstance().keyboard.t9MultiTap
        val before = multiTap.getValue()
        multiTap.setValue(T9MultiTap.Medium)
        try {
            launch(inputMethod = Engines.T9)
            pressOneTwiceSoon()
            assertFieldBecomes("。")
            key(onKeyboard().text("ABC")).click()
            key(onKeyboard().text("分词"))
            tap(R.string.a11y_key_backspace)
            key(onKeyboard().text("，。？！"))
        } finally {
            multiTap.setValue(before)
        }
    }

    // a field that asks not to be learned from is not read: the cursor the editor reported after ， tells it is there
    @Test
    fun nineKeysOnePressedAgainInAFieldNotLearnedFromTakesThePlaceOfTheLast() {
        val multiTap = AppPrefs.getInstance().keyboard.t9MultiTap
        val before = multiTap.getValue()
        // at its slowest, 0.9 s: the most room for the editor's report of ， before the second press
        multiTap.setValue(T9MultiTap.Slow)
        try {
            launch(action = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, inputMethod = Engines.T9)
            val one = oneKey()
            val first = press(one)
            // again once the editor's report of ， is handled, as at a person's pace (before it, ，。 goes in:
            // commitOverEngine), and still within 连按's 0.9 s: a device slower to report skips the test, saying how slow
            assertFieldBecomes("，")
            instrumentation.waitForIdleSync()
            val reported = SystemClock.uptimeMillis() - first
            assumeTrue(
                "the editor reported ， $reported ms after the press: too late for 连按's ${T9MultiTap.Slow.timeout} ms",
                reported < T9MultiTap.Slow.timeout - PRESS_LATENCY,
            )
            press(one)
            assertFieldBecomes("。")
        } finally {
            multiTap.setValue(before)
        }
    }

    /** 九键's 1 pressed twice, 0.1 s apart, within the multi-tap timeout. */
    private fun pressOneTwiceSoon() {
        val one = oneKey()
        repeat(2) {
            press(one)
            SystemClock.sleep(100)
        }
    }

    // found once: a search for the key takes time out of 连按's window
    private fun oneKey(): Rect = key(onKeyboard().text("，。？！")).visibleBounds

    /** A press at the centre of [bounds], injected, as a click waits for the screen to settle; when it went down. */
    private fun press(bounds: Rect): Long {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("injected", instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        return down
    }

    // a real field, learning: the word the data lacks typed once is offered by its keys
    @Test
    fun englishOffersAWordOnceTyped() {
        // typed once: not left learned by a run before, nor kept on the device after this one
        fun forget() = withFcitx { forgetEnglishWords(listOf("qwzx")) }
        forget()
        try {
            launch(inputMethod = ENGLISH)
            tapLetters("qwzx")
            tapSpace()
            assertFieldBecomes("qwzx ")
            tapLetters("qwz")
            key(candidate("qwzx"))
        } finally {
            forget()
        }
    }

    @Test
    fun aCandidateCanBePicked() {
        launch(inputMethod = PINYIN)
        tapLetters("nihao")
        key(candidate("你好")).click()
        assertFieldBecomes("你好")
    }

    /**
     * After a pick, Pinyin offers predictions (的, 了, ...) for what might come next. A Backspace
     * then goes to the app, which deletes, and the predictions go away with it (PinyinSession's
     * backspace with nothing typed); libime's pinyin instead took the first one only to dismiss them.
     */
    @Test
    fun backspaceAfterAPickDeletesAndDismissesThePredictions() {
        launch(inputMethod = PINYIN)
        tapLetters("nihao")
        key(candidate("你好")).click()
        assertFieldBecomes("你好")
        key(anyCandidate()) // predictions, whichever the dictionary offers
        tap(R.string.a11y_key_backspace)
        assertFieldBecomes("你")
        assertTrue("predictions dismissed", device.wait(Until.gone(anyCandidate()), 5_000))
        tap(R.string.a11y_key_backspace)
        assertFieldBecomes("")
    }

    @Test
    fun backspaceDeletesTypedText() {
        launch(inputMethod = ENGLISH)
        // words as written: "abc" a space corrects (ABC), which backspace would take back
        tapLetters("cat")
        tapSpace()
        assertFieldBecomes("cat ")
        tap(R.string.a11y_key_backspace)
        tap(R.string.a11y_key_backspace)
        assertFieldBecomes("ca")
    }

    /**
     * fcitx switching again before the system's echo of a switch is back leaves the keyboard, and
     * the system's subtype, on the last switch. On the emulator the service's startup switch to
     * Pinyin and a test's switch to English ran 2 ms apart, and the echo of the first put the
     * keyboard back on Pinyin. Ending on an input method the system did not have shows it was told.
     */
    @Test
    fun switchesBackToBackLeaveTheKeyboardOnTheLast() {
        assumeTrue("subtypes from Android 14 on", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val switched = listOf(ENGLISH, PINYIN, SHUANGPIN)
        withFcitx {
            if (originalIms == null) originalIms = enabledIme().map { it.uniqueName }.toTypedArray()
            val missing = switched.filter { im -> enabledIme().none { it.uniqueName == im } }
            if (missing.isNotEmpty()) setEnabledIme((enabledIme().map { it.uniqueName } + missing).toTypedArray())
            // without a subtype the service tells the system nothing, and no echo comes back;
            // before the launch, so that its switch to English is told
            if (switched.any { SubtypeManager.subtypeOf(it) == null }) SubtypeManager.syncWith(enabledIme())
        }
        launch(inputMethod = ENGLISH)
        // the launch's switch told: a service that never tells the system fails here, or at the end on
        // Shuangpin if the system had English already
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (systemIme() != ENGLISH && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals("the system's subtype after the launch", ENGLISH, systemIme())
        // back to back from one block (each activateIme its own hop to fcitx): all three IMChangeEvents
        // most likely reach the service before the first's echo is back, as on the emulator
        val changes = withFcitx {
            val seen = CopyOnWriteArrayList<String>()
            coroutineScope {
                // in the emitting thread: eventFlow drops its oldest event past 15 buffered
                val collecting = launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
                    eventFlow.filterIsInstance<FcitxEvent.IMChangeEvent>().collect { seen += it.data.uniqueName }
                }
                activateIme(PINYIN)
                activateIme(ENGLISH)
                activateIme(SHUANGPIN)
                // the system on Shuangpin, the main thread idle, and a second more for a switch back
                val told = SystemClock.uptimeMillis() + 5_000
                while (systemIme() != SHUANGPIN && SystemClock.uptimeMillis() < told) delay(50)
                instrumentation.waitForIdleSync()
                delay(1_000)
                collecting.cancel()
            }
            seen.toList()
        }
        // a single slot holds the last switch (Shuangpin), so the earlier switches' echoes read as the user's picks and switch fcitx back
        // from the third switch on only its sub-modes' changes, which repeat the input method (Fcitx.handleFcitxEvent)
        assertEquals("input methods from the third switch on, of $changes", listOf(SHUANGPIN), changes.dropWhile { it != SHUANGPIN }.distinct())
        assertEquals("the input method switched to last", SHUANGPIN, withFcitx { currentIme().uniqueName })
        assertEquals("the system's subtype", SHUANGPIN, systemIme())
    }

    /** The fcitx input method of the system's subtype, null if not one of ours. */
    private fun systemIme() = context.inputMethodManager.currentInputMethodSubtype?.let(SubtypeManager::inputMethodOf)

    /**
     * Dragging left from Backspace selects text before the cursor; letting go deletes it. The
     * normal touch path, where every touch ends in an Up; the cancel path is the next test.
     */
    @Test
    fun swipingLeftFromBackspaceDeletesWhatItSelected() {
        launch(inputMethod = ENGLISH)
        tapLetters("to")
        tapSpace()
        tapLetters("be")
        tapSpace()
        assertFieldBecomes("to be ")
        val backspace = key(onKeyboard().desc(string(R.string.a11y_key_backspace))).visibleBounds
        device.swipe(backspace.centerX(), backspace.centerY(), backspace.centerX() - backspace.width() * 3, backspace.centerY(), 40)
        device.waitForIdle()
        var selection = -1 to -1
        scenario.onActivity { selection = it.field.selectionStart to it.field.selectionEnd }
        val text = fieldText()
        assertTrue("deleted from the end: '$text' at $selection", text.length < "to be ".length && "to be ".startsWith(text))
        assertEquals(text.length to text.length, selection)
        // a tap afterwards is a plain Backspace again
        tap(R.string.a11y_key_backspace)
        assertFieldBecomes(text.dropLast(1))
    }

    /**
     * With the vivo keypress workaround the keyboard dispatches touches itself, and a cancelled
     * touch reaches the key without an Up. The swipe's reading must not outlive it: a swipe
     * cancelled mid-composition (which reads as "reset", selecting nothing) must not make the
     * next swipe, with nothing composed, a reset too -- it would select and delete nothing.
     */
    @Test
    fun aSwipeAfterACancelledOneUnderTheVivoWorkaroundReadsTheEditorAfresh() {
        val vivo = AppPrefs.getInstance().advanced.vivoKeypressWorkaround
        val before = vivo.getValue()
        vivo.setValue(true)
        try {
            launch(inputMethod = ENGLISH)
            tapLetters("to") // composed
            val bounds = key(onKeyboard().desc(string(R.string.a11y_key_backspace))).visibleBounds
            val y = bounds.centerY().toFloat()
            val x0 = bounds.centerX().toFloat()
            val x1 = x0 - bounds.width() * 3
            val downTime = SystemClock.uptimeMillis()
            fun inject(action: Int, x: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("injected", instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            inject(MotionEvent.ACTION_DOWN, x0)
            for (step in 1..30) {
                inject(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * step / 30)
                SystemClock.sleep(10)
            }
            inject(MotionEvent.ACTION_CANCEL, x1)
            device.waitForIdle()
            tapSpace()
            assertFieldBecomes("to ")
            // nothing composed now: this swipe selects, and letting go deletes
            device.swipe(x0.toInt(), y.toInt(), x1.toInt(), y.toInt(), 40)
            device.waitForIdle()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (fieldText() == "to " && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            val text = fieldText()
            assertTrue("the swipe deleted from the end: '$text'", text.length < "to ".length && "to ".startsWith(text))
        } finally {
            vivo.setValue(before)
        }
    }

    /** The Return key says and performs the editor's action. */
    @Test
    fun returnPerformsASearchFieldsAction() {
        launch(action = EditorInfo.IME_ACTION_SEARCH, inputMethod = ENGLISH)
        tap(R.string.a11y_key_action_search)
        awaitEditorActions(listOf(EditorInfo.IME_ACTION_SEARCH))
    }

    /**
     * Characterises current behaviour, as upstream has it: while a word is composed the key
     * shows "Enter", yet the English engine commits the word and then passes Return on, so the
     * search runs as well.
     */
    @Test
    fun returnWhileComposingEnglishCommitsTheWordAndSearches() {
        launch(action = EditorInfo.IME_ACTION_SEARCH, inputMethod = ENGLISH)
        tapLetters("abc")
        tap(R.string.a11y_key_enter)
        assertFieldBecomes("abc")
        awaitEditorActions(listOf(EditorInfo.IME_ACTION_SEARCH))
    }

    private fun awaitEditorActions(expected: List<Int>) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (editorActions() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, editorActions())
    }

    private fun editorActions(): List<Int> {
        var actions = emptyList<Int>()
        scenario.onActivity { actions = it.actions.toList() }
        return actions
    }

    /**
     * English composes the word being typed in the editor. Tapping inside it moves fcitx's
     * cursor there (EditingSession.onCursorUpdate -> MovePreeditCursor), so the next letter
     * lands at the tap.
     */
    @Test
    fun tappingInsideTheComposedWordTypesThere() {
        launch(inputMethod = ENGLISH)
        tapLetters("abcdefgh")
        assertFieldBecomes("abcdefgh")
        pointInField(offset = 4).let { (x, y) -> device.click(x, y) }
        waitForCursor(4)
        tapLetters("z")
        assertFieldBecomes("abcdzefgh")
    }

    /** Tapping outside finishes the composition where it is; the next letter lands at the tap. */
    @Test
    fun tappingOutsideTheComposedWordLeavesItAndTypesThere() {
        launch(inputMethod = ENGLISH)
        tapLetters("ab")
        tapSpace()
        tapLetters("cd")
        assertFieldBecomes("ab cd")
        pointInField(offset = 1).let { (x, y) -> device.click(x, y) }
        waitForCursor(1)
        tapLetters("x")
        assertFieldBecomes("axb cd")
    }

    /** Screen coordinates of the caret position before [offset] in the field. */
    private fun pointInField(offset: Int): Pair<Int, Int> {
        var point = 0 to 0
        scenario.onActivity {
            val location = IntArray(2).also { l -> it.field.getLocationOnScreen(l) }
            val layout = it.field.layout
            val x = layout.getPrimaryHorizontal(offset) + it.field.totalPaddingLeft - it.field.scrollX
            val line = layout.getLineForOffset(offset)
            val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2 + it.field.totalPaddingTop
            point = (location[0] + x.toInt()) to (location[1] + y)
        }
        return point
    }

    private fun waitForCursor(position: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var at = -1
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { at = it.field.selectionStart }
            if (at == position) break
            SystemClock.sleep(50)
        }
        assertTrue("cursor at $at, not $position", at == position)
        device.waitForIdle()
    }

    private companion object {
        const val PINYIN = Engines.PINYIN
        const val SHUANGPIN = Engines.SHUANGPIN
        const val ENGLISH = "keyboard-us"

        // allowed for an injected press's way to the keyboard, which counts 连按 from when it gets there
        const val PRESS_LATENCY = 100L

        val instrumentation = InstrumentationRegistry.getInstrumentation()!!
        val context = instrumentation.targetContext!!
        val device: UiDevice = UiDevice.getInstance(instrumentation)
        val imePkg: String = context.packageName
        val imeId = "$imePkg/${FcitxInputMethodService::class.java.name}"

        /** What the device and fcitx had before, put back after the class. */
        var originalDeviceIme: String? = null
        var wasEnabled = false
        var spellChecker: String? = null
        // whether rememberDeviceIme got as far as turning it off: JUnit runs @AfterClass after a failed @BeforeClass too
        var spellCheckerOff = false
        var originalIms: Array<String>? = null

        fun shell(command: String): String = device.executeShellCommand(command).trim()

        fun <T> withFcitx(block: suspend FcitxAPI.() -> T): T = try {
            runBlocking { withTimeout(30_000) { FcitxDaemon.connect(SoftKeyboardTest::class.java.name).runOnReady(block) } }
        } finally {
            FcitxDaemon.disconnect(SoftKeyboardTest::class.java.name)
        }

        @BeforeClass
        @JvmStatic
        fun rememberDeviceIme() {
            originalDeviceIme = shell("settings get secure default_input_method").takeIf { it.isNotEmpty() && it != "null" }
            // "pkg/.Short;subtype:pkg/full.Name" -- compared as components, subtypes dropped
            val self = ComponentName.unflattenFromString(imeId)
            wasEnabled = shell("settings get secure enabled_input_methods").split(':')
                .any { ComponentName.unflattenFromString(it.substringBefore(';')) == self }
            // the system's spell checker (on in AOSP images, as redroid) underlines "ab", and a tap
            // on it opens its suggestions over the field, which takes the next tap on a key
            spellChecker = shell("settings get secure spell_checker_enabled").takeIf { it.isNotEmpty() && it != "null" }
            spellCheckerOff = true
            shell("settings put secure spell_checker_enabled 0")
        }

        /**
         * Leaves the device as it was: a developer's own keyboard back, and this input method
         * no longer bound in this process, where it would share the native engine with the next
         * test class (FcitxTest starts one of its own).
         *
         * On a device whose keyboard is this app already, it is left selected -- and bound here,
         * where FcitxTest then refuses to start an engine of its own (checkThisInputMethodIsNotBound).
         */
        @AfterClass
        @JvmStatic
        fun restoreDeviceIme() {
            try {
                // the device's input method is put back even if fcitx's list could not be; the
                // subtypes with it, as the input method list's settings page does on a change
                val restoreIms = runCatching {
                    originalIms?.let { ims ->
                        withFcitx {
                            setEnabledIme(ims)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) SubtypeManager.syncWith(enabledIme())
                        }
                    }
                }
                val original = originalDeviceIme
                when {
                    original == null -> shell("ime reset")
                    ComponentName.unflattenFromString(original) == ComponentName.unflattenFromString(imeId) -> {
                        restoreIms.getOrThrow()
                        return
                    }
                    else -> shell("ime set $original")
                }
                if (!wasEnabled) shell("ime disable $imeId")
                // The system unbinds the service asynchronously; its onDestroy stops the engine
                // (FcitxDaemon.disconnect -> Fcitx.stop, which blocks until stopped).
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (FcitxInputMethodService::class.java.name in shell("dumpsys activity services $imePkg") &&
                    SystemClock.uptimeMillis() < deadline
                ) SystemClock.sleep(100)
                check(FcitxInputMethodService::class.java.name !in shell("dumpsys activity services $imePkg")) {
                    "the input method service is still bound; the next class's engine would collide with it"
                }
                restoreIms.getOrThrow()
                // the record goes before onDestroy has run on this process's main thread
                instrumentation.waitForIdleSync()
            } finally {
                // also where this app is the device's input method (returned above) or a restore failed
                if (spellCheckerOff) {
                    if (spellChecker == null) shell("settings delete secure spell_checker_enabled")
                    else shell("settings put secure spell_checker_enabled $spellChecker")
                }
            }
        }
    }
}
