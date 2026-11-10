/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.junit.Assert.assertEquals
import java.io.File

/**
 * A board's wiring, key by key, as committed text (060-render-p2 S5a M5): recorded from the views' KeyDef
 * (the old Layout, with the exceptions its test writes out), the scene's wiring compared against it. Each
 * line a key's id and what it does; `WIRING_RECORD=1` records them anew (review the diff before committing).
 * contract: the committed wiring files, one line per key, id then what it does.
 */
internal object WiringSnapshot {
    // where WIRING_RECORD=1 writes them (the module's directory the tests' working one); read from the classpath
    private val dir = File("src/test/resources/wiring")

    /** [old]'s lines are the file's (recorded from them under WIRING_RECORD=1), and [now]'s are too. */
    fun check(name: String, old: List<String>, now: List<String>) {
        val file = "wiring/$name.txt"
        val was = old.sorted().joinToString("\n", postfix = "\n")
        if (System.getenv("WIRING_RECORD") == "1") {
            dir.mkdirs()
            File(dir, "$name.txt").writeText(was)
        }
        val committed = if (System.getenv("WIRING_RECORD") == "1") was else
            checkNotNull(javaClass.classLoader!!.getResource(file)) { "$file: none (WIRING_RECORD=1 records it)" }.readText()
        assertEquals("$file: the views' wiring", committed, was)
        assertEquals("$file: the scene's wiring", committed, now.sorted().joinToString("\n", postfix = "\n"))
    }

    /**
     * [key] as the old views' click (KeyDef's SoundEffect): the layout's sound= alone, the function class (by
     * kind, 060-theme-sounds-icons) one with the letters' as the system's click is the standard one for both.
     */
    fun layoutSound(key: SoundKey): InputFeedbacks.SoundEffect = when (key) {
        SoundKey.SPACE -> InputFeedbacks.SoundEffect.SpaceBar
        SoundKey.BACKSPACE -> InputFeedbacks.SoundEffect.Delete
        SoundKey.ENTER -> InputFeedbacks.SoundEffect.Return
        SoundKey.LETTER, SoundKey.FUNCTION -> InputFeedbacks.SoundEffect.Standard
    }

    /** One key's line: [id], then each of what it does, in a set's order sorted. */
    fun line(id: String, shortcut: Any?, bothWays: Boolean, actions: Set<String>, popups: Set<String>, sound: Any, space: Boolean, backspace: Boolean, enabled: Boolean) =
        "$id: shortcut=$shortcut bothWays=$bothWays actions=${actions.sorted()} popups=${popups.sorted()} sound=$sound space=$space backspace=$backspace enabled=$enabled"
}
