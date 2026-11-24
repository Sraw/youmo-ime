/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.text.InputType
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.picker.PickerLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.set
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.scene.Op
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** A number board drawn for the pads it is for (`pads=`), the symbols' as the panel opens; one refused as read draws the app's. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class CustomPanelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPrefs.getInstance().keyboard
    private val dirWas = CustomKeyboards.dir
    private val dir by lazy { File(folder.root, "layouts").apply { mkdirs() } }
    private val h by lazy { KeyboardHarness() }

    @Before
    fun theTestsFolder() {
        CustomKeyboards.dir = { dir }
        SceneBoard.forgetScenes()
    }

    @After
    fun asItWas() {
        h.close()
        forgetTheTestsLayouts(dirWas)
        SceneBoard.forgetScenes()
        prefs.numberKeyboardLayout.setValue("")
        prefs.symbolsPanelLayout.setValue("")
    }

    private fun own(id: String, base: String, attrs: String = "", edit: (String) -> String = { it }): CustomChoice? {
        val text = edit(BuiltInLayouts(context).text(base)!!.replaceFirst("id=\"$base\"", "id=\"$id\" based-on=\"$base\"$attrs"))
        File(dir, "$id.xml").writeText(text)
        CustomKeyboards.forget(id)
        return CustomKeyboards.choice(id)
    }

    // its 1 shown as 壹 (it types 1 still)
    private fun numbers(attrs: String = "", edit: (String) -> String = { it }) =
        own("my-num", "number", attrs) { edit(it.replace("label=\"1\" text-size=\"30\"", "label=\"壹\" text-size=\"30\"")) }

    private fun shownOnes(type: Int): List<String> = h.start(type).texts().filter { it == "1" || it == "壹" }

    @Test
    fun aNumberBoardIsDrawnForThePadsItIsForTheAppsForTheRest() {
        val mine = numbers(" pads=\"full phone\"")!!
        assertTrue(mine.drawn)
        assertEquals(listOf("full", "phone"), mine.pads)
        assertEquals(listOf("1"), shownOnes(InputType.TYPE_CLASS_NUMBER))
        prefs.numberKeyboardLayout.setValue(mine.id)
        assertEquals(listOf("壹"), shownOnes(InputType.TYPE_CLASS_NUMBER))
        assertEquals(listOf("壹"), shownOnes(InputType.TYPE_CLASS_PHONE))
        // a decimal field has the full pad, so the board for it; a date's is not the board's
        assertEquals(listOf("壹"), shownOnes(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL))
        assertEquals(listOf("1"), shownOnes(InputType.TYPE_CLASS_DATETIME))
        h.start()
        h.tap(h.key("123"))
        assertTrue(h.texts().toString(), "壹" in h.texts())
        h.api.calls.clear()
        h.tap(h.key("壹"))
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") && "sym=${FcitxKeyMapping.FcitxKey_KP_1}," in it })
        prefs.numberKeyboardLayout.setValue("")
        assertEquals(listOf("1"), shownOnes(InputType.TYPE_CLASS_NUMBER))
    }

    // made once for each pad, not again at each field of the other
    @Test
    fun aNumberBoardForTwoPadsIsMadeOnceForEach() {
        prefs.numberKeyboardLayout.setValue(numbers(" pads=\"full phone\"")!!.id)
        fun shown(type: Int): NumberKeyboard {
            h.start(type)
            return KeyboardHarness.find<NumberKeyboard>(h.inputView)!!.also { assertTrue(h.texts().toString(), "壹" in h.texts()) }
        }
        val number = shown(InputType.TYPE_CLASS_NUMBER)
        val phone = shown(InputType.TYPE_CLASS_PHONE)
        assertTrue(number !== phone)
        repeat(2) {
            assertSame(number, shown(InputType.TYPE_CLASS_NUMBER))
            assertSame(phone, shown(InputType.TYPE_CLASS_PHONE))
        }
    }

    @Test
    fun aNumberBoardNamingNoPadsIs123sAlone() {
        val mine = numbers()!!
        assertEquals(listOf("full"), mine.pads)
        prefs.numberKeyboardLayout.setValue(mine.id)
        assertEquals(listOf("壹"), shownOnes(InputType.TYPE_CLASS_NUMBER))
        assertEquals(listOf("1"), shownOnes(InputType.TYPE_CLASS_PHONE))
    }

    // their fields have the full pad now
    @Test
    fun aBoardImportedForTheMergedPadsIsTheFullOnes() {
        val mine = numbers(" pads=\"digits decimal\"")!!
        assertTrue(mine.drawn)
        assertEquals(listOf("full"), mine.pads)
        prefs.numberKeyboardLayout.setValue(mine.id)
        assertEquals(listOf("壹"), shownOnes(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertEquals(listOf("壹"), shownOnes(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED))
        assertEquals(listOf("1"), shownOnes(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun aFileReplacedIsDrawnAsTheNextFieldStarts() {
        val mine = numbers()!!
        prefs.numberKeyboardLayout.setValue(mine.id)
        assertEquals(listOf("壹"), shownOnes(InputType.TYPE_CLASS_NUMBER))
        // the same id imported again: drawn anew, the old keyboard not kept
        own("my-num", "number") { it.replace("label=\"1\" text-size=\"30\"", "label=\"一\" text-size=\"30\"") }
        File(dir, "my-num.xml").setLastModified(System.currentTimeMillis() + 5_000)
        CustomKeyboards.forget("my-num")
        assertTrue(h.start(InputType.TYPE_CLASS_NUMBER).texts().toString(), "一" in h.texts())
    }

    @Test
    fun oneRefusedAsItIsReadDrawsTheAppsTheChoiceKept() {
        // a direction override: let in by an older build, refused as read now
        val mine = numbers { it.replaceFirst("<row>", "<row>\n    <key id=\"rlo\" width=\"1\" label=\"x\" send=\"commit:&#x202E;\" shortcut=\"my-num/rlo\"/>") }!!
        assertFalse(mine.drawn)
        prefs.numberKeyboardLayout.setValue(mine.id)
        assertNull(prefs.drawnPanelLayout(PanelLayouts.Panel.NUMBER))
        assertEquals(listOf("1"), shownOnes(InputType.TYPE_CLASS_NUMBER))
        assertEquals(mine.id, prefs.numberKeyboardLayout.getValue())
        val letters = own("my-qwerty", "qwerty")!!
        prefs.numberKeyboardLayout.setValue(letters.id)
        assertNull(prefs.drawnPanelLayout(PanelLayouts.Panel.NUMBER))
    }

    @Test
    fun theSymbolPanelIsTheUsersAsItOpens() {
        val back = h.service.getString(R.string.picker_back)
        fun labels() = h.keys().map { it.label }
        val mine = own("my-sym", "symbols") { it.replace("label=\"{back}\"", "label=\"回去\"") }!!
        assertTrue(mine.drawn)
        h.start()
        h.tap(h.key("符"))
        assertTrue(labels().toString(), back in labels())
        h.tap(h.key(back))
        prefs.symbolsPanelLayout.setValue(mine.id)
        h.tap(h.key("符"))
        assertTrue(labels().toString(), "回去" in labels() && back !in labels())
        h.tap(h.key("回去"))
        assertTrue(labels().toString(), "q" in labels())
        prefs.symbolsPanelLayout.setValue("")
        h.tap(h.key("符"))
        assertTrue(labels().toString(), back in labels())
    }

    // a board put in place of the one the panel was made with: the categories and grid go to its slots, found by kind
    @Test
    fun aPanelOpenedBeforeHasItsCategoriesAndGridInTheNewBoardsSlots() {
        val swapped = own("my-sym", "symbols") {
            it.replace(
                "<slot id=\"categories\" kind=\"symbol-categories\" width=\"18\"/>\n    <slot id=\"grid\" kind=\"symbol-grid\" width=\"82\"/>",
                "<slot id=\"symbols\" kind=\"symbol-grid\" width=\"82\"/>\n    <slot id=\"kinds\" kind=\"symbol-categories\" width=\"18\"/>",
            )
        }!!
        assertTrue(swapped.drawn)
        val back = h.service.getString(R.string.picker_back)
        h.start()
        h.tap(h.key("符"))
        val panel = KeyboardHarness.find<PickerLayout>(h.inputView)!!
        val made = panel.embeddedKeyboard
        assertTrue("the app's: the categories left", panel.side!!.left < panel.grid.left)
        h.tap(h.key(back))
        prefs.symbolsPanelLayout.setValue(swapped.id)
        h.tap(h.key("符"))
        assertTrue("the same panel, another board", panel === KeyboardHarness.find<PickerLayout>(h.inputView) && panel.embeddedKeyboard !== made)
        for ((slot, view) in listOf(PickerLayout.CATEGORIES_SLOT to panel.side!!, PickerLayout.GRID_SLOT to panel.grid)) {
            assertEquals(slot, cellOf(panel, slot), Rect(view.left, view.top, view.right, view.bottom))
        }
        assertTrue("the categories right", panel.side!!.left > panel.grid.left)
        assertTrue(panel.indexOfChild(panel.grid) > panel.indexOfChild(panel.embeddedKeyboard))
    }

    // a number layout of before 0.6.3, its own lock key beside 返回: tapped it locks, its icon and its words follow
    @Test
    @Uat("PAN-42")
    fun aNumberLayoutsOwnLockKeyStillLocks() {
        val mine = numbers { it.replace(OWN_BACK, OLD_BACK_AND_LOCK) }!!
        assertTrue(mine.drawn)
        prefs.numberKeyboardLayout.setValue(mine.id)
        h.start(InputType.TYPE_CLASS_NUMBER)
        val board = KeyboardHarness.find<NumberKeyboard>(h.inputView)!!
        fun icons() = KeyboardHarness.find<KeyboardCanvasView>(board)!!.keyAt("lock")!!.ops(false).filterIsInstance<Op.Icon>().map { it.name }
        assertEquals(listOf("lock-open"), icons())
        assertEquals(context.getString(R.string.number_pad_lock), h.keyNamed("lock").description)
        h.tap(h.keyNamed("lock"))
        assertTrue(board.locked)
        assertEquals(listOf("lock"), icons())
        assertEquals(context.getString(R.string.number_pad_unlock), h.keyNamed("lock").description)
        h.tap(h.keyNamed("lock"))
        assertFalse(board.locked)
    }

    // a board without the grid: the grid hidden, the keys below it drawn and tapped
    @Test
    fun aPanelWithoutTheGridHidesItAndItsKeysAreTapped() {
        val bare = own("my-sym", "symbols") { it.replace("<slot id=\"grid\" kind=\"symbol-grid\" width=\"82\"/>", "<gap width=\"82\"/>") }!!
        assertTrue(bare.drawn)
        prefs.symbolsPanelLayout.setValue(bare.id)
        val back = h.service.getString(R.string.picker_back)
        h.start()
        h.tap(h.key("符"))
        val panel = KeyboardHarness.find<PickerLayout>(h.inputView)!!
        assertFalse(panel.grid.isVisible)
        assertTrue(panel.side!!.isVisible)
        h.tap(h.key(back))
        assertTrue(h.keys().map { it.label }.toString(), "q" in h.keys().map { it.label })
    }

    private companion object {
        const val OWN_BACK = """width="22" label="{back}" text-size="16" text-style="bold" send="layout:Text"
         long-press="panel-lock"/>"""
        const val OLD_BACK_AND_LOCK = """width="12" label="{back}" text-size="16" text-style="bold" send="layout:Text"/>
    <key id="lock" role="lock" kind="function" width="10" icon="{lock-icon}" a11y="a11y_key_panel_lock" send="panel-lock"/>"""
    }

    private fun cellOf(panel: PickerLayout, slot: String): Rect {
        val kb = panel.embeddedKeyboard
        val canvas = KeyboardHarness.find<KeyboardCanvasView>(kb)!!
        return Rect().apply {
            set(canvas.scene!!.slots.single { it.kind == slot }.bounds)
            offset(kb.left + canvas.left, kb.top + canvas.top)
        }
    }
}
