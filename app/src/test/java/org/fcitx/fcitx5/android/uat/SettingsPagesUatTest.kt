/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * UAT cases of the settings app's pages over a fake engine (uat/01, 03, 13): the input methods,
 * the nine keys' letters and long presses, the user dictionary's lists. What the engine does with
 * what a page saves (the words typed after) is the engine's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SettingsPagesUatTest {

    private val api = FakeFcitxAPI()
    private val st = SettingsHarness(api)
    private val activity get() = st.activity

    @After
    fun close() = st.close()

    private fun idle() = st.idle()

    // a page's work done off the main thread (a search, a list read): waited for, as a user would
    private fun until(what: String, test: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!test()) {
            check(System.currentTimeMillis() < deadline) { "never: $what; shown ${shown()}" }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        }
    }

    private fun open(route: SettingsRoute) = st.open(route)

    private val page: Fragment get() = st.page

    private fun s(id: Int, vararg args: Any) = st.s(id, *args)

    private fun preferences(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).flatMap { i ->
            val p = group.getPreference(i)
            listOf(p) + ((p as? PreferenceGroup)?.let(::preferences) ?: emptyList())
        }.filter { it.isVisible }

    private fun shown(): List<String> = KeyboardHarness.all(activity.window.decorView)
        .filter { it.isShown && it is TextView }.map { (it as TextView).text.toString() }.toList()

    private fun view(text: String): View = KeyboardHarness.all(activity.window.decorView)
        .first { it.isShown && it is TextView && it.text.toString() == text }

    private fun clickable(v: View): View = generateSequence(v) { it.parent as? View }.first { it.hasOnClickListeners() || it.isLongClickable }

    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun entry(name: String) = InputMethodEntry(name, name, "", "", name, "zh_CN", "androidengine", false)

    @Test
    @Uat("SET-03")
    fun theInputMethodsListed() {
        api.availableIme = arrayOf(entry(Engines.PINYIN), entry(Engines.ENGLISH), entry(Engines.T9))
        api.enabledIme = arrayOf(entry(Engines.PINYIN), entry(Engines.ENGLISH))
        open(SettingsRoute.InputMethodList)
        until("listed") { "Pinyin" in shown() }
        val rows = shown().filter { it in listOf("Pinyin", "English", "Pinyin (9 keys)") }
        assertEquals(listOf("Pinyin", "English"), rows)
    }

    @Test
    @Uat("SET-04")
    fun addingNineKeys() {
        val all = listOf(Engines.PINYIN, Engines.ENGLISH, Engines.WUBI, "engine-erbi", "engine-cangjie", "engine-ziranma", Engines.SHUANGPIN, "engine-wubipinyin", Engines.HANDWRITING, Engines.T9)
        api.availableIme = all.map(::entry).toTypedArray()
        api.enabledIme = arrayOf(entry(Engines.PINYIN), entry(Engines.ENGLISH))
        open(SettingsRoute.InputMethodList)
        until("listed") { "Pinyin" in shown() }
        KeyboardHarness.all(activity.window.decorView).filterIsInstance<com.google.android.material.floatingactionbutton.FloatingActionButton>().single().performClick()
        idle()
        val list = dialog().listView
        val items = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
        // the eight not added, nine keys first
        assertEquals(8, items.size)
        assertEquals("Pinyin (9 keys)", items.first())
        list.performItemClick(list.getChildAt(0), 0, 0)
        idle()
        until("added") { "Pinyin (9 keys)" in shown() }
        assertEquals("Pinyin (9 keys)", shown().filter { it in listOf("Pinyin", "English", "Pinyin (9 keys)") }.last())
        until("saved") { api.calls.any { it.startsWith("setEnabledIme") } }
        assertTrue(api.calls.toString(), api.calls.last { it.startsWith("setEnabledIme") }.endsWith("${Engines.T9})"))
    }

    // a key of the nine keys' page: its card, read out as its letters and digit
    private fun card(digit: Char): View = KeyboardHarness.all(activity.window.decorView)
        .first { it.isShown && it.contentDescription?.toString()?.endsWith(digit.toString()) == true && it.hasOnClickListeners() }

    private fun editKey(digit: Char, typed: String?, button: Int = AlertDialog.BUTTON_POSITIVE) {
        card(digit).performClick()
        idle()
        val d = dialog()
        if (typed != null) KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().single().setText(typed)
        d.getButton(button).performClick()
        idle()
    }

    @Test
    @Uat("T9-13")
    fun nineKeysLettersEdited() {
        val letters = AppPrefs.getInstance().keyboard.t9Letters
        try {
            open(SettingsRoute.T9Letters)
            editKey('7', "prs")
            editKey('3', "")
            // the keyboard made after: 7's long press without q, 3's symbols a row of up to five
            KeyboardHarness(im = KeyboardHarness.T9).use { h ->
                h.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.T9, KeyboardHarness.ENGLISH)
                h.api.enabledIme = h.api.availableIme
                h.start()
                val seven = h.longPressed('7')
                assertTrue(seven.toString(), seven.containsAll(listOf("7", "p", "r", "s")) && "q" !in seven && "Q" !in seven)
                // emptied, 3 is as on a phone (the field's hint says so); its two symbols one row, not a column
                var rows = emptySet<Int>()
                val three = h.longPressed('3') {
                    rows = listOf("+", "-").map { t ->
                        val c = KeyboardHarness.all(inputView).filterIsInstance<TextView>().last { shown(it) && it.text.toString() == t }
                        IntArray(2).also((c.parent as View)::getLocationInWindow)[1]
                    }.toSet()
                }
                assertTrue(three.toString(), three.containsAll(listOf("+", "-", "D", "3", "d")))
                assertEquals(1, rows.size)
            }
        } finally {
            letters.setValue(letters.defaultValue)
        }
    }

    @Test
    @Uat("T9-29")
    fun nineKeysLongPressesEdited() {
        val symbols = AppPrefs.getInstance().keyboard.t9Symbols
        try {
            // 1's marks typed as the popup gives them (. not made 。): T9KeyboardTest.aLongPressedSymbolGoesOnAsThePopupGivesIt
            open(SettingsRoute.T9Symbols)
            editKey('5', "")
            assertTrue(symbols.getValue(), symbols.getValue().isNotEmpty())
            editKey('5', null, AlertDialog.BUTTON_NEUTRAL)
            // a letter alone is no symbol: nothing kept, 1 as it was
            val before = symbols.getValue()
            editKey('1', "a")
            assertEquals(before, symbols.getValue())
            editKey('1', "， 。")
            KeyboardHarness(im = KeyboardHarness.T9).use { h ->
                h.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.T9, KeyboardHarness.ENGLISH)
                h.api.enabledIme = h.api.availableIme
                h.start()
                val five = h.longPressed('5')
                assertTrue(five.toString(), "#" in five)
                val one = h.longPressed('1')
                assertTrue(one.toString(), "，" in one && "。" in one && "…" !in one)
                // nine keys English's 1: its own marks, half width, not the pinyin's
                h.tap(h.key("中/英"))
                h.emit(org.fcitx.fcitx5.android.core.FcitxEvent.IMChangeEvent(KeyboardHarness.ENGLISH))
                val english = h.longPressed('1')
                assertTrue(english.toString(), english.containsAll(listOf(":", ";", "(", ")", "@")) && "，" !in english && "：" !in english)
            }
        } finally {
            symbols.setValue(symbols.defaultValue)
        }
    }

    /** The long press's popup of the nine keys' [digit] key, let go out of it (nothing typed). */
    private fun KeyboardHarness.longPressed(digit: Char, shown: KeyboardHarness.() -> Unit = {}): List<String> {
        val key = nineKey(digit.toString())
        val before = texts(inputView).size
        val f = finger(key, at(key))
        advance(700)
        // the popup's texts: after the keyboard's in the tree, the popup drawn over it
        val popup = texts(inputView).drop(before)
        shown()
        f.move(at(key))
        val out = KeyboardHarness.Touch(key.width / 2f, key.height * 3f)
        f.move(out)
        f.up(out)
        check(before > 0)
        return popup
    }

    private fun words(vararg w: UserWord) {
        api.userWords.clear()
        api.userWords.addAll(w)
    }

    @Test
    @Uat("WD-30")
    fun searchDeleteSeveralAndTakeThemBack() {
        val lan = UserWord("澜境花园", "lan jing hua yuan", UserWord.Kind.ADDED)
        val you = UserWord("幽默", "you mo", UserWord.Kind.ADDED)
        words(lan, you)
        var removed = emptyList<UserWord>()
        api.learned = { op ->
            when (op) {
                is LearningOp.RemoveWords -> { removed = op.words; api.userWords.removeAll(op.words.toSet()); LearningResult.Done(9) }
                is LearningOp.Undo -> { api.userWords.addAll(removed); LearningResult.Done() }
                else -> LearningResult.Done()
            }
        }
        open(SettingsRoute.UserWordList(UserWord.Kind.ADDED.name))
        until("listed") { "澜境花园" in shown() && "幽默" in shown() }
        val search = KeyboardHarness.all(activity.window.decorView).filterIsInstance<EditText>().first()
        search.setText("lan")
        until("lan") { "澜境花园" in shown() && "幽默" !in shown() }
        search.setText("幽")
        until("幽") { "幽默" in shown() && "澜境花园" !in shown() }
        search.setText("")
        until("all") { "澜境花园" in shown() && "幽默" in shown() }
        clickable(view("澜境花园")).performLongClick()
        idle()
        clickable(view("幽默")).performClick()
        idle()
        val menu = shadowOf(activity).optionsMenu
        val delete = (0 until menu.size()).map { menu.getItem(it) }.first { it.title.toString() == s(R.string.delete_selected) }
        delete.javaClass.getMethod("invoke").invoke(delete)
        idle()
        val confirm = dialog()
        val message = KeyboardHarness.all(confirm.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(message.toString(), activity.resources.getQuantityString(R.plurals.confirm_delete_words, 2, 2) in message)
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        until("deleted") { "澜境花园" !in shown() && "幽默" !in shown() }
        val said = activity.resources.getQuantityString(R.plurals.learning_removed, 2, 2)
        until("said") { said in shown() }
        view(s(R.string.learning_undo)).performClick()
        until("back") { "澜境花园" in shown() && "幽默" in shown() }
        assertTrue(api.calls.toString(), "learning(Undo(token=9))" in api.calls)
    }

    @Test
    @Uat("WD-21")
    fun theUsersWordsListsCountsAndDays() {
        val today = (System.currentTimeMillis() / 86_400_000L).toInt()
        words(
            UserWord("汪小明", "wang xiao ming", UserWord.Kind.LEARNED, count = 2f, lastUsed = today, order = 1),
            UserWord("鲸喵屿", "jing miao yu", UserWord.Kind.LEARNED, count = 2f, lastUsed = today, order = 2),
            UserWord("是", "shi", UserWord.Kind.BLOCKED),
        )
        open(SettingsRoute.UserWords)
        val prefs = preferences((page as PreferenceFragmentCompat).preferenceScreen)
        assertEquals(
            listOf(s(R.string.learning_use_habits), s(R.string.learning_auto_backup)),
            prefs.filterIsInstance<TwoStatePreference>().map { it.title.toString() },
        )
        val titles = prefs.map { it.title.toString() }
        for (kind in UserWord.Kind.entries) {
            assertTrue("${kind.name} in $titles", s(org.fcitx.fcitx5.android.ui.main.settings.UserWordsFragment.title(kind)) in titles)
        }
        open(SettingsRoute.UserWordList(UserWord.Kind.LEARNED.name))
        until("listed") { "汪小明" in shown() }
        val detail = shown()[shown().indexOf("汪小明") + 1]
        // typed twice, the day only
        assertTrue(detail, "2" in detail && ":" !in detail.substringAfter("wang xiao ming"))
        val menu = shadowOf(activity).optionsMenu
        val recent = (0 until menu.size()).map { menu.getItem(it) }.first { it.title.toString() == s(R.string.learning_sort_recent) }
        recent.javaClass.getMethod("invoke").invoke(recent)
        until("recent first") { shown().indexOf("鲸喵屿") in 0 until shown().indexOf("汪小明") }
        open(SettingsRoute.UserWordList(UserWord.Kind.BLOCKED.name))
        until("blocked") { "是" in shown() }
    }

    @Test
    @Uat("VO-15")
    fun theVoiceModelsDataIsCredited() {
        open(SettingsRoute.License)
        until("listed") { preferences((page as PreferenceFragmentCompat).preferenceScreen ?: return@until false).size > 3 }
        val prefs = preferences((page as PreferenceFragmentCompat).preferenceScreen)
        val libri = prefs.firstOrNull { it.title == "LibriSpeech" }
        // the voice build credits it; the text build has no voice model, nor the entry
        assertEquals(org.fcitx.fcitx5.android.input.voice.VoiceFeature.AVAILABLE, libri != null)
        if (libri == null) return
        assertEquals("CC-BY-4.0", libri.summary.toString())
        libri.performClick()
        idle()
        assertEquals("https://www.openslr.org/12/", shadowOf(activity).nextStartedActivity.dataString)
    }
}
