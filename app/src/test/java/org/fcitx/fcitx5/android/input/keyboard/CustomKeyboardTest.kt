/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.MotionEvent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.EnglishKeyboard
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
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

/**
 * A layout of the user's as an input method's keyboard (061-custom-layout L3, §4.4, §5): drawn in place of the app's,
 * its base drawn where its file is refused or gone (the choice left), listed by ⋯ and the settings, put back on its base
 * as it is deleted; a key of two rows bubbles over its first row.
 */
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
        CustomKeyboards.dir = dirWas
        EngineState.shuangpinScheme = schemeWas
        SceneBoard.forgetScenes()
        prefs.pinyinKeyboard.setValue(prefs.pinyinKeyboard.defaultValue)
        prefs.englishKeyboard.setValue(prefs.englishKeyboard.defaultValue)
        prefs.shuangpinKeyboard.setValue(prefs.shuangpinKeyboard.defaultValue)
        listOf(prefs.pinyinKeyboardLayout, prefs.shuangpinKeyboardLayout, prefs.englishKeyboardLayout, prefs.tableKeyboardLayout).forEach { it.setValue("") }
    }

    // the app's board [base] made layout [id] of the user's, [edit] done to its text, saved as its file
    private fun own(id: String, base: String = "qwerty", edit: (String) -> String = { it }): CustomChoice? {
        val text = edit(BuiltInLayouts(context).text(base)!!.replaceFirst("id=\"$base\"", "id=\"$id\" based-on=\"$base\""))
        File(dir, "$id.xml").writeText(text)
        CustomKeyboards.forget(id)
        return CustomKeyboards.choice(id)
    }

    // q and w the other way round: AZERTY's way of a letters layout of one's own
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
        // the keyboard's name says which: another stamp (the file replaced) another keyboard
        val name = KeyboardNames.of(TextKeyboard.Name, mine)
        assertEquals(TextKeyboard.Name, KeyboardNames.base(name))
        assertEquals("${mine.id}@${mine.stamp}", KeyboardNames.custom(name))
        assertTrue(KeyboardNames.sameLayout(name, "${mine.id}@other"))
    }

    @Test
    fun oneRefusedAsItIsReadDrawsItsBaseTheChoiceKept() {
        // a letter key that runs a command its base's does not: let in by an older build, refused as it is read now
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
        // shown as the 18 keys' drawn with it
        val shown = ImKeyboards.shown(InputMethodCatalog.PINYIN, prefs.imKeyboards(), EngineState.shuangpinScheme)
        assertEquals(ImKeyboards.Layout.PINYIN18, shown.layout)
        assertEquals(mine, shown.custom)
        // English cannot type on grouped keys: not offered there
        assertTrue(KeyboardPicker(context) { InputMethodCatalog.ENGLISH }.offered()!!.first.none { it.layout != null })
        fun pinyinList(): ListPreference {
            val ctx = PlaceholderStrings(context)
            val screen = InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.of(InputMethodCatalog.PINYIN)!!, mutableMapOf(), {}, open = {})
            return screen.findPreference(AppPrefs.PINYIN_KEYBOARD)!!
        }
        val list = pinyinList()
        assertEquals("Custom:my-k18", list.entryValues.last())
        assertEquals("Custom:my-k18", list.value)
        // its file gone: the choice kept, the 18 keys drawn and the list saying so
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

    // a pref no layout may have (a backup's restored, edited by hand): its base drawn, nothing thrown (the fcitx thread asks too)
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

    // grouped keys based on the full keyboard (let in): where the engine refuses it here, or it is refused as read, grouped
    // keys are drawn in its place, its own family's base, never the full keyboard with dead group keys (L3 review S2)
    @Test
    fun oneBasedOnABoardOfAnotherFamilyFallsBackToItsOwnFamilys() {
        val mine = own("my-k18", base = "k18") { it.replace("based-on=\"k18\"", "based-on=\"qwerty\"") }!!
        assertTrue(mine.drawn)
        assertEquals("k18", mine.basedOn)
        assertEquals(ImKeyboard.KEYS18, ImKeyboards.baseOf(mine))
        val drawn = SceneLayouts.custom(context, mine.id, mine.stamp)!!
        assertEquals("k18", drawn.base)
        assertEquals("grouped", SceneLayouts.builtIn(context, drawn.base)!!.layout.family)
        // the file replaced (by one of another family) since a keyboard was made: not drawn by a stamp it no longer has
        File(dir, "my-k18.xml").writeText(BuiltInLayouts(context).text("qwerty")!!.replaceFirst("id=\"qwerty\"", "id=\"my-k18\""))
        assertNull(SceneLayouts.custom(context, mine.id, mine.stamp + "x"))
    }

    // a nine keys' layout of the user's reads the nine keys' settings: their section shown on its page (§5.4, L3 review S4)
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
        // a letters one: not
        val letters = own("my-qwerty")!!
        prefs.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, letters.id)
        assertFalse(nineShown(InputMethodCatalog.PINYIN))
    }

    // what an input start asks (the toolbar's 键盘 for a table) is the layouts as last listed: no file read for it (L3 review S3)
    @Test
    fun anInputStartAsksTheLayoutsAsLastListed() {
        val wubi = InputMethodCatalog.TABLES.keys.first { InputMethodCatalog.hasTableKeys(it) }
        val picker = KeyboardPicker(context) { wubi }
        assertTrue(own("my-qwerty")!!.tableExtras)
        // listed off the main thread: none till then (an earlier test's listing drained first, so none lands late)
        CustomKeyboards.awaitListed()
        CustomKeyboards.relist()
        CustomKeyboards.awaitListed()
        assertTrue(picker.offers())
        File(dir, "my-qwerty.xml").delete()
        assertTrue("as last listed", picker.offers())
        // the main thread's own listing (⋯ opened) is its own: not what an input start asks (re-check S-2)
        assertTrue(CustomKeyboards.all().isEmpty())
        assertTrue("the main thread's listing not kept", picker.offers())
        // a change listed anew, off the main thread
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

    // L1's review: the bubble over a key of two rows is over its first row (KeyNode.head), as BaseKeyboard's two
    // GestureType.Down uses of previewBounds pass it on, the corner's (AltPreview) and the plain one's (Preview)
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
}
