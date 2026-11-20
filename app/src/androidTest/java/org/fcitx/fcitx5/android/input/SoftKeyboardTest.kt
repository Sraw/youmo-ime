/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Process
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
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostEvent
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

/** End to end over a real `EditText`; selects this input method itself and puts the device's back afterwards. */
class SoftKeyboardTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    private lateinit var scenario: ActivityScenario<TypingHostActivity>

    private var pinyinKeyboardWas: PinyinKeyboard? = null

    /** From the app's strings, so the tests run in any device language. */
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun launch(action: Int? = null, inputMethod: String, nineKeys: Boolean = false) {
        // set before the keyboard is made, whatever an earlier run left (its put-back once lost on API 36)
        val keyboard = AppPrefs.getInstance().keyboard.pinyinKeyboard
        if (pinyinKeyboardWas == null) pinyinKeyboardWas = keyboard.getValue()
        keyboard.setValue(if (nineKeys) PinyinKeyboard.NineKeys else PinyinKeyboard.Full)
        // in the debug app (app/src/debug)
        val intent = Intent(context, TypingHostActivity::class.java)
        action?.let { intent.putExtra(TypingHostActivity.EXTRA_ACTION, it) }
        scenario = ActivityScenario.launch(intent)
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
        key(onKeyboard().desc(string(R.string.a11y_key_space_with_ime, spaceName(ime))))
    }

    // as TextKeyboard.onInputMethodUpdate: the app's name, in the app's language (fcitx's own names are Chinese only)
    private fun spaceName(ime: InputMethodEntry) = InputMethodNames.of(context, ime)

    @Before
    fun selectThisInputMethod() {
        // am instrument force-stops this package, and `ime set` on the chosen one binds nothing anew: on API 36 the field's
        // show request then fails (no keyboard). Disable and enable rebinds it; once only, since a second disable would race
        // the old service's record going away.
        shell("ime enable $imeId")
        shell("ime set $imeId")
        if (running()) return
        shell("ime disable $imeId")
        shell("ime enable $imeId")
        shell("ime set $imeId")
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (!running() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200)
        assertTrue("input method running in this process", running())
    }

    private fun running(): Boolean = shell("dumpsys activity services $imePkg").let {
        FcitxInputMethodService::class.java.name in it && " ${Process.myPid()}:$imePkg" in it.replace("{", " ").replace("/", " ")
    }

    @After
    fun close() {
        if (::scenario.isInitialized) scenario.close()
        pinyinKeyboardWas?.let { AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(it) }
    }

    private fun key(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), 10_000)
            ?: error("no key $selector on screen; keys: " +
                device.findObjects(By.pkg(imePkg)).mapNotNull { it.contentDescription ?: it.text }.distinct())

    // the same package as the host's EditText: a bare text match could hit the field or a candidate
    private fun onKeyboard() = By.pkg(imePkg).hasAncestor(By.res(imePkg, "keyboard_view"))
    private fun candidate(text: String) = By.pkg(imePkg).text(text).hasAncestor(By.res(imePkg, "candidate_view"))
    private fun anyCandidate() = By.pkg(imePkg).clazz("android.widget.TextView").hasAncestor(By.res(imePkg, "candidate_view"))

    private fun tapLetters(letters: String) = letters.forEach {
        key(onKeyboard().text("$it")).click()
        device.waitForIdle()
    }

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

    // nothing typed, 1 is ，。？！ and a quick second press replaces the last; while typing it is 分词
    @Test
    fun nineKeysOneIsPunctuationTillSomethingIsTyped() {
        // 连按 on at 中 (0.6 s) whatever the device has; Fast's 0.4 s leaves less room
        val multiTap = AppPrefs.getInstance().keyboard.t9MultiTap
        val before = multiTap.getValue()
        multiTap.setValue(T9MultiTap.Medium)
        try {
            launch(inputMethod = InputMethodCatalog.PINYIN, nineKeys = true)
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

    // a no-learning field is not read back: the cursor the editor reports after ， is what tells it is there
    @Test
    fun nineKeysOnePressedAgainInAFieldNotLearnedFromTakesThePlaceOfTheLast() {
        val multiTap = AppPrefs.getInstance().keyboard.t9MultiTap
        val before = multiTap.getValue()
        multiTap.setValue(T9MultiTap.Slow)
        try {
            launch(action = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, inputMethod = InputMethodCatalog.PINYIN, nineKeys = true)
            val one = oneKey()
            val first = press(one)
            // after the editor's report of ， (before it, ，。 goes in: commitOverEngine) and within 0.9 s; a slower device skips
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

    private fun pressOneTwiceSoon() {
        val one = oneKey()
        repeat(2) {
            press(one)
            SystemClock.sleep(100)
        }
    }

    // found once: a search takes time out of 连按's window
    private fun oneKey(): Rect = key(onKeyboard().text("，。？！")).visibleBounds

    /** Injected, since a click waits for the screen to settle; returns when it went down. */
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

    @Test
    fun englishOffersAWordOnceTyped() {
        // not left learned by an earlier run, nor kept after this one
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

    /** The predictions go with the Backspace (PinyinSession's backspace with nothing typed); libime used to eat the key. */
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
        // "abc" would be corrected by space (ABC), which backspace takes back
        tapLetters("cat")
        tapSpace()
        assertFieldBecomes("cat ")
        tap(R.string.a11y_key_backspace)
        tap(R.string.a11y_key_backspace)
        assertFieldBecomes("ca")
    }

    /** On the emulator the startup switch and a test's switch ran 2 ms apart and the first's echo put the keyboard back. */
    @Test
    fun switchesBackToBackLeaveTheKeyboardOnTheLast() {
        assumeTrue("subtypes from Android 14 on", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val switched = listOf(ENGLISH, PINYIN, SHUANGPIN)
        withFcitx {
            if (originalIms == null) originalIms = enabledIme().map { it.uniqueName }.toTypedArray()
            val missing = switched.filter { im -> enabledIme().none { it.uniqueName == im } }
            if (missing.isNotEmpty()) setEnabledIme((enabledIme().map { it.uniqueName } + missing).toTypedArray())
            // without a subtype the service tells the system nothing and no echo comes; before the launch, so its switch is told
            if (switched.any { SubtypeManager.subtypeOf(it) == null }) SubtypeManager.syncWith(enabledIme())
        }
        launch(inputMethod = ENGLISH)
        // a service that never tells the system fails here, or at the end on Shuangpin
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (systemIme() != ENGLISH && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals("the system's subtype after the launch", ENGLISH, systemIme())
        // back to back: all three events most likely reach the service before the first's echo
        val changes = withFcitx {
            val seen = CopyOnWriteArrayList<String>()
            coroutineScope {
                // in the emitting thread: eventFlow drops its oldest past 15 buffered
                val collecting = launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
                    eventFlow.filterIsInstance<HostEvent.InputMethodChanged>().collect { seen += it.im }
                }
                activateIme(PINYIN)
                activateIme(ENGLISH)
                activateIme(SHUANGPIN)
                val told = SystemClock.uptimeMillis() + 5_000
                while (systemIme() != SHUANGPIN && SystemClock.uptimeMillis() < told) delay(50)
                instrumentation.waitForIdleSync()
                delay(1_000)
                collecting.cancel()
            }
            seen.toList()
        }
        // earlier echoes must not read as user picks; from the third switch on only sub-mode changes repeat it (KotlinHost.emit)
        assertEquals("input methods from the third switch on, of $changes", listOf(SHUANGPIN), changes.dropWhile { it != SHUANGPIN }.distinct())
        assertEquals("the input method switched to last", SHUANGPIN, withFcitx { currentIme().uniqueName })
        assertEquals("the system's subtype", SHUANGPIN, systemIme())
    }

    private fun systemIme() = context.inputMethodManager.currentInputMethodSubtype?.let(SubtypeManager::inputMethodOf)

    /** The touch path ending in Up; the cancel path is the next test. */
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
        tap(R.string.a11y_key_backspace)
        assertFieldBecomes(text.dropLast(1))
    }

    @Test
    fun returnPerformsASearchFieldsAction() {
        launch(action = EditorInfo.IME_ACTION_SEARCH, inputMethod = ENGLISH)
        tap(R.string.a11y_key_action_search)
        awaitEditorActions(listOf(EditorInfo.IME_ACTION_SEARCH))
    }

    /** Upstream's behaviour: the key shows "Enter" yet English commits the word and passes Return on. */
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

    // EditingSession.onCursorUpdate -> MovePreeditCursor
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
        const val PINYIN = InputMethodCatalog.PINYIN
        const val SHUANGPIN = InputMethodCatalog.SHUANGPIN
        const val ENGLISH = "keyboard-us"

        // an injected press's way to the keyboard, which counts 连按 from its arrival
        const val PRESS_LATENCY = 100L

        val instrumentation = InstrumentationRegistry.getInstrumentation()!!
        val context = instrumentation.targetContext!!
        val device: UiDevice = UiDevice.getInstance(instrumentation)
        val imePkg: String = context.packageName
        val imeId = "$imePkg/${FcitxInputMethodService::class.java.name}"

        var originalDeviceIme: String? = null
        var wasEnabled = false
        // selectThisInputMethod's disable and enable move this input method to the end and drop its subtypes
        var enabledIms: String? = null
        var spellChecker: String? = null
        // JUnit runs @AfterClass after a failed @BeforeClass too
        var spellCheckerOff = false
        var originalIms: Array<String>? = null

        fun shell(command: String): String = device.executeShellCommand(command).trim()

        fun <T> withFcitx(block: suspend HostApi.() -> T): T = try {
            runBlocking { withTimeout(30_000) { HostDaemon.connect(SoftKeyboardTest::class.java.name).runOnReady(block) } }
        } finally {
            HostDaemon.disconnect(SoftKeyboardTest::class.java.name)
        }

        @BeforeClass
        @JvmStatic
        fun rememberDeviceIme() {
            originalDeviceIme = shell("settings get secure default_input_method").takeIf { it.isNotEmpty() && it != "null" }
            // "pkg/.Short;subtype:pkg/full.Name": compared as components
            val self = ComponentName.unflattenFromString(imeId)
            enabledIms = shell("settings get secure enabled_input_methods").takeIf { it.isNotEmpty() && it != "null" }
            wasEnabled = enabledIms.orEmpty().split(':')
                .any { ComponentName.unflattenFromString(it.substringBefore(';')) == self }
            // the spell checker underlines "ab", and a tap on it opens suggestions that take the next key tap
            spellChecker = shell("settings get secure spell_checker_enabled").takeIf { it.isNotEmpty() && it != "null" }
            spellCheckerOff = true
            shell("settings put secure spell_checker_enabled 0")
        }

        /**
         * Unbinds this input method from the process, where it would share the native engine with the next class.
         * Where this app is the device's keyboard it stays bound, and HostTest refuses to start (checkThisInputMethodIsNotBound).
         */
        @AfterClass
        @JvmStatic
        fun restoreDeviceIme() {
            try {
                // the device's input method is put back even if fcitx's list could not be
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
                // unbinding is async; onDestroy stops the engine (HostDaemon.disconnect -> KotlinHost.stop, blocking)
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
                // executeShellCommand passes no shell, so unquoted (the value has no spaces)
                enabledIms?.let { shell("settings put secure enabled_input_methods $it") }
                if (spellCheckerOff) {
                    if (spellChecker == null) shell("settings delete secure spell_checker_enabled")
                    else shell("settings put secure spell_checker_enabled $spellChecker")
                }
            }
        }
    }
}
