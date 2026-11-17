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
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.forgetTheTestsLayouts
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

/** 外观 → 布局 (LayoutListFragment): its cards read on IO, its list let go of with its view. */
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
        // a layout of the user's: a card made with its scene
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

    // R26 F8135: the page left (外观 closed, the screen turned) while its cards are read on IO, held by the start's listing
    // as after an upgrade: the cards made all the same, with no fragment to ask its context of, never the app's crash.
    // Waited for the read's end, not a time out (R26 F8490: a read that never ran passed as well); begun and not ended
    // as the page is left told by the read's own latches, not how it waits (R26 F8506)
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
                // the read begun as the page is left
                assertTrue("the read begun", readBegun.await(WAIT_MS, TimeUnit.MILLISECONDS))
            }
            // the page gone, the read not ended: held by the start's listing (awaitStart)
            assertEquals("the read waiting for the start", 1L, readEnded.count)
            // the start ends: the read goes on, the page gone, to its end
            held.removeFirst().run()
            assertTrue("the read ended", readEnded.await(WAIT_MS, TimeUnit.MILLISECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(emptyList<Throwable>(), thrown)
        } finally {
            main.uncaughtExceptionHandler = handlerWas
        }
    }

    // R26 F8403: 外观 kept on the back stack, its tab's view gone and the fragment kept: the old list no observer of the
    // adapter the fragment keeps (held, Activity and all, and told of each reload)
    @Test
    fun theListOfAViewGoneIsNoObserverOfTheAdapter() {
        SettingsHarness().use { st ->
            st.open(SettingsRoute.Theme)
            st.tab(2)
            val page = st.page.childFragmentManager.fragments.filterIsInstance<LayoutListFragment>().single()
            // the page's own, as its list has it
            val adapter = page.requireView().all().filterIsInstance<RecyclerView>().single().adapter!!
            assertTrue(adapter.hasObservers())
            st.open(SettingsRoute.About)
            assertNull(page.view)
            assertFalse(adapter.hasObservers())
        }
    }

    // R26 F8117: grouped keys based on the full keyboard (let in) previewed as the keyboard draws them (SceneLayouts):
    // the styles, marks and keys' data of the board it is based on, its own family's drawn only in its place; the
    // card's preview had its own family's base for both
    @Test
    @Uat("ST-192")
    fun aCardBasedOnAnotherFamilysBoardIsPreviewedAsTheKeyboardDrawsIt() {
        val k18 = BuiltInLayouts(appContext).text("k18")!!
        // in the folder the page lists (CustomLayouts.dir), not the keyboards' of this class's other tests
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
