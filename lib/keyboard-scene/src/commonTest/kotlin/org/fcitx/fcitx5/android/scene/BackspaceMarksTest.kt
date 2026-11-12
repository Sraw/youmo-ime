/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ⌫'s two marks on the floating boards: which are drawn where they are set (ContentTest is long enough)
class BackspaceMarksTest {
    private fun icons(s: Scene, id: String) = s.keys.first { it.id == id }.ops(false).filterIsInstance<Op.Icon>()

    private fun face(s: Scene, id: String) = (s.keys.first { it.id == id }.shape as Shape.Rect).box

    @Test
    fun aFloatingBoardsBackspaceAtTheDefaultsSideBySideOnTop() {
        // 上滑 右上, 下滑 左上 (the defaults): ⌫'s 清除 and 恢复 side by side on top beside its icon. The narrow ⌫s
        // of floating 大千, 注音九键 and 手写 have no room for both after the icons' class (6%) and the marks' class
        // (to 0.875) are smaller, and leave them out as KeyMarkYield says (恢复 first, then 清除); the swipes work
        val drawn = mapOf(
            "qwerty" to setOf("delete-sweep", "restore"),
            "k14" to setOf("delete-sweep", "restore"),
            "k18" to setOf("delete-sweep", "restore"),
            "nine" to setOf("delete-sweep", "restore"),
            "number" to setOf("delete-sweep", "restore"),
            "symbols" to setOf("delete-sweep", "restore"),
            "zhuyin" to setOf("delete-sweep"),
            "handwriting" to setOf("delete-sweep"),
            "zhuyin-nine" to emptySet(),
        )
        for (d in listOf(2.0, 2.625, 2.75, 3.5)) for ((board, marks) in drawn) {
            val k = d / Forms.DENSITY
            val e = Forms.env(board, Forms.floating.copy(width = Forms.floating.width * k, height = Forms.floating.height * k, density = d))
            val s = Forms.scene(board, e)
            val icons = icons(s, "backspace").associate { it.name to it.box }
            assertEquals(marks + "backspace", icons.keys, "$board at $d")
            val f = face(s, "backspace")
            icons["delete-sweep"]?.let { assertTrue(it.left > (f.left + f.right) / 2, "$board at $d: 清除 top right $it") }
            icons["restore"]?.let { assertTrue(it.right < (f.left + f.right) / 2, "$board at $d: 恢复 top left $it") }
        }
    }

    @Test
    fun aFloatingBoardsBackspaceDrawsBothWithTheDownOneBelow() {
        // docs/keyboard.md's advice: 下滑 at 右下, and every floating board draws both of ⌫'s marks (at 左下 恢复
        // meets ⌫'s icon on its left and is left out on most of them, so the docs name 右下 alone)
        for (down in listOf(KeyMarks.At.BottomRight)) for (d in listOf(2.0, 2.625, 2.75, 3.5)) {
            for (board in listOf("qwerty", "k14", "k18", "nine", "number", "symbols", "zhuyin", "handwriting", "zhuyin-nine")) {
                val k = d / Forms.DENSITY
                val e = Forms.env(board, Forms.floating.copy(width = Forms.floating.width * k, height = Forms.floating.height * k, density = d))
                val s = Forms.scene(board, e.copy(marks = e.marks.copy(upAt = KeyMarks.At.TopRight, downAt = down)))
                assertEquals(setOf("backspace", "delete-sweep", "restore"), icons(s, "backspace").map { it.name }.toSet(), "$board at $d")
            }
        }
    }
}
