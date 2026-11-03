/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPadView
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.parcelable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPopupMenu
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * Quick input on the keyboard (uat/11 QU-12, QU-17, QU-18) over a fake engine: the handwriting pad
 * lending its place to the full keyboard, a candidate's card, the clipboard's 存为快速输入.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class QuickPhraseKeyboardUatTest {

    private val handwriting = InputMethodEntry(InputMethodCatalog.HANDWRITING, "手写", "", "", "写", "zh_CN", "androidengine", false)

    private var h = KeyboardHarness()

    private val addr = QuickPhraseEntry("addr", "北京市海淀区\n3 号楼")

    @Before
    fun phone() {
        phoneStorage()
        File(HostConfigDir.inUse(ConfigHome.DATA).dir, QuickPhraseBook.DIR).deleteRecursively()
    }

    @After
    fun close() = h.close()

    private fun s(id: Int, vararg args: Any) = h.service.getString(id, *args)

    private fun onHandwriting() {
        h.close()
        h = KeyboardHarness(im = handwriting).apply {
            api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, handwriting)
            api.enabledIme = api.availableIme
        }
        h.start()
    }

    private fun quickInput(on: Boolean) {
        val aux = if (on) FormattedText(arrayOf("快速输入: "), intArrayOf(0), -1) else FormattedText.Empty
        h.emit(HostEvent.InputPanel(FormattedText.Empty, aux, FormattedText.Empty, quickPhrase = on))
    }

    private fun padShown() = KeyboardHarness.find<HandwritingPadView>(h.inputView)?.let { h.shown(it) } == true

    private fun lettersShown() = "q" in h.texts() && "p" in h.texts()

    @Test
    @Uat("QU-12")
    fun handwritingLendsItsPlaceToTheFullKeyboardWhileQuickInputLasts() {
        onHandwriting()
        assertTrue(padShown())
        quickInput(true)
        assertFalse(padShown())
        assertTrue(h.texts().toString(), lettersShown())
        // a key typed keeps it: the panel again, still quick input's
        quickInput(true)
        assertTrue(lettersShown())
        quickInput(false)
        assertTrue(padShown())
        // the pad's own panels bring nothing
        quickInput(false)
        assertTrue(padShown())
    }

    // review Q1: the input method changed under quick input, its change told before quick input's end:
    // the new input method's letters stay, the pad is not brought back over them
    @Test
    fun anotherInputMethodChosenDuringQuickInputKeepsItsOwnLetters() {
        onHandwriting()
        quickInput(true)
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
        quickInput(false)
        assertFalse(padShown())
        assertTrue(h.texts().toString(), lettersShown())
        // and back on handwriting it is the pad, its own again
        h.emit(HostEvent.InputMethodChanged(handwriting))
        assertTrue(padShown())
        // told the other way round, the same
        quickInput(true)
        quickInput(false)
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
        assertFalse(padShown())
        assertTrue(lettersShown())
    }

    // the zhuyin keyboards send no letters either: 大千 and 注音九键 give way as the pad does, and an
    // input method chosen meanwhile keeps its own letters, the zhuyin keyboard not brought back over them
    @Test
    @Uat("QU-12")
    fun theZhuyinKeyboardsLendTheirPlaceAsThePadDoes() {
        val told = AppPrefs.getInstance().internal.zhuyinTraditionalTold
        val wasTold = told.getValue()
        told.setValue(true)
        try {
            for (keyboard in listOf(ImKeyboard.ZHUYIN_FULL, ImKeyboard.ZHUYIN_NINE)) {
                h.close()
                h = KeyboardHarness(keyboards = ImKeyboards.Chosen(pinyin = keyboard)).apply {
                    api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
                    api.enabledIme = api.availableIme
                }
                h.start()
                assertTrue("$keyboard: ${h.texts()}", zhuyinShown() && !lettersShown())
                quickInput(true)
                assertTrue("$keyboard: ${h.texts()}", lettersShown() && !zhuyinShown())
                quickInput(false)
                assertTrue("$keyboard: ${h.texts()}", zhuyinShown())
                // another input method while it lasts: its letters stay after
                quickInput(true)
                h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
                quickInput(false)
                assertTrue("$keyboard: ${h.texts()}", lettersShown() && !zhuyinShown())
                h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
                assertTrue("$keyboard: ${h.texts()}", zhuyinShown())
            }
        } finally {
            told.setValue(wasTold)
        }
    }

    private fun zhuyinShown() = h.texts().any { it.contains('ㄅ') }

    private fun candidates(vararg words: String) {
        h.api.candidates = Array(words.size) { CandidateWord(if (it == 0) "addr" else "", words[it], "") }
        h.emit(HostEvent.Candidates(words.size, h.api.candidates.toList()))
    }

    private fun lines(): List<String> {
        val m = h.inputView.candidateActionMenu ?: error("no menu")
        return (0 until m.listView!!.adapter.count).map { m.listView!!.adapter.getItem(it).toString() }
    }

    private fun longPress(word: String) {
        val text = KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().first { h.shown(it) && it.text.toString() == word }
        generateSequence(text as View) { it.parent as? View }.first { it.isLongClickable }.performLongClick()
        h.idle()
    }

    private fun pick(label: String) {
        val at = lines().indexOf(label)
        check(at > 0) { "no $label in ${lines()}" }
        h.inputView.candidateActionMenu!!.performItemClick(at)
        h.idle()
    }

    @Test
    @Uat("QU-17")
    fun aCandidatesCardCommitsCopiesEditsDeletesAndUndoes() {
        QuickPhraseManager.put(null, addr, now = 1)
        h.start()
        h.api.quickPhrases[0] = QuickPhraseBook.Found(addr.key, addr.content, true)
        h.api.quickPhrases[1] = QuickPhraseBook.Found("ghost", "👻", false)
        candidates("北京市海淀区⏎", "👻")
        // a built-in one: put in or copied, nothing else
        longPress("👻")
        assertEquals(listOf(s(R.string.quick_phrase_commit), s(R.string.quick_phrase_copy)), lines().drop(1))
        pick(s(R.string.quick_phrase_copy))
        assertEquals("👻", h.service.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        // the user's own: its whole text copied, not the bar's excerpt
        longPress("北京市海淀区⏎")
        assertEquals(listOf(s(R.string.quick_phrase_commit), s(R.string.quick_phrase_copy), s(R.string.edit), s(R.string.delete)), lines().drop(1))
        pick(s(R.string.quick_phrase_copy))
        assertEquals(addr.content, h.service.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        longPress("北京市海淀区⏎")
        h.api.calls.clear()
        pick(s(R.string.quick_phrase_commit))
        h.waitFor("selected") { h.api.calls.any { it == "select(0, 北京市海淀区⏎)" } }
        longPress("北京市海淀区⏎")
        pick(s(R.string.edit))
        val opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertEquals(
            SettingsRoute.QuickPhrase(edit = true, key = addr.key, content = addr.content, existing = true),
            opened.parcelable<SettingsRoute>(MainActivity.EXTRA_SETTINGS_ROUTE),
        )
        // deleted, the same menu then offering its undo
        longPress("北京市海淀区⏎")
        h.api.calls.clear()
        pick(s(R.string.delete))
        h.waitFor("deleted") { QuickPhraseManager.load().isEmpty() && "reloadConfig()" in h.api.calls }
        assertEquals(listOf(s(R.string.quick_phrase_deleted), s(R.string.undo)), lines().drop(1))
        h.api.calls.clear()
        pick(s(R.string.undo))
        h.waitFor("back") { QuickPhraseManager.load() == listOf(addr) && "reloadConfig()" in h.api.calls }
    }

    private fun held(text: String) {
        val v = KeyboardHarness.all(h.inputView).first { h.shown(it) && it is TextView && it.text.toString().startsWith(text) }
        generateSequence(v) { it.parent as? View }.first { it.isLongClickable }.performLongClick()
        h.idle()
    }

    private fun menuTitles(): List<String> {
        val menu = ShadowPopupMenu.getLatestPopupMenu().menu
        return (0 until menu.size()).map { menu.getItem(it).title.toString() }
    }

    @Test
    @Uat("QU-18")
    fun theClipboardSavesAnEntryAsQuickInputButNotAPasswordNorOneTooLong() {
        h.start()
        h.copied("北京市海淀区 3 号楼")
        h.clipboard("北京市海淀区 3 号楼")
        held("北京市海淀区 3 号楼")
        h.menuPick(s(R.string.clipboard_save_quick_phrase))
        val opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(
            SettingsRoute.QuickPhrase(edit = true, content = "北京市海淀区 3 号楼"),
            opened.parcelable<SettingsRoute>(MainActivity.EXTRA_SETTINGS_ROUTE),
        )
        // review M2: past the page's limit, told and not opened (its text would not fit the Intent)
        val long = "长".repeat(QuickPhraseBook.MAX_CONTENT + 1)
        h.copied(long)
        h.waitFor("the long one listed") { KeyboardHarness.all(h.inputView).any { h.shown(it) && it is TextView && it.text.startsWith("长长长") } }
        held("长长长")
        h.menuPick(s(R.string.clipboard_save_quick_phrase))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertEquals(s(R.string.quick_phrase_too_long_to_save, QuickPhraseBook.MAX_CONTENT), ShadowToast.getTextOfLatestToast())
        // a password (shown as dots): not offered
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastClipTimestamp = -1L
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry = null
        val clip = ClipData.newPlainText("", "mima1234").apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        h.service.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        h.waitFor("stored") { org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry?.text == "mima1234" }
        h.waitFor("dots listed") { KeyboardHarness.all(h.inputView).any { h.shown(it) && it is TextView && it.text.startsWith("•") } }
        held("•")
        assertTrue(menuTitles().toString(), s(R.string.delete) in menuTitles())
        assertFalse(menuTitles().toString(), s(R.string.clipboard_save_quick_phrase) in menuTitles())
    }
}
