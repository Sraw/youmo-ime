/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.user.RareChoice.Change
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.settings.LetterScroller
import org.fcitx.fcitx5.android.ui.main.settings.RareCharCell
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsFragment
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsViewModel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.cards.CheckStrip
import org.fcitx.fcitx5.android.ui.main.settings.rareSummary
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
import org.robolectric.shadows.ShadowDialog
import splitties.dimensions.dp
import java.io.File
import java.time.Duration

/**
 * 生僻字屏蔽's page (dev/design/061-rare-char-block.md §6; 061-settings-cards.md §3) and the marks on the
 * user's words (§5.4).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class RareCharsPageUatTest : RarePageUat() {

    @Test
    @Uat("WD-60")
    fun fourCardsAndOffOnlyToLookAt() {
        st.open(SettingsRoute.PinyinDict(""))
        until("关 on the words page") { rareRow()?.summary == s(R.string.rare_chars_off) }
        openPage()
        assertRows(
            "four cards, 扩展区 first (held by default), each its name, what it holds and a few of its characters" to {
                val names = listOf(R.string.rare_group_ext, R.string.rare_group_l3, R.string.rare_group_trad, R.string.rare_group_out)
                assertEquals(names.map { s(it) }, cards().map { card -> KeyboardHarness.all(card).filterIsInstance<TextView>().first().text.toString() })
                assertTrue(shown().toString(), s(R.string.rare_group_l3_about) in shown())
                assertTrue(shown().toString(), "犇 堃 喆 淼 垚 芃" in shown())
                assertEquals(listOf("view:rare/group/EXT", "view:rare/group/L3", "view:rare/group/TRAD", "view:rare/group/OUT"), cards().map { it.tag })
            },
            "no cell, no letters on the cards; the title the page's" to {
                assertEquals(0, fragment().shownLayout.size)
                assertTrue(cells().isEmpty())
                assertTrue(views().none { it is LetterScroller })
                assertEquals(s(R.string.rare_chars), title())
            },
            "the strips count each group's held out of all; off, 扩展区's is what turning on holds" to {
                assertEquals(s(R.string.rare_chars_card_held, "1", "1"), strip(R.string.rare_group_ext).count.text.toString())
                assertEquals(s(R.string.rare_chars_card_held, "0", "6"), strip(R.string.rare_group_out).count.text.toString())
                assertEquals(MaterialCheckBox.STATE_CHECKED, strip(R.string.rare_group_ext).state)
            },
            "off: only the strips faded and inert, the cards' bodies as they are and open" to {
                assertFalse(switch().isChecked)
                val strip = strip(R.string.rare_group_trad)
                assertEquals(0.5f, strip.alpha)
                assertFalse(strip.isEnabled)
                strip.performClick()
                idle()
                assertEquals(MaterialCheckBox.STATE_UNCHECKED, strip(R.string.rare_group_trad).state)
                assertTrue(cards().all { it.alpha == 1f })
                assertTrue(body(R.string.rare_group_trad).isEnabled)
            },
            "off, a group opened: its strip faded, said to be only to look at, a tap changes nothing" to {
                openGroup(R.string.rare_group_out)
                assertTrue(shown().toString(), s(R.string.rare_chars_view_only) in shown().joinToString("\n"))
                assertFalse(strip(R.string.rare_group_out).isEnabled)
                cell("龘").performClick()
                idle()
                assertFalse(cell("龘").held)
                assertFalse(cell("龘").isEnabled)
            },
            "a group's grid: its title, its cells alone, its letters; what the phone cannot draw said over it" to {
                assertEquals(s(R.string.rare_group_out), title())
                assertEquals(setOf("炟", "荅", "畣", "鎉", "龘"), cells().keys)
                assertFalse("龗 not drawn here", "龗" in cells())
                assertEquals(listOf('d'), views().filterIsInstance<LetterScroller>().single().letters)
                assertTrue(shown().toString(), s(R.string.rare_chars_unshown, "1") in shown().joinToString("\n"))
                assertTrue(views().none { it is MaterialCardView && it.tag != null })
            },
            "back: the cards again, still on the page; 扩展区's grid says what has no reading" to {
                back()
                assertEquals(4, cards().size)
                assertTrue(st.page is RareCharsFragment)
                assertEquals(s(R.string.rare_chars), title())
                openGroup(R.string.rare_group_ext)
                assertTrue(shown().toString(), s(R.string.rare_chars_unread, "1") in shown().joinToString("\n"))
                back()
            },
        )
    }

    @Test
    @Uat("WD-52", "AX-17")
    fun thePageHoldsBackAndLetsThrough() {
        openPage()
        assertRows(
            "on: 扩展区 held, counted without what has no reading; the strips usable" to {
                switch().isChecked = true
                idle()
                assertEquals(s(R.string.rare_chars_note, s(R.string.rare_chars_held, "1")), note())
                assertTrue(strip(R.string.rare_group_out).isEnabled)
                assertEquals(1f, strip(R.string.rare_group_out).alpha)
            },
            "a tap holds one back, faded and struck, read out as checked, a screen reader told (review S5)" to {
                openGroup(R.string.rare_group_out)
                val told = ArrayList<Int>()
                val tapped = cell("龘")
                ViewCompat.setAccessibilityDelegate(tapped, object : AccessibilityDelegateCompat() {
                    override fun sendAccessibilityEvent(host: View, eventType: Int) {
                        told += eventType
                    }
                })
                tapped.performClick()
                idle()
                ViewCompat.setAccessibilityDelegate(tapped, null)
                assertTrue(told.toString(), AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED in told)
                st.capture("RareCharsPageUatTest/page")
                assertTrue(cell("龘").held)
                val info = AccessibilityNodeInfo.obtain()
                cell("龘").onInitializeAccessibilityNodeInfo(info)
                @Suppress("DEPRECATION")
                assertTrue(info.isCheckable && info.isChecked)
                assertEquals("龘, dá", said(cell("龘")))
                assertEquals("荅, dā、dá", said(cell("荅")))
                assertEquals("dā…", cell("荅").reading)
                assertEquals("the fixed strip counts it", s(R.string.rare_chars_card_held, "1", "6"), strip(R.string.rare_group_out).count.text.toString())
            },
            "back on the cards: the card's strip some, then all held, the count told and undone" to {
                back()
                assertEquals(s(R.string.rare_chars_note, s(R.string.rare_chars_held, "2")), note())
                assertEquals(MaterialCheckBox.STATE_INDETERMINATE, strip(R.string.rare_group_out).state)
                strip(R.string.rare_group_out).performClick()
                idle()
                assertEquals(MaterialCheckBox.STATE_CHECKED, strip(R.string.rare_group_out).state)
                assertTrue(shown().toString(), s(R.string.rare_chars_changed_held, "5") in shown())
                assertEquals("龗 held too, though not drawn", s(R.string.rare_chars_note, s(R.string.rare_chars_held, "7")), note())
                snackbarUndo().performClick()
                idle()
                assertEquals(s(R.string.rare_chars_note, s(R.string.rare_chars_held, "2")), note())
                openGroup(R.string.rare_group_out)
                assertFalse(cell("畣").held)
            },
            "the fixed strip holds the whole group too" to {
                strip(R.string.rare_group_out).performClick()
                idle()
                assertTrue(listOf("炟", "荅", "畣", "鎉", "龘").all { cell(it).held })
                snackbarUndo().performClick()
                idle()
                assertFalse(cell("畣").held)
                assertTrue(cell("龘").held)
            },
            "saved as the page is left, the engine told; the row says how many" to {
                leave()
                val saved = file()
                assertTrue(saved.on)
                assertEquals(setOf(0x9F98), saved.block)
                st.open(SettingsRoute.PinyinDict(""))
                until("counted on the words page") { rareRow()?.summary == s(R.string.rare_chars_held, "2") }
            },
            "left with no change: nothing saved, the engine not told" to {
                openPage()
                st.open(SettingsRoute.About)
                idle()
                assertFalse("reloadEngine()" in api.calls)
            },
        )
    }

    @Test
    @Uat("WD-57")
    fun aSearchFindsASpellingOrThePastedCharacter() {
        RareCharsManager.update(listOf(Change.On(true)), TestCharLevels.levels)
        openPage()
        assertRows(
            "a pinyin searched: its characters across the groups, 扩展区's too, in place of the cards" to {
                search("DA")
                assertEquals(setOf("炟", "荅", "畣", "鎉", "龘", "𤝰"), cells().keys)
                assertTrue(cards().isEmpty())
            },
            "each group's section titled with how many, no box on it; no letters" to {
                assertTrue(shown().toString(), s(R.string.rare_chars_found_in, s(R.string.rare_group_out), "5") in shown())
                assertTrue(shown().toString(), s(R.string.rare_chars_found_in, s(R.string.rare_group_ext), "1") in shown())
                assertTrue(views().none { it is CheckStrip || it is MaterialCheckBox })
                assertTrue(views().none { it is LetterScroller })
            },
            "a character pasted: its cell; a common one, one not this page's, one without a cell said why" to {
                search("龘")
                assertEquals(setOf("龘"), cells().keys)
                search("大")
                assertTrue(shown().toString(), s(R.string.rare_chars_common, "大") in shown())
                assertTrue(cells().isEmpty())
                search("😀")
                assertTrue(s(R.string.rare_chars_not_ours, "😀") in shown())
                search(NO_READING)
                assertTrue(s(R.string.rare_chars_no_cell, NO_READING, s(R.string.rare_group_ext)) in shown())
                search("xyz")
                assertTrue(s(R.string.rare_chars_none_found, "xyz") in shown())
            },
            "the search cleared: the cards" to {
                search("")
                assertEquals(4, cards().size)
                assertTrue(cells().isEmpty())
            },
        )
    }

    // §6.2: a long press and a drag sets every cell between to the start's opposite, one undo for them all
    @Test
    @Uat("WD-52")
    fun aDragHoldsBackARunAndOneUndoTakesItBack() {
        RareCharsManager.update(listOf(Change.On(true)), TestCharLevels.levels)
        openPage()
        openGroup(R.string.rare_group_out)
        val list = list()
        fun at(c: String): Pair<Float, Float> {
            val v = cell(c)
            return (v.left + v.width / 2f) to (v.top + v.height / 2f)
        }
        val time = SystemClock.uptimeMillis()
        fun touch(action: Int, c: String) {
            val (x, y) = at(c)
            list.dispatchTouchEvent(MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0))
        }
        touch(MotionEvent.ACTION_DOWN, "炟")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        touch(MotionEvent.ACTION_MOVE, "龘")
        idle()
        assertRows(
            "while dragged: the run shown held" to { assertTrue(listOf("炟", "畣", "鎉", "龘").all { cell(it).held }) },
            "outside it, as it was" to { assertFalse(cell("荅").held) },
        )
        // back to 畣: 鎉 and 龘 as they were
        touch(MotionEvent.ACTION_MOVE, "畣")
        idle()
        assertFalse(cell("鎉").held)
        touch(MotionEvent.ACTION_MOVE, "龘")
        touch(MotionEvent.ACTION_UP, "龘")
        idle()
        assertRows(
            "lifted: the four held, told" to {
                assertTrue(listOf("炟", "畣", "鎉", "龘").all { cell(it).held })
                assertTrue(shown().toString(), s(R.string.rare_chars_changed_held, "4") in shown())
            },
            "one undo takes the four back" to {
                snackbarUndo().performClick()
                idle()
                assertTrue(listOf("炟", "畣", "鎉", "龘").none { cell(it).held })
            },
        )
    }

    // §6.2: 恢复默认, asked first; a turn keeps what the page changed; a later version's file is not changed
    @Test
    @Uat("WD-57")
    fun resetATurnAndALaterVersionsFile() {
        RareCharsManager.update(listOf(Change.On(true), Change.One(0x9F98, true), Change.One(0x24770, false)), TestCharLevels.levels)
        openPage()
        assertRows(
            "a turn: the change made kept, and saved as the old page stopped" to {
                openGroup(R.string.rare_group_l3)
                cell("犇").performClick()
                idle()
                st.recreate()
                until("the grid again") { cells().isNotEmpty() }
                assertTrue(cell("犇").held)
                assertTrue(0x7287 in file().block)
            },
            "a turn: the search typed kept, and what it found" to {
                back()
                search("da")
                st.recreate()
                until("the grid again") { cells().isNotEmpty() }
                assertEquals("da", views().filterIsInstance<EditText>().single().text.toString())
                assertEquals(setOf("炟", "荅", "畣", "鎉", "龘", "𤝰"), cells().keys)
                search("")
            },
            "恢复默认 asked first, then 扩展区 alone, undone from the title bar, saved as the page is left" to {
                (st.page as RareCharsFragment).reset()
                idle()
                val d = ShadowDialog.getLatestDialog() as AlertDialog
                assertEquals(s(R.string.rare_chars_reset_confirm), d.findViewById<TextView>(android.R.id.message)!!.text.toString())
                d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                idle()
                assertEquals(MaterialCheckBox.STATE_UNCHECKED, strip(R.string.rare_group_l3).state)
                assertEquals(MaterialCheckBox.STATE_UNCHECKED, strip(R.string.rare_group_out).state)
                assertEquals(s(R.string.rare_chars_note, s(R.string.rare_chars_held, "1")), note())
                tapUndo()
                openGroup(R.string.rare_group_l3)
                assertTrue("undone: 犇 held again", cell("犇").held)
                back()
                (st.page as RareCharsFragment).reset()
                idle()
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                idle()
                leave()
                val saved = file()
                assertTrue(saved.on)
                assertEquals(setOf(Level.EXT), saved.groups)
                assertEquals(emptySet<Int>(), saved.allow + saved.block)
            },
        )
    }

    // §4.2, §6.2: a later version's file shown, nothing on the page changes it, nothing saved
    @Test
    fun aLaterVersionsFileIsShownNotChanged() {
        val later = "# youmo-rare-chars 2\non\t1\ngroups\tEXT OUT\n"
        File(HostConfigDir.inUse(ConfigHome.DATA).dir, RareCharsManager.FILE).apply { parentFile!!.mkdirs() }.writeText(later)
        openPage()
        assertRows(
            "why said; the switch, the strips and the cells not to be changed; the cards open" to {
                assertTrue(s(R.string.rare_chars_newer) in shown())
                assertFalse(switch().isEnabled)
                assertFalse(strip(R.string.rare_group_out).isEnabled)
                assertEquals(CheckStrip.DISABLED_ALPHA, strip(R.string.rare_group_out).alpha)
                openGroup(R.string.rare_group_out)
                assertTrue(s(R.string.rare_chars_newer) in shown())
                assertFalse(strip(R.string.rare_group_out).isEnabled)
                assertFalse(cell("龘").isEnabled)
                cell("龘").performClick()
                idle()
            },
            "恢复默认 and ↶ not offered" to {
                assertFalse(menuItem(R.string.rare_chars_reset).isEnabled)
                assertFalse(menuItem(R.string.undo).isEnabled)
                (st.page as RareCharsFragment).reset()
                idle()
                assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true)
            },
            "left: the file as it was, the engine not told" to {
                st.open(SettingsRoute.About)
                idle()
                assertFalse("reloadEngine()" in api.calls)
                assertEquals(later, File(HostConfigDir.inUse(ConfigHome.DATA).dir, RareCharsManager.FILE).readText())
            },
        )
    }

    // §5.4: the user's words with a character held back marked, one tap letting it through
    @Test
    @Uat("WD-56")
    fun aUserWordWithACharacterHeldIsMarkedAndLetThrough() {
        RareCharsManager.update(listOf(Change.On(true), Change.One(0x9F98, true)), TestCharLevels.levels)
        api.userWords += UserWord("龘龘", "da da", UserWord.Kind.ADDED)
        api.userWords += UserWord("大家", "da jia", UserWord.Kind.ADDED)
        st.open(SettingsRoute.UserWordList(UserWord.Kind.ADDED.name))
        val mark = s(R.string.rare_word_has, "龘")
        until("marked") { shown().any { mark in it } }
        assertRows(
            "the word without one not marked" to { assertEquals(1, shown().count { s(R.string.rare_word_has, "").trimEnd() in it }) },
            "tapped: 「放行 龘」 among what it offers; done, not marked, the engine told" to {
                views().filterIsInstance<TextView>().first { it.text.toString() == "龘龘" }.let { v ->
                    generateSequence(v as View) { it.parent as? View }.first { it.hasOnClickListeners() }.performClick()
                }
                idle()
                val d = ShadowDialog.getLatestDialog() as AlertDialog
                val items = (0 until d.listView.adapter.count).map { d.listView.adapter.getItem(it).toString() }
                val at = items.indexOf(s(R.string.rare_allow_one, "龘"))
                assertTrue(items.toString(), at >= 0)
                d.listView.performItemClick(d.listView, at, 0)
                until("let through") { shown().none { mark in it } }
                assertFalse(0x9F98 in file().block)
                assertTrue("reloadEngine()" in api.calls)
            },
            "the add dialog says at once, 放行 there refreshes the list behind it (review N9), the word still saved" to {
                // a word with 𤝰 the list has not read yet: shown once the list is read again
                api.userWords += UserWord("大𤝰", "da da", UserWord.Kind.ADDED)
                views().filterIsInstance<FloatingActionButton>().single().performClick()
                idle()
                val d = ShadowDialog.getLatestDialog() as AlertDialog
                val decor = d.window!!.decorView
                val (text, pinyin) = KeyboardHarness.all(decor).filterIsInstance<EditText>().toList()
                text.setText("𤝰")
                idle()
                val layout = KeyboardHarness.all(decor).filterIsInstance<TextInputLayout>().first()
                assertEquals(s(R.string.rare_add_hint, "𤝰"), layout.helperText.toString())
                val allow = KeyboardHarness.all(decor).filterIsInstance<MaterialButton>().single { it.text == s(R.string.rare_allow_this) }
                assertTrue(allow.isShown)
                allow.performClick()
                until("the list read again, 大𤝰 in it unmarked") { "大𤝰" in shown() && shown().none { s(R.string.rare_word_has, "𤝰") in it } }
                assertTrue(cp("𤝰") in file().allow)
                pinyin.setText("da")
                d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                until("saved") { api.userWords.any { it.text == "𤝰" } }
            },
        )
    }

    // review N6: the words page's count read after a save of the page just left, not before it
    @Test
    fun theCountWaitsForTheSaveUnderWay() = runBlocking {
        val pending = CompletableDeferred<Boolean>()
        RareCharsViewModel.saving = pending
        try {
            val count = async(Dispatchers.Default) { rareSummary(RuntimeEnvironment.getApplication()) { grid } }
            delay(300)
            assertFalse("counted before the save was done", count.isCompleted)
            pending.complete(true)
            assertEquals(s(R.string.rare_chars_off), withTimeout(5_000) { count.await() })
        } finally {
            pending.complete(false)
            RareCharsViewModel.saving = null
        }
    }

    // review S8: the cell's text grows with the font's scale, so does the cell, and fewer go across
    @Test
    fun aCellGrowsWithTheFontScaleAndFewerGoAcross() {
        val ctx = RuntimeEnvironment.getApplication()
        // the cell's height and its two lines' at the scale now
        fun measured(): Pair<Int, Float> {
            val paints = RareCharCell.Paints(ctx)
            val cell = RareCharCell(ctx, paints)
            cell.measure(View.MeasureSpec.makeMeasureSpec(ctx.dp(48), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            return cell.measuredHeight to paints.char.fontSpacing + paints.reading.fontSpacing
        }
        val (normal, _) = measured()
        RuntimeEnvironment.setFontScale(2f)
        try {
            val (large, lines) = measured()
            assertRows(
                "twice as tall at 2.0" to { assertTrue("$normal, $large", large >= normal * 2 - 1) },
                "its two lines fit at 2.0" to { assertTrue("$lines <= $large", lines <= large) },
                "7 across 360dp at 1.0, 3 at 2.0" to { assertEquals(listOf(7, 3), listOf(RareCharCell.columns(360, 1f), RareCharCell.columns(360, 2f))) },
            )
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
