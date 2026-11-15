/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.scene.Icons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 061-toolbar-icons §2.3: every icon of the bar and the panels has a theme's name, one name to one thing done. */
class BarIconsTest {

    private val all = (BarIcons.BAR + BarIcons.PANELS).distinct()

    // a button added to the toolbar without a name is red here (the `when` of BarIcons.of makes it a compile error too)
    @Uat("ST-128")
    @Test
    fun everyToolbarButtonAndTheMenuKeyHaveANameAThemeMayWrite() {
        for (b in ToolbarButton.entries) assertTrue("$b: ${BarIcons.of(b).name}", BarIcons.of(b).name in Icons.ALL)
        for (open in listOf(true, false)) assertTrue(BarIcons.menu(open).name in Icons.ALL)
        assertEquals(listOf("toolbar-collapse", "toolbar-expand"), listOf(BarIcons.menu(true).name, BarIcons.menu(false).name))
        assertEquals(listOf("trad-simp", "trad-simp-off"), listOf(BarIcons.tradSimp(true).name, BarIcons.tradSimp(false).name))
        for (i in all) assertTrue(i.name, i.name in Icons.ALL)
        assertTrue(BarIcons.BAR.containsAll(ToolbarButton.entries.map(BarIcons::of)))
        // the buttons beside the keys too (the user's 凡是画这个动作图标的地方): read ahead with the bar
        assertTrue(BarIcons.BAR.containsAll(listOf(BarIcons.ONE_HANDED_EXIT, BarIcons.TOUCH_KEYBOARD, BarIcons.CANDIDATES_PREV, BarIcons.CANDIDATES_NEXT)))
        assertEquals(listOf("one-handed-exit", "touch-keyboard", "candidates-prev", "candidates-next"),
            listOf(BarIcons.ONE_HANDED_EXIT, BarIcons.TOUCH_KEYBOARD, BarIcons.CANDIDATES_PREV, BarIcons.CANDIDATES_NEXT).map { it.name })
    }

    // not the preference's key made into a name: the stored values are never renamed, the names are a theme's
    @Test
    fun theNamesAreWrittenNotMadeOfTheStoredKeys() {
        assertEquals("cursor-move", BarIcons.of(ToolbarButton.CursorMove).name)
        assertEquals("cursor_move", ToolbarButton.CursorMove.key)
        assertEquals("hide-keyboard", BarIcons.of(ToolbarButton.Hide).name)
        assertEquals("choose-keyboard", BarIcons.of(ToolbarButton.Keyboard).name)
        assertEquals("quick-input", BarIcons.of(ToolbarButton.QuickPhrase).name)
        assertEquals("habits-off", BarIcons.of(ToolbarButton.Habits).name)
    }

    // the bar shares a name with a shortcut's command only where it does that command's job (§2.2), and no name
    // stands for two images of its own here (one thing done)
    @Uat("ST-129")
    @Test
    fun aNameIsOneThingDone() {
        val shared = all.map { it.name }.filter { it in Icons.COMMANDS }.toSet()
        assertEquals(
            setOf("undo", "redo", "cursor-move", "clipboard", "quick-input", "emoji", "voice", "hide-keyboard", "choose-keyboard", "trad-simp"),
            shared,
        )
        for ((name, icons) in all.groupBy { it.name }) assertEquals(name, 1, icons.map { it.fallback }.distinct().size)
        // the bar's own are no key's nor command's
        assertTrue(Icons.TOOLBAR.none { it in Icons.COMMANDS || it in Icons.KEYS })
        assertTrue(Icons.PANEL.none { it in Icons.COMMANDS || it in Icons.KEYS })
        assertEquals((Icons.TOOLBAR + Icons.PANEL).toSet(), all.map { it.name }.toSet() - Icons.COMMANDS.toSet() - Icons.KEYS.toSet())
    }

    // the engine's toggles on and off each a name of its own; unikey's and names unknown none (their app images stay)
    @Uat("ST-132")
    @Test
    fun theEnginesTogglesHaveANamePerState() {
        assertEquals(8, BarIcons.ENGINE_ICONS.size)
        val names = BarIcons.ENGINE_ICONS.map { BarIcons.engine(it)!!.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals("trad-simp", BarIcons.engine("fcitx-chttrans-active")!!.name)
        assertEquals("trad-simp-off", BarIcons.engine("fcitx-chttrans-inactive")!!.name)
        assertEquals("punctuation-off", BarIcons.engine("fcitx-punc-inactive")!!.name)
        for (other in listOf("document-edit", "character-set", "edit-find", "", "fcitx-x-active")) assertNull(BarIcons.engine(other))
    }
}
