/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Clipboard
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.CursorMove
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Emoji
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Handwriting
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Hide
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Keyboard
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.More
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.OneHanded
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Redo
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Theme
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.TradSimp
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Undo
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Voice
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig.Item
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig.Layout
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig.Situation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarConfigTest {

    private val everything = Situation(voice = true, tradSimp = true, oneHanded = true, keyboards = true)

    @Test
    fun neverSetTheBarIsZeroFoursBar() {
        assertEquals(ToolbarConfig.DEFAULT, ToolbarConfig.parse(""))
        assertEquals(
            Layout(listOf(Undo, Redo, CursorMove, Clipboard, More), listOf(Emoji, Handwriting, Voice, Hide)),
            ToolbarConfig.DEFAULT.layout(everything)
        )
    }

    // 061-handwriting-toolbar Q2, Q3: pinned by default, left out where it does not write (English, a password)
    @Test
    @Uat("TB-55")
    fun handwritingIsPinnedByDefaultAndLeftOutWhereItDoesNotWrite() {
        val none = everything.copy(handwriting = false)
        assertEquals(listOf(Emoji, Voice, Hide), ToolbarConfig.DEFAULT.layout(none).pinned)
        // a bar the user set before 0.6.1: it comes in hidden, before the divider
        val old = ToolbarConfig.parse("undo,|,emoji,voice,hide")
        assertEquals(Item.Button(Handwriting, false), old.items[old.items.indexOf(Item.Divider) - 1])
    }

    // 061-handwriting-toolbar §6.3: pinned for one who wrote with 0.6.0's handwriting, once, the user's order kept
    @Test
    fun pinningHandwritingPutsItFirstAfterTheDivider() {
        val old = ToolbarConfig.parse("undo,-redo,|,voice,hide")
        val pinned = old.withHandwritingPinned()
        assertEquals(Layout(listOf(Undo, More), listOf(Handwriting, Voice, Hide)), pinned.layout(everything))
        // shown already and elsewhere: moved, never twice
        val twice = ToolbarConfig.parse("handwriting,undo,|,voice").withHandwritingPinned()
        assertEquals(1, twice.items.count { it is Item.Button && it.button == Handwriting })
        assertEquals(Layout(listOf(Undo, More), listOf(Handwriting, Voice)), twice.layout(everything))
        assertEquals(pinned, ToolbarConfig.parse(pinned.serialize()))
    }

    // one made with no divider (parse always puts one in): 手写 pinned at the right alone, the rest on the toolbar
    @Test
    fun pinningHandwritingWithNoDividerPutsOneBeforeIt() {
        val pinned = ToolbarConfig(listOf(Item.Button(Undo, true), Item.Button(Handwriting, true))).withHandwritingPinned()
        assertEquals(listOf(Item.Button(Undo, true), Item.Divider, Item.Button(Handwriting, true)), pinned.items)
        assertEquals(Layout(listOf(Undo, More), listOf(Handwriting)), pinned.layout(everything))
    }

    @Test
    fun everyButtonOnceInTheDefault() {
        val buttons = ToolbarConfig.DEFAULT.items.filterIsInstance<Item.Button>().map { it.button }
        assertEquals(ToolbarButton.settable.toSet(), buttons.toSet())
        assertEquals(ToolbarButton.settable.size, buttons.size)
    }

    @Test
    fun serializeRoundTrips() {
        val config = ToolbarConfig.parse("theme,-undo,|,hide,-emoji")
        assertEquals(config, ToolbarConfig.parse(config.serialize()))
        assertEquals(ToolbarConfig.DEFAULT, ToolbarConfig.parse(ToolbarConfig.DEFAULT.serialize()))
    }

    @Test
    fun orderAndVisibilityAreTheUsers() {
        val config = ToolbarConfig.parse("theme,-undo,clipboard,|,hide,-emoji,trad_simp")
        assertEquals(Layout(listOf(Theme, Clipboard, More), listOf(Hide, TradSimp)), config.layout(everything))
    }

    @Test
    fun unknownAndRepeatedKeysAreDroppedMissingOnesComeInHiddenBeforeTheDivider() {
        val config = ToolbarConfig.parse("from_a_later_version,undo,undo,-undo,|,hide")
        val items = config.items
        assertEquals(Item.Button(Undo, true), items.first())
        assertEquals(Item.Button(Hide, true), items.last())
        assertEquals(Item.Divider, items[items.size - 2])
        assertEquals(ToolbarButton.settable.size + 1, items.size)
        assertEquals(Layout(listOf(Undo, More), listOf(Hide)), config.layout(everything))
    }

    @Test
    fun noDividerEverythingIsOnTheToolbar() {
        val config = ToolbarConfig.parse("emoji,undo")
        assertEquals(Layout(listOf(Emoji, Undo, More), emptyList()), config.layout(everything))
        // a list made without one, the same
        assertEquals(Layout(listOf(Undo, More), emptyList()), ToolbarConfig(listOf(Item.Button(Undo, true))).layout(everything))
    }

    @Test
    fun buttonsOfNoUseInTheSituationAreLeftOut() {
        val config = ToolbarConfig.parse("trad_simp,one_handed,undo,|,voice,hide")
        assertEquals(
            Layout(listOf(Undo, More), listOf(Hide)),
            config.layout(Situation(voice = false, tradSimp = false, oneHanded = false, keyboards = false))
        )
        assertEquals(
            Layout(listOf(TradSimp, OneHanded, Undo, More), listOf(Voice, Hide)),
            config.layout(everything)
        )
    }

    @Test
    fun theKeyboardButtonComesInHiddenOnAConfigWithoutItOnlyWhereThereIsAChoice() {
        // stored before it existed: parsed with it unpinned and hidden, nothing to migrate
        val old = ToolbarConfig.parse("undo,|,hide")
        assertEquals(Item.Button(Keyboard, false), old.items.single { it is Item.Button && it.button == Keyboard })
        assertEquals(Layout(listOf(Undo, More), listOf(Hide)), old.layout(everything))
        val config = ToolbarConfig.parse("keyboard,undo,|,hide")
        assertEquals(Layout(listOf(Keyboard, Undo, More), listOf(Hide)), config.layout(everything))
        assertEquals(Layout(listOf(Undo, More), listOf(Hide)), config.layout(everything.copy(keyboards = false)))
    }

    @Test
    fun quickInputIsNotOnTheBarTillTickedNeverArrangedNorArranged() {
        // the user's call (2026-11-02): one to add, on neither build's bar by default, a bar never
        // arranged (new or upgraded) nor one arranged before 0.5.1
        fun quick(c: ToolbarConfig) = c.items.single { it is Item.Button && it.button == ToolbarButton.QuickPhrase }
        assertEquals(Item.Button(ToolbarButton.QuickPhrase, false), quick(ToolbarConfig.parse("")))
        assertFalse(ToolbarButton.QuickPhrase in ToolbarConfig.parse("").layout(everything).toolbar)
        val arranged = ToolbarConfig.parse("undo,redo,cursor_move,clipboard,|,emoji,voice,hide")
        assertEquals(Item.Button(ToolbarButton.QuickPhrase, false), quick(arranged))
        // ticked, it is on the toolbar where it stands in the list
        val ticked = ToolbarConfig.parse("undo,redo,cursor_move,clipboard,quick_input,|,emoji,voice,hide")
        assertEquals(listOf(Undo, Redo, CursorMove, Clipboard, ToolbarButton.QuickPhrase, More), ticked.layout(everything).toolbar)
        assertEquals(ToolbarButton.QuickPhrase, ToolbarButton.ofKey("quick_input"))
    }

    @Test
    fun theMoreButtonIsFixedWhateverWasStored() {
        for (raw in listOf("-more,undo,|,hide", "undo,|,more,hide", "undo,|,hide")) {
            val config = ToolbarConfig.parse(raw)
            assertEquals(raw, false, config.items.any { it is Item.Button && it.button == More })
            assertEquals(raw, Layout(listOf(Undo, More), listOf(Hide)), config.layout(everything))
        }
    }

    @Test
    @Uat("TB-57")
    fun pinnedOnesWithNoRoomGoOntoTheToolbarInOrderBeforeTheMoreButton() {
        val config = ToolbarConfig.parse("undo,|,emoji,voice,hide,theme")
        assertEquals(Layout(listOf(Undo, More), listOf(Emoji, Voice, Hide, Theme)), config.layout(everything, 4))
        assertEquals(Layout(listOf(Undo, Hide, Theme, More), listOf(Emoji, Voice)), config.layout(everything, 2))
        assertEquals(Layout(listOf(Undo, Emoji, Voice, Hide, Theme, More), emptyList()), config.layout(everything, 0))
        assertEquals(Layout(listOf(Undo, Emoji, Voice, Hide, Theme, More), emptyList()), config.layout(everything, -1))
    }
}
