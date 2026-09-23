/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.ComponentName
import android.content.Intent
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

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
        val name = withFcitx {
            if (originalIms == null) originalIms = enabledIme().map { it.uniqueName }.toTypedArray()
            if (enabledIme().none { it.uniqueName == inputMethod }) {
                setEnabledIme((enabledIme().map { it.uniqueName } + inputMethod).toTypedArray())
            }
            activateIme(inputMethod)
            enabledIme().first { it.uniqueName == inputMethod }.displayName
        }
        // the Space key names the input method; wait for the switch to show
        key(onKeyboard().desc(string(R.string.a11y_key_space_with_ime, name)))
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

    @Test
    fun aCandidateCanBePicked() {
        launch(inputMethod = PINYIN)
        tapLetters("nihao")
        key(candidate("你好")).click()
        assertFieldBecomes("你好")
    }

    /**
     * After a pick, Pinyin offers predictions (的, 了, ...) for what might come next. The first
     * Backspace dismisses them -- the engine takes the key for that -- and only the next one
     * deletes. Looks like a lost key press if the predictions go unnoticed.
     */
    @Test
    fun backspaceAfterAPickFirstDismissesThePredictions() {
        launch(inputMethod = PINYIN)
        tapLetters("nihao")
        key(candidate("你好")).click()
        assertFieldBecomes("你好")
        key(anyCandidate()) // predictions, whichever the dictionary offers
        tap(R.string.a11y_key_backspace)
        assertTrue("predictions dismissed", device.wait(Until.gone(anyCandidate()), 5_000))
        assertFieldBecomes("你好")
        tap(R.string.a11y_key_backspace)
        assertFieldBecomes("你")
    }

    @Test
    fun backspaceDeletesTypedText() {
        launch(inputMethod = ENGLISH)
        tapLetters("abc")
        tapSpace()
        assertFieldBecomes("abc ")
        tap(R.string.a11y_key_backspace)
        tap(R.string.a11y_key_backspace)
        assertFieldBecomes("ab")
    }

    /**
     * Dragging left from Backspace selects text before the cursor; letting go deletes it. The
     * normal touch path, where every touch ends in an Up; the cancel path is the next test.
     */
    @Test
    fun swipingLeftFromBackspaceDeletesWhatItSelected() {
        launch(inputMethod = ENGLISH)
        tapLetters("ab")
        tapSpace()
        tapLetters("cd")
        tapSpace()
        assertFieldBecomes("ab cd ")
        val backspace = key(onKeyboard().desc(string(R.string.a11y_key_backspace))).visibleBounds
        device.swipe(backspace.centerX(), backspace.centerY(), backspace.centerX() - backspace.width() * 3, backspace.centerY(), 40)
        device.waitForIdle()
        var selection = -1 to -1
        scenario.onActivity { selection = it.field.selectionStart to it.field.selectionEnd }
        val text = fieldText()
        assertTrue("deleted from the end: '$text' at $selection", text.length < "ab cd ".length && "ab cd ".startsWith(text))
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
            tapLetters("ab") // composed
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
            assertFieldBecomes("ab ")
            // nothing composed now: this swipe selects, and letting go deletes
            device.swipe(x0.toInt(), y.toInt(), x1.toInt(), y.toInt(), 40)
            device.waitForIdle()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (fieldText() == "ab " && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            val text = fieldText()
            assertTrue("the swipe deleted from the end: '$text'", text.length < "ab ".length && "ab ".startsWith(text))
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
        const val PINYIN = "pinyin"
        const val ENGLISH = "keyboard-us"

        val instrumentation = InstrumentationRegistry.getInstrumentation()!!
        val context = instrumentation.targetContext!!
        val device: UiDevice = UiDevice.getInstance(instrumentation)
        val imePkg: String = context.packageName
        val imeId = "$imePkg/${FcitxInputMethodService::class.java.name}"

        /** What the device and fcitx had before, put back after the class. */
        var originalDeviceIme: String? = null
        var wasEnabled = false
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
        }

        /**
         * Leaves the device as it was: a developer's own keyboard back, and this input method
         * no longer bound in this process, where it would share the native engine with the next
         * test class (FcitxTest starts one of its own).
         *
         * On a device whose keyboard is this app already, it is left selected -- and bound here,
         * which FcitxTest collides with regardless of this class.
         */
        @AfterClass
        @JvmStatic
        fun restoreDeviceIme() {
            // the device's input method is put back even if fcitx's list could not be
            val restoreIms = runCatching { originalIms?.let { ims -> withFcitx { setEnabledIme(ims) } } }
            val original = originalDeviceIme
            when {
                original == null -> shell("ime reset")
                ComponentName.unflattenFromString(original) == ComponentName.unflattenFromString(imeId) -> return
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
        }
    }
}
