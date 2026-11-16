/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.res.Configuration
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.user.RareChoice.Change
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.cards.CardColumns
import org.fcitx.fcitx5.android.ui.main.settings.LetterScroller
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsFragment
import org.fcitx.fcitx5.android.ui.main.settings.cards.GroupCard
import org.fcitx.fcitx5.android.ui.rare.RareLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import splitties.dimensions.dp
import java.time.Duration
import java.util.Locale

/** 生僻字屏蔽's cards (dev/design/061-settings-cards.md §2.4, §2.8, §3.2): ↶, each group's place, what is read out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class RareCharsCardsUatTest : RarePageUat() {

    // 061-settings-cards.md §2.4: the title bar's ↶ takes back the last step, the snackbar long gone; a save clears it
    @Test
    fun theTitleBarsUndoTakesBackTheLastStepTillASave() {
        RareCharsManager.update(listOf(Change.On(true), Change.One(0x9F98, true)), TestCharLevels.levels)
        openPage()
        assertRows(
            "nothing done yet: ↶ grayed" to { assertFalse(menuItem(R.string.undo).isEnabled) },
            "some held → all: 「已屏蔽 6/6」" to {
                assertEquals(MaterialCheckBox.STATE_INDETERMINATE, strip(R.string.rare_group_out).state)
                strip(R.string.rare_group_out).performClick()
                idle()
                assertEquals(s(R.string.rare_chars_card_held, "6", "6"), strip(R.string.rare_group_out).count.text.toString())
            },
            "the snackbar gone, ↶ takes the picked one back: some again, 龘 alone, ↶ grayed" to {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
                assertFalse(shown().contains(s(R.string.rare_chars_changed_held, "5")))
                tapUndo()
                assertEquals(MaterialCheckBox.STATE_INDETERMINATE, strip(R.string.rare_group_out).state)
                assertEquals(s(R.string.rare_chars_card_held, "1", "6"), strip(R.string.rare_group_out).count.text.toString())
                assertFalse(menuItem(R.string.undo).isEnabled)
            },
            "one cell is a step too" to {
                openGroup(R.string.rare_group_out)
                cell("炟").performClick()
                idle()
                assertTrue(cell("炟").held)
                tapUndo()
                assertFalse(cell("炟").held)
                assertTrue(cell("龘").held)
            },
            "a save as the page stops: ↶ grayed, nothing to take back" to {
                cell("炟").performClick()
                idle()
                assertTrue(menuItem(R.string.undo).isEnabled)
                st.leaveAndReturn()
                assertFalse(menuItem(R.string.undo).isEnabled)
                assertTrue(cp("炟") in file().block)
            },
            "the switch takes ↶ and the snackbar's 撤销 away: neither turns it back on with the step before" to {
                back()
                strip(R.string.rare_group_out).performClick()
                idle()
                assertTrue(menuItem(R.string.undo).isEnabled)
                snackbarUndo()
                switch().isChecked = false
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                assertFalse(menuItem(R.string.undo).isEnabled)
                assertTrue(views().none { it is TextView && it.text.toString() == s(R.string.undo) && it.hasOnClickListeners() })
                assertFalse(switch().isChecked)
            },
        )
    }

    // §3.2: each group's grid where it was left, over the cards and a turn; back to the cards from the up arrow too
    @Test
    fun eachGroupKeepsWhereItWasScrolledAndATurnKeepsTheGroup() {
        api.rareGridAnswer = many
        openPage()
        openGroup(R.string.rare_group_out)
        val manager = list().layoutManager as GridLayoutManager
        list().scrollBy(0, list().height * 2)
        idle()
        val at = manager.findFirstVisibleItemPosition()
        assertRows(
            "scrolled down" to { assertTrue("$at", at > 10) },
            "another group and back: where it was" to {
                back()
                openGroup(R.string.rare_group_l3)
                assertEquals(0, (list().layoutManager as GridLayoutManager).findFirstVisibleItemPosition())
                back()
                openGroup(R.string.rare_group_out)
                assertEquals(at, (list().layoutManager as GridLayoutManager).findFirstVisibleItemPosition())
            },
            "a turn: still in the group, its title, where it was" to {
                st.recreate()
                until("the grid again") { cells().isNotEmpty() }
                assertEquals(s(R.string.rare_group_out), title())
                assertEquals(at, (list().layoutManager as GridLayoutManager).findFirstVisibleItemPosition())
            },
            "the up arrow: the cards, not the page left" to {
                activity.onSupportNavigateUp()
                idle()
                assertEquals(4, cards().size)
                assertTrue(st.page is RareCharsFragment)
            },
        )
    }

    // §2.6, §3.1: a card's description two lines at most, never cut, in each language: four across a phone on its side
    // (the page 600 dp wide), and two across one upright at the font 1.3 times
    @Test
    fun eachGroupsDescriptionFitsTwoLinesUncut() {
        val app = RuntimeEnvironment.getApplication()
        val cut = ArrayList<String>()
        for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE)) {
            for ((screen, scale) in listOf(851 to 1f, 393 to 1.3f)) {
                val config = Configuration(app.resources.configuration).apply {
                    setLocale(locale)
                    fontScale = scale
                }
                val ctx = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_FcitxAppTheme)
                val width = minOf(screen, 600).toFloat()
                val across = CardColumns.columns(width, scale, CardColumns.GROUP_MIN_DP, CardColumns.GROUP)
                assertEquals("$screen dp", if (screen > 600) 4 else 2, across)
                val px = ctx.dp(CardColumns.cardWidth(width, across)).toInt()
                for (level in RareLayout.ORDER) {
                    val about = ctx.getString(RareCharsFragment.groupAbout(level))
                    val card = GroupCard(ctx).apply { bind("", about, emptyList(), "", "") }
                    card.view.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    card.view.layout(0, 0, card.view.measuredWidth, card.view.measuredHeight)
                    // the size the font scale makes it, so the narrow case is the narrow one
                    assertEquals(ctx.resources.displayMetrics.density * 12 * scale, card.about.textSize, 1f)
                    val lines = card.about.layout
                    if (lines.lineCount > 2 || lines.getEllipsisCount(lines.lineCount - 1) > 0) cut += "$locale $screen dp × $scale: $about"
                }
            }
        }
        assertEquals(emptyList<String>(), cut)
    }

    // 061-settings-cards.md §2.8: a card two nodes read one after the other, the cards a list; the letters as an adjustable (review N12)
    @Test
    fun theCardsAndTheLettersAreReadOut() {
        openPage()
        fun node(v: View) = AccessibilityNodeInfo.obtain().also { v.onInitializeAccessibilityNodeInfo(it) }
        assertRows(
            "a card's body: a button, its group, all and held, a tap read as looking in; what is in it not read apart" to {
                val body = body(R.string.rare_group_ext)
                val info = node(body)
                assertEquals(Button::class.java.name, info.className)
                // held by default, off or on
                assertEquals(s(R.string.rare_chars_card_a11y, s(R.string.rare_group_ext), "1", "1"), info.contentDescription.toString())
                assertEquals(s(R.string.rare_chars_card_open), info.actionList.single { it.id == AccessibilityNodeInfo.ACTION_CLICK }.label)
                assertTrue(KeyboardHarness.all(body).drop(1).all { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO || it !is TextView })
            },
            "its strip: a checkbox, read after the body, saying the group's state" to {
                val strip = strip(R.string.rare_group_ext)
                val info = node(strip)
                assertEquals(CheckBox::class.java.name, info.className)
                assertEquals(s(R.string.rare_chars_group_a11y, s(R.string.rare_group_ext), "1", s(R.string.rare_chars_box_all)), info.contentDescription.toString())
                assertEquals(body(R.string.rare_group_ext).id, strip.accessibilityTraversalAfter)
                assertEquals("the next card's body after this strip", strip.id, body(R.string.rare_group_l3).accessibilityTraversalAfter)
            },
            "the cards a list of four, each body its place in it" to {
                val grid = cards().first().parent as View
                assertEquals(4, node(grid).collectionInfo.rowCount)
                assertEquals(1, node(body(R.string.rare_group_l3)).collectionItemInfo.rowIndex)
            },
            "a group's grid: the letters there" to {
                openGroup(R.string.rare_group_l3)
                assertEquals(listOf('b', 'z'), views().filterIsInstance<LetterScroller>().single().letters)
            },
            "the letters read as an adjustable, a swipe the next letter (review N12)" to {
                val letters = views().filterIsInstance<LetterScroller>().single()
                fun at(): Int {
                    val info = AccessibilityNodeInfo.obtain()
                    letters.onInitializeAccessibilityNodeInfo(info)
                    assertEquals(SeekBar::class.java.name, info.className)
                    return info.rangeInfo.current.toInt()
                }
                assertEquals(0, at())
                assertTrue(letters.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                idle()
                assertEquals(1, at())
                assertTrue(letters.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
                idle()
                assertEquals(0, at())
            },
            "the letter it is at read out, the next once gone to" to {
                val letters = views().filterIsInstance<LetterScroller>().single()
                val first = letters.letters.first().uppercase()
                assertEquals(first, ViewCompat.getStateDescription(letters)?.toString())
                assertTrue(letters.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                idle()
                assertEquals(letters.letters[1].uppercase(), ViewCompat.getStateDescription(letters)?.toString())
            },
        )
    }
}
