/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.widget.TextView
import java.io.File
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** 切输入法 and 选输入法 through a real [KotlinHost] on a scripted engine, as [KeyShortcutsHostTest] runs the edit ones. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class InputMethodCommandsHostTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule(order = 1)
    val host = HostKeyboardRule("im-commands-host")
    private val h get() = host.h
    private val fcitx get() = host.fcitx

    private fun start(prefill: String = "") {
        host.up()
        h.start(prefill = { it.setText(prefill); it.setSelection(prefill.length) })
    }

    private fun swipe(v: ShownKey, up: Boolean) {
        val finger = h.finger(v, h.at(v))
        val way = if (up) -1f else 1f
        for (i in 1..6) finger.move(h.at(v, dy = way * 10f * i, ms = 16))
        finger.up(h.at(v, dy = way * 60f))
    }

    private fun type(keys: String) {
        val was = host.preedit
        keys.forEach { h.tap(h.key(it.toString())) }
        host.until("$keys typed") { host.preedit == was + keys }
    }

    private fun enable(vararg ims: String) {
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(*ims))
            activateIme(ims.first())
        }
        host.synced()
    }

    private val im get() = fcitx.inputMethodEntryCached.uniqueName

    // the globe hidden, a swipe does its tap
    @Test
    @Uat("KB-113")
    fun aSwipeOfSwitchInputMethodIsTheGlobesTap() {
        prefs.set(AppPrefs.getInstance().keyboard.showLangSwitchKey, false)
        prefs.set(AppPrefs.getInstance().internal.keySwipes, SwipeSlots.Default.with("full/q", up = true, SwipeSlot.Command(KeyCommand.SwitchInputMethod)).serialize())
        start()
        enable(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH)
        val globe = h.service.getString(R.string.a11y_key_switch_language)
        assertTrue(h.keys().none { it.description == globe })
        swipe(h.key("q"), up = true)
        host.until("on English") { im == InputMethodCatalog.ENGLISH }
        // round to the first, as 🌐 goes
        swipe(h.key("q"), up = true)
        host.until("back on pinyin") { im == InputMethodCatalog.PINYIN }
        assertEquals("", h.field.text.toString())
    }

    private val globe: ShownKey get() = h.keys().single { it.description == h.service.getString(R.string.a11y_key_switch_language) }

    /** The field and the preedit after 🌐 is tapped with `ni` typed on pinyin; back on pinyin with the field emptied. */
    private fun globeOnNi(): Pair<String, String> {
        type("ni")
        h.tap(globe)
        host.until("on English") { im == InputMethodCatalog.ENGLISH }
        host.synced()
        val after = h.field.text.toString() to host.preedit
        h.tap(globe)
        host.until("back on pinyin") { im == InputMethodCatalog.PINYIN }
        h.field.setText("")
        host.synced()
        return after
    }

    // the one command not committing what is typed first: the host's switch does with it what it does for 🌐
    @Test
    @Uat("KB-113")
    fun aSwipeOfSwitchInputMethodWhilePinyinIsTypedIsTheGlobesTapToo() {
        prefs.set(AppPrefs.getInstance().internal.keySwipes, SwipeSlots.Default.with("full/q", up = true, SwipeSlot.Command(KeyCommand.SwitchInputMethod)).serialize())
        start()
        enable(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH)
        val byGlobe = globeOnNi()
        type("ni")
        swipe(h.key("q"), up = true)
        host.until("on English") { im == InputMethodCatalog.ENGLISH }
        host.synced()
        assertEquals(byGlobe, h.field.text.toString() to host.preedit)
    }

    private fun chooseOnQ() =
        prefs.set(AppPrefs.getInstance().internal.keySwipes, SwipeSlots.Default.with("full/q", up = true, SwipeSlot.Command(KeyCommand.ChooseInputMethod)).serialize())

    private fun menuHeld(): KeyboardHarness.Finger {
        val q = h.key("q")
        val finger = h.finger(q, h.at(q))
        for (i in 1..6) finger.move(h.at(q, dy = -10f * i, ms = 16))
        return finger
    }

    private fun shown() = h.popupChoices().map { it.text }

    private fun focused() = h.popupChoices().single { it.focused }.text

    private fun name(uniqueName: String) = InputMethodNames.of(h.service, uniqueName, "")

    // enabled ones in the globe's order then 更多…; the finger on the next one, so the least move is the globe's tap
    @Test
    @Uat("KB-114")
    fun aSwipeOfChooseInputMethodPicksFromItsMenu() {
        chooseOnQ()
        start()
        enable(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH)
        val q = h.key("q")
        var finger = menuHeld()
        val more = h.service.getString(R.string.input_method_menu_more)
        assertEquals(listOf(name(InputMethodCatalog.PINYIN), name(InputMethodCatalog.SHUANGPIN), name(InputMethodCatalog.ENGLISH), more), shown())
        assertEquals(name(InputMethodCatalog.SHUANGPIN), focused())
        finger.move(h.at(q, dx = 60f, dy = -60f, ms = 16))
        assertEquals(name(InputMethodCatalog.ENGLISH), focused())
        finger.up(h.at(q, dx = 60f, dy = -60f))
        host.until("on English") { im == InputMethodCatalog.ENGLISH }
        assertTrue(shown().isEmpty())

        // back down on the key picks nothing
        finger = menuHeld()
        finger.move(h.at(q, ms = 16))
        finger.up(h.at(q))
        host.synced()
        assertEquals(InputMethodCatalog.ENGLISH, im)

        // on English, the finger starts on pinyin, round to the start: two over is English, greyed, nothing
        finger = menuHeld()
        assertEquals(name(InputMethodCatalog.PINYIN), focused())
        finger.move(h.at(q, dx = 120f, dy = -60f, ms = 16))
        assertEquals(name(InputMethodCatalog.ENGLISH), focused())
        finger.up(h.at(q, dx = 120f, dy = -60f))
        host.synced()
        assertEquals(InputMethodCatalog.ENGLISH, im)
        assertEquals("", h.field.text.toString())
    }

    @Test
    @Uat("KB-114")
    fun aPickWhilePinyinIsTypedIsTheGlobesTapToo() {
        chooseOnQ()
        start()
        enable(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH)
        val byGlobe = globeOnNi()
        type("ni")
        menuHeld().up(h.at(h.key("q"), dy = -60f))
        host.until("on English") { im == InputMethodCatalog.ENGLISH }
        host.synced()
        assertEquals(byGlobe, h.field.text.toString() to host.preedit)
    }

    // no menu made (no scene, no room for it): the swipe is the globe's long press, its picker, not nothing
    @Test
    @Uat("KB-114")
    fun aMenuThatCannotOpenIsTheGlobesLongPress() {
        chooseOnQ()
        start()
        enable(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH)
        val board = KeyboardHarness.all(h.keyboard).filterIsInstance<BaseKeyboard>().first { h.shown(it) }
        val pops = board.popupActionListener
        board.popupActionListener = PopupActionListener { if (it !is PopupAction.ShowInputMethodMenuAction) pops?.onPopupAction(it) }
        menuHeld().up(h.at(h.key("q"), dy = -60f))
        h.idle()
        // the swipe's preview lingers a moment after the lift: no popup lists the input methods
        val names = listOf(name(InputMethodCatalog.PINYIN), name(InputMethodCatalog.ENGLISH))
        assertTrue(shown().toString(), shown().none { it in names })
        // built on the host's thread, so waited for; by its title, as the input method's own window may be shown after it
        val title = h.service.getString(R.string.choose_input_method)
        fun picker() = ShadowDialog.getShownDialogs().firstOrNull { p ->
            p.isShowing && p.window?.decorView?.let { d -> KeyboardHarness.all(d).filterIsInstance<TextView>().any { it.text.toString() == title } } == true
        }
        host.until("the picker shown") { picker() != null }
        picker()?.dismiss()
    }

    // a swipe down opens it as well: going on down keeps the pick, back up onto the key cancels
    @Test
    @Uat("KB-115")
    fun aMenuOpenedByASwipeDownCancelsBackUpOnTheKeyNotGoingOnDown() {
        prefs.set(AppPrefs.getInstance().internal.keySwipes, SwipeSlots.Default.with("full/q", up = false, SwipeSlot.Command(KeyCommand.ChooseInputMethod)).serialize())
        start()
        enable(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH)
        val q = h.key("q")
        fun held(): KeyboardHarness.Finger {
            val finger = h.finger(q, h.at(q))
            for (i in 1..6) finger.move(h.at(q, dy = 10f * i, ms = 16))
            return finger
        }
        var finger = held()
        assertEquals(name(InputMethodCatalog.SHUANGPIN), focused())
        finger.move(h.at(q, dy = 90f, ms = 16))
        finger.move(h.at(q, dy = 200f, ms = 16))
        assertEquals(name(InputMethodCatalog.SHUANGPIN), focused())
        finger.move(h.at(q, dx = 60f, dy = 200f, ms = 16))
        assertEquals(name(InputMethodCatalog.ENGLISH), focused())
        finger.up(h.at(q, dx = 60f, dy = 200f))
        host.until("on English") { im == InputMethodCatalog.ENGLISH }

        // back up onto the key picks nothing
        finger = held()
        finger.move(h.at(q, ms = 16))
        assertTrue(shown().isEmpty())
        finger.up(h.at(q))
        host.synced()
        assertEquals(InputMethodCatalog.ENGLISH, im)
        assertEquals("", h.field.text.toString())

        // straight back onto the key on the move after it opened
        finger = h.finger(q, h.at(q))
        var dy = 0f
        while (name(InputMethodCatalog.PINYIN) !in shown()) {
            dy += 10f
            finger.move(h.at(q, dy = dy, ms = 16))
        }
        finger.move(h.at(q, ms = 16))
        assertTrue(shown().isEmpty())
        finger.up(h.at(q))
        host.synced()
        assertEquals(InputMethodCatalog.ENGLISH, im)
    }

    private fun savedDefault() = File(appContext.filesDir, "im-commands-host/config/profile").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("DefaultIM=") }?.substringAfter('=')

    // a pick is 🌐's switch, not the picker's for this field: the default changes and is saved
    @Test
    @Uat("KB-114")
    fun aPickBecomesTheDefault() {
        chooseOnQ()
        start()
        enable(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH)
        menuHeld().up(h.at(h.key("q"), dy = -60f))
        host.until("on 双拼, saved as the default", { savedDefault() }) { im == InputMethodCatalog.SHUANGPIN && savedDefault() == InputMethodCatalog.SHUANGPIN }
        // on 双拼 the finger starts on English
        menuHeld().up(h.at(h.key("q"), dy = -60f))
        host.until("on English, saved as the default", { savedDefault() }) { im == InputMethodCatalog.ENGLISH && savedDefault() == InputMethodCatalog.ENGLISH }
    }

    @Test
    @Uat("KB-114")
    fun withOneInputMethodItsMenuIsItAndMore() {
        chooseOnQ()
        start()
        enable(InputMethodCatalog.PINYIN)
        val finger = menuHeld()
        val more = h.service.getString(R.string.input_method_menu_more)
        assertEquals(listOf(name(InputMethodCatalog.PINYIN), more), shown())
        assertEquals(more, focused())
        finger.up(h.at(h.key("q"), dy = -60f))
        h.idle()
        assertEquals(InputMethodCatalog.PINYIN, im)
    }
}
