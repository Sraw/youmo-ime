/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
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
            .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.VirtualKeyboard)
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
}
