/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.asCoroutineDispatcher
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.OfferedLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.forgetTheTestsLayouts
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles
import org.fcitx.fcitx5.android.input.layout.LayoutImport
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.utils.appContext
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class LayoutListFragmentTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dirWas = CustomKeyboards.dir
    private val realListed = ThemeLayoutsToList.listedFile
    private val realRestored = ThemeLayoutsToList.restoredFile
    private val realTrying = ThemeLayoutsToList.tryingFile
    private val realBackground = ThemeLayoutsToList.background
    private val realCardsRead = LayoutListFragment.Hooks.cardsRead
    private val layouts get() = File(folder.root, "layouts")

    @Before
    fun ours() {
        CustomKeyboards.dir = { layouts }
        ThemeLayoutsToList.listedFile = { File(folder.root, "no_backup/listed") }
        ThemeLayoutsToList.restoredFile = { File(folder.root, "no_backup/restored") }
        ThemeLayoutsToList.tryingFile = { File(folder.root, "no_backup/trying") }
        val k18 = BuiltInLayouts(appContext).text("k18")!!
        CustomLayouts.save("mine", k18.replace("name=\"18 键\"", "name=\"我的\"").replaceFirst("id=\"k18\"", "id=\"mine\" based-on=\"k18\""), layouts)
    }

    @After
    fun asItWas() {
        forgetTheTestsLayouts(dirWas)
        ThemeLayoutsToList.listedFile = realListed
        ThemeLayoutsToList.restoredFile = realRestored
        ThemeLayoutsToList.tryingFile = realTrying
        ThemeLayoutsToList.background = realBackground
        LayoutListFragment.Hooks.cardsRead = realCardsRead
        CustomLayouts.forgetAll()
        val keyboard = AppPrefs.getInstance().keyboard
        InputMethodCatalog.run { listOf(PINYIN, SHUANGPIN, ENGLISH) }.forEach { keyboard.setImKeyboard(it, ImKeyboard.FULL) }
        keyboard.keyboardLayoutsOffered.setValue("")
    }

    private fun page(st: SettingsHarness): LayoutListFragment {
        st.open(SettingsRoute.Theme)
        st.tab(2)
        fun Fragment.all(): List<Fragment> = listOf(this) + childFragmentManager.fragments.flatMap { it.all() }
        return st.activity.supportFragmentManager.fragments.flatMap { it.all() }.filterIsInstance<LayoutListFragment>().single()
    }

    private fun shownDialog(st: SettingsHarness): AlertDialog {
        st.idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun tick(dialog: AlertDialog, at: Int) {
        dialog.listView.performItemClick(null, at, at.toLong())
    }

    private fun press(st: SettingsHarness, dialog: AlertDialog, button: Int) {
        dialog.getButton(button).performClick()
        st.idle()
    }

    // ticked: on and used; unticked: off, back on its base; the other left as it was
    @Test
    @Uat("ST-227")
    fun useForTurnsOnWhatIsTickedAndOffWhatIsNot() {
        val keyboard = AppPrefs.getInstance().keyboard
        keyboard.setOfferedLayouts(OfferedLayouts.NONE)
        val mine = CustomKeyboards.choice("mine")!!
        SettingsHarness().use { st ->
            val page = page(st)
            page.askUse(st.activity, mine, ask = false)
            var dialog = shownDialog(st)
            assertEquals(listOf(false, false), (0..1).map { dialog.listView.isItemChecked(it) })
            tick(dialog, 0)
            press(st, dialog, DialogInterface.BUTTON_POSITIVE)
            assertEquals(ImKeyboard.CUSTOM, keyboard.imKeyboards().pinyin)
            assertEquals(listOf("mine"), keyboard.offeredLayouts().of(InputMethodCatalog.PINYIN))
            page.askUse(st.activity, mine, ask = false)
            dialog = shownDialog(st)
            assertTrue("pinyin's on", dialog.listView.isItemChecked(0))
            tick(dialog, 0)
            tick(dialog, 1)
            press(st, dialog, DialogInterface.BUTTON_POSITIVE)
            assertEquals(ImKeyboard.KEYS18, keyboard.imKeyboards().pinyin)
            assertEquals(emptyList<String>(), keyboard.offeredLayouts().of(InputMethodCatalog.PINYIN))
            assertEquals(ImKeyboard.CUSTOM, keyboard.imKeyboards().shuangpin)
            assertEquals(listOf("mine"), keyboard.offeredLayouts().of(InputMethodCatalog.SHUANGPIN))
        }
    }

    // imported again: the toast says who uses it; asked only where it is not on, 先不用 turning nothing back
    @Test
    @Uat("ST-228")
    fun importedAgain() = assertRows(
        "used by 拼音: says so, asks 双拼, 先不用 keeps 拼音 as it is" to {
            reimported(inUse = true) { st ->
                val keyboard = AppPrefs.getInstance().keyboard
                assertEquals(st.s(R.string.layout_updated_in_use, "我的 2", st.s(R.string.layout_im_pinyin)), ShadowToast.getTextOfLatestToast())
                val dialog = shownDialog(st)
                assertEquals(st.s(R.string.layout_use_now, st.s(R.string.layout_im_shuangpin)), dialog.findViewById<TextView>(android.R.id.message)!!.text.toString())
                press(st, dialog, DialogInterface.BUTTON_NEGATIVE)
                assertEquals(ImKeyboard.CUSTOM, keyboard.imKeyboards().pinyin)
                assertEquals(listOf("mine"), keyboard.offeredLayouts().of(InputMethodCatalog.PINYIN))
                assertEquals(ImKeyboard.FULL, keyboard.imKeyboards().shuangpin)
            }
        },
        "used by nothing: says it was updated" to {
            reimported(inUse = false) { st ->
                assertEquals(st.s(R.string.layout_updated, "我的 2"), ShadowToast.getTextOfLatestToast())
            }
        },
    )

    private fun reimported(inUse: Boolean, then: (SettingsHarness) -> Unit) {
        val keyboard = AppPrefs.getInstance().keyboard
        keyboard.setOfferedLayouts(OfferedLayouts.NONE)
        keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.FULL)
        val text = CustomLayouts.text(File(layouts, CustomLayoutFiles.fileName("mine")))!!
        savedWhereAnImportSaves()
        CustomLayouts.save("mine", text)
        if (inUse) keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, "mine")
        SettingsHarness().use { st ->
            confirmed(st, page(st), text.replace("name=\"我的\"", "name=\"我的 2\""))
            then(st)
        }
    }

    // a backup left an id on whose file it could not bring: a new file under it is on nowhere till asked
    @Test
    fun aFreshFileUnderAnIdLeftOnIsOnNowhere() {
        val keyboard = AppPrefs.getInstance().keyboard
        keyboard.setOfferedLayouts(OfferedLayouts.NONE.with(InputMethodCatalog.ENGLISH, "fresh").with(InputMethodCatalog.PINYIN, "fresh"))
        val text = BuiltInLayouts(appContext).text("qwerty")!!.replaceFirst("id=\"qwerty\"", "id=\"fresh\" based-on=\"qwerty\"")
        savedWhereAnImportSaves()
        SettingsHarness().use { st ->
            confirmed(st, page(st), text)
            assertEquals(OfferedLayouts.NONE, keyboard.offeredLayouts())
            // asked of every input method that can type on it, none ticked
            val dialog = shownDialog(st)
            assertEquals(4, dialog.listView.count)
            assertTrue((0 until 4).none { dialog.listView.isItemChecked(it) })
        }
    }

    // the import saves to the app's folder, not the test's
    private fun savedWhereAnImportSaves() {
        CustomKeyboards.dir = { CustomLayouts.dir() }
        CustomKeyboards.forget()
    }

    private fun confirmed(st: SettingsHarness, page: LayoutListFragment, text: String) {
        val opened = CustomLayouts.open(text.byteInputStream(), null, null) as CustomLayouts.Opened.Judged
        page.confirm(st.activity, opened.text, opened.verdict as LayoutImport.Verdict.Ok, null, emptyMap())
        val dialog = shownDialog(st)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        until("saved and asked", step = st::idle) { (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it !== dialog && it.isShowing } != null }
    }

    // a newer version 双拼 cannot type on (no Q): 双拼 back on the 18 keys as after a delete, pinyin kept on it
    @Test
    fun anImportReplacingOneInUseSendsBackTheInputMethodsThatCannotTypeOnIt() {
        val keyboard = AppPrefs.getInstance().keyboard
        try {
            keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, "mine")
            keyboard.setImKeyboard(InputMethodCatalog.SHUANGPIN, ImKeyboard.CUSTOM, "mine")
            val noQ = CustomLayouts.text(File(layouts, CustomLayoutFiles.fileName("mine")))!!
                .replace("<key id=\"r1c1\" label=\"{group:q}\" letters=\"q\" shortcut=\"g18/q\"/>", "<gap/>")
            CustomLayouts.save("mine", noQ, layouts)
            val read = CustomKeyboards.choice("mine")!!
            assertTrue("drawn", read.drawn)
            LayoutListFragment().sendBack(read)
            assertEquals(ImKeyboard.KEYS18, keyboard.imKeyboards().shuangpin)
            assertEquals(ImKeyboard.CUSTOM, keyboard.imKeyboards().pinyin)
        } finally {
            keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.FULL)
            keyboard.setImKeyboard(InputMethodCatalog.SHUANGPIN, ImKeyboard.FULL)
        }
    }

    // the page left while its cards are read on IO (held by the start's listing): cards made without a fragment, no crash.
    // Waits on the read's own latches, not a timeout
    @Test
    fun theCardsReadAfterThePageIsGoneCrashNothing() {
        val held = ArrayDeque<Runnable>()
        ThemeLayoutsToList.background = Executor { held += it }
        ThemeLayoutsToList.atStart(emptyList()) { ThemeLayoutsToList.InUse(null, null, dark = false) }
        val readBegun = CountDownLatch(1)
        val readEnded = CountDownLatch(1)
        LayoutListFragment.Hooks.cardsRead = Executor { r ->
            Thread {
                readBegun.countDown()
                try {
                    r.run()
                } finally {
                    readEnded.countDown()
                }
            }.start()
        }.asCoroutineDispatcher()
        val main = Thread.currentThread()
        val handlerWas = main.uncaughtExceptionHandler
        val thrown = ArrayList<Throwable>()
        main.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> thrown += e }
        try {
            SettingsHarness().use { st ->
                st.open(SettingsRoute.Theme)
                st.tab(2)
                assertTrue("the read begun", readBegun.await(WAIT_MS, TimeUnit.MILLISECONDS))
            }
            // the page gone, the read held by the start's listing (awaitStart)
            assertEquals("the read waiting for the start", 1L, readEnded.count)
            // the start ends: the read goes on to its end
            held.removeFirst().run()
            assertTrue("the read ended", readEnded.await(WAIT_MS, TimeUnit.MILLISECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(emptyList<Throwable>(), thrown)
        } finally {
            main.uncaughtExceptionHandler = handlerWas
        }
    }

    // 外观 on the back stack with its tab's view gone: the old list no longer observes the kept adapter (it would leak the Activity)
    @Test
    fun theListOfAViewGoneIsNoObserverOfTheAdapter() {
        SettingsHarness().use { st ->
            st.open(SettingsRoute.Theme)
            st.tab(2)
            val page = st.page.childFragmentManager.fragments.filterIsInstance<LayoutListFragment>().single()
            val adapter = page.requireView().all().filterIsInstance<RecyclerView>().single().adapter!!
            assertTrue(adapter.hasObservers())
            st.open(SettingsRoute.About)
            assertNull(page.view)
            assertFalse(adapter.hasObservers())
        }
    }

    // grouped keys based on the full keyboard previewed as the keyboard draws them (SceneLayouts), on the base board's styles
    @Test
    @Uat("ST-192")
    fun aCardBasedOnAnotherFamilysBoardIsPreviewedAsTheKeyboardDrawsIt() {
        val k18 = BuiltInLayouts(appContext).text("k18")!!
        // the page's folder, not the keyboards' of this class's other tests
        val listed = CustomLayouts.dir()
        CustomLayouts.save("cross", k18.replace("name=\"18 键\"", "name=\"跨\"").replaceFirst("id=\"k18\"", "id=\"cross\" based-on=\"qwerty\""), listed)
        try {
            preview()
        } finally {
            listed.listFiles().orEmpty().filter { it.name.startsWith("cross.") }.forEach { it.delete() }
        }
    }

    private fun preview() {
        SettingsHarness().use { st ->
            st.open(SettingsRoute.Theme)
            st.tab(2)
            val page = st.page.childFragmentManager.fragments.filterIsInstance<LayoutListFragment>().single()
            fun card() = page.shownItems.filterIsInstance<LayoutListFragment.Item.Card>().firstOrNull { it.id == "cross" }
            val until = System.currentTimeMillis() + WAIT_MS
            while (card() == null && System.currentTimeMillis() < until) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(POLL_MS)
            }
            val scene = card()!!.scene!!
            assertEquals("styles and marks of, drawn in its place", listOf("qwerty", "k18"), listOf(scene.base, scene.fallback))
        }
    }

    private fun View.all(): Sequence<View> =
        sequenceOf(this) + ((this as? ViewGroup)?.let { g -> (0 until g.childCount).asSequence().flatMap { g.getChildAt(it).all() } } ?: emptySequence())

    private companion object {
        const val WAIT_MS = 3000L
        const val POLL_MS = 20L
    }
}
