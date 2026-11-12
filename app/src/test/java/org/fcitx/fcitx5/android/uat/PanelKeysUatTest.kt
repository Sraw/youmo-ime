/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.ChinesePunctuation
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.input.picker.EmojiSearchUi
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.input.popup.PopupFocus
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panels' function keys as every keyboard's (060-keys-unify §6.2, D5, J1–J3): ⌫'s, the space's and Return's
 * slots and marks on the symbol panel, the emoji, the emoticons, the expanded candidates and the emoji search
 * (whose 清空 and 恢复 are its query's); their ，。 long presses; the action long presses the number pad and the
 * symbol panel's 123 gained; a password field's 清空 not run there either; a symbol layer of 32 shown whole.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class PanelKeysUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var made: KeyboardHarness? = null

    @After
    fun close() {
        made?.close()
        AppPrefs.getInstance().internal.symbolPanelLocked.setValue(false)
    }

    private val written = "你好"

    private val zh = ChinesePunctuation.map

    private fun harness(inputType: Int = InputType.TYPE_CLASS_TEXT): KeyboardHarness {
        made?.close()
        return KeyboardHarness().also { made = it }.start(inputType, prefill = { it.setText(written); it.setSelection(written.length) })
    }

    // the panels, each opened from the letters
    private fun KeyboardHarness.symbols() = tap(key("符"))

    private fun KeyboardHarness.emoji() = tap(described(service.getString(R.string.a11y_key_emoji)))

    private fun KeyboardHarness.emoticons() {
        emoji()
        tap(keyNamed("emoticon"))
    }

    private fun KeyboardHarness.expanded() {
        api.candidates = Array(60) { CandidateWord("", "字$it", "") }
        emit(HostEvent.Candidates(60, api.candidates.toList()))
        tap(described(service.getString(R.string.expand_candidates_list)))
    }

    private fun KeyboardHarness.search(): EmojiSearchUi {
        emoji()
        tap(keyNamed("search"))
        return KeyboardHarness.find<EmojiSearchUi>(inputView)!!.also { s -> "xiao".forEach { c -> tap(searchKeys(s).first { it.label == "$c" }) } }
    }

    private fun KeyboardHarness.searchKeys(s: EmojiSearchUi) = keys(s.keyboard)

    // the panel's key named [name] (fn/backspace…) among [keys]
    private fun List<ShownKey>.named(name: String): SceneShownKey =
        filterIsInstance<SceneShownKey>().single { k -> k.node.copy == 0 && k.node.shortcut?.let { KeyId.of(it).name } == name }

    private fun KeyboardHarness.named(name: String) = keys().named(name)

    private fun KeyboardHarness.swipeUp(k: ShownKey) = swipe(k, 0f, -k.height * 1.3f)

    private fun KeyboardHarness.swipeDown(k: ShownKey) = swipe(k, 0f, k.height * 1.3f)

    private val panels: List<Pair<String, KeyboardHarness.() -> Unit>> = listOf(
        "符号" to { symbols() },
        "表情" to { emoji() },
        "颜文字" to { emoticons() },
        "展开候选" to { expanded() },
    )

    @Test
    @Uat("PAN-25")
    fun slotsAndMarks() {
        prefs.setWays(up = true, down = true)
        for ((panel, open) in panels) {
            val h = harness()
            h.open()
            val backspace = h.named("fn/backspace")
            assertEquals("$panel: 清空 at 上滑's place, 恢复 at 下滑's", listOf("TR", "TL"), backspace.markPlaces().shortcuts)
            h.swipeUp(backspace)
            h.waitFor("$panel: the field cleared") { h.field.text.isEmpty() }
            h.swipeDown(h.named("fn/backspace"))
            h.waitFor("$panel: put back") { h.field.text.toString() == written }
        }
        // the symbol panel's space: up, the edit menu, held open under the finger
        val h = harness()
        h.symbols()
        val space = h.named("fn/space")
        val icons = h.shownIcons().size
        val finger = h.finger(space, h.at(space))
        for (i in 1..6) finger.move(h.at(space, dy = -space.height * 0.25f * i, ms = 16))
        h.idle()
        assertTrue(h.shownIcons().toString(), h.shownIcons().size > icons)
        finger.up(h.at(space))
        h.idle()
        assertEquals(written, h.field.text.toString())
    }

    // J1: the search's ⌫ clears and restores what is searched for, not the field
    @Test
    @Uat("PAN-25")
    fun theSearchsSlotsAreItsQuerys() {
        prefs.setWays(up = true, down = true)
        val h = harness()
        val s = h.search()
        fun query() = KeyboardHarness.all(s).filterIsInstance<TextView>().first().text.toString()
        assertEquals("xiao", query())
        val backspace = h.searchKeys(s).named("fn/backspace")
        assertEquals(listOf("TR", "TL"), backspace.markPlaces().shortcuts)
        h.swipeUp(backspace)
        assertEquals(h.service.getString(R.string.emoji_search_hint), query())
        assertEquals("the field not the query: kept", written, h.field.text.toString())
        h.swipeDown(h.searchKeys(s).named("fn/backspace"))
        assertEquals("xiao", query())
        // nothing taken: 恢复 again does nothing, nor 清空 of an empty query to the field
        h.swipeDown(h.searchKeys(s).named("fn/backspace"))
        assertEquals("xiao", query())
        "xiao".forEach { _ -> h.tap(h.searchKeys(s).named("fn/backspace")) }
        h.swipeUp(h.searchKeys(s).named("fn/backspace"))
        assertEquals(written, h.field.text.toString())
        assertFalse(h.api.calls.toString(), "clearTyped()" in h.api.calls)
    }

    // PAN-26: the emoticons' ，。 and the emoji's 。 long pressed: the letters' lists
    @Test
    @Uat("PAN-26")
    fun punctuationLongPress() {
        // the same items; laid out from where the key is, so their order on the screen is not compared
        fun KeyboardHarness.held(name: String) = longPress(named(name)).sorted()
        // Chinese punctuation on: shown as it types (KB-83), so what is picked is what is typed
        fun chinese() = harness().also { h ->
            val active = listOf(StatusEntry(1, "punctuation", "fcitx-punc-active", ""))
            h.api.statusAreaCached = active
            h.api.punctuationCached = zh
            h.emit(HostEvent.StatusArea(active, KeyboardHarness.PINYIN, zh))
            h.idle()
        }
        var h = chinese()
        val comma = h.held("fn/comma")
        val period = h.held("fn/period")
        assertTrue(comma.toString(), comma.size > 1 && "。" in comma)
        h = chinese()
        h.emoticons()
        assertEquals(comma, h.held("fn/comma"))
        assertEquals(period, h.held("fn/period"))
        h = chinese()
        h.emoji()
        assertEquals(period, h.held("fn/period"))
        // as the letters' ，: held and let go, its first typed as the punctuation has it and as it was shown
        val key = h.named("fn/period")
        val before = h.field.text.toString()
        val f = h.finger(key, h.at(key))
        h.advance(700)
        f.move(h.at(key))
        val focused = h.popupChoices().single { it.focused }.text
        f.up(h.at(key))
        h.idle()
        assertEquals("，", focused)
        assertEquals(before + focused, h.field.text.toString())
    }

    // a password field: a command that reads the field (复制, set on ⌫'s 上滑) not run from a panel either, as on
    // the letters (KeySwipe); a plain field's copied, the same swipe
    @Test
    @Uat("PAN-27")
    fun aPasswordIsNotCopiedFromAPanel() {
        prefs.setWays(up = true, down = true)
        prefs.set(AppPrefs.getInstance().internal.keySwipes, SwipeSlots.Default.with("fn/backspace", true, SwipeSlot.Command(KeyCommand.Copy)).serialize())
        val clipboard = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java)
        fun copied(inputType: Int): String? {
            clipboard.setPrimaryClip(ClipData.newPlainText("", "-"))
            val h = harness(inputType)
            h.field.selectAll()
            h.symbols()
            h.swipeUp(h.named("fn/backspace"))
            h.idle()
            assertEquals(written, h.field.text.toString())
            return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(written, copied(InputType.TYPE_CLASS_TEXT))
        assertEquals("-", copied(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }

    // J2: the number pad's space and !?#, the symbol panel's 123, held: what they do on every keyboard
    @Test
    @Uat("PAN-28")
    fun actionLongPressesEverywhere() {
        var h = harness(InputType.TYPE_CLASS_NUMBER)
        h.api.calls.clear()
        h.longPress(h.named("fn/symbols"))
        assertTrue(h.api.calls.toString(), "triggerQuickPhrase()" in h.api.calls)
        h = harness()
        h.symbols()
        h.api.calls.clear()
        h.longPress(h.named("fn/numbers"))
        assertTrue(h.api.calls.toString(), "triggerUnicode()" in h.api.calls)
    }

    // J2: the number pad's space held does what the letters' does
    @Test
    @Uat("PAN-28")
    fun numberPadsSpaceHeldAsTheLetters() {
        // a behaviour the fake host records (the default talks, with no voice pack here nothing)
        prefs.set(AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior, SpaceLongPressBehavior.Enumerate)
        fun held(inputType: Int): List<String> {
            val h = harness(inputType)
            h.api.calls.clear()
            h.longPress(h.named("fn/space"))
            h.idle()
            return h.api.calls.toList()
        }
        val letters = held(InputType.TYPE_CLASS_TEXT)
        assertTrue(letters.toString(), "enumerateIme(true)" in letters)
        assertEquals(letters, held(InputType.TYPE_CLASS_NUMBER))
    }

    // J3: ，'s list at the most a layer holds (32), every item on the screen, the last picked: the finger slid
    // from the item first under it, the popup's focus moving by PopupFocus.STEP, not by where its cells are
    private fun aLayerOf32() {
        val items = (0 until 32).map { "${'Ａ' + it % 26}${it / 26}" }
        prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.of(mapOf(KeyId("fn/comma") to UserList(false, items))).serialize())
        val h = harness()
        val comma = h.named("fn/comma")
        val f = h.finger(comma, h.at(comma))
        h.advance(700)
        f.move(h.at(comma))
        val shown = h.popupChoices()
        assertEquals(items.sorted(), shown.map { it.text }.sorted())
        val screen = android.graphics.Rect(0, 0, h.inputView.rootView.width, h.inputView.rootView.height)
        shown.forEach { assertTrue("${it.text} ${it.cell} in $screen", screen.contains(it.cell)) }
        fun centre(k: KeyboardHarness.PopupKey) = k.cell.exactCenterX() to k.cell.exactCenterY()
        val (a, b) = centre(shown.first { it.focused }) to centre(shown.first { it.text == items.last() })
        val step = PopupFocus.STEP / h.service.resources.displayMetrics.density
        val p = h.at(comma, (b.first - a.first) * step, (b.second - a.second) * step)
        f.move(p)
        f.up(p)
        h.idle()
        assertEquals(written + items.last(), h.field.text.toString())
    }

    @Test
    @Uat("KB-82")
    @Config(qualifiers = "w320dp-h568dp-port-xhdpi")
    fun aLayerOf32OnTheNarrowestOneHanded() {
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        aLayerOf32()
    }

    @Test
    @Uat("KB-82")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun aLayerOf32Landscape() {
        aLayerOf32()
    }
}
