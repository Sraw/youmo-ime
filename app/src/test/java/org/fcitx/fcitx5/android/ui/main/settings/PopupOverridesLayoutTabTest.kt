/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.forgetTheTestsLayouts
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPages
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** A user layout's tab: its name the card's, its own keys, a key another tab shares marked 共用 (none when not in use). */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PopupOverridesLayoutTabTest : PopupOverridesFragmentTest.EditorDialog() {

    @get:Rule
    val folder = TemporaryFolder()

    private val dirWas = CustomKeyboards.dir
    private val schemeWas = EngineState.shuangpinScheme
    private val dir by lazy { File(folder.root, "layouts").apply { mkdirs() } }
    private val keyboard get() = AppPrefs.getInstance().keyboard

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
    }

    // the 18 keys made 17 (HJK one key of its own name) plus a key typing 哈 with a long press; [changed] edits the file further
    private fun k17(changed: (String) -> String = { it }) {
        val text = BuiltInLayouts(st.activity).text("k18")!!.replaceFirst("id=\"k18\" name=\"18 键\"", "id=\"my-k17\" name=\"我的 17 键\" based-on=\"k18\"")
            .replace(Regex("<key id=\"r2c4\"[^>]*/>\\s*<key id=\"r2c5\"[^>]*/>"), "<key id=\"hjk\" width=\"fill\" label=\"HJK\" letters=\"hjk\" lean=\"off\" shortcut=\"my-k17/hjk\"/>")
            .replace("<include src=\"grouped-bottom\"/>", "<row><key id=\"ha\" label=\"哈\" send=\"commit:哈\" shortcut=\"my-k17/ha\" swipe-up=\"commit:哈哈\"><popup from=\"list\"><item text=\"嘿\"/></popup></key></row>\n  <include src=\"grouped-bottom\"/>")
        File(dir, "my-k17.xml").writeText(changed(text))
        CustomKeyboards.forget("my-k17")
        assertTrue(CustomKeyboards.choice("my-k17")!!.drawn)
    }

    private fun openPage() {
        prefs.set(AppPrefs.getInstance().internal.keyLists, KeyLists.HEADER)
        st.activity
        st.open(SettingsRoute.PopupOverrides)
    }

    private fun texts() = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    // tap the card a text names
    private fun clickName(name: String) {
        var v: android.view.View = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == name }
        while (!v.hasOnClickListeners()) v = v.parent as android.view.View
        v.performClick()
        st.idle()
    }

    @Test
    @Uat("ST-185")
    fun aLayoutInUseHasATabOfItsOwnKeys() {
        k17()
        prefs.set(keyboard.pinyinKeyboard, keyboard.pinyinKeyboard.getValue())
        prefs.set(keyboard.pinyinKeyboardLayout, "")
        keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, "my-k17")
        openPage()
        clickName("我的 17 键")
        // the tab's title and own keys; the 18 keys' WE shared, its HJK and 哈 not
        assertTrue(texts().count { it == "我的 17 键" } >= 2)
        val shared = st.s(R.string.shortcut_shared_tag)
        assertTrue(keyCalled("WE").contentDescription.toString(), keyCalled("WE").contentDescription.endsWith(", $shared"))
        assertFalse(keyCalled("HJK").contentDescription.toString().endsWith(", $shared"))
        val we = dialogOf("WE")
        assertTrue(KeyboardHarness.all(we.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == st.s(R.string.shortcut_shared_with, st.s(R.string.shortcut_page_g18)) })
        we.dismiss()
        st.idle()
        // as the layout writes it: 哈哈 first, then 嘿
        val ha = dialogOf("哈")
        assertTrue(ha.isShowing)
        ha.dismiss()
        st.idle()
        val list = KeyCaps.resolver(st.activity, "my-k17")!!.edit(KeyId("my-k17/ha"))
        assertEquals(listOf("哈哈", "嘿"), list.items.take(2))
        assertEquals("哈哈", list.first)
    }

    // an app-named key is the app's: letters and swipes the layout's own, the long press and editor the 18 keys'; the layout
    // let go, its tab goes and 全键盘 is picked
    @Test
    fun aSharedKeyIsTheAppsKeyAndItsTabGoesWithItsLayout() {
        k17 { it.replace("letters=\"we\" shortcut=\"g18/we\"", "letters=\"ew\" shortcut=\"g18/we\" swipe-down=\"commit:我们\"") }
        assertTrue("letters=\"ew\" shortcut=\"g18/we\" swipe-down" in File(dir, "my-k17.xml").readText())
        keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, "my-k17")
        openPage()
        val app = KeyCaps.resolver(st.activity, "k18")!!.defaultOf(KeyId("g18/we"))
        assertEquals(app, KeyCaps.resolver(st.activity, "my-k17")!!.defaultOf(KeyId("g18/we")))
        // what EW would give: e before w
        assertTrue(app.toString(), app!!.items.indexOfFirst { it.text == "w" } < app.items.indexOfFirst { it.text == "e" })
        clickName("我的 17 键")
        val we = dialogOf("WE")
        val items = KeyboardHarness.all(we.window!!.decorView).mapNotNull { it.contentDescription?.toString() }.toList()
        assertTrue(items.toString(), "w" in items && items.indexOf("w") < items.indexOf("e") && items.none { "我们" in it })
        we.dismiss()
        st.idle()
        // let go: the page remade on the tab it had, which is gone
        keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.KEYS18)
        st.recreate()
        assertFalse(texts().toString(), "我的 17 键" in texts())
        val full = KeyboardHarness.all(st.activity.window.decorView).first { it.tag == "view:keys/board/${KeyShortcutPages.Full.form}" }
        assertTrue(full.isSelected)
    }

    // a key name two layouts share is 共用, edited on its layout with its own default (嘿), not on the app board, which lacks it
    @Test
    fun aKeyOfItsOwnNameTwoLayoutsShareIsEditedOnItsLayout() {
        k17()
        val qwerty = BuiltInLayouts(st.activity).text("qwerty")!!.replaceFirst("id=\"qwerty\" name=\"26 键\"", "id=\"my-qw\" name=\"我的 26 键\" based-on=\"qwerty\"")
            .replaceFirst("  <row>\n    <key id=\"symbols\"", "  <row><key id=\"ha\" label=\"哈\" send=\"commit:哈\" shortcut=\"my-k17/ha\"><popup from=\"list\"><item text=\"嘿\"/></popup></key></row>\n  <row>\n    <key id=\"symbols\"")
        assertTrue("my-k17/ha" in qwerty)
        File(dir, "my-qw.xml").writeText(qwerty)
        CustomKeyboards.forget("my-qw")
        assertTrue(CustomKeyboards.choice("my-qw")!!.drawn)
        // put back after (PrefsRule)
        prefs.set(keyboard.pinyinKeyboard, keyboard.pinyinKeyboard.getValue())
        prefs.set(keyboard.pinyinKeyboardLayout, "")
        prefs.set(keyboard.englishKeyboard, keyboard.englishKeyboard.getValue())
        prefs.set(keyboard.englishKeyboardLayout, "")
        keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, "my-k17")
        keyboard.setImKeyboard(InputMethodCatalog.ENGLISH, ImKeyboard.CUSTOM, "my-qw")
        openPage()
        clickName("我的 17 键")
        val shared = st.s(R.string.shortcut_shared_tag)
        assertTrue(keyCalled("哈").contentDescription.toString(), keyCalled("哈").contentDescription.endsWith(", $shared"))
        val ha = dialogOf("哈")
        val items = KeyboardHarness.all(ha.window!!.decorView).mapNotNull { it.contentDescription?.toString() }.toList()
        assertTrue(items.toString(), "嘿" in items)
        ha.dismiss()
        st.idle()
    }

    // one name breaking makes every card's name two lines high so a row is even. Native graphics: legacy Paint measures a
    // character a pixel, so no name would break
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aLongNameMakesEveryCardTwoLinesHigh() {
        val long = "我的十七键布局名字长得一行写不下的那种样子"
        assertTrue(long.length <= 24)
        k17 { it.replace("name=\"我的 17 键\"", "name=\"$long\"") }
        prefs.set(keyboard.pinyinKeyboard, keyboard.pinyinKeyboard.getValue())
        prefs.set(keyboard.pinyinKeyboardLayout, "")
        keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, "my-k17")
        openPage()
        // the cards' names: the layout's, and one of the app's
        val cells = KeyboardHarness.all(st.activity.window.decorView).filter { it.tag?.toString()?.startsWith("view:keys/board/") == true }
        val names = cells.flatMap { KeyboardHarness.all(it).filterIsInstance<TextView>() }
            .filter { it.text.toString() == long || it.text.toString() == st.s(R.string.shortcut_page_g18) }.toList()
        assertEquals(names.map { it.text }.toString(), 2, names.size)
        assertTrue(names.all { it.minLines == 2 })
    }

    @Test
    @Uat("ST-186")
    fun aLayoutNotInUseHasNoTab() {
        k17()
        openPage()
        assertFalse(texts().toString(), "我的 17 键" in texts())
        assertNull((ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing })
    }
}
