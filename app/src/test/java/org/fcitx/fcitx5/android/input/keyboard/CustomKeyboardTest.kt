/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.fixture
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.EnglishKeyboard
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.materialPack
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.sent
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.scene.keys.ScrollLists
import org.fcitx.fcitx5.android.ui.main.settings.KeyCaps
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import kotlin.math.roundToInt

/** A user's layout as an input method's keyboard: drawn in place of the app's, its base where its file is refused or gone, put back on its base when deleted. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class CustomKeyboardTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPrefs.getInstance().keyboard
    private val dirWas = CustomKeyboards.dir
    private val schemeWas = EngineState.shuangpinScheme
    private val dir by lazy { File(folder.root, "layouts").apply { mkdirs() } }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Before
    fun theTestsFolder() {
        CustomKeyboards.dir = { dir }
        EngineState.shuangpinScheme = ShuangpinScheme.ZIRANMA
        SceneBoard.forgetScenes()
    }

    @After
    fun asItWas() {
        forgetTheTestsLayouts(dirWas)
        EngineState.shuangpinScheme = schemeWas
        SceneBoard.forgetScenes()
        prefs.pinyinKeyboard.setValue(prefs.pinyinKeyboard.defaultValue)
        prefs.englishKeyboard.setValue(prefs.englishKeyboard.defaultValue)
        prefs.shuangpinKeyboard.setValue(prefs.shuangpinKeyboard.defaultValue)
        listOf(prefs.pinyinKeyboardLayout, prefs.shuangpinKeyboardLayout, prefs.englishKeyboardLayout, prefs.tableKeyboardLayout).forEach { it.setValue("") }
    }

    private fun own(id: String, base: String = "qwerty", edit: (String) -> String = { it }): CustomChoice? {
        val text = edit(BuiltInLayouts(context).text(base)!!.replaceFirst("id=\"$base\"", "id=\"$id\" based-on=\"$base\""))
        File(dir, "$id.xml").writeText(text)
        CustomKeyboards.forget(id)
        return CustomKeyboards.choice(id)
    }

    private fun swapped(t: String) = t.replace("<key id=\"q\" label=\"{case:q}\" send=\"q\"", "<key id=\"q\" label=\"{case:w}\" send=\"w\"")
        .replace("<key id=\"w\" label=\"{case:w}\" send=\"w\"", "<key id=\"w\" label=\"{case:q}\" send=\"q\"")

    private fun TextKeyboard.wired() = apply { setViewTreeLifecycleOwner(Owner()) }.laidOut()

    private fun labelOf(keyboard: BaseKeyboard, id: String) = keyboard.shownKeys().single { it.id == id }.label

    @Test
    fun aLayoutOfTheUsersIsDrawnInPlaceOfTheApps() {
        val mine = own("my-qwerty", edit = ::swapped)!!
        assertTrue(mine.drawn)
        val app = TextKeyboard(context, TestThemes.PixelDark).wired()
        val custom = TextKeyboard(context, TestThemes.PixelDark, custom = "${mine.id}@${mine.stamp}").wired()
        assertEquals(labelOf(app, "w"), labelOf(custom, "q"))
        assertEquals(labelOf(app, "q"), labelOf(custom, "w"))
        // another stamp (the file replaced) is another keyboard
        val name = KeyboardNames.of(TextKeyboard.Name, mine)
        assertEquals(TextKeyboard.Name, KeyboardNames.base(name))
        assertEquals("${mine.id}@${mine.stamp}", KeyboardNames.custom(name))
        assertTrue(KeyboardNames.sameLayout(name, "${mine.id}@other"))
    }

    // a board lacking keys is drawn as it is, never its base in its place
    @Test
    fun oneLackingKeysIsDrawnAsItIs() {
        val noBackspace = own("my-qwerty") { swapped(it).replace(Regex("<key id=\"backspace\"[^>]*?/>"), "") }!!
        assertTrue(noBackspace.drawn)
        val drawn = TextKeyboard(context, TestThemes.PixelDark, custom = "${noBackspace.id}@${noBackspace.stamp}").wired()
        assertEquals(labelOf(TextKeyboard(context, TestThemes.PixelDark).wired(), "q"), labelOf(drawn, "w"))
        assertTrue(drawn.shownKeys().none { it.id == "backspace" })
        // the nine keys without 0, their side slot under another id: the column in the slot of its kind
        val nine = own("my-nine", base = "nine") {
            it.replace(Regex("<key id=\"0\"[^>]*/>"), "<gap width=\"18\"/>").replace("<slot id=\"side\"", "<slot id=\"syllables\"")
        }!!
        assertTrue(nine.drawn)
        val t9 = T9Keyboard(context, TestThemes.PixelDark, semicolon = false, custom = "${nine.id}@${nine.stamp}").laidOut()
        assertTrue(t9.shownKeys().none { it.id == "0" })
        val slot = KeyboardHarness.all(t9).filterIsInstance<KeyboardCanvasView>().single().scene!!.slots.single { it.kind == "nine-side" }
        assertEquals("syllables", slot.id)
        val column = (0 until t9.childCount).map { t9.getChildAt(it) }.filterIsInstance<ScrollView>().single()
        assertEquals(View.VISIBLE, column.visibility)
        assertEquals(slot.bounds.width.roundToInt(), column.width)
    }

    // ST-221's layout: a row of its own over the bottom one, the nine keys' list across it
    @Test
    @Uat("ST-221")
    fun aLetterLayoutsHorizontalColumnShowsTheFieldsListAndTypesIt() {
        File(dir, "uat-qwerty-scroll.xml").writeText(fixture("layouts/uat-qwerty-scroll.layout").readText())
        CustomKeyboards.forget("uat-qwerty-scroll")
        val mine = CustomKeyboards.choice("uat-qwerty-scroll")!!
        assertTrue(mine.drawn)
        prefs.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, mine.id)
        val h = KeyboardHarness()
        try {
            fun column() = KeyboardHarness.all(h.keyboard).filterIsInstance<HorizontalScrollView>().single { h.shown(it) && it.parent is BaseKeyboard }
            fun items() = (column().getChildAt(0) as ViewGroup).children.filterIsInstance<TextView>().filter { it.isVisible }.toList()
            fun texts(id: String) = ScrollDefaults.of(id)!!.map { (it as ScrollItem.Text).text }
            h.start()
            val slot = KeyboardHarness.all(h.keyboard).filterIsInstance<KeyboardCanvasView>().single { h.shown(it) }.scene!!.slots.single { it.kind == "scroll" }
            val v = column()
            assertEquals(slot.bounds.width.roundToInt(), v.width)
            assertEquals(ScrollDefaults.of(ScrollDefaults.NINE)!!.size, items().size)
            // six in view across the row
            assertEquals((v.width - v.paddingLeft - v.paddingRight) / 6f, items().first().width.toFloat(), 1f)
            h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            assertEquals(texts(ScrollDefaults.URL), items().map { it.text.toString() })
            h.tap(items().first { it.text.toString() == ".com" })
            assertEquals(".com", h.field.text.toString())
            // a phone field's list on the letters 返回 leads to: the field decides, whichever keyboard
            h.start(InputType.TYPE_CLASS_PHONE)
            h.tap(h.keyNamed("back"))
            assertTrue(h.texts().toString(), "q" in h.texts())
            assertEquals(texts(ScrollDefaults.PHONE), items().map { it.text.toString() })
        } finally {
            h.close()
        }
    }

    @Test
    @Uat("ST-221")
    fun aTouchInTheColumnIsItsAloneAndTheSpaceStillMovesTheCursor() {
        File(dir, "uat-qwerty-scroll.xml").writeText(fixture("layouts/uat-qwerty-scroll.layout").readText())
        CustomKeyboards.forget("uat-qwerty-scroll")
        prefs.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, CustomKeyboards.choice("uat-qwerty-scroll")!!.id)
        val h = KeyboardHarness()
        try {
            h.start()
            val column = KeyboardHarness.all(h.keyboard).filterIsInstance<HorizontalScrollView>().single { h.shown(it) && it.parent is BaseKeyboard }
            val box = h.box(column)
            h.api.calls.clear()
            // a slide along it scrolls it: no key pressed, no glide read
            h.drag(box, box.left + box.width() * 0.1f, box.exactCenterY())
            assertTrue(column.scrollX > 0)
            assertEquals(emptyList<String>(), h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") })
            assertEquals("", h.field.text.toString())
            val space = h.keyNamed("space")
            h.swipe(space, -space.width * 0.6f, 0f)
            assertTrue(h.sent().toString(), h.sent().isNotEmpty() && h.sent().all { "sym=${FcitxKeyMapping.FcitxKey_Left}," in it })
        } finally {
            h.close()
        }
    }

    @Test
    fun theNineKeysColumnOnAnotherKeyboardShowsNoSyllablesAndEnglishsOwnList() {
        val keyboard = TextKeyboard(context, TestThemes.PixelDark)
        fun shown() = keyboard.sideInput().let { ScrollLists.shown(ScrollLists.NINE_SIDE, null, null, null, it.pad, it.ime, it.composing) }
        keyboard.onInputMethodUpdate(KeyboardHarness.PINYIN)
        assertFalse(keyboard.sideInput().composing)
        assertEquals(ScrollLists.Shown.Category(ScrollDefaults.NINE), shown())
        keyboard.onInputMethodUpdate(KeyboardHarness.ENGLISH)
        assertEquals(ScrollLists.Shown.Category(ScrollDefaults.NINE_ENGLISH), shown())
    }

    @Test
    fun oneRefusedAsItIsReadDrawsItsBaseTheChoiceKept() {
        // a command its base's key lacks: let in by an older build, refused as read now
        val mine = own("my-qwerty") { swapped(it).replace("send=\"w\" shortcut=\"full/q\"", "send=\"lang\" shortcut=\"full/q\"") }!!
        assertFalse(mine.drawn)
        val app = TextKeyboard(context, TestThemes.PixelDark).wired()
        val drawn = TextKeyboard(context, TestThemes.PixelDark, custom = "${mine.id}@${mine.stamp}").wired()
        assertEquals(labelOf(app, "q"), labelOf(drawn, "q"))
        prefs.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, mine.id)
        val chosen = prefs.imKeyboards()
        assertEquals(ImKeyboard.CUSTOM, chosen.of(InputMethodCatalog.PINYIN))
        assertEquals(ImKeyboard.FULL, ImKeyboards.effective(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, EngineState.shuangpinScheme, chosen.customOf(InputMethodCatalog.PINYIN)))
        assertEquals(ImKeyboards.Shown(ImKeyboards.Layout.TEXT), ImKeyboards.shown(InputMethodCatalog.PINYIN, chosen, EngineState.shuangpinScheme))
    }

    @Test
    fun theKeyboardsListAndTheSettingsOfferItAndSayWhereItIsGone() {
        val mine = own("my-k18", base = "k18")!!
        prefs.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, mine.id)
        val (offered, current) = KeyboardPicker(context) { InputMethodCatalog.PINYIN }.offered()!!
        assertEquals(KeyboardPicker.Offered(ImKeyboard.CUSTOM, mine), offered.last())
        assertEquals(KeyboardPicker.Offered(ImKeyboard.CUSTOM, mine), current)
        val shown = ImKeyboards.shown(InputMethodCatalog.PINYIN, prefs.imKeyboards(), EngineState.shuangpinScheme)
        assertEquals(ImKeyboards.Layout.PINYIN18, shown.layout)
        assertEquals(mine, shown.custom)
        // English cannot type on grouped keys
        assertTrue(KeyboardPicker(context) { InputMethodCatalog.ENGLISH }.offered()!!.first.none { it.layout != null })
        fun pinyinList(): ListPreference {
            val ctx = PlaceholderStrings(context)
            val screen = InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.of(InputMethodCatalog.PINYIN)!!, mutableMapOf(), {}, open = {})
            return screen.findPreference(AppPrefs.PINYIN_KEYBOARD)!!
        }
        val list = pinyinList()
        assertEquals("Custom:my-k18", list.entryValues.last())
        assertEquals("Custom:my-k18", list.value)
        File(dir, "my-k18.xml").delete()
        CustomKeyboards.forget()
        val gone = prefs.imKeyboards()
        assertEquals("my-k18", gone.layoutOf(InputMethodCatalog.PINYIN))
        assertNull(gone.customOf(InputMethodCatalog.PINYIN))
        assertEquals(ImKeyboards.Shown(ImKeyboards.Layout.TEXT), ImKeyboards.shown(InputMethodCatalog.PINYIN, gone, EngineState.shuangpinScheme))
        val after = pinyinList()
        assertEquals("Custom:my-k18", after.value)
        assertTrue(after.entries.last().toString(), after.entries.size == after.entryValues.size)
    }

    // a pref no layout may have (a backup's, edited by hand): its base drawn, nothing thrown (the fcitx thread asks too)
    @Test
    fun anIdNoLayoutMayHaveInAPrefDrawsTheBase() {
        val wubi = InputMethodCatalog.TABLES.keys.first { InputMethodCatalog.hasTableKeys(it) }
        for (bad in listOf("X/Y", "../x", "Upper", "a--b", "")) {
            prefs.pinyinKeyboard.setValue(PinyinKeyboard.Custom)
            prefs.shuangpinKeyboard.setValue(ShuangpinKeyboard.Custom)
            prefs.englishKeyboard.setValue(EnglishKeyboard.Custom)
            listOf(prefs.pinyinKeyboardLayout, prefs.shuangpinKeyboardLayout, prefs.englishKeyboardLayout, prefs.tableKeyboardLayout).forEach { it.setValue(bad) }
            assertNull(bad, CustomKeyboards.choice(bad))
            val chosen = prefs.imKeyboards()
            assertTrue(bad, chosen.customs.isEmpty())
            for (im in listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH, wubi)) {
                assertEquals("$bad $im", ImKeyboards.Shown(ImKeyboards.layoutOf(im, chosen, EngineState.shuangpinScheme)), ImKeyboards.shown(im, chosen, EngineState.shuangpinScheme))
                assertEquals("$bad $im", null, ImKeyboards.shown(im, chosen, EngineState.shuangpinScheme).custom)
            }
            assertEquals(bad, ImKeyboards.Layout.TEXT, ImKeyboards.layoutOf(InputMethodCatalog.PINYIN, chosen, EngineState.shuangpinScheme))
            assertEquals(bad, ImKeyboards.Layout.TEXT, ImKeyboards.layoutOf(wubi, chosen, EngineState.shuangpinScheme))
        }
    }

    // refused here, grouped keys based on the full keyboard draw their own family's base, never the full keyboard with
    // dead group keys; theme styles and key data are the based-on board's, the key shape's defaults its family's
    @Test
    @Uat("ST-192")
    fun oneBasedOnABoardOfAnotherFamilyFallsBackToItsOwnFamilys() {
        val mine = own("my-k18", base = "k18") { it.replace("based-on=\"k18\"", "based-on=\"qwerty\"") }!!
        assertTrue(mine.drawn)
        assertEquals("k18", mine.basedOn)
        assertEquals(ImKeyboard.KEYS18, ImKeyboards.baseOf(mine))
        val drawn = SceneLayouts.custom(context, mine.id, mine.stamp)!!
        assertEquals("styles and marks of, styles of, drawn in its place", listOf("qwerty", "qwerty", "k18"), listOf(drawn.base, drawn.stylesOf, drawn.fallback))
        // 18 keys typed; the 11th group's corner is the 18 keys' `(`, not 14's `~`, on the keyboard and in the editor alike
        assertEquals(KeyboardVariant.Form.Grouped18, KeyboardVariants.of(drawn.layout, "", ShuangpinScheme.ZIRANMA, shape = drawn.fallback).form)
        val h = KeyId.of("g18/h")
        assertEquals("(", SceneBoard.resolver(drawn, InputMethodCatalog.PINYIN).defaultOf(h)?.first?.text)
        assertEquals("(", KeyCaps.resolver(context, mine.id)!!.defaultOf(h)?.first?.text)
        assertEquals("grouped", SceneLayouts.builtIn(context, drawn.fallback)!!.layout.family)
        // replaced by one of another family since made: not drawn by a stamp it no longer has
        File(dir, "my-k18.xml").writeText(BuiltInLayouts(context).text("qwerty")!!.replaceFirst("id=\"qwerty\"", "id=\"my-k18\""))
        assertNull(SceneLayouts.custom(context, mine.id, mine.stamp + "x"))
    }

    // a settings cap of the user's layout takes its base's theme styles, as SceneBoard does, not only its own id's
    @Test
    fun aCapOfTheUsersLayoutTakesTheStylesOfItsBaseAsTheKeyboardDoes() {
        phoneStorage()
        val pack = materialPack("based", "<style select=\"qwerty/q\" fill=\"#ff0000\"/>")
        val mine = own("my-qwerty")!!
        fun red(layout: String): Boolean {
            val cell = SceneKeyCell(context, pack).apply { board = KeyCaps.board(context, "full/q", layout) }
            cell.measure(View.MeasureSpec.makeMeasureSpec(CAP, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(CAP, View.MeasureSpec.EXACTLY))
            cell.layout(0, 0, CAP, CAP)
            return cell.keyNode!!.ops(false).filterIsInstance<Op.Fill>().any { it.color.value == 0xFFFF0000.toInt() }
        }
        assertEquals("the app's qwerty, the user's on it", listOf(true, true), listOf(red("qwerty"), red(mine.id)))
    }

    @Test
    fun aNineKeysLayoutOfTheUsersShowsTheNineKeysSettings() {
        fun nineShown(im: String) = InputMethodSettings.of(im)!!.filterIsInstance<InputMethodSettings.Section>()
            .single { it.title == R.string.keyboard_nine }.shown(emptyMap())
        assertFalse(nineShown(InputMethodCatalog.PINYIN))
        assertFalse(nineShown(InputMethodCatalog.ENGLISH))
        val mine = own("my-nine", base = "nine")!!
        prefs.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, mine.id)
        prefs.setImKeyboard(InputMethodCatalog.ENGLISH, ImKeyboard.CUSTOM, mine.id)
        assertTrue(nineShown(InputMethodCatalog.PINYIN))
        assertTrue(nineShown(InputMethodCatalog.ENGLISH))
        val letters = own("my-qwerty")!!
        prefs.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, letters.id)
        assertFalse(nineShown(InputMethodCatalog.PINYIN))
    }

    // an input start asks the layouts as last listed: no file read for it
    @Test
    fun anInputStartAsksTheLayoutsAsLastListed() {
        val wubi = InputMethodCatalog.TABLES.keys.first { InputMethodCatalog.hasTableKeys(it) }
        val picker = KeyboardPicker(context) { wubi }
        assertTrue(own("my-qwerty")!!.tableExtras)
        // listed off the main thread; an earlier test's listing drained first, so none lands late
        CustomKeyboards.awaitListed()
        CustomKeyboards.relist()
        CustomKeyboards.awaitListed()
        assertTrue(picker.offers())
        File(dir, "my-qwerty.xml").delete()
        assertTrue("as last listed", picker.offers())
        // the main thread's own listing (⋯ opened) is not what an input start asks
        assertTrue(CustomKeyboards.all().isEmpty())
        assertTrue("the main thread's listing not kept", picker.offers())
        CustomKeyboards.changed(dir)
        CustomKeyboards.awaitListed()
        assertFalse(picker.offers())
        assertNull(picker.offered())
    }

    @Test
    fun deletedWhileInUseEachInputMethodGoesBackToItsBase() {
        val mine = own("my-k18", base = "k18")!!
        prefs.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, mine.id)
        prefs.setImKeyboard(InputMethodCatalog.SHUANGPIN, ImKeyboard.CUSTOM, mine.id)
        val after = ImKeyboards.afterDelete(prefs.imKeyboards(), mine.id, mine, EngineState.shuangpinScheme)
        assertEquals(mapOf(InputMethodCatalog.PINYIN to ImKeyboard.KEYS18, InputMethodCatalog.SHUANGPIN to ImKeyboard.KEYS18), after)
        // as 删除 does: the file, then each one's preference
        File(dir, "my-k18.xml").delete()
        CustomKeyboards.forget(mine.id)
        after.forEach { (slot, k) -> prefs.setImKeyboard(ImKeyboards.imOf(slot), k) }
        val chosen = prefs.imKeyboards()
        assertEquals(ImKeyboard.KEYS18, chosen.of(InputMethodCatalog.PINYIN))
        assertEquals(ImKeyboard.KEYS18, chosen.of(InputMethodCatalog.SHUANGPIN))
        assertTrue(ImKeyboards.usedBy(chosen, mine.id).isEmpty())
    }

    // the bubble over a key of two rows is over its first row (KeyNode.head), for both previews
    @Test
    fun aKeyOfTwoRowsBubblesOverItsFirstRow() {
        for (mark in listOf("", " mark=\"false\"")) {
            SceneBoard.forgetScenes()
            val mine = own("my-tall") { t ->
                t.replace("<key id=\"p\" label=\"{case:p}\" send=\"p\"", "<key id=\"p\" label=\"{case:p}\" send=\"p\" rows=\"2\"$mark")
            }
            assertNotNull(mine)
            assertTrue(mine!!.drawn)
            val keyboard = TextKeyboard(context, TestThemes.PixelDark, custom = "${mine.id}@${mine.stamp}").wired()
            val previews = ArrayList<PopupAction.PreviewAction>()
            keyboard.popupActionListener = PopupActionListener { (it as? PopupAction.PreviewAction)?.let(previews::add) }
            val p = keyboard.shownKeys().filterIsInstance<SceneShownKey>().single { it.id == "p" }
            val node = p.node
            assertTrue(mark, node.head.bottom < node.bounds.bottom)
            val x = p.canvas.left + node.anchor.x.toFloat()
            val y = p.canvas.top + (node.head.top + node.head.bottom).toFloat() / 2
            MotionEvent.obtain(1_000L, 1_000L, MotionEvent.ACTION_DOWN, x, y, 0).also { keyboard.dispatchTouchEvent(it) }.recycle()
            MotionEvent.obtain(1_000L, 1_010L, MotionEvent.ACTION_UP, x, y, 0).also { keyboard.dispatchTouchEvent(it) }.recycle()
            val at = IntArray(2).also(p.canvas::getLocationInWindow)
            val bubble = previews.single().bounds
            assertEquals(mark, at[1] + node.head.bottom.roundToInt(), bubble.bottom)
            assertEquals(mark, at[1] + node.head.top.roundToInt(), bubble.top)
        }
    }

    private companion object {
        const val CAP = 120
    }
}
