/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * What a finger did on each key of a board, line by line, against the committed record; `GESTURES_RECORD=1`
 * records anew (review the diff before committing it).
 *
 * contract: the committed records in `src/test/resources/gestures`, which mutation cannot see.
 */
internal object GestureSnapshot {
    // GESTURES_RECORD=1 writes here (the tests run in the module's directory); read from the classpath
    private val dir = File("src/test/resources/gestures")

    fun check(name: String, lines: List<String>) {
        val file = "gestures/$name.txt"
        val now = lines.joinToString("\n", postfix = "\n")
        if (System.getenv("GESTURES_RECORD") == "1") {
            dir.mkdirs()
            File(dir, "$name.txt").writeText(now)
            return
        }
        val committed = checkNotNull(javaClass.classLoader!!.getResource(file)) { "$file: none (GESTURES_RECORD=1 records it)" }.readText()
        assertEquals("$file: what each key did", committed, now)
    }
}

internal fun KeyboardHarness.sent(out: MutableList<String>, what: String) {
    out += "$what: " + api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") } +
        " field ${field.text}|${field.selectionStart},${field.selectionEnd}"
    api.calls.clear()
}

/** Each of [ids] tapped, swiped up and down (space and ⌫ also sideways), long pressed where [held]: a line each. */
internal fun KeyboardHarness.recordGestures(out: MutableList<String>, ids: List<String>, held: (String) -> Boolean, pressed: (String) -> Unit = {}) {
    for (id in ids) {
        val k = { keyNamed(id) }
        press(k())
        pressed(id)
        release()
        sent(out, "$id tap")
        swipe(k(), 0f, -k().bounds.height().toFloat())
        sent(out, "$id up")
        swipe(k(), 0f, k().bounds.height().toFloat())
        sent(out, "$id down")
        if (id == "space" || id == "backspace") {
            swipe(k(), -k().bounds.width() * 2f, 0f)
            sent(out, "$id left")
            swipe(k(), k().bounds.width() * 2f, 0f)
            sent(out, "$id right")
        }
        if (held(id)) {
            out += "$id long press: " + longPress(k())
            sent(out, "$id long press")
        }
    }
}

/** ⌫ held [ms]: deletes at least [atLeast]; [out] keeps what it sent, not how many (that depends on the frame). */
internal fun KeyboardHarness.backspaceHeld(out: MutableList<String>, ms: Long, atLeast: Int, kept: (String) -> Boolean = { it.startsWith("sendKey") }) {
    press(keyNamed("backspace"))
    after(ms)
    release()
    val deletes = api.calls.count { it.startsWith("sendKey") }
    assertTrue("$deletes deletes", deletes >= atLeast)
    out += "backspace held: ${api.calls.filter(kept).distinct()}"
    api.calls.clear()
}
