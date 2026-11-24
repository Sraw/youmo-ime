/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.directBoot
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keys.ScrollListStore
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.parcelable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPopupMenu

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ScrollColumnMenuTest {

    private var harness: KeyboardHarness? = null
    private val h get() = harness!!

    private val pref get() = AppPrefs.getInstance().internal.scrollLists

    private fun start(im: InputMethodEntry = KeyboardHarness.PINYIN, inputType: Int = InputType.TYPE_CLASS_TEXT) {
        harness = KeyboardHarness(im = im, keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS, english = ImKeyboard.NINE_KEYS)).also {
            it.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
            it.api.enabledIme = it.api.availableIme
            it.start(inputType)
        }
    }

    @After
    fun close() {
        harness?.close()
        pref.setValue(ScrollListStore.HEADER)
        EngineState.syllablesState.value = EngineState.SyllableState()
    }

    private fun s(id: Int) = h.service.getString(id)

    private fun column(): List<TextView> = KeyboardHarness.all(h.keyboard).filterIsInstance<ScrollView>().first { h.shown(it) }
        .let { it.getChildAt(0) as ViewGroup }.children.filterIsInstance<TextView>().filter { it.isVisible }.toList()

    private fun stored(id: String) = ScrollListStore.parse(pref.getValue())[id]

    private data class Item(val label: String, val disabled: Boolean)

    // the latest system menu, if it is still up
    private fun menu(): List<Item> = ShadowPopupMenu.getLatestPopupMenu()?.takeIf { shadowOf(it).isShowing }?.menu
        ?.let { m -> (0 until m.size()).map { m.getItem(it) }.map { Item(it.title.toString(), !it.isEnabled) } }.orEmpty()

    private fun hold(item: TextView) {
        h.press(item)
        h.after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
    }

    /** Held, let go, then [label] tapped in the menu left open. */
    private fun pick(item: TextView, label: String) {
        hold(item)
        h.release()
        h.menuPick(label)
    }

    private fun said(v: TextView) = AccessibilityNodeInfoCompat.wrap(v.createAccessibilityNodeInfo()).actionList.mapNotNull { it.label?.toString() }

    private val typed get() = h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") }

    private fun remove(v: TextView) = s(R.string.scroll_list_remove).replace("%1\$s", v.text.toString())

    private fun ops(v: TextView) = listOf(s(R.string.scroll_list_to_front), s(R.string.scroll_list_edit), remove(v))

    private fun undo() {
        val undo = KeyboardHarness.all(h.notice()!!.contentView).filterIsInstance<TextView>().last { it.text.toString() == s(R.string.learning_undo) }
        assertTrue(undo.performClick())
        h.idle()
    }

    private val nine get() = ScrollDefaults.of(ScrollDefaults.NINE)!!

    @Test
    @Uat("KB-117", "PAN-28")
    fun aHeldItemsMenuStaysAfterTheLiftAndMovesToTheFrontUndone() {
        start()
        val question = column()[2]
        hold(question)
        assertEquals(ops(question), menu().map { it.label })
        assertEquals(listOf(false, false, false), menu().map { it.disabled })
        assertTrue("held, its item shows it", question.isActivated)
        h.api.calls.clear()
        h.release()
        assertEquals("let go, still up", ops(question), menu().map { it.label })
        assertEquals(nine, stored(ScrollDefaults.NINE))
        h.menuPick(s(R.string.scroll_list_to_front))
        assertEquals(ScrollItem.Text("?"), stored(ScrollDefaults.NINE).first())
        assertEquals(emptyList<String>(), typed)
        assertTrue(h.noticeTexts().toString(), h.noticeTexts().containsAll(listOf(s(R.string.scroll_list_moved_to_front), s(R.string.learning_undo))))
        undo()
        assertEquals(nine, stored(ScrollDefaults.NINE))
        assertEquals("?", column()[2].text.toString())
        // the first: no 移到最前
        hold(column()[0])
        h.release()
        assertEquals(listOf(s(R.string.scroll_list_edit), remove(column()[0])), menu().map { it.label })
    }

    @Test
    @Uat("KB-117")
    fun aFingerDriftingAfterTheMenuOpensPicksNothing() {
        start()
        val held = column()[2]
        val box = h.box(held)
        val dp = h.service.resources.displayMetrics.density
        hold(held)
        h.api.calls.clear()
        // up and aside as a hand settling does: once picked the old way
        h.move(box.exactCenterX() + 21 * dp, box.exactCenterY() - 27 * dp, lift = true)
        assertEquals(nine, stored(ScrollDefaults.NINE))
        assertEquals(emptyList<String>(), typed)
        assertEquals(ops(held), menu().map { it.label })
    }

    @Test
    @Uat("KB-117")
    fun removingSaysWhatAndIsUndoneWhereItWas() {
        start()
        val bang = column()[3]
        val label = bang.text.toString()
        pick(bang, remove(bang))
        assertEquals(nine.filterIndexed { i, _ -> i != 3 }, stored(ScrollDefaults.NINE))
        assertTrue(h.noticeTexts().toString(), s(R.string.scroll_list_removed).replace("%1\$s", label) in h.noticeTexts())
        undo()
        assertEquals(nine, stored(ScrollDefaults.NINE))
        assertEquals(label, column()[3].text.toString())
    }

    @Test
    @Uat("KB-117")
    fun anUndoAfterTheListChangedElsewhereLeavesIt() {
        start()
        pick(column()[3], remove(column()[3]))
        val elsewhere = ScrollListStore.Empty.with(ScrollDefaults.NINE, listOf(ScrollItem.Text("a"), ScrollItem.Text("b"))).serialize()
        pref.setValue(elsewhere)
        h.idle()
        undo()
        assertEquals(elsewhere, pref.getValue())
    }

    @Test
    @Uat("KB-117")
    fun aListChangedUnderAnOpenMenuClosesItAndItsPickDoesNothing() {
        start()
        hold(column()[2])
        h.release()
        val open = ShadowPopupMenu.getLatestPopupMenu()
        val abc = ScrollListStore.Empty.with(ScrollDefaults.NINE, listOf(ScrollItem.Text("a"), ScrollItem.Text("b"), ScrollItem.Text("c"))).serialize()
        pref.setValue(abc)
        h.idle()
        assertEquals(emptyList<Item>(), menu())
        // its item tapped all the same (a tap on its way as it closed): the place now holds another item
        val item = (0 until open.menu.size()).map(open.menu::getItem).first { it.title.toString() == s(R.string.scroll_list_to_front) }
        item.javaClass.getMethod("invoke").invoke(item)
        h.idle()
        assertEquals(abc, pref.getValue())
    }

    @Test
    @Uat("KB-117")
    fun anotherFieldClosesTheMenu() {
        start()
        hold(column()[2])
        h.release()
        assertEquals(3, menu().size)
        h.start(inputType = InputType.TYPE_CLASS_TEXT, fieldId = 7)
        assertEquals(emptyList<Item>(), menu())
        assertTrue(column().none { it.isActivated })
    }

    @Test
    fun aRotationClosesTheMenu() {
        start()
        hold(column()[2])
        h.release()
        assertEquals(3, menu().size)
        RuntimeEnvironment.setQualifiers("+land")
        h.service.onConfigurationChanged(h.service.resources.configuration)
        h.idle()
        assertEquals(emptyList<Item>(), menu())
    }

    @Test
    @Uat("PAN-28")
    fun theNumberPadsColumnHasTheSameMenu() {
        start()
        h.tap(h.key("123"))
        val minus = column()[1]
        assertEquals("-", minus.text.toString())
        h.api.calls.clear()
        pick(minus, s(R.string.scroll_list_to_front))
        assertEquals(ScrollItem.Text("-"), stored(ScrollDefaults.NUMBER).first())
        assertEquals(emptyList<String>(), typed)
        undo()
        assertEquals(ScrollDefaults.of(ScrollDefaults.NUMBER), stored(ScrollDefaults.NUMBER))
    }

    private fun scroll() = KeyboardHarness.all(h.keyboard).filterIsInstance<ScrollView>().first { h.shown(it) }

    private fun scrolledToEnd(): Int {
        scroll().scrollTo(0, Int.MAX_VALUE / 2)
        h.idle()
        return scroll().scrollY.also { assertTrue(it > 0) }
    }

    @Test
    @Uat("PAN-36")
    fun theNineKeysColumnStaysPutThrough123AndBack() {
        start()
        val at = scrolledToEnd()
        h.tap(h.key("123"))
        h.tap(h.key(s(R.string.picker_back)))
        h.nineKey("2")
        assertEquals(at, scroll().scrollY)
    }

    @Test
    @Uat("PAN-36")
    fun aListEditedInPlaceKeepsItsPlace() {
        start()
        val at = scrolledToEnd()
        h.chinesePunctuation()
        assertEquals("，", column()[0].text.toString())
        assertEquals(at, scroll().scrollY)
        // the last one removed: as near the end as the shorter list goes
        pick(column().last(), remove(column().last()))
        assertEquals(8, column().size)
        assertTrue(scroll().scrollY > 0)
    }

    @Test
    @Uat("KB-117")
    fun theOnlyItemHasNoRemove() {
        start()
        pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NINE, listOf(ScrollItem.Text("?"))).serialize())
        h.idle()
        assertEquals(1, column().size)
        hold(column()[0])
        h.release()
        assertEquals(listOf(Item(s(R.string.scroll_list_edit), disabled = false)), menu())
    }

    @Test
    @Uat("KB-117")
    fun anAddressFieldsListIsTheOneChanged() {
        start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        assertEquals(".com", column()[2].text.toString())
        pick(column()[2], remove(column()[2]))
        assertTrue(stored(ScrollDefaults.URL).toString(), ScrollItem.Text(".com") !in stored(ScrollDefaults.URL))
        assertEquals(ScrollDefaults.of(ScrollDefaults.NINE), stored(ScrollDefaults.NINE))
        assertTrue(column().map { it.text.toString() }.toString(), column().none { it.text.toString() == ".com" })
    }

    @Test
    @Uat("KB-117")
    fun editOpensTheListsCard() {
        start()
        pick(column()[0], s(R.string.scroll_list_edit))
        val opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertEquals(SettingsRoute.ScrollList(ScrollDefaults.NINE), opened.parcelable<SettingsRoute>(MainActivity.EXTRA_SETTINGS_ROUTE))
    }

    @Test
    @Uat("KB-117")
    fun aDragHeldStillShowsNoMenu() {
        start()
        val item = h.box(column()[1])
        val slop = ViewConfiguration.get(h.service).scaledTouchSlop
        h.press(column()[1])
        h.move(item.exactCenterX(), item.exactCenterY() - slop * 3)
        h.after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        assertEquals(emptyList<Item>(), menu())
        h.release()
        assertEquals(ScrollDefaults.of(ScrollDefaults.NINE), stored(ScrollDefaults.NINE))
    }

    @Test
    @Uat("KB-117")
    fun syllablesAreNotHeld() {
        start()
        EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.PINYIN, true, listOf("ni", "mi"), 2)
        h.idle()
        h.relayout()
        assertEquals(listOf("ni", "mi"), column().map { it.text.toString() })
        hold(column()[0])
        assertEquals(emptyList<Item>(), menu())
        h.release()
    }

    @Test
    @Uat("KB-117")
    fun aNewerVersionsListsAreNotChanged() {
        start()
        val newer = "@9\n${ScrollDefaults.NINE}\tt|?"
        pref.setValue(newer)
        h.idle()
        hold(column()[0])
        h.release()
        assertEquals(ops(column()[0]), menu().map { it.label })
        assertEquals(listOf(true, true, true), menu().map { it.disabled })
        assertEquals(newer, pref.getValue())
    }

    @Test
    @Uat("KB-117")
    fun beforeTheFirstUnlockTheListsAreNotChanged() {
        directBoot(true)
        try {
            start()
            val before = pref.getValue()
            hold(column()[2])
            h.release()
            assertEquals(listOf(true, true, true), menu().map { it.disabled })
            assertEquals(emptyList<String>(), said(column()[2]))
            // a faded item run all the same
            h.menuPick(remove(column()[2]))
            assertEquals(before, pref.getValue())
        } finally {
            directBoot(false)
        }
    }

    @Test
    @Uat("AX-23")
    fun aSceneRemadeLeavesTalkBacksActionsAsTheyWere() {
        start()
        pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NINE, listOf(ScrollItem.Command("copy"), ScrollItem.Text("?"))).serialize())
        h.idle()
        // ViewCompat's own list: an action removed and added again takes back the same id
        fun actions(v: TextView) = (v.getTag(androidx.core.R.id.tag_accessibility_actions) as List<*>).toList()
        val before = column().map { actions(it) to it.foreground }
        assertEquals(listOf(2, 3), before.map { it.first.size })
        KeyboardHarness.all(h.keyboard).filterIsInstance<BaseKeyboard>().first { h.shown(it) }.columns.update()
        column().zip(before).forEach { (v, was) ->
            assertTrue(actions(v).zip(was.first).all { (a, b) -> a === b })
            assertTrue(v.foreground === was.second)
        }
    }

    @Test
    @Uat("T9-72")
    fun theEnglishNineKeysListIsItsOwn() {
        start(im = KeyboardHarness.ENGLISH)
        assertEquals(listOf(",", ".", "?", "!"), column().take(4).map { it.text.toString() })
        pick(column()[3], remove(column()[3]))
        assertTrue(ScrollItem.Text("!") !in stored(ScrollDefaults.NINE_ENGLISH))
        assertEquals(ScrollDefaults.of(ScrollDefaults.NINE), stored(ScrollDefaults.NINE))
    }

    @Test
    @Uat("AX-23")
    fun talkBackDoesTheMenusThreeThings() {
        start()
        val a11y = listOf(s(R.string.a11y_scroll_list_to_front), s(R.string.a11y_scroll_list_edit), s(R.string.a11y_scroll_list_remove))
        assertEquals(a11y - a11y[0], said(column()[0]))
        assertEquals(a11y, said(column()[2]))
        val v = column()[2]
        val toFront = AccessibilityNodeInfoCompat.wrap(v.createAccessibilityNodeInfo()).actionList.first { it.label == a11y[0] }
        assertTrue(v.performAccessibilityAction(toFront.id, null))
        h.idle()
        assertEquals(ScrollItem.Text("?"), stored(ScrollDefaults.NINE).first())
        // a command read by its name
        pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NINE, listOf(ScrollItem.Command("copy"), ScrollItem.Text("?"))).serialize())
        h.idle()
        assertEquals(s(R.string.shortcut_copy), column()[0].contentDescription?.toString())
    }

    @Test
    @Uat("KB-117")
    fun aListChangedUnderAHeldItemShowsNoMenu() {
        start()
        h.press(column()[2])
        // another window's settings saved meanwhile: another item sits where the finger is
        pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NINE, listOf(ScrollItem.Text("a"), ScrollItem.Text("b"), ScrollItem.Text("c"))).serialize())
        h.after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        assertEquals(emptyList<Item>(), menu())
        h.release()
        assertEquals(listOf("a", "b", "c"), stored(ScrollDefaults.NINE).map { (it as ScrollItem.Text).text })
    }

    @Test
    @Uat("PAN-36")
    fun punctuationSwitchedUnderAHeldItemKeepsItsTap() {
        start()
        h.chinesePunctuation()
        h.press(column()[0])
        h.chinesePunctuation(marks = emptyMap())
        h.api.calls.clear()
        h.release()
        assertEquals(typed.toString(), ",", h.field.text.toString())
    }

    @Test
    @Uat("KB-118")
    fun aCommandInTheNineKeysColumnRunsOnATap() {
        pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NINE, listOf(ScrollItem.Command(KeyCommand.SelectToStart.key))).serialize())
        start()
        h.start(prefill = { it.setText("hello world"); it.setSelection(5) })
        h.tap(column().single())
        assertEquals(0 to 5, h.field.let { minOf(it.selectionStart, it.selectionEnd) to maxOf(it.selectionStart, it.selectionEnd) })
        assertEquals("nothing typed", "hello world", h.field.text.toString())
    }

    @Test
    @Uat("ST-220")
    fun aPhoneFieldsColumnOnTheNineKeysIsThePhones() {
        start(inputType = InputType.TYPE_CLASS_PHONE)
        h.tap(h.key(s(R.string.picker_back)))
        assertEquals(listOf("+", "*", "#", "-", "(", ")", ","), column().map { it.text.toString() })
    }
}
