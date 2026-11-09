/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import java.io.File

/**
 * What a finger did on each key of a board (its taps, swipes and long presses, what reached the engine
 * and the field), line by line, against the committed record: a key that stops sending, sends the
 * wrong thing or pops up where it should not shows as a changed line. `GESTURES_RECORD=1` records them
 * anew (review the diff before committing it).
 */
internal object GestureSnapshot {
    // where GESTURES_RECORD=1 writes them (the module's directory the tests' working one); read from the classpath
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
