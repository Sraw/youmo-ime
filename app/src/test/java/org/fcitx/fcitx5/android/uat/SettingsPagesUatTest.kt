/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.net.Uri
import android.os.Looper
import android.text.SpannableString
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.ShuangpinNames
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.pinyin.CommonWordsManager
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.engine.phrase.CommonWords
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.ListedItem
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.CommonWordsFragment
import org.fcitx.fcitx5.android.ui.main.settings.PinyinCustomPhraseFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.UserWordsFragment
import org.fcitx.fcitx5.android.until as waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch

/**
 * UAT cases of the settings app's pages over a fake engine (uat/01, 03, 13): the input methods,
 * the nine keys' letters and long presses, the user dictionary's lists. What the engine does with
 * what a page saves (the words typed after) is the engine's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SettingsPagesUatTest {

    @Before
    fun phone() = phoneStorage()

    private val api = FakeHostApi()
    private val st = SettingsHarness(api)
    private val activity get() = st.activity

    @After
    fun close() = st.close()

    private fun idle() = st.idle()

    // a page's work done off the main thread (a search, a list read): waited for, as a user would
    private fun until(what: String, test: () -> Boolean) =
        waitUntil(what, { "; shown ${shown()}" }, step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }, done = test)

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

    // a dialog opened by a tap may show a looper turn later under load: wait for it rather than read null
    private fun dialog(): AlertDialog {
        until("a dialog showing") { ShadowDialog.getLatestDialog()?.isShowing == true }
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun entry(name: String) = InputMethodEntry(name, name, "", "", name, "zh_CN", "androidengine", false)

    @Test
    @Uat("SET-03")
    fun theInputMethodsListed() {
        api.availableIme = arrayOf(entry(InputMethodCatalog.PINYIN), entry(InputMethodCatalog.ENGLISH), entry(InputMethodCatalog.WUBI))
        api.enabledIme = arrayOf(entry(InputMethodCatalog.PINYIN), entry(InputMethodCatalog.ENGLISH))
        open(SettingsRoute.InputMethodList)
        until("listed") { "Pinyin" in shown() }
        val rows = shown().filter { it in listOf("Pinyin", "English", "Wubi") }
        assertEquals(listOf("Pinyin", "English"), rows)
    }

    // 0.4's 九键拼音 is pinyin's keyboard now (I1): none to add, 五笔 first
    @Test
    @Uat("SET-12")
    fun addingAnInputMethodOffersNoNineKeysAndWubiFirst() {
        val all = listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH, "engine-erbi", "engine-cangjie", InputMethodCatalog.WUBI, "engine-ziranma", InputMethodCatalog.SHUANGPIN, "engine-wubipinyin", InputMethodCatalog.HANDWRITING)
        api.availableIme = all.map(::entry).toTypedArray()
        api.enabledIme = arrayOf(entry(InputMethodCatalog.PINYIN), entry(InputMethodCatalog.ENGLISH))
        open(SettingsRoute.InputMethodList)
        until("listed") { "Pinyin" in shown() }
        KeyboardHarness.all(activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        idle()
        val list = dialog().listView
        val items = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
        assertEquals(7, items.size)
        assertEquals("Wubi", items.first())
        assertTrue(items.toString(), items.none { "9" in it })
        list.performItemClick(list.getChildAt(0), 0, 0)
        idle()
        until("added") { "Wubi" in shown() }
        until("saved") { api.calls.any { it.startsWith("setEnabledIme") } }
        assertTrue(api.calls.toString(), api.calls.last { it.startsWith("setEnabledIme") }.endsWith("${InputMethodCatalog.WUBI})"))
    }

    // a key of the nine keys' page: its card, read out as its letters and digit
    private fun card(digit: Char): View = KeyboardHarness.all(activity.window.decorView)
        .first { it.isShown && it.contentDescription?.toString()?.endsWith(digit.toString()) == true && it.hasOnClickListeners() }

    private fun editKey(digit: Char, typed: String?, button: Int = AlertDialog.BUTTON_POSITIVE) {
        card(digit).performClick()
        idle()
        val d = dialog()
        if (typed != null) KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().single().setText(typed)
        // every per-key dialog names its reset 恢复此键默认, as uat T9-29 tells the tester to find it
        if (button == AlertDialog.BUTTON_NEUTRAL) assertEquals(activity.getString(R.string.reset_this_key), d.getButton(button).text.toString())
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
            KeyboardHarness(im = KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS).use { h ->
                h.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
                h.api.enabledIme = h.api.availableIme
                h.start()
                val seven = h.longPressed('7')
                assertTrue(seven.toString(), seven.containsAll(listOf("7", "p", "r", "s")) && "q" !in seven && "Q" !in seven)
                // emptied, 3 is as on a phone (the field's hint says so); its two symbols one row, not a column
                var rows = emptySet<Int>()
                val three = h.longPressed('3') {
                    // each cell's top, whichever draws the popup
                    rows = listOf("+", "-").map { t -> popupChoices().last { it.text == t }.cell.top }.toSet()
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
            val nine = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS, english = ImKeyboard.NINE_KEYS)
            KeyboardHarness(im = KeyboardHarness.PINYIN, keyboards = nine).use { h ->
                h.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
                h.api.enabledIme = h.api.availableIme
                h.start()
                val five = h.longPressed('5')
                assertTrue(five.toString(), "#" in five)
                val one = h.longPressed('1')
                assertTrue(one.toString(), "，" in one && "。" in one && "…" !in one)
                // nine keys English's 1: its own marks, half width, not the pinyin's
                h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
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
        // the popup's texts, whichever draws it
        val popup = popupChoices().map { it.text }
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
            assertTrue("${kind.name} in $titles", s(UserWordsFragment.title(kind)) in titles)
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
        // credited in every build: the voice input is (its models the voice pack's)
        assertNotNull(libri)
        libri!!
        assertEquals("CC-BY-4.0", libri.summary.toString())
        libri.performClick()
        idle()
        assertEquals("https://www.openslr.org/12/", shadowOf(activity).nextStartedActivity.dataString)
    }

    private fun listedPage(what: LearningOp.Listed, items: List<ListedItem>) {
        api.learned = { op -> if (op is LearningOp.ListItems && op.what == what) LearningResult.Listed(items) else LearningResult.Done() }
        open(SettingsRoute.LearnedItems(what.name))
        until("listed") { st.titles().containsAll(items.map { it.text }) }
    }

    private fun said(text: String) = st.pref(text).summary.toString()

    @Test
    @Uat("PY-94")
    fun aPinOnTheFourteenKeysSaysSoAndShowsItsLetters() {
        // as the engine lists a pin made on AS GH UI (WordsUatTest.aPinOnTheFourteenKeysIsListedUnderItsKeys)
        val keys = NineKeyLetters.G14.typings("shi").single()
        listedPage(LearningOp.Listed.PINS, listOf(ListedItem(Code(Kinds.keys(NineKeyLetters.G14.tag()), keys), "", "时")))
        assertEquals(s(R.string.learning_pinned_detail, "as gh ui", s(R.string.learning_kind_keys14)), said("时"))
    }

    @Test
    @Uat("SP-19")
    fun aPinOnTheEighteenKeysSaysSoWithItsSchemeAndLetters() {
        // as the engine lists them (WordsUatTest.pinsOnTheEighteenKeysAndTheFullListedUnderTheirKinds)
        val keys18 = "${NineKeyLetters.G18.key('u')}${NineKeyLetters.G18.key('i')}"
        listedPage(
            LearningOp.Listed.PINS,
            listOf(
                ListedItem(Code(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.G18.tag()), keys18), "", "时"),
                ListedItem(Code(Kinds.shuangpin("Ziranma"), "ui"), "", "使"),
            ),
        )
        val ziranma = ShuangpinNames.of(activity, "Ziranma")!!
        val keyboard18 = s(R.string.learning_kind_with_scheme, s(R.string.learning_kind_shuangpin_keys18), ziranma)
        assertEquals(s(R.string.learning_pinned_detail, "u io", keyboard18), said("时"))
        val full = s(R.string.learning_kind_with_scheme, s(R.string.learning_kind_shuangpin), ziranma)
        assertEquals(s(R.string.learning_pinned_detail, "ui", full), said("使"))
    }

    @Test
    @Uat("PY-72")
    fun aPredictionTurnedOffListedUnderBlockedAndTakenBack() {
        var listed = listOf(ListedItem(null, "你好", "啊"))
        api.learned = { op ->
            when (op) {
                LearningOp.Overview -> LearningResult.Overview(0, 0, 0, 0, listed.size, true)
                is LearningOp.ListItems -> LearningResult.Listed(if (op.what == LearningOp.Listed.NO_PREDICT) listed else emptyList())
                is LearningOp.RemoveItems -> {
                    listed = listed - op.items.toSet()
                    LearningResult.Done()
                }
                else -> LearningResult.Done()
            }
        }
        // in the lexicon page, right under the blocked words
        open(SettingsRoute.UserWords)
        val titles = st.titles()
        val blocked = titles.indexOf(s(UserWordsFragment.title(UserWord.Kind.BLOCKED)))
        assertEquals(titles.toString(), blocked + 1, titles.indexOf(s(R.string.my_words_no_predict)))
        open(SettingsRoute.UserWordList(UserWord.Kind.BLOCKED.name))
        val row = s(R.string.learning_no_predict_entry, 1)
        until("the row on top") { row in shown() }
        view(row).performClick()
        until("listed") { "啊" in st.titles() }
        assertEquals(s(R.string.learning_no_predict_detail, "你好"), said("啊"))
        st.pref("啊").performClick()
        idle()
        val again = dialog().getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(s(R.string.learning_suggest_again), again.text.toString())
        again.performClick()
        until("taken back") { "啊" !in st.titles() }
        assertTrue(api.calls.toString(), api.calls.any { it.startsWith("learning(RemoveItems") && "你好" in it })
    }

    @Test
    @Uat("EN-04", "EN-26")
    fun anEnglishWordNoLongerCorrectedIsMarkedAndCorrectedAgainOnATap() {
        api.englishWords += UserWord("teh", "", UserWord.Kind.LEARNED, count = 1f, neverCorrected = true)
        api.learned = { op ->
            if (op is LearningOp.RestoreCorrection) {
                api.englishWords.replaceAll { if (it.text in op.texts) it.copy(neverCorrected = false) else it }
            }
            LearningResult.Done()
        }
        open(SettingsRoute.UserWordList(UserWord.Kind.LEARNED.name, english = true))
        until("listed") { "teh" in shown() }
        assertEquals(s(R.string.english_word_learned), shown()[shown().indexOf("teh") + 1])
        act("teh", R.string.english_restore_correction)
        until("corrected again") { shown().getOrNull(shown().indexOf("teh") + 1).let { it != null && it != s(R.string.english_word_learned) } }
        assertTrue(api.calls.toString(), "learning(RestoreCorrection(texts=[teh]))" in api.calls)
    }

    private fun wordDialog(text: String, pinyin: String) {
        val d = dialog()
        val (t, p) = KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().toList()
        t.setText(text)
        p.setText(pinyin)
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    // a row tapped, one of what its dialog offers picked
    private fun act(word: String, action: Int) {
        clickable(view(word)).performClick()
        idle()
        val l = dialog().listView
        val items = (0 until l.adapter.count).map { l.adapter.getItem(it).toString() }
        val at = items.indexOf(s(action))
        assertTrue(items.toString(), at >= 0)
        l.performItemClick(null, at, at.toLong())
        idle()
    }

    private fun addedCount(): String {
        open(SettingsRoute.UserWords)
        val title = UserWordsFragment.title(UserWord.Kind.ADDED)
        until("counted") { !st.pref(title).summary.isNullOrEmpty() }
        return st.pref(title).summary.toString()
    }

    @Test
    @Uat("WD-03")
    fun aWordAddedByHandListedCountedEditedDeleted() {
        api.learned = { op ->
            if (op is LearningOp.RemoveWords) api.userWords.removeAll(op.words.toSet())
            LearningResult.Done()
        }
        assertEquals(activity.resources.getQuantityString(R.plurals.word_count, 0, 0), addedCount())
        open(SettingsRoute.UserWordList(UserWord.Kind.ADDED.name))
        KeyboardHarness.all(activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        idle()
        wordDialog("澜境花园", "lan jing hua yuan")
        until("listed") { "澜境花园" in shown() }
        assertTrue(api.calls.toString(), "addUserWord(澜境花园, lan jing hua yuan)" in api.calls)
        assertEquals(activity.resources.getQuantityString(R.plurals.word_count, 1, 1), addedCount())
        open(SettingsRoute.UserWordList(UserWord.Kind.ADDED.name))
        until("listed") { "澜境花园" in shown() }
        act("澜境花园", R.string.edit)
        // filled in with the word's own
        val fields = KeyboardHarness.all(dialog().window!!.decorView).filterIsInstance<EditText>().map { it.text.toString() }.toList()
        assertEquals(listOf("澜境花园", "lan jing hua yuan"), fields)
        wordDialog("澜境公园", "lan jing gong yuan")
        until("edited") { "澜境公园" in shown() && "澜境花园" !in shown() }
        assertTrue(api.calls.toString(), "addUserWord(澜境公园, lan jing gong yuan)" in api.calls)
        act("澜境公园", R.string.delete)
        until("deleted") { "澜境公园" !in shown() }
        assertTrue(api.userWords.toString(), api.userWords.isEmpty())
    }

    private fun phraseDialog(key: String?, order: String?, phrase: String?, button: Int = AlertDialog.BUTTON_POSITIVE) {
        val d = dialog()
        val fields = KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().toList()
        listOf(key, order, phrase).zip(fields).forEach { (v, f) -> if (v != null) f.setText(v) }
        d.getButton(button).performClick()
        idle()
    }

    // the page left, as going back to type: it saves then, and the engine reads it again
    private fun phrasesSaved(): List<String> {
        open(SettingsRoute.About)
        until("saved") { "reloadEngine()" in api.calls }
        api.calls.clear()
        return CustomPhraseManager.load().map { "${it.key},${it.order}=${it.value}" }
    }

    // a tap on the row's text, not its pen
    private fun tapPhrase(text: String) {
        open(SettingsRoute.PinyinCustomPhrase)
        until("listed") { shown().any { text in it } }
        clickable(KeyboardHarness.all(activity.window.decorView).first { it.isShown && it is TextView && text in it.text }).performClick()
        idle()
    }

    @Test
    @Uat("WD-18", "WD-35")
    fun aCustomPhraseAddedEditedOnItsRowAndDeleted() {
        CustomPhraseManager.save(emptyList())
        try {
            open(SettingsRoute.PinyinCustomPhrase)
            KeyboardHarness.all(activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
            idle()
            // the key field's filter makes capitals lowercase as they are typed, so every keyboard reaches
            // it; a capital being composed keeps its input method's composing span on the letter made
            // lowercase, else one that composes and then appends would type the letters twice (F4955; F4973:
            // the capital, not a letter already lowercase, is what reaches that branch)
            val key = KeyboardHarness.all(dialog().window!!.decorView).filterIsInstance<EditText>().first()
            key.setText("DZ")
            val typed = SpannableString("Xx").also { BaseInputConnection.setComposingSpans(it) }
            key.text.replace(2, 2, typed, 0, 1)
            assertEquals("dzx", key.text.toString())
            assertEquals(2 to 3, BaseInputConnection.getComposingSpanStart(key.text) to BaseInputConnection.getComposingSpanEnd(key.text))
            key.text.delete(2, 3)
            phraseDialog(null, "1", "北京市海淀区")
            assertEquals(listOf("dz,1=北京市海淀区"), phrasesSaved())
            tapPhrase("北京市海淀区")
            val filled = KeyboardHarness.all(dialog().window!!.decorView).filterIsInstance<EditText>().map { it.text.toString() }.toList()
            assertEquals(listOf("dz", "1", "北京市海淀区"), filled)
            phraseDialog(null, null, "上海市")
            assertEquals(listOf("dz,1=上海市"), phrasesSaved())
            tapPhrase("上海市")
            assertEquals(s(R.string.delete), dialog().getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
            phraseDialog(null, null, null, AlertDialog.BUTTON_NEUTRAL)
            assertEquals(emptyList<String>(), phrasesSaved())
        } finally {
            CustomPhraseManager.save(emptyList())
        }
    }

    // F4974: the page could be edited while its file was still being read, and what was added then was
    // taken for the file's and never saved; F5001: import and export, off till the read ends; F5003: an
    // import delivered before the read (the activity made anew under the picker) waits for it; F5008: so
    // does an export, which wrote an empty file
    @Test
    @Uat("WD-37")
    fun nothingIsAddedToTheCustomPhrasesBeforeTheirFileIsRead() {
        CustomPhraseManager.save(listOf(PinyinCustomPhrase("bj", 1, "北京")))
        val read = CountDownLatch(1)
        val load = PinyinCustomPhraseFragment.load
        PinyinCustomPhraseFragment.load = { read.await(); load() }
        try {
            open(SettingsRoute.PinyinCustomPhrase)
            idle()
            val fab = KeyboardHarness.all(activity.window.decorView).filterIsInstance<FloatingActionButton>().single()
            assertNotEquals(View.VISIBLE, fab.visibility)
            val menu = KeyboardHarness.all(activity.window.decorView).filterIsInstance<Toolbar>().first().menu
            fun usable() = listOf(R.string.import_file, R.string.export_file).map { id ->
                (0 until menu.size()).map(menu::getItem).single { it.title.toString() == s(id) }.isEnabled
            }
            assertEquals(listOf(false, false), usable())
            // the file's phrase again, which is not added twice, and a new one
            val file = Uri.parse("content://phrases.test/import.txt")
            shadowOf(activity.contentResolver).registerInputStream(file, ByteArrayInputStream("bj,1=北京\nsh,1=上海\n".toByteArray()))
            (page as PinyinCustomPhraseFragment).importPhrases(file)
            // an export delivered before the read too (F5008): the file's phrase, not an empty file
            val exportTo = Uri.parse("content://phrases.test/export.txt")
            val exported = ByteArrayOutputStream()
            shadowOf(activity.contentResolver).registerOutputStream(exportTo, exported)
            (page as PinyinCustomPhraseFragment).exportPhrases(exportTo)
            idle()
            read.countDown()
            until("listed") { shown().any { "上海" in it } }
            until("addable") { fab.visibility == View.VISIBLE }
            until("importable") { usable() == listOf(true, true) }
            until("exported") { "bj,1=北京" in exported.toString(Charsets.UTF_8) }
            // the file's phrase not counted again
            assertEquals(s(R.string.import_phrases_done, 1), ShadowToast.getTextOfLatestToast())
            assertEquals(listOf("bj,1=北京", "sh,1=上海"), phrasesSaved())
        } finally {
            read.countDown()
            PinyinCustomPhraseFragment.load = load
            CustomPhraseManager.save(emptyList())
        }
    }

    private fun commonWordsSaved(): List<String> {
        open(SettingsRoute.About)
        until("saved") { "reloadEngine()" in api.calls }
        api.calls.clear()
        return CommonWordsManager.load().words.map { it.toString() }
    }

    // 常用词 (060-common-words): added with the pinyin left blank, filled as the dictionary reads it for
    // the user to check, the replacements one a line; listed with the keys that type it; a file imported
    // after saying what it adds and skips; exported tab-separated under its header
    @Test
    @Uat("WD-39", "WD-42")
    fun aCommonWordAddedImportedAndExported() {
        CommonWordsManager.write(CommonWords.EMPTY)
        api.readings["米字旁"] = "mi zi pang"
        try {
            open(SettingsRoute.PinyinDict(""))
            until("listed on the words page") {
                preferences((page as PreferenceFragmentCompat).preferenceScreen).any { it.title == s(R.string.common_words) }
            }
            open(SettingsRoute.CommonWords)
            KeyboardHarness.all(activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
            idle()
            val d = dialog()
            val (text, pinyin, replacements) = KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().toList()
            text.setText("米字旁")
            replacements.setText("米字旁")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            until("pinyin filled") { pinyin.text.toString() == "mi zi pang" }
            assertEquals(s(R.string.common_word_same_as_word), d.getButton(AlertDialog.BUTTON_POSITIVE).let { it.performClick(); idle(); replacements.error?.toString() })
            replacements.setText("米\n糸")
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            until("listed with its keys") { "米字旁 → 米、糸\nmizipang · mzp" in shown() }
            assertEquals(listOf("米字旁[mi'zi'pang] 米 糸"), commonWordsSaved())
            open(SettingsRoute.CommonWords)
            until("read") { shown().any { "米字旁" in it } }
            val file = Uri.parse("content://words.test/import.txt")
            shadowOf(activity.contentResolver).registerInputStream(
                file,
                ByteArrayInputStream("木字旁 木\n中华人民共和国[zhong hua ren min gong he guo] 中国\n米字旁[mi'zi'pang]\n".toByteArray()),
            )
            api.readings["木字旁"] = "mu zi pang"
            (page as CommonWordsFragment).importWords(file)
            val asked = dialog()
            val said = KeyboardHarness.all(asked.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertTrue(said, s(R.string.common_words_import_counts, 2, 0, 1) in said)
            assertTrue(said, s(R.string.common_words_import_line, 3, "米字旁[mi'zi'pang]", s(R.string.common_word_no_replacement)) in said)
            asked.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            val exportTo = Uri.parse("content://words.test/export.txt")
            val exported = ByteArrayOutputStream()
            shadowOf(activity.contentResolver).registerOutputStream(exportTo, exported)
            (page as CommonWordsFragment).exportWords(exportTo)
            until("exported") { exported.size() > 0 }
            assertEquals(
                "# youmo-common-words 1\n米字旁[mi'zi'pang]\t米\t糸\n木字旁[mu'zi'pang]\t木\n中华人民共和国[zhong'hua'ren'min'gong'he'guo]\t中国\n",
                exported.toString(Charsets.UTF_8),
            )
            assertEquals(3, commonWordsSaved().size)
        } finally {
            CommonWordsManager.write(CommonWords.EMPTY)
        }
    }

    @Test
    @Uat("MN-22")
    fun thePreviewsBarFollowsTheCandidateSizeOnThePage() {
        val size = ThemeManager.prefs.candidateTextSize
        try {
            st.activity
            st.open(SettingsRoute.Theme)
            st.tab(1)
            // the preview's keys: where they start under the bar, and how tall they are
            fun keys(): Pair<Int, Int> {
                st.tab(1)
                val k = KeyboardHarness.find<TextKeyboard>(st.activity.window.decorView)!!
                val at = IntArray(2).also { k.getLocationInWindow(it) }
                return at[1] to k.height
            }
            val (top, height) = keys()
            size.setValue(28)
            val (bigTop, bigHeight) = keys()
            size.setValue(14)
            val (smallTop, smallHeight) = keys()
            assertTrue("$smallTop < $top < $bigTop", smallTop < top && top < bigTop)
            assertEquals(listOf(height, height), listOf(bigHeight, smallHeight))
        } finally {
            size.setValue(size.defaultValue)
        }
    }
}
