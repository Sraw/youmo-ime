/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.junit.Assert.assertEquals
import java.io.File

/**
 * A board's wiring, key by key; `WIRING_RECORD=1` records anew (review the diff before committing).
 * contract: the committed wiring files, one line per key, id then what it does.
 */
internal object WiringSnapshot {
    // WIRING_RECORD=1 writes here (the tests run in the module's directory); read from the classpath
    private val dir = File("src/test/resources/wiring")

    fun check(name: String, now: List<String>) {
        val file = "wiring/$name.txt"
        val lines = now.sorted().joinToString("\n", postfix = "\n")
        if (System.getenv("WIRING_RECORD") == "1") {
            dir.mkdirs()
            File(dir, "$name.txt").writeText(lines)
        }
        val committed = if (System.getenv("WIRING_RECORD") == "1") lines else
            checkNotNull(javaClass.classLoader!!.getResource(file)) { "$file: none (WIRING_RECORD=1 records it)" }.readText()
        assertEquals("$file: the scene's wiring", committed, lines)
    }

    enum class Sound { Standard, SpaceBar, Delete, Return }

    /** The function class is recorded as the letters' one, the system's click being the standard one for both. */
    fun layoutSound(key: SoundKey): Sound = when (key) {
        SoundKey.SPACE -> Sound.SpaceBar
        SoundKey.BACKSPACE -> Sound.Delete
        SoundKey.ENTER -> Sound.Return
        SoundKey.LETTER, SoundKey.FUNCTION -> Sound.Standard
    }

    fun line(id: String, shortcut: Any?, bothWays: Boolean, actions: Set<String>, popups: Set<String>, sound: Any, space: Boolean, backspace: Boolean, enabled: Boolean) =
        "$id: shortcut=$shortcut bothWays=$bothWays actions=${actions.sorted()} popups=${popups.sorted()} sound=$sound space=$space backspace=$backspace enabled=$enabled"
}
