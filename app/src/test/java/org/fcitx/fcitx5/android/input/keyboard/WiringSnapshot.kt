package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import java.io.File

/**
 * A board's wiring, key by key, as committed text (060-render-p2 S5a M5): recorded from the views' KeyDef
 * (the old Layout, with the exceptions its test writes out), the scene's wiring compared against it. Each
 * line a key's id and what it does; `WIRING_RECORD=1` records them anew (review the diff before committing).
 */
internal object WiringSnapshot {
    private val dir = File("src/test/resources/wiring")

    /** [old]'s lines are the file's (recorded from them under WIRING_RECORD=1), and [now]'s are too. */
    fun check(name: String, old: List<String>, now: List<String>) {
        val file = File(dir, "$name.txt")
        val was = old.sorted().joinToString("\n", postfix = "\n")
        if (System.getenv("WIRING_RECORD") == "1") {
            dir.mkdirs()
            file.writeText(was)
        }
        val committed = file.readText()
        assertEquals("$file: the views' wiring", committed, was)
        assertEquals("$file: the scene's wiring", committed, now.sorted().joinToString("\n", postfix = "\n"))
    }

    /** One key's line: [id], then each of what it does, in a set's order sorted. */
    fun line(id: String, shortcut: Any?, bothWays: Boolean, actions: Set<String>, popups: Set<String>, sound: Any, space: Boolean, backspace: Boolean, enabled: Boolean) =
        "$id: shortcut=$shortcut bothWays=$bothWays actions=${actions.sorted()} popups=${popups.sorted()} sound=$sound space=$space backspace=$backspace enabled=$enabled"
}
