/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputBinding
import android.view.inputmethod.InputMethod
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.inLandscape
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.picker.PickerGridView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The number keyboard the user opened kept for the next field of the same app; its lock, kept through hiding too. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class StickyNumbersUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private val h = KeyboardHarness()

    @After
    fun close() = h.close()

    private fun numbers() = h.texts().let { "5" in it && "q" !in it }

    private fun letters() = "q" in h.texts()

    private fun tap123() {
        h.tap(h.key("123"))
        assertTrue(h.texts().toString(), numbers())
    }

    private fun back() = h.tap(h.keyNamed("back"))

    /** Another field of the same app, as →| or a tap goes to it. */
    private fun next(type: Int = InputType.TYPE_CLASS_TEXT, imeOptions: Int = 0) {
        h.field.inputType = type
        h.field.imeOptions = imeOptions
        h.another()
    }

    private fun assertNumbers(where: String) = assertTrue("$where: ${h.texts()}", numbers())

    private fun assertLetters(where: String) = assertTrue("$where: ${h.texts()}", letters())

    private fun lock() = h.tap(h.keyNamed("lock"))

    private fun locked() = KeyboardHarness.find<NumberKeyboard>(h.board)?.locked == true

    @Test
    @Uat("PAN-39")
    fun keptAcrossPlainFields() {
        h.start(imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NAVIGATE_NEXT)
        tap123()
        next()
        assertNumbers("the next plain field")
        next(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        assertNumbers("an amount, its own pad")
        next()
        assertNumbers("a plain field after the amount")
        next(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertNumbers("an EditText of no type")
        next(InputType.TYPE_CLASS_PHONE)
        assertTrue(h.texts().toString(), "=" !in h.texts())
        next()
        assertTrue("the full pad after a phone's: ${h.texts()}", numbers() && "=" in h.texts())
        next(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertLetters("an email field")
        next(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertLetters("let go at the email field")
    }

    @Test
    @Uat("PAN-39")
    fun fieldsAskingForLettersGetThem() = assertRows(
        listOf(
            "an email" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            "a web email" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS),
            "a URI" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
            "a password" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            "a web password" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            "a visible password" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
        ),
        { it.first },
    ) { (where, type) ->
        h.hide()
        h.start()
        tap123()
        next(type)
        assertLetters(where)
    }

    @Test
    @Uat("PAN-40")
    fun anIncognitoFieldIsPlain() = assertRows(
        listOf(
            "incognito" to EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            "a search" to EditorInfo.IME_ACTION_SEARCH,
        ),
        { it.first },
    ) { (where, options) ->
        h.hide()
        h.start(imeOptions = options)
        tap123()
        next()
        assertNumbers("from $where")
        next(imeOptions = options)
        assertNumbers("to $where")
    }

    @Test
    @Uat("PAN-40")
    fun backHiddenOtherAppOrFieldPadLetGo() = assertRows(
        listOf<Pair<String, () -> Unit>>(
            "back" to { h.start(); tap123(); back(); next() },
            "hidden between" to { h.start(); tap123(); h.hide(); next() },
            "another app" to { h.start(); tap123(); h.start(packageName = "org.example.other") },
            "a number field's own pad" to { h.start(InputType.TYPE_CLASS_NUMBER); next() },
            "back then 123 in a phone field" to { h.start(InputType.TYPE_CLASS_PHONE); back(); h.tap(h.key("123")); next() },
        ),
        { it.first },
    ) { (where, steps) ->
        h.hide()
        steps()
        assertLetters(where)
    }

    @Test
    @Uat("PAN-39")
    fun thisAppsOwnFieldsKeepItToo() {
        h.start(packageName = h.service.packageName)
        tap123()
        next()
        assertNumbers("the next field of this app")
    }

    @Test
    @Uat("PAN-39")
    fun thePanelOpenedFromItClosesAtTheNextField() {
        h.start()
        tap123()
        h.tap(h.key("符"))
        val grid = { KeyboardHarness.find<PickerGridView>(h.inputView)?.let(h::shown) == true }
        assertTrue("the panel", grid())
        next()
        assertNumbers("the next field")
        assertFalse("the panel closed", grid())
    }

    @Test
    @Uat("PAN-41")
    fun aWebViewsRestartIsANewField() {
        h.start(fieldId = WEB_VIEW)
        tap123()
        h.restart()
        assertNumbers("the same field restarted")
        h.restart(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertLetters("its email field")
        h.restart(InputType.TYPE_CLASS_TEXT)
        assertLetters("let go at the email field")
        // an email field restarted is that field still
        h.tap(h.key("123"))
        h.restart(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        h.tap(h.key("123"))
        h.restart()
        assertNumbers("an email field restarted")
    }

    @Test
    @Uat("PAN-39")
    fun aHardwareKeyboardIsAsHiding() {
        h.start()
        tap123()
        val now = SystemClock.uptimeMillis()
        val a = { action: Int -> KeyEvent(now, now, action, KeyEvent.KEYCODE_A, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD) }
        h.service.onKeyDown(KeyEvent.KEYCODE_A, a(KeyEvent.ACTION_DOWN))
        h.service.onKeyUp(KeyEvent.KEYCODE_A, a(KeyEvent.ACTION_UP))
        h.idle()
        h.service.showTouchKeyboard()
        h.idle()
        next()
        assertLetters("after the hardware keyboard")
    }

    @Test
    @Uat("PAN-39")
    fun theInputViewReplacedKeepsIt() {
        h.start()
        tap123()
        h.inLandscape(relayout = true) { assertNumbers("turned") }
        h.relayout()
        assertNumbers("turned back")
        next()
        assertNumbers("the next field after turning")
        prefs.setStyle(KeyboardStyle.FLOATING)
        h.idle()
        h.relayout()
        assertNumbers("floating")
        next()
        assertNumbers("the next field, floating")
        prefs.setStyleDefault()
        val themeWas = ThemeManager.prefs.normalModeTheme.getValue()
        prefs.later { ThemeManager.setNormalModeTheme(themeWas) }
        prefs.set(ThemeManager.prefs.followSystemDayNightTheme, false)
        ThemeManager.setNormalModeTheme(TestThemes.PixelDark)
        h.idle()
        h.relayout()
        assertNumbers("another theme")
        next()
        assertNumbers("the next field, another theme")
    }

    @Test
    @Uat("PAN-39")
    fun anEmailFieldsNumbersStayThroughATurn() {
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        tap123()
        h.inLandscape(relayout = true) { assertNumbers("turned") }
        h.relayout()
        assertNumbers("turned back")
    }

    @Test
    @Uat("PAN-42")
    fun aLetterLayoutsPanelLockLocksNothing() {
        h.start()
        // as a key of a user's letter layout sending panel-lock
        KeyboardHarness.find<TextKeyboard>(h.board)!!.keyActionListener!!.onKeyAction(KeyAction.PanelLockAction, KeyActionListener.Source.Keyboard)
        h.idle()
        tap123()
        assertFalse("the pad's lock", locked())
        h.hide()
        next()
        assertLetters("hidden, then another field")
    }

    @Test
    @Uat("PAN-42")
    fun theLockKeepsThroughHidingAndLetterFields() {
        h.start()
        tap123()
        lock()
        assertTrue("locked", locked())
        h.hide()
        next()
        assertNumbers("hidden, then another field")
        assertTrue("still locked", locked())
        next(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertLetters("an email field")
        next()
        assertNumbers("a plain field after the email field")
        h.inLandscape(relayout = true) { assertTrue("turned: ${h.texts()}", numbers() && locked()) }
        h.relayout()
        assertTrue("turned back", locked())
        back()
        next()
        assertLetters("back")
    }

    @Test
    @Uat("PAN-42")
    fun theLockLetsGo() = assertRows(
        listOf<Pair<String, () -> Unit>>(
            "the lock again, hidden" to { lock(); h.hide(); next() },
            "another app" to { h.start(packageName = "org.example.other") },
        ),
        { it.first },
    ) { (where, steps) ->
        h.hide()
        h.start()
        tap123()
        lock()
        assertTrue(locked())
        steps()
        assertLetters(where)
    }

    @Test
    @Uat("PAN-42")
    fun theHomeScreenOrASystemWindowBetweenKeepsTheLock() {
        h.start()
        tap123()
        lock()
        bind(OTHER_UID)
        assertTrue("bound elsewhere, still up", locked())
        h.hide()
        bind(APP_UID)
        next()
        assertTrue("back in the app: ${h.texts()}", numbers() && locked())
    }

    @Test
    @Uat("PAN-42")
    fun unlockedInAPlainFieldItIsKeptAsByThe123Key() {
        h.start()
        tap123()
        lock()
        lock()
        assertFalse(locked())
        next()
        assertNumbers("the next field")
        h.hide()
        next()
        assertLetters("hidden")
    }

    @Test
    @Uat("PAN-42")
    fun lockedInAPhoneFieldTheLockShowsOpenInAnotherAppsPhoneField() {
        h.start(InputType.TYPE_CLASS_PHONE)
        lock()
        next()
        assertTrue("the full pad, locked: ${h.texts()}", numbers() && "=" in h.texts() && locked())
        h.start(InputType.TYPE_CLASS_PHONE)
        assertTrue("its own pad, locked still", locked())
        h.start(InputType.TYPE_CLASS_PHONE, packageName = "org.example.other")
        assertFalse("another app's phone field", locked())
    }

    private fun bind(uid: Int) {
        (h.service.onCreateInputMethodInterface() as InputMethod).bindInput(InputBinding(null, null, uid, 0))
        h.idle()
    }

    private companion object {
        // a WebView's fields share its id, which is set
        const val WEB_VIEW = 41
        const val APP_UID = 10_301
        const val OTHER_UID = 10_302
    }
}
