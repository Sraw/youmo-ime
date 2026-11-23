/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
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
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.keys.ScrollListStore
import org.fcitx.fcitx5.android.input.popup.PopupFocus
import org.fcitx.fcitx5.android.scene.KeyState
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

    private data class Item(val label: String, val box: Rect, val disabled: Boolean)

    private fun menu(): List<Item> = KeyboardHarness.all(h.inputView).filterIsInstance<PopupSceneView>().filter { h.shown(it) }.flatMap { v ->
        val at = h.box(v)
        v.scene?.keys.orEmpty().filter { it.kind == "menu" }.sortedBy { it.bounds.left }
            .map { k -> Item(k.label ?: k.a11y, KeyboardHarness.sceneBox(v, at, k), KeyState.Disabled in k.states) }
    }.toList()

    private fun hold(item: TextView) {
        h.press(item)
        h.after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
    }

    /** Held, slid onto [label] and lifted. */
    private fun pick(item: TextView, label: String) {
        hold(item)
        val to = (menu().firstOrNull { it.label == label } ?: error("no $label in ${menu()}")).box
        h.move(to.exactCenterX(), to.exactCenterY(), lift = true)
    }

    private fun said(v: TextView) = AccessibilityNodeInfoCompat.wrap(v.createAccessibilityNodeInfo()).actionList.mapNotNull { it.label?.toString() }

    private val typed get() = h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") }

    private val ops get() = listOf(s(R.string.scroll_list_remove), s(R.string.scroll_list_to_front), s(R.string.scroll_list_edit))

    @Test
    @Uat("KB-117", "PAN-28")
    fun aHeldItemShowsTheMenuAndMovesToTheFront() {
        start()
        val question = column()[2]
        hold(question)
        val shown = menu()
        assertEquals(ops, shown.map { it.label })
        assertEquals(listOf(false, false, false), shown.map { it.disabled })
        // over the item held
        val box = h.box(question)
        assertTrue("$shown over $box", shown.any { it.box.centerX() in box.left..box.right } && shown.all { it.box.bottom <= box.bottom + box.height() })
        val to = shown[1].box
        h.api.calls.clear()
        h.move(to.exactCenterX(), to.exactCenterY(), lift = true)
        assertEquals(ScrollItem.Text("?"), stored(ScrollDefaults.NINE).first())
        assertEquals(emptyList<String>(), typed)
        // first now: not moved again; lifted where it was held, nothing done
        hold(column()[0])
        assertEquals(listOf(false, true, false), menu().map { it.disabled })
        val before = pref.getValue()
        h.release()
        assertEquals(before, pref.getValue())
        assertEquals(emptyList<String>(), typed)
        assertEquals(emptyList<Item>(), menu())
    }

    @Test
    @Uat("KB-117")
    fun aWobbleUnderTheMenuPicksNothing() {
        start()
        val held = column()[2]
        val box = h.box(held)
        val slop = ViewConfiguration.get(h.service).scaledTouchSlop
        hold(held)
        assertEquals(ops, menu().map { it.label })
        // past the slop, short of the menu's row and of the step that moves its focus
        val step = menu()[0].box.width() * PopupFocus.STEP * (0.5f + PopupFocus.HYSTERESIS)
        assertTrue("$slop $step", slop < step)
        h.move(box.exactCenterX() + (slop + step) / 2, box.exactCenterY(), lift = true)
        assertEquals(ScrollDefaults.of(ScrollDefaults.NINE), stored(ScrollDefaults.NINE))
        assertEquals(emptyList<Item>(), menu())
        // straight up onto the cell over the finger picks it
        pick(column()[2], s(R.string.scroll_list_remove))
        assertEquals(ScrollDefaults.of(ScrollDefaults.NINE)!!.filterIndexed { i, _ -> i != 2 }, stored(ScrollDefaults.NINE))
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
        pick(column().last(), s(R.string.scroll_list_remove))
        assertEquals(8, column().size)
        assertTrue(scroll().scrollY > 0)
    }

    @Test
    @Uat("KB-117")
    fun removingIsGreyedOnTheLastItem() {
        start()
        pick(column()[3], s(R.string.scroll_list_remove))
        assertEquals(ScrollDefaults.of(ScrollDefaults.NINE)!!.filterIndexed { i, _ -> i != 3 }, stored(ScrollDefaults.NINE))
        assertEquals(8, column().size)
        pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NINE, listOf(ScrollItem.Text("?"))).serialize())
        h.idle()
        assertEquals(1, column().size)
        hold(column()[0])
        assertEquals(listOf(true, true, false), menu().map { it.disabled })
        h.release()
    }

    @Test
    @Uat("KB-117")
    fun anAddressFieldsListIsTheOneChanged() {
        start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        assertEquals(".com", column()[2].text.toString())
        pick(column()[2], s(R.string.scroll_list_remove))
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
        assertEquals(listOf(true, true, true), menu().map { it.disabled })
        h.release()
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
            assertEquals(listOf(true, true, true), menu().map { it.disabled })
            h.release()
            assertEquals(emptyList<String>(), said(column()[2]))
            val board = KeyboardHarness.all(h.keyboard).filterIsInstance<BaseKeyboard>().first { h.shown(it) }
            board.columns.change(KeyAction.ScrollList(ScrollDefaults.NINE, 2, KeyAction.ScrollList.Op.Remove))
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
        pick(column()[3], s(R.string.scroll_list_remove))
        assertTrue(ScrollItem.Text("!") !in stored(ScrollDefaults.NINE_ENGLISH))
        assertEquals(ScrollDefaults.of(ScrollDefaults.NINE), stored(ScrollDefaults.NINE))
    }

    @Test
    @Uat("AX-23")
    fun talkBackDoesTheMenusThreeThings() {
        start()
        val a11y = listOf(s(R.string.a11y_scroll_list_remove), s(R.string.a11y_scroll_list_to_front), s(R.string.a11y_scroll_list_edit))
        assertEquals(a11y - a11y[1], said(column()[0]))
        assertEquals(a11y, said(column()[2]))
        val v = column()[2]
        val toFront = AccessibilityNodeInfoCompat.wrap(v.createAccessibilityNodeInfo()).actionList.first { it.label == a11y[1] }
        assertTrue(v.performAccessibilityAction(toFront.id, null))
        h.idle()
        assertEquals(ScrollItem.Text("?"), stored(ScrollDefaults.NINE).first())
        // a command read by its name
        pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NINE, listOf(ScrollItem.Command("copy"), ScrollItem.Text("?"))).serialize())
        h.idle()
        assertEquals(s(R.string.shortcut_copy), column()[0].contentDescription?.toString())
    }
}
