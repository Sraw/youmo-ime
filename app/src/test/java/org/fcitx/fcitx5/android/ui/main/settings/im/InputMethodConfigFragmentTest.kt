/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.os.Looper
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.fragment.NavHostFragment
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class InputMethodConfigFragmentTest {

    // a retired page's route restored, popped before its move to 通用输入 runs: the move not done, no crash
    @Test
    fun theRetiredPageLeftBeforeItsMoveCrashesNothing() {
        SettingsHarness().use { st ->
            val nav = st.activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            nav.navController.navigate(SettingsRoute.InputMethodConfig("手写", InputMethodCatalog.HANDWRITING_RETIRED))
            val looper = shadowOf(Looper.getMainLooper())
            var page: InputMethodConfigFragment? = null
            repeat(MAX_TASKS) {
                if (page?.view == null) {
                    looper.runOneTask()
                    page = nav.childFragmentManager.fragments.filterIsInstance<InputMethodConfigFragment>().firstOrNull()
                }
            }
            assertNotNull("the page's view made", page?.view)
            val made = page!!
            nav.navController.popBackStack()
            nav.childFragmentManager.executePendingTransactions()
            assertFalse(made.isAdded)
            st.idle()
            // back where the page was opened from, not moved on to 通用输入
            val at = nav.navController.currentDestination!!
            assertFalse(at.hasRoute<SettingsRoute.SharedInput>())
            assertTrue(at.hasRoute<SettingsRoute.Index>())
        }
    }

    private companion object {
        const val MAX_TASKS = 1000
    }
}
