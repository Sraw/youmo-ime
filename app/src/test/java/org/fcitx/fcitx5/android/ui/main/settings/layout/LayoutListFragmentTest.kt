/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.asCoroutineDispatcher
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.forgetTheTestsLayouts
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.uat.SettingsHarness
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
