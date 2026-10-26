/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.core.FakeFcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The settings app on a [FakeFcitxAPI], as [SettingsUatTest] has it: pages opened by their route,
 * read through their preferences. Engine config a page builds from is put in [api] first
 * (`setImConfig`, `setGlobalConfig`, `setAddonSubConfig`); [intent], what it is opened with. [close] at the end: the daemon seam is global.
 */
class SettingsHarness(val api: FakeFcitxAPI = FakeFcitxAPI(), private val intent: android.content.Intent? = null) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // put back at close: a keyboard harness still open beside this one keeps its own (TB-18)
    private val before = FcitxDaemon.testConnection

    val activity: MainActivity by lazy {
        FcitxDaemon.testConnection = { FakeFcitxConnection(scope, api) }
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().get().also { idle() }
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    fun s(id: Int, vararg args: Any): String = activity.getString(id, *args)

    private val nav get() = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

    fun open(route: SettingsRoute) {
        nav.navController.navigate(route)
        idle()
    }

    /** The page's tab [at] (外观's 主题 / 大小与样式), its pages made as a swipe to it would. */
    fun tab(at: Int) {
        KeyboardHarness.all(activity.window.decorView).filterIsInstance<androidx.viewpager2.widget.ViewPager2>().first().setCurrentItem(at, false)
        idle()
        activity.window.decorView.let {
            it.measure(View.MeasureSpec.makeMeasureSpec(it.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(it.height, View.MeasureSpec.EXACTLY))
            it.layout(0, 0, it.width, it.height)
        }
        idle()
    }

    val page: Fragment get() = nav.childFragmentManager.primaryNavigationFragment!!

    private fun fragments(fm: FragmentManager): List<Fragment> = fm.fragments.flatMap { listOf(it) + fragments(it.childFragmentManager) }

    /** Every preference shown on the page, in the pages under it too (the tabs of 外观). */
    fun preferences(): List<Preference> = (listOf(page) + fragments(page.childFragmentManager))
        .filterIsInstance<PreferenceFragmentCompat>()
        .flatMap { f -> f.preferenceScreen?.let(::preferences).orEmpty() }

    private fun preferences(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).flatMap { i ->
            val p = group.getPreference(i)
            listOf(p) + ((p as? PreferenceGroup)?.let(::preferences) ?: emptyList())
        }.filter { it.isVisible }

    fun titles() = preferences().map { it.title.toString() }

    fun pref(title: String): Preference = preferences().firstOrNull { it.title.toString() == title }
        ?: error("no $title in ${titles()}")

    fun pref(title: Int) = pref(s(title))

    /** Every text shown in the activity's window. */
    fun texts(): List<String> = KeyboardHarness.all(activity.window.decorView)
        .filter { it.visibility == View.VISIBLE && it.isShown }
        .filterIsInstance<TextView>().map { it.text.toString() }.toList()

    fun capture(path: String) {
        idle()
        activity.window.decorView.captureRoboImage(
            "src/test/screenshots/$path.png",
            RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)),
        )
    }

    override fun close() {
        FcitxDaemon.testConnection = before
        scope.cancel()
    }
}
