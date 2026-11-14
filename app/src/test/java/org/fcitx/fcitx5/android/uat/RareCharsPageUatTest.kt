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
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
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
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.ReadingSource
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.user.RareChoice.Change
import org.fcitx.fcitx5.android.engine.user.RareGrid
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.settings.LetterScroller
import org.fcitx.fcitx5.android.ui.main.settings.RareCharCell
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsFragment
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsViewModel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.rareSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
import java.nio.ByteBuffer
import java.time.Duration
import org.fcitx.fcitx5.android.until as waitUntil

/**
 * 生僻字屏蔽's page (dev/design/061-rare-char-block.md §6) and the marks on the user's words (§5.4),
 * over a grid of a few characters of each group, read as the real levels' table has them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class RareCharsPageUatTest {

    private val api = FakeHostApi()
    private val st = SettingsHarness(api)
    private val activity get() = st.activity
    private val glyph = RareCharsViewModel.hasGlyph

    @Before
    fun setUp() {
        phoneStorage()
        RareCharsManager.useLevels(TestCharLevels.levels)
        api.rareGridAnswer = grid
        // 龗 not drawn here: no cell, counted all the same
        RareCharsViewModel.hasGlyph = { it != "龗" }
    }

    @After
    fun close() {
        RareCharsManager.useLevels(null)
        RareCharsViewModel.hasGlyph = glyph
        RareCharsViewModel.saving = null
        st.close()
    }

    private fun idle() = st.idle()

    private fun until(what: String, test: () -> Boolean) =
        waitUntil(what, { "; shown ${shown()}" }, step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }, done = test)

    private fun s(id: Int, vararg args: Any) = st.s(id, *args)

    private fun views() = KeyboardHarness.all(activity.window.decorView).filter { it.isShown }

    private fun shown(): List<String> = views().filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun cells() = views().filterIsInstance<RareCharCell>().associateBy { it.char }

    private fun cell(c: String) = cells()[c] ?: error("no cell $c in ${cells().keys}")

    // what a screen reader is told of a cell: its node's description, and an event's the same
    private fun said(cell: RareCharCell): String {
        val info = AccessibilityNodeInfo.obtain()
        cell.onInitializeAccessibilityNodeInfo(info)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        cell.onInitializeAccessibilityEvent(event)
        assertEquals("an event says what the node does", info.contentDescription?.toString(), event.contentDescription?.toString())
        return info.contentDescription.toString()
    }

    private fun list() = views().filterIsInstance<RecyclerView>().first()

    private fun switch() = views().filterIsInstance<MaterialSwitch>().single()

    private fun box(group: Int) = views().filterIsInstance<MaterialCheckBox>().first { s(group) in it.contentDescription.toString() }

    private fun note() = shown().first { s(R.string.rare_chars_note_off).substringAfter(" · ") in it }

    private fun search(text: String) {
        views().filterIsInstance<EditText>().single().setText(text)
        idle()
    }

    private fun file() = RareCharsManager.load()

    private fun leave() {
        st.open(SettingsRoute.About)
        until("saved") { "reloadEngine()" in api.calls }
        api.calls.clear()
    }

    private fun openPage() {
        st.open(SettingsRoute.RareChars)
        until("the grid") { cells().isNotEmpty() }
    }

    private fun snackbarUndo() = views().filterIsInstance<TextView>().first { it.text.toString() == s(R.string.undo) && it.hasOnClickListeners() }

    @Test
    @Uat("WD-52", "AX-17")
    fun thePageHoldsBackAndLetsThrough() {
        st.open(SettingsRoute.PinyinDict(""))
        until("关 on the words page") { rareRow()?.summary == s(R.string.rare_chars_off) }
        openPage()
        assertRows(
            "off: the grid there to look at, nothing changed by a tap" to {
                assertFalse(switch().isChecked)
                cell("龘").performClick()
                idle()
                assertFalse(cell("龘").held)
                assertFalse(cell("龘").isEnabled)
            },
            "the groups' heads, 扩展区 shut; what has no cell said under its group" to {
                assertTrue(shown().toString(), shown().any { it.startsWith("▸ ${s(R.string.rare_group_ext)}") })
                assertTrue(shown().toString(), s(R.string.rare_chars_unread, "1") in shown().joinToString("\n"))
                assertTrue(shown().toString(), s(R.string.rare_chars_unshown, "1") in shown().joinToString("\n"))
                assertFalse("龗 not drawn here", "龗" in cells())
                assertFalse("𤝰 in the shut group", "𤝰" in cells())
                assertEquals("龗 not drawn: no L", listOf("B", "Z", "F", "D"), shown().filter { it.length == 1 && it[0].isUpperCase() }.distinct())
            },
            "on: 扩展区 held, counted without what has no reading" to {
                switch().isChecked = true
                idle()
                assertEquals(s(R.string.rare_chars_note, s(R.string.rare_chars_held, "1")), note())
            },
            "a tap holds one back, faded and struck, read out as checked, a screen reader told (review S5)" to {
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
                assertEquals(s(R.string.rare_chars_note, s(R.string.rare_chars_held, "2")), note())
            },
            "a group's box: some, then all held, the count told and undone" to {
                assertEquals(MaterialCheckBox.STATE_INDETERMINATE, box(R.string.rare_group_out).checkedState)
                box(R.string.rare_group_out).performClick()
                idle()
                assertEquals(MaterialCheckBox.STATE_CHECKED, box(R.string.rare_group_out).checkedState)
                assertTrue(shown().toString(), s(R.string.rare_chars_changed_held, "5") in shown())
                assertEquals("龗 held too, though not drawn", s(R.string.rare_chars_note, s(R.string.rare_chars_held, "7")), note())
                snackbarUndo().performClick()
                idle()
                assertEquals(s(R.string.rare_chars_note, s(R.string.rare_chars_held, "2")), note())
                assertFalse(cell("畣").held)
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
            "a pinyin searched: its characters across the groups, 扩展区's too" to {
                search("DA")
                assertEquals(setOf("炟", "荅", "畣", "鎉", "龘", "𤝰"), cells().keys)
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
                search("")
                assertTrue("龘" in cells())
            },
        )
    }

    private fun rareRow(): Preference? {
        val page = st.page as? PreferenceFragmentCompat ?: return null
        fun all(g: PreferenceGroup): List<Preference> = (0 until g.preferenceCount).flatMap { i ->
            val p = g.getPreference(i)
            listOf(p) + ((p as? PreferenceGroup)?.let(::all) ?: emptyList())
        }
        return all(page.preferenceScreen).firstOrNull { it.title == s(R.string.rare_chars) }
    }

    // §6.2: a long press and a drag sets every cell between to the start's opposite, one undo for them all
    @Test
    @Uat("WD-52")
    fun aDragHoldsBackARunAndOneUndoTakesItBack() {
        RareCharsManager.update(listOf(Change.On(true)), TestCharLevels.levels)
        openPage()
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
                cell("犇").performClick()
                idle()
                st.recreate()
                until("the grid again") { cells().isNotEmpty() }
                assertTrue(cell("犇").held)
                assertTrue(0x7287 in file().block)
            },
            "a turn: the search typed kept, and what it found" to {
                search("da")
                st.recreate()
                until("the grid again") { cells().isNotEmpty() }
                assertEquals("da", views().filterIsInstance<EditText>().single().text.toString())
                assertEquals(setOf("炟", "荅", "畣", "鎉", "龘", "𤝰"), cells().keys)
                search("")
            },
            "恢复默认 asked first, then 扩展区 alone, undone, saved as the page is left" to {
                (st.page as RareCharsFragment).reset()
                idle()
                val d = ShadowDialog.getLatestDialog() as AlertDialog
                assertEquals(s(R.string.rare_chars_reset_confirm), d.findViewById<TextView>(android.R.id.message)!!.text.toString())
                d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                idle()
                assertFalse(cell("龘").held)
                assertFalse(cell("犇").held)
                assertEquals(s(R.string.rare_chars_note, s(R.string.rare_chars_held, "1")), note())
                snackbarUndo().performClick()
                idle()
                assertTrue("undone: 犇 held again", cell("犇").held)
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
            "why said; the switch, the boxes and the cells not to be changed" to {
                assertTrue(s(R.string.rare_chars_newer) in shown())
                assertFalse(switch().isEnabled)
                assertFalse(box(R.string.rare_group_out).isEnabled)
                assertFalse(cell("龘").isEnabled)
                cell("龘").performClick()
                idle()
            },
            "恢复默认 not offered" to {
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

    // review N12: a group's head read with its state, the letters as an adjustable
    @Test
    fun theHeadsAndTheLettersAreReadOut() {
        openPage()
        assertRows(
            "扩展区's head read shut, without the arrow, a tap read as opening it" to {
                val head = views().filterIsInstance<TextView>().first { it.text.startsWith("▸ ${s(R.string.rare_group_ext)}") }
                assertEquals(s(R.string.rare_chars_group_shut), ViewCompat.getStateDescription(head))
                assertFalse(head.contentDescription.toString(), "▸" in head.contentDescription)
                val info = AccessibilityNodeInfo.obtain()
                head.onInitializeAccessibilityNodeInfo(info)
                assertEquals(s(R.string.rare_chars_expand), info.actionList.single { it.id == AccessibilityNodeInfo.ACTION_CLICK }.label)
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

    private companion object {
        const val NO_READING = "𠀁" // U+20001: 扩展 B's, in the dictionary with no reading

        private fun cp(c: String) = c.codePointAt(0)

        // each group's few, as the test's table has them: 犇 and 㑇 三级, 髮 繁体, 𤝰 扩展 B, the rest past the
        // table in the basic block (TestCharLevels; the JVM tests have no APK assets)
        val grid: RareGrid by lazy {
            val b = PinyinDataBuilder().unigram("<unk>", -7f, 0f)
            fun read(c: String, vararg readings: Pair<String, Int>) = readings.forEach { (syllable, tone) ->
                b.charReading(ReadingSource.MANDARIN, cp(c), Syllables.id(syllable), tone)
            }
            read("大", "da" to 4)
            read("炟", "da" to 2)
            read("犇", "ben" to 1)
            read("㑇", "zhou" to 4)
            read("髮", "fa" to 4)
            read("荅", "da" to 1, "da" to 2)
            read("畣", "da" to 2)
            read("鎉", "da" to 2, "ta" to 4)
            read("龘", "da" to 2)
            read("龗", "ling" to 2)
            read("𤝰", "da" to 2)
            val data = PinyinData.load(ByteBuffer.wrap(b.build().toByteArray()))
            RareGrid.copy(TestCharLevels.levels, data.charReadings, sequenceOf(cp(NO_READING)))
        }
    }
}
