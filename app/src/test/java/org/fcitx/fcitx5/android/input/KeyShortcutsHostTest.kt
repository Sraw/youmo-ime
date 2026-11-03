/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.until as waitUntil
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
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
        h.idle()
        assertEquals("hao", preedit)
        // nor once hao is cleared in its turn: ni was typed over
        swipe(backspace, up = true)
        until("hao cleared") { preedit.isEmpty() }
        swipe(backspace, up = false)
        until("hao typed again") { preedit == "hao" }
        h.tap(h.key("a"))
        until("a typed") { preedit == "haoa" }
        swipe(backspace, up = false)
        h.idle()
        assertEquals("haoa", preedit)
    }

    // review M1: another field, as empty as the clear left the first, gets nothing of it
    @Test
    fun aClearIsNeverPutBackInAnotherField() {
        start("给 A 的话")
        swipe(backspace, up = true)
        until("cleared") { h.field.text.isEmpty() }
        h.start(fieldId = 2)
        swipe(backspace, up = false)
        h.idle()
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
}
