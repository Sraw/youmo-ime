/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Clipboard
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.CursorMove
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Emoji
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Habits
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

    // 061-handwriting-toolbar Q2: pinned by default; where it does not write (English, a password) greyed in its
    // place, not left out, so nothing moves as 中/英 switch (the user's call, 2026-11-15)
    @Test
    @Uat("TB-55", "TB-67")
    fun handwritingIsPinnedByDefaultAndGreyedWhereItDoesNotWrite() {
        val none = everything.copy(handwriting = false)
        assertEquals(listOf(Emoji, Handwriting, Voice, Hide), ToolbarConfig.DEFAULT.layout(none).pinned)
        assertEquals(setOf(Handwriting), ToolbarConfig.DEFAULT.layout(none).unavailable)
        assertEquals(emptySet<ToolbarButton>(), ToolbarConfig.DEFAULT.layout(everything).unavailable)
        // the same slots, the same buttons in them, in English as in 中文, at any width
        for (row in listOf(715, 825, 1080, 2072)) {
            assertEquals(row.toString(), ToolbarConfig.DEFAULT.layout(everything).slots(row, 110), ToolbarConfig.DEFAULT.layout(none).slots(row, 110))
        }
        // on the toolbar the same
        val onToolbar = ToolbarConfig.parse("undo,handwriting,|,emoji")
        assertEquals(listOf(Undo, Handwriting, More), onToolbar.layout(none).toolbar)
        // a bar the user set before 0.6.1: it comes in hidden, before the divider
        val old = ToolbarConfig.parse("undo,|,emoji,voice,hide")
        assertEquals(Item.Button(Handwriting, false), old.items[old.items.indexOf(Item.Divider) - 1])
    }

    // FB11 (the user's call, 2026-11-15): the toolbar with nothing to show here, the menu key is ⋯ itself
    @Test
    @Uat("TB-69")
    fun aToolbarWithNothingToShowIsMoreAlone() {
        // the default has some
        assertFalse(ToolbarConfig.DEFAULT.layout(everything).moreOnly)
        // none ticked; none before the divider
        assertTrue(ToolbarConfig.parse("-undo,-redo,-cursor_move,-clipboard,|,emoji,voice,hide").layout(everything).moreOnly)
        assertTrue(ToolbarConfig.parse("|,undo,emoji,voice,hide").layout(everything).moreOnly)
        // what is ticked of no use here: by the situation (簡繁 without chttrans, 单手 floating)
        val situational = ToolbarConfig.parse("trad_simp,one_handed,|,emoji")
        assertTrue(situational.layout(everything.copy(tradSimp = false, oneHanded = false)).moreOnly)
        assertFalse(situational.layout(everything.copy(tradSimp = false)).moreOnly)
        // a greyed 手写 holds its slot: English does not merge them
        val handwriting = ToolbarConfig.parse("handwriting,|,emoji,voice,hide")
        assertFalse(handwriting.layout(everything.copy(handwriting = false)).moreOnly)
        // more pinned than room: still more alone, the right's all there
        val crowded = ToolbarConfig.parse("|,emoji,handwriting,voice,hide,theme,floating,habits")
        val layout = crowded.layout(everything)
        assertTrue(layout.moreOnly)
        assertEquals(listOf(More), layout.slots(715, 110).toolbar)
        assertEquals(emptyList<ToolbarButton>(), layout.slots(715, 110).overflow)
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

    // every one at the right stays there however narrow the bar (the user's call, 2026-11-15): none goes onto the toolbar
    @Test
    @Uat("TB-57")
    fun everyPinnedOneStaysAtTheRight() {
        val config = ToolbarConfig.parse("undo,|,emoji,voice,hide,theme")
        assertEquals(Layout(listOf(Undo, More), listOf(Emoji, Voice, Hide, Theme)), config.layout(everything))
    }

    // the bar split into slots of one width (the user's call, 2026-11-15): as many as fit at about a button's own, the
    // toolbar open or shut alike; the toolbar's that have no slot in ⋯'s panel, none scrolled; the right's all kept
    @Test
    @Uat("TB-02", "TB-22")
    fun theBarIsSplitIntoEqualSlotsTheToolbarsOverflowInMore() {
        val layout = Layout(listOf(Undo, Redo, CursorMove, Clipboard, More), listOf(Emoji, Handwriting, Voice, Hide))
        // 393 dp at 2.75 px a dp, 40 dp a button: ten slots, everything fits, nothing in ⋯
        assertEquals(ToolbarConfig.Slots(10, 108, layout.toolbar, emptyList()), layout.slots(1080, 110))
        // the slots as many and as wide however many the toolbar has: shut or open the same
        val fewer = Layout(listOf(Undo, More), layout.pinned)
        assertEquals(layout.slots(1080, 110).cellPx, fewer.slots(1080, 110).cellPx)
        assertEquals(layout.slots(1080, 110).count, fewer.slots(1080, 110).count)
        // landscape, 753 dp: nineteen
        assertEquals(19, layout.slots(2072, 110).count)
        assertEquals(109, layout.slots(2072, 110).cellPx)
        // the floating keyboard at 300 dp: eight, three in the middle, so undo and redo, then ⋯; the rest in its panel
        assertEquals(ToolbarConfig.Slots(8, 103, listOf(Undo, Redo, More), listOf(CursorMove, Clipboard)), layout.slots(825, 110))
        // at its least, 260 dp: seven, the right's four all kept
        assertEquals(ToolbarConfig.Slots(7, 102, listOf(Undo, More), listOf(Redo, CursorMove, Clipboard)), layout.slots(715, 110))
        // a larger 候选字号 (56 dp a button) on 393 dp: seven, narrower than a button's own
        assertEquals(7, layout.slots(1080, 154).count)
        // narrower still: never fewer than the menu key, two between and the right's, the slots narrower than the least
        val many = Layout(listOf(More), listOf(Emoji, Handwriting, Voice, Hide, Undo, Redo, CursorMove, Clipboard, Theme, Habits))
        assertEquals(ToolbarConfig.Slots(13, 55, listOf(More), emptyList()), many.slots(715, 110))
        assertEquals("not laid out yet", ToolbarConfig.Slots(0, 110, layout.toolbar, emptyList()), layout.slots(0, 110))
        // the slots fill the bar, what is left over less than a pixel a slot: each as wide as it can be (R26 F8471; R26
        // F8260: the slots' x, open or shut, are the view's, not Slots'; this checks only the widths)
        val s = layout.slots(1080, 110)
        assertTrue("all fit", s.count * s.cellPx <= 1080 && 1080 - s.count * s.cellPx < s.count)
    }

    // 手写 shown at the right after 表情, where a new install has it, in a bar arranged before it was there to choose: 0.6.0's
    // (none in it), or the first test build's written unticked before the divider as parse put it, the first time only;
    // one the user took off since (unticked at the right, or moved onto the toolbar) left so
    @Test
    @Uat("TB-66")
    fun aBarArrangedBeforeHandwritingGetsItAfterTheEmoji() {
        val zeroSix = "undo,redo,cursor_move,clipboard,-quick_input,|,emoji,voice,hide"
        assertTrue(ToolbarConfig.handwritingNeverOffered(zeroSix, firstLook = false))
        assertEquals(
            listOf(Emoji, Handwriting, Voice, Hide),
            ToolbarConfig.parse(zeroSix).withHandwritingOffered().layout(everything).pinned,
        )
        assertEquals("no 表情 at the right: first there", listOf(Handwriting, Voice),
            ToolbarConfig.parse("undo,emoji,|,voice").withHandwritingOffered().layout(everything).pinned)
        assertEquals("no divider", listOf(Handwriting), ToolbarConfig(listOf(Item.Button(Undo, true))).withHandwritingOffered().layout(everything).pinned)
        val beta = ToolbarConfig.parse(zeroSix).serialize()
        assertTrue("$beta: the first test build's, first look", ToolbarConfig.handwritingNeverOffered(beta, firstLook = true))
        assertFalse("$beta: looked at once already", ToolbarConfig.handwritingNeverOffered(beta, firstLook = false))
        for (taken in listOf("undo,|,emoji,-handwriting,voice", "undo,handwriting,|,emoji,voice", "undo,|,emoji,voice,-handwriting")) {
            assertFalse(taken, ToolbarConfig.handwritingNeverOffered(taken, firstLook = true))
        }
        assertFalse("never arranged: the default has it", ToolbarConfig.handwritingNeverOffered("", firstLook = true))
        val offered = ToolbarConfig.parse(zeroSix).withHandwritingOffered().serialize()
        assertFalse("once offered, left as it is", ToolbarConfig.handwritingNeverOffered(offered, firstLook = true))
    }
}
