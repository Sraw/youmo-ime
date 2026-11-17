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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * 按键字符和快捷键's tab of a layout of the user's in use (061-custom-layout §9, L6): its name the card's, its keys its
 * own, a key of a name another tab has marked 共用 and its editor saying which; one not in use none.
 */
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
        CustomKeyboards.dir = dirWas
        EngineState.shuangpinScheme = schemeWas
        SceneBoard.forgetScenes()
    }

    // the 18 keys made 17 (H and JK one key of its own name) with a key of its own typing 哈, its long press written;
    // [changed] what else is done to the file
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

    // a text on the page tapped: the card it names
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
        // the tab's title, its own keys; the 18 keys' WE shared, its HJK and 哈 not
        assertTrue(texts().count { it == "我的 17 键" } >= 2)
        val shared = st.s(R.string.shortcut_shared_tag)
        assertTrue(keyCalled("WE").contentDescription.toString(), keyCalled("WE").contentDescription.endsWith(", $shared"))
        assertFalse(keyCalled("HJK").contentDescription.toString().endsWith(", $shared"))
        val we = dialogOf("WE")
        assertTrue(KeyboardHarness.all(we.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == st.s(R.string.shortcut_shared_with, st.s(R.string.shortcut_page_g18)) })
        we.dismiss()
        st.idle()
        // its own key's long press as the layout writes it: 哈哈 first, then 嘿
        val ha = dialogOf("哈")
        assertTrue(ha.isShowing)
        ha.dismiss()
        st.idle()
        val list = KeyCaps.resolver(st.activity, "my-k17")!!.edit(KeyId("my-k17/ha"))
        assertEquals(listOf("哈哈", "嘿"), list.items.take(2))
        assertEquals("哈哈", list.first)
    }

    // a key of the app's name is the app's key: letters and a swipe of the layout's own (WE's written EW, its down 我们)
    // none of its long press nor of its editor's, the 18 keys' WE's (L6 review S2, N9); the layout let go, the tab picked
    // goes, 全键盘 picked
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
        // let go of: the page made again on the tab it had, which is gone
        keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.KEYS18)
        st.recreate()
        assertFalse(texts().toString(), "我的 17 键" in texts())
        val full = KeyboardHarness.all(st.activity.window.decorView).first { it.tag == "view:keys/board/${KeyShortcutPages.Full.form}" }
        assertTrue(full.isSelected)
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
