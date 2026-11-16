/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Looper
import android.view.MenuItem
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.ReadingSource
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.RareGrid
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.settings.RareCharCell
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsFragment
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsViewModel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.cards.CheckStrip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.robolectric.Shadows.shadowOf
import java.nio.ByteBuffer
import java.time.Duration
import org.fcitx.fcitx5.android.until as waitUntil

/**
 * What 生僻字屏蔽's page tests share (dev/design/061-rare-char-block.md §6, 061-settings-cards.md §3): the
 * page over a grid of a few characters of each group, read as the real levels' table has them, and the
 * ways to look at it and touch it.
 */
abstract class RarePageUat {

    protected val api = FakeHostApi()
    protected val st = SettingsHarness(api)
    protected val activity get() = st.activity
    protected val glyph = RareCharsViewModel.hasGlyph

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

    protected fun idle() = st.idle()

    protected fun until(what: String, test: () -> Boolean) =
        waitUntil(what, { "; shown ${shown()}" }, step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }, done = test)

    protected fun s(id: Int, vararg args: Any) = st.s(id, *args)

    protected fun views() = KeyboardHarness.all(activity.window.decorView).filter { it.isShown }

    protected fun shown(): List<String> = views().filterIsInstance<TextView>().map { it.text.toString() }.toList()

    protected fun cells() = views().filterIsInstance<RareCharCell>().associateBy { it.char }

    // [c]'s cell, the list scrolled to it where it is not shown: on a phone's height 其它表外字's cells, the last
    // group's, are below 扩展区's head and the groups above (held by default, first)
    protected fun cell(c: String): RareCharCell {
        for (way in intArrayOf(1, -1)) {
            while (c !in cells()) {
                // till the list moves no more: at its end, or held still (a drag under way)
                val was = list().computeVerticalScrollOffset()
                list().scrollBy(0, way * list().height / 4)
                idle()
                if (list().computeVerticalScrollOffset() == was) break
            }
        }
        return cells()[c] ?: error("no cell $c in ${cells().keys}")
    }

    // what a screen reader is told of a cell: its node's description, and an event's the same
    protected fun said(cell: RareCharCell): String {
        val info = AccessibilityNodeInfo.obtain()
        cell.onInitializeAccessibilityNodeInfo(info)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        cell.onInitializeAccessibilityEvent(event)
        assertEquals("an event says what the node does", info.contentDescription?.toString(), event.contentDescription?.toString())
        return info.contentDescription.toString()
    }

    protected fun list() = views().filterIsInstance<RecyclerView>().first()

    protected fun switch() = views().filterIsInstance<MaterialSwitch>().single()

    protected fun fragment() = st.page as RareCharsFragment

    // a group's strip, by what it reads out: its card's on the cards, the fixed one over its grid
    protected fun strip(group: Int) = views().filterIsInstance<CheckStrip>().first { it.contentDescription.toString().startsWith(s(group)) }

    // a card's body, the part a tap opens the group by
    protected fun body(group: Int) = views().first { it !is CheckStrip && it.isClickable && it.contentDescription?.toString()?.startsWith(s(group)) == true }

    protected fun cards() = views().filterIsInstance<MaterialCardView>().filter { it.tag?.toString()?.startsWith("view:rare/group/") == true }.toList()

    protected fun openGroup(group: Int) {
        body(group).performClick()
        // laid out anew: the last group's cells stay the list's children till then
        idle()
        until("the group's grid") { !list().isLayoutRequested && cells().isNotEmpty() }
    }

    protected fun back() {
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
    }

    protected fun title() = activity.supportActionBar!!.title.toString()

    protected fun menuItem(title: Int): MenuItem {
        idle()
        val menu = shadowOf(activity).optionsMenu
        return (0 until menu.size()).map { menu.getItem(it) }.first { it.title.toString() == s(title) }
    }

    protected fun tapUndo() {
        val item = menuItem(R.string.undo)
        assertTrue("↶ enabled", item.isEnabled)
        item.javaClass.getMethod("invoke").invoke(item)
        idle()
    }

    protected fun note() = shown().first { s(R.string.rare_chars_note_off).substringAfter(" · ") in it }

    protected fun search(text: String) {
        views().filterIsInstance<EditText>().single().setText(text)
        idle()
    }

    protected fun file() = RareCharsManager.load()

    protected fun leave() {
        st.open(SettingsRoute.About)
        until("saved") { "reloadEngine()" in api.calls }
        api.calls.clear()
    }

    protected fun openPage() {
        st.open(SettingsRoute.RareChars)
        until("the cards") { cards().size == 4 }
    }

    protected fun snackbarUndo() = views().filterIsInstance<TextView>().first { it.text.toString() == s(R.string.undo) && it.hasOnClickListeners() }

    protected fun rareRow(): Preference? {
        val page = st.page as? PreferenceFragmentCompat ?: return null
        fun all(g: PreferenceGroup): List<Preference> = (0 until g.preferenceCount).flatMap { i ->
            val p = g.getPreference(i)
            listOf(p) + ((p as? PreferenceGroup)?.let(::all) ?: emptyList())
        }
        return all(page.preferenceScreen).firstOrNull { it.title == s(R.string.rare_chars) }
    }

    companion object {
        const val NO_READING = "𠀁" // U+20001: 扩展 B's, in the dictionary with no reading

        fun cp(c: String) = c.codePointAt(0)

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

        // 其它表外字 a few screens long, over many letters (past the table, U+9C00 on), and 犇 for 三级字
        val many: RareGrid by lazy {
            val b = PinyinDataBuilder().unigram("<unk>", -7f, 0f)
            val syllables = listOf("ba", "ca", "da", "fa", "ga", "ha", "ka", "la", "ma", "na", "pa", "sa", "ta", "wa", "ya", "za")
            (0 until 400).forEach { b.charReading(ReadingSource.MANDARIN, 0x9C00 + it, Syllables.id(syllables[it % syllables.size]), 1) }
            b.charReading(ReadingSource.MANDARIN, cp("犇"), Syllables.id("ben"), 1)
            val data = PinyinData.load(ByteBuffer.wrap(b.build().toByteArray()))
            RareGrid.copy(TestCharLevels.levels, data.charReadings, emptySequence())
        }
    }
}
