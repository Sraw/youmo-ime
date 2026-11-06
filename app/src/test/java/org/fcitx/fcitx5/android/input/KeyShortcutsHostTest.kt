/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutRunner
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.until as waitUntil
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The swipes' shortcuts carried out (051-c §4): 清除 and 恢复 on backspace (up and down by default),
 * v's 粘贴 while pinyin is typed, through a real [KotlinHost] on a scripted engine, the field an app's.
 * What the main thread and the host's each do, in the order they do it, as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class KeyShortcutsHostTest {

    @get:Rule
    val prefs = PrefsRule()

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private lateinit var h: KeyboardHarness

    private fun start(prefill: String = "") {
        home = File(appContext.filesDir, "shortcuts-host").apply { deleteRecursively() }
        fcitx = startedHost(ScriptedEngine(home))
        h = keyboardOn(fcitx)
        h.start(prefill = { it.setText(prefill); it.setSelection(prefill.length) })
    }

    @After
    fun tearDown() {
        AppPrefs.getInstance().keyboard.pinyinKeyboard.let { it.setValue(it.defaultValue) }
        if (::h.isInitialized) h.close()
        if (::fcitx.isInitialized) fcitx.stop()
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun until(what: String, done: () -> Boolean) = waitUntil(
        what,
        { "field [${h.field.text}] preedit [${fcitx.inputPanelCached.preedit}]" },
        step = { h.idle() },
        done = done,
    )

    // what the main thread posted to the host done, and what it told back shown
    private fun synced() {
        var done = false
        h.service.postFcitxJob { done = true }
        until("the host's queue done") { done }
        h.idle()
    }

    private val backspace: View get() = h.inputView.findViewById(R.id.button_backspace)

    private val preedit get() = fcitx.inputPanelCached.preedit.toString()

    // past the swipe's threshold and lifted: the key's shortcut that way
    private fun swipe(v: View, up: Boolean) {
        val finger = h.finger(v, h.at(v))
        val way = if (up) -1f else 1f
        for (i in 1..6) finger.move(h.at(v, dy = way * 10f * i, ms = 16))
        finger.up(h.at(v, dy = way * 60f))
    }

    private fun type(keys: String) {
        val was = preedit
        keys.forEach { h.tap(h.key(it.toString())) }
        until("$keys typed") { preedit == was + keys }
    }

    @Test
    fun backspaceSwipedUpClearsWhatIsBeforeTheCursorAndDownPutsItBack() {
        start("你好世界")
        swipe(backspace, up = true)
        until("the field cleared") { h.field.text.isEmpty() }
        swipe(backspace, up = false)
        until("put back") { h.field.text.toString() == "你好世界" }
        assertEquals(4, h.field.selectionStart)
    }

    @Test
    fun pinyinClearedIsDroppedUncommittedAndTypedAgain() {
        start()
        type("nihao")
        swipe(backspace, up = true)
        until("nothing typed") { preedit.isEmpty() }
        assertEquals("nothing committed", "", h.field.text.toString())
        swipe(backspace, up = false)
        until("typed again") { preedit == "nihao" }
        assertEquals("", h.field.text.toString())
    }

    // review S1: pinyin typed since the clear is not dropped by putting the clear back
    @Test
    fun nothingIsPutBackOverWhatIsTypedSince() {
        start()
        type("ni")
        swipe(backspace, up = true)
        until("ni cleared") { preedit.isEmpty() }
        type("hao")
        swipe(backspace, up = false)
        synced()
        assertEquals("hao", preedit)
        // nor once hao is cleared in its turn: ni was typed over
        swipe(backspace, up = true)
        until("hao cleared") { preedit.isEmpty() }
        swipe(backspace, up = false)
        until("hao typed again") { preedit == "hao" }
        h.tap(h.key("a"))
        until("a typed") { preedit == "haoa" }
        swipe(backspace, up = false)
        synced()
        assertEquals("haoa", preedit)
    }

    // the keyboard changed since (the full keys for the 14): what the one before typed is no code
    // of this one's, and would reach the field as letters with this one's next key. Its clear is
    // dropped, as the change itself drops what is typed; a text clear stays
    @Test
    fun pinyinClearedIsNotTypedAgainOnAnotherKeyboard() {
        start("你好")
        type("ni")
        swipe(backspace, up = true)
        until("ni cleared") { preedit.isEmpty() }
        swipe(backspace, up = true)
        until("text cleared") { h.field.text.isEmpty() }
        AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14)
        h.idle()
        swipe(backspace, up = false)
        until("the text put back") { h.field.text.toString() == "你好" }
        swipe(backspace, up = false)
        synced()
        assertEquals("", preedit)
        assertEquals("你好", h.field.text.toString())
    }

    // review2: a clear on its way as another field starts (the host's part done, the main thread's
    // not yet) does nothing there: it was the first's. The main thread's part run as the listener runs
    // it, with the field it was asked in, once the second has started: no thread's timing in it
    @Test
    fun aClearUnderWayIsNotMadeInAFieldStartedSince() {
        start("给 A 的话")
        val asked = h.service.startedField
        h.start(fieldId = 2, prefill = { it.setText("给 B 的话"); it.setSelection(it.text.length) })
        val runner = KeyShortcutRunner(h.service, InputWindowManager(), {}, {}) { false }
        runner.clear(null, asked)
        runner.clear(HostCore.Retyping(InputMethodCatalog.PINYIN, emptyList()), asked)
        synced()
        assertEquals("给 B 的话", h.field.text.toString())
        // in its own field it clears
        runner.clear(null, h.service.startedField)
        synced()
        assertEquals("", h.field.text.toString())
    }

    // review2: the screen turned restarts input with the same EditorInfo, the same input: its clear
    // stays. Finished, then started so again, it is gone (onFinishInput forgets, review M1)
    @Test
    fun aClearOutlastsTheScreenTurningButNotTheInputFinishing() {
        start("你好")
        swipe(backspace, up = true)
        until("cleared") { h.field.text.isEmpty() }
        h.turn()
        swipe(backspace, up = false)
        until("put back") { h.field.text.toString() == "你好" }
        swipe(backspace, up = true)
        until("cleared again") { h.field.text.isEmpty() }
        h.service.onFinishInput()
        h.turn()
        swipe(backspace, up = false)
        synced()
        assertEquals("", h.field.text.toString())
    }

    // review2: a field that asks for no learning keeps nothing of a clear, as a password does
    @Test
    fun aFieldKeptFromLearningIsClearedWithNothingToPutBack() {
        home = File(appContext.filesDir, "shortcuts-host").apply { deleteRecursively() }
        fcitx = startedHost(ScriptedEngine(home))
        h = keyboardOn(fcitx)
        h.start(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, prefill = { it.setText("秘密"); it.setSelection(2) })
        swipe(backspace, up = true)
        until("cleared") { h.field.text.isEmpty() }
        swipe(backspace, up = false)
        synced()
        assertEquals("", h.field.text.toString())
    }

    // review M1: another field, as empty as the clear left the first, gets nothing of it
    @Test
    fun aClearIsNeverPutBackInAnotherField() {
        start("给 A 的话")
        swipe(backspace, up = true)
        until("cleared") { h.field.text.isEmpty() }
        h.start(fieldId = 2)
        swipe(backspace, up = false)
        synced()
        assertEquals("", h.field.text.toString())
    }

    // 051-c §1.4: what is typed goes in first, then the shortcut runs
    @Test
    fun vSwipedUpWhilePinyinIsTypedCommitsItThenPastes() {
        start()
        appContext.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, "X"))
        type("nihao")
        swipe(h.key("v"), up = true)
        until("committed, then pasted") { h.field.text.endsWith("X") && preedit.isEmpty() }
        assertEquals("你好X", h.field.text.toString())
    }

    // a key's shortcut set to 快速输入 opens it as 符's long press and the bar's button do, what is
    // typed put in first
    @Test
    fun aShortcutOfQuickInputOpensItAfterWhatIsTyped() {
        prefs.set(AppPrefs.getInstance().internal.keyShortcuts, KeyShortcuts.Empty.with("full/q", KeyShortcut.Command(KeyCommand.QuickInput)).serialize())
        start()
        type("nihao")
        swipe(h.key("q"), up = true)
        until("committed, then quick input open") { h.field.text.toString() == "你好" && fcitx.inputPanelCached.quickPhrase }
        assertEquals("", preedit)
    }

    private fun backspaces(n: Int, leaving: String) {
        repeat(n) { h.tap(backspace) }
        until("deleted to [$leaving]") { h.field.text.toString() == leaving }
    }

    // KB-64: Backspaces one after another put back whole, the cursor where it was
    @Test
    fun backspacesOneAfterAnotherArePutBackWholeBySwipingDown() {
        start("你好世界")
        backspaces(3, leaving = "你")
        swipe(backspace, up = false)
        until("put back") { h.field.text.toString() == "你好世界" }
        assertEquals(4, h.field.selectionStart)
    }

    // something committed after them: a run is over, not put back over it
    @Test
    fun aRunIsNotPutBackOnceSomethingIsCommitted() {
        start("你好世界")
        backspaces(2, leaving = "你好")
        h.service.commitOverEngine("啊")
        until("committed") { h.field.text.toString() == "你好啊" }
        swipe(backspace, up = false)
        synced()
        assertEquals("你好啊", h.field.text.toString())
    }

    // review L3: a copy changes nothing of the field, the cursor back where it was: the run goes on
    @Test
    fun aCopyBetweenBackspacesEndsNoRun() {
        prefs.set(AppPrefs.getInstance().internal.keyShortcuts, KeyShortcuts.Empty.with("full/c", KeyShortcut.Command(KeyCommand.Copy)).serialize())
        start("你好世界")
        backspaces(1, leaving = "你好世")
        swipe(h.key("c"), up = true)
        until("copied") { appContext.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() == "你好世" }
        backspaces(1, leaving = "你好")
        assertEquals(2, h.field.selectionStart)
        swipe(backspace, up = false)
        until("both put back") { h.field.text.toString() == "你好世界" }
    }

    // 0.6.0 #4: a copy or cut with nothing selected in an empty field leaves the clipboard as it was
    @Test
    fun aCopyOrCutOfAnEmptyFieldKeepsTheClipboard() {
        prefs.set(
            AppPrefs.getInstance().internal.keyShortcuts,
            KeyShortcuts.Empty.with("full/c", KeyShortcut.Command(KeyCommand.Copy)).with("full/x", KeyShortcut.Command(KeyCommand.Cut)).serialize(),
        )
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, "旧的"))
        start("")
        swipe(h.key("c"), up = true)
        swipe(h.key("x"), up = true)
        synced()
        assertEquals("旧的", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    // review L4: the screen turned is the same input, its engine focused again: the run is kept
    @Test
    fun aRunOutlastsTheScreenTurning() {
        start("你好世界")
        backspaces(2, leaving = "你好")
        h.turn()
        synced()
        swipe(backspace, up = false)
        until("put back") { h.field.text.toString() == "你好世界" }
    }

    // review L5: a tap on the field puts the cursor somewhere: what is deleted after it is a run of its own
    @Test
    fun aTapOnTheFieldStartsAnotherRun() {
        start("abc")
        backspaces(1, leaving = "ab")
        h.service.onViewClicked(false)
        h.field.setSelection(1)
        h.idle()
        backspaces(1, leaving = "b")
        swipe(backspace, up = false)
        until("the second put back") { h.field.text.toString() == "ab" }
        assertEquals(1, h.field.selectionStart)
    }

    // review G: a cut and a paste are the editor's (the context menu), changing the field: the run is
    // over, though the paste leaves it reading as the run did
    @Test
    fun aRunIsNotPutBackAfterACutAndAPaste() {
        start("你好世界")
        backspaces(1, leaving = "你好世")
        swipe(h.key("x"), up = true)
        until("cut") { h.field.text.isEmpty() }
        swipe(h.key("v"), up = true)
        until("pasted") { h.field.text.toString() == "你好世" }
        swipe(backspace, up = false)
        synced()
        assertEquals("你好世", h.field.text.toString())
    }
}
