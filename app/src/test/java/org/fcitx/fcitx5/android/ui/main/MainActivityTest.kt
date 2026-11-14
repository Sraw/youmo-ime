/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.Navigator
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * MainActivity is exported: another app's intent must not open any page through the deep-link
 * extras Navigation acts on when the graph is set, while the route the keyboard sends still arrives.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @Navigator.Name("test")
    private class TestNavigator : Navigator<NavDestination>() {
        override fun createDestination() = NavDestination(this)
    }

    @Test
    fun deepLinksAreDroppedAndTheRestKept() {
        val intent = Intent(Intent.ACTION_RUN)
            .putExtra(NavController.KEY_DEEP_LINK_IDS, intArrayOf(GRAPH, PAGE))
            .putParcelableArrayListExtra(NavController.KEY_DEEP_LINK_ARGS, arrayListOf(Bundle(), Bundle()))
            .putExtra(NavController.KEY_DEEP_LINK_EXTRAS, Bundle())
            .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.Keys)
        intent.dropDeepLinks()
        assertFalse(intent.hasExtra(NavController.KEY_DEEP_LINK_IDS))
        assertFalse(intent.hasExtra(NavController.KEY_DEEP_LINK_ARGS))
        assertFalse(intent.hasExtra(NavController.KEY_DEEP_LINK_EXTRAS))
        assertTrue(intent.hasExtra(MainActivity.EXTRA_SETTINGS_ROUTE))
        assertEquals(Intent.ACTION_RUN, intent.action)
    }

    // fails if a Navigation upgrade reads a deep link from extras dropDeepLinks leaves
    @Test
    fun navigationFollowsNoDeepLinkOnceDropped() {
        val controller = NavHostController(RuntimeEnvironment.getApplication())
        val navigator = TestNavigator()
        controller.navigatorProvider.addNavigator(navigator)
        controller.setGraph(
            NavGraph(controller.navigatorProvider.getNavigator(NavGraphNavigator::class.java)).apply {
                id = GRAPH
                addDestination(navigator.createDestination().apply { id = HOME })
                addDestination(navigator.createDestination().apply { id = PAGE })
                setStartDestination(HOME)
            },
            null
        )
        val intent = Intent(Intent.ACTION_RUN).putExtra(NavController.KEY_DEEP_LINK_IDS, intArrayOf(GRAPH, PAGE))
        assertTrue(controller.handleDeepLink(Intent(intent)))
        intent.dropDeepLinks()
        assertFalse(controller.handleDeepLink(intent))
    }

    private companion object {
        const val GRAPH = 1
        const val HOME = 2
        const val PAGE = 3
    }

    @Test
    fun aFileIntoTheAppsOwnDirectoriesIsNotOpenedFromOutside() {
        val ctx = RuntimeEnvironment.getApplication()
        fun file(f: File) = Uri.fromFile(f)
        assertTrue(MainActivity.importable(ctx, Uri.parse("content://downloads/1")))
        assertTrue(MainActivity.importable(ctx, file(File("/sdcard/Download/words.scel"))))
        assertFalse(MainActivity.importable(ctx, file(File(ctx.filesDir, "before-restore.fxlb"))))
        assertFalse(MainActivity.importable(ctx, file(File(ctx.createDeviceProtectedStorageContext().dataDir, "files/x"))))
        assertFalse(MainActivity.importable(ctx, file(File(ctx.getExternalFilesDir(null), "x"))))
        // a path that climbs into them is taken where it leads: from a directory there is (on the JVM too, where
        // /sdcard is not, and a missing one is climbed out of by name, past a link such as macOS's /var)
        assertFalse(MainActivity.importable(ctx, Uri.parse("file:///usr/../" + ctx.filesDir.path.trimStart('/') + "/x")))
        assertFalse(MainActivity.importable(ctx, Uri.parse("http://example.com/x.txt")))
    }
}
