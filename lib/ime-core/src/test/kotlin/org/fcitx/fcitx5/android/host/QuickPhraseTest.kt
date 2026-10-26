/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quick input's temp mode contract; lines are `modules/quickphrase/quickphrasetempmode.cpp`'s. */
class QuickPhraseTest {

    private class Mem(private val files: Map<String, String>) : PkgDataDir {
        override fun read(path: String) = files[path]?.toByteArray()
        override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }
    }

    private val table = QuickPhraseTable.load(
        listOf(
            Mem(
                mapOf(
                    "data/quickphrase.d/t.mb" to (1..12).joinToString("\n") { "ab$it v$it" } +
                        "\nxy one\nxy two\n:s: smile\n1a digitkey",
                ),
            ),
        ),
    )
    private val rig = TempModeRig(table, { null })

    private fun trigger() = rig.press(TempKeys.GRAVE, KeyStates.SUPER)

    @Test
    fun superGraveAndSuperSemicolonTriggerOnPressOnly() {
        // :86-94, quickphrase.h:35-39
        assertFalse(rig.release(TempKeys.GRAVE, KeyStates.SUPER).filtered)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        val e = trigger()
        assertTrue(e.filtered && e.accepted)
        assertTrue(rig.quickPhrase.isActive(rig.ic))
        assertEquals("Quick Phrase: ", rig.aux)
        rig.press(KeySyms.ESCAPE)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        rig.press(TempKeys.SEMICOLON, KeyStates.SUPER)
        assertTrue(rig.quickPhrase.isActive(rig.ic))
        // the trigger keys pass the engine first (PostInputMethod); Escape, taken by the mode, does not
        assertEquals(listOf(TempKeys.GRAVE, TempKeys.SEMICOLON), rig.engineKeys)
    }

    @Test
    fun theTableIsAskedForAtTheFirstLookupNotBefore() {
        // the app loads it on a thread of its own at start (KotlinFcitx.startup): nothing may wait on it sooner
        var asked = 0
        val rig = TempModeRig(QuickPhraseTable.EMPTY, { null }, tableSource = { asked++; table })
        rig.press(TempKeys.GRAVE, KeyStates.SUPER)
        assertEquals(0, asked)
        rig.type("xy")
        assertTrue(asked > 0)
        assertEquals(listOf("one", "two"), rig.page)
    }

    @Test
    fun typingListsMatchesAndSpaceCommitsTheFirst() {
        trigger()
        rig.type("xy")
        assertEquals("xy", rig.preedit)
        assertEquals(listOf("one", "two"), rig.page)
        assertEquals(listOf("one", "two"), (0..1).map { rig.panel.candidates!!.candidate(it).text })
        assertEquals("xy", rig.panel.candidates!!.candidate(0).comment)
        assertEquals("1. ", rig.panel.candidates!!.label(0))
        rig.press(KeySyms.SPACE)
        assertEquals(listOf("one"), rig.commits())
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        assertTrue(rig.panel === TempPanel.EMPTY)
    }

    @Test
    fun digitsSelectOnThePageElseType() {
        // :103-115: digit selection with no modifier, page-relative; past the page, typed
        trigger()
        rig.type("xy")
        rig.press(KeySyms.KP_0 + 2)
        assertEquals(listOf("two"), rig.commits())
        trigger()
        rig.type("x3")
        assertEquals("x3", rig.preedit)
        rig.press(KeySyms.ESCAPE)
        trigger()
        rig.type("1")
        assertEquals("1", rig.preedit)
        assertEquals(listOf("digitkey"), rig.page)
    }

    @Test
    fun returnCommitsWhatWasTyped() {
        // :175-186
        trigger()
        rig.type("zq")
        rig.press(KeySyms.RETURN)
        assertEquals(listOf("zq"), rig.commits())
        trigger()
        rig.press(KeySyms.KP_ENTER)
        assertEquals(listOf("zq"), rig.commits())
        assertFalse(rig.quickPhrase.isActive(rig.ic))
    }

    @Test
    fun pagesOfFiveUpDownAndTab() {
        trigger()
        rig.type("ab")
        val list = rig.panel.candidates!!
        assertEquals(12, list.totalSize)
        assertEquals(listOf("v1", "v10", "v11", "v12", "v2"), rig.page)
        // Up on the first page before any Down: not taken by the list, types nothing (:121-133)
        val before = rig.log.size
        rig.press(TempKeys.UP)
        assertEquals(0, rig.panel.candidates!!.currentPage)
        assertEquals("ab", rig.preedit)
        assertTrue(rig.log.drop(before).none { it.startsWith("commit") })
        rig.press(TempKeys.DOWN)
        assertEquals(listOf("v3", "v4", "v5", "v6", "v7"), rig.page)
        assertEquals(0, rig.panel.candidates!!.cursorIndex) // ResetToFirst (:349)
        rig.press(TempKeys.DOWN)
        rig.press(TempKeys.DOWN) // no next: used, as Down was used before
        assertEquals(listOf("v8", "v9"), rig.page)
        rig.press(TempKeys.UP)
        rig.press(TempKeys.UP)
        rig.press(TempKeys.UP) // no prev, used before: swallowed
        assertEquals(0, rig.panel.candidates!!.currentPage)
        assertEquals("ab", rig.preedit)
        rig.press(KeySyms.TAB)
        assertEquals(1, rig.panel.candidates!!.cursorIndex)
        rig.press(KeySyms.TAB, KeyStates.SHIFT)
        rig.press(KeySyms.TAB, KeyStates.SHIFT) // wraps to the last
        assertEquals(11, rig.panel.candidates!!.globalCursorIndex)
        assertEquals(2, rig.panel.candidates!!.currentPage)
        rig.press(KeySyms.SPACE)
        assertEquals(listOf("v9"), rig.commits())
    }

    @Test
    fun editingKeys() {
        trigger()
        rig.type("abc")
        rig.press(TempKeys.HOME)
        assertEquals(0, rig.panel.preedit.cursor)
        rig.press(KeySyms.DELETE)
        assertEquals("bc", rig.preedit)
        rig.press(TempKeys.END)
        rig.press(TempKeys.LEFT)
        rig.press(KeySyms.BACKSPACE)
        assertEquals("c", rig.preedit)
        assertEquals(0, rig.panel.preedit.cursor)
        rig.press(TempKeys.RIGHT)
        assertEquals(1, rig.panel.preedit.cursor)
        rig.press(KeySyms.BACKSPACE) // emptied: leaves (:207-213)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        trigger()
        rig.press(KeySyms.BACKSPACE)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        trigger()
        rig.press(KeySyms.DELETE)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        assertEquals(emptyList<String>(), rig.commits())
    }

    @Test
    fun bufferHoldsThirty() {
        trigger()
        rig.type("q".repeat(35))
        assertEquals("q".repeat(30), rig.preedit)
    }

    @Test
    fun modifiedKeysGoBackToTheApplication() {
        trigger()
        val e = rig.press('c'.code, KeyStates.CTRL)
        assertTrue(e.filtered)
        assertFalse(e.accepted)
        assertTrue("back 63" in rig.log)
        assertFalse(rig.release('x'.code).accepted)
        assertTrue(rig.engineKeys == listOf(TempKeys.GRAVE))
    }

    @Test
    fun tappingAListedCandidate() {
        trigger()
        rig.type("ab")
        assertTrue(rig.quickPhrase.selectCandidate(rig.ic, 11))
        assertEquals(listOf("v9"), rig.commits())
        assertFalse(rig.quickPhrase.selectCandidate(rig.ic, 0))
    }

    @Test
    fun clientPreeditIsUnderlinedAndTapsMoveTheCursor() {
        rig.ic.capabilities = Capability.PREEDIT
        trigger()
        rig.type("abc")
        assertEquals("abc", rig.panel.clientPreedit.toString())
        assertEquals(TextFormatFlag.Underline.flag, rig.panel.clientPreedit.flags[0])
        assertTrue(rig.panel.preedit.isEmpty())
        rig.pipeline.invokeAction(rig.ic, InvokeActionEvent(InvokeActionEvent.Action.LeftClick, 1))
        assertEquals(1, rig.panel.clientPreedit.cursor)
        rig.pipeline.invokeAction(rig.ic, InvokeActionEvent(InvokeActionEvent.Action.LeftClick, 9))
        assertEquals(3, rig.panel.clientPreedit.cursor) // capped (fcitx leaves it past the end)
        rig.pipeline.invokeAction(rig.ic, InvokeActionEvent(InvokeActionEvent.Action.RightClick, 1))
        assertFalse(rig.quickPhrase.isActive(rig.ic))
    }

    @Test
    fun aTapWithoutClientPreeditLeaves() {
        // :331-343
        trigger()
        rig.type("ab")
        val e = rig.pipeline.invokeAction(rig.ic, InvokeActionEvent(InvokeActionEvent.Action.LeftClick, 1))
        assertTrue(e.accepted)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
    }

    @Test
    fun triggerWithTextPrefixStrAndAlt() {
        // :289-304; :175-180 alt on Return; :246-251 str on its key; aux text until typed
        rig.quickPhrase.trigger(rig.ic, text = "hint", str = "S", alt = "ALT", key = Key('k'.code))
        assertEquals("Quick Phrase: hint", rig.aux)
        rig.press(KeySyms.RETURN)
        rig.quickPhrase.trigger(rig.ic, str = "S", alt = "ALT", key = Key('k'.code))
        rig.press('k'.code)
        rig.quickPhrase.trigger(rig.ic, text = "hint", prefix = "P")
        rig.type("x")
        assertEquals("Quick Phrase: ", rig.aux)
        assertEquals("Px", rig.preedit)
        assertEquals(2, rig.panel.preedit.cursor)
        rig.press(KeySyms.RETURN)
        assertEquals(listOf("ALT", "S", "Px"), rig.commits())
    }

    @Test
    fun leavesOnResetFocusOutAndSwitch() {
        trigger()
        rig.pipeline.reset(rig.ic)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        trigger()
        rig.pipeline.switchedInputMethod(rig.ic)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        trigger()
        rig.pipeline.focusOut(rig.ic)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        assertNull(rig.panel.candidates)
    }

    @Test
    fun reloadReplacesTheTable() {
        fun table() = QuickPhraseTable.load(listOf(Mem(mapOf("data/QuickPhrase.mb" to "xy new"))))
        fun replaced(reload: (QuickPhrase) -> Unit) {
            val rig = TempModeRig(table, { null })
            reload(rig.quickPhrase)
            rig.press(TempKeys.GRAVE, KeyStates.SUPER)
            rig.type("xy")
            assertEquals(listOf("new"), rig.page)
        }
        assertRows(
            "a table" to { replaced { it.reload(table()) } },
            // reloadConfig hands a table read later, off the host thread
            "a lazy table (the app's path, F4037/F4098)" to { val t = table(); replaced { it.reload { t } } },
        )
    }
}
